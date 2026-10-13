package com.meetspace.app

import androidx.lifecycle.ViewModelStore
import com.meetspace.app.ui.CallAttachmentStatus
import com.meetspace.app.ui.CallRecordingStatus
import com.meetspace.app.ui.CallPanel
import com.meetspace.core.data.*
import com.meetspace.core.media.CallPhase
import com.meetspace.core.media.CallState
import com.meetspace.core.model.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CallDiscussionViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val stores = mutableListOf<ViewModelStore>()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { stores.forEach(ViewModelStore::clear); Dispatchers.resetMain() }

    @Test fun chatAndCommentsSharePersistedEntriesButKeepIndependentDrafts() = discussionTest {
        val chat = Chat().apply { stored = listOf(message("own"), message("peer", sequence = 2).copy(senderId = "peer")) }
        val model = model(chat)
        connect(model); runCurrent()
        model.actions.open(CallPanel.CHAT); runCurrent()
        assertEquals(model.state.value.chat.entries, model.state.value.comments.entries)
        assertTrue(model.state.value.chat.entries.first().own)
        assertFalse(model.state.value.chat.entries.last().own)
        assertEquals("Сегодня", model.state.value.chat.entries.first().dateLabel)
        assertEquals("participant", liveCall().participantId)
        model.actions.chatDraft("Черновик чата")
        model.actions.commentDraft("Общий комментарий")
        model.actions.sendComment(); runCurrent()
        assertEquals("Общий комментарий", chat.sent.single().second.text)
        assertEquals("room", chat.sent.single().first)
        assertEquals("Черновик чата", model.state.value.chat.draft)
        assertEquals("", model.state.value.comments.draft)
        assertEquals("Общий комментарий", model.state.value.chat.entries.last().text)
        assertEquals(model.state.value.chat.entries, model.state.value.comments.entries)
        assertEquals("sent-1", chat.marked.last().second)
    }

    @Test fun ambiguousSendBlocksDoubleTapAndRetriesIdenticalPayloadFromEitherPanel() = discussionTest {
        val gate = CompletableDeferred<MeetingChatMessage>()
        val chat = Chat().apply { send = { _, _ -> gate.await() } }
        val model = model(chat)
        connect(model); runCurrent()
        model.actions.chatDraft("Исходный текст")
        model.actions.sendChat(); model.actions.sendComment(); runCurrent()
        assertEquals(1, chat.sent.size)
        assertTrue(model.state.value.chat.sending)
        assertTrue(model.state.value.comments.sending)
        gate.completeExceptionally(network()); runCurrent()
        model.actions.chatDraft("Нельзя заменить неподтверждённое сообщение")
        model.actions.commentDraft("Нельзя отправить второе")
        assertEquals("Исходный текст", model.state.value.chat.draft)
        assertEquals("", model.state.value.comments.draft)
        chat.send = { room, input -> message("accepted", room).copy(text = input.text, clientRequestId = input.clientRequestId) }
        model.actions.retryComments(); runCurrent()
        assertEquals(2, chat.sent.size)
        assertEquals(chat.sent.first(), chat.sent.last())
        assertEquals("", model.state.value.chat.draft)
        assertFalse(model.state.value.comments.sending)
        assertEquals(listOf("accepted"), model.state.value.comments.entries.map { it.id })
    }

    @Test fun authoritativeRefreshAcknowledgesAnUncertainSendWithoutAnotherPost() = discussionTest {
        val chat = Chat().apply { send = { _, _ -> throw network() } }
        val model = model(chat)
        connect(model); runCurrent()
        model.actions.commentDraft("Сохранено сервером")
        model.actions.sendComment(); runCurrent()
        val input = chat.sent.single().second
        chat.stored = listOf(message("persisted").copy(text = input.text, clientRequestId = input.clientRequestId))
        model.bind(liveCall().copy(discussionRevision = 1)); runCurrent()
        assertEquals(1, chat.sent.size)
        assertEquals("", model.state.value.comments.draft)
        assertNull(model.state.value.comments.error)
        assertEquals(listOf("persisted"), model.state.value.chat.entries.map { it.id })
    }

    @Test fun aDefinitiveValidationFailureUnlocksTheDraftForCorrection() = discussionTest {
        val chat = Chat().apply { send = { _, _ -> throw AppFailure(FailureKind.VALIDATION, "Исправьте сообщение.") } }
        val model = model(chat)
        connect(model); runCurrent()
        model.actions.chatDraft("Неверно"); model.actions.sendChat(); runCurrent()
        assertFalse(model.state.value.chat.sending)
        model.actions.chatDraft("Исправлено")
        chat.send = { room, input -> message("fixed", room).copy(text = input.text, clientRequestId = input.clientRequestId) }
        model.actions.sendChat(); runCurrent()
        assertNotEquals(chat.sent.first().second.clientRequestId, chat.sent.last().second.clientRequestId)
        assertEquals("Исправлено", chat.sent.last().second.text)
    }

    @Test fun lateNonCancellableHistoryFromAnotherRoomCannotReplaceCurrentMessages() = discussionTest {
        val gate = CompletableDeferred<MeetingChatPage>()
        val chat = Chat().apply { list = { room, _ ->
            if (room == "old") withContext(NonCancellable) { gate.await() }
            else MeetingChatPage(listOf(message("new-only", room)))
        } }
        val model = model(chat)
        connect(model, "old"); runCurrent()
        model.actions.chatDraft("Старый приватный черновик")
        model.bind(liveCall("new")); runCurrent()
        assertEquals("", model.state.value.chat.draft)
        assertEquals(listOf("new-only"), model.state.value.chat.entries.map { it.id })
        gate.complete(MeetingChatPage(listOf(message("private-old", "old")))); runCurrent()
        assertEquals(listOf("new-only"), model.state.value.comments.entries.map { it.id })
        assertNull(model.state.value.chat.error)
    }

    @Test fun accountOrOriginChangeClearsDraftsAndRejectsDelayedResults() = discussionTest {
        for (changeOrigin in listOf(false, true)) {
            val gate = CompletableDeferred<MeetingChatPage>()
            val foundation = Foundation()
            val chat = Chat().apply { list = { _, _ -> withContext(NonCancellable) { gate.await() } } }
            val model = model(chat, foundation)
            connect(model); runCurrent()
            model.actions.chatDraft("Личный черновик")
            model.actions.commentDraft("Личный комментарий")
            foundation.state.value = if (changeOrigin) foundation.state.value.copy(
                profiles = listOf(ServerProfile("profile", "https://other.example.test")))
            else foundation.state.value.copy(session = AccountSession(User("other", "other@example.test"), Capabilities(), true))
            runCurrent()
            gate.complete(MeetingChatPage(listOf(message("private")))); runCurrent()
            assertEquals("", model.state.value.chat.draft)
            assertEquals("", model.state.value.comments.draft)
            assertTrue(model.state.value.chat.entries.isEmpty())
            assertFalse(model.state.value.comments.canSend)
            model.clear()
        }
    }

    @Test fun foregroundStopCancelsRequestsAndPollingThenResumeReloadsTheCurrentRoom() = discussionTest {
        val gate = CompletableDeferred<MeetingChatPage>()
        val chat = Chat().apply { list = { _, _ -> withContext(NonCancellable) { gate.await() } } }
        val model = model(chat)
        connect(model); runCurrent()
        model.foreground(false)
        gate.complete(MeetingChatPage(listOf(message("late")))); runCurrent()
        assertTrue(model.state.value.chat.entries.isEmpty())
        assertFalse(model.state.value.chat.loading)
        assertFalse(model.state.value.chat.canSend)
        val stoppedCount = chat.pages.size
        advanceTimeBy(60_000); runCurrent()
        assertEquals(stoppedCount, chat.pages.size)
        chat.list = { room, _ -> MeetingChatPage(listOf(message("fresh", room))) }
        model.foreground(true); runCurrent()
        assertEquals(stoppedCount + 1, chat.pages.size)
        assertEquals(listOf("fresh"), model.state.value.chat.entries.map { it.id })
        advanceTimeBy(5_000); runCurrent()
        assertEquals(stoppedCount + 2, chat.pages.size)
    }

    @Test fun reconnectDisablesSendingAndResumesHistoryWithoutLosingDrafts() = discussionTest {
        val chat = Chat()
        val model = model(chat)
        connect(model); runCurrent()
        model.actions.chatDraft("После восстановления")
        model.bind(liveCall().copy(phase = CallPhase.RECONNECTING))
        model.actions.sendChat(); runCurrent()
        val count = chat.pages.size
        advanceTimeBy(10_000); runCurrent()
        assertEquals(count, chat.pages.size)
        assertTrue(chat.sent.isEmpty())
        assertFalse(model.state.value.chat.canSend)
        assertEquals("После восстановления", model.state.value.chat.draft)
        model.bind(liveCall()); runCurrent()
        assertEquals(count + 1, chat.pages.size)
        assertTrue(model.state.value.chat.canSend)
        model.actions.sendChat(); runCurrent()
        assertEquals("После восстановления", chat.sent.single().second.text)
    }

    @Test fun completedOrUnknownMeetingStatusKeepsHistoryReadOnly() = discussionTest {
        for (status in listOf("finished", "future-status")) {
            val chat = Chat().apply { stored = listOf(message("history")) }
            val meetings = Meetings().apply { result = { meeting(it, status) } }
            val model = model(chat, meetings = meetings)
            connect(model); runCurrent()
            assertEquals(listOf("history"), model.state.value.comments.entries.map { it.id })
            assertFalse(model.state.value.chat.canSend)
            assertNotNull(model.state.value.chat.unavailableReason)
            model.actions.commentDraft("Не отправлять"); model.actions.sendComment(); runCurrent()
            assertTrue(chat.sent.isEmpty())
            model.clear()
        }
    }

    @Test fun revokedMembershipRemovesPrivateHistoryAndExplicitRetryRechecksAccess() = discussionTest {
        val chat = Chat().apply { stored = listOf(message("private")) }
        val model = model(chat)
        connect(model); runCurrent()
        chat.list = { _, _ -> throw AppFailure(FailureKind.FORBIDDEN, "Доступ закрыт.") }
        model.bind(liveCall().copy(discussionRevision = 1)); runCurrent()
        assertTrue(model.state.value.chat.entries.isEmpty())
        assertTrue(model.state.value.comments.entries.isEmpty())
        assertFalse(model.state.value.chat.canSend)
        assertNotNull(model.state.value.chat.error)
        val deniedCount = chat.pages.size
        advanceTimeBy(5_000); runCurrent()
        assertEquals(deniedCount, chat.pages.size)
        chat.list = { room, _ -> MeetingChatPage(listOf(message("allowed", room))) }
        model.actions.retryChat(); runCurrent()
        assertEquals(listOf("allowed"), model.state.value.comments.entries.map { it.id })
        assertTrue(model.state.value.chat.canSend)
    }

    @Test fun olderPagingIsBoundedAndDoesNotLoseNewerVersionsOrMoveOnLiveRefresh() = discussionTest {
        val chat = Chat().apply { list = { room, before ->
            val upper = before?.toInt() ?: 300
            val lower = (upper - 29).coerceAtLeast(1)
            val items = (lower..upper).map { message("m-$it", room, it.toLong()).copy(version = 3, text = "current-$it") }
            val overlap = before?.let { listOf(message("m-${upper + 1}", room, (upper + 1).toLong()).copy(version = 1, text = "stale")) }.orEmpty()
            MeetingChatPage(items + overlap, (lower - 1).takeIf { it > 0 }?.toString())
        } }
        val model = model(chat)
        connect(model); runCurrent()
        repeat(9) { model.actions.moreChat(); runCurrent() }
        assertEquals(200, model.state.value.chat.entries.size)
        assertEquals("m-1", model.state.value.chat.entries.first().id)
        assertEquals("m-200", model.state.value.chat.entries.last().id)
        assertTrue(model.state.value.chat.entries.none { it.text == "stale" })
        assertFalse(model.state.value.comments.hasMore)
        val before = model.state.value.chat.entries
        model.bind(liveCall().copy(discussionRevision = 1)); runCurrent()
        assertEquals(before, model.state.value.chat.entries)
        assertEquals(model.state.value.chat.entries, model.state.value.comments.entries)
    }

    @Test fun repeatedOlderCursorFailsWithoutReplacingTheVisibleHistory() = discussionTest {
        val chat = Chat().apply { list = { room, before ->
            if (before == null) MeetingChatPage(listOf(message("latest", room)), "same")
            else MeetingChatPage(listOf(message("older", room)), "same")
        } }
        val model = model(chat)
        connect(model); runCurrent()
        model.actions.moreComments(); runCurrent()
        assertEquals(listOf("latest"), model.state.value.chat.entries.map { it.id })
        assertNotNull(model.state.value.comments.error)
        assertFalse(model.state.value.chat.loading)
    }

    @Test fun explicitLatestReturnsFromOlderHistoryWithoutJumpingOnIncomingMessages() = discussionTest {
        for (comments in listOf(false, true)) {
            var incoming = false
            val chat = Chat().apply { list = { room, before ->
                if (before != null) MeetingChatPage(listOf(message("old", room, 1)))
                else MeetingChatPage(buildList {
                    add(message("previous-latest", room, 9))
                    if (incoming) add(message("new-live", room, 10))
                }, "older", unreadCount = if (incoming) 1 else 0)
            } }
            val model = model(chat)
            connect(model); runCurrent()
            model.actions.open(if (comments) CallPanel.COMMENTS else CallPanel.CHAT); runCurrent()
            model.actions.moreChat(); runCurrent()
            assertTrue(model.state.value.chat.viewingOlder)
            assertTrue(model.state.value.comments.viewingOlder)
            val olderWindow = model.state.value.chat.entries

            incoming = true
            model.bind(liveCall().copy(discussionRevision = 1)); runCurrent()
            assertEquals(olderWindow, model.state.value.chat.entries)
            assertTrue(model.state.value.chat.viewingOlder)
            assertTrue(chat.marked.none { it.second == "new-live" })

            if (comments) model.actions.latestComments() else model.actions.latestChat()
            runCurrent()
            assertNull(chat.pages.last().second)
            assertFalse(model.state.value.chat.viewingOlder)
            assertFalse(model.state.value.comments.viewingOlder)
            assertEquals(listOf("previous-latest", "new-live"), model.state.value.chat.entries.map { it.id })
            assertEquals(model.state.value.chat.entries, model.state.value.comments.entries)
            assertEquals("new-live", chat.marked.last().second)
            model.clear()
        }
    }

    @Test fun readAcknowledgementRequiresAnOpenDiscussionAndIgnoresOlderPages() = discussionTest {
        val chat = Chat().apply { list = { room, before ->
            if (before == null) MeetingChatPage(listOf(message("latest", room, 9)), "older", unreadCount = 2)
            else MeetingChatPage(listOf(message("old", room, 1)))
        } }
        val model = model(chat)
        connect(model); runCurrent()
        assertTrue(chat.marked.isEmpty())
        assertEquals(2, model.state.value.commentCount)
        model.actions.open(CallPanel.CHAT); runCurrent()
        assertEquals(listOf("room" to "latest"), chat.marked)
        assertNull(model.state.value.commentCount)
        model.actions.moreChat(); runCurrent()
        assertEquals(1, chat.marked.size)
    }

    @Test fun deletionAndAttachmentsRemainSharedAndRecordingBadgeUsesOnlyActiveServerStates() = discussionTest {
        val chat = Chat().apply { stored = listOf(message("deleted").copy(text = "Hidden", deletedAt = AT),
            message("file", sequence = 2).copy(attachments = listOf(MeetingChatAttachment("file", "room", "notes.txt", "text/plain", 5, "attached")))) }
        val recordings = Records().apply { status = "ready" }
        val model = model(chat, records = recordings)
        connect(model); runCurrent()
        assertEquals("Сообщение удалено", model.state.value.chat.entries.first().text)
        assertTrue(model.state.value.chat.entries.first().deleted)
        assertEquals("notes.txt", model.state.value.comments.entries.last().attachmentLabel)
        assertFalse(model.state.value.recording)
        recordings.status = "recording"
        model.bind(liveCall().copy(recordingRevision = 1)); runCurrent()
        assertTrue(model.state.value.recording)
        recordings.status = "processing"
        model.bind(liveCall().copy(recordingRevision = 2)); runCurrent()
        assertFalse(model.state.value.recording)
        assertEquals(90L, model.state.value.elapsedSeconds)
    }

    @Test fun uploadedFileIsSentByIdAndItsSourceBytesAreWiped() = discussionTest {
        val chat = Chat()
        val model = model(chat)
        connect(model); runCurrent()
        val source = "notes".toByteArray()
        model.upload("notes.txt", "text/plain") { source }; runCurrent()
        assertTrue(source.all { it == 0.toByte() })
        assertEquals("file-ready", model.state.value.chat.pendingFiles.single().id)
        model.actions.sendChat(); runCurrent()
        assertEquals(listOf("file-ready"), chat.sent.single().second.attachmentIds)
        assertEquals("", chat.sent.single().second.text)
        assertTrue(model.state.value.chat.pendingFiles.isEmpty())
    }

    @Test fun uncertainFileUploadReusesItsIdAndClearsBytesOnAccountChange() = discussionTest {
        val chat = Chat().apply { uploadFailure = network() }
        val foundation = Foundation()
        val model = model(chat, foundation)
        connect(model); runCurrent()
        val source = "private file".toByteArray()
        model.upload("notes.txt", "text/plain") { source }; runCurrent()
        model.actions.sendChat(); runCurrent()
        assertTrue(chat.sent.isEmpty())
        assertFalse(model.state.value.chat.canAttach)
        model.actions.retryChat(); runCurrent()
        assertEquals(2, chat.uploadIds.size)
        assertEquals(chat.uploadIds.first(), chat.uploadIds.last())
        foundation.state.value = foundation.state.value.copy(session = null); runCurrent()
        assertTrue(source.all { it == 0.toByte() })
        assertTrue(model.state.value.chat.pendingFiles.isEmpty())
    }

    @Test fun recordingStartsOnlyExplicitlyAndBusyBlocksAnotherStart() = discussionTest {
        val records = Records()
        val gate = CompletableDeferred<Recording>()
        records.start = { gate.await() }
        val foundation = Foundation().apply { enableRecording() }
        val model = model(Chat(), foundation, records = records)
        connect(model); runCurrent()
        assertTrue(records.started.isEmpty())
        assertTrue(model.state.value.recordingState.canStart)
        model.recordingActions.start(); model.recordingActions.start(); runCurrent()
        assertEquals(listOf("room"), records.started)
        assertTrue(model.state.value.recordingState.busy)
        gate.complete(Recording("record", "room", "composite", "starting", AT, requestedBy = "self")); runCurrent()
        assertFalse(model.state.value.recording)
        assertEquals(CallRecordingStatus.STARTING, model.state.value.recordingState.status)
        assertFalse(model.state.value.recordingState.canPause)
        assertTrue(model.state.value.recordingState.canStop)
        model.recordingActions.stop(); runCurrent()
        assertEquals(listOf("record"), records.stopped)
        assertEquals(CallRecordingStatus.STOPPING, model.state.value.recordingState.status)
    }

    @Test fun lostRecordingStartIsReconciledWithoutReplayingMutation() = discussionTest {
        val records = Records().apply { start = { status = "recording"; throw network() } }
        val foundation = Foundation().apply { enableRecording() }
        val model = model(Chat(), foundation, records = records)
        connect(model); runCurrent()
        model.recordingActions.start(); runCurrent()
        assertEquals(1, records.started.size)
        assertEquals(CallRecordingStatus.RECORDING, model.state.value.recordingState.status)
        assertFalse(model.state.value.recordingState.canStart)
        model.recordingActions.start(); runCurrent()
        assertEquals(1, records.started.size)
    }

    @Test fun failedRecordingMetadataHidesThePreviouslyActiveBadgeUntilReconfirmed() = discussionTest {
        val records = Records().apply { status = "recording" }
        val foundation = Foundation().apply { enableRecording() }
        val model = model(Chat(), foundation, records = records)
        connect(model); runCurrent()
        assertTrue(model.state.value.recording)
        assertTrue(model.state.value.recordingState.canStop)
        model.foreground(false); runCurrent()
        assertEquals(CallRecordingStatus.UNKNOWN, model.state.value.recordingState.status)
        assertFalse(model.state.value.recording)
        model.foreground(true); runCurrent()
        assertTrue(model.state.value.recording)

        records.list = { throw network() }
        model.bind(liveCall().copy(recordingRevision = 1)); runCurrent()
        assertEquals(CallRecordingStatus.UNKNOWN, model.state.value.recordingState.status)
        assertFalse(model.state.value.recording)
        assertFalse(model.state.value.recordingState.canStart)
        assertFalse(model.state.value.recordingState.canStop)
        assertNotNull(model.state.value.recordingState.error)
        model.recordingActions.start(); model.recordingActions.stop(); runCurrent()
        assertTrue(records.started.isEmpty())
        assertTrue(records.stopped.isEmpty())

        val gate = CompletableDeferred<RecordingPage>()
        records.list = { gate.await() }
        model.foreground(false); model.foreground(true); runCurrent()
        assertEquals(CallRecordingStatus.UNKNOWN, model.state.value.recordingState.status)
        assertFalse(model.state.value.recording)
        assertFalse(model.state.value.recordingState.canStart)
        gate.complete(RecordingPage(emptyList(), null)); runCurrent()
        assertEquals(CallRecordingStatus.STOPPED, model.state.value.recordingState.status)
        assertTrue(model.state.value.recordingState.canStart)
        assertNull(model.state.value.recordingState.error)
        assertTrue(records.started.isEmpty())
    }

    @Test fun ownerCanStopAnExistingRecordingEvenWhenServerAdvertisesNoStartModes() = discussionTest {
        val records = Records().apply { status = "recording" }
        val model = model(Chat(), Foundation(), records = records)
        connect(model); runCurrent()
        assertTrue(model.state.value.recordingState.canStop)
        assertFalse(model.state.value.recordingState.canStart)
        model.recordingActions.stop(); runCurrent()
        assertEquals(listOf("record"), records.stopped)
        assertTrue(records.started.isEmpty())
        assertEquals(CallRecordingStatus.STOPPING, model.state.value.recordingState.status)
    }

    @Test fun interruptedSendKeepsRetryVisibleAfterResumeAndResendsTheIdenticalPayload() = discussionTest {
        val gate = CompletableDeferred<MeetingChatMessage>()
        val chat = Chat().apply { send = { _, _ -> gate.await() } }
        val model = model(chat)
        connect(model); runCurrent()
        model.actions.chatDraft("Неподтверждённое сообщение")
        model.actions.sendChat(); runCurrent()
        val original = chat.sent.single()
        assertTrue(model.state.value.chat.sending)

        model.foreground(false); runCurrent()
        assertFalse(model.state.value.chat.sending)
        val error = model.state.value.chat.error
        assertTrue(error.orEmpty().contains("Повторите"))
        assertEquals(error, model.state.value.comments.error)
        model.foreground(true); runCurrent()
        advanceTimeBy(5_000); runCurrent()
        assertEquals(error, model.state.value.chat.error)
        assertEquals("Неподтверждённое сообщение", model.state.value.chat.draft)
        assertEquals(1, chat.sent.size)
        assertFalse(model.state.value.chat.canSend)
        model.actions.chatDraft("Нельзя подменить ожидающую отправку")
        assertEquals(original.second.text, model.state.value.chat.draft)

        chat.send = { room, input -> message("confirmed", room).copy(text = input.text, clientRequestId = input.clientRequestId) }
        model.actions.retryComments(); runCurrent()
        assertEquals(listOf(original, original), chat.sent)
        assertNull(model.state.value.chat.error)
        assertNull(model.state.value.comments.error)
        assertEquals("", model.state.value.chat.draft)
        assertEquals(listOf("confirmed"), model.state.value.chat.entries.map { it.id })
        assertTrue(model.state.value.chat.canSend)
    }

    @Test fun interruptedUploadRemainsVisibleAfterPollingAndExplicitRetryKeepsItsRequestId() = discussionTest {
        val gate = CompletableDeferred<Unit>()
        val chat = Chat().apply { beforeUpload = { gate.await() } }
        val model = model(chat)
        connect(model); runCurrent()
        val source = "private notes".toByteArray()
        var reads = 0
        model.upload("notes.txt", "text/plain") { reads++; source }; runCurrent()
        val pendingId = model.state.value.chat.pendingFiles.single().id
        assertEquals(CallAttachmentStatus.UPLOADING, model.state.value.chat.pendingFiles.single().status)

        model.foreground(false); runCurrent()
        val interrupted = model.state.value.chat.pendingFiles.single()
        assertEquals(pendingId, interrupted.id)
        assertEquals(CallAttachmentStatus.UNKNOWN, interrupted.status)
        assertNotNull(interrupted.error)
        assertNotNull(model.state.value.chat.error)
        assertEquals(model.state.value.chat.pendingFiles, model.state.value.comments.pendingFiles)
        assertEquals(model.state.value.chat.error, model.state.value.comments.error)
        assertTrue(source.any { it != 0.toByte() })

        model.foreground(true); runCurrent()
        advanceTimeBy(5_000); runCurrent()
        assertEquals(interrupted, model.state.value.chat.pendingFiles.single())
        assertNotNull(model.state.value.chat.error)
        assertEquals(1, chat.uploadIds.size)
        model.actions.sendChat(); runCurrent()
        assertTrue(chat.sent.isEmpty())

        chat.beforeUpload = {}
        model.actions.retryComments(); runCurrent()
        assertEquals(listOf(pendingId, pendingId), chat.uploadIds)
        assertEquals(1, reads)
        assertEquals(CallAttachmentStatus.READY, model.state.value.chat.pendingFiles.single().status)
        assertNull(model.state.value.chat.error)
        assertTrue(source.all { it == 0.toByte() })
    }

    @Test fun interruptionBeforeFileReadCompletesRequiresReselectionInsteadOfAnEmptyRetry() = discussionTest {
        val gate = CompletableDeferred<ByteArray>()
        val chat = Chat()
        val model = model(chat)
        connect(model); runCurrent()
        model.upload("notes.txt", "text/plain") { gate.await() }; runCurrent()
        val id = model.state.value.chat.pendingFiles.single().id
        model.foreground(false); runCurrent()
        assertEquals(CallAttachmentStatus.UNKNOWN, model.state.value.chat.pendingFiles.single().status)
        assertTrue(model.state.value.chat.pendingFiles.single().error.orEmpty().contains("выберите"))
        assertTrue(model.state.value.chat.error.orEmpty().contains("выберите"))
        model.foreground(true); model.actions.retryChat(); runCurrent()
        assertTrue(chat.uploadIds.isEmpty())
        assertEquals(id, model.state.value.chat.pendingFiles.single().id)
        assertNotNull(model.state.value.chat.error)
        model.actions.removeChatAttachment(id)
        assertTrue(model.state.value.chat.pendingFiles.isEmpty())
        assertTrue(model.state.value.chat.canAttach)
    }

    @Test fun accountChangeAtSuspendedFileReadBoundaryPreventsUploadBeforeCollectorRuns() = discussionTest {
        val gate = CompletableDeferred<Unit>()
        val foundation = Foundation()
        val chat = Chat()
        val model = model(chat, foundation)
        connect(model); runCurrent()
        val source = "old account bytes".toByteArray()
        model.upload("notes.txt", "text/plain") {
            gate.await()
            // StateFlow's collector is queued on StandardTestDispatcher. The read returns
            // in this same continuation, so the POST must check the current account itself.
            foundation.switchAccount()
            source
        }
        runCurrent()
        assertEquals(CallAttachmentStatus.UPLOADING, model.state.value.chat.pendingFiles.single().status)
        assertTrue(chat.uploadIds.isEmpty())
        gate.complete(Unit); runCurrent()
        assertTrue(chat.uploadIds.isEmpty())
        assertTrue(source.all { it == 0.toByte() })
        assertTrue(model.state.value.chat.pendingFiles.isEmpty())
        assertFalse(model.state.value.chat.canAttach)
    }

    @Test fun accountChangeAtSuspendedDownloadBoundaryPreventsExportAndCleansItsDestination() = discussionTest {
        val gate = CompletableDeferred<Unit>()
        val foundation = Foundation()
        val bytes = "old account download".toByteArray()
        val file = MeetingChatAttachment("file", "room", "notes.txt", "text/plain", bytes.size.toLong(), "attached", messageId = "entry")
        val chat = Chat().apply {
            stored = listOf(message("entry").copy(attachments = listOf(file)))
            download = { _, _ ->
                gate.await()
                // Change the value before the collector can cancel this request: writing
                // downloaded bytes must independently recheck the account scope.
                foundation.switchAccount()
                bytes
            }
        }
        val model = model(chat, foundation)
        connect(model); runCurrent()
        assertTrue(model.state.value.chat.entries.single().attachments.single().canDownload)
        var writes = 0
        var cleanups = 0
        assertTrue(model.export("entry", "file", onFailure = { cleanups++ }) { writes++ })
        runCurrent()
        assertTrue(model.state.value.chat.entries.single().attachments.single().downloading)
        assertEquals(0, writes)
        gate.complete(Unit); runCurrent()
        assertEquals(0, writes)
        assertEquals(1, cleanups)
        assertTrue(bytes.all { it == 0.toByte() })
        assertTrue(model.state.value.chat.entries.isEmpty())
    }

    private fun discussionTest(block: suspend TestScope.() -> Unit) = runTest(dispatcher) {
        try { block() } finally {
            // viewModelScope is independent from the test body; stop polling before runTest
            // advances virtual time while awaiting completion, not in the later JUnit teardown.
            stores.forEach(ViewModelStore::clear)
            stores.clear()
            runCurrent()
        }
    }

    private fun model(chat: Chat, foundation: Foundation = Foundation(), meetings: Meetings = Meetings(), records: Records = Records()): CallDiscussionViewModel =
        CallDiscussionViewModel(foundation, chat, meetings, records, Clock.fixed(AT.plusSeconds(90), ZoneOffset.UTC)).also { model ->
            stores += ViewModelStore().apply { put("discussion", model) }
        }
    private fun connect(model: CallDiscussionViewModel, room: String = "room") { model.foreground(true); model.bind(liveCall(room)) }

    private class Chat : MeetingChatRepository {
        var stored = emptyList<MeetingChatMessage>()
        var uploadFailure: AppFailure? = null
        var beforeUpload: suspend () -> Unit = {}
        var download: suspend (String, MeetingChatAttachment) -> ByteArray = { _, _ -> "file".toByteArray() }
        val uploadIds = mutableListOf<String>()
        override suspend fun uploadMeetingAttachment(conferenceId: String, filename: String, mimeType: String, bytes: ByteArray, clientRequestId: String): MeetingChatAttachment {
            uploadIds += clientRequestId
            uploadFailure?.let { throw it }
            beforeUpload()
            return MeetingChatAttachment("file-ready", conferenceId, filename, mimeType, bytes.size.toLong(), "ready")
        }
        override suspend fun downloadMeetingAttachment(conferenceId: String, attachment: MeetingChatAttachment): ByteArray = download(conferenceId, attachment)

        val pages = mutableListOf<Pair<String, String?>>()
        val sent = mutableListOf<Pair<String, ChatMessageInput>>()
        val marked = mutableListOf<Pair<String, String>>()
        var list: suspend (String, String?) -> MeetingChatPage = { room, _ -> MeetingChatPage(stored.filter { it.conferenceId == room }) }
        var send: suspend (String, ChatMessageInput) -> MeetingChatMessage = { room, input ->
            message("sent-${sent.size}", room, 100L + sent.size).copy(text = input.text, clientRequestId = input.clientRequestId).also { stored = stored + it }
        }
        override suspend fun meetingMessages(conferenceId: String, cursor: String?, limit: Int): MeetingChatPage {
            pages += conferenceId to cursor; return list(conferenceId, cursor)
        }
        override suspend fun sendMeetingMessage(conferenceId: String, input: ChatMessageInput): MeetingChatMessage {
            sent += conferenceId to input; return send(conferenceId, input)
        }
        override suspend fun meetingChatReadState(conferenceId: String) = ChatReadState(0, null)
        override suspend fun markMeetingChatRead(conferenceId: String, messageId: String): ChatReadState {
            marked += conferenceId to messageId; return ChatReadState(0, messageId)
        }
    }
    private class Foundation : FoundationRepository {
        fun enableRecording() { state.value = state.value.copy(session = state.value.session!!.copy(capabilities = Capabilities(recordingModes = listOf("composite")))) }
        fun switchAccount() { state.value = state.value.copy(session = AccountSession(User("other", "other@example.test"), Capabilities(), true)) }

        override val state = MutableStateFlow(RepositoryState(listOf(ServerProfile("profile", "https://fixture.example.test")), "profile",
            session = AccountSession(User("self", "self@example.test", "Self"), Capabilities(), true)))
        override suspend fun addServer(rawUrl: String) = state.value.selectedProfile!!
        override suspend fun selectServer(profileId: String) = Unit
        override suspend fun removeServer(profileId: String) = Unit
        override suspend fun login(email: String, password: String) = Unit
        override suspend fun restore() = Unit
        override suspend fun logout() = LogoutResult(true)
        override suspend fun setTheme(mode: ThemeMode) = Unit
    }
    private class Meetings : MeetingsRepository {
        var result: suspend (String) -> Meeting = { meeting(it) }
        override suspend fun conference(id: String) = result(id)
        override suspend fun timeline(query: MeetingsQuery): MeetingPage = error("unused")
        override suspend fun createMeeting(input: MeetingInput): Meeting = error("unused")
        override suspend fun scheduleMeeting(id: String, scheduledAt: Instant, plannedDurationMin: Int?): Meeting = error("unused")
        override suspend fun transitionMeeting(id: String, transition: MeetingTransition): Meeting = error("unused")
        override suspend fun membership(id: String): MeetingParticipant? = error("unused")
        override suspend fun joinMeeting(id: String, inviteCode: String?): MeetingParticipant = error("unused")
        override suspend fun joinInvite(rawCodeOrUrl: String): MeetingParticipant = error("unused")
        override suspend fun leaveMeeting(id: String): MeetingParticipant = error("unused")
        override suspend fun history(id: String): MeetingHistory = error("unused")
        override suspend fun updateDisplayName(displayName: String): User = error("unused")
    }
    private class Records : RecordingsRepository {
        var status: String? = null
        var list: suspend (String) -> RecordingPage = { conferenceId -> RecordingPage(status?.let {
            listOf(Recording("record", conferenceId, "composite", it, AT, requestedBy = "self"))
        }.orEmpty(), null) }
        val started = mutableListOf<String>()
        val stopped = mutableListOf<String>()
        var start: suspend () -> Recording = { Recording("record", "room", "composite", "starting", AT, requestedBy = "self") }
        override suspend fun startRecording(conferenceId: String, mode: String): Recording { started += conferenceId; return start() }
        override suspend fun stopRecording(conferenceId: String, recordingId: String): Recording {
            stopped += recordingId
            return Recording(recordingId, conferenceId, "composite", "stopping", AT, requestedBy = "self")
        }
        override suspend fun recordings(conferenceId: String, offset: Int, limit: Int) = list(conferenceId)
        override suspend fun recording(conferenceId: String, recordingId: String): Recording = error("unused")
        override suspend fun recordingAccess(conferenceId: String, recordingId: String, fileId: String): RecordingAccess = error("unused")
        override suspend fun recordingTranscript(conferenceId: String, recordingId: String): RecordingTranscriptState = error("unused")
        override suspend fun recordingSegments(conferenceId: String, recordingId: String, offset: Int, limit: Int): RecordingSegmentPage = error("unused")
        override suspend fun recordingSummary(conferenceId: String, recordingId: String): RecordingSummaryState = error("unused")
    }
    companion object {
        private val AT = Instant.parse("2026-10-12T12:00:00Z")
        private fun liveCall(room: String = "room") = CallState(CallPhase.CONNECTED, room, "Test meeting", "participant")
        private fun message(id: String, room: String = "room", sequence: Long = 1) = MeetingChatMessage(
            id, room, "self", "Self", "request-$id", "Text $id", sequence, 1, AT, AT)
        private fun meeting(id: String, status: String = "active") = Meeting(id, "self", "Test meeting", "", "", status, AT, AT, startedAt = AT)
        private fun network() = AppFailure(FailureKind.NETWORK, "Нет связи.")
    }
}
