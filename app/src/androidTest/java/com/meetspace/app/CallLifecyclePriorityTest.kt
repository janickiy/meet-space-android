package com.meetspace.app

import android.content.Context
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.meetspace.core.data.CallRepository
import com.meetspace.core.data.CallSignaling
import com.meetspace.core.data.MeetingsRepository
import com.meetspace.core.media.CallEngine
import com.meetspace.core.media.CallPhase
import com.meetspace.core.media.CallState
import com.meetspace.core.model.Meeting
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.webrtc.CapturerObserver
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer

/** Cancellable control-plane gates; no backend, device capture or user projection consent. */
class CallLifecyclePriorityTest {
    @Test fun oldProjectionCallbackCannotInterruptANewerSessionInTheSameCall(): Unit = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cancelled = AtomicBoolean()
        val stopped = java.util.concurrent.atomic.AtomicInteger()
        val engine = CallEngine(InstrumentationRegistry.getInstrumentation().targetContext,
            unavailable(CallRepository::class.java), unavailable(MeetingsRepository::class.java)) { stopped.incrementAndGet() }
        try {
            actor(engine) {
                state(engine).value = CallState(phase = CallPhase.CONNECTED, conferenceId = meeting.id, sharing = true)
                setField(engine, "screenSessionActive", true)
                setField(engine, "screenSession", 2L)
            }
            enqueue(engine) {
                started.complete(Unit)
                try { release.await() } finally { if (!release.isCompleted) cancelled.set(true) }
            }
            withTimeout(5_000) { started.await() }
            CallEngine::class.java.getDeclaredMethod("requestScreenStop", java.lang.Long.TYPE, String::class.java, String::class.java)
                .apply { isAccessible = true }.invoke(engine, 1L, meeting.id, null)
            SystemClock.sleep(100)
            assertFalse("An old projection callback must not cancel the next session's operation", cancelled.get())
            release.complete(Unit)
            actor(engine) { assertTrue(engine.state.value.sharing); assertEquals(CallPhase.CONNECTED, engine.state.value.phase); assertEquals(0, stopped.get()) }
            engine.stopScreen(meeting.id)
            actor(engine) { assertFalse(engine.state.value.sharing); assertEquals(1, stopped.get()) }
        } finally { release.complete(Unit); finish(engine) }
    }

    @Test fun stoppingQueuedScreenStartDoesNotConsumeItsOldConsent(): Unit = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val stopped = java.util.concurrent.atomic.AtomicInteger()
        val engine = CallEngine(InstrumentationRegistry.getInstrumentation().targetContext,
            unavailable(CallRepository::class.java), unavailable(MeetingsRepository::class.java)) { stopped.incrementAndGet() }
        try {
            actor(engine) { state(engine).value = CallState(phase = CallPhase.CONNECTED, conferenceId = meeting.id) }
            enqueue(engine) { started.complete(Unit); release.await() }
            withTimeout(5_000) { started.await() }
            engine.startScreen(android.content.Intent(), meeting.id)
            engine.stopScreen(meeting.id)
            release.complete(Unit)
            actor(engine) {
                assertEquals("Stopped queued consent must not enter native startup", 0, stopped.get())
                assertFalse(engine.state.value.sharing)
                assertNull(field<Any?>(engine, "screenSource"))
                assertNull(field<Any?>(engine, "screenCapture"))
            }
        } finally { release.complete(Unit); finish(engine) }
    }

    @Test fun screenStopPreemptsSlowPresenceAndReleasesProjectionOwner(): Unit = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val stopped = java.util.concurrent.atomic.AtomicInteger()
        val engine = CallEngine(InstrumentationRegistry.getInstrumentation().targetContext,
            unavailable(CallRepository::class.java), unavailable(MeetingsRepository::class.java)) { stopped.incrementAndGet() }
        try {
            actor(engine) {
                state(engine).value = CallState(phase = CallPhase.CONNECTED, conferenceId = meeting.id, sharing = true)
                setField(engine, "screenSessionActive", true)
            }
            enqueue(engine) { started.complete(Unit); release.await() }
            withTimeout(5_000) { started.await() }
            engine.stopScreen(meeting.id)
            assertTrue("Stop must release the projection owner before a control-plane request finishes",
                waitUntil(3_000) { stopped.get() == 1 && !engine.state.value.sharing })
            assertFalse(release.isCompleted)
            engine.stopScreen(meeting.id)
            actor(engine) { assertEquals("Repeated stop cannot release another owner", 1, stopped.get()) }
        } finally { release.complete(Unit); finish(engine) }
    }

    @Test fun leavingCurrentDirectCallPreservesANewerQueuedPreview(): Unit = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val engine = newEngine(unavailable(MeetingsRepository::class.java))
        val previous = "66666666-6666-4666-8666-666666666666"
        val next = "88888888-8888-4888-8888-888888888888"
        try {
            actor(engine) { state(engine).value = CallState(phase = CallPhase.PREJOIN, directCallId = previous) }
            enqueue(engine) { started.complete(Unit); release.await() }
            withTimeout(5_000) { started.await() }
            engine.prepareDirectPreview(next, "Next")
            engine.leaveDirect(previous)
            assertTrue("Scoped leave must preserve a newer queued direct-call request", waitUntil(3_000) { engine.state.value.directCallId == next })
            assertFalse(release.isCompleted)
            engine.leaveDirect(previous)
            actor(engine) { assertEquals(next, engine.state.value.directCallId) }
        } finally { release.complete(Unit); finish(engine) }
    }

    @Test fun leavingQueuedDirectPreviewCannotOpenItOrCancelTheNextCall(): Unit = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val engine = newEngine(unavailable(MeetingsRepository::class.java))
        val previous = "66666666-6666-4666-8666-666666666666"
        val next = "88888888-8888-4888-8888-888888888888"
        try {
            enqueue(engine) { started.complete(Unit); release.await() }
            withTimeout(5_000) { started.await() }
            engine.prepareDirectPreview(previous, "Previous")
            engine.leaveDirect(previous)
            engine.prepareDirectPreview(next, "Next")
            release.complete(Unit)
            actor(engine) { assertEquals(next, engine.state.value.directCallId); assertEquals(CallPhase.PREJOIN, engine.state.value.phase) }
            engine.leaveDirect(previous)
            actor(engine) { assertEquals("Late cleanup of an old ID cannot cancel the newer call", next, engine.state.value.directCallId) }
            engine.leaveDirect(next)
            actor(engine) { assertEquals(CallPhase.IDLE, engine.state.value.phase) }
        } finally { release.complete(Unit); finish(engine) }
    }

    @Test fun idleEstablishedScreenShareSurvivesDeviceOnlyPause() {
        val stoppedProjection = java.util.concurrent.atomic.AtomicInteger()
        val engine = CallEngine(InstrumentationRegistry.getInstrumentation().targetContext,
            unavailable(CallRepository::class.java), unavailable(MeetingsRepository::class.java)) { stoppedProjection.incrementAndGet() }
        val capture = OwnedCapture()
        try {
            actor(engine) {
                state(engine).value = CallState(phase = CallPhase.CONNECTED, conferenceId = meeting.id, camera = true, microphone = true, sharing = true)
                setField(engine, "cameraCapture", capture)
                setField(engine, "screenSessionActive", true)
            }
            engine.pauseDevices()
            actor(engine) {
                assertTrue(capture.stopped.get())
                assertFalse(engine.state.value.camera); assertFalse(engine.state.value.microphone)
                assertTrue(engine.state.value.sharing)
                assertEquals(CallPhase.CONNECTED, engine.state.value.phase)
                assertEquals(0, stoppedProjection.get())
            }
        } finally { finish(engine) }
    }

    @Test fun pauseDuringSlowConferenceLookupReleasesCameraBeforeTheRequestCompletes(): Unit = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Meeting>()
        val meetings = object : MeetingsRepository by unavailable(MeetingsRepository::class.java) {
            override suspend fun conference(id: String): Meeting { started.complete(Unit); return release.await() }
        }
        val engine = newEngine(meetings)
        val capture = OwnedCapture()
        try {
            actor(engine) {
                state(engine).value = CallState(phase = CallPhase.PREJOIN, conferenceId = meeting.id, title = meeting.title, camera = true)
                setField(engine, "cameraCapture", capture)
            }
            engine.connect()
            withTimeout(5_000) { started.await() }
            engine.microphone(true); engine.camera(true)
            engine.pauseDevices()
            assertTrue("Background pause must release the owned camera before a slow request finishes",
                waitUntil(3_000) { capture.stopped.get() && !engine.state.value.camera })
            assertFalse("Cleanup must not wait for a successful network response", release.isCompleted)
            assertTrue("Interrupted setup remains an explicit retry state", engine.state.value.phase in setOf(CallPhase.FAILED, CallPhase.PREJOIN))
            assertNull(engine.state.value.localVideo)
            actor(engine) { assertNull("Old queued enables cannot recreate the factory", field<Any?>(engine, "factory")) }
            engine.leave(updateMembership = false)
            actor(engine) { assertEquals(CallPhase.IDLE, engine.state.value.phase) }
            engine.prepare(meeting)
            actor(engine) { assertEquals("Lifecycle cancellation must retain the actor", CallPhase.PREJOIN, engine.state.value.phase) }
        } finally {
            release.completeExceptionally(IllegalStateException("Offline gate cleanup"))
            finish(engine)
        }
    }

    @Test fun leavePreemptsSlowPresenceAndRepeatedLifecycleRequestsKeepActorUsable(): Unit = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val signaling = object : CallSignaling {
            override val iceConfiguration = "unused"
            override val messages = Channel<String>(1)
            val closed = AtomicBoolean()
            override fun send(envelope: String) = true
            override suspend fun presence(connectionId: String, sequence: Long, microphone: Boolean, camera: Boolean, screen: Boolean) {
                started.complete(Unit); release.await()
            }
            override fun close() { closed.set(true); messages.close() }
        }
        val engine = newEngine(unavailable(MeetingsRepository::class.java))
        val capture = OwnedCapture()
        try {
            actor(engine) {
                state(engine).value = CallState(phase = CallPhase.CONNECTED, conferenceId = meeting.id, title = meeting.title, microphone = true, camera = true)
                setField(engine, "cameraCapture", capture)
                setField(engine, "signaling", signaling)
            }
            enqueue(engine) { signaling.presence("offline", 1, true, true, false) }
            withTimeout(5_000) { started.await() }
            // These enables belong to the old call and may not restore devices after leave.
            engine.microphone(true); engine.camera(true)
            engine.leave(updateMembership = false)
            assertTrue("Leave must close signaling and capture without waiting for presence",
                waitUntil(3_000) { signaling.closed.get() && capture.stopped.get() && engine.state.value.phase == CallPhase.IDLE })
            assertFalse(release.isCompleted)
            actor(engine) {
                assertFalse(engine.state.value.microphone); assertFalse(engine.state.value.camera)
                assertNull(field<Any?>(engine, "factory"))
            }
            repeat(3) { engine.pauseDevices(); engine.leave(updateMembership = false) }
            actor(engine) { assertEquals(CallPhase.IDLE, engine.state.value.phase) }
            engine.prepare(meeting)
            actor(engine) { assertEquals(CallPhase.PREJOIN, engine.state.value.phase) }
        } finally {
            release.complete(Unit)
            finish(engine)
        }
    }

    private class OwnedCapture : VideoCapturer {
        val stopped = AtomicBoolean()
        override fun initialize(helper: SurfaceTextureHelper?, context: Context?, observer: CapturerObserver?) = Unit
        override fun startCapture(width: Int, height: Int, framerate: Int) = throw AssertionError("This test never starts hardware capture")
        override fun stopCapture() { stopped.set(true) }
        override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) = Unit
        override fun dispose() { stopped.set(true) }
        override fun isScreencast() = false
    }

    private fun newEngine(meetings: MeetingsRepository) = CallEngine(
        InstrumentationRegistry.getInstrumentation().targetContext, unavailable(CallRepository::class.java), meetings)
    private fun finish(engine: CallEngine) {
        engine.leave(updateMembership = false)
        try { actor(engine) { assertEquals(CallPhase.IDLE, engine.state.value.phase) } }
        finally { field<CoroutineScope>(engine, "scope").cancel(); field<ExecutorCoroutineDispatcher>(engine, "dispatcher").close() }
    }
    private fun waitUntil(timeout: Long, condition: () -> Boolean): Boolean {
        val end = SystemClock.elapsedRealtime() + timeout
        while (!condition() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(10)
        return condition()
    }
    private fun enqueue(engine: CallEngine, command: suspend () -> Unit) {
        CallEngine::class.java.declaredMethods.single { it.name == "enqueue" }.apply { isAccessible = true }.invoke(engine, command)
    }
    private fun <T> actor(engine: CallEngine, operation: () -> T): T {
        val result = CompletableDeferred<T>()
        enqueue(engine) { try { result.complete(operation()) } catch (failure: Throwable) { result.completeExceptionally(failure) } }
        return runBlocking { withTimeout(10_000) { result.await() } }
    }
    @Suppress("UNCHECKED_CAST")
    private fun state(engine: CallEngine): MutableStateFlow<CallState> = field(engine, "mutable")
    @Suppress("UNCHECKED_CAST")
    private fun <T> field(engine: CallEngine, name: String): T = CallEngine::class.java.getDeclaredField(name).apply { isAccessible = true }.get(engine) as T
    private fun setField(engine: CallEngine, name: String, value: Any) { CallEngine::class.java.getDeclaredField(name).apply { isAccessible = true }.set(engine, value) }
    @Suppress("UNCHECKED_CAST")
    private fun <T> unavailable(type: Class<T>): T = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
        throw IllegalStateException("Offline test does not provide ${method.name}")
    } as T
    private companion object {
        val meeting = Meeting("77777777-7777-4777-8777-777777777777", "owner", "Offline lifecycle", "", "", "active", Instant.EPOCH, Instant.EPOCH)
    }
}
