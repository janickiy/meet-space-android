package com.meetspace.app

import com.meetspace.app.ui.ChatFilter
import com.meetspace.core.data.*
import com.meetspace.core.model.*
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MessagingViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    @Test fun draftsFollowConversationAndAreDiscardedOnAccountChange() = runTest(dispatcher) {
        val f = FakeFoundation(); val m = model(FakeMessaging(), f)
        m.open("one"); runCurrent(); m.draft("First draft")
        m.open("two"); runCurrent(); assertEquals("", m.state.value.draft); m.draft("Second draft")
        m.open("one"); runCurrent(); assertEquals("First draft", m.state.value.draft)
        m.back(); m.open("two"); runCurrent(); assertEquals("Second draft", m.state.value.draft)
        f.state.value = f.state.value.copy(session = null); runCurrent()
        assertTrue(m.state.value.drafts.isEmpty())
    }
    @Test fun obsoleteSearchAndContextCannotReplaceAnotherChat() = runTest(dispatcher) {
        val search = CompletableDeferred<ChatPage<ChatMessage>>(); val context = CompletableDeferred<ChatPage<ChatMessage>>()
        val r = FakeMessaging().apply { searchResult = { _, _, _ -> withContext(NonCancellable) { search.await() } }; contextResult = { _, _ -> withContext(NonCancellable) { context.await() } } }
        val m = model(r); m.open("room"); runCurrent(); m.searchMessages("secret"); advanceTimeBy(301); runCurrent(); m.focusMessage("old"); runCurrent()
        m.open("two"); runCurrent(); search.complete(ChatPage(listOf(message("secret")))); context.complete(ChatPage(listOf(message("old")))); runCurrent()
        assertEquals("two", m.state.value.selectedId); assertNull(m.state.value.searchResults.data)
        assertNull(m.state.value.focusedMessageId); assertFalse(m.state.value.messages.data.orEmpty().any { it.id == "secret" || it.id == "old" })
    }
    @Test fun pinIsAppliedOnlyAfterServerAcknowledgesAndContextDoesNotAdvanceReadCursor() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>(); val r = FakeMessaging().apply { pinResult = { gate.await() } }
        val m = model(r); m.foreground(true); m.workspaceVisible(true); m.open("room"); runCurrent()
        val message = m.state.value.messages.data!!.single(); m.pin(message, true); runCurrent()
        assertFalse(m.state.value.messages.data!!.single().pinned)
        gate.complete(Unit); runCurrent(); assertTrue(m.state.value.messages.data!!.single().pinned)
        m.focusMessage("old"); runCurrent(); val reads = r.reads.size; m.markRead(); runCurrent(); assertEquals(reads, r.reads.size)
        m.suspendWork()
    }
    @Test fun folderFilterChangesResetPagingAndUseServerFilter() = runTest(dispatcher) {
        val r = FakeMessaging(); val m = model(r); m.selectFolder("folder"); advanceTimeBy(251); runCurrent()
        m.folderFilter("conference"); advanceTimeBy(251); runCurrent()
        assertEquals("conference", r.folderQueries.last().second.type); assertNull(r.folderQueries.last().second.cursor)
    }

    @Test fun lateResponseFromPreviousAccountCannotRepopulateHistory() = runTest(dispatcher) {
        val gate = CompletableDeferred<MessagePage>()
        val foundation = FakeFoundation()
        val repository = FakeMessaging().apply { messageResult = { _, _ -> withContext(NonCancellable) { gate.await() } } }
        val model = model(repository, foundation)
        model.open("old"); runCurrent()
        foundation.state.value = foundation.state.value.copy(session = AccountSession(User("other", "other@example.test"), Capabilities(), true))
        runCurrent()
        gate.complete(MessagePage(listOf(message("private", "old")))); runCurrent()
        assertEquals("other", model.state.value.selfId)
        assertNull(model.state.value.selectedId)
        assertNull(model.state.value.messages.data)
        assertNull(model.state.value.error)
    }

    @Test fun sameAccountDifferentOriginDiscardsDraftAndBoundedCache() = runTest(dispatcher) {
        val foundation = FakeFoundation()
        val repository = FakeMessaging()
        val model = model(repository, foundation)
        model.open("room"); runCurrent(); model.draft("Private draft"); model.reply(message("reply"))
        foundation.state.value = foundation.state.value.copy(profiles = listOf(ServerProfile("profile", "https://another.example.test")))
        runCurrent()
        assertEquals("", model.state.value.draft)
        assertNull(model.state.value.replyTo)
        assertNull(model.state.value.messages.data)
        repository.messageResult = { _, _ -> throw network() }
        model.open("room"); runCurrent()
        assertNull(model.state.value.messages.data)
    }

    @Test fun ambiguousSendRetriesSameIdentityAndPayloadWhileDoubleTapIsIgnored() = runTest(dispatcher) {
        val gate = CompletableDeferred<ChatMessage>()
        val repository = FakeMessaging().apply { sendResult = { _, _ -> gate.await() } }
        val model = model(repository)
        model.open("room"); runCurrent(); model.draft("Original"); model.send(); model.send(); runCurrent()
        assertEquals(1, repository.sent.size)
        assertTrue(model.state.value.sending)
        gate.completeExceptionally(network()); runCurrent()
        assertTrue(model.state.value.pendingSend)
        model.draft("Must not replace uncertain payload"); model.reply(message("different")); model.cancelCompose()
        assertEquals("Original", model.state.value.draft)
        assertNull(model.state.value.replyTo)
        repository.sendResult = { id, input -> message("accepted", id).copy(text = input.text, clientRequestId = input.clientRequestId) }
        model.send(); runCurrent()
        assertEquals(2, repository.sent.size)
        assertEquals(repository.sent[0].second, repository.sent[1].second)
        assertFalse(model.state.value.pendingSend)
        assertEquals("", model.state.value.draft)
        assertEquals(1, model.state.value.messages.data!!.count { it.id == "accepted" })
    }

    @Test fun validationRejectionUnlocksDraftAndCorrectedMessageGetsNewIdentity() = runTest(dispatcher) {
        val repository = FakeMessaging().apply { sendResult = { _, _ -> throw AppFailure(FailureKind.VALIDATION, "Исправьте текст.") } }
        val model = model(repository)
        model.open("room"); runCurrent(); model.draft("Rejected"); model.send(); runCurrent()
        assertFalse(model.state.value.pendingSend)
        model.draft("Corrected")
        assertEquals("Corrected", model.state.value.draft)
        repository.sendResult = { id, input -> message("accepted", id).copy(text = input.text, clientRequestId = input.clientRequestId) }
        model.send(); runCurrent()
        assertEquals("Corrected", repository.sent.last().second.text)
        assertNotEquals(repository.sent.first().second.clientRequestId, repository.sent.last().second.clientRequestId)
    }

    @Test fun refreshReconcilesUnknownSendByClientRequestIdWithoutSecondPost() = runTest(dispatcher) {
        val repository = FakeMessaging().apply { sendResult = { _, _ -> throw network() } }
        val model = model(repository)
        model.open("room"); runCurrent(); model.draft("Already accepted"); model.send(); runCurrent()
        val input = repository.sent.single().second
        repository.messageResult = { id, _ -> MessagePage(listOf(message("accepted", id).copy(clientRequestId = input.clientRequestId, text = input.text))) }
        model.actions.refreshMessages(); runCurrent()
        assertFalse(model.state.value.pendingSend)
        assertEquals("", model.state.value.draft)
        assertEquals(1, repository.sent.size)
    }

    @Test fun replyAndOwnEditCarryCorrectScopeAndOtherAuthorsCannotBeEdited() = runTest(dispatcher) {
        val repository = FakeMessaging()
        val model = model(repository)
        model.open("room"); runCurrent()
        val original = message("reply-source").copy(senderId = "peer", senderName = "Peer")
        model.reply(original); model.draft("Reply"); model.send(); runCurrent()
        assertEquals("room", repository.sent.single().first)
        assertEquals(original.id, repository.sent.single().second.replyTo)
        model.edit(original)
        assertNull(model.state.value.editing)
        val own = message("own")
        model.edit(own); model.draft("Edited"); model.send(); runCurrent()
        assertEquals(Triple("room", own.id, "Edited"), repository.edited.single())
        assertNull(model.state.value.editing)
    }

    @Test fun paginationDeduplicatesMessagesUsingNewestVersionAndRejectsRepeatedCursor() = runTest(dispatcher) {
        val newest = message("same", sequence = 10).copy(version = 3, text = "New")
        val repository = FakeMessaging().apply { messageResult = { _, cursor ->
            if (cursor == null) MessagePage(listOf(newest), "next")
            else MessagePage(listOf(newest.copy(version = 1, text = "Stale"), message("older", sequence = 1)), "next")
        } }
        val model = model(repository)
        model.open("room"); runCurrent(); model.actions.moreMessages(); runCurrent()
        assertNotNull(model.state.value.messages.error)
        assertEquals(listOf(newest), model.state.value.messages.data)
        repository.messageResult = { _, _ -> MessagePage(listOf(newest.copy(version = 1, text = "Stale"), message("older", sequence = 1))) }
        model.actions.moreMessages(); runCurrent()
        assertEquals(2, model.state.value.messages.data!!.size)
        assertEquals("New", model.state.value.messages.data!!.single { it.id == newest.id }.text)
        assertNull(model.state.value.messageCursor)
    }

    @Test fun olderPaginationMovesPastTwoHundredMessagesWithoutGrowingMemoryOrLosingCursor() = runTest(dispatcher) {
        val requests = mutableListOf<String?>()
        val repository = FakeMessaging().apply { messageResult = { _, cursor ->
            requests += cursor
            val upper = cursor?.toInt() ?: 350
            val lower = (upper - 49).coerceAtLeast(1)
            val page = (lower..upper).map { message("message-$it", sequence = it.toLong()).copy(version = 3) }
            val staleOverlap = if (cursor == null) emptyList() else
                listOf(message("message-${upper + 1}", sequence = (upper + 1).toLong()).copy(version = 1))
            MessagePage(page + staleOverlap, (lower - 1).takeIf { it > 0 }?.toString())
        } }
        val model = model(repository)
        model.foreground(true); model.workspaceVisible(true)
        model.open("room"); runCurrent()
        assertEquals("300", model.state.value.messageCursor)
        for (lower in listOf(251, 201, 151, 101, 51, 1)) {
            model.actions.moreMessages(); runCurrent()
            val window = model.state.value.messages.data!!
            assertEquals(minOf(200, 351 - lower), window.size)
            assertEquals(lower.toLong(), window.first().sequence)
            assertEquals(minOf(350, lower + 199).toLong(), window.last().sequence)
            assertTrue(window.all { it.version == 3L })
            assertEquals((lower - 1).takeIf { it > 0 }?.toString(), model.state.value.messageCursor)
        }
        assertEquals(listOf(null, "300", "250", "200", "150", "100", "50"), requests)
        // Reading older pages must not send older read markers and trigger a WSS
        // reconciliation which would replace the window the user is browsing.
        assertEquals(listOf("room" to "message-350"), repository.reads)
        // A fresh REST request returns the latest page, rather than trapping the user
        // in the older bounded window or keeping the older page's exhausted cursor.
        model.actions.refreshMessages(); runCurrent()
        assertEquals((301L..350L).toList(), model.state.value.messages.data!!.map { it.sequence })
        assertEquals("300", model.state.value.messageCursor)
        assertNull(model.state.value.messages.error)
        model.suspendWork()
    }

    @Test fun historyResponseStartedBeforeSendAckCannotEraseAcceptedMessage() = runTest(dispatcher) {
        val gate = CompletableDeferred<MessagePage>()
        val repository = FakeMessaging()
        val model = model(repository)
        model.open("room"); runCurrent()
        val beforeSend = model.state.value.messages.data.orEmpty()
        repository.messageResult = { _, _ -> withContext(NonCancellable) { gate.await() } }
        model.actions.refreshMessages(); runCurrent()
        model.draft("Accepted while history was loading"); model.send(); runCurrent()
        assertEquals("Accepted while history was loading", model.state.value.messages.data!!.single { it.id == "sent" }.text)
        gate.complete(MessagePage(beforeSend)); runCurrent()
        assertEquals("Accepted while history was loading", model.state.value.messages.data!!.single { it.id == "sent" }.text)
        assertFalse(model.state.value.messages.loading)
        assertNull(model.state.value.messages.error)
    }

    @Test fun lateHistoryFailureCannotRollbackAcknowledgedEdit() = runTest(dispatcher) {
        val gate = CompletableDeferred<MessagePage>()
        val repository = FakeMessaging()
        val model = model(repository)
        model.open("room"); runCurrent()
        val original = model.state.value.messages.data!!.single()
        repository.messageResult = { _, _ -> withContext(NonCancellable) { gate.await() } }
        model.actions.refreshMessages(); runCurrent()
        model.edit(original); model.draft("Acknowledged edit"); model.send(); runCurrent()
        assertEquals(2L, model.state.value.messages.data!!.single().version)
        gate.completeExceptionally(network()); runCurrent()
        assertEquals("Acknowledged edit", model.state.value.messages.data!!.single().text)
        assertEquals(2L, model.state.value.messages.data!!.single().version)
        assertFalse(model.state.value.messages.loading)
        assertNull(model.state.value.messages.error)
    }

    @Test fun staleRefreshCannotDowngradeAnObservedMessageVersion() = runTest(dispatcher) {
        val newest = message("same").copy(version = 3, text = "Corrected text")
        val repository = FakeMessaging().apply { messageResult = { _, _ -> MessagePage(listOf(newest)) } }
        val model = model(repository)
        model.open("room"); runCurrent()
        repository.messageResult = { _, _ -> MessagePage(listOf(newest.copy(version = 1, text = "Old text"))) }
        model.actions.refreshMessages(); runCurrent()
        assertEquals(3L, model.state.value.messages.data!!.single().version)
        assertEquals("Corrected text", model.state.value.messages.data!!.single().text)
    }

    @Test fun deletionReplacesTextWithServerTombstoneAndRefreshesReplies() = runTest(dispatcher) {
        val original = message("removed")
        val tombstone = original.copy(version = 2, text = "", deletedAt = at)
        val reply = message("reply", sequence = 2).copy(replyTo = original.id, replyPreview = ChatReply(original.id, "", "Member", true))
        val repository = FakeMessaging().apply {
            messageResult = { _, _ -> MessagePage(listOf(original)) }
            deleteResult = { _, _ -> messageResult = { _, _ -> MessagePage(listOf(tombstone, reply)) }; tombstone }
        }
        val model = model(repository)
        model.open("room"); runCurrent(); model.delete(original); runCurrent()
        assertEquals(listOf("room" to original.id), repository.deleted)
        assertEquals(tombstone, model.state.value.messages.data!!.single { it.id == original.id })
        assertTrue(model.state.value.messages.data!!.single { it.id == reply.id }.replyPreview!!.deleted)
    }

    @Test fun loadedHistoryRemainsInRamOfflineUntilLogoutThenIsGone() = runTest(dispatcher) {
        val foundation = FakeFoundation()
        val repository = FakeMessaging()
        val model = model(repository, foundation)
        model.open("room"); runCurrent(); model.back(); model.open("other"); runCurrent()
        repository.messageResult = { _, _ -> throw network() }
        model.open("room"); runCurrent()
        assertEquals("room-message", model.state.value.messages.data!!.single().id)
        assertNotNull(model.state.value.messages.error)
        foundation.state.value = foundation.state.value.copy(session = null); runCurrent()
        assertNull(model.state.value.messages.data)
        assertNull(model.state.value.selectedId)
        val before = repository.sent.size
        model.draft("Must not send"); model.send(); runCurrent()
        assertEquals(before, repository.sent.size)
    }

    @Test fun forbiddenRefreshEvictsHistoryAndDoesNotReuseItOnReopen() = runTest(dispatcher) {
        val repository = FakeMessaging()
        val model = model(repository)
        model.open("room"); runCurrent()
        repository.messageResult = { _, _ -> throw AppFailure(FailureKind.FORBIDDEN, "Доступ закрыт.") }
        model.actions.refreshMessages(); runCurrent()
        assertNull(model.state.value.selectedId)
        assertNull(model.state.value.messages.data)
        repository.messageResult = { _, _ -> throw network() }
        model.open("room"); runCurrent()
        assertNull(model.state.value.messages.data)
    }

    @Test fun lateGroupMutationCannotReplaceAnotherSelectedConversation() = runTest(dispatcher) {
        val gate = CompletableDeferred<ChatConversation>()
        val repository = FakeMessaging().apply { groupChangeResult = { id, _, _ -> withContext(NonCancellable) { gate.await() } } }
        val model = model(repository)
        model.open("old"); runCurrent(); model.editGroup("Renamed", ""); runCurrent()
        model.open("new"); runCurrent()
        gate.complete(conversation("old").copy(name = "Renamed")); runCurrent()
        assertEquals("new", model.state.value.selectedId)
        assertEquals("new", model.state.value.detail.data!!.id)
        assertFalse(model.state.value.busy)
    }

    @Test fun lateSendResponseCannotInsertOldChatMessagesAfterSwitch() = runTest(dispatcher) {
        val gate = CompletableDeferred<ChatMessage>()
        val repository = FakeMessaging().apply { sendResult = { _, _ -> withContext(NonCancellable) { gate.await() } } }
        val model = model(repository)
        model.open("old"); runCurrent(); model.draft("Old draft"); model.send(); runCurrent()
        model.open("new"); runCurrent(); gate.complete(message("late", "old")); runCurrent()
        assertEquals("new", model.state.value.selectedId)
        assertTrue(model.state.value.messages.data!!.all { it.conversationId == "new" })
        assertFalse(model.state.value.sending)
    }

    @Test fun websocketReconnectUsesNewSessionAndDeduplicatesInvalidations() = runTest(dispatcher) {
        val first = FakeEvents(); val second = FakeEvents()
        val repository = FakeMessaging().apply { eventsResult = { if (eventOpens == 1) first else second } }
        val model = model(repository)
        model.foreground(true); runCurrent(); first.channel.send(UserEvent("connected1", "connected")); runCurrent()
        val baseline = repository.conversationQueries.size
        repeat(3) { first.channel.send(UserEvent("same-event", "conversation.message.created", conversationId = "room")) }
        runCurrent(); advanceTimeBy(501); runCurrent()
        assertEquals(baseline + 1, repository.conversationQueries.size)
        first.channel.close(UserEventsDisconnected(true)); runCurrent(); advanceTimeBy(2001); runCurrent()
        assertEquals(2, repository.eventOpens)
        second.channel.send(UserEvent("connected2", "connected")); runCurrent()
        assertEquals("Обновления подключены", model.state.value.realtimeLabel)
        model.foreground(false); runCurrent()
        assertTrue(first.closed)
        assertTrue(second.closed)
    }

    @Test fun lateWebsocketOpenAfterLogoutClosesReturnedSession() = runTest(dispatcher) {
        val gate = CompletableDeferred<UserEvents>()
        val events = FakeEvents()
        val foundation = FakeFoundation()
        val repository = FakeMessaging().apply { eventsResult = { withContext(NonCancellable) { gate.await() } } }
        val model = model(repository, foundation)
        model.foreground(true); runCurrent()
        foundation.state.value = foundation.state.value.copy(session = null); runCurrent()
        gate.complete(events); runCurrent()
        assertTrue(events.closed)
        assertNull(model.state.value.messages.data)
    }

    @Test fun readAcknowledgmentsDoNotCreateRealtimeRefreshLoop() = runTest(dispatcher) {
        val events = FakeEvents()
        val repository = FakeMessaging().apply {
            eventsResult = { events }
            readResult = { _, id -> events.channel.send(UserEvent("read-${reads.size}", "conversation.read.updated", conversationId = "room")); ChatReadState(0, id) }
        }
        val model = model(repository)
        model.workspaceVisible(true); model.foreground(true); runCurrent(); model.open("room"); runCurrent()
        assertEquals(1, repository.reads.size)
        repeat(5) { advanceTimeBy(1000); runCurrent() }
        assertEquals(1, repository.reads.size)
        assertEquals(0L, model.state.value.detail.data!!.unreadCount)
        model.foreground(false); runCurrent()
    }

    @Test fun readingAndNotificationReadNeverRunAsHiddenScreenSideEffects() = runTest(dispatcher) {
        val repository = FakeMessaging()
        val model = model(repository)
        model.open("room"); runCurrent(); model.markRead(); runCurrent()
        assertTrue(repository.reads.isEmpty())
        model.loadNotifications(); runCurrent()
        assertTrue(repository.notificationReads.isEmpty())
        model.readAllNotifications(); runCurrent()
        assertEquals(listOf("notice"), repository.notificationReads)
    }

    @Test fun folderSearchAndGlobalFiltersDoNotRemainStuckInFolder() = runTest(dispatcher) {
        val repository = FakeMessaging()
        val model = model(repository)
        model.selectFolder("folder"); runCurrent(); model.query("planning"); advanceTimeBy(301); runCurrent()
        assertEquals("planning", repository.folderQueries.last().second.search)
        model.filter(ChatFilter.DIRECT); runCurrent()
        assertNull(model.state.value.selectedFolderId)
        assertEquals("direct", repository.conversationQueries.last().type)
    }

    @Test fun uploadedAndDownloadedTemporaryBytesAreWipedEvenOnFailure() = runTest(dispatcher) {
        val repository = FakeMessaging()
        val model = model(repository)
        model.open("room"); runCurrent()
        val bytes = byteArrayOf(1, 2, 3)
        model.upload("file.txt", "text/plain") { bytes }; runCurrent()
        assertTrue(bytes.all { it == 0.toByte() })
        assertEquals(1, model.state.value.attachments.size)
        val download = byteArrayOf(4, 5, 6)
        repository.downloadResult = { _, _ -> download }
        model.export(attachment()) { throw AppFailure(FailureKind.STORAGE, "Запись не выполнена.") }; runCurrent()
        assertTrue(download.all { it == 0.toByte() })
        assertFalse(model.state.value.attachmentBusy)
        assertEquals("Запись не выполнена.", model.state.value.error)
    }

    @Test fun unknownGroupCreateReusesIdOnlyForSameIntent() = runTest(dispatcher) {
        val repository = FakeMessaging().apply { groupCreateResult = { _, _, _, _ -> throw network() } }
        val model = model(repository)
        model.createGroup("Team", "Description", listOf("b", "a")); runCurrent()
        model.createGroup("Team", "Description", listOf("a", "b")); runCurrent()
        assertEquals(repository.groupCreates[0].last(), repository.groupCreates[1].last())
        model.createGroup("Other", "Description", listOf("a", "b")); runCurrent()
        assertNotEquals(repository.groupCreates[1].last(), repository.groupCreates[2].last())
    }

    private fun model(repository: FakeMessaging, foundation: FakeFoundation = FakeFoundation()) = MessagingViewModel(foundation, repository)
    private class FakeEvents : UserEvents {
        val channel = Channel<UserEvent>(Channel.UNLIMITED)
        override val events: ReceiveChannel<UserEvent> get() = channel
        var closed = false
        override fun close() { closed = true; channel.close() }
    }
    private class FakeMessaging : MessagingRepository {
        val conversationQueries = mutableListOf<ChatQuery>()
        val folderQueries = mutableListOf<Pair<String?, FolderQuery>>()
        val sent = mutableListOf<Pair<String, ChatMessageInput>>()
        val edited = mutableListOf<Triple<String, String, String>>()
        val deleted = mutableListOf<Pair<String, String>>()
        val reads = mutableListOf<Pair<String, String>>()
        val notificationReads = mutableListOf<String>()
        val groupCreates = mutableListOf<List<String>>()
        var searchResult: suspend (String, String, String?) -> ChatPage<ChatMessage> = { id, _, _ -> ChatPage(listOf(message("result", id))) }
        var contextResult: suspend (String, String) -> ChatPage<ChatMessage> = { id, mid -> ChatPage(listOf(message(mid, id))) }
        var pinResult: suspend () -> Unit = {}
        override suspend fun searchMessages(id: String, query: String, cursor: String?) = searchResult(id, query, cursor)
        override suspend fun messageContext(id: String, messageId: String) = contextResult(id, messageId)
        override suspend fun messagePins(id: String, cursor: String?) = ChatPage<ChatMessage>(emptyList())
        override suspend fun pinMessage(id: String, messageId: String, pinned: Boolean) = pinResult()
        var eventOpens = 0
        var messageResult: suspend (String, String?) -> MessagePage = { id, _ -> MessagePage(listOf(message("$id-message", id))) }
        var sendResult: suspend (String, ChatMessageInput) -> ChatMessage = { id, input -> message("sent", id).copy(text = input.text, clientRequestId = input.clientRequestId, replyTo = input.replyTo) }
        var deleteResult: suspend (String, String) -> ChatMessage = { id, messageId -> message(messageId, id).copy(version = 2, text = "", deletedAt = at) }
        var groupChangeResult: suspend (String, String, String) -> ChatConversation = { id, name, description -> conversation(id).copy(name = name, description = description) }
        var groupCreateResult: suspend (String, String, List<String>, String) -> ChatConversation = { name, _, _, _ -> conversation("created").copy(name = name) }
        var eventsResult: suspend () -> UserEvents = { FakeEvents() }
        var readResult: suspend (String, String) -> ChatReadState = { _, id -> ChatReadState(0, id) }
        var downloadResult: suspend (String, ChatAttachment) -> ByteArray = { _, _ -> byteArrayOf(1) }
        override suspend fun conversations(query: ChatQuery): ChatPage<ChatConversation> { conversationQueries += query; return ChatPage(listOf(conversation("room"))) }
        override suspend fun conversation(id: String) = Companion.conversation(id)
        override suspend fun searchPeers(search: String) = listOf(ChatPeer("peer", "Peer"))
        override suspend fun createDirect(peerId: String) = conversation("direct").copy(type = "direct", peer = ChatPeer(peerId, "Peer"))
        override suspend fun createGroup(name: String, description: String, memberIds: List<String>, clientRequestId: String): ChatConversation {
            groupCreates += listOf(name, description, memberIds.joinToString(), clientRequestId); return groupCreateResult(name, description, memberIds, clientRequestId)
        }
        override suspend fun updateGroup(id: String, name: String, description: String) = groupChangeResult(id, name, description)
        override suspend fun groupMembers(id: String) = listOf(ChatMember("self", "Member", "owner"))
        override suspend fun addGroupMembers(id: String, userIds: List<String>) = conversation(id)
        override suspend fun removeGroupMember(id: String, userId: String) = conversation(id)
        override suspend fun changeGroupRole(id: String, userId: String, role: String) = conversation(id)
        override suspend fun transferGroupOwnership(id: String, userId: String) = conversation(id)
        override suspend fun leaveGroup(id: String) = Unit
        override suspend fun deleteGroup(id: String) = Unit
        override suspend fun setConversationNotifications(id: String, enabled: Boolean) = conversation(id).copy(notificationsEnabled = enabled)
        override suspend fun messages(id: String, cursor: String?) = messageResult(id, cursor)
        override suspend fun sendMessage(id: String, input: ChatMessageInput): ChatMessage { sent += id to input; return sendResult(id, input) }
        override suspend fun editMessage(id: String, messageId: String, text: String): ChatMessage { edited += Triple(id, messageId, text); return message(messageId, id).copy(text = text, version = 2) }
        override suspend fun deleteMessage(id: String, messageId: String): ChatMessage { deleted += id to messageId; return deleteResult(id, messageId) }
        override suspend fun markConversationRead(id: String, messageId: String): ChatReadState { reads += id to messageId; return readResult(id, messageId) }
        override suspend fun uploadAttachment(id: String, filename: String, mimeType: String, bytes: ByteArray, clientRequestId: String) = attachment(id).copy(filename = filename, mimeType = mimeType, size = bytes.size.toLong())
        override suspend fun downloadAttachment(id: String, attachment: ChatAttachment) = downloadResult(id, attachment)
        override suspend fun folders() = listOf(ChatFolder("folder", "Folder"))
        override suspend fun createFolder(name: String) = ChatFolder("folder", name)
        override suspend fun renameFolder(id: String, name: String) = ChatFolder(id, name)
        override suspend fun deleteFolder(id: String) = Unit
        override suspend fun reorderFolders(ids: List<String>) = ids.mapIndexed { i, id -> ChatFolder(id, id, i) }
        override suspend fun folderItems(folderId: String?, query: FolderQuery): ChatPage<FolderEntry> { folderQueries += folderId to query; return ChatPage(emptyList()) }
        override suspend fun setFolderItem(folderId: String, kind: String, itemId: String, included: Boolean) = ChatFolder(folderId, "Folder")
        override suspend fun peerPresence(id: String) = false
        override suspend fun notifications(cursor: String?) = NotificationPage(listOf(InboxNotification("notice", "conference.reminder", at)), unreadCount = 1)
        override suspend fun readNotification(id: String): InboxNotification { notificationReads += id; return InboxNotification(id, "conference.reminder", at, readAt = at) }
        override suspend fun notificationPreferences() = NotificationPreferences()
        override suspend fun saveNotificationPreferences(preferences: NotificationPreferences) = preferences
        override suspend fun integrationCapabilities() = IntegrationCapabilities("noop")
        override suspend fun registerPushDevice(token: String) = PushDevice("device", "android", "fcm")
        override suspend fun revokePushDevice(id: String) = Unit
        override suspend fun openUserEvents(): UserEvents { eventOpens++; return eventsResult() }
    }
    private class FakeFoundation : FoundationRepository {
        override val state = MutableStateFlow(RepositoryState(profiles = listOf(ServerProfile("profile", "https://fixture.example.test")), selectedProfileId = "profile", session = AccountSession(User("self", "member@example.test"), Capabilities(), true)))
        override suspend fun restore() = Unit
        override suspend fun addServer(rawUrl: String) = state.value.selectedProfile!!
        override suspend fun selectServer(profileId: String) = Unit
        override suspend fun removeServer(profileId: String) = Unit
        override suspend fun login(email: String, password: String) = Unit
        override suspend fun logout() = LogoutResult(true)
        override suspend fun setTheme(mode: ThemeMode) = Unit
    }
    companion object {
        private val at = Instant.parse("2026-10-11T12:00:00Z")
        private fun conversation(id: String) = ChatConversation(id, "group", name = "Group $id", myRole = "owner", memberCount = 2, unreadCount = 1)
        private fun message(id: String, conversationId: String = "room", sequence: Long = 1) = ChatMessage(id, conversationId, "self", "Member", "request-$id", "Text $id", sequence, 1, at, at)
        private fun attachment(id: String = "room") = ChatAttachment("attachment", id, "file.txt", "text/plain", 3, "uploaded")
        private fun network() = AppFailure(FailureKind.NETWORK, "Нет подключения.")
    }
}
