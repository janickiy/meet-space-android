package com.meetspace.app

import android.Manifest
import android.media.AudioFormat
import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule

/** Real M144 capture → Opus → local ICE/DTLS/SRTP → decode → production activity detector.
 * Emulator-only: replace every borrowed capture buffer with a synthetic tone or zeroes;
 * never pass host/user microphone samples through, retain buffers or contact a server. */
class SpeakingStatsNativeTest {
    @Test fun syntheticToneAndSilenceDriveLocalAndRemoteSdkStatistics() {
        assumeTrue("Synthetic audio transport test runs only on the disposable emulator",
            Build.FINGERPRINT.startsWith("generic") || Build.MODEL.contains("sdk", ignoreCase = true))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        val amplitude = AtomicInteger(0)
        var phase = 0.0
        val adm = JavaAudioDeviceModule.builder(context).setAudioFormat(AudioFormat.ENCODING_PCM_16BIT)
            .setUseHardwareAcousticEchoCanceler(false).setUseHardwareNoiseSuppressor(false)
            .setAudioBufferCallback { buffer, format, channels, sampleRate, bytes, timestamp ->
                check(format == AudioFormat.ENCODING_PCM_16BIT && channels == 1)
                buffer.order(ByteOrder.nativeOrder())
                val strength = amplitude.get()
                for (index in 0 until minOf(bytes, buffer.capacity()) / 2) {
                    buffer.putShort(index * 2, (strength * sin(phase)).toInt().toShort())
                    phase = (phase + 2 * PI * 440 / sampleRate) % (2 * PI)
                }
                timestamp
            }.createAudioDeviceModule()
        var factory: PeerConnectionFactory? = null
        var source: AudioSource? = null
        var track: AudioTrack? = null
        var sender: PeerConnection? = null
        var receiver: PeerConnection? = null
        try {
            adm.setSpeakerMute(true)
            factory = PeerConnectionFactory.builder().setAudioDeviceModule(adm).createPeerConnectionFactory()
            val config = PeerConnection.RTCConfiguration(emptyList()).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN }
            val sendingPeer = checkNotNull(factory.createPeerConnection(config, Observer())).also { sender = it }
            val receivingPeer = checkNotNull(factory.createPeerConnection(config, Observer())).also { receiver = it }
            source = factory.createAudioSource(MediaConstraints().apply {
                mandatory += MediaConstraints.KeyValuePair("googEchoCancellation", "false")
                mandatory += MediaConstraints.KeyValuePair("googAutoGainControl", "false")
                mandatory += MediaConstraints.KeyValuePair("googNoiseSuppression", "false")
            })
            // CallEngine creates receive slots before the SFU advertises a participant's track.
            val receiveSlot = checkNotNull(receivingPeer.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)))
            val nativeTrackId = checkNotNull(receiveSlot.receiver.track()).id()
            val sdpType = Class.forName("com.meetspace.core.media.RemoteSdpTracks")
            fun negotiate(): Any {
                set(receivingPeer, create(receivingPeer, offer = true), local = true)
                waitFor("offer ICE candidates") { receivingPeer.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE }
                set(sendingPeer, receivingPeer.localDescription, local = false)
                set(sendingPeer, create(sendingPeer, offer = false), local = true)
                waitFor("answer ICE candidates") { sendingPeer.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE }
                val prepared = checkNotNull(sdpType.getMethod("prepare", String::class.java)
                    .invoke(sdpType.getField("INSTANCE").get(null), sendingPeer.localDescription.description))
                val description = prepared.javaClass.getMethod("getDescription").invoke(prepared) as String
                set(receivingPeer, SessionDescription(SessionDescription.Type.ANSWER, description), local = false)
                return prepared
            }
            negotiate()
            val mid = checkNotNull(receiveSlot.mid)
            track = factory.createAudioTrack("synthetic-microphone", source)
            val sendSlot = sendingPeer.transceivers.single { it.mid == mid }
            assertTrue(sendSlot.sender.setTrack(track, false))
            sendSlot.sender.setStreams(listOf("synthetic-stream"))
            sendSlot.direction = RtpTransceiver.RtpTransceiverDirection.SEND_ONLY
            val prepared = negotiate()
            waitFor("local SRTP connection") {
                sendingPeer.connectionState() == PeerConnection.PeerConnectionState.CONNECTED && receivingPeer.connectionState() == PeerConnection.PeerConnectionState.CONNECTED
            }
            assertEquals(mid, receiveSlot.mid)
            val playout = receiveSlot.receiver.track() as AudioTrack
            assertEquals(nativeTrackId, playout.id())
            val tracksByMid = prepared.javaClass.getMethod("getTracksByMid").invoke(prepared) as Map<*, *>
            val remoteTrack = checkNotNull(tracksByMid[mid])
            assertEquals("synthetic-stream", remoteTrack.javaClass.getMethod("getStreamId").invoke(remoteTrack))
            val advertisedTrackId = remoteTrack.javaClass.getMethod("getTrackId").invoke(remoteTrack) as String
            assertNotEquals("The receiving track keeps its locally generated ID", advertisedTrackId, nativeTrackId)
            val advertisedSsrcs = remoteTrack.javaClass.getMethod("getSsrcs").invoke(remoteTrack) as Set<*>
            for (gain in listOf(0.0, .25, 1.0)) {
                playout.setVolume(gain)
                assertEquals("Received audio uses the local speaker gain", gain, playout.volume, .00001)
            }
            val bindingClass = Class.forName("com.meetspace.core.media.ReceivedMicrophone")
            val binding = bindingClass.getConstructor(String::class.java, String::class.java, Set::class.java)
                .newInstance("remote", nativeTrackId, advertisedSsrcs)
            val activityClass = Class.forName("com.meetspace.core.media.SpeakingActivity")
            val localActivity = activityClass.getConstructor().newInstance()
            val remoteActivity = activityClass.getConstructor().newInstance()
            val update = activityClass.getMethod("update", RTCStatsReport::class.java, java.lang.Long.TYPE, String::class.java, String::class.java, Map::class.java, Set::class.java)
            val audioLevels = activityClass.getMethod("audioLevels", java.lang.Long.TYPE, Set::class.java)
            fun level(owner: Any, id: String): Float = ((audioLevels.invoke(owner, SystemClock.elapsedRealtime(), setOf(id)) as Map<*, *>)[id] as? Number)?.toFloat() ?: 0f
            fun active(owner: Any, peer: PeerConnection, local: Boolean): Set<*> {
                val report = stats(peer)
                return update.invoke(owner, report, SystemClock.elapsedRealtime(), if (local) "self" else "unused",
                    if (local) "synthetic-microphone" else null, if (local) emptyMap<String, Any>() else mapOf(mid to binding),
                    setOf(if (local) "self" else "remote")) as Set<*>
            }
            assertTrue(active(localActivity, sendingPeer, true).isEmpty())
            assertTrue(active(remoteActivity, receivingPeer, false).isEmpty())
            amplitude.set(3000)
            waitFor("received stats report the native ID on the negotiated MID") {
                stats(receivingPeer).statsMap.values.any {
                    val ssrc = (it.members["ssrc"] as? Number)?.toLong()
                    it.type == "inbound-rtp" && it.members["kind"] == "audio" &&
                        it.members["mid"] == mid && it.members["trackIdentifier"] == nativeTrackId &&
                        ssrc != null && (advertisedSsrcs.isEmpty() || ssrc in advertisedSsrcs)
                }
            }
            var quietLocal = 0f
            var quietRemote = 0f
            waitFor("native local and received tone") {
                val local = active(localActivity, sendingPeer, true)
                val remote = active(remoteActivity, receivingPeer, false)
                quietLocal = level(localActivity, "self"); quietRemote = level(remoteActivity, "remote")
                "self" in local && "remote" in remote && quietLocal > .025f && quietRemote > .025f
            }
            amplitude.set(12000)
            waitFor("louder tone raises measured local and received volume") {
                active(localActivity, sendingPeer, true); active(remoteActivity, receivingPeer, false)
                level(localActivity, "self") > quietLocal * 1.6f && level(remoteActivity, "remote") > quietRemote * 1.6f
            }
            amplitude.set(0)
            waitFor("native silence clears both highlights") {
                active(localActivity, sendingPeer, true).isEmpty() && active(remoteActivity, receivingPeer, false).isEmpty() &&
                    // Opus may retain inaudible comfort noise; it must remain below the UI floor.
                    level(localActivity, "self") < .012f && level(remoteActivity, "remote") < .012f
            }
            activityClass.getMethod("clear").invoke(localActivity)
            activityClass.getMethod("clear").invoke(remoteActivity)
        } finally {
            amplitude.set(0)
            sender?.close(); receiver?.close()
            sender?.dispose(); receiver?.dispose()
            track?.dispose(); source?.dispose(); factory?.dispose(); adm.release()
        }
    }

    private fun waitFor(label: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 12_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            Thread.sleep(200) // Test runner only; never the audio/capture callback.
        }
        fail("Timed out: $label")
    }
    private fun stats(peer: PeerConnection): RTCStatsReport {
        val result = AtomicReference<RTCStatsReport>()
        val done = CountDownLatch(1)
        peer.getStats { result.set(it); done.countDown() }
        assertTrue("Native stats callback", done.await(3, TimeUnit.SECONDS))
        return checkNotNull(result.get())
    }
    private fun create(peer: PeerConnection, offer: Boolean): SessionDescription {
        val result = AtomicReference<SessionDescription>()
        val error = AtomicReference<String>()
        val done = CountDownLatch(1)
        val observer = object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) { result.set(sdp); done.countDown() }
            override fun onCreateFailure(message: String) { error.set(message); done.countDown() }
            override fun onSetSuccess() = Unit
            override fun onSetFailure(message: String) = Unit
        }
        if (offer) peer.createOffer(observer, MediaConstraints()) else peer.createAnswer(observer, MediaConstraints())
        assertTrue(done.await(3, TimeUnit.SECONDS)); assertNull(error.get())
        return checkNotNull(result.get())
    }
    private fun set(peer: PeerConnection, sdp: SessionDescription, local: Boolean) {
        val error = AtomicReference<String>()
        val done = CountDownLatch(1)
        val observer = object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) = Unit
            override fun onCreateFailure(message: String) = Unit
            override fun onSetSuccess() { done.countDown() }
            override fun onSetFailure(message: String) { error.set(message); done.countDown() }
        }
        if (local) peer.setLocalDescription(observer, sdp) else peer.setRemoteDescription(observer, sdp)
        assertTrue(done.await(3, TimeUnit.SECONDS)); assertNull(error.get())
    }
    private class Observer : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidate(candidate: IceCandidate) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(channel: DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
    }
}
