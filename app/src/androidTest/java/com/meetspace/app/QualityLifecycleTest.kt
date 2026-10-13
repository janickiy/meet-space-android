package com.meetspace.app

import android.content.Intent
import android.net.TrafficStats
import android.os.Build
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.meetspace.core.media.CallPhase
import java.io.File
import java.lang.ref.WeakReference
import java.net.URI
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.Logging
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * Opt-in measurements against disposable fixtures only. Never captures camera/microphone/screen.
 * GC/weak-reference/PSS/RSS observations are evidence for analysis, not automatic leak diagnoses.
 * HPROF is deliberately private and unsanitized (fixture credentials may be in heap); only the
 * numeric JSON is suitable for the audit report. Run each method in a fresh instrumentation process.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class QualityLifecycleTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val app get() = context.applicationContext as LiveCallApplication
    private val arguments get() = InstrumentationRegistry.getArguments()

    @Test fun nativeFactoryDisposalBatches() {
        requireOptIn()
        val measure = Measurement("native-factory")
        val warmup = count("qualityWarmup", 10, 1, 30)
        val cycles = count("qualityFactoryCycles", 100, 1, 200)
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false).setInjectableLogger({ _, _, _ -> }, Logging.Severity.LS_NONE)
            .createInitializationOptions())
        measure.run(warmup, cycles) { cycle ->
            val egl = EglBase.create()
            try {
                val audio = JavaAudioDeviceModule.builder(context).createAudioDeviceModule()
                try {
                    audio.setAudioRecordEnabled(false)
                    val factory = PeerConnectionFactory.builder().setAudioDeviceModule(audio)
                        .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, false, false))
                        .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
                        .createPeerConnectionFactory()
                    try {
                        measure.watch("factory", cycle, factory)
                        measure.watch("audioModule", cycle, audio)
                        measure.watch("egl", cycle, egl)
                    } finally { factory.dispose() }
                } finally { audio.release() }
            } finally { egl.release() }
        }
    }

    @Test fun realCallJoinLeaveBatches() = runBlocking {
        requireOptIn("live-call.properties")
        val backend = app.liveBackend
        check(URI(backend.input.getProperty("origin")).host in setOf("10.0.2.2", "127.0.0.1", "localhost")) {
            "Quality harness requires the emulator-local disposable fixture"
        }
        backend.login()
        val meeting = backend.conference(backend.input.getProperty("conferenceId"))
        val engine = app.callEngine
        val measure = Measurement("real-call")
        val warmup = count("qualityWarmup", 10, 1, 30)
        val cycles = count("qualityCallCycles", 100, 1, 200)
        // One application-scoped engine is intentional; constructing engines repeatedly would
        // measure an unsupported ownership model (each owns a process-lifetime actor executor).
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use {
            instrumentation.waitForIdleSync()
            try {
                engine.leave()
                await("initial call cleanup") { engine.state.value.phase == CallPhase.IDLE }
                measure.run(warmup, cycles) { cycle ->
                    engine.prepare(meeting)
                    await("call prejoin", 20_000) { engine.state.value.phase == CallPhase.PREJOIN }
                    engine.connect()
                    await("real call connected", 65_000) {
                        check(engine.state.value.phase != CallPhase.FAILED) { "Disposable call failed; inspect sanitized stage06 diagnostics" }
                        engine.state.value.phase == CallPhase.CONNECTED
                    }
                    assertFalse(engine.state.value.camera)
                    assertFalse(engine.state.value.microphone)
                    assertFalse(engine.state.value.sharing)
                    if (cycle == 0 || cycle == warmup || (cycle - warmup + 1) % 10 == 0) {
                        await("relay evidence", 15_000) { engine.state.value.diagnostics.relay }
                    }
                    engine.leave()
                    await("call leave", 20_000) { engine.state.value.phase == CallPhase.IDLE }
                    assertNull(engine.state.value.localVideo)
                    assertNull(engine.state.value.localScreen)
                    assertTrue(engine.state.value.videos.isEmpty())
                    assertTrue(engine.state.value.routes.isEmpty())
                    assertNull("The disposable backend must confirm membership leave", engine.state.value.error)
                }
            } finally {
                engine.leave()
                await("final call cleanup", 20_000) { engine.state.value.phase == CallPhase.IDLE }
            }
        }
    }

    @Test fun activityAndRecordingLifecycleBatches() = runBlocking {
        requireOptIn("live-recordings.properties")
        val backend = app.liveRecordingsBackend
        check(app.repository === backend.primary) { "Restart instrumentation after installing fixture inputs" }
        backend.loginPrimary()
        val measure = Measurement("activity-recording")
        val warmup = count("qualityWarmup", 3, 1, 30)
        val cycles = count("qualityActivityCycles", 20, 1, 100)
        try {
            measure.run(warmup, cycles) { cycle -> recordingCycle(measure, cycle) }
        } finally {
            backend.finish()
            app.messagingNotifications.clear()
        }
        QualityLeakAssertions.assertNoLeaksIfRequested("activity-recording")
    }

    @Test fun authLoginLogoutBatches() = runBlocking {
        requireOptIn("live-recordings.properties")
        val backend = app.liveRecordingsBackend
        check(app.repository === backend.primary) { "Restart instrumentation after installing fixture inputs" }
        // Keep one actual server profile; the fixture exposes one supported emulator origin.
        // A second fabricated alias would not establish real server-switch coverage.
        backend.loginPrimary()
        backend.primary.logout()
        val measure = Measurement("auth")
        try {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
                compose.waitUntil(25_000) { compose.onAllNodes(androidx.compose.ui.test.hasTestTag("login-submit")).fetchSemanticsNodes().isNotEmpty() }
                measure.run(count("qualityWarmup", 3, 1, 30), count("qualityAuthCycles", 20, 1, 100)) { cycle ->
                    authCycle(measure, cycle, scenario)
                }
            }
        } finally {
            backend.finish()
            app.messagingNotifications.clear()
        }
    }

    /** Keep old session/data references inside this method, out of the post-cycle GC snapshot. */
    private fun authCycle(measure: Measurement, cycle: Int, scenario: ActivityScenario<MainActivity>) {
        val backend = app.liveRecordingsBackend
        lateinit var foundation: FoundationViewModel
        lateinit var recordings: RecordingsViewModel
        scenario.onActivity { activity ->
            foundation = ViewModelProvider(activity)[FoundationViewModel::class.java]
            recordings = ViewModelProvider(activity)[RecordingsViewModel::class.java]
        }
        await("auth ready for login") { !foundation.state.value.busy && backend.primary.state.value.session == null }
        backend.withPrimaryLoginInput { email, password -> onMain { foundation.login(email, password) } }
        await("actual HTTPS login") { !foundation.state.value.busy && backend.primary.state.value.session != null }
        assertNull("Actual login must succeed", foundation.state.value.error)
        measure.watch("session", cycle, checkNotNull(backend.primary.state.value.session))
        onMain { recordings.show(backend.resource("conferenceId")) }
        await("authorized recording list") { recordings.state.value.recordings.data?.items?.any { it.id == backend.resource("recordingId") } == true }
        onMain { recordings.openRecording(backend.resource("recordingId")) }
        await("authorized recording detail") { recordings.state.value.detail.data != null }
        measure.watch("recordingDetail", cycle, checkNotNull(recordings.state.value.detail.data))
        onMain { foundation.logout() }
        await("logout clears scoped recording state") {
            !foundation.state.value.busy && backend.primary.state.value.session == null &&
                !recordings.state.value.visible && recordings.state.value.detail.data == null && recordings.playback.value == null
        }
        assertNull("Actual logout must succeed", foundation.state.value.error)
        assertNull("Fixture must confirm remote logout", foundation.state.value.notice)
        compose.waitForIdle()
    }

    /** Method boundary prevents measurement code from retaining the last Activity/VM/player. */
    private fun recordingCycle(measure: Measurement, cycle: Int) {
        val backend = app.liveRecordingsBackend
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
            lateinit var vm: RecordingsViewModel
            compose.waitUntil(25_000) { compose.onAllNodes(androidx.compose.ui.test.hasTestTag("tab-calls")).fetchSemanticsNodes().isNotEmpty() }
            scenario.onActivity { activity ->
                vm = ViewModelProvider(activity)[RecordingsViewModel::class.java]
                measure.watch("activity", cycle, activity)
                measure.watch("decorView", cycle, activity.window.decorView)
                measure.watch("recordingsViewModel", cycle, vm)
                measure.watch("foundationViewModel", cycle, ViewModelProvider(activity)[FoundationViewModel::class.java])
                measure.watch("messagingViewModel", cycle, ViewModelProvider(activity)[MessagingViewModel::class.java])
                measure.watch("meetingsViewModel", cycle, ViewModelProvider(activity)[MeetingsViewModel::class.java])
            }
            compose.onNodeWithTag("tab-calls").performClick()
            compose.onNodeWithTag("meeting-recordings").performClick()
            await("recordings navigation") { vm.state.value.visible }
            // Use actual VM actions for deterministic resource selection; no fabricated success.
            onMain { vm.openMeeting(backend.resource("conferenceId")) }
            await("actual recording list") { vm.state.value.recordings.data?.items?.any { it.id == backend.resource("recordingId") } == true }
            onMain { vm.openRecording(backend.resource("recordingId")) }
            await("actual recording detail") { vm.state.value.detail.data != null }
            onMain {
                val recording = checkNotNull(vm.state.value.detail.data)
                vm.play(recording, recording.files.single { it.id == backend.resource("videoFileId") })
            }
            awaitNativePlayer(scenario)
            rememberPlayer(measure, cycle, scenario)
            scenario.recreate()
            awaitNativePlayer(scenario)
            scenario.onActivity { activity ->
                assertSame("Configuration recreation retains the same VM", vm, ViewModelProvider(activity)[RecordingsViewModel::class.java])
                measure.watch("activity", cycle, activity)
                measure.watch("decorView", cycle, activity.window.decorView)
            }
            rememberPlayer(measure, cycle, scenario)
            onMain { vm.closePlayback(); vm.close() }
            await("recording capability released") { vm.playback.value == null && !vm.state.value.visible }
        }
        instrumentation.waitForIdleSync()
    }

    // Compose's test clock must advance to install/update the AndroidView player. A plain
    // Thread.sleep poll can leave a ready VM route waiting forever for a composition frame.
    private fun awaitNativePlayer(scenario: ActivityScenario<MainActivity>) = compose.waitUntil(timeoutMillis = 45_000) {
        var ready = false
        scenario.onActivity { activity ->
            val player = playerView(activity.window.decorView)?.player as? ExoPlayer
            ready = player != null && player.playbackState == Player.STATE_READY &&
                (player.videoDecoderCounters?.renderedOutputBufferCount ?: 0) > 0
        }
        ready
    }

    private fun rememberPlayer(measure: Measurement, cycle: Int, scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { activity ->
            val view = checkNotNull(playerView(activity.window.decorView))
            measure.watch("playerView", cycle, view)
            measure.watch("player", cycle, checkNotNull(view.player))
        }
    }
    private fun playerView(view: View): PlayerView? {
        if (view is PlayerView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) playerView(view.getChildAt(index))?.let { return it }
        return null
    }
    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(label: String, timeout: Long = 30_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        throw AssertionError("Quality lifecycle timeout: $label")
    }
    private fun requireOptIn(marker: String? = null) {
        assumeTrue("Expensive stage09 measurements require -e quality09 true", arguments.getString("quality09") == "true")
        assumeTrue("Measurements require disposable fixture inputs", if (marker != null) File(context.filesDir, marker).isFile else
            listOf("live-call.properties", "live-recordings.properties").any { File(context.filesDir, it).isFile })
    }
    private fun count(key: String, default: Int, min: Int, max: Int): Int =
        arguments.getString(key)?.toIntOrNull()?.also { require(it in min..max) { "Quality count outside supported bounds" } } ?: default

    private data class Watched(val kind: String, val cycle: Int, val reference: WeakReference<Any>)
    private inner class Measurement(private val name: String) {
        private val label = arguments.getString("qualityLabel", "baseline").also { require(it.matches(Regex("[a-zA-Z0-9_-]{1,40}"))) }
        private val file = File(context.cacheDir, "stage09-quality-$label-$name.json")
        private val watched = mutableListOf<Watched>()
        private val samples = JSONArray()
        private val hprofs = JSONArray()
        private val evidence = JSONObject().put("schema", 1).put("label", label).put("method", name)
            .put("sdk", Build.VERSION.SDK_INT).put("pageSize", Os.sysconf(OsConstants._SC_PAGESIZE))
            // Read installed APK metadata: a freshly compiled test APK can target a frozen baseline.
            .put("abi", Build.SUPPORTED_ABIS.firstOrNull()).put("versionName", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
            .put("samples", samples).put("hprofs", hprofs).put("captureStarted", false)
            .put("interpretation", "GC and RSS are observations; retained-reference paths and trends require independent analysis")
        fun watch(kind: String, cycle: Int, value: Any) { watched.add(Watched(kind, cycle, WeakReference(value))) }
        fun run(warmup: Int, cycles: Int, action: (Int) -> Unit) {
            evidence.put("warmupCycles", warmup).put("measuredCycles", cycles).put("completedCycles", 0).put("status", "running")
            write()
            try {
                repeat(warmup) { action(it); progress(it + 1, "warmup") }
                snapshot("warmup", warmup)
                heap("warmup")
                repeat(cycles) { offset ->
                    val cycle = warmup + offset
                    action(cycle); progress(cycle + 1, "measured")
                    if ((offset + 1) % 10 == 0 || offset == cycles - 1) snapshot("batch", cycle + 1)
                }
                // The final settled observation gives pending framework/socket cleanup time.
                Thread.sleep(5_000)
                snapshot("settled", warmup + cycles)
                heap("end")
                val idleSettleMs = count("qualityIdleSettleMs", 0, 0, 180_000)
                evidence.put("extraIdleSettleMs", idleSettleMs)
                if (idleSettleMs > 0) {
                    val deadline = SystemClock.elapsedRealtime() + idleSettleMs
                    progress(warmup + cycles, "idle-wait")
                    while (SystemClock.elapsedRealtime() < deadline) Thread.sleep(minOf(5_000, deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1))
                    snapshot("idle-settled", warmup + cycles)
                    heap("idle")
                }
                evidence.put("status", "completed"); write()
            } catch (failure: Throwable) {
                // Messages/stack traces may contain fixture URLs or credentials; never serialize.
                evidence.put("status", "failed").put("failureType", failure.javaClass.simpleName.take(80)); write()
                throw failure
            }
        }
        private fun progress(completed: Int, phase: String) { evidence.put("completedCycles", completed).put("phase", phase); write() }
        private fun write() { file.writeText(evidence.toString(2)) }
        private fun heap(phase: String) {
            if (arguments.getString("qualityHprof") != "true") return
            val filename = "stage09-quality-$label-$name-$phase.hprof"
            val status = try { Debug.dumpHprofData(File(context.cacheDir, filename).absolutePath); "written" } catch (_: Exception) { "unavailable" }
            hprofs.put(JSONObject().put("phase", phase).put("filename", filename).put("status", status).put("privateUnsanitized", true)); write()
        }
        private fun snapshot(phase: String, completed: Int) {
            instrumentation.waitForIdleSync()
            repeat(3) { Runtime.getRuntime().gc(); System.runFinalization(); Thread.sleep(200) }
            val runtime = Runtime.getRuntime()
            val memory = Debug.MemoryInfo().apply { Debug.getMemoryInfo(this) }
            val runtimeStats = JSONObject()
            listOf("art.gc.bytes-allocated", "art.gc.bytes-freed", "art.gc.gc-count", "art.gc.gc-time", "art.gc.blocking-gc-count", "art.gc.blocking-gc-time")
                .forEach { key -> runtimeStats.put(key, runCatching { Debug.getRuntimeStat(key)?.toLongOrNull() }.getOrNull() ?: JSONObject.NULL) }
            val references = JSONObject()
            watched.groupBy { it.kind }.forEach { (kind, values) ->
                references.put(kind, JSONObject().put("watched", values.size).put("alive", values.count { it.reference.get() != null })
                    .put("aliveOlderThanOneCycle", values.count { it.cycle < completed - 1 && it.reference.get() != null }))
            }
            // No FD destinations/thread names are exported: those can embed URLs or personal text.
            val rssKb = File("/proc/self/status").useLines { lines -> lines.firstOrNull { it.startsWith("VmRSS:") }
                ?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLongOrNull() }
            val sample = JSONObject().put("phase", phase).put("completedCycles", completed)
                .put("elapsedRealtimeMs", SystemClock.elapsedRealtime()).put("processCpuMs", Process.getElapsedCpuTime())
                .put("javaUsedBytes", runtime.totalMemory() - runtime.freeMemory()).put("javaCommittedBytes", runtime.totalMemory())
                .put("nativeHeapAllocatedBytes", Debug.getNativeHeapAllocatedSize()).put("nativeHeapBytes", Debug.getNativeHeapSize())
                .put("pssTotalKb", memory.totalPss).put("pssDalvikKb", memory.dalvikPss).put("pssNativeKb", memory.nativePss)
                .put("rssKb", rssKb ?: JSONObject.NULL).put("threads", File("/proc/self/task").list()?.size ?: JSONObject.NULL)
                .put("fds", File("/proc/self/fd").list()?.size ?: JSONObject.NULL)
                .put("uidRxBytes", TrafficStats.getUidRxBytes(Process.myUid())).put("uidTxBytes", TrafficStats.getUidTxBytes(Process.myUid()))
                .put("runtimeStats", runtimeStats).put("weakReferences", references)
            samples.put(sample); write()
        }
    }
}
