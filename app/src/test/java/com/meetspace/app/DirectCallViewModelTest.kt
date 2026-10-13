package com.meetspace.app

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelProvider
import com.meetspace.core.data.*
import com.meetspace.core.media.*
import com.meetspace.core.model.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DirectCallViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val stores = mutableListOf<ViewModelStore>()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { stores.forEach { it.clear() }; Dispatchers.resetMain() }
    private fun runModelTest(block: suspend TestScope.() -> Unit) = runTest(dispatcher) {
        try { block() } finally { stores.forEach { it.clear() }; stores.clear(); runCurrent() }
    }
    private val now = Instant.parse("2026-10-12T10:00:00Z")
    private val peer = ChatPeer("peer", "Собеседник")
    private fun model(repo: FakeCalls, foundation: FakeFoundation = FakeFoundation(), media: FakeMedia = FakeMedia(), clock: Clock = Clock.fixed(now, ZoneId.of("UTC"))): DirectCallViewModel {
        val store = ViewModelStore().also(stores::add)
        return ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : androidx.lifecycle.ViewModel> create(c: Class<T>) = DirectCallViewModel(foundation, repo, media, clock) as T
        })[DirectCallViewModel::class.java]
    }
    private fun call(client: String, status: String = "ringing", incoming: Boolean = false, version: Long = 1) = DirectCall(
        "call", "conversation", if (status == "ringing") null else "conference", if (incoming) "peer" else "self", if (incoming) "self" else "peer",
        if (incoming) "caller-client" else client, if (status == "accepted") if (incoming) client else "callee-client" else null,
        ChatPeer(if (incoming) "peer" else "self", "Инициатор"), ChatPeer(if (incoming) "self" else "peer", "Получатель"), status, version,
        now, now.plusSeconds(45), if (status in setOf("accepted", "ended")) now else null, if (status == "ended") now.plusSeconds(20) else null,
        if (status == "ended") 20 else 0)

    @Test fun uncertainCreateRetriesSameRequestAndNeverCallsDifferentPeer() = runModelTest {
        val repo = FakeCalls(); val model = model(repo)
        repo.start = { _, _, _ -> throw AppFailure(FailureKind.TIMEOUT, "Ответ потерян") }
        model.start(peer); runCurrent()
        assertTrue(model.state.value.uncertain)
        val first = repo.starts.single()
        model.start(ChatPeer("different", "Другой")); runCurrent()
        assertEquals(1, repo.starts.size)
        repo.start = { _, _, client -> call(client) }
        model.retryStart(); runCurrent()
        assertEquals(first, repo.starts.last())
        assertEquals("call", model.state.value.call?.id)
    }
    @Test fun incomingNeverCapturesOrAcceptsUntilExplicitActionAndOnlyOwnerJoins() = runModelTest {
        val repo = FakeCalls(); val media = FakeMedia(); val model = model(repo, media = media)
        repo.active = call("foreign-device", incoming = true)
        model.foreground(true); runCurrent()
        assertTrue(model.state.value.incoming)
        assertEquals(0, media.connections)
        assertEquals(emptyList<Boolean>(), media.microphones)
        assertEquals(emptyList<Boolean>(), media.cameras)
        repo.act = { _, action, client ->
            if (action == DirectCallAction.ACCEPT) call(client, "accepted", true, 2).also { repo.active = it } else repo.active!!
        }
        model.accept(false, true); runCurrent()
        assertEquals(1, media.connections)
        assertEquals(false to true, media.devices)
        assertEquals(model.state.value.clientId, media.clientId)
        model.refresh(); model.event(UserEvent("event", "call.updated")); runCurrent()
        assertEquals(1, media.connections)
    }
    @Test fun acceptedOnOtherDeviceDoesNotJoinOrEndIt() = runModelTest {
        val repo = FakeCalls(); val media = FakeMedia(); val model = model(repo, media = media)
        repo.active = call("other-device", "accepted", incoming = true, version = 2)
        model.foreground(true); runCurrent()
        assertTrue(model.state.value.otherDevice)
        assertEquals(0, media.connections)
        model.end(); runCurrent()
        assertTrue(repo.actions.isEmpty())
        assertFalse(model.state.value.visible)
    }
    @Test fun staleSnapshotCannotRegressAcceptedAndTerminalStopsMedia() = runModelTest {
        val repo = FakeCalls(); val media = FakeMedia(); val model = model(repo, media = media)
        val client = model.state.value.clientId
        repo.active = call(client, "accepted", version = 3)
        model.foreground(true); runCurrent()
        assertEquals(1, media.connections)
        repo.active = call(client, version = 1)
        model.refresh(); runCurrent()
        assertEquals("accepted", model.state.value.call?.status)
        repo.active = null; repo.read = { call(client, "ended", version = 4) }
        model.refresh(); runCurrent()
        assertEquals("ended", model.state.value.call?.status)
        assertEquals(CallPhase.IDLE, media.state.value.phase)
    }
    @Test fun lateCreateFromOldAccountCannotOpenCallOrPublishMedia() = runModelTest {
        val repo = FakeCalls(); val foundation = FakeFoundation(); val media = FakeMedia(); val model = model(repo, foundation, media)
        val gate = CompletableDeferred<DirectCall>(); val client = model.state.value.clientId
        repo.start = { _, _, _ -> withContext(NonCancellable) { gate.await() } }
        model.start(peer); runCurrent()
        foundation.state.value = foundation.state.value.copy(session = AccountSession(User("other", "other@example.test"), Capabilities(), true))
        runCurrent(); gate.complete(call(client)); runCurrent()
        assertNull(model.state.value.call)
        assertFalse(model.state.value.visible)
        assertEquals(0, media.connections)
        assertNotEquals(client, model.state.value.clientId)
    }
    @Test fun queuedCreateCannotRunUnderChangedAccountBeforeIdentityCollectorResets() = runModelTest {
        val repo = FakeCalls(); val foundation = FakeFoundation(); val model = model(repo, foundation)
        model.start(peer)
        foundation.state.value = foundation.state.value.copy(session = AccountSession(User("other", "other@example.test"), Capabilities(), true))
        runCurrent()
        assertTrue(repo.starts.isEmpty())
        assertNull(model.state.value.call)
    }
    @Test fun queuedEndCannotRunUnderChangedAccount() = runModelTest {
        val repo = FakeCalls(); val foundation = FakeFoundation(); val model = model(repo, foundation)
        repo.active = call(model.state.value.clientId, "accepted", version = 2)
        model.foreground(true); runCurrent(); repo.actions.clear()
        model.end()
        foundation.state.value = foundation.state.value.copy(session = AccountSession(User("other", "other@example.test"), Capabilities(), true))
        runCurrent()
        assertTrue(repo.actions.isEmpty())
        assertNull(model.state.value.call)
    }
    @Test fun meetingInProgressBlocksCreatingOrAcceptingPersonalCall() = runModelTest {
        val repo = FakeCalls(); val media = FakeMedia().apply { state.value = CallState(phase = CallPhase.CONNECTED, conferenceId = "meeting") }
        val model = model(repo, media = media)
        model.start(peer); runCurrent()
        assertTrue(repo.starts.isEmpty())
        repo.active = call("incoming", incoming = true)
        model.foreground(true); runCurrent(); model.accept(true, false); runCurrent()
        assertTrue(repo.actions.isEmpty())
        assertEquals("meeting", media.state.value.conferenceId)
    }
    @Test fun failedEndStopsDevicesAndNeverReattachesFromPolling() = runModelTest {
        val repo = FakeCalls(); val media = FakeMedia(); val model = model(repo, media = media)
        repo.active = call(model.state.value.clientId, "accepted", version = 2)
        repo.act = { _, action, _ -> if (action == DirectCallAction.END) throw AppFailure(FailureKind.TIMEOUT, "Нет ответа") else repo.active!! }
        model.foreground(true); runCurrent(); model.end(); runCurrent()
        assertTrue(model.state.value.ending)
        assertEquals(CallPhase.IDLE, media.state.value.phase)
        model.refresh(); runCurrent()
        assertEquals(1, media.connections)
        assertEquals(1, repo.actions.count { it == DirectCallAction.HEARTBEAT })
    }
    @Test fun minimizedCallKeepsOwnerHeartbeatAndForegroundRecoveryDoesNotCreateAgain() = runModelTest {
        val repo = FakeCalls(); val media = FakeMedia(); val clock = TestClock(now); val model = model(repo, media = media, clock = clock)
        repo.active = call(model.state.value.clientId, "accepted", version = 2)
        model.foreground(true); runCurrent(); model.minimize(); model.foreground(false)
        clock.time = now.plusSeconds(11); advanceTimeBy(10_000); runCurrent()
        assertTrue(repo.actions.count { it == DirectCallAction.HEARTBEAT } >= 2)
        model.foreground(true); runCurrent()
        assertEquals(1, media.connections)
        assertTrue(repo.starts.isEmpty())
    }
    @Test fun acceptanceWhileBackgroundDoesNotReenablePausedDevicesOnFirstAttachment() = runModelTest {
        val repo = FakeCalls(); val media = FakeMedia(); val model = model(repo, media = media)
        val client = model.state.value.clientId
        repo.active = call(client)
        model.foreground(true); runCurrent(); model.camera(true)
        model.foreground(false)
        repo.active = call(client, "accepted", version = 2)
        model.refresh(); runCurrent()
        assertEquals(0, media.connections)
        model.foreground(true); runCurrent()
        assertEquals(1, media.connections)
        assertEquals(false to false, media.devices)
    }
    @Test fun ringingCancelAndIncomingDeclineUseDifferentCommands() = runModelTest {
        val repo = FakeCalls(); val model = model(repo)
        repo.active = call(model.state.value.clientId)
        repo.act = { _, action, client -> call(client, if (action == DirectCallAction.CANCEL) "cancelled" else "declined", version = 2) }
        model.foreground(true); runCurrent(); model.end(); runCurrent()
        assertEquals(DirectCallAction.CANCEL, repo.actions.single())
        model.dismiss(); repo.active = call("foreign", incoming = true); model.refresh(); runCurrent()
        model.decline(); runCurrent(); assertEquals(DirectCallAction.DECLINE, repo.actions.last())
    }
    @Test fun selectedMutedMicrophoneSurvivesCreateResponse() = runModelTest {
        val repo = FakeCalls(); repo.start = { _, _, client -> call(client) }
        val model = model(repo)
        model.start(peer, microphone = false); runCurrent()
        assertFalse(model.state.value.microphoneWanted)
    }
    @Test fun newCallInvalidatesLateEndResponseAndResetsBusyState() = runModelTest {
        val repo = FakeCalls(); val media = FakeMedia(); val model = model(repo, media = media)
        val client = model.state.value.clientId
        repo.active = call(client, "accepted", version = 2)
        val gate = CompletableDeferred<DirectCall>()
        repo.act = { _, action, _ -> if (action == DirectCallAction.END) withContext(NonCancellable) { gate.await() } else repo.active!! }
        model.foreground(true); runCurrent(); model.end(); runCurrent()
        repo.active = call("new-caller", incoming = true).copy(id = "new-call", conversationId = "new-chat")
        model.refresh(); runCurrent()
        assertEquals("new-call", model.state.value.call?.id)
        assertFalse(model.state.value.busy)
        gate.complete(call(client, "ended", version = 3)); runCurrent()
        assertEquals("new-call", model.state.value.call?.id)
        assertEquals("new-call", media.state.value.directCallId)
    }
    @Test fun reconciliationOfUnacknowledgedCreateReleasesBusyWithoutWaitingForLostReply() = runModelTest {
        val repo = FakeCalls(); val model = model(repo)
        val gate = CompletableDeferred<DirectCall>(); val client = model.state.value.clientId
        repo.start = { _, _, _ -> withContext(NonCancellable) { gate.await() } }
        model.start(peer); runCurrent()
        repo.active = call(client)
        model.refresh(); runCurrent()
        assertFalse(model.state.value.busy)
        assertEquals("call", model.state.value.call?.id)
        gate.completeExceptionally(AppFailure(FailureKind.TIMEOUT, "Late failed reply")); runCurrent()
        assertNull(model.state.value.error)
        assertFalse(model.state.value.busy)
    }
    private class FakeMedia : DirectCallMedia {
        override val state = MutableStateFlow(CallState())
        var connections = 0; var devices = false to false; var clientId = ""
        val microphones = mutableListOf<Boolean>(); val cameras = mutableListOf<Boolean>()
        override fun preview(call: DirectCall, name: String) { state.value = CallState(phase = CallPhase.PREJOIN, directCallId = call.id) }
        override fun connect(call: DirectCall, clientId: String, name: String, microphone: Boolean, camera: Boolean) {
            connections++; devices = microphone to camera; this.clientId = clientId
            state.value = CallState(phase = CallPhase.CONNECTED, directCallId = call.id, conferenceId = call.conferenceId.orEmpty())
        }
        override fun microphone(enabled: Boolean) { microphones += enabled }
        override fun camera(enabled: Boolean) { cameras += enabled }
        override fun stop(callId: String) { if (state.value.directCallId == callId) state.value = CallState() }
    }
    private class FakeCalls : DirectCallsRepository {
        var active: DirectCall? = null
        val starts = mutableListOf<Triple<String, String, String>>()
        val actions = mutableListOf<DirectCallAction>()
        var start: suspend (String, String, String) -> DirectCall = { _, _, _ -> error("unset") }
        var act: suspend (String, DirectCallAction, String) -> DirectCall = { _, _, _ -> active!! }
        var read: suspend (String) -> DirectCall = { active!! }
        override suspend fun startDirectCall(targetUserId: String, clientRequestId: String, clientId: String): DirectCall {
            starts += Triple(targetUserId, clientRequestId, clientId); return start(targetUserId, clientRequestId, clientId)
        }
        override suspend fun activeDirectCall() = active
        override suspend fun directCall(id: String) = read(id)
        override suspend fun directCallAction(id: String, action: DirectCallAction, clientId: String): DirectCall { actions += action; return act(id, action, clientId) }
    }
    private class FakeFoundation : FoundationRepository {
        override val state = MutableStateFlow(RepositoryState(listOf(ServerProfile("profile", "https://example.test")), "profile",
            session = AccountSession(User("self", "self@example.test"), Capabilities(), true)))
        override suspend fun addServer(rawUrl: String) = error("unused")
        override suspend fun selectServer(profileId: String) {}
        override suspend fun removeServer(profileId: String) {}
        override suspend fun login(email: String, password: String) {}
        override suspend fun restore() {}
        override suspend fun logout() = LogoutResult(true)
        override suspend fun setTheme(mode: ThemeMode) {}
    }
    private class TestClock(var time: Instant) : Clock() {
        override fun instant() = time
        override fun getZone() = ZoneId.of("UTC")
        override fun withZone(zone: ZoneId) = this
    }
}
