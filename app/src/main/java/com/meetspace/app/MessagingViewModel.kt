package com.meetspace.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meetspace.app.ui.*
import com.meetspace.core.data.*
import com.meetspace.core.model.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.coroutines.coroutineContext

/** Bounded RAM history. Account/origin changes discard drafts, attachments and event state. */
class MessagingViewModel(private val foundation: FoundationRepository, private val repository: MessagingRepository) : ViewModel() {
    private val mutable = MutableStateFlow(MessagingUiState(selfId = selfId()))
    val state = mutable.asStateFlow()
    private var account = scope()
    private var generation = 0L
    private val jobs = mutableMapOf<String, Job>()
    private val tickets = mutableMapOf<String, Long>()
    private var visible = false
    private var foreground = false
    private var eventJob: Job? = null
    private var eventSession: UserEvents? = null
    private var reconcileJob: Job? = null
    private var pending: ChatMessageInput? = null
    private var groupAttempt: Pair<String, String>? = null
    private data class History(val detail: ChatConversation?, val items: List<ChatMessage>, val cursor: String?)
    private val history = linkedMapOf<String, History>()
    private val eventIds = linkedSetOf<String>()
    private val readThrough = mutableMapOf<String, String>()
    private val pendingNotifications = linkedSetOf<String>()
    private var notificationListener: ((InboxNotification) -> Unit)? = null
    private var callEventListener: ((UserEvent) -> Unit)? = null
    init { viewModelScope.launch { foundation.state.collect {
        if (account != scope()) { suspendWork(); account = scope(); mutable.value = MessagingUiState(selfId = selfId()); if (foreground && account != null) startEvents() }
    } } }
    val actions get() = MessagingActions(
        activate = ::activate, refresh = ::refresh, query = ::query, filter = ::filter,
        moreConversations = { loadConversations(true) }, open = ::open, back = ::back,
        refreshMessages = { loadMessages(false) }, moreMessages = { loadMessages(true) },
        searchMessages = ::searchMessages, moreSearch = { searchMessages(state.value.searchQuery, true) },
        loadPins = ::loadPins, morePins = { loadPins(true) }, pin = ::pin, focusMessage = ::focusMessage,
        latestMessages = { cancel("context"); mutable.update { it.copy(viewingContext = false, focusedMessageId = null) }; loadMessages(false) },
        clearConversation = ::clearConversation, folderFilter = ::folderFilter,
        draft = ::draft, send = ::send, reply = ::reply, edit = ::edit, cancelCompose = ::cancelCompose,
        delete = ::delete, markRead = ::markRead, removeAttachment = ::removeAttachment,
        searchUsers = ::searchUsers, createDirect = ::createDirect, createGroup = ::createGroup,
        loadMembers = ::loadMembers, editGroup = ::editGroup, addMember = ::addMember, removeMember = ::removeMember,
        leaveGroup = ::leaveGroup, deleteGroup = ::deleteGroup, conversationNotifications = ::conversationNotifications,
        selectFolder = ::selectFolder, createFolder = ::createFolder, renameFolder = ::renameFolder, deleteFolder = ::deleteFolder,
        reorderFolders = ::reorderFolders, moreFolderItems = { loadFolder(false, true) },
        searchFolderCandidates = ::searchFolderCandidates, moreFolderCandidates = { loadFolder(true, true) }, folderMembership = ::folderMembership,
        loadNotifications = ::loadNotifications, moreNotifications = { notifications(true) }, readNotification = ::readNotification,
        readAllNotifications = ::readAllNotifications, saveNotificationPreferences = ::saveNotificationPreferences, clearMessage = ::clearMessage,
    )
    fun foreground(value: Boolean) {
        foreground = value
        if (value && scope() != null) { mutable.update { it.copy(selfId = selfId()) }; startEvents() } else stopEvents()
    }
    fun workspaceVisible(value: Boolean) { visible = value }
    fun notificationPermission(enabled: Boolean) { mutable.update { it.copy(notificationPermissionEnabled = enabled) } }
    fun onNotification(listener: ((InboxNotification) -> Unit)?) { notificationListener = listener }
    fun onCallEvent(listener: ((UserEvent) -> Unit)?) { callEventListener = listener }
    fun suspendWork() {
        generation++; jobs.values.toList().forEach(Job::cancel); jobs.clear(); tickets.clear()
        stopEvents(); history.clear(); eventIds.clear(); readThrough.clear(); pendingNotifications.clear(); pending = null; groupAttempt = null; mutable.value = MessagingUiState()
    }
    override fun onCleared() { suspendWork(); notificationListener = null; callEventListener = null; super.onCleared() }
    fun activate() {
        if (scope() == null) return
        if (state.value.conversations.data == null && !state.value.conversations.loading) loadConversations(false)
        if (state.value.folders.data == null && !state.value.folders.loading) loadFolders()
        if (state.value.notifications.data == null && !state.value.notifications.loading) notifications(false)
    }
    fun refresh() {
        if (scope() == null) return
        loadConversations(false); loadFolders(); notifications(false)
        if (state.value.selectedId != null) { loadDetail(); loadMessages(false); loadPins() }
        if (state.value.selectedFolderId != null) loadFolder(false, false)
        if (foreground && eventJob?.isActive != true) startEvents()
    }
    fun query(value: String) {
        mutable.update { it.copy(query = value.take(100), conversationCursor = null) }
        if (state.value.selectedFolderId == null) loadConversations(false, true) else loadFolder(false, false)
    }
    fun filter(value: ChatFilter) { selectFolder(null); mutable.update { it.copy(filter = value, conversationCursor = null) }; loadConversations(false) }
    private fun loadConversations(append: Boolean, debounce: Boolean = false) {
        val s = state.value; val cursor = s.conversationCursor.takeIf { append }
        if (append && (cursor == null || s.conversations.loading)) return
        val old = s.conversations
        mutable.update { it.copy(conversations = old.copy(loading = true, error = null)) }
        request("conversations", { if (debounce) delay(300); repository.conversations(ChatQuery(s.query, when (s.filter) { ChatFilter.DIRECT -> "direct"; ChatFilter.GROUP -> "group"; else -> null }, s.filter == ChatFilter.UNREAD, cursor)) }, { page ->
            verifyCursor(cursor, page.nextCursor)
            val items = if (append) merge(old.data.orEmpty(), page.items) { it.id } else page.items
            mutable.update { it.copy(conversations = MessagingLoad(items.take(300)), conversationCursor = page.nextCursor.takeIf { items.size < 300 }) }
        }, { e -> mutable.update { it.copy(conversations = old.copy(loading = false, error = e.message)) } })
    }
    fun open(id: String) {
        if (scope() == null || id == state.value.selectedId) return
        rememberDraft(); rememberHistory(); cancelConversationWork(); pending = null
        val cached = history.remove(id)?.also { history[id] = it }
        mutable.update { it.copy(selectedId = id, detail = MessagingLoad(cached?.detail), messages = MessagingLoad(cached?.items), messageCursor = cached?.cursor,
            draft = it.drafts[id].orEmpty(), searchQuery = "", searchResults = MessagingLoad(), searchCursor = null, pins = MessagingLoad(), pinsCursor = null, focusedMessageId = null, viewingContext = false, replyTo = null, editing = null, attachments = emptyList(), members = MessagingLoad(), peerOnline = null,
            pendingSend = false, sending = false, attachmentBusy = false, error = null, notice = null) }
        loadDetail(); loadMessages(false); loadPins()
    }
    fun back() {
        rememberDraft(); rememberHistory(); cancelConversationWork(); pending = null
        mutable.update { it.copy(selectedId = null, detail = MessagingLoad(), messages = MessagingLoad(), messageCursor = null,
            draft = "", searchQuery = "", searchResults = MessagingLoad(), searchCursor = null, pins = MessagingLoad(), pinsCursor = null, focusedMessageId = null, viewingContext = false, attachments = emptyList(), replyTo = null, editing = null, members = MessagingLoad(),
            pendingSend = false, sending = false, attachmentBusy = false, peerOnline = null) }
    }
    private fun cancelConversationWork() { listOf("detail", "messages", "members", "presence", "send", "attachment", "export", "read", "searchMessages", "pins", "context").forEach(::cancel) }
    private fun rememberHistory() {
        val s = state.value; val id = s.selectedId ?: return
        if (s.messages.data != null) history[id] = History(s.detail.data, s.messages.data.takeLast(200), s.messageCursor)
        while (history.size > 10) { val oldest = history.keys.first(); history.remove(oldest); readThrough.remove(oldest) }
        if (readThrough.size > 100) readThrough.keys.toList().filter { it !in history && it != s.selectedId }.forEach(readThrough::remove)
    }
    private fun loadDetail() {
        val id = state.value.selectedId ?: return; val old = state.value.detail
        mutable.update { it.copy(detail = old.copy(loading = true, error = null)) }
        request("detail", { repository.conversation(id) }, { detail ->
            val latest = detail.lastMessageId ?: state.value.messages.data?.lastOrNull()?.id
            val projection = if (latest != null && latest == readThrough[id]) detail.copy(unreadCount = 0) else detail
            mutable.update { it.copy(detail = MessagingLoad(projection)) }
            if (detail.type == "direct") request("presence", { repository.peerPresence(id) }, { online -> mutable.update { it.copy(peerOnline = online) } }, { mutable.update { it.copy(peerOnline = null) } })
        }, { e -> if (!accessDenied(id, e)) mutable.update { it.copy(detail = old.copy(loading = false, error = e.message)) } })
    }
    private fun loadMessages(append: Boolean) {
        if (state.value.viewingContext) return
        val s = state.value; val id = s.selectedId ?: return; val cursor = s.messageCursor.takeIf { append }
        if (append && (cursor == null || s.messages.loading)) return
        val old = s.messages
        mutable.update { it.copy(messages = old.copy(loading = true, error = null)) }
        request("messages", { repository.messages(id, cursor) }, { page ->
            verifyCursor(cursor, page.nextCursor)
            val retained = if (append) old.data.orEmpty() else old.data.orEmpty().filter { known -> page.items.any { it.id == known.id } }
            // Older pagination moves the bounded window towards the requested page.
            // Its cursor remains usable even when the in-memory window is full.
            val items = mergeMessages(retained, page.items, keepOldest = append)
            mutable.update { it.copy(messages = MessagingLoad(items), messageCursor = page.nextCursor) }
            page.lastReadMessageId?.let { readThrough[id] = it }
            mutable.update { it.copy(detail = it.detail.copy(data = it.detail.data?.copy(unreadCount =
                if (items.lastOrNull()?.id != null && items.last().id == readThrough[id]) 0 else page.unreadCount))) }
            pending?.let { attempt -> if (items.any { it.clientRequestId == attempt.clientRequestId && it.senderId == selfId() }) sent() }
            rememberHistory(); if (!append && foreground && visible) markRead()
        }, { e -> if (!accessDenied(id, e)) mutable.update { it.copy(messages = old.copy(loading = false, error = e.message)) } })
    }
    private fun accessDenied(id: String, error: AppFailure): Boolean {
        if (error.kind !in setOf(FailureKind.FORBIDDEN, FailureKind.NOT_FOUND)) return false
        history.remove(id); if (state.value.selectedId == id) back(); history.remove(id)
        mutable.update { it.copy(drafts = it.drafts - id) }
        mutable.update { it.copy(error = "Доступ к чату больше не подтверждён. История очищена.",
            conversations = it.conversations.copy(data = it.conversations.data?.filterNot { c -> c.id == id })) }; return true
    }
    fun draft(value: String) { if (pending == null && !state.value.sending) mutable.update { it.copy(draft = value.take(8000), drafts = boundedDrafts(it.drafts, it.selectedId, value.take(8000))) } }
    fun reply(message: ChatMessage) { if (message.kind == "message" && pending == null && !state.value.sending && message.deletedAt == null) mutable.update { it.copy(replyTo = message, editing = null) } }
    fun edit(message: ChatMessage) {
        if (message.kind != "message" || pending != null || state.value.sending || message.senderId != selfId() || message.deletedAt != null) return
        mutable.update { it.copy(editing = message, replyTo = null, draft = message.text, attachments = emptyList()) }
    }
    fun cancelCompose() {
        if (pending != null) { message("Сначала повторите отправку: сервер мог уже принять сообщение."); return }
        mutable.update { it.copy(editing = null, replyTo = null, draft = "") }
    }
    fun send() {
        val s = state.value; val id = s.selectedId ?: return
        if (scope() == null || s.sending || s.attachmentBusy) return
        if (s.draft.codePointCount(0, s.draft.length) > 4000) { message("В сообщении допускается не более 4000 символов."); return }
        if (s.draft.isBlank() && s.attachments.isEmpty()) return
        val editing = s.editing
        val input = pending ?: ChatMessageInput(UUID.randomUUID().toString(), s.draft.trim(), s.replyTo?.id, s.attachments.map { it.id })
        if (editing == null) pending = input
        mutable.update { it.copy(sending = true, error = null) }
        request("send", { if (editing != null) repository.editMessage(id, editing.id, s.draft.trim()) else repository.sendMessage(id, input) }, { result ->
            // A history request begun before this acknowledgment may omit this message or
            // carry its old version; its late success/failure must not replace the new state.
            cancel("messages")
            mutable.update { it.copy(messages = MessagingLoad(mergeMessages(it.messages.data.orEmpty(), listOf(result)))) }
            sent(); rememberHistory(); loadConversations(false)
        }, { e ->
            val uncertain = editing == null && e.kind in setOf(FailureKind.NETWORK, FailureKind.TIMEOUT, FailureKind.UNAVAILABLE)
            if (!uncertain) pending = null
            if (!accessDenied(id, e)) mutable.update { it.copy(sending = false, pendingSend = uncertain, error = e.message,
                notice = if (uncertain) "Результат отправки не подтверждён. Повтор использует тот же идентификатор сообщения." else null) }
        })
    }
    private fun sent() { pending = null; mutable.update { it.copy(drafts = it.drafts - it.selectedId.orEmpty(), draft = "", replyTo = null, editing = null, attachments = emptyList(), sending = false, pendingSend = false, notice = null) } }
    fun delete(message: ChatMessage) {
        if (message.kind != "message") return
        val id = state.value.selectedId ?: return
        mutate({ repository.deleteMessage(id, message.id) }) { result ->
            mutable.update { it.copy(messages = MessagingLoad(mergeMessages(it.messages.data.orEmpty(), listOf(result)))) }
            history.remove(id); loadMessages(false); loadConversations(false)
        }
    }
    private fun boundedDrafts(values: Map<String, String>, id: String?, text: String): Map<String, String> {
        if (id == null) return values
        val next = values.toMutableMap().apply { remove(id); if (text.isNotBlank()) put(id, text) }
        while (next.size > 20) next.remove(next.keys.first())
        return next
    }
    private fun rememberDraft() {
        val s = state.value
        // An uncertain send must never be restored as a new message with a different idempotency key.
        if (s.editing == null) mutable.update { it.copy(drafts = boundedDrafts(it.drafts, s.selectedId, if (pending == null && !s.sending) s.draft else "")) }
    }
    fun folderFilter(value: String) {
        if (value !in setOf("all", "conference", "conversation")) return
        mutable.update { it.copy(folderFilter = value, folderCursor = null) }; loadFolder(false, false)
    }
    fun searchMessages(query: String, append: Boolean = false) {
        val s = state.value; val id = s.selectedId ?: return
        val q = query.take(200); val cursor = s.searchCursor.takeIf { append }
        if (append && (cursor == null || s.searchResults.loading)) return
        cancel("searchMessages")
        mutable.update { it.copy(searchQuery = q, searchResults = if (append) it.searchResults.copy(loading = true, error = null) else MessagingLoad(loading = q.trim().length >= 2), searchCursor = cursor) }
        if (q.trim().length < 2) return
        request("searchMessages", { delay(300); repository.searchMessages(id, q.trim(), cursor) }, { page ->
            verifyCursor(cursor, page.nextCursor)
            mutable.update { it.copy(searchResults = MessagingLoad((if (append) merge(it.searchResults.data.orEmpty(), page.items) { it.id } else page.items).take(200)), searchCursor = page.nextCursor.takeIf { _ -> (it.searchResults.data?.size ?: 0) < 180 }) }
        }, { e -> if (!accessDenied(id, e)) mutable.update { it.copy(searchResults = it.searchResults.copy(loading = false, error = e.message)) } })
    }
    fun loadPins(append: Boolean = false) {
        val s = state.value; val id = s.selectedId ?: return; val cursor = s.pinsCursor.takeIf { append }
        if (append && (cursor == null || s.pins.loading)) return
        mutable.update { it.copy(pins = it.pins.copy(loading = true, error = null)) }
        request("pins", { repository.messagePins(id, cursor) }, { page ->
            verifyCursor(cursor, page.nextCursor)
            mutable.update { it.copy(pins = MessagingLoad((if (append) merge(it.pins.data.orEmpty(), page.items) { it.id } else page.items).take(100)), pinsCursor = page.nextCursor) }
        }, { e -> if (!accessDenied(id, e)) mutable.update { it.copy(pins = MessagingLoad(error = e.message), pinsCursor = null) } })
    }
    fun pin(message: ChatMessage, pinned: Boolean) {
        val id = state.value.selectedId ?: return
        if (message.conversationId != id || message.kind != "message" || message.deletedAt != null) return
        mutate({ repository.pinMessage(id, message.id, pinned) }, false) {
            mutable.update { it.copy(messages = it.messages.copy(data = it.messages.data?.map { m -> if (m.id == message.id) m.copy(pinned = pinned) else m })) }
            loadPins()
        }
    }
    fun focusMessage(messageId: String) {
        val id = state.value.selectedId ?: return
        cancel("messages"); mutable.update { it.copy(viewingContext = true, messages = it.messages.copy(loading = true, error = null)) }
        request("context", { repository.messageContext(id, messageId) }, { page ->
            if (page.items.none { it.id == messageId }) throw AppFailure(FailureKind.INVALID_RESPONSE, "Сообщение больше недоступно. Обновите историю.")
            mutable.update { it.copy(messages = MessagingLoad(page.items.sortedBy { m -> m.sequence }), focusedMessageId = messageId, messageCursor = null) }
        }, { e -> if (!accessDenied(id, e)) mutable.update { it.copy(messages = it.messages.copy(loading = false, error = e.message)) } })
    }
    fun clearConversation(hide: Boolean) {
        val id = state.value.selectedId ?: return
        if (state.value.detail.data?.type != "direct") return
        mutate({ repository.clearConversation(id, hide) }) {
            cancelConversationWork(); pending = null; history.remove(id)
            mutable.update { it.copy(messages = MessagingLoad(), pins = MessagingLoad(), searchResults = MessagingLoad(), drafts = it.drafts - id,
                draft = "", replyTo = null, editing = null, attachments = emptyList(), sending = false, pendingSend = false, viewingContext = false, focusedMessageId = null) }
            if (hide) { back(); history.remove(id) } else { loadMessages(false); loadPins() }
            loadConversations(false); loadFolders()
        }
    }
    fun markRead() {
        val id = state.value.selectedId ?: return
        if (!foreground || !visible || state.value.viewingContext) return
        val newest = state.value.messages.data?.maxByOrNull { it.sequence } ?: return
        if (readThrough[id] == newest.id || jobs["read"]?.isActive == true) return
        request("read", { repository.markConversationRead(id, newest.id) }, { read ->
            readThrough[id] = newest.id
            mutable.update { it.copy(detail = it.detail.copy(data = it.detail.data?.copy(unreadCount = read.unreadCount)), conversations = it.conversations.copy(data = it.conversations.data?.map { c -> if (c.id == id) c.copy(unreadCount = read.unreadCount) else c })) }
        }, { e -> accessDenied(id, e) })
    }
    fun upload(filename: String, mimeType: String, content: suspend () -> ByteArray) {
        val s = state.value; val id = s.selectedId ?: return
        if (scope() == null || s.attachmentBusy || s.sending || pending != null || s.editing != null) return
        if (s.attachments.size >= 5) { message("Можно добавить до пяти файлов."); return }
        mutable.update { it.copy(attachmentBusy = true, error = null) }
        request("attachment", {
            val bytes = content()
            try { checkCurrent()
                if (bytes.isEmpty() || bytes.size > 10 * 1024 * 1024) throw AppFailure(FailureKind.VALIDATION, "Размер файла должен быть от 1 байта до 10 МБ.")
                repository.uploadAttachment(id, filename, mimeType, bytes, UUID.randomUUID().toString())
            } finally { bytes.fill(0) }
        }, { item -> mutable.update { it.copy(attachments = it.attachments + item, attachmentBusy = false) } },
            { e -> mutable.update { it.copy(attachmentBusy = false, error = e.message) }; accessDenied(id, e) })
    }
    fun removeAttachment(id: String) { if (pending == null && !state.value.sending) mutable.update { it.copy(attachments = it.attachments.filterNot { item -> item.id == id }) } }
    fun export(attachment: ChatAttachment, onFailure: suspend () -> Unit = {}, write: suspend (ByteArray) -> Unit): Boolean {
        val id = state.value.selectedId ?: return false
        if (scope() == null || state.value.attachmentBusy || attachment.conversationId != id) return false
        mutable.update { it.copy(attachmentBusy = true, error = null) }
        request("export", {
            try { val bytes = repository.downloadAttachment(id, attachment); try { checkCurrent(); write(bytes) } finally { bytes.fill(0) } }
            catch (error: Exception) { withContext(NonCancellable) { onFailure() }; throw error }
        },
            { mutable.update { it.copy(attachmentBusy = false, notice = "Файл сохранён в выбранном месте.") } },
            { e -> mutable.update { it.copy(attachmentBusy = false, error = e.message) }; accessDenied(id, e) })
        return true
    }
    fun searchUsers(value: String) {
        mutable.update { it.copy(userQuery = value.take(100), users = MessagingLoad(loading = true)) }
        request("users", { delay(250); repository.searchPeers(value.take(100)) },
            { list -> mutable.update { it.copy(users = MessagingLoad(list.filterNot { person -> person.id == selfId() })) } },
            { e -> mutable.update { it.copy(users = MessagingLoad(error = e.message)) } })
    }
    fun createDirect(peer: String) = mutate({ repository.createDirect(peer) }) { item -> open(item.id); loadConversations(false) }
    fun createGroup(name: String, description: String, members: List<String>) {
        val signature = listOf(name.trim(), description.trim(), members.sorted().joinToString(",")).joinToString("|")
        val id = groupAttempt?.takeIf { it.first == signature }?.second ?: UUID.randomUUID().toString().also { groupAttempt = signature to it }
        mutate({ repository.createGroup(name.trim(), description.trim(), members, id) }) { item -> groupAttempt = null; open(item.id); loadConversations(false) }
    }
    fun loadMembers() {
        val id = state.value.selectedId ?: return
        mutable.update { it.copy(members = it.members.copy(loading = true, error = null)) }
        request("members", { repository.groupMembers(id) }, { items -> mutable.update { it.copy(members = MessagingLoad(items)) } },
            { e -> if (!accessDenied(id, e)) mutable.update { it.copy(members = it.members.copy(loading = false, error = e.message)) } })
    }
    private fun groupChange(block: suspend (String) -> ChatConversation) {
        val id = state.value.selectedId ?: return
        mutate({ block(id) }) { result -> mutable.update { it.copy(detail = MessagingLoad(result)) }; if (result.type == "group") loadMembers(); loadConversations(false) }
    }
    fun editGroup(name: String, description: String) = groupChange { repository.updateGroup(it, name, description) }
    fun addMember(user: String) = groupChange { repository.addGroupMembers(it, listOf(user)) }
    fun removeMember(user: String) = groupChange { repository.removeGroupMember(it, user) }
    fun changeRole(user: String, role: String) = groupChange { repository.changeGroupRole(it, user, role) }
    fun transferOwnership(user: String) = groupChange { repository.transferGroupOwnership(it, user) }
    fun leaveGroup() { val id = state.value.selectedId ?: return; mutate({ repository.leaveGroup(id) }) { back(); history.remove(id); loadConversations(false) } }
    fun deleteGroup() { val id = state.value.selectedId ?: return; mutate({ repository.deleteGroup(id) }) { back(); history.remove(id); loadConversations(false) } }
    fun conversationNotifications(enabled: Boolean) = groupChange { repository.setConversationNotifications(it, enabled) }
    private fun loadFolders() {
        val old = state.value.folders; mutable.update { it.copy(folders = old.copy(loading = true, error = null)) }
        request("folders", { repository.folders() }, { items ->
            mutable.update { it.copy(folders = MessagingLoad(items)) }
            if (state.value.selectedFolderId != null && items.none { it.id == state.value.selectedFolderId }) selectFolder(null)
        }, { e -> mutable.update { it.copy(folders = old.copy(loading = false, error = e.message)) } })
    }
    fun selectFolder(id: String?) {
        cancel("folderItems"); cancel("folderCandidates")
        mutable.update { it.copy(selectedFolderId = id, folderEntries = MessagingLoad(), folderCursor = null, folderCandidates = MessagingLoad(), candidateCursor = null, candidateQuery = "", folderFilter = "all", query = "") }
        if (id != null) loadFolder(false, false)
    }
    fun createFolder(name: String) = mutate({ repository.createFolder(name) }) { folder -> loadFolders(); selectFolder(folder.id) }
    fun renameFolder(id: String, name: String) = mutate({ repository.renameFolder(id, name) }) { loadFolders() }
    fun deleteFolder(id: String) = mutate({ repository.deleteFolder(id) }) { if (state.value.selectedFolderId == id) selectFolder(null); loadFolders() }
    fun reorderFolders(ids: List<String>) = mutate({ repository.reorderFolders(ids) }) { items -> mutable.update { it.copy(folders = MessagingLoad(items)) } }
    fun searchFolderCandidates(query: String) { mutable.update { it.copy(candidateQuery = query.take(100), candidateCursor = null) }; loadFolder(true, false) }
    private fun loadFolder(candidates: Boolean, append: Boolean) {
        val s = state.value; val id = s.selectedFolderId ?: return
        val old = if (candidates) s.folderCandidates else s.folderEntries; val cursor = (if (candidates) s.candidateCursor else s.folderCursor).takeIf { append }
        if (append && (cursor == null || old.loading)) return
        fun update(load: MessagingLoad<List<FolderEntry>>, next: String?) { mutable.update { if (candidates) it.copy(folderCandidates = load, candidateCursor = next) else it.copy(folderEntries = load, folderCursor = next) } }
        update(old.copy(loading = true, error = null), cursor)
        request(if (candidates) "folderCandidates" else "folderItems",
            { if (!append) delay(250); repository.folderItems(id, FolderQuery(type = if (candidates) "all" else s.folderFilter, search = if (candidates) s.candidateQuery else s.query, cursor = cursor, candidates = candidates)) },
            { page -> verifyCursor(cursor, page.nextCursor); val items = if (append) merge(old.data.orEmpty(), page.items) { it.type + it.id } else page.items
                update(MessagingLoad(items.take(300)), page.nextCursor.takeIf { items.size < 300 }) },
            { e -> update(old.copy(loading = false, error = e.message), cursor) })
    }
    fun folderMembership(folder: String, kind: String, id: String, included: Boolean) =
        mutate({ repository.setFolderItem(folder, kind, id, included) }, false) { loadFolders(); loadFolder(false, false); loadFolder(true, false) }
    fun loadNotifications() { notifications(false); loadPreferences() }
    private fun notifications(append: Boolean, eventNotification: String? = null) {
        eventNotification?.let { pendingNotifications.add(it) }
        while (pendingNotifications.size > 100) pendingNotifications.remove(pendingNotifications.first())
        val s = state.value; val cursor = s.notificationCursor.takeIf { append }; val old = s.notifications
        if (append && (cursor == null || old.loading)) return
        mutable.update { it.copy(notifications = old.copy(loading = true, error = null)) }
        request("notifications", { repository.notifications(cursor) }, { page ->
            verifyCursor(cursor, page.nextCursor)
            val items = if (append) merge(old.data.orEmpty(), page.items) { it.id } else page.items
            mutable.update { it.copy(notifications = MessagingLoad(items.take(300)), notificationCursor = page.nextCursor.takeIf { items.size < 300 }, notificationUnread = page.unreadCount) }
            page.items.filter { it.id in pendingNotifications }.forEach { item ->
                pendingNotifications.remove(item.id)
                if (item.readAt == null) notificationListener?.invoke(item)
            }
        }, { e -> mutable.update { it.copy(notifications = old.copy(loading = false, error = e.message)) } })
    }
    fun readNotification(id: String) = request("notification-read-" + id, { repository.readNotification(id) }, { item ->
        mutable.update { it.copy(notifications = it.notifications.copy(data = it.notifications.data?.map { old -> if (old.id == id) item else old })) }; notifications(false)
    })
    fun readAllNotifications() {
        val unread = state.value.notifications.data.orEmpty().filter { it.readAt == null }.map { it.id }
        if (unread.isNotEmpty()) mutate({ unread.forEach { repository.readNotification(it) } }, false) { notifications(false) }
    }
    private fun loadPreferences() = request("preferences", { repository.notificationPreferences() },
        { prefs -> mutable.update { it.copy(notificationPreferences = MessagingLoad(prefs)) } },
        { e -> mutable.update { it.copy(notificationPreferences = MessagingLoad(error = e.message)) } })
    fun saveNotificationPreferences(prefs: NotificationPreferences) = mutate({ repository.saveNotificationPreferences(prefs) }, false) {
        result -> mutable.update { it.copy(notificationPreferences = MessagingLoad(result), notice = "Настройки уведомлений сохранены.") }
    }
    private fun startEvents() {
        if (eventJob?.isActive == true || scope() == null) return
        val epoch = generation; val expectedScope = scope()
        eventJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            var attempt = 0
            while (isActive && foreground && epoch == generation && expectedScope == scope()) {
                var ownedSession: UserEvents? = null
                try {
                    mutable.update { it.copy(realtimeLabel = if (attempt == 0) "Подключаем обновления…" else "Восстанавливаем обновления…") }
                    val session = repository.openUserEvents(); ownedSession = session; ensureActive()
                    if (expectedScope != scope()) { session.close(); break }
                    eventSession = session
                    for (event in session.events) {
                        ensureActive(); if (expectedScope != scope()) break
                        if (event.type == "connected" || event.type == "call.updated") callEventListener?.invoke(event)
                        if (event.type == "connected") {
                            listOf("pins", "searchMessages", "context").forEach { key -> this@MessagingViewModel.cancel(key) }
                            history.clear()
                            mutable.update { it.copy(realtimeLabel = "Обновления подключены", pins = MessagingLoad(), searchResults = MessagingLoad(), searchCursor = null, pinsCursor = null, viewingContext = false, focusedMessageId = null) }
                            refresh(); if (state.value.searchQuery.isNotBlank()) searchMessages(state.value.searchQuery); continue
                        }
                        if (!eventIds.add(event.id)) continue
                        if (eventIds.size > 512) eventIds.remove(eventIds.first())
                        if (event.type == "notification.created" && event.notificationId != null) notifications(false, event.notificationId)
                        scheduleReconcile(event)
                    }; attempt++
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (closed: UserEventsDisconnected) { if (!closed.retryable) { mutable.update { it.copy(realtimeLabel = "Обновления недоступны. Обновите вручную.") }; break }; attempt++ }
                catch (_: Exception) { attempt++ }
                finally { ownedSession?.close(); if (eventSession === ownedSession) eventSession = null }
                if (expectedScope != scope() || epoch != generation) break
                mutable.update { it.copy(realtimeLabel = "Нет связи. Доступна загруженная история.") }
                delay((2000L * attempt.coerceAtLeast(1)).coerceAtMost(30_000L))
            }
        }
        eventJob?.start()
    }
    private fun scheduleReconcile(event: UserEvent) {
        if (listOf("deleted", "removed", "cleared", "membership").any(event.type::contains)) event.conversationId?.let(history::remove)
        val revoked = event.type == "conversation.deleted" || event.type == "conversation.hidden" ||
            event.type == "conversation.member.removed" && event.userId == selfId()
        if (revoked) mutable.update { it.copy(drafts = it.drafts - event.conversationId.orEmpty()) }
        if (revoked && event.conversationId == state.value.selectedId) {
            val id = state.value.selectedId
            back(); id?.let(history::remove); mutable.update { it.copy(drafts = it.drafts - id.orEmpty()) }
            message("Чат скрыт или доступ к нему закрыт.")
        } else if (event.conversationId == state.value.selectedId && event.type == "conversation.history.cleared") {
            cancel("messages"); cancel("searchMessages"); cancel("pins"); cancel("context"); pending = null
            mutable.update { it.copy(messages = MessagingLoad(), pins = MessagingLoad(), searchResults = MessagingLoad(), viewingContext = false, focusedMessageId = null, drafts = it.drafts - it.selectedId.orEmpty(), draft = "", replyTo = null, editing = null, attachments = emptyList(), pendingSend = false) }
        } else if (event.conversationId == state.value.selectedId && event.type == "message.deleted") {
            listOf("messages", "pins", "searchMessages", "context").forEach(::cancel)
            mutable.update { it.copy(messages = MessagingLoad(), pins = MessagingLoad(), searchResults = MessagingLoad(), viewingContext = false, focusedMessageId = null, replyTo = null, editing = null) }
        }
        if (reconcileJob?.isActive == true) return
        reconcileJob = viewModelScope.launch { delay(500); loadConversations(false); loadFolders()
            if (state.value.selectedId != null) { loadDetail(); loadMessages(false); loadPins() }
            if (state.value.selectedFolderId != null) loadFolder(false, false)
            notifications(false)
        }
    }
    private fun stopEvents() {
        eventJob?.cancel(); eventJob = null; eventSession?.close(); eventSession = null; reconcileJob?.cancel(); reconcileJob = null
        mutable.update { it.copy(realtimeLabel = "Обновления приостановлены") }
    }
    fun message(text: String) { mutable.update { it.copy(error = text) } }
    fun clearMessage() { mutable.update { it.copy(error = null, notice = null) } }
    private fun selfId() = foundation.state.value.session?.user?.id.orEmpty()
    private fun scope(): String? {
        val s = foundation.state.value; if (s.session?.guestMeeting != null) return null; val p = s.selectedProfile ?: return null; val user = s.session?.user ?: return null
        return listOf(p.id, p.origin, user.id).joinToString("|")
    }
    private suspend fun checkCurrent() { coroutineContext.ensureActive(); if (scope() != account) throw CancellationException("Scope changed") }
    private fun cancel(key: String) { tickets[key] = (tickets[key] ?: 0) + 1; jobs.remove(key)?.cancel() }
    private fun <T> request(key: String, block: suspend () -> T, done: (T) -> Unit, failed: (AppFailure) -> Unit = { message(it.message ?: "Действие не выполнено.") }) {
        if (scope() == null) return
        cancel(key); val ticket = tickets.getValue(key); val epoch = generation; val identity = scope()
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try { val value = block(); coroutineContext.ensureActive(); if (epoch == generation && identity == scope() && tickets[key] == ticket) done(value) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                coroutineContext.ensureActive()
                if (epoch == generation && identity == scope() && tickets[key] == ticket) failed(error as? AppFailure ?: AppFailure(FailureKind.UNAVAILABLE, "Не удалось выполнить действие. Обновите данные и повторите."))
            } finally { if (epoch == generation && identity == scope() && tickets[key] == ticket) jobs.remove(key) }
        }; jobs[key] = job; job.start()
    }
    private fun <T> mutate(block: suspend () -> T, revise: Boolean = true, done: (T) -> Unit) {
        if (scope() == null || state.value.busy) return
        val selected = state.value.selectedId
        mutable.update { it.copy(busy = true, error = null) }
        request("mutation", block, { value ->
            val stillSelected = selected == state.value.selectedId
            mutable.update { it.copy(busy = false, mutationRevision = it.mutationRevision + if (revise && stillSelected) 1 else 0) }
            if (stillSelected) done(value) else { history.clear(); loadConversations(false); loadFolders() }
        }, { e ->
            mutable.update { it.copy(busy = false, error = e.message) }
            if (selected == state.value.selectedId) selected?.let { accessDenied(it, e) }
        })
    }
    private fun verifyCursor(previous: String?, next: String?) { if (previous != null && previous == next) throw AppFailure(FailureKind.INVALID_RESPONSE, "Сервер повторил страницу. Обновите список.") }
    private fun <T> merge(old: List<T>, new: List<T>, key: (T) -> String): List<T> = (old + new).associateBy(key).values.toList()
    private fun mergeMessages(old: List<ChatMessage>, new: List<ChatMessage>, keepOldest: Boolean = false): List<ChatMessage> =
        (old + new).groupBy { it.id }.values.map { copies -> copies.maxWith(compareBy<ChatMessage> { it.version }.thenBy { it.updatedAt }) }
            .sortedWith(compareBy<ChatMessage> { it.sequence }.thenBy { it.createdAt })
            .let { if (keepOldest) it.take(200) else it.takeLast(200) }
    class Factory(private val foundation: FoundationRepository, private val repository: MessagingRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(MessagingViewModel::class.java)); return MessagingViewModel(foundation, repository) as T
        }
    }
}
