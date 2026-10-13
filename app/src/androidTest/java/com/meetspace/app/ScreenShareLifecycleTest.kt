package com.meetspace.app

import android.content.Intent
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.meetspace.core.data.CallRepository
import com.meetspace.core.data.MeetingsRepository
import com.meetspace.core.media.CallEngine
import com.meetspace.core.media.CallPhase
import com.meetspace.core.media.CallState
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.webrtc.PeerConnectionFactory

/**
 * Real Android/native startup rejection, without a backend or projection consent.
 * Test-only reflection supplies the connected precondition and inspects ownership.
 * This does not prove successful MediaProjection capture, rotation, reconnect or FPS.
 */
class ScreenShareLifecycleTest {
    @Test fun staleDirectCallCleanupAndConsentCannotAffectTheNextCall() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val stopped = AtomicInteger()
        val engine = CallEngine(context, unavailable(CallRepository::class.java), unavailable(MeetingsRepository::class.java)) { stopped.incrementAndGet() }
        try {
            actor(engine) { state(engine).value = CallState(phase = CallPhase.CONNECTED,
                conferenceId = "next-conference", directCallId = "next-call", sharing = true) }
            engine.leaveDirect("previous-call")
            engine.stopScreen("previous-conference", "previous-call")
            engine.startScreen(Intent(), "previous-conference", "previous-call")
            actor(engine) {
                assertEquals("next-call", engine.state.value.directCallId)
                assertEquals(CallPhase.CONNECTED, engine.state.value.phase)
                assertTrue("Old cleanup cannot change the next call's screen state", engine.state.value.sharing)
                assertNull(field<Any>(engine, "factory"))
                assertNull(field<Any>(engine, "screenCapture"))
                assertEquals("Rejected consent releases only its pending foreground request", 1, stopped.get())
            }
        } finally {
            engine.leaveDirect("next-call")
            actor(engine) { assertEquals(CallPhase.IDLE, engine.state.value.phase) }
            field<CoroutineScope>(engine, "scope").cancel()
            field<ExecutorCoroutineDispatcher>(engine, "dispatcher").close()
        }
    }

    @Test fun rejectedProjectionReleasesNativeResourcesAndAllowsAnotherAttempt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val stopped = AtomicInteger()
        val existingScreenThreads = screenThreads()
        val engine = CallEngine(context, unavailable(CallRepository::class.java),
            unavailable(MeetingsRepository::class.java)) { stopped.incrementAndGet() }
        val evidence = JSONObject().put("successfulProjectionStarted", false)
            .put("networkSessionOpened", false).put("fpsMeasured", false)
        try {
            actor(engine) {
                CallEngine::class.java.getDeclaredMethod("ensureFactory").apply {
                    isAccessible = true
                }.invoke(engine)
                state(engine).value = CallState(phase = CallPhase.CONNECTED,
                    conferenceId = "screen-cleanup-test", title = "Screen cleanup test")
            }
            val factory = actor(engine) { field<PeerConnectionFactory>(engine, "factory") }
            assertNotNull("The real native factory must exist before the startup fault", factory)
            repeat(3) { cycle ->
                val previousSession = actor(engine) {
                    state(engine).value = engine.state.value.copy(error = null)
                    field<Long>(engine, "screenSession")
                }
                // Android must reject this empty Intent: no user consent token is supplied.
                // The production startup path has already taken ownership of native resources.
                engine.startScreen(Intent())
                actor(engine) {
                    assertScreenReleased(engine)
                    assertTrue("A failed attempt must invalidate its callback session",
                        field<Long>(engine, "screenSession") > previousSession)
                    assertEquals("Screen startup failure must keep the existing call available",
                        CallPhase.CONNECTED, engine.state.value.phase)
                    assertNotNull("Rejected consent must be visible to the caller", engine.state.value.error)
                    assertSame("A screen failure must not dispose the call's native factory",
                        factory, field<PeerConnectionFactory>(engine, "factory"))
                    assertEquals("Foreground ownership must be released once per failed attempt",
                        cycle + 1, stopped.get())
                }
                assertNoNewScreenThread(existingScreenThreads)
                engine.stopScreen()
                engine.stopScreen()
                actor(engine) {
                    assertScreenReleased(engine)
                    assertEquals("Repeated stop must not release the foreground owner again",
                        cycle + 1, stopped.get())
                }
            }
            engine.leave(updateMembership = false)
            actor(engine) {
                assertEquals(CallPhase.IDLE, engine.state.value.phase)
                assertScreenReleased(engine)
                assertNull(field<Any>(engine, "factory"))
                assertNull(field<Any>(engine, "egl"))
                assertNull(field<Any>(engine, "audioModule"))
            }
            evidence.put("rejectedStartupCycles", 3).put("repeatedStopIsIdempotent", true)
                .put("allScreenOwnershipCleared", true).put("callFactoryReleasedOnLeave", true)
                .put("screenHelperThreadTerminated", true)
                .put("projectionOwnerReleasedCount", stopped.get()).put("status", "passed")
        } finally {
            // This dedicated test engine, unlike the application singleton, has no next call.
            // Dispose its process-lifetime actor too, so the test adds no executor/job leak.
            engine.leave(updateMembership = false)
            try { actor(engine) { assertScreenReleased(engine) } }
            finally {
                field<CoroutineScope>(engine, "scope").cancel()
                field<ExecutorCoroutineDispatcher>(engine, "dispatcher").close()
                File(context.cacheDir, "screen-share-startup-cleanup.json").writeText(evidence.toString(2))
            }
        }
    }

    private fun assertScreenReleased(engine: CallEngine) {
        for (name in listOf("screenCapture", "screenSource", "screenTrack", "screenHelper")) {
            assertNull("Screen resource $name must be released", field<Any>(engine, name))
        }
        assertFalse(field<Boolean>(engine, "screenSessionActive"))
        assertFalse(engine.state.value.sharing)
        assertNull(engine.state.value.localScreen)
        assertEquals(0L, engine.state.value.diagnostics.screenFramesEncoded)
        assertNull(engine.state.value.diagnostics.screenEncodedFps)
    }

    private fun screenThreads(): Set<Thread> =
        Thread.getAllStackTraces().keys.filter { it.isAlive && it.name == "MeetSpace-screen" }.toSet()

    private fun assertNoNewScreenThread(existing: Set<Thread>) {
        val deadline = SystemClock.elapsedRealtime() + 3_000
        while (screenThreads().any { it !in existing } && SystemClock.elapsedRealtime() < deadline) {
            // Test-only wait for HandlerThread shutdown, never used to pace captured frames.
            Thread.sleep(10)
        }
        assertTrue("The rejected startup must terminate its SurfaceTextureHelper thread",
            screenThreads().all { it in existing })
    }

    private fun <T> actor(engine: CallEngine, operation: () -> T): T {
        val result = CompletableDeferred<T>()
        val command: suspend () -> Unit = {
            try { result.complete(operation()) }
            catch (failure: Throwable) { result.completeExceptionally(failure) }
        }
        CallEngine::class.java.declaredMethods.single { it.name == "enqueue" }.apply {
            isAccessible = true
        }.invoke(engine, command)
        return runBlocking { withTimeout(20_000) { result.await() } }
    }

    @Suppress("UNCHECKED_CAST")
    private fun state(engine: CallEngine): MutableStateFlow<CallState> = field(engine, "mutable")

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(engine: CallEngine, name: String): T =
        CallEngine::class.java.getDeclaredField(name).apply { isAccessible = true }.get(engine) as T

    @Suppress("UNCHECKED_CAST")
    private fun <T> unavailable(type: Class<T>): T = Proxy.newProxyInstance(type.classLoader,
        arrayOf(type)) { _, method, _ ->
        throw AssertionError("This native rejection test must not call backend method ${method.name}")
    } as T
}
