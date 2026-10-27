package com.meetspace.app

import androidx.lifecycle.ViewModelStore
import com.meetspace.app.ui.ChatFilter
import com.meetspace.app.ui.NotificationFilter
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

    @Test fun attachmentExportCancelledBeforeItsBodyStartsStillCleansTheNewDocument() = runTest(dispatcher) {
        for (clearViewModel in listOf(false, true)) {
            val repository = FakeMessaging()
            val model = model(repository)
            val store = ViewModelStore().apply { put("messaging", model) }
            model.open("room"); runCurrent()
            var downloads = 0; var writes = 0; var cleanups = 0
            repository.downloadResult = { _, _ -> downloads++; byteArrayOf(1) }
            assertTrue(model.export(attachment(), onFailure = { cleanups++ }) { writes++ })
            if (clearViewModel) store.clear() else model.back()
            runCurrent()
            assertEquals(0, downloads); assertEquals(0, writes); assertEquals(1, cleanups)
            store.clear(); runCurrent()
            assertEquals(1, cleanups)
        }
    }

    @Test fun successfulAttachmentExportKeepsTheDocumentAndFailureCleansExactlyOnce() = runTest(dispatcher) {
        val repository = FakeMessaging()
        val model = model(repository)
        model.open("room"); runCurrent()
        var writes = 0; var cleanups = 0
        assertTrue(model.export(attachment(), onFailure = { cleanups++ }) { writes++ })
        runCurrent(); model.back(); runCurrent()
        assertEquals(1, writes); assertEquals(0, cleanups)
        model.open("room"); runCurrent()
        repository.downloadResult = { _, _ -> throw network() }
        assertTrue(model.export(attachment(), onFailure = { cleanups++ }) { writes++ })
        runCurrent(); model.back(); runCurrent()
        assertEquals(1, writes); assertEquals(1, cleanups)
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
    @Test fun optionalChatResourcesRevalidateCanonicalAccessInsteadOfErasingAnAccessibleChat() = runTest(dispatcher) {
        for (resource in listOf("pins", "search", "context", "pin", "download", "upload")) {
            for (kind in listOf(FailureKind.NOT_FOUND, FailureKind.FORBIDDEN)) {
                val repository = FakeMessaging()
                val model = model(repository)
                model.open("room"); runCurrent(); model.draft("Keep my draft")
                val before = repository.conversationRequests.size
                failResource(repository, resource, AppFailure(kind, "Resource unavailable"))
                requestResource(model, resource); advanceTimeBy(301); runCurrent()
                assertEquals("$resource must recheck canonical access", before + 1, repository.conversationRequests.size)
                assertEquals("$resource must keep an authorized chat selected", "room", model.state.value.selectedId)
                assertEquals("Keep my draft", model.state.value.draft)
                assertTrue(model.state.value.messages.data.orEmpty().any { it.id == "room-message" })
                assertFalse(model.state.value.busy)
                assertFalse(model.state.value.attachmentBusy)
                if (resource == "context") assertFalse(model.state.value.viewingContext)
                if (resource in listOf("pins", "pin")) assertNotNull(model.state.value.pins.error)
                if (resource == "search") assertNotNull(model.state.value.searchResults.error)
                model.suspendWork()
            }
        }
    }
    @Test fun optionalResourceFailureStillClearsPrivateHistoryWhenCanonicalAccessWasRevoked() = runTest(dispatcher) {
        for (resource in listOf("pins", "search", "context", "pin", "download", "upload")) {
            for (canonicalKind in listOf(FailureKind.NOT_FOUND, FailureKind.FORBIDDEN)) {
                val repository = FakeMessaging()
                val model = model(repository)
                model.open("room"); runCurrent(); model.draft("Revoked private draft")
                failResource(repository, resource, AppFailure(FailureKind.NOT_FOUND, "Resource unavailable"))
                repository.conversationResult = { throw AppFailure(canonicalKind, "Canonical access denied") }
                requestResource(model, resource); advanceTimeBy(301); runCurrent()
                assertNull("$resource must honor canonical denial", model.state.value.selectedId)
                assertNull(model.state.value.messages.data)
                assertNull(model.state.value.detail.data)
                assertTrue(model.state.value.draft.isEmpty())
                assertFalse(model.state.value.drafts.containsKey("room"))
                assertTrue(model.state.value.conversations.data.orEmpty().none { it.id == "room" })
                model.suspendWork()
            }
        }
    }
    @Test fun missingPinsOnInitialOpenDoesNotCloseTheChatOnAnOlderBackend() = runTest(dispatcher) {
        val repository = FakeMessaging().apply { pinsResult = { _, _ -> throw AppFailure(FailureKind.NOT_FOUND, "Optional endpoint absent") } }
        val model = model(repository)
        model.open("room"); model.draft("Draft during load"); runCurrent()
        assertEquals(listOf("room", "room"), repository.conversationRequests)
        assertEquals("room", model.state.value.selectedId)
        assertEquals("Draft during load", model.state.value.draft)
        assertNotNull(model.state.value.detail.data)
        assertTrue(model.state.value.messages.data.orEmpty().any { it.id == "room-message" })
        assertNotNull(model.state.value.pins.error)
        model.suspendWork()
    }
    @Test fun transientOptionalResourceFailuresKeepLoadedProjectionsAndDoNotRecheckAuthorization() = runTest(dispatcher) {
        for (resource in listOf("pins", "search", "context", "pin", "download", "upload")) {
            val repository = FakeMessaging().apply { pinsResult = { id, _ -> ChatPage(listOf(message("pinned", id))) } }
            val model = model(repository)
            model.open("room"); runCurrent()
            if (resource == "search") {
                repository.searchResult = { id, _, _ -> ChatPage(listOf(message("result", id)), "older") }
                model.searchMessages("find"); advanceTimeBy(301); runCurrent()
            }
            if (resource == "context") { model.focusMessage("older"); runCurrent() }
            val before = model.state.value
            val canonicalRequests = repository.conversationRequests.size
            failResource(repository, resource, network())
            if (resource == "search") model.searchMessages("find", append = true) else requestResource(model, resource)
            advanceTimeBy(301); runCurrent()
            assertEquals(canonicalRequests, repository.conversationRequests.size)
            assertEquals("room", model.state.value.selectedId)
            assertEquals(before.messages.data, model.state.value.messages.data)
            if (resource == "pins") assertEquals(before.pins.data, model.state.value.pins.data)
            if (resource == "search") assertEquals(before.searchResults.data, model.state.value.searchResults.data)
            assertFalse(model.state.value.busy)
            assertFalse(model.state.value.attachmentBusy)
            model.suspendWork()
        }
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

    @Test fun realtimeNotificationBurstBeyondFirstPageDoesNotDelayTheInboxOrLoseAlerts() = runTest(dispatcher) {
        val events = FakeEvents()
        val pendingDelivery = CompletableDeferred<NotificationPage>()
        val firstPage = NotificationPage((1..30).map { notice("notice-$it") }, "older", 31)
        var requests = 0
        val repository = FakeMessaging().apply {
            eventsResult = { events }
            notificationsResult = { cursor ->
                if (++requests == 1) pendingDelivery.await()
                else if (cursor == null) firstPage else NotificationPage(listOf(notice("notice-0")), unreadCount = 31)
            }
        }
        val model = model(repository)
        val delivered = mutableListOf<String>()
        model.onNotification { delivered += it.id }; model.foreground(true); runCurrent()
        repeat(31) { events.channel.send(UserEvent("event-$it", "notification.created", notificationId = "notice-$it")) }
        runCurrent()
        assertEquals(30, model.state.value.notifications.data!!.size)
        assertEquals("older", model.state.value.notificationCursor)
        assertTrue(delivered.isEmpty())
        pendingDelivery.complete(firstPage); runCurrent()
        assertEquals((0..30).map { "notice-$it" }.toSet(), delivered.toSet())
        assertEquals(31, delivered.size)
        assertTrue("The older event is resolved independently of inbox pagination", "older" in repository.notificationQueries)
        assertEquals(30, model.state.value.notifications.data!!.size)
        model.foreground(false); runCurrent()
    }

    @Test fun loadingUnreadHistoryAndReconnectingDoNotCreateNewNotificationAlerts() = runTest(dispatcher) {
        val events = FakeEvents()
        val repository = FakeMessaging().apply { eventsResult = { events } }
        val model = model(repository)
        val delivered = mutableListOf<String>()
        model.onNotification { delivered += it.id }; model.foreground(true); runCurrent()
        events.channel.send(UserEvent("connected", "connected")); runCurrent()
        model.loadNotifications(); runCurrent()
        model.notificationFilter(NotificationFilter.UNREAD); runCurrent()
        assertEquals(1L, model.state.value.notificationUnread)
        assertTrue(delivered.isEmpty())
        model.foreground(false); runCurrent()
    }

    @Test fun notificationDeliveryRetriesAfterRefreshAndDeduplicatesReplayedEvent() = runTest(dispatcher) {
        val events = FakeEvents()
        var failing = true
        val repository = FakeMessaging().apply {
            eventsResult = { events }
            notificationsResult = { if (failing) throw network() else NotificationPage(listOf(notice("notice")), unreadCount = 1) }
        }
        val model = model(repository)
        val delivered = mutableListOf<String>()
        model.onNotification { delivered += it.id }; model.foreground(true); runCurrent()
        val event = UserEvent("new-notice", "notification.created", notificationId = "notice")
        events.channel.send(event); runCurrent()
        assertTrue(delivered.isEmpty())
        failing = false; model.loadNotifications(); runCurrent()
        assertEquals(listOf("notice"), delivered)
        events.channel.send(event); runCurrent(); advanceTimeBy(501); runCurrent()
        assertEquals(listOf("notice"), delivered)
        assertTrue(repository.notificationReads.isEmpty())
        model.foreground(false); runCurrent()
    }

    @Test fun lateNotificationDeliveryAfterLogoutCannotAlertThePreviousAccount() = runTest(dispatcher) {
        val events = FakeEvents()
        val pendingDelivery = CompletableDeferred<NotificationPage>()
        val foundation = FakeFoundation()
        var requests = 0
        val repository = FakeMessaging().apply {
            eventsResult = { events }
            notificationsResult = {
                if (++requests == 1) withContext(NonCancellable) { pendingDelivery.await() }
                else NotificationPage(listOf(notice("notice")), unreadCount = 1)
            }
        }
        val model = model(repository, foundation)
        val delivered = mutableListOf<String>()
        model.onNotification { delivered += it.id }; model.foreground(true); runCurrent()
        events.channel.send(UserEvent("new-notice", "notification.created", notificationId = "notice")); runCurrent()
        foundation.state.value = foundation.state.value.copy(session = null); runCurrent()
        pendingDelivery.complete(NotificationPage(listOf(notice("notice")), unreadCount = 1)); runCurrent()
        assertTrue(delivered.isEmpty())
        assertNull(model.state.value.notifications.data)
        model.foreground(false); runCurrent()
    }

    @Test fun detachingNotificationListenerDuringFetchRetainsTheEventForTheNextListener() = runTest(dispatcher) {
        val events = FakeEvents()
        val pendingDelivery = CompletableDeferred<NotificationPage>()
        var requests = 0
        val page = NotificationPage(listOf(notice("notice")), unreadCount = 1)
        val repository = FakeMessaging().apply {
            eventsResult = { events }
            notificationsResult = {
                if (++requests == 1) withContext(NonCancellable) { pendingDelivery.await() } else page
            }
        }
        val model = model(repository)
        val oldListener = mutableListOf<String>()
        val newListener = mutableListOf<String>()
        model.onNotification { oldListener += it.id }; model.foreground(true); runCurrent()
        events.channel.send(UserEvent("new-notice", "notification.created", notificationId = "notice")); runCurrent()
        model.onNotification(null)
        model.onNotification { newListener += it.id }; runCurrent()
        assertTrue(oldListener.isEmpty())
        assertEquals(listOf("notice"), newListener)
        pendingDelivery.complete(page); runCurrent()
        assertTrue(oldListener.isEmpty())
        assertEquals(listOf("notice"), newListener)
        model.foreground(false); runCurrent()
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

    @Test fun unreadFilterSearchesPastReadPagesAndCanReturnToAllEvents() = runTest(dispatcher) {
        val repository = FakeMessaging().apply { notificationsResult = { cursor ->
            if (cursor == null) NotificationPage(listOf(notice("read").copy(readAt = at)), "older", 1)
            else NotificationPage(listOf(notice("unread")), unreadCount = 1)
        } }
        val model = model(repository)
        model.loadNotifications(); runCurrent()
        assertEquals(listOf("read"), model.state.value.notifications.data!!.map { it.id })
        model.notificationFilter(NotificationFilter.UNREAD); runCurrent()
        assertEquals(listOf("unread"), model.state.value.notifications.data!!.map { it.id })
        assertNull(model.state.value.notificationCursor)
        assertEquals(listOf(null, null, "older"), repository.notificationQueries)
        model.notificationFilter(NotificationFilter.ALL); runCurrent()
        assertEquals(listOf("read"), model.state.value.notifications.data!!.map { it.id })
        assertTrue(repository.notificationReads.isEmpty())
    }

    @Test fun unreadFilterPublishesThreeEventsWithoutWalkingLargeReadHistory() = runTest(dispatcher) {
        val repository = FakeMessaging().apply { notificationsResult = { cursor ->
            check(cursor == null) { "Older history must wait for an explicit load-more action" }
            NotificationPage(listOf(notice("first"), notice("second"), notice("third")) +
                (1..27).map { notice("read-$it").copy(readAt = at) }, "large-read-history", 3)
        } }
        val model = model(repository); model.notificationFilter(NotificationFilter.UNREAD); runCurrent()
        assertEquals(listOf(null), repository.notificationQueries)
        assertEquals(listOf("first", "second", "third"), model.state.value.notifications.data!!.map { it.id })
        assertEquals("large-read-history", model.state.value.notificationCursor)
        assertEquals(3L, model.state.value.notificationUnread)
        assertFalse(model.state.value.notifications.loading)
        assertNull(model.state.value.notifications.error)
        repository.notificationsResult = { cursor ->
            assertEquals("large-read-history", cursor)
            NotificationPage(listOf(notice("old-read").copy(readAt = at)), unreadCount = 3)
        }
        model.actions.moreNotifications(); runCurrent()
        assertEquals(listOf(null, "large-read-history"), repository.notificationQueries)
        assertEquals(3, model.state.value.notifications.data!!.size)
        assertNull(model.state.value.notificationCursor)
    }

    @Test fun readAllAcknowledgesEveryServerPageNotOnlyLoadedRows() = runTest(dispatcher) {
        val repository = FakeMessaging().apply { notificationsResult = { cursor ->
            val start = cursor?.toInt() ?: 0
            val ids = (start until (start + 30).coerceAtMost(61)).map { "notice-$it" }
            NotificationPage(ids.map { notice(it).copy(readAt = at.takeIf { _ -> it in acknowledgedNotifications }) },
                (start + 30).takeIf { it < 61 }?.toString(), 61L - acknowledgedNotifications.size)
        } }
        val model = model(repository)
        model.loadNotifications(); runCurrent()
        assertEquals(30, model.state.value.notifications.data!!.size)
        model.readAllNotifications(); model.readAllNotifications(); runCurrent()
        assertEquals(61, repository.notificationReads.size)
        assertEquals(61, repository.notificationReads.toSet().size)
        assertEquals(0L, model.state.value.notificationUnread)
        assertTrue(model.state.value.notifications.data!!.all { it.readAt != null })
        assertFalse(model.state.value.notificationBusy)
    }

    @Test fun readAllStopsAtConfirmedGlobalZeroWithoutWalkingReadHistory() = runTest(dispatcher) {
        val repository = FakeMessaging().apply { notificationsResult = { cursor ->
            when (cursor) {
                null -> NotificationPage(listOf("first", "second", "third").map {
                    notice(it).copy(readAt = at.takeIf { _ -> it in acknowledgedNotifications })
                }, "history-1", 3L - acknowledgedNotifications.size)
                "history-1" -> NotificationPage(listOf(notice("old").copy(readAt = at)), "history-2", 0)
                else -> error("Already-read history must not be traversed after the server confirms zero unread")
            }
        } }
        val model = model(repository); model.loadNotifications(); runCurrent()
        repository.notificationQueries.clear()
        model.readAllNotifications(); runCurrent()
        assertEquals(listOf("first", "second", "third"), repository.notificationReads)
        // Snapshot page, one older page confirming zero, then the ordinary reconciliation.
        assertEquals(listOf(null, "history-1", null), repository.notificationQueries)
        assertEquals(0L, model.state.value.notificationUnread)
        assertFalse(model.state.value.notificationBusy)
        assertNull(model.state.value.notifications.error)
    }

    @Test fun notificationReadWaitsForAcknowledgementAndCancelsStaleListResponse() = runTest(dispatcher) {
        val stale = CompletableDeferred<NotificationPage>(); val acknowledged = CompletableDeferred<InboxNotification>()
        var lists = 0
        val repository = FakeMessaging().apply {
            notificationsResult = {
                if (++lists == 2) withContext(NonCancellable) { stale.await() }
                else NotificationPage(listOf(notice("notice").copy(readAt = at.takeIf { "notice" in acknowledgedNotifications })),
                    unreadCount = if ("notice" in acknowledgedNotifications) 0 else 1)
            }
            notificationReadResult = { acknowledged.await() }
        }
        val model = model(repository); model.loadNotifications(); runCurrent()
        model.loadNotifications(); runCurrent()
        model.readNotification("notice"); runCurrent()
        assertEquals(1L, model.state.value.notificationUnread)
        assertNull(model.state.value.notifications.data!!.single().readAt)
        assertTrue(model.state.value.notificationBusy)
        acknowledged.complete(notice("notice").copy(readAt = at)); runCurrent()
        stale.complete(NotificationPage(listOf(notice("notice")), unreadCount = 1)); runCurrent()
        assertEquals(0L, model.state.value.notificationUnread)
        assertNotNull(model.state.value.notifications.data!!.single().readAt)
        model.readNotification("notice"); runCurrent()
        assertEquals(listOf("notice"), repository.notificationReads)
    }

    @Test fun readAllFailurePreservesAcknowledgedRowsAndCanBeRetried() = runTest(dispatcher) {
        val repository = FakeMessaging().apply {
            notificationsResult = { NotificationPage(listOf("first", "second").map { notice(it).copy(readAt = at.takeIf { _ -> it in acknowledgedNotifications }) },
                unreadCount = 2L - acknowledgedNotifications.size) }
            notificationReadResult = { id -> if (id == "second") throw network() else notice(id).copy(readAt = at) }
        }
        val model = model(repository); model.loadNotifications(); runCurrent()
        model.readAllNotifications(); runCurrent()
        assertEquals(1L, model.state.value.notificationUnread)
        assertNotNull(model.state.value.notifications.data!!.first().readAt)
        assertNull(model.state.value.notifications.data!!.last().readAt)
        assertTrue(model.state.value.notifications.error!!.startsWith("Не все уведомления прочитаны."))
        assertFalse(model.state.value.notificationBusy)
        repository.notificationReadResult = { notice(it).copy(readAt = at) }
        model.readAllNotifications(); runCurrent()
        assertEquals(listOf("first", "second", "second"), repository.notificationReads)
        assertEquals(0L, model.state.value.notificationUnread)
        assertNull(model.state.value.notifications.error)
    }

    @Test fun accountSwitchStopsReadAllBeforeNextNotificationAndDiscardsLateAcknowledgement() = runTest(dispatcher) {
        val gate = CompletableDeferred<InboxNotification>(); val foundation = FakeFoundation()
        val repository = FakeMessaging().apply {
            notificationsResult = { NotificationPage(listOf(notice("old-one"), notice("old-two")), unreadCount = 2) }
            notificationReadResult = { withContext(NonCancellable) { gate.await() } }
        }
        val model = model(repository, foundation); model.loadNotifications(); runCurrent()
        model.readAllNotifications(); runCurrent()
        foundation.state.value = foundation.state.value.copy(session = AccountSession(User("other", "other@example.test"), Capabilities(), true))
        runCurrent(); gate.complete(notice("old-one").copy(readAt = at)); runCurrent()
        assertEquals(listOf("old-one"), repository.notificationReads)
        assertNull(model.state.value.notifications.data)
        assertEquals(0L, model.state.value.notificationUnread)
        assertEquals(NotificationFilter.ALL, model.state.value.notificationFilter)
        assertFalse(model.state.value.notificationBusy)
    }

    @Test fun unreadScanRejectsCursorCyclesWithoutPretendingInboxIsEmpty() = runTest(dispatcher) {
        val repository = FakeMessaging().apply { notificationsResult = { cursor ->
            NotificationPage(listOf(notice("read").copy(readAt = at)), if (cursor == "one") "two" else "one", 1)
        } }
        val model = model(repository); model.notificationFilter(NotificationFilter.UNREAD); runCurrent()
        assertEquals(listOf(null, "one", "two"), repository.notificationQueries)
        assertNotNull(model.state.value.notifications.error)
        assertNull(model.state.value.notifications.data)
        assertFalse(model.state.value.notifications.loading)
    }

    @Test fun readAllRejectsCursorCycleAndDoesNotReadDuplicateNotificationTwice() = runTest(dispatcher) {
        val repository = FakeMessaging().apply { notificationsResult = { cursor ->
            NotificationPage(listOf(notice("notice").copy(readAt = at.takeIf { "notice" in acknowledgedNotifications })),
                if (cursor == "one") "two" else "one", if (acknowledgedNotifications.isEmpty()) 2 else 1)
        } }
        val model = model(repository); model.loadNotifications(); runCurrent(); model.readAllNotifications(); runCurrent()
        assertEquals(listOf("notice"), repository.notificationReads)
        assertNotNull(model.state.value.notifications.error)
        assertFalse(model.state.value.notificationBusy)
    }

    @Test fun notificationPagingRemainsAvailableBeyondThreeHundredRowsWithBoundedMemory() = runTest(dispatcher) {
        val repository = FakeMessaging().apply { notificationsResult = { cursor ->
            val start = cursor?.toInt() ?: 0
            NotificationPage((start until start + 30).map { notice("notice-$it") },
                (start + 30).takeIf { it < 360 }?.toString(), 360)
        } }
        val model = model(repository); model.loadNotifications(); runCurrent()
        repeat(11) { model.actions.moreNotifications(); runCurrent() }
        assertEquals(300, model.state.value.notifications.data!!.size)
        assertEquals("notice-60", model.state.value.notifications.data!!.first().id)
        assertEquals("notice-359", model.state.value.notifications.data!!.last().id)
        assertNull(model.state.value.notificationCursor)
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

    private fun failResource(repository: FakeMessaging, resource: String, failure: AppFailure) {
        when (resource) {
            "pins" -> repository.pinsResult = { _, _ -> throw failure }
            "search" -> repository.searchResult = { _, _, _ -> throw failure }
            "context" -> repository.contextResult = { _, _ -> throw failure }
            "pin" -> repository.pinResult = { throw failure }
            "download" -> repository.downloadResult = { _, _ -> throw failure }
            "upload" -> repository.uploadResult = { _, _, _, _ -> throw failure }
            else -> error("Unknown test resource")
        }
    }
    private fun requestResource(model: MessagingViewModel, resource: String) {
        when (resource) {
            "pins" -> model.loadPins()
            "search" -> model.searchMessages("find")
            "context" -> model.focusMessage("missing")
            "pin" -> model.pin(model.state.value.messages.data!!.first(), true)
            "download" -> assertTrue(model.export(attachment()) { error("A rejected download cannot write a document") })
            "upload" -> model.upload("missing.txt", "text/plain") { byteArrayOf(1) }
            else -> error("Unknown test resource")
        }
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
        val notificationQueries = mutableListOf<String?>()
        val acknowledgedNotifications = mutableSetOf<String>()
        var notificationsResult: suspend (String?) -> NotificationPage = {
            NotificationPage(listOf(notice("notice").copy(readAt = at.takeIf { "notice" in acknowledgedNotifications })),
                unreadCount = if ("notice" in acknowledgedNotifications) 0 else 1)
        }
        var notificationReadResult: suspend (String) -> InboxNotification = { notice(it).copy(readAt = at) }
        val groupCreates = mutableListOf<List<String>>()
        val conversationRequests = mutableListOf<String>()
        var conversationResult: suspend (String) -> ChatConversation = { Companion.conversation(it) }
        var searchResult: suspend (String, String, String?) -> ChatPage<ChatMessage> = { id, _, _ -> ChatPage(listOf(message("result", id))) }
        var contextResult: suspend (String, String) -> ChatPage<ChatMessage> = { id, mid -> ChatPage(listOf(message(mid, id))) }
        var pinsResult: suspend (String, String?) -> ChatPage<ChatMessage> = { _, _ -> ChatPage(emptyList()) }
        var pinResult: suspend () -> Unit = {}
        override suspend fun searchMessages(id: String, query: String, cursor: String?) = searchResult(id, query, cursor)
        override suspend fun messageContext(id: String, messageId: String) = contextResult(id, messageId)
        override suspend fun messagePins(id: String, cursor: String?) = pinsResult(id, cursor)
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
        var uploadResult: suspend (String, String, String, ByteArray) -> ChatAttachment = { id, filename, mimeType, bytes -> attachment(id).copy(filename = filename, mimeType = mimeType, size = bytes.size.toLong()) }
        override suspend fun conversations(query: ChatQuery): ChatPage<ChatConversation> { conversationQueries += query; return ChatPage(listOf(Companion.conversation("room"))) }
        override suspend fun conversation(id: String): ChatConversation { conversationRequests += id; return conversationResult(id) }
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
        override suspend fun uploadAttachment(id: String, filename: String, mimeType: String, bytes: ByteArray, clientRequestId: String) = uploadResult(id, filename, mimeType, bytes)
        override suspend fun downloadAttachment(id: String, attachment: ChatAttachment) = downloadResult(id, attachment)
        override suspend fun folders() = listOf(ChatFolder("folder", "Folder"))
        override suspend fun createFolder(name: String) = ChatFolder("folder", name)
        override suspend fun renameFolder(id: String, name: String) = ChatFolder(id, name)
        override suspend fun deleteFolder(id: String) = Unit
        override suspend fun reorderFolders(ids: List<String>) = ids.mapIndexed { i, id -> ChatFolder(id, id, i) }
        override suspend fun folderItems(folderId: String?, query: FolderQuery): ChatPage<FolderEntry> { folderQueries += folderId to query; return ChatPage(emptyList()) }
        override suspend fun setFolderItem(folderId: String, kind: String, itemId: String, included: Boolean) = ChatFolder(folderId, "Folder")
        override suspend fun peerPresence(id: String) = false
        override suspend fun notifications(cursor: String?): NotificationPage { notificationQueries += cursor; return notificationsResult(cursor) }
        override suspend fun readNotification(id: String): InboxNotification {
            notificationReads += id
            return notificationReadResult(id).also { if (it.readAt != null) acknowledgedNotifications += id }
        }
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
        private fun notice(id: String) = InboxNotification(id, "conference.soon", at)
        private fun conversation(id: String) = ChatConversation(id, "group", name = "Group $id", myRole = "owner", memberCount = 2, unreadCount = 1)
        private fun message(id: String, conversationId: String = "room", sequence: Long = 1) = ChatMessage(id, conversationId, "self", "Member", "request-$id", "Text $id", sequence, 1, at, at)
        private fun attachment(id: String = "room") = ChatAttachment("attachment", id, "file.txt", "text/plain", 3, "uploaded")
        private fun network() = AppFailure(FailureKind.NETWORK, "Нет подключения.")
    }
}
