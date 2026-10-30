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
class MessagingViewModel(private val foundation: FoundationRepository, private val repository: MessagingRepository,
    private val outgoingStore: OutgoingMessageStore = MemoryOutgoingMessageStore()) : ViewModel() {
    private val mutable = MutableStateFlow(MessagingUiState(selfId = selfId()))
    val state = mutable.asStateFlow()
    private var account = scope()
    private var generation = 0L
    private val jobs = mutableMapOf<String, Job>()
    private val tickets = mutableMapOf<String, Long>()
    private var nextTicket = 0L
    private var visible = false
    private var foreground = false
    private var eventJob: Job? = null
    private var eventSession: UserEvents? = null
    private var reconcileJob: Job? = null
    private var notificationDeliveryJob: Job? = null
    private val outgoing = linkedMapOf<String, LocalOutgoingMessage>()
    private val persisting = mutableMapOf<String, Deferred<Result<Unit>>>()
    private val durableOutgoing = mutableSetOf<String>()
    private val retiredOutgoing = linkedSetOf<String>()
    private var groupAttempt: Pair<String, String>? = null
    private data class History(val detail: ChatConversation?, val items: List<ChatMessage>, val cursor: String?)
    private val history = linkedMapOf<String, History>()
    private val eventIds = linkedSetOf<String>()
    private val readThrough = mutableMapOf<String, String>()
    private val pendingNotifications = linkedSetOf<String>()
    private val notificationPageCursors = linkedSetOf<String>()
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
        draft = ::draft, send = ::send, retryMessage = ::retryMessage, discardMessage = ::discardMessage, reply = ::reply, edit = ::edit, cancelCompose = ::cancelCompose,
        delete = ::delete, markRead = ::markRead, removeAttachment = ::removeAttachment,
        searchUsers = ::searchUsers, createDirect = ::createDirect, createGroup = ::createGroup,
        loadMembers = ::loadMembers, editGroup = ::editGroup, deleteGroupAvatar = ::deleteGroupAvatar, addMember = ::addMember, removeMember = ::removeMember,
        leaveGroup = ::leaveGroup, deleteGroup = ::deleteGroup, conversationNotifications = ::conversationNotifications,
        selectFolder = ::selectFolder, createFolder = ::createFolder, renameFolder = ::renameFolder, deleteFolder = ::deleteFolder,
        reorderFolders = ::reorderFolders, moreFolderItems = { loadFolder(false, true) },
        searchFolderCandidates = ::searchFolderCandidates, moreFolderCandidates = { loadFolder(true, true) }, folderMembership = ::folderMembership,
        loadNotifications = ::loadNotifications, moreNotifications = { notifications(true) }, readNotification = ::readNotification,
        notificationFilter = ::notificationFilter,
        readAllNotifications = ::readAllNotifications, saveNotificationPreferences = ::saveNotificationPreferences, clearMessage = ::clearMessage,
    )
    fun foreground(value: Boolean) {
        foreground = value
        if (value && scope() != null) { mutable.update { it.copy(selfId = selfId()) }; startEvents(); deliverPendingNotifications() } else stopEvents()
    }
    fun workspaceVisible(value: Boolean) { visible = value }
    fun notificationPermission(enabled: Boolean) { mutable.update { it.copy(notificationPermissionEnabled = enabled) } }
    fun onNotification(listener: ((InboxNotification) -> Unit)?) {
        notificationListener = listener
        if (listener == null) { notificationDeliveryJob?.cancel(); notificationDeliveryJob = null }
        else deliverPendingNotifications()
    }
    fun onCallEvent(listener: ((UserEvent) -> Unit)?) { callEventListener = listener }
    fun suspendWork() {
        generation++; jobs.values.toList().forEach(Job::cancel); jobs.clear(); tickets.clear()
        stopEvents(); history.clear(); eventIds.clear(); readThrough.clear(); pendingNotifications.clear(); notificationPageCursors.clear(); outgoing.clear(); persisting.clear(); durableOutgoing.clear(); retiredOutgoing.clear(); groupAttempt = null; mutable.value = MessagingUiState()
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
        rememberDraft(); rememberHistory(); cancelConversationWork()
        loadOutgoing(id)
        val cached = history.remove(id)?.also { history[id] = it }
        mutable.update { it.copy(selectedId = id, detail = MessagingLoad(cached?.detail), messages = MessagingLoad(cached?.items), messageCursor = cached?.cursor,
            draft = it.drafts[id].orEmpty(), searchQuery = "", searchResults = MessagingLoad(), searchCursor = null, pins = MessagingLoad(), pinsCursor = null, focusedMessageId = null, viewingContext = false, replyTo = null, editing = null, attachments = emptyList(), members = MessagingLoad(), peerOnline = null,
            localMessages = outgoing.values.filter { row -> row.targetId == id }, pendingSend = false, sending = false, attachmentBusy = false, avatarBusy = false, error = null, notice = null) }
        trimOutgoing(); loadDetail(); loadMessages(false); loadPins()
    }
    fun back() {
        rememberDraft(); rememberHistory(); cancelConversationWork()
        mutable.update { it.copy(selectedId = null, detail = MessagingLoad(), messages = MessagingLoad(), messageCursor = null,
            draft = "", searchQuery = "", searchResults = MessagingLoad(), searchCursor = null, pins = MessagingLoad(), pinsCursor = null, focusedMessageId = null, viewingContext = false, attachments = emptyList(), replyTo = null, editing = null, members = MessagingLoad(),
            localMessages = emptyList(), pendingSend = false, sending = false, attachmentBusy = false, avatarBusy = false, peerOnline = null) }
    }
    private fun cancelConversationWork() { listOf("outgoing-load", "detail", "messages", "members", "presence", "send", "attachment", "export", "read", "searchMessages", "pins", "context", "group-avatar").forEach(::cancel) }
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
            reconcileOutgoing(id, page.items)
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
    private fun resourceDenied(error: AppFailure) = error.kind in setOf(FailureKind.FORBIDDEN, FailureKind.NOT_FOUND)
    private fun revalidateResourceAccess(id: String, error: AppFailure) {
        // An optional endpoint may be unavailable on an older backend; a missing message
        // or attachment also says nothing about access to the conversation itself.
        // Only the canonical detail/history handlers may invalidate that authorization.
        if (resourceDenied(error) && state.value.selectedId == id) loadDetail()
    }
    fun draft(value: String) { if (!state.value.sending) mutable.update { it.copy(draft = value.take(8000), drafts = boundedDrafts(it.drafts, it.selectedId, value.take(8000))) } }
    fun reply(message: ChatMessage) { if (message.kind == "message" && !state.value.sending && message.deletedAt == null) mutable.update { it.copy(replyTo = message, editing = null) } }
    fun edit(message: ChatMessage) {
        if (message.kind != "message" || state.value.sending || message.senderId != selfId() || message.deletedAt != null) return
        mutable.update { it.copy(editing = message, replyTo = null, draft = message.text, attachments = emptyList()) }
    }
    fun cancelCompose() { mutable.update { it.copy(editing = null, replyTo = null, draft = "") } }
    fun send() {
        val s = state.value; val id = s.selectedId ?: return
        val identity = scope() ?: return
        if (s.sending || s.attachmentBusy || s.draft.codePointCount(0, s.draft.length) > 4000) return
        if (s.draft.isBlank() && s.attachments.isEmpty()) return
        val editing = s.editing
        if (editing != null) {
            mutable.update { it.copy(sending = true, error = null, notice = null) }
            request("send", { repository.editMessage(id, editing.id, s.draft.trim()) }, { result ->
                cancel("messages")
                mutable.update { it.copy(messages = MessagingLoad(mergeMessages(it.messages.data.orEmpty(), listOf(result))),
                    draft = "", editing = null, replyTo = null, sending = false, error = null, notice = null) }
                rememberHistory()
            }, { mutable.update { it.copy(sending = false, error = null, notice = null) } })
            return
        }
        if (outgoing.values.count { it.targetId == id } >= 100 || outgoing.size >= 200 || outgoing.values.count { it.sending } >= 100) return
        val user = foundation.state.value.session?.user ?: return
        val row = LocalOutgoingMessage(UUID.randomUUID().toString(), OutgoingMessageKind.CONVERSATION, id, s.draft.trim(), user.id, user.displayLabel,
            replyTo = s.replyTo?.id, attachments = s.attachments.map { OutgoingAttachment(it.id, it.filename, it.mimeType, it.size, it.checksum) })
        outgoing[row.clientRequestId] = row
        mutable.update { it.copy(draft = "", drafts = it.drafts - id, attachments = emptyList(), replyTo = null, error = null, notice = null, pendingSend = false) }
        publishOutgoing()
        persistAndDeliver(identity, row)
    }
    fun retryMessage(clientRequestId: String) {
        val row = outgoing[clientRequestId] ?: return
        val identity = scope() ?: return
        if (row.targetId != state.value.selectedId || row.sending) return
        val sending = row.copy(failed = false, sending = true)
        outgoing[clientRequestId] = sending; publishOutgoing()
        persistAndDeliver(identity, sending)
    }
    fun discardMessage(clientRequestId: String) {
        val row = outgoing[clientRequestId] ?: return
        if (row.targetId != state.value.selectedId) return
        cancel("outgoing-$clientRequestId")
        removeOutgoing(scope() ?: return, row)
    }
    private fun publishOutgoing() {
        mutable.update { it.copy(localMessages = outgoing.values.filter { row -> row.targetId == it.selectedId }.sortedBy { row -> row.createdAt }, pendingSend = false) }
    }
    private fun persistAndDeliver(identity: String, row: LocalOutgoingMessage) {
        val saved = viewModelScope.async(NonCancellable, start = CoroutineStart.UNDISPATCHED) {
            runCatching { outgoingStore.put(identity, row) }.also { result ->
                if (result.isSuccess && scope() == identity) { durableOutgoing.add(row.clientRequestId); trimOutgoing() }
            }
        }
        persisting[row.clientRequestId] = saved
        saved.invokeOnCompletion { if (persisting[row.clientRequestId] === saved) persisting.remove(row.clientRequestId) }
        var acknowledged = false
        val job = request("outgoing-${row.clientRequestId}", {
            saved.await().getOrThrow(); checkCurrent()
            repository.sendMessage(row.targetId, row.input)
        }, { result ->
            acknowledged = true
            removeOutgoing(identity, row)
            if (state.value.selectedId == row.targetId) {
                cancel("messages")
                mutable.update { it.copy(messages = MessagingLoad(mergeMessages(it.messages.data.orEmpty(), listOf(result))), error = null, notice = null) }
                rememberHistory()
            } else history.remove(row.targetId)
            loadConversations(false)
        }, {
            outgoing[row.clientRequestId]?.let { outgoing[row.clientRequestId] = it.copy(failed = true, sending = false) }
            trimOutgoing(); publishOutgoing()
            mutable.update { it.copy(error = null, notice = null) }
        })
        job?.invokeOnCompletion {
            if (!acknowledged && scope() == identity) {
                outgoing[row.clientRequestId]?.let { outgoing[row.clientRequestId] = it.copy(failed = true, sending = false) }
                trimOutgoing(); publishOutgoing()
            }
        }
    }
    private fun removeOutgoing(identity: String, row: LocalOutgoingMessage) {
        retiredOutgoing.add(row.clientRequestId)
        while (retiredOutgoing.size > 1000) retiredOutgoing.remove(retiredOutgoing.first())
        jobs.remove("outgoing-${row.clientRequestId}"); tickets.remove("outgoing-${row.clientRequestId}")
        outgoing.remove(row.clientRequestId); durableOutgoing.remove(row.clientRequestId); publishOutgoing()
        val saved = persisting.remove(row.clientRequestId)
        viewModelScope.launch(NonCancellable) { saved?.await(); runCatching { outgoingStore.remove(identity, row.kind, row.targetId, row.clientRequestId) } }
    }
    private fun loadOutgoing(id: String) {
        val identity = scope() ?: return
        request("outgoing-load", { outgoingStore.messages(identity, OutgoingMessageKind.CONVERSATION, id) }, { rows ->
            rows.filter { it.clientRequestId !in retiredOutgoing && it.clientRequestId !in outgoing }.take((200 - outgoing.size).coerceAtLeast(0)).forEach {
                outgoing.putIfAbsent(it.clientRequestId, it); durableOutgoing.add(it.clientRequestId)
            }
            reconcileOutgoing(id, state.value.messages.data.orEmpty().filter { it.conversationId == id })
            publishOutgoing()
        }, { /* Storage failure must not expose private message content through a banner. */ })
    }
    private fun trimOutgoing() {
        outgoing.values.filter { it.targetId != state.value.selectedId && !it.sending && it.clientRequestId in durableOutgoing }.map { it.clientRequestId }
            .forEach { outgoing.remove(it); durableOutgoing.remove(it) }
    }
    private fun reconcileOutgoing(id: String, items: List<ChatMessage>) {
        val identity = scope() ?: return
        outgoing.values.filter { row -> row.targetId == id && items.any { it.senderId == selfId() && it.clientRequestId == row.clientRequestId } }
            .toList().forEach { row -> cancel("outgoing-${row.clientRequestId}"); removeOutgoing(identity, row) }
    }
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
        if (s.editing == null) mutable.update { it.copy(drafts = boundedDrafts(it.drafts, s.selectedId, if (!s.sending) s.draft else "")) }
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
        }, { e ->
            mutable.update { it.copy(searchResults = if (resourceDenied(e)) MessagingLoad(error = e.message) else it.searchResults.copy(loading = false, error = e.message),
                searchCursor = it.searchCursor.takeUnless { resourceDenied(e) }) }
            revalidateResourceAccess(id, e)
        })
    }
    fun loadPins(append: Boolean = false) {
        val s = state.value; val id = s.selectedId ?: return; val cursor = s.pinsCursor.takeIf { append }
        if (append && (cursor == null || s.pins.loading)) return
        mutable.update { it.copy(pins = it.pins.copy(loading = true, error = null)) }
        request("pins", { repository.messagePins(id, cursor) }, { page ->
            verifyCursor(cursor, page.nextCursor)
            mutable.update { it.copy(pins = MessagingLoad((if (append) merge(it.pins.data.orEmpty(), page.items) { it.id } else page.items).take(100)), pinsCursor = page.nextCursor) }
        }, { e ->
            mutable.update { it.copy(pins = if (resourceDenied(e)) MessagingLoad(error = e.message) else it.pins.copy(loading = false, error = e.message),
                pinsCursor = it.pinsCursor.takeUnless { resourceDenied(e) }) }
            revalidateResourceAccess(id, e)
        })
    }
    fun pin(message: ChatMessage, pinned: Boolean) {
        val id = state.value.selectedId ?: return
        if (message.conversationId != id || message.kind != "message" || message.deletedAt != null) return
        mutate({ repository.pinMessage(id, message.id, pinned) }, revise = false, optionalResource = true) {
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
        }, { e ->
            val denied = resourceDenied(e)
            mutable.update { it.copy(messages = if (denied) MessagingLoad(error = e.message) else it.messages.copy(loading = false, error = e.message),
                viewingContext = it.viewingContext && !denied, focusedMessageId = it.focusedMessageId.takeUnless { denied }) }
            revalidateResourceAccess(id, e)
            if (denied && state.value.selectedId == id) loadMessages(false)
        })
    }
    fun clearConversation(hide: Boolean) {
        val id = state.value.selectedId ?: return
        if (state.value.detail.data?.type != "direct") return
        mutate({ repository.clearConversation(id, hide) }) {
            cancelConversationWork(); history.remove(id)
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
        if (scope() == null || s.attachmentBusy || s.sending || s.editing != null) return
        if (s.attachments.size >= 5) { message("Можно добавить до пяти файлов."); return }
        mutable.update { it.copy(attachmentBusy = true, error = null) }
        request("attachment", {
            val bytes = content()
            try { checkCurrent()
                if (bytes.isEmpty() || bytes.size > 10 * 1024 * 1024) throw AppFailure(FailureKind.VALIDATION, "Размер файла должен быть от 1 байта до 10 МБ.")
                repository.uploadAttachment(id, filename, mimeType, bytes, UUID.randomUUID().toString())
            } finally { bytes.fill(0) }
        }, { item -> mutable.update { it.copy(attachments = it.attachments + item, attachmentBusy = false) } },
            { e -> mutable.update { it.copy(attachmentBusy = false, error = e.message) }; revalidateResourceAccess(id, e) })
    }
    fun removeAttachment(id: String) { if (!state.value.sending) mutable.update { it.copy(attachments = it.attachments.filterNot { item -> item.id == id }) } }
    fun export(attachment: ChatAttachment, onFailure: suspend () -> Unit = {}, write: suspend (ByteArray) -> Unit): Boolean {
        val id = state.value.selectedId ?: return false
        if (scope() == null || state.value.attachmentBusy || attachment.conversationId != id) return false
        mutable.update { it.copy(attachmentBusy = true, error = null) }
        var completed = false
        val export = request("export", {
            val bytes = repository.downloadAttachment(id, attachment)
            try { checkCurrent(); write(bytes); completed = true } finally { bytes.fill(0) }
        },
            { mutable.update { it.copy(attachmentBusy = false, notice = "Файл сохранён в выбранном месте.") } },
            { e -> mutable.update { it.copy(attachmentBusy = false, error = e.message) }; revalidateResourceAccess(id, e) }) ?: return false
        // Cancellation before the LAZY request body starts must also remove its new SAF document.
        export.invokeOnCompletion { if (!completed) viewModelScope.launch(NonCancellable, start = CoroutineStart.UNDISPATCHED) { runCatching { onFailure() } } }
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
    fun uploadGroupAvatar(mimeType: String, bytes: ByteArray) = groupAvatarChange(mimeType, bytes)
    fun deleteGroupAvatar() = groupAvatarChange()
    private fun groupAvatarChange(mimeType: String? = null, bytes: ByteArray? = null) {
        val s = state.value
        val id = s.selectedId
        if (scope() == null || id == null || s.detail.data?.canManage != true || s.busy || s.avatarBusy) {
            bytes?.fill(0)
            return
        }
        mutable.update { it.copy(avatarBusy = true, error = null) }
        val job = request("group-avatar", {
            try {
                checkCurrent()
                if (bytes != null && mimeType != null) repository.putGroupAvatar(id, mimeType, bytes)
                else repository.deleteGroupAvatar(id)
            } finally { bytes?.fill(0) }
        }, { result ->
            mutable.update { it.copy(detail = MessagingLoad(result), avatarBusy = false, avatarRevision = it.avatarRevision + 1) }
            loadConversations(false)
        }, { error ->
            mutable.update { it.copy(avatarBusy = false, error = error.message, avatarRevision = it.avatarRevision + 1) }
            revalidateResourceAccess(id, error)
        })
        job?.invokeOnCompletion { bytes?.fill(0) } ?: bytes?.fill(0)
    }
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
    fun notificationFilter(value: NotificationFilter) {
        if (value == state.value.notificationFilter) return
        cancel("notifications"); notificationPageCursors.clear()
        mutable.update { it.copy(notificationFilter = value, notifications = MessagingLoad(), notificationCursor = null) }
        notifications(false)
    }
    private data class NotificationBatch(val page: NotificationPage, val cursors: Set<String>)
    private fun notifications(append: Boolean, mutationError: String? = null) {
        val s = state.value; val cursor = s.notificationCursor.takeIf { append }; val old = s.notifications
        if (scope() == null || s.notificationBusy || append && (cursor == null || old.loading)) return
        val seen = if (append) notificationPageCursors.toMutableSet() else linkedSetOf()
        mutable.update { it.copy(notifications = old.copy(loading = true, error = null)) }
        request("notifications", {
            // The server has a single, stable createdAt/id cursor, without an unread filter.
            // Scan past read-only pages, then publish the first matching page immediately.
            // Keep its cursor instead of traversing old history merely to fill thirty rows.
            var next = cursor; var pages = 0; var unread = 0L
            val matches = linkedMapOf<String, InboxNotification>()
            do {
                checkCurrent()
                if (++pages > 1000 || next != null && !seen.add(next!!)) throw invalidNotificationCursor()
                val page = repository.notifications(next); checkCurrent()
                if (page.nextCursor != null && page.nextCursor in seen) throw invalidNotificationCursor()
                unread = page.unreadCount
                page.items.forEach { item ->
                    if (s.notificationFilter == NotificationFilter.ALL || item.readAt == null) matches[item.id] = item
                }
                next = page.nextCursor
                if (s.notificationFilter == NotificationFilter.UNREAD && unread == 0L) next = null
            } while (s.notificationFilter == NotificationFilter.UNREAD && matches.isEmpty() && next != null)
            NotificationBatch(NotificationPage(matches.values.toList(), next, unread), seen)
        }, { batch ->
            val page = batch.page
            val items = if (append) merge(old.data.orEmpty(), page.items) { it.id } else page.items
            notificationPageCursors.clear(); notificationPageCursors.addAll(batch.cursors.toList().takeLast(1000))
            // Keep a bounded window while preserving the cursor for older notifications.
            mutable.update { it.copy(notifications = MessagingLoad(items.takeLast(300), error = mutationError), notificationCursor = page.nextCursor, notificationUnread = page.unreadCount) }
            deliverPendingNotifications()
        }, { e -> mutable.update { it.copy(notifications = old.copy(loading = false, error = e.message)) } })
    }
    private fun queueNotification(id: String) {
        pendingNotifications.add(id)
        while (pendingNotifications.size > 1000) pendingNotifications.remove(pendingNotifications.first())
        deliverPendingNotifications()
    }
    /** Resolve only observed realtime IDs, independently of the visible filter and page.
     * A burst can move an event beyond page one before REST completes. Loading older history
     * must neither hide that event nor alert for unrelated, previously unread history. */
    private fun deliverPendingNotifications() {
        if (!foreground || scope() == null || notificationListener == null || pendingNotifications.isEmpty() || notificationDeliveryJob?.isActive == true) return
        val delivery = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                do {
                    val targets = pendingNotifications.toSet()
                    val remaining = targets.toMutableSet()
                    var cursor: String? = null; var pages = 0
                    val seen = linkedSetOf<String>()
                    do {
                        checkCurrent()
                        if (++pages > 1000 || cursor != null && !seen.add(cursor!!)) throw invalidNotificationCursor()
                        val page = repository.notifications(cursor); checkCurrent()
                        if (page.nextCursor != null && page.nextCursor in seen) throw invalidNotificationCursor()
                        for (item in page.items) if (item.id in remaining) {
                            checkCurrent()
                            val listener = notificationListener ?: break
                            remaining.remove(item.id)
                            pendingNotifications.remove(item.id)
                            if (item.readAt == null) listener(item)
                        }
                        cursor = page.nextCursor
                    } while (remaining.isNotEmpty() && cursor != null)
                    // New IDs queued while scanning start at the newest page. An unavailable
                    // ID stays pending for a later refresh/reconnect, without a tight retry loop.
                } while (pendingNotifications.any { it !in targets })
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Retain unresolved IDs; the next successful refresh retries. */ }
            finally { if (notificationDeliveryJob === coroutineContext[Job]) notificationDeliveryJob = null }
        }
        notificationDeliveryJob = delivery; delivery.start()
    }
    fun readNotification(id: String) {
        if (scope() == null || state.value.notificationBusy) return
        val existing = state.value.notifications.data.orEmpty().firstOrNull { it.id == id }
        if (existing?.readAt != null) return
        startNotificationMutation()
        request("notification-mutation", {
            checkCurrent()
            repository.readNotification(id).also { checkCurrent(); validateNotificationRead(id, it) }
        }, { item ->
            applyNotificationRead(item, existing != null)
            finishNotificationMutation()
        }, { e -> finishNotificationMutation(e.message ?: "Не удалось прочитать уведомление.") })
    }
    fun readAllNotifications() {
        if (scope() == null || state.value.notificationBusy || state.value.notificationUnread == 0L) return
        startNotificationMutation()
        request("notification-mutation", {
            var cursor: String? = null; var pages = 0
            val seen = linkedSetOf<String>(); val read = hashSetOf<String>()
            // Read each server page, including notifications that have never been displayed.
            // New events arriving after the first page remain unread and are reconciled below.
            do {
                checkCurrent()
                if (++pages > 1000 || cursor != null && !seen.add(cursor!!)) throw invalidNotificationCursor()
                val page = repository.notifications(cursor); checkCurrent()
                if (page.nextCursor != null && page.nextCursor in seen) throw invalidNotificationCursor()
                // Count is global, including older pages. A server-confirmed zero avoids
                // walking years of read history; do not infer zero from local POST counts.
                if (page.unreadCount == 0L) break
                for (item in page.items) if (item.readAt == null && read.add(item.id)) {
                    checkCurrent()
                    val acknowledged = repository.readNotification(item.id); checkCurrent()
                    validateNotificationRead(item.id, acknowledged)
                    applyNotificationRead(acknowledged, true)
                }
                cursor = page.nextCursor
            } while (cursor != null)
        }, { finishNotificationMutation() }, { e ->
            finishNotificationMutation("Не все уведомления прочитаны. ${e.message ?: "Повторите попытку."}")
        })
    }
    private fun startNotificationMutation() {
        // A list response begun before the read acknowledgement cannot restore unread state.
        cancel("notifications")
        mutable.update { it.copy(notificationBusy = true, notifications = it.notifications.copy(loading = false, error = null)) }
    }
    private fun applyNotificationRead(item: InboxNotification, wasUnread: Boolean) {
        mutable.update { state -> state.copy(
            notifications = state.notifications.copy(data = state.notifications.data?.map { if (it.id == item.id) item else it }
                ?.filter { state.notificationFilter == NotificationFilter.ALL || it.readAt == null }),
            notificationUnread = (state.notificationUnread - if (wasUnread) 1 else 0).coerceAtLeast(0),
        ) }
    }
    private fun finishNotificationMutation(error: String? = null) {
        mutable.update { it.copy(notificationBusy = false, notifications = it.notifications.copy(error = error)) }
        notifications(false, mutationError = error)
    }
    private fun validateNotificationRead(id: String, item: InboxNotification) {
        if (item.id != id || item.readAt == null) throw AppFailure(FailureKind.INVALID_RESPONSE, "Сервер не подтвердил прочтение уведомления.")
    }
    private fun invalidNotificationCursor() = AppFailure(FailureKind.INVALID_RESPONSE, "Сервер повторил страницу уведомлений или вернул слишком длинный список. Обновите список.")
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
                        if (event.type == "notification.created") event.notificationId?.let { queueNotification(it); notifications(false) }
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
            cancel("messages"); cancel("searchMessages"); cancel("pins"); cancel("context")
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
        notificationDeliveryJob?.cancel(); notificationDeliveryJob = null
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
    private fun cancel(key: String) { tickets[key] = ++nextTicket; jobs.remove(key)?.cancel() }
    private fun <T> request(key: String, block: suspend () -> T, done: (T) -> Unit, failed: (AppFailure) -> Unit = { message(it.message ?: "Действие не выполнено.") }): Job? {
        if (scope() == null) return null
        cancel(key); val ticket = tickets.getValue(key); val epoch = generation; val identity = scope()
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try { val value = block(); coroutineContext.ensureActive(); if (epoch == generation && identity == scope() && tickets[key] == ticket) done(value) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                coroutineContext.ensureActive()
                if (epoch == generation && identity == scope() && tickets[key] == ticket) failed(error as? AppFailure ?: AppFailure(FailureKind.UNAVAILABLE, "Не удалось выполнить действие. Обновите данные и повторите."))
            } finally { if (epoch == generation && identity == scope() && tickets[key] == ticket) { jobs.remove(key); if (key.startsWith("outgoing-")) tickets.remove(key) } }
        }; jobs[key] = job; job.start(); return job
    }
    private fun <T> mutate(block: suspend () -> T, revise: Boolean = true, optionalResource: Boolean = false, done: (T) -> Unit) {
        if (scope() == null || state.value.busy) return
        val selected = state.value.selectedId
        mutable.update { it.copy(busy = true, error = null) }
        request("mutation", block, { value ->
            val stillSelected = selected == state.value.selectedId
            mutable.update { it.copy(busy = false, mutationRevision = it.mutationRevision + if (revise && stillSelected) 1 else 0) }
            if (stillSelected) done(value) else { history.clear(); loadConversations(false); loadFolders() }
        }, { e ->
            mutable.update { it.copy(busy = false, error = e.message) }
            if (selected == state.value.selectedId) selected?.let { id ->
                if (optionalResource) {
                    if (resourceDenied(e)) mutable.update { it.copy(pins = MessagingLoad(error = e.message), pinsCursor = null) }
                    revalidateResourceAccess(id, e)
                } else accessDenied(id, e)
            }
        })
    }
    private fun verifyCursor(previous: String?, next: String?) { if (previous != null && previous == next) throw AppFailure(FailureKind.INVALID_RESPONSE, "Сервер повторил страницу. Обновите список.") }
    private fun <T> merge(old: List<T>, new: List<T>, key: (T) -> String): List<T> = (old + new).associateBy(key).values.toList()
    private fun mergeMessages(old: List<ChatMessage>, new: List<ChatMessage>, keepOldest: Boolean = false): List<ChatMessage> =
        (old + new).groupBy { it.id }.values.map { copies -> copies.maxWith(compareBy<ChatMessage> { it.version }.thenBy { it.updatedAt }) }
            .sortedWith(compareBy<ChatMessage> { it.sequence }.thenBy { it.createdAt })
            .let { if (keepOldest) it.take(200) else it.takeLast(200) }
    class Factory(private val foundation: FoundationRepository, private val repository: MessagingRepository, private val outgoingStore: OutgoingMessageStore = MemoryOutgoingMessageStore()) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(MessagingViewModel::class.java)); return MessagingViewModel(foundation, repository, outgoingStore) as T
        }
    }
}
