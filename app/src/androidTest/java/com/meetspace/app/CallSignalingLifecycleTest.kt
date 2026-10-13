package com.meetspace.app

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.meetspace.core.media.CallPhase
import java.io.File
import java.net.URI
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Regression uses a real fixture WebSocket and observes actual resource ownership on rejection. */
class CallSignalingLifecycleTest {
    @Test fun rejectedIceClosesAlreadyOpenedSignaling() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(InstrumentationRegistry.getArguments().getString("quality09") == "true")
        assumeTrue(File(context.filesDir, "live-call.properties").isFile)
        val app = context.applicationContext as LiveCallApplication
        val backend = app.liveBackend
        check(URI(backend.input.getProperty("origin")).host in setOf("10.0.2.2", "127.0.0.1", "localhost"))
        backend.login()
        val meeting = backend.conference(backend.input.getProperty("conferenceId"))
        val engine = app.callEngine
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use {
            instrumentation.waitForIdleSync()
            engine.leave()
            waitFor { engine.state.value.phase == CallPhase.IDLE }
            val openedBefore = backend.openedSessionCount.get()
            val closedBefore = backend.closedSessionCount.get()
            backend.malformedIceForQualityTest = true
            try {
                engine.prepare(meeting)
                waitFor { engine.state.value.phase == CallPhase.PREJOIN }
                engine.connect()
                waitFor { engine.state.value.phase == CallPhase.FAILED }
                assertEquals("The fixture must open a real WSS session before ICE validation", openedBefore + 1, backend.openedSessionCount.get())
                assertEquals("Rejected ICE must close its already opened signaling session", closedBefore + 1, backend.closedSessionCount.get())
            } finally {
                backend.malformedIceForQualityTest = false
                // Close the leaked baseline socket even if the expected regression assertion fails.
                backend.lastSocket?.cancel()
                engine.leave()
                waitFor { engine.state.value.phase == CallPhase.IDLE }
            }
        }
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        throw AssertionError("Quality signaling lifecycle timed out")
    }
}
