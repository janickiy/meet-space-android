package com.meetspace.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meetspace.app.ui.*
import com.meetspace.core.data.*
import com.meetspace.core.media.CallPhase
import com.meetspace.core.media.CallState
import com.meetspace.core.model.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.coroutineContext

/** One meeting discussion; drafts and pending uploads stay in account-scoped RAM. */
class CallDiscussionViewModel(
    private val foundation: FoundationRepository,
    private val repository: MeetingChatRepository,
    private val meetings: MeetingsRepository,
    private val recordings: RecordingsRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {
    private val mutable = MutableStateFlow(CallUiDetails())
    val state = mutable.asStateFlow()
    private var identity = account()
    private var call = CallState()
    private var foreground = false
    private var panel: CallPanel? = null
    private var generation = 0L
    private val jobs = mutableMapOf<String, Job>()
    private val tickets = mutableMapOf<String, Long>()
    private var poll: Job? = null
    private var messages = emptyList<MeetingChatMessage>()
    private var cursor: String? = null
    private var browsingOlder = false
    private var readThrough: String? = null
    private var startedAt: Instant? = null
    private var activeMeeting = true
    private var access = true
    private data class Attempt(val input: ChatMessageInput, val panel: CallPanel)
    private var pending: Attempt? = null
    private val attached = mutableListOf<MeetingChatAttachment>()
    private data class Upload(val id: String, val name: String, val mime: String, var bytes: ByteArray? = null,
        var status: CallAttachmentStatus = CallAttachmentStatus.PREPARING, var error: String? = null)
    private var upload: Upload? = null
    private var downloading: String? = null
    private var currentRecording: Recording? = null
    private var recordingKnown = false
    private var recordingBusy = false
    private var recordingError: String? = null
    private var meetingOwner: String? = null
    private var recordingMode = "composite"

    init { viewModelScope.launch { foundation.state.collect {
        if (identity != account()) { clear(); identity = account() }
    } } }

    val actions get() = CallPanelActions(
        open = ::open, room = MeetingRoomActions(refresh = ::loadParticipants, admit = ::admit, moderate = ::moderate, searchUsers = ::searchInviteUsers, invite = ::invite),
        chatDraft = { draft(CallPanel.CHAT, it) }, sendChat = { send(CallPanel.CHAT) },
        retryChat = { retry(CallPanel.CHAT) }, moreChat = { loadMessages(older = true) },
        commentDraft = { draft(CallPanel.COMMENTS, it) }, sendComment = { send(CallPanel.COMMENTS) },
        retryComments = { retry(CallPanel.COMMENTS) }, moreComments = { loadMessages(older = true) },
        latestChat = ::latest, latestComments = ::latest,
        removeChatAttachment = ::removeAttachment,
    )

    val recordingActions get() = CallRecordingActions(start = { controlRecording(false) }, stop = { controlRecording(true) }, retry = ::loadRecording, selectMode = { mode -> if (mode in state.value.recordingState.modes) { recordingMode = mode; updateRecording() } })

    fun bind(value: CallState) {
        if (identity != account()) { clear(); identity = account() }
        if (!value.visible || account() == null) { if (call.visible) clear(); return }
        val changed = call.conferenceId != value.conferenceId
        val wasConnected = connected()
        val becameConnected = call.phase != CallPhase.CONNECTED && value.phase == CallPhase.CONNECTED
        val revision = call.discussionRevision != value.discussionRevision
        val recordingRevision = call.recordingRevision != value.recordingRevision
        if (changed) clear()
        call = value
        updateAvailability()
        if (!connected()) { stopRequests(); return }
        if (changed || becameConnected || !wasConnected) { refresh(); startPolling() }
        else {
            if (revision) loadMessages()
            if (recordingRevision) loadRecording()
        }
    }

    fun foreground(value: Boolean) {
        if (foreground == value) return
        foreground = value
        updateAvailability()
        if (connected()) { refresh(); startPolling() } else stopRequests()
    }

    private fun open(value: CallPanel?) {
        panel = value
        if (value == CallPanel.PARTICIPANTS) loadParticipants()
        if (value == CallPanel.CHAT || value == CallPanel.COMMENTS) {
            latest()
        }
    }

    private fun latest() {
        browsingOlder = false; cursor = null
        cancel("messages"); loadMessages()
    }

    private fun draft(target: CallPanel, text: String) {
        if (pending != null || state.value.chat.sending) return
        val bounded = if (text.codePointCount(0, text.length) > 4000) text.substring(0, text.offsetByCodePoints(0, 4000)) else text
        editPanel(target) { it.copy(draft = bounded, error = null) }
    }

    private fun retry(target: CallPanel) {
        if (pending != null) send(target)
        else if (upload?.bytes != null) startUpload(checkNotNull(upload), null)
        else { access = true; updateAvailability(); loadMessages(); loadRecording(); startPolling() }
    }

    private fun send(target: CallPanel) {
        if (!canSend() || state.value.chat.sending || upload != null) return
        val draft = if (target == CallPanel.COMMENTS) state.value.comments.draft else state.value.chat.draft
        val attempt = pending ?: if (draft.isNotBlank() || attached.isNotEmpty()) {
            Attempt(ChatMessageInput(UUID.randomUUID().toString(), draft.trim(), attachmentIds = attached.map { it.id }), target)
        } else return
        pending = attempt
        editBoth { it.copy(sending = true, error = null) }
        request("send", { repository.sendMeetingMessage(call.conferenceId, attempt.input) }, { message ->
            cancel("messages")
            messages = merge(messages, listOf(message))
            browsingOlder = false
            acknowledged(); publishMessages(); markRead()
        }, { error ->
            val uncertain = error.kind in setOf(FailureKind.NETWORK, FailureKind.TIMEOUT, FailureKind.UNAVAILABLE)
            if (!uncertain) pending = null
            if (!denied(error)) editBoth { it.copy(sending = false, error = error.message +
                if (uncertain) " Повторите отправку: повтор не создаст дубликат." else "") }
            updateAvailability()
        })
    }

    private fun acknowledged() {
        val target = pending?.panel
        pending = null
        attached.clear(); publishAttachments()
        editBoth { it.copy(sending = false, error = null) }
        target?.let { editPanel(it) { p -> p.copy(draft = "") } }
    }

    private fun refresh() { loadMessages(); loadMeeting(); loadRecording(); loadParticipants() }

    private fun loadMessages(older: Boolean = false) {
        if (!connected() || !access || jobs["messages"]?.isActive == true) return
        val before = cursor.takeIf { older }
        if (older && before == null) return
        editBoth { it.copy(loading = messages.isEmpty() || older) }
        request("messages", { repository.meetingMessages(call.conferenceId, before) }, { page ->
            if (before != null && page.nextCursor == before) throw AppFailure(FailureKind.INVALID_RESPONSE, "Сервер повторил страницу сообщений. Обновите историю.")
            if (older) {
                messages = merge(messages, page.items, keepOldest = true)
                cursor = page.nextCursor; browsingOlder = true
            } else if (browsingOlder) {
                // Do not move a reader's older window when a live event arrives.
                messages = merge(messages, page.items.filter { item -> messages.any { it.id == item.id } }, keepOldest = true)
            } else {
                messages = merge(messages.filter { known -> page.items.any { it.id == known.id } }, page.items)
                cursor = page.nextCursor
            }
            pending?.let { attempt ->
                if (page.items.any { it.clientRequestId == attempt.input.clientRequestId && it.senderId == selfId() }) {
                    cancel("send"); acknowledged()
                }
            }
            mutable.value = state.value.copy(commentCount = page.unreadCount.coerceIn(0, 99).toInt().takeIf { it > 0 })
            publishMessages()
            if (!older) markRead()
        }, { error -> if (!denied(error)) editBoth { it.copy(loading = false, error = error.message) } })
    }

    private fun publishMessages() {
        val zone = ZoneId.systemDefault()
        val today = clock.instant().atZone(zone).toLocalDate()
        var lastDate: java.time.LocalDate? = null
        val entries = messages.map { m ->
            val date = m.createdAt.atZone(zone).toLocalDate()
            val label = if (lastDate == date) null else when (date) {
                today -> "Сегодня"
                today.minusDays(1) -> "Вчера"
                else -> date.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("ru")))
            }
            lastDate = date
            CallTextEntry(m.id, m.senderName, if (m.deletedAt != null) "Сообщение удалено" else m.text,
                m.createdAt.atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm")), own = m.senderId == selfId(),
                dateLabel = label, attachmentLabel = m.attachments.takeIf { m.deletedAt == null && it.isNotEmpty() }?.joinToString { it.filename },
                deleted = m.deletedAt != null,
                attachments = if (m.deletedAt != null) emptyList() else m.attachments.map { a ->
                    CallAttachmentUi(a.id, a.filename, a.mimeType, a.size, canDownload = a.status == "attached" && downloading == null,
                        downloading = downloading == a.id)
                })
        }
        editBoth { it.copy(entries = entries, loading = false, hasMore = cursor != null, viewingOlder = browsingOlder,
            error = if (pending == null && upload?.error == null) null else it.error) }
        updateAvailability()
    }

    private fun markRead() {
        if (!connected() || browsingOlder || panel !in setOf(CallPanel.CHAT, CallPanel.COMMENTS)) return
        val newest = messages.maxByOrNull { it.sequence }?.id ?: return
        if (readThrough == newest || jobs["read"]?.isActive == true) return
        request("read", { repository.markMeetingChatRead(call.conferenceId, newest) }, { read ->
            readThrough = newest
            mutable.value = state.value.copy(commentCount = read.unreadCount.coerceIn(0, 99).toInt().takeIf { it > 0 })
        }, { denied(it) })
    }

    private fun loadMeeting() {
        if (!connected() || jobs["meeting"]?.isActive == true) return
        request("meeting", { meetings.conference(call.conferenceId) }, { meeting ->
            startedAt = meeting.startedAt
            meetingOwner = meeting.ownerId
            activeMeeting = meeting.status == "active"
            tick(); updateAvailability()
        }, { startedAt = null; tick() })
    }

    private fun updateRoom(edit: (MeetingRoomUiState) -> MeetingRoomUiState) { mutable.value = state.value.copy(room = edit(state.value.room)) }
    private fun loadParticipants() {
        if (!connected() || jobs["participants"]?.isActive == true || state.value.room.busy) return
        updateRoom { it.copy(loading = true, error = null) }
        request("participants", { meetings.participants(call.conferenceId) }, { people -> updateRoom { it.copy(participants = people, selfId = call.participantId, loading = false) } },
            { e -> updateRoom { it.copy(loading = false, error = e.message, participants = if (e.kind in setOf(FailureKind.FORBIDDEN, FailureKind.NOT_FOUND)) emptyList() else it.participants) } })
    }
    private fun admit(id: String, allow: Boolean) {
        val room = state.value.room
        if (room.busy || room.self?.role != "owner") return
        roomMutation { meetings.admitParticipant(call.conferenceId, id, allow) }
    }
    private fun moderate(id: String, action: MeetingModeration) {
        val room = state.value.room; val person = room.participants.find { it.id == id } ?: return
        if (room.busy || !canModerateMeeting(room.self, person, action.action)) return
        roomMutation { meetings.moderateParticipant(call.conferenceId, id, action) }
    }
    private fun roomMutation(block: suspend () -> MeetingParticipant) {
        if (!connected()) return
        cancel("participants"); updateRoom { it.copy(busy = true, loading = false, error = null, notice = null) }
        request("room-mutation", block, { person -> updateRoom { it.copy(busy = false, participants = it.participants.map { p -> if (p.id == person.id) person else p }) }; loadParticipants() },
            { e -> updateRoom { it.copy(busy = false, error = e.message) } })
    }
    private fun searchInviteUsers(query: String) {
        cancel("invite-search"); updateRoom { it.copy(query = query.take(100), users = emptyList(), error = null) }
        if (query.trim().length < 2) return
        request("invite-search", { delay(300); meetings.invitationUsers(call.conferenceId, query.trim()) }, { users -> updateRoom { it.copy(users = users) } }, { e -> updateRoom { it.copy(error = e.message) } })
    }
    private fun invite(userIds: List<String>, emails: List<String>) {
        if (!connected() || state.value.room.busy || state.value.room.self?.role !in setOf("owner", "co_host")) return
        updateRoom { it.copy(busy = true, error = null, notice = null) }
        request("invite", { meetings.inviteParticipants(call.conferenceId, userIds, emails) },
            { updateRoom { it.copy(busy = false, notice = "Приглашения поставлены в очередь отправки.", users = emptyList(), query = "") } },
            { e -> updateRoom { it.copy(busy = false, error = e.message) } })
    }

    private fun loadRecording() {
        if (!connected() || jobs["recording"]?.isActive == true || recordingBusy) return
        request("recording", { recordings.recordings(call.conferenceId) }, { page ->
            currentRecording = page.items.firstOrNull { it.status in setOf("starting", "recording", "stopping", "processing", "finalizing", "uploading", "degraded") }
            recordingKnown = true; recordingError = null; updateRecording()
        }, { recordingKnown = false; recordingError = it.message; updateRecording() })
    }

    private fun updateRecording() {
        val status = if (!recordingKnown) CallRecordingStatus.UNKNOWN else when (currentRecording?.status) {
            "recording", "degraded" -> CallRecordingStatus.RECORDING
            "starting" -> CallRecordingStatus.STARTING
            "stopping", "processing", "finalizing", "uploading" -> CallRecordingStatus.STOPPING
            else -> CallRecordingStatus.STOPPED
        }
        val mode = foundation.state.value.session?.capabilities?.recordingModes.orEmpty()
        if (recordingMode !in mode) recordingMode = mode.firstOrNull().orEmpty()
        val enabled = connected() && activeMeeting && recordingKnown && !recordingBusy
        val owner = selfId() != null && (selfId() == meetingOwner || selfId() == currentRecording?.requestedBy)
        mutable.value = state.value.copy(recording = status == CallRecordingStatus.RECORDING,
            recordingState = CallRecordingUiState(status = status, busy = recordingBusy, modes = mode, selectedMode = recordingMode,
                canStart = foundation.state.value.session?.guestMeeting == null && enabled && currentRecording == null && mode.isNotEmpty(),
                canStop = enabled && owner && currentRecording?.status in setOf("starting", "recording", "degraded"),
                error = recordingError, description = if (currentRecording?.mode == "composite") "Видео, звук и демонстрация экрана в одной записи." else null, unavailableReason = when {
                    foundation.state.value.session?.guestMeeting != null -> "Гость не может управлять записью встречи."
                    !connected() -> "Управление станет доступно после подключения к встрече."
                    !activeMeeting -> "Встреча завершена."
                    !recordingKnown -> "Не удалось подтвердить состояние записи. Обновите данные."
                    mode.isEmpty() -> "Запись недоступна на этом сервере."
                    currentRecording != null && !owner -> "Остановить запись может организатор или участник, который её начал."
                    else -> "Остановить запись может её инициатор или организатор встречи."
                }))
    }

    private fun controlRecording(stop: Boolean) {
        val ui = state.value.recordingState
        if (if (stop) !ui.canStop else !ui.canStart) return
        val cid = call.conferenceId
        val rid = currentRecording?.id
        val modes = foundation.state.value.session?.capabilities?.recordingModes.orEmpty()
        val mode = recordingMode.takeIf { it in modes }
        if (!stop && mode == null) return
        cancel("recording"); recordingBusy = true; recordingError = null; updateRecording()
        request("recording-control", {
            if (stop) recordings.stopRecording(cid, checkNotNull(rid)) else recordings.startRecording(cid, checkNotNull(mode))
        }, { result ->
            currentRecording = result.takeIf { it.status !in setOf("ready", "partial_ready", "failed", "cancelled") }
            recordingBusy = false; recordingKnown = true; updateRecording()
        }, { failure ->
            recordingBusy = false; recordingKnown = false; recordingError = failure.message
            updateRecording()
            // Reconcile a lost mutation acknowledgement with GET; never replay a start automatically.
            loadRecording()
        })
    }

    fun message(text: String) { editBoth { it.copy(error = text) } }

    fun upload(name: String, mime: String, read: suspend () -> ByteArray) {
        if (!state.value.chat.canAttach || attached.size >= 5 || upload != null) return
        val item = Upload(UUID.randomUUID().toString(), name, mime)
        upload = item; publishAttachments(); startUpload(item, read)
    }

    private fun startUpload(item: Upload, read: (suspend () -> ByteArray)?) {
        if (!canSend() || jobs["upload"]?.isActive == true || upload !== item) return
        item.status = CallAttachmentStatus.UPLOADING; item.error = null
        publishAttachments(); editBoth { it.copy(error = null) }
        val cid = call.conferenceId
        val epoch = generation; val scope = identity
        request("upload", {
            if (item.bytes == null) {
                val bytes = checkNotNull(read).invoke()
                try { coroutineContext.ensureActive(); check(upload === item); item.bytes = bytes }
                catch (error: Exception) { bytes.fill(0); throw error }
            }
            guardScope(epoch, scope, cid)
            repository.uploadMeetingAttachment(cid, item.name, item.mime, checkNotNull(item.bytes), item.id)
        }, { result ->
            item.bytes?.fill(0); item.bytes = null; upload = null
            attached.add(result); publishAttachments()
        }, { error ->
            val uncertain = error.kind in setOf(FailureKind.NETWORK, FailureKind.TIMEOUT, FailureKind.UNAVAILABLE)
            item.status = if (uncertain) CallAttachmentStatus.UNKNOWN else CallAttachmentStatus.FAILED
            item.error = error.message
            if (!uncertain) { item.bytes?.fill(0); item.bytes = null }
            publishAttachments(); message(error.message + if (uncertain) " Повторите загрузку или уберите файл." else " Уберите файл и выберите его заново.")
        })
    }

    private fun removeAttachment(id: String) {
        if (pending != null || state.value.chat.sending || jobs["upload"]?.isActive == true) return
        if (upload?.id == id) { upload?.bytes?.fill(0); upload = null }
        attached.removeAll { it.id == id }; publishAttachments(); editBoth { it.copy(error = null) }
    }

    private fun publishAttachments() {
        val files = attached.map { CallPendingAttachmentUi(it.id, it.filename, it.mimeType, it.size) } +
            listOfNotNull(upload?.let { CallPendingAttachmentUi(it.id, it.name, it.mime, it.bytes?.size?.toLong() ?: 0, it.status, it.error) })
        editBoth { it.copy(pendingFiles = files) }; updateAvailability()
    }

    fun attachment(entryId: String, attachmentId: String): MeetingChatAttachment? = messages.firstOrNull { it.id == entryId && it.deletedAt == null }
        ?.attachments?.firstOrNull { it.id == attachmentId && it.status == "attached" }

    fun export(entryId: String, attachmentId: String, onFailure: suspend () -> Unit, write: suspend (ByteArray) -> Unit): Boolean {
        val file = attachment(entryId, attachmentId) ?: return false
        if (!connected() || downloading != null) return false
        val cid = call.conferenceId
        val epoch = generation; val scope = identity
        downloading = file.id; publishMessages()
        var completed = false
        val download = request("download", {
            val bytes = repository.downloadMeetingAttachment(cid, file)
            try { coroutineContext.ensureActive(); guardScope(epoch, scope, cid); write(bytes); completed = true } finally { bytes.fill(0) }
        }, { downloading = null; publishMessages() }, { downloading = null; publishMessages(); message(it.message) }) ?: return false
        // A cleared meeting may cancel the accepted request before its first instruction.
        download.invokeOnCompletion { if (!completed) viewModelScope.launch(NonCancellable, start = CoroutineStart.UNDISPATCHED) { runCatching { onFailure() } } }
        return true
    }

    private fun startPolling() {
        if (!connected() || poll?.isActive == true) return
        poll = viewModelScope.launch {
            var seconds = 0
            while (isActive && connected()) {
                delay(1000); tick(); seconds++
                if (seconds % 5 == 0) loadMessages()
                if (seconds % 15 == 0) { loadMeeting(); loadRecording() }
                if (seconds % 5 == 0 && panel == CallPanel.PARTICIPANTS) loadParticipants()
            }
        }
    }

    private fun tick() { mutable.value = state.value.copy(elapsedSeconds = startedAt?.let { (clock.instant().epochSecond - it.epochSecond).coerceAtLeast(0) }) }
    private fun connected() = foreground && identity != null && identity == account() && call.phase == CallPhase.CONNECTED && call.participantId.isNotBlank()
    private fun canSend() = connected() && access && activeMeeting
    private fun updateAvailability() { editBoth { it.copy(canSend = canSend() && (pending == null || it.sending),
        canAttach = canSend() && pending == null && upload == null && attached.size < 5,
        participantCount = call.participants.count { p -> p.online }, unavailableReason = when {
        !access -> "Доступ к истории встречи не подтверждён."
        !activeMeeting -> "Встреча завершена. История доступна для чтения."
        !connected() -> "Отправка станет доступна после подключения к встрече."
        else -> null
    }) }; updateRecording() }

    private fun denied(error: AppFailure): Boolean {
        if (error.kind !in setOf(FailureKind.FORBIDDEN, FailureKind.NOT_FOUND, FailureKind.SESSION_EXPIRED)) return false
        stopRequests()
        access = false; messages = emptyList(); cursor = null; pending = null
        upload?.bytes?.fill(0); upload = null; attached.clear()
        editBoth { CallTextPanelState(error = error.message) }
        mutable.value = state.value.copy(commentCount = null)
        updateAvailability(); return true
    }

    private fun merge(old: List<MeetingChatMessage>, incoming: List<MeetingChatMessage>, keepOldest: Boolean = false): List<MeetingChatMessage> {
        val sorted = (old + incoming).groupBy { it.id }.values.map { versions -> versions.maxBy { it.version } }.sortedBy { it.sequence }
        return if (keepOldest) sorted.take(200) else sorted.takeLast(200)
    }

    private fun editBoth(edit: (CallTextPanelState) -> CallTextPanelState) { mutable.value = state.value.copy(chat = edit(state.value.chat), comments = edit(state.value.comments)) }
    private fun editPanel(target: CallPanel, edit: (CallTextPanelState) -> CallTextPanelState) {
        mutable.value = if (target == CallPanel.COMMENTS) state.value.copy(comments = edit(state.value.comments)) else state.value.copy(chat = edit(state.value.chat))
    }
    private fun account(): String? = foundation.state.value.let { s -> s.session?.let { listOf(s.selectedProfile?.id, s.selectedProfile?.origin, it.user.id).joinToString("|") } }
    private fun guardScope(epoch: Long, scope: String?, conference: String) {
        if (epoch != generation || scope != account() || conference != call.conferenceId || !connected()) throw CancellationException("Meeting scope changed")
    }
    private fun selfId() = foundation.state.value.session?.user?.id
    private fun cancel(key: String) { tickets[key] = (tickets[key] ?: 0) + 1; jobs.remove(key)?.cancel() }
    private fun <T> request(key: String, block: suspend () -> T, success: (T) -> Unit, failure: (AppFailure) -> Unit): Job? {
        if (!connected()) return null
        cancel(key)
        val ticket = tickets.getValue(key); val epoch = generation; val scope = identity; val conference = call.conferenceId
        fun current() = epoch == generation && scope == account() && conference == call.conferenceId && tickets[key] == ticket
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                if (!current() || !connected()) throw CancellationException("Meeting scope changed")
                val result = block(); coroutineContext.ensureActive(); if (current()) success(result)
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (current()) failure(error as? AppFailure ?: AppFailure(FailureKind.UNAVAILABLE, "Не удалось обновить встречу. Повторите попытку.")) }
            finally { if (current()) jobs.remove(key) }
        }
        jobs[key] = job
        job.start()
        return job
    }
    private fun stopRequests() {
        generation++; poll?.cancel(); poll = null
        jobs.values.toList().forEach(Job::cancel); jobs.clear(); tickets.clear()
        editBoth { it.copy(loading = false, sending = false,
            error = if (pending != null) "Отправка прервана. Повторите отправку: повтор не создаст дубликат." else it.error) }
        recordingBusy = false; recordingKnown = false; downloading = null
        upload?.let { it.status = CallAttachmentStatus.UNKNOWN; it.error = if (it.bytes == null) "Чтение файла прервано. Уберите файл и выберите его заново." else "Загрузка прервана. Повторите её или уберите файл." }
        if (upload != null) { publishAttachments(); editBoth { it.copy(error = upload?.error) } }
        mutable.value = state.value.copy(recording = false, room = MeetingRoomUiState())
        updateRecording()
    }
    fun clear() {
        stopRequests(); call = CallState(); panel = null; messages = emptyList(); cursor = null
        pending = null; readThrough = null; startedAt = null; browsingOlder = false; access = true; activeMeeting = true
        upload?.bytes?.fill(0); upload = null; attached.clear(); downloading = null
        currentRecording = null; recordingKnown = false; recordingBusy = false; recordingError = null; meetingOwner = null
        mutable.value = CallUiDetails()
    }
    override fun onCleared() { clear(); super.onCleared() }
    class Factory(private val foundation: FoundationRepository, private val repository: MeetingChatRepository, private val meetings: MeetingsRepository, private val recordings: RecordingsRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = CallDiscussionViewModel(foundation, repository, meetings, recordings) as T
    }
}
