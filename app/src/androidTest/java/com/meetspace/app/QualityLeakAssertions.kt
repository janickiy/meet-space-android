package com.meetspace.app

import android.content.pm.ApplicationInfo
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.lang.reflect.InvocationTargetException
import org.json.JSONObject

/** Reflection keeps the default baseline APK/test APK entirely independent of LeakCanary. */
internal object QualityLeakAssertions {
    fun assertNoLeaksIfRequested(tag: String) {
        val arguments = InstrumentationRegistry.getArguments()
        if (arguments.getString("qualityLeakCanary") != "true") return
        check(arguments.getString("quality09") == "true") { "Leak checks require the opt-in quality fixture harness" }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) { "Leak checks require a debug build" }
        check(File(context.filesDir, "live-recordings.properties").isFile) { "Leak checks require disposable recording fixture inputs" }
        val label = arguments.getString("qualityLabel", "baseline")
        require(label.matches(Regex("[a-zA-Z0-9_-]{1,40}")) && tag.matches(Regex("[a-z-]{1,40}")))
        val output = File(context.cacheDir, "stage09-quality-$label-$tag-leakcanary.json")
        val evidence = JSONObject().put("label", label).put("tag", tag).put("version", "2.14")
            .put("status", "running").put("kind", "LeakAssertions.assertNoLeaks")
        output.writeText(evidence.toString(2))
        try {
            val assertions = try { Class.forName("leakcanary.LeakAssertions") }
            catch (_: ClassNotFoundException) {
                throw AssertionError("Build and install both APKs with -PmeetspaceLeakCanary=true before using -e qualityLeakCanary true")
            }
            instrumentation.waitForIdleSync()
            // LeakCanary disables asynchronous heap dumping under JUnit. Its instrumentation
            // artifact performs this explicit synchronous assertion on the instrumentation thread.
            try { assertions.getMethod("assertNoLeaks", String::class.java).invoke(assertions.getField("INSTANCE").get(null), tag) }
            catch (wrapped: InvocationTargetException) { throw wrapped.targetException }
            evidence.put("status", "assertion-returned")
        } catch (failure: Throwable) {
            evidence.put("status", "failed").put("failureType", failure.javaClass.simpleName.take(80))
            throw failure
        } finally {
            // Assertion-returned means no application leak was reported by this configured
            // detector. Raw library reports/HPROFs are private, and numeric baseline stays separate.
            output.writeText(evidence.toString(2))
        }
    }
}
