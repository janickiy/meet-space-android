package com.meetspace.app

import android.Manifest
import android.media.AudioFormat
import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
        val tone = AtomicBoolean(false)
        var phase = 0.0
        val adm = JavaAudioDeviceModule.builder(context).setAudioFormat(AudioFormat.ENCODING_PCM_16BIT)
            .setUseHardwareAcousticEchoCanceler(false).setUseHardwareNoiseSuppressor(false)
            .setAudioBufferCallback { buffer, format, channels, sampleRate, bytes, timestamp ->
                check(format == AudioFormat.ENCODING_PCM_16BIT && channels == 1)
                buffer.order(ByteOrder.nativeOrder())
                val loud = tone.get()
                for (index in 0 until minOf(bytes, buffer.capacity()) / 2) {
                    buffer.putShort(index * 2, if (loud) (7000 * sin(phase)).toInt().toShort() else 0)
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
            sender = checkNotNull(factory.createPeerConnection(config, Observer()))
            receiver = checkNotNull(factory.createPeerConnection(config, Observer()))
            source = factory.createAudioSource(MediaConstraints().apply {
                mandatory += MediaConstraints.KeyValuePair("googEchoCancellation", "false")
                mandatory += MediaConstraints.KeyValuePair("googAutoGainControl", "false")
                mandatory += MediaConstraints.KeyValuePair("googNoiseSuppression", "false")
            })
            track = factory.createAudioTrack("synthetic-microphone", source)
            checkNotNull(sender.addTrack(track, listOf("synthetic-stream")))
            val offer = create(sender, offer = true)
            set(sender, offer, local = true)
            waitFor("offer ICE candidates") { sender.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE }
            // Exercise the same streamless SDP adaptation as CallEngine, retaining MID/track identity.
            val sdpType = Class.forName("com.meetspace.core.media.RemoteSdpTracks")
            val prepared = sdpType.getMethod("prepare", String::class.java).invoke(sdpType.getField("INSTANCE").get(null), sender.localDescription.description)
            val description = prepared.javaClass.getMethod("getDescription").invoke(prepared) as String
            set(receiver, SessionDescription(SessionDescription.Type.OFFER, description), local = false)
            set(receiver, create(receiver, offer = false), local = true)
            waitFor("answer ICE candidates") { receiver.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE }
            set(sender, receiver.localDescription, local = false)
            waitFor("local SRTP connection") { sender.connectionState() == PeerConnection.PeerConnectionState.CONNECTED && receiver.connectionState() == PeerConnection.PeerConnectionState.CONNECTED }
            val transceiver = receiver.transceivers.single { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO }
            val mid = checkNotNull(transceiver.mid)
            assertEquals("synthetic-microphone", transceiver.receiver.track()?.id())
            val bindingClass = Class.forName("com.meetspace.core.media.CatalogTrack")
            val binding = bindingClass.getConstructor(String::class.java, String::class.java, String::class.java, String::class.java, String::class.java)
                .newInstance("synthetic-microphone", "synthetic-stream", "remote", "microphone", "audio")
            val activityClass = Class.forName("com.meetspace.core.media.SpeakingActivity")
            val localActivity = activityClass.getConstructor().newInstance()
            val remoteActivity = activityClass.getConstructor().newInstance()
            val update = activityClass.getMethod("update", RTCStatsReport::class.java, java.lang.Long.TYPE, String::class.java, String::class.java, Map::class.java, Set::class.java)
            fun active(owner: Any, peer: PeerConnection, local: Boolean): Set<*> {
                val report = stats(peer)
                return update.invoke(owner, report, SystemClock.elapsedRealtime(), if (local) "self" else "unused",
                    if (local) "synthetic-microphone" else null, if (local) emptyMap<String, Any>() else mapOf(mid to binding),
                    setOf(if (local) "self" else "remote")) as Set<*>
            }
            assertTrue(active(localActivity, sender, true).isEmpty())
            assertTrue(active(remoteActivity, receiver, false).isEmpty())
            tone.set(true)
            waitFor("native local and received tone") {
                val local = active(localActivity, sender, true)
                val remote = active(remoteActivity, receiver, false)
                "self" in local && "remote" in remote
            }
            tone.set(false)
            waitFor("native silence clears both highlights") {
                active(localActivity, sender, true).isEmpty() && active(remoteActivity, receiver, false).isEmpty()
            }
            activityClass.getMethod("clear").invoke(localActivity)
            activityClass.getMethod("clear").invoke(remoteActivity)
        } finally {
            tone.set(false)
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
