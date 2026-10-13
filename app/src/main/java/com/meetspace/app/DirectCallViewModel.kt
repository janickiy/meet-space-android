package com.meetspace.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meetspace.core.data.DirectCallsRepository
import com.meetspace.core.data.FoundationRepository
import com.meetspace.core.media.CallEngine
import com.meetspace.core.media.CallPhase
import com.meetspace.core.media.CallState
import com.meetspace.core.model.*
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** One transient Android client owns a call. Client ids are never restored after process death. */
internal interface DirectCallMedia {
    val state: StateFlow<CallState>
    fun preview(call: DirectCall, name: String)
    fun connect(call: DirectCall, clientId: String, name: String, microphone: Boolean, camera: Boolean)
    fun microphone(enabled: Boolean)
    fun camera(enabled: Boolean)
    fun stop(callId: String)
}

internal class EngineDirectCallMedia(private val engine: CallEngine) : DirectCallMedia {
    override val state get() = engine.state
    override fun preview(call: DirectCall, name: String) = engine.prepareDirectPreview(call.id, name)
    override fun connect(call: DirectCall, clientId: String, name: String, microphone: Boolean, camera: Boolean) {
        engine.joinDirect(call.conferenceId ?: return, name, clientId, microphone, camera, call.id)
    }
    override fun microphone(enabled: Boolean) = engine.microphone(enabled)
    override fun camera(enabled: Boolean) = engine.camera(enabled)
    override fun stop(callId: String) = engine.leaveDirect(callId)
}

data class DirectCallSession(
    val call: DirectCall? = null,
    val pendingPeer: ChatPeer? = null,
    val selfId: String = "",
    val clientId: String = "",
    val minimized: Boolean = false,
    val busy: Boolean = false,
    val uncertain: Boolean = false,
    val ending: Boolean = false,
    val microphoneWanted: Boolean = true,
    val cameraWanted: Boolean = false,
    val elapsedSeconds: Long = 0,
    val error: String? = null,
) {
    val active: Boolean get() = call?.active == true || pendingPeer != null
    val visible: Boolean get() = (call != null || pendingPeer != null) && !minimized
    val incoming: Boolean get() = call?.let { it.status == "ringing" && it.calleeUserId == selfId } == true
    val ownsMedia: Boolean get() = call?.ownsMedia(selfId, clientId) == true
    val peer: ChatPeer? get() = call?.peer(selfId) ?: pendingPeer
    val canControl: Boolean get() = !ending && (incoming || ownsMedia) && call?.active == true
    val otherDevice: Boolean get() = call?.active == true && !incoming && !ownsMedia
}

