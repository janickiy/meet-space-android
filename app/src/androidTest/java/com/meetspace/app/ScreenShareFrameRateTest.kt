package com.meetspace.app

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import com.meetspace.core.media.ScreenShareVideoPolicy
import java.io.Closeable
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * Real bundled libwebrtc, VP8 encoding/decoding and RTP counters; synthetic high-rate I420 input.
 * This intentionally does not claim MediaProjection, a high-refresh display or server recording QA.
 */
class ScreenShareFrameRateTest {
    @Test fun nativeScreenSourceCapsEncodedFramesAcrossResizeRestartAndNewPeer() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .setInjectableLogger({ _, _, _ -> }, Logging.Severity.LS_NONE)
            .createInitializationOptions())
        val audio = JavaAudioDeviceModule.builder(context).createAudioDeviceModule()
        audio.setAudioRecordEnabled(false)
        val factory = PeerConnectionFactory.builder().setAudioDeviceModule(audio)
            .setVideoEncoderFactory(SoftwareVideoEncoderFactory())
            .setVideoDecoderFactory(SoftwareVideoDecoderFactory())
            .createPeerConnectionFactory()
        val measurements = JSONArray()
        val evidence = JSONObject().put("sdk", Build.VERSION.SDK_INT)
            .put("webrtcArtifact", "io.github.webrtc-sdk:android:144.7559.15")
            .put("input", "synthetic animated I420 at requested 144 fps")
            .put("encoder", "native software VP8")
            .put("actualMediaProjection", false).put("physicalHighRefreshDevice", false)
            .put("serverRecording", false).put("success", false).put("measurements", measurements)
        try {
            // Control proves the same native peer/producer can exceed the requested screen limit.
            Loopback(factory).use { loop ->
                Producer(factory, limited = false).use { input ->
                    try {
                        loop.connect(input.track)
                        input.start()
                        loop.waitForDimensions(Size(320, 180))
                        val control = loop.measure("uncapped-control", input, 4_000)
                        measurements.put(control)
                        assertTrue("Control must encode >20 fps so a slow producer cannot mask the bug: $control",
                            control.getDouble("encodedFps") > 20.0)
                    } finally { loop.detach() }
                }
            }
            Loopback(factory).use { loop ->
                Producer(factory, limited = true).use { input ->
                    try {
                        loop.connect(input.track)
                        input.start()
                        loop.waitForDimensions(Size(320, 180))
                        assertCapped(loop.measure("source-only-landscape", input, 5_000).also { measurements.put(it) })
                        assertTrue(ScreenShareVideoPolicy.applySenderLimit(loop.sender))
                        input.resize(Size(180, 320))
                        loop.waitForDimensions(Size(180, 320))
                        assertCapped(loop.measure("source-and-sender-portrait-after-resize", input, 5_000).also { measurements.put(it) })
                    } finally { loop.stopPublication() }
                }
                measurements.put(loop.assertStopped("stopped-before-restart"))
                // New source + new track on the retained peer represents stop/restart without reuse.
                Producer(factory, limited = true).use { restarted ->
                    try {
                        loop.publish(restarted.track)
                        restarted.start()
                        loop.waitForDimensions(Size(320, 180))
                        assertCapped(loop.measure("limited-new-source-and-track", restarted, 5_000).also { measurements.put(it) })
                    } finally { loop.detach() }
                }
                evidence.put("restartSenderLimitApplications", loop.senderLimitApplications)
            }
            // A recreated connection cannot depend on parameters retained by an old sender.
            Loopback(factory).use { reconnected ->
                Producer(factory, limited = true).use { input ->
                    try {
                        reconnected.connectReceiveOnly()
                        reconnected.publish(input.track)
                        input.start()
                        reconnected.waitForDimensions(Size(320, 180))
                        assertCapped(reconnected.measure("limited-new-peer", input, 5_000).also { measurements.put(it) })
                    } finally { reconnected.detach() }
                }
                evidence.put("newPeerSenderLimitApplications", reconnected.senderLimitApplications)
            }
            evidence.put("success", true)
        } finally {
            factory.dispose()
            audio.release()
            File(context.cacheDir, "screen-share-native-fps.json").writeText(evidence.toString(2))
        }
    }

    private fun assertCapped(sample: JSONObject) {
        assertTrue("Synthetic input must remain substantially faster than the cap: $sample", sample.getDouble("submittedFps") >= 60.0)
        assertTrue("VP8 must actually encode frames: $sample", sample.getDouble("encodedFps") >= 8.0)
        val seconds = sample.getDouble("seconds")
        val maximum = ceil(seconds * ScreenShareVideoPolicy.MAX_FPS).toLong() + 2L
        assertTrue("outbound-rtp.framesEncoded exceeded 15 fps (two frames allowed at sample boundaries): $sample",
            sample.getLong("framesEncodedDelta") <= maximum)
    }

    private data class Size(val width: Int, val height: Int)

    private class Producer(factory: PeerConnectionFactory, limited: Boolean) : Closeable {
        private val source = factory.createVideoSource(true).also { if (limited) ScreenShareVideoPolicy.apply(it) }
        val track: VideoTrack = factory.createVideoTrack("screen-test-${UUID.randomUUID()}", source)
        val submitted = AtomicLong()
        private val size = AtomicReference(Size(320, 180))
        private val error = AtomicReference<Throwable?>()
        private val executor = Executors.newSingleThreadScheduledExecutor { Thread(it, "Screen-FPS-test-input") }
        private var started = false

        fun start() {
            check(!started)
            started = true
            source.capturerObserver.onCapturerStarted(true)
            // Test producer cadence only: the production cap uses native VideoSource adaptation.
            executor.scheduleAtFixedRate({
                try {
                    val dimensions = size.get()
                    val sequence = submitted.incrementAndGet()
                    val buffer = JavaI420Buffer.allocate(dimensions.width, dimensions.height)
                    val frame = VideoFrame(buffer, 0, System.nanoTime())
                    try {
                        val y = ByteArray(dimensions.width * dimensions.height) { index ->
                            (32 + ((index / dimensions.width + index % dimensions.width + sequence * 3) % 192)).toByte()
                        }
                        buffer.dataY.put(y)
                        val chroma = ByteArray((dimensions.width / 2) * (dimensions.height / 2)) { 128.toByte() }
                        buffer.dataU.put(chroma); buffer.dataV.put(chroma)
                        // The observer retains anything it needs; this producer releases its own reference once.
                        source.capturerObserver.onFrameCaptured(frame)
                    } finally { frame.release() }
                } catch (failure: Throwable) { error.compareAndSet(null, failure) }
            }, 0, 1_000_000_000L / 144L, TimeUnit.NANOSECONDS)
        }

        fun resize(value: Size) {
            // Mirrors reapplication on the existing source when capture dimensions change.
            ScreenShareVideoPolicy.apply(source)
            size.set(value)
        }
        fun assertHealthy() { error.get()?.let { throw AssertionError("Synthetic producer failed", it) } }
        override fun close() {
            executor.shutdownNow()
            check(executor.awaitTermination(5, TimeUnit.SECONDS)) { "Producer must stop before source disposal" }
            if (started) source.capturerObserver.onCapturerStopped()
            track.dispose()
            source.dispose()
        }
    }

    private class Loopback(private val factory: PeerConnectionFactory) : Closeable {
        private val decoded = AtomicReference<Size?>()
        private val localIce = mutableListOf<IceCandidate>()
        private val remoteIce = mutableListOf<IceCandidate>()
        private val lock = Any()
        private val iceExecutor = Executors.newSingleThreadExecutor { Thread(it, "Screen-FPS-test-ICE") }
        private val closing = AtomicBoolean()
        private var descriptionsSet = false
        private val left: PeerConnection
        private val right: PeerConnection
        private lateinit var transceiver: RtpTransceiver
        lateinit var sender: RtpSender
            private set
        val senderLimitApplications = JSONArray()

        init {
            val config = PeerConnection.RTCConfiguration(emptyList()).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            }
            left = checkNotNull(factory.createPeerConnection(config, object : EmptyObserver() {
                override fun onIceCandidate(candidate: IceCandidate) = forwardIce(candidate, toRight = true)
            }))
            right = checkNotNull(factory.createPeerConnection(config, object : EmptyObserver() {
                override fun onIceCandidate(candidate: IceCandidate) = forwardIce(candidate, toRight = false)
                override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
                    (receiver.track() as? VideoTrack)?.addSink { frame -> decoded.set(Size(frame.rotatedWidth, frame.rotatedHeight)) }
                }
            }))
            left.setAudioPlayout(false); left.setAudioRecording(false)
            right.setAudioPlayout(false); right.setAudioRecording(false)
        }

        fun connect(track: VideoTrack) {
            transceiver = left.addTransceiver(track, RtpTransceiver.RtpTransceiverInit(
                RtpTransceiver.RtpTransceiverDirection.SEND_ONLY, listOf("screen-test")))
            prepareVideo()
            negotiate()
        }

        fun connectReceiveOnly() {
            transceiver = left.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY, listOf("screen-test")))
            prepareVideo()
            negotiate()
        }

        private fun prepareVideo() {
            val vp8 = factory.getRtpReceiverCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO).codecs
                .filter { it.name.equals("VP8", ignoreCase = true) }
            check(vp8.isNotEmpty())
            transceiver.setCodecPreferences(vp8)
            sender = transceiver.sender
        }

        fun publish(track: VideoTrack) {
            decoded.set(null)
            track.setEnabled(false)
            assertTrue(sender.setTrack(track, false))
            val initial = ScreenShareVideoPolicy.applySenderLimit(sender)
            senderLimitApplications.put(JSONObject().put("stage", "before-answer").put("applied", initial)
                .put("encodingCount", sender.parameters.encodings.size))
            transceiver.direction = RtpTransceiver.RtpTransceiverDirection.SEND_RECV
            if (initial) track.setEnabled(true)
            negotiate()
            assertTrue("An SDP answer must restore a send encoding and retain its checked limit",
                ScreenShareVideoPolicy.applySenderLimit(sender))
            senderLimitApplications.put(JSONObject().put("stage", "after-answer").put("applied", true)
                .put("encodingCount", sender.parameters.encodings.size))
            track.setEnabled(true)
        }

        fun stopPublication() {
            detach()
            transceiver.direction = RtpTransceiver.RtpTransceiverDirection.RECV_ONLY
            negotiate()
        }

        fun assertStopped(label: String): JSONObject {
            CountDownLatch(1).await(500, TimeUnit.MILLISECONDS) // Allow the encoder's last pending work to drain.
            val before = stats()
            CountDownLatch(1).await(1_000, TimeUnit.MILLISECONDS)
            val after = stats()
            val frames = encoded(after) - encoded(before)
            val sample = JSONObject().put("phase", label).put("seconds", (after.timestampUs - before.timestampUs) / 1_000_000.0)
                .put("framesEncodedDelta", frames)
            assertTrue("A stopped publication must not continue encoding RTP frames: $sample", frames <= 1L)
            return sample
        }

        private fun negotiate() {
            val offer = createDescription(left, offer = true)
            setDescription(left, offer, local = true); setDescription(right, offer, local = false)
            val answer = createDescription(right, offer = false)
            setDescription(right, answer, local = true); setDescription(left, answer, local = false)
            val queued = synchronized(lock) {
                descriptionsSet = true
                (localIce.map { it to true } + remoteIce.map { it to false }).also {
                    localIce.clear(); remoteIce.clear()
                }
            }
            queued.forEach { (candidate, toRight) -> forwardIce(candidate, toRight) }
        }

        private fun forwardIce(candidate: IceCandidate, toRight: Boolean) {
            val deliver = synchronized(lock) {
                if (closing.get()) false
                else if (!descriptionsSet) {
                    (if (toRight) localIce else remoteIce).add(candidate)
                    false
                } else true
            }
            if (deliver) try {
                iceExecutor.execute {
                    // JNI never executes while the Java queue lock is held or inside a peer callback.
                    if (!closing.get()) (if (toRight) right else left).addIceCandidate(candidate)
                }
            } catch (failure: java.util.concurrent.RejectedExecutionException) {
                if (!closing.get()) throw failure
            }
        }

        fun waitForDimensions(expected: Size) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (System.nanoTime() < deadline) {
                if (decoded.get() == expected) return
                CountDownLatch(1).await(50, TimeUnit.MILLISECONDS)
            }
            assertEquals("Remote decoder must preserve the captured aspect and pixel dimensions", expected, decoded.get())
        }

        fun measure(label: String, input: Producer, millis: Long): JSONObject {
            input.assertHealthy()
            val before = stats()
            val submittedBefore = input.submitted.get()
            CountDownLatch(1).await(millis, TimeUnit.MILLISECONDS)
            val after = stats()
            val submittedAfter = input.submitted.get()
            input.assertHealthy()
            val seconds = (after.timestampUs - before.timestampUs) / 1_000_000.0
            val frames = encoded(after) - encoded(before)
            return JSONObject().put("phase", label).put("seconds", seconds)
                .put("framesEncodedBefore", encoded(before)).put("framesEncodedAfter", encoded(after))
                .put("framesEncodedDelta", frames).put("encodedFps", frames / seconds)
                .put("submittedFramesDelta", submittedAfter - submittedBefore)
                .put("submittedFps", (submittedAfter - submittedBefore) / seconds)
                .put("decodedWidth", decoded.get()?.width).put("decodedHeight", decoded.get()?.height)
                .put("outboundRtp", JSONArray(after.statsMap.values
                    .filter { it.type == "outbound-rtp" && it.members["kind"] == "video" }
                    .map { JSONObject(it.members).put("id", it.id) }))
        }

        private fun stats(): RTCStatsReport {
            val future = CompletableFuture<RTCStatsReport>()
            left.getStats { future.complete(it) }
            return future.get(5, TimeUnit.SECONDS)
        }
        private fun encoded(report: RTCStatsReport) = report.statsMap.values
            .filter { it.type == "outbound-rtp" && it.members["kind"] == "video" }
            .sumOf { (it.members["framesEncoded"] as? Number)?.toLong() ?: 0L }
        fun detach() {
            if (::sender.isInitialized) assertTrue("Stop must detach the sender before releasing its source", sender.setTrack(null, false))
        }
        override fun close() {
            closing.set(true)
            iceExecutor.shutdown()
            check(iceExecutor.awaitTermination(5, TimeUnit.SECONDS)) { "ICE forwarding must stop before peer disposal" }
            left.close(); right.close(); left.dispose(); right.dispose()
        }
    }

    private abstract class EmptyObserver : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidate(candidate: IceCandidate) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(channel: DataChannel) { channel.close(); channel.dispose() }
        override fun onRenegotiationNeeded() = Unit
    }

    private companion object {
        fun createDescription(peer: PeerConnection, offer: Boolean): SessionDescription {
            val future = CompletableFuture<SessionDescription>()
            val observer = object : EmptySdpObserver() {
                override fun onCreateSuccess(description: SessionDescription) { future.complete(description) }
                override fun onCreateFailure(error: String) { future.completeExceptionally(AssertionError(error)) }
            }
            if (offer) peer.createOffer(observer, MediaConstraints()) else peer.createAnswer(observer, MediaConstraints())
            return future.get(10, TimeUnit.SECONDS)
        }
        fun setDescription(peer: PeerConnection, description: SessionDescription, local: Boolean) {
            val future = CompletableFuture<Unit>()
            val observer = object : EmptySdpObserver() {
                override fun onSetSuccess() { future.complete(Unit) }
                override fun onSetFailure(error: String) { future.completeExceptionally(AssertionError(error)) }
            }
            if (local) peer.setLocalDescription(observer, description) else peer.setRemoteDescription(observer, description)
            future.get(10, TimeUnit.SECONDS)
        }
    }

    private abstract class EmptySdpObserver : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String) = Unit
        override fun onSetFailure(error: String) = Unit
    }
}
