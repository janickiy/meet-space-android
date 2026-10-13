package com.meetspace.app

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.projection.MediaProjectionManager
import android.media.projection.MediaProjectionConfig
import android.os.Build
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import com.meetspace.core.data.CallRepository
import com.meetspace.core.data.MeetingsRepository
import com.meetspace.core.media.CallEngine
import com.meetspace.core.media.CallDiagnostics
import com.meetspace.core.media.CallPhase
import com.meetspace.core.media.CallState
import com.meetspace.core.media.ScreenShareVideoPolicy
import java.io.Closeable
import java.io.File
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.webrtc.*

/**
 * Real system consent, foreground service, production CallEngine and MediaProjection on Android.
 * A local native VP8 loopback substitutes only backend/SDP setup. No server recording is created.
 * Test-only reflection supplies the negotiated call; this does not test production reconnection.
 */
class ScreenShareProjectionTest {
    @Test fun projectionCapsEncodedFramesAcrossRotationStopAndFreshConsentRestart() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as LiveCallApplication
        val device = UiDevice.getInstance(instrumentation)
        // Dismiss a chooser left by an earlier failed consent attempt before opening the test host.
        device.pressBack()
        device.pressHome()
        val measurements = JSONArray()
        val evidence = JSONObject().put("androidSdk", Build.VERSION.SDK_INT)
            .put("deviceModel", Build.MODEL).put("deviceFingerprint", Build.FINGERPRINT)
            .put("webrtcArtifact", "io.github.webrtc-sdk:android:144.7559.15")
            .put("actualMediaProjection", true).put("productionScreenShareService", true)
            .put("productionCallEngineCapture", true).put("transport", "native local VP8 loopback")
            .put("callSetup", "test-only reflection and pre-negotiated screen sender")
            .put("consentScope", "default display requested through the standard MediaProjectionConfig API")
            .put("physicalHighRefreshDevice", false).put("productionNetworkReconnect", false)
            .put("serverRecording", false).put("measurements", measurements).put("success", false)
        val engine = CallEngine(context, unavailable(CallRepository::class.java),
            unavailable(MeetingsRepository::class.java)) {
            context.stopService(Intent(context, ScreenShareService::class.java))
        }
        var loop: ProjectionLoopback? = null
        app.mediaProjectionTestEngine = engine
        try {
            actor(engine) {
                CallEngine::class.java.getDeclaredMethod("ensureFactory").apply { isAccessible = true }.invoke(engine)
            }
            loop = ProjectionLoopback(actor(engine) { field(engine, "factory") })
            val native = loop
            actor(engine) {
                setField(engine, "pc", native.senderPeer)
                setField(engine, "peerId", "projection-test-${UUID.randomUUID()}")
                field<MutableMap<String, RtpTransceiver>>(engine, "slots")["video/screen"] = native.transceiver
                // Holding a transaction bypasses network negotiation only; capture/publishing uses production code.
                val gate = field<Any>(engine, "gate")
                gate.javaClass.getDeclaredMethod("begin").apply { isAccessible = true }.invoke(gate)
                state(engine).value = CallState(phase = CallPhase.CONNECTED,
                    conferenceId = "projection-loopback-test", title = "Projection FPS test")
            }
            ActivityScenario.launch(ComponentActivity::class.java).use { activity ->
                rotate(activity, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
                activity.onActivity { host -> evidence.put("displayRefreshRate", host.display?.refreshRate) }
                startWithFreshConsent(activity, device, engine)
                val firstCaptureStarted = SystemClock.elapsedRealtime()
                native.waitForOrientation(portrait = true)
                assertCapped(native.measure("actual-projection-portrait", engine, 5_000).also { measurements.put(it) })
                rotate(activity, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
                native.waitForOrientation(portrait = false)
                assertCapped(native.measure("actual-projection-landscape-after-rotation", engine, 5_000).also { measurements.put(it) })
                rotate(activity, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
                native.waitForOrientation(portrait = true)
                assertCapped(native.measure("actual-projection-portrait-after-second-rotation", engine, 5_000).also { measurements.put(it) })
                val remaining = 31_000L - (SystemClock.elapsedRealtime() - firstCaptureStarted)
                if (remaining > 0) {
                    assertCapped(native.measure("actual-projection-long-running-tail", engine,
                        remaining.coerceAtLeast(5_000)).also { measurements.put(it) })
                }
                evidence.put("firstProjectionActiveMillis", SystemClock.elapsedRealtime() - firstCaptureStarted)
                val stoppedCapture = actor(engine) { field<ScreenCapturerAndroid>(engine, "screenCapture") }
                stopAndAssertReleased(engine)
                assertNull("The SDK must clear its MediaProjection after stopping", stoppedCapture.mediaProjection)
                measurements.put(native.assertStopped("actual-projection-stopped"))

                // Android 14+ consent is one-use. A new dialog must return a fresh token for restart.
                startWithFreshConsent(activity, device, engine)
                val restartedCapture = actor(engine) { field<ScreenCapturerAndroid>(engine, "screenCapture") }
                assertNotSame("Restart must create a fresh capturer", stoppedCapture, restartedCapture)
                native.waitForOrientation(portrait = true)
                assertCapped(native.measure("actual-projection-fresh-consent-restart", engine, 5_000).also { measurements.put(it) })
                stopAndAssertReleased(engine)
                assertNull(restartedCapture.mediaProjection)
                measurements.put(native.assertStopped("actual-projection-stopped-after-restart"))
                evidence.put("freshConsentCount", 2).put("rotationCount", 2)
                    .put("allScreenOwnershipCleared", true).put("success", true)
            }
        } catch (failure: Throwable) {
            evidence.put("failure", "${failure.javaClass.simpleName}: ${failure.message}")
            File(context.cacheDir, "screen-share-projection-ui.xml").also {
                runCatching { device.dumpWindowHierarchy(it) }
            }
            throw failure
        } finally {
            // ScreenShareService.onDestroy reads the same injected engine until service shutdown drains.
            runCatching { stopAndAssertReleased(engine) }
            context.stopService(Intent(context, ScreenShareService::class.java))
            instrumentation.waitForIdleSync()
            runCatching { actor(engine) { Unit } }
            loop?.close()
            engine.leave(updateMembership = false)
            try { actor(engine) { assertEquals(CallPhase.IDLE, engine.state.value.phase) } }
            finally {
                app.mediaProjectionTestEngine = null
                field<CoroutineScope>(engine, "scope").cancel()
                field<ExecutorCoroutineDispatcher>(engine, "dispatcher").close()
                File(context.cacheDir, "screen-share-projection-fps.json").writeText(evidence.toString(2))
            }
        }
    }

    private fun startWithFreshConsent(activity: ActivityScenario<ComponentActivity>, device: UiDevice, engine: CallEngine) {
        val result = CompletableFuture<ActivityResult>()
        val launcherReference = AtomicReference<ActivityResultLauncher<Intent>>()
        val key = "projection-fps-${UUID.randomUUID()}"
        activity.onActivity { host ->
            val launcher = host.activityResultRegistry.register(key, ActivityResultContracts.StartActivityForResult()) {
                result.complete(it)
            }
            launcherReference.set(launcher)
            val manager = host.getSystemService(MediaProjectionManager::class.java)
            val intent = if (Build.VERSION.SDK_INT >= 34)
                manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            else manager.createScreenCaptureIntent()
            launcher.launch(intent)
        }
        approveFullScreenConsent(device, result)
        val consent = result.get(15, TimeUnit.SECONDS)
        assertEquals("System consent must be explicitly accepted", Activity.RESULT_OK, consent.resultCode)
        val data = checkNotNull(consent.data)
        activity.onActivity { host ->
            ContextCompat.startForegroundService(host, Intent(host, ScreenShareService::class.java)
                .putExtra(ScreenShareService.CONSENT, data)
                .putExtra(ScreenShareService.CONFERENCE, engine.state.value.conferenceId)
                .putExtra(ScreenShareService.DIRECT_CALL, engine.state.value.directCallId))
            launcherReference.get().unregister()
        }
        waitFor("Production engine did not start MediaProjection: ${engine.state.value.error}", 20_000) {
            if (engine.state.value.error != null && !engine.state.value.sharing) {
                throw AssertionError("Projection startup failed: ${engine.state.value.error}")
            }
            engine.state.value.sharing
        }
        actor(engine) {
            assertNotNull(field<ScreenCapturerAndroid>(engine, "screenCapture").mediaProjection)
            assertTrue("Only enable the track after RTP framerate parameters have been checked",
                field<Boolean>(engine, "screenSenderLimited"))
        }
    }

    private fun approveFullScreenConsent(device: UiDevice, result: CompletableFuture<ActivityResult>) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        var chooserOpened = false
        var entireScreenSelected = false
        while (SystemClock.elapsedRealtime() < deadline && !result.isDone) {
            val full = listOf("Entire screen", "Full screen", "Весь экран", "Весь экран устройства")
                .firstNotNullOfOrNull { device.findObject(By.text(it)) }
            if (full != null && !entireScreenSelected) {
                if (chooserOpened) full.click()
                entireScreenSelected = true
                waitMillis(250)
                continue
            }
            if (!chooserOpened && !entireScreenSelected) {
                val chooser = device.findObject(By.res("com.android.systemui", "screen_share_mode_spinner"))
                    ?: device.findObject(By.textContains("Single app"))
                    ?: device.findObject(By.textContains("single app"))
                    ?: device.findObject(By.textContains("One app"))
                    ?: device.findObject(By.textContains("Одно приложение"))
                if (chooser != null) {
                    chooser.click(); chooserOpened = true
                    waitMillis(250)
                    continue
                }
            }
            val positive = listOf("Start sharing", "Start recording", "Start now", "Share screen", "Начать", "Начать сейчас")
                .firstNotNullOfOrNull { device.findObject(By.text(it)) }
                ?: device.findObject(By.res("android", "button1"))
            if (positive != null && (entireScreenSelected || !chooserOpened)) positive.click()
            waitMillis(150)
        }
        assertTrue("System full-screen consent dialog did not complete", result.isDone)
    }

    private fun rotate(activity: ActivityScenario<ComponentActivity>, orientation: Int) {
        activity.onActivity { it.requestedOrientation = orientation }
        waitFor("Test activity did not change orientation", 10_000) {
            var correct = false
            activity.onActivity { host ->
                correct = host.resources.configuration.orientation == if (orientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
                    android.content.res.Configuration.ORIENTATION_PORTRAIT else android.content.res.Configuration.ORIENTATION_LANDSCAPE
            }
            correct
        }
        // ComponentActivity test host does not recreate test content itself after configuration change.
        activity.onActivity { host ->
            host.setContent {
                var frame by remember { mutableLongStateOf(0L) }
                LaunchedEffect(Unit) { while (true) withFrameNanos { frame++ } }
                Canvas(Modifier.fillMaxSize()) {
                    drawRect(Color(0xFF17233B))
                    val phase = (frame % 180L).toFloat() / 180f
                    drawRect(Color(0xFF36C5A4), Offset(phase * size.width, 0f), Size(size.width / 5, size.height))
                    drawCircle(Color(0xFFFCBE50), size.minDimension / 7f,
                        Offset(size.width / 2f, (0.1f + phase * 0.8f) * size.height))
                    for (index in 0..12) drawLine(Color(0xFF7688F0),
                        Offset(0f, (index / 12f) * size.height),
                        Offset(size.width, ((index + (frame % 12L)) / 24f) * size.height), 3f)
                }
            }
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun stopAndAssertReleased(engine: CallEngine) {
        engine.stopScreen()
        actor(engine) {
            assertFalse(engine.state.value.sharing)
            assertNull(engine.state.value.localScreen)
            for (name in listOf("screenCapture", "screenTrack", "screenSource", "screenHelper")) {
                assertNull("Stop must release $name", field<Any?>(engine, name))
            }
            assertEquals(0L, engine.state.value.diagnostics.screenFramesEncoded)
            assertNull(engine.state.value.diagnostics.screenEncodedFps)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun assertCapped(sample: JSONObject) {
        assertTrue("Animated screen must produce actual encoded frames: $sample", sample.getDouble("encodedFps") >= 4.0)
        val maxFrames = ceil(sample.getDouble("seconds") * ScreenShareVideoPolicy.MAX_FPS).toLong() + 2
        assertTrue("Real MediaProjection outbound-rtp.framesEncoded exceeded 15 fps: $sample",
            sample.getLong("framesEncodedDelta") <= maxFrames)
        assertTrue("Capturer must keep receiving frames: $sample", sample.getLong("capturedFramesDelta") > 0)
        val captureWidth = sample.getInt("captureWidth")
        val captureHeight = sample.getInt("captureHeight")
        val decodedWidth = sample.getInt("decodedWidth")
        val decodedHeight = sample.getInt("decodedHeight")
        assertTrue("Encoded adaptation may downscale but must keep positive dimensions",
            decodedWidth in 2..captureWidth && decodedHeight in 2..captureHeight)
        assertEquals("Decoded aspect must preserve the captured screen without stretching/cropping",
            captureWidth.toDouble() / captureHeight, decodedWidth.toDouble() / decodedHeight, 0.01)
    }

    private class ProjectionLoopback(private val factory: PeerConnectionFactory) : Closeable {
        val senderPeer: PeerConnection
        private val receiverPeer: PeerConnection
        val transceiver: RtpTransceiver
        private val decoded = AtomicReference<Pair<Int, Int>?>(null)
        private val iceError = AtomicReference<Throwable?>()
        private val closing = AtomicBoolean()
        private val ice = Executors.newSingleThreadExecutor { Thread(it, "Projection-test-ICE") }
        // Accessed only by the ICE router. SDK callbacks never perform JNI or hold an ICE lock.
        private var descriptionsReady = false
        private val pendingLocal = mutableListOf<IceCandidate>()
        private val pendingRemote = mutableListOf<IceCandidate>()
        private val receiverReference = AtomicReference<PeerConnection?>()
        private val senderReference = AtomicReference<PeerConnection?>()

        init {
            val configuration = PeerConnection.RTCConfiguration(emptyList()).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            }
            senderPeer = checkNotNull(factory.createPeerConnection(configuration, object : EmptyObserver() {
                override fun onIceCandidate(candidate: IceCandidate) { route(candidate, fromSender = true) }
            }))
            senderReference.set(senderPeer)
            receiverPeer = checkNotNull(factory.createPeerConnection(configuration, object : EmptyObserver() {
                override fun onIceCandidate(candidate: IceCandidate) { route(candidate, fromSender = false) }
                override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
                    (receiver.track() as? VideoTrack)?.addSink { frame -> decoded.set(frame.rotatedWidth to frame.rotatedHeight) }
                }
            }))
            receiverReference.set(receiverPeer)
            senderPeer.setAudioRecording(false); senderPeer.setAudioPlayout(false)
            receiverPeer.setAudioRecording(false); receiverPeer.setAudioPlayout(false)
            val source = factory.createVideoSource(true)
            val placeholder = factory.createVideoTrack("projection-placeholder", source)
            try {
                placeholder.setEnabled(false)
                transceiver = senderPeer.addTransceiver(placeholder, RtpTransceiver.RtpTransceiverInit(
                    RtpTransceiver.RtpTransceiverDirection.SEND_RECV, listOf("projection-test")))
                val vp8 = factory.getRtpReceiverCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO).codecs
                    .filter { it.name.equals("VP8", ignoreCase = true) }
                check(vp8.isNotEmpty())
                transceiver.setCodecPreferences(vp8)
                negotiate()
                check(transceiver.sender.setTrack(null, false))
                check(transceiver.sender.parameters.encodings.isNotEmpty())
            } catch (failure: Throwable) {
                closing.set(true)
                ice.shutdownNow()
                ice.awaitTermination(10, TimeUnit.SECONDS)
                receiverPeer.close(); receiverPeer.dispose()
                senderPeer.close(); senderPeer.dispose()
                throw failure
            } finally { placeholder.dispose(); source.dispose() }
        }

        private fun route(candidate: IceCandidate, fromSender: Boolean) {
            if (closing.get()) return
            try {
                ice.execute {
                    try {
                        if (!descriptionsReady) {
                            (if (fromSender) pendingLocal else pendingRemote).add(candidate)
                        } else {
                            val peer = checkNotNull(if (fromSender) receiverReference.get() else senderReference.get())
                            check(peer.addIceCandidate(candidate))
                        }
                    } catch (failure: Throwable) { iceError.compareAndSet(null, failure) }
                }
            } catch (failure: RejectedExecutionException) { if (!closing.get()) throw failure }
        }

        private fun negotiate() {
            val offer = description(senderPeer, offer = true)
            setDescription(senderPeer, offer, local = true); setDescription(receiverPeer, offer, local = false)
            val answer = description(receiverPeer, offer = false)
            setDescription(receiverPeer, answer, local = true); setDescription(senderPeer, answer, local = false)
            ice.submit {
                descriptionsReady = true
                val local = pendingLocal.toList(); pendingLocal.clear()
                val remote = pendingRemote.toList(); pendingRemote.clear()
                local.forEach { check(receiverPeer.addIceCandidate(it)) }
                remote.forEach { check(senderPeer.addIceCandidate(it)) }
            }.get(10, TimeUnit.SECONDS)
        }

        fun waitForOrientation(portrait: Boolean) {
            waitFor("MediaProjection/decoder did not reflect screen rotation", 20_000) {
                iceError.get()?.let { throw AssertionError("ICE router failed", it) }
                decoded.get()?.let { (it.first < it.second) == portrait } == true
            }
            // Drain dimension changes and the encoder's previous-size frame before sampling.
            waitMillis(750)
        }

        fun measure(label: String, engine: CallEngine, millis: Long): JSONObject {
            iceError.get()?.let { throw AssertionError("ICE router failed", it) }
            val capturer = actor(engine) { field<ScreenCapturerAndroid>(engine, "screenCapture") }
            updateEngineDiagnostics(engine)
            val before = stats()
            val capturedBefore = capturer.numCapturedFrames
            waitMillis(millis)
            val after = stats()
            val diagnostics = updateEngineDiagnostics(engine)
            assertTrue("Production sender-scoped diagnostics must receive an encoded-frame counter",
                diagnostics.screenFramesEncoded > 0L)
            val diagnosticsFps = checkNotNull(diagnostics.screenEncodedFps) {
                "Two production statistics polls must calculate the actual screen FPS"
            }
            assertTrue("Production encoded FPS must stay within the measurement-window tolerance",
                diagnosticsFps <= ScreenShareVideoPolicy.MAX_FPS + 0.5)
            val dimensions = actor(engine) { field<Pair<Int, Int>>(engine, "screenSize") }
            val seconds = (after.timestampUs - before.timestampUs) / 1_000_000.0
            val frames = encoded(after) - encoded(before)
            val captured = capturer.numCapturedFrames - capturedBefore
            return JSONObject().put("phase", label).put("seconds", seconds)
                .put("framesEncodedBefore", encoded(before)).put("framesEncodedAfter", encoded(after))
                .put("framesEncodedDelta", frames).put("encodedFps", frames / seconds)
                .put("capturedFramesDelta", captured).put("capturedFps", captured / seconds)
                .put("engineScreenFramesEncoded", diagnostics.screenFramesEncoded)
                .put("engineScreenEncodedFps", diagnosticsFps)
                .put("captureWidth", dimensions.first).put("captureHeight", dimensions.second)
                .put("decodedWidth", decoded.get()?.first).put("decodedHeight", decoded.get()?.second)
                .put("rtpMaxFramerates", JSONArray(transceiver.sender.parameters.encodings.map { it.maxFramerate }))
                .put("outboundRtp", JSONArray(after.statsMap.values
                    .filter { it.type == "outbound-rtp" && it.members["kind"] == "video" }
                    .map { JSONObject(it.members).put("id", it.id) }))
        }

        fun assertStopped(label: String): JSONObject {
            waitMillis(750)
            val before = stats()
            waitMillis(1_000)
            val after = stats()
            val frames = encoded(after) - encoded(before)
            assertTrue("Stopped MediaProjection must stop encoding RTP frames", frames <= 1L)
            return JSONObject().put("phase", label).put("framesEncodedDelta", frames)
                .put("seconds", (after.timestampUs - before.timestampUs) / 1_000_000.0)
        }

        private fun stats(): RTCStatsReport {
            val result = CompletableFuture<RTCStatsReport>()
            senderPeer.getStats(transceiver.sender) { result.complete(it) }
            return result.get(5, TimeUnit.SECONDS)
        }
        private fun encoded(report: RTCStatsReport): Long = report.statsMap.values
            .filter { it.type == "outbound-rtp" && it.members["kind"] == "video" }
            .sumOf { (it.members["framesEncoded"] as? Number)?.toLong() ?: 0L }
        override fun close() {
            closing.set(true)
            ice.shutdown()
            check(ice.awaitTermination(10, TimeUnit.SECONDS)) { "ICE routing must finish before peers are disposed" }
            // CallEngine owns senderPeer and disposes it/factory on leave.
            receiverPeer.close(); receiverPeer.dispose()
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

    private abstract class EmptySdpObserver : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String) = Unit
        override fun onSetFailure(error: String) = Unit
    }

    private companion object {
        fun description(peer: PeerConnection, offer: Boolean): SessionDescription {
            val result = CompletableFuture<SessionDescription>()
            val observer = object : EmptySdpObserver() {
                override fun onCreateSuccess(description: SessionDescription) { result.complete(description) }
                override fun onCreateFailure(error: String) { result.completeExceptionally(AssertionError(error)) }
            }
            if (offer) peer.createOffer(observer, MediaConstraints()) else peer.createAnswer(observer, MediaConstraints())
            return result.get(10, TimeUnit.SECONDS)
        }
        fun setDescription(peer: PeerConnection, description: SessionDescription, local: Boolean) {
            val result = CompletableFuture<Unit>()
            val observer = object : EmptySdpObserver() {
                override fun onSetSuccess() { result.complete(Unit) }
                override fun onSetFailure(error: String) { result.completeExceptionally(AssertionError(error)) }
            }
            if (local) peer.setLocalDescription(observer, description) else peer.setRemoteDescription(observer, description)
            result.get(10, TimeUnit.SECONDS)
        }
        fun waitMillis(millis: Long) { CountDownLatch(1).await(millis, TimeUnit.MILLISECONDS) }
        fun waitFor(message: String, millis: Long, condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + millis
            while (SystemClock.elapsedRealtime() < deadline) {
                if (condition()) return
                waitMillis(50)
            }
            throw AssertionError(message)
        }
        fun <T> actor(engine: CallEngine, operation: () -> T): T {
            val result = CompletableDeferred<T>()
            val command: suspend () -> Unit = {
                try { result.complete(operation()) }
                catch (failure: Throwable) { result.completeExceptionally(failure) }
            }
            CallEngine::class.java.declaredMethods.single { it.name == "enqueue" }.apply { isAccessible = true }
                .invoke(engine, command)
            return runBlocking { withTimeout(20_000) { result.await() } }
        }
        @Suppress("UNCHECKED_CAST")
        fun <T> field(engine: CallEngine, name: String): T = CallEngine::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.get(engine) as T
        fun setField(engine: CallEngine, name: String, value: Any?) {
            CallEngine::class.java.getDeclaredField(name).apply { isAccessible = true }.set(engine, value)
        }
        fun state(engine: CallEngine): MutableStateFlow<CallState> = field(engine, "mutable")
        fun updateEngineDiagnostics(engine: CallEngine): CallDiagnostics {
            val previous = actor(engine) { field<Any?>(engine, "screenStatsSample") }
            actor(engine) {
                CallEngine::class.java.getDeclaredMethod("stats", java.lang.Long.TYPE)
                    .apply { isAccessible = true }.invoke(engine, field<Long>(engine, "generation"))
            }
            waitFor("Production screen statistics callback did not complete", 5_000) {
                actor(engine) { field<Any?>(engine, "screenStatsSample") !== previous }
            }
            return engine.state.value.diagnostics
        }
        @Suppress("UNCHECKED_CAST")
        fun <T> unavailable(type: Class<T>): T = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
            throw AssertionError("Projection loopback must not call backend method ${method.name}")
        } as T
    }
}