class DirectCallViewModel internal constructor(
    private val foundation: FoundationRepository,
    private val repository: DirectCallsRepository,
    private val media: DirectCallMedia,
    private val clock: Clock = Clock.systemUTC(),
    private val defaultMicrophone: () -> Boolean = { true },
) : ViewModel() {
    private var account = identity()
    private var clientId = UUID.randomUUID().toString()
    private val mutable = MutableStateFlow(DirectCallSession(selfId = selfId(), clientId = clientId))
    val state = mutable.asStateFlow()
    private var foreground = false
    private var generation = 0L
    private var poll: Job? = null
    private var refreshJob: Job? = null
    private var refreshAgain = false
    private var command: Job? = null
    private var commandToken = 0L
    private var heartbeat: Job? = null
    private var lastHeartbeat: Instant? = null
    private var attachedCall: String? = null
    private var previewCall: String? = null
    private data class Attempt(val peer: ChatPeer, val requestId: String)
    private var attempt: Attempt? = null

    init {
        viewModelScope.launch {
            foundation.state.collect {
                if (account != identity()) {
                    reset(); account = identity(); clientId = UUID.randomUUID().toString()
                    mutable.value = DirectCallSession(selfId = selfId(), clientId = clientId)
                    if (foreground) startPolling()
                }
            }
        }
        viewModelScope.launch {
            media.state.collect { value ->
                if (attachedCall != null && value.phase == CallPhase.FAILED) {
                    mutable.update { it.copy(error = value.error ?: "Не удалось подключить звук. Завершите звонок и повторите.") }
                }
            }
        }
    }

    fun foreground(value: Boolean) {
        foreground = value
        if (value) { startPolling(); refresh() }
        else {
            // Match Activity.onStop: a later acceptance must not restore paused devices.
            if (state.value.active) mutable.update { it.copy(microphoneWanted = false, cameraWanted = false) }
            else { poll?.cancel(); poll = null }
        }
    }

    /** Existing account WebSocket supplies invalidations, never trusted media authorisation. */
    fun event(event: UserEvent) {
        if (event.type == "connected" || event.type == "call.updated") refresh()
    }

    fun start(peer: ChatPeer, microphone: Boolean = defaultMicrophone()) {
        if (identity() == null || peer.id == selfId() || state.value.busy) return
        if (state.value.call?.active == true) {
            resume()
            if (state.value.peer?.id != peer.id) message("Сначала завершите текущий личный звонок.")
            return
        }
        if (media.state.value.visible && media.state.value.directCallId == null) {
            message("Сначала завершите текущую встречу."); return
        }
        val existing = attempt
        if (existing != null && existing.peer.id != peer.id) {
            resume(); message("Сначала проверьте результат предыдущего вызова."); return
        }
        if (existing == null) {
            attempt = Attempt(peer, UUID.randomUUID().toString())
            attachedCall = null; previewCall = null
            mutable.value = DirectCallSession(pendingPeer = peer, selfId = selfId(), clientId = clientId,
                microphoneWanted = microphone)
        }
        retryStart()
    }

    fun retryStart() {
        val request = attempt ?: return
        if (state.value.busy) return
        runCommand({ repository.startDirectCall(request.peer.id, request.requestId, clientId) },
            failed = { error ->
                val uncertain = error.kind in setOf(FailureKind.NETWORK, FailureKind.TIMEOUT, FailureKind.UNAVAILABLE, FailureKind.INVALID_RESPONSE)
                if (!uncertain) attempt = null
                mutable.update { it.copy(busy = false, uncertain = uncertain,
                    pendingPeer = if (uncertain) request.peer else null, error = error.message) }
                refresh()
            }) { call ->
            apply(call, fromCommand = true)
        }
    }

    fun accept(microphone: Boolean, camera: Boolean) {
        val call = state.value.call ?: return
        if (!state.value.incoming || state.value.busy) return
        if (media.state.value.visible && media.state.value.directCallId != call.id) {
            message("Сначала завершите текущую встречу."); return
        }
        mutable.update { it.copy(microphoneWanted = microphone, cameraWanted = camera) }
        action(call, DirectCallAction.ACCEPT)
    }

    fun decline() {
        val call = state.value.call ?: return
        if (state.value.incoming) action(call, DirectCallAction.DECLINE, ending = true)
    }

    fun end() {
        val call = state.value.call
        if (state.value.busy) return
        if (call == null) {
            if (attempt != null) { message("Проверьте результат вызова перед закрытием."); retryStart() }
            else dismiss()
            return
        }
        when {
            !call.active || state.value.otherDevice -> dismiss()
            state.value.incoming -> decline()
            call.status == "ringing" -> action(call, DirectCallAction.CANCEL, ending = true)
            else -> action(call, DirectCallAction.END, ending = true)
        }
    }

    private fun action(call: DirectCall, action: DirectCallAction, ending: Boolean = false) {
        if (state.value.busy || identity() == null) return
        if (ending) {
            stopMedia(); mutable.update { it.copy(ending = true) }
            heartbeat?.cancel(); heartbeat = null
        }
        runCommand({ repository.directCallAction(call.id, action, clientId) }, expectedCallId = call.id, failed = { error ->
            mutable.update { it.copy(busy = false, error = error.message) }
            // Keep media off and let the server lease expire if ending was not acknowledged.
            refresh()
        }) { apply(it, fromCommand = true) }
    }

    fun microphone(enabled: Boolean) {
        if (!state.value.canControl) return
        mutable.update { it.copy(microphoneWanted = enabled, error = null) }
        ensurePreview(); media.microphone(enabled)
    }
    fun camera(enabled: Boolean) {
        if (!state.value.canControl) return
        mutable.update { it.copy(cameraWanted = enabled, error = null) }
        ensurePreview(); media.camera(enabled)
    }
    fun minimize() { mutable.update { it.copy(minimized = true) } }
    fun resume() { mutable.update { it.copy(minimized = false) }; refresh() }
    fun dismiss() {
        if (state.value.call?.active == true && !state.value.otherDevice || attempt != null) { minimize(); return }
        stopMedia(); mutable.value = DirectCallSession(selfId = selfId(), clientId = clientId)
    }
    fun message(text: String) { mutable.update { it.copy(error = text) } }
    fun clearMessage() { mutable.update { it.copy(error = null) } }

    fun refresh() {
        if (identity() == null) return
        if (refreshJob?.isActive == true) { refreshAgain = true; return }
        val epoch = generation; val owner = identity()
        refreshJob = viewModelScope.launch {
            do {
                refreshAgain = false
                try {
                    if (!current(epoch, owner)) return@launch
                    val previous = state.value.call
                    val current = repository.activeDirectCall()
                    ensureActive()
                    if (!current(epoch, owner)) return@launch
                    if (state.value.call?.id != previous?.id && current?.id != state.value.call?.id) {
                        refreshAgain = true
                        continue
                    }
                    if (current != null) apply(current)
                    else if (previous?.active == true) {
                        val terminal = repository.directCall(previous.id)
                        ensureActive()
                        if (current(epoch, owner) && state.value.call?.id == previous.id) apply(terminal)
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    if (current(epoch, owner) && state.value.active) message(safe(error).message)
                }
            } while (refreshAgain && current(epoch, owner))
        }
    }

    private fun apply(call: DirectCall, fromCommand: Boolean = false) {
        if (selfId() !in setOf(call.callerUserId, call.calleeUserId)) return
        val previous = state.value.call
        if (previous?.id == call.id && previous.version > call.version) return
        if (previous?.id != call.id) {
            heartbeat?.cancel(); heartbeat = null; lastHeartbeat = null
            if (!fromCommand) {
                commandToken++; command?.cancel(); command = null
                mutable.update { it.copy(busy = false) }
            }
            if (previous?.active == true) stopMedia()
        }
        val same = previous?.id == call.id
        val creating = state.value.pendingPeer?.id == call.calleeUserId && call.callerClientId == clientId
        mutable.update { it.copy(call = call, pendingPeer = null, uncertain = false,
            minimized = if (same) it.minimized else false, ending = same && it.ending,
            microphoneWanted = if (same || creating) it.microphoneWanted else defaultMicrophone(),
            cameraWanted = same && it.cameraWanted,
            elapsedSeconds = duration(call), error = if (same && it.ending && call.active) it.error else null) }
        attempt = null
        if (!call.active) {
            heartbeat?.cancel(); heartbeat = null; lastHeartbeat = null; stopMedia()
            mutable.update { it.copy(ending = false) }
            return
        }
        startPolling()
        if (state.value.ending) return
        if (call.status == "accepted" && state.value.ownsMedia) {
            if (media.state.value.visible && media.state.value.directCallId != call.id) {
                message("Звонок принят. Завершите текущую встречу для подключения."); return
            }
            if (attachedCall != call.id && foreground) {
                attachedCall = call.id
                media.connect(call, clientId, call.peer(selfId())?.displayName.orEmpty(),
                    state.value.microphoneWanted, state.value.cameraWanted)
            }
            beat()
        } else if (call.status == "ringing") ensurePreview()
        else stopMedia()
    }

    private fun ensurePreview() {
        val value = state.value
        val call = value.call ?: return
        if (!foreground || call.status != "ringing" || !value.canControl || previewCall == call.id) return
        if (media.state.value.visible && media.state.value.directCallId != call.id) return
        previewCall = call.id
        media.preview(call, call.peer(selfId())?.displayName.orEmpty())
    }

    private fun startPolling() {
        if (identity() == null || poll?.isActive == true) return
        poll = viewModelScope.launch {
            var seconds = 0
            while (isActive && identity() != null && (foreground || state.value.active)) {
                if (seconds % (if (state.value.active) 3 else 10) == 0) refresh()
                state.value.call?.let { call ->
                    mutable.update { it.copy(elapsedSeconds = duration(call)) }
                    if (call.status == "accepted" && state.value.ownsMedia && !state.value.ending) {
                        if (lastHeartbeat?.let { Duration.between(it, clock.instant()).seconds >= 35 } == true) {
                            // Lost lease: release local devices before waiting for reconciliation.
                            stopMedia(); mutable.update { it.copy(ending = true, error = "Связь с сервером потеряна. Проверяем завершение звонка…") }
                        } else if (seconds % 10 == 0) beat()
                    }
                }
                seconds++; delay(1_000)
            }
        }
    }

    private fun beat() {
        val call = state.value.call ?: return
        if (heartbeat?.isActive == true || state.value.ending || !state.value.ownsMedia || call.status != "accepted") return
        val now = clock.instant()
        if (lastHeartbeat != null && Duration.between(lastHeartbeat, now).seconds < 9) return
        // Bound time without a successful renewal from the first accepted state as well.
        if (lastHeartbeat == null) lastHeartbeat = now
        val epoch = generation; val owner = identity()
        heartbeat = viewModelScope.launch {
            try {
                if (!current(epoch, owner) || state.value.call?.id != call.id || state.value.ending) return@launch
                val result = repository.directCallAction(call.id, DirectCallAction.HEARTBEAT, clientId)
                ensureActive()
                if (current(epoch, owner) && state.value.call?.id == call.id) { lastHeartbeat = clock.instant(); apply(result) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (current(epoch, owner)) { message(safe(error).message); refresh() } }
        }
    }

    private fun runCommand(block: suspend () -> DirectCall, expectedCallId: String? = null,
        failed: (AppFailure) -> Unit = { message(it.message) }, done: (DirectCall) -> Unit) {
        val epoch = generation; val owner = identity()
        val token = ++commandToken
        fun relevant(resultId: String? = null) = current(epoch, owner) && token == commandToken &&
            (if (expectedCallId != null) state.value.call?.id == expectedCallId else state.value.call == null || state.value.call?.id == resultId)
        mutable.update { it.copy(busy = true, error = null) }
        command = viewModelScope.launch {
            var resultId: String? = null
            try {
                if (!relevant()) return@launch
                val result = block(); ensureActive()
                resultId = result.id
                if (relevant(resultId)) { mutable.update { it.copy(busy = false) }; done(result) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (relevant()) failed(safe(error)) }
            finally { if (relevant(resultId)) mutable.update { it.copy(busy = false) } }
        }
    }

    private fun duration(call: DirectCall): Long = if (call.status != "accepted") call.durationSec
        else call.acceptedAt?.let { Duration.between(it, clock.instant()).seconds.coerceAtLeast(0) } ?: 0
    private fun identity(): String? = foundation.state.value.let { s ->
        val p = s.selectedProfile ?: return null; val user = s.session?.user ?: return null
        "${p.id}|${p.origin}|${user.id}"
    }
    private fun selfId() = foundation.state.value.session?.user?.id.orEmpty()
    private fun current(epoch: Long, owner: String?) = owner != null && epoch == generation && owner == identity()
    private fun safe(error: Exception) = error as? AppFailure ?: AppFailure(FailureKind.NETWORK, "Не удалось связаться с сервером. Проверьте подключение.")
    private fun stopMedia() {
        val owned = attachedCall ?: previewCall
        if (owned != null) media.stop(owned)
        attachedCall = null; previewCall = null
    }
    private fun reset() {
        generation++; commandToken++; poll?.cancel(); poll = null; refreshJob?.cancel(); refreshJob = null
        command?.cancel(); command = null; heartbeat?.cancel(); heartbeat = null
        refreshAgain = false; attempt = null; lastHeartbeat = null; stopMedia()
    }
    override fun onCleared() { reset(); super.onCleared() }
    class Factory(private val foundation: FoundationRepository, private val repository: DirectCallsRepository, private val engine: CallEngine,
        private val defaultMicrophone: () -> Boolean = { true }) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(DirectCallViewModel::class.java))
            return DirectCallViewModel(foundation, repository, EngineDirectCallMedia(engine), defaultMicrophone = defaultMicrophone) as T
        }
    }
}
