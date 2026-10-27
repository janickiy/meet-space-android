package com.meetspace.app

import android.net.TrafficStats
import android.os.Build
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.lang.ref.WeakReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.webrtc.EglBase
import org.webrtc.Logging
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.JavaAudioDeviceModule

/** Opt-in offline audit on an owned empty emulator. Numeric evidence contains no user content. */
class AuditOfflineLifecycleTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val args get() = InstrumentationRegistry.getArguments()

    @Test fun activityRecreationAndBackgroundReleaseOwners() {
        requireEnvironment()
        val measurement = Measurement("activity")
        measurement.run(3, 20) { measurement.activityCycle() }
        measurement.assertCollected("Destroyed Activities/decor/VMs must be collectible after settling")
        if (args.getString("auditLeakCanary") == "true") {
            // Explicit assertion; the optional library is absent from production and normal measurements.
            val assertions = Class.forName("leakcanary.LeakAssertions")
            assertions.getMethod("assertNoLeaks", String::class.java)
                .invoke(assertions.getField("INSTANCE").get(null), "offline-activity-audit")
        }
    }

    @Test fun nativeFactoriesReleaseAcrossRepeatedLifetimes() {
        requireEnvironment()
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false).setInjectableLogger({ _, _, _ -> }, Logging.Severity.LS_NONE)
            .createInitializationOptions())
        val measurement = Measurement("native-factory")
        measurement.run(10, 100) { measurement.factoryCycle() }
        measurement.assertCollected("Disposed native wrappers must be collectible")
    }

    private fun requireEnvironment() {
        assumeTrue("Run explicitly with -e auditOffline true", args.getString("auditOffline") == "true")
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Owned emulator only" }
        check(context.filesDir.listFiles().orEmpty().none { it.name.startsWith("live-") }) { "No live fixture input allowed" }
        // An empty StateFlow before restore does not prove the persisted account store is empty.
        check(!File(context.filesDir, "datastore/meetspace_profiles.preferences_pb").exists()) { "Fresh profile store required" }
        check(File(context.noBackupFilesDir, "meetspace_sessions_v1").listFiles().orEmpty().isEmpty()) { "No saved sessions allowed" }
        check(!context.getSharedPreferences("meetspace-push-binding", android.content.Context.MODE_PRIVATE)
            .getBoolean("consent", false)) { "No push registration allowed" }
        val app = context.applicationContext as MeetSpaceApplication
        check(app.repository.state.value.session == null && app.repository.state.value.profiles.isEmpty()) {
            "Offline lifecycle audit requires an empty test profile store"
        }
    }

    private inner class Measurement(private val kind: String) {
        private val label = args.getString("auditLabel", "baseline").also { require(it.matches(Regex("[a-zA-Z0-9_-]{1,40}"))) }
        private val references = mutableListOf<WeakReference<Any>>()
        private val samples = JSONArray()
        private val report = JSONObject().put("kind", kind).put("label", label)
            .put("sdk", Build.VERSION.SDK_INT).put("abi", Build.SUPPORTED_ABIS.first())
            .put("captureOrNetworkSessionStarted", false).put("samples", samples)

        fun retained() = references.count { it.get() != null }
        private fun watch(value: Any) { references.add(WeakReference(value)) }
        fun assertCollected(message: String) {
            val count = retained()
            report.put("collectionAssertionPassed", count == 0)
            File(context.cacheDir, "audit-$label-$kind.json").writeText(report.toString(2))
            assertEquals(message, 0, count)
        }

        fun activityCycle() {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    watch(activity)
                    watch(activity.window.decorView)
                    watch(ViewModelProvider(activity)[FoundationViewModel::class.java])
                    watch(ViewModelProvider(activity)[MeetingsViewModel::class.java])
                    watch(ViewModelProvider(activity)[MessagingViewModel::class.java])
                    watch(ViewModelProvider(activity)[RecordingsViewModel::class.java])
                    watch(ViewModelProvider(activity)[DirectCallViewModel::class.java])
                    watch(ViewModelProvider(activity)[CallDiscussionViewModel::class.java])
                    watch(ViewModelProvider(activity)[SshWizardViewModel::class.java])
                    watch(ViewModelProvider(activity)[InstallerViewModel::class.java])
                }
                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.moveToState(Lifecycle.State.RESUMED)
                scenario.recreate()
                scenario.onActivity { watch(it); watch(it.window.decorView) }
            }
            instrumentation.waitForIdleSync()
        }

        fun factoryCycle() {
            val egl = EglBase.create()
            try {
                val audio = JavaAudioDeviceModule.builder(context).createAudioDeviceModule()
                try {
                    audio.setAudioRecordEnabled(false)
                    val factory = PeerConnectionFactory.builder().setAudioDeviceModule(audio).createPeerConnectionFactory()
                    try { watch(egl); watch(audio); watch(factory) }
                    finally { factory.dispose() }
                } finally { audio.release() }
            } finally { egl.release() }
        }

        fun run(warmup: Int, cycles: Int, action: () -> Unit) {
            report.put("warmupCycles", warmup).put("measuredCycles", cycles).put("status", "running")
            try {
                repeat(warmup) { action() }
                snapshot("warmup", 0)
                repeat(cycles) { i ->
                    action()
                    if ((i + 1) % 10 == 0) snapshot("batch", i + 1)
                }
                SystemClock.sleep(5_000) // test settling only; never used for capture pacing
                snapshot("settled", cycles)
                report.put("status", "completed")
            } finally {
                File(context.cacheDir, "audit-$label-$kind.json").writeText(report.toString(2))
            }
        }

        private fun snapshot(phase: String, completed: Int) {
            instrumentation.waitForIdleSync()
            repeat(3) { Runtime.getRuntime().gc(); System.runFinalization(); SystemClock.sleep(200) }
            val memory = Debug.MemoryInfo().apply { Debug.getMemoryInfo(this) }
            val runtime = Runtime.getRuntime()
            val data = JSONObject().put("phase", phase).put("completedCycles", completed)
                .put("elapsedRealtimeMs", SystemClock.elapsedRealtime()).put("cpuMs", Process.getElapsedCpuTime())
                .put("javaUsedBytes", runtime.totalMemory() - runtime.freeMemory())
                .put("nativeAllocatedBytes", Debug.getNativeHeapAllocatedSize()).put("pssKb", memory.totalPss)
                .put("graphicsKb", memory.getMemoryStat("summary.graphics").toLongOrNull() ?: JSONObject.NULL)
                .put("threads", File("/proc/self/task").list()?.size ?: JSONObject.NULL)
                .put("fds", File("/proc/self/fd").list()?.size ?: JSONObject.NULL)
                .put("uidRxBytes", TrafficStats.getUidRxBytes(Process.myUid())).put("uidTxBytes", TrafficStats.getUidTxBytes(Process.myUid()))
                .put("watchedObjects", references.size).put("retainedObjects", retained())
            samples.put(data)
        }
    }
}
