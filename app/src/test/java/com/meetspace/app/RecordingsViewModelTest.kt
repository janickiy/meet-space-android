package com.meetspace.app

import com.meetspace.core.data.*
import com.meetspace.core.model.*
import java.io.*
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun before() { Dispatchers.setMain(dispatcher) }
    @After fun after() { Dispatchers.resetMain() }
    @Test fun transcriptSeekReusesLeaseAndArchiveCanBeExportedWithoutPlayableVideo() = runTest {
        val r = Records(); val m = RecordingsViewModel(Foundation(), Meetings(), r)
        m.show("room"); runCurrent(); m.openRecording("record"); runCurrent()
        m.seek(12000); runCurrent(); assertEquals(12000L, m.playback.value!!.positionMs)
        val lease = r.leases.single(); m.seek(25000); runCurrent()
        assertSame(lease, r.leases.single()); assertEquals(25000L, m.playback.value!!.seekRequest.value!!.second)
        val archive = media.copy(type = "tracks_archive", filename = "tracks.zip", mimeType = "application/zip")
        val tracks = record().copy(mode = "individual_tracks", files = listOf(archive))
        assertFalse(tracks.playable); assertNotNull(m.queueExport(tracks, archive)); m.close()
    }
    @Test fun deletingRecordKeepsDataUntilAcknowledgedAndClosesPlayerThen() = runTest {
        val r = Records(); val gate = CompletableDeferred<Unit>(); r.deletion = { gate.await() }
        val m = RecordingsViewModel(Foundation(), Meetings(), r)
        m.show("room"); runCurrent(); m.openRecording("record"); runCurrent(); m.play(record(), media); runCurrent()
        m.deleteRecording(record().copy(requestedBy = "user")); runCurrent()
        assertTrue(m.state.value.deleting); assertNotNull(m.state.value.detail.data); assertFalse(r.leases.single().closed)
        gate.complete(Unit); runCurrent()
        assertFalse(m.state.value.deleting); assertTrue(r.leases.single().closed); assertNull(m.state.value.detailId)
        assertTrue(m.state.value.recordings.data!!.items.isEmpty()); m.close()
    }

    @Test fun accountChangeDropsEvenLateNonCancellableResponse() = runTest {
        val f = Foundation(); val r = Records(); val gate = CompletableDeferred<RecordingPage>()
        r.list = { withContext(NonCancellable) { gate.await() } }
        val m = RecordingsViewModel(f, Meetings(), r)
        m.show("room"); runCurrent()
        f.state.value = f.state.value.copy(session = null); runCurrent()
        gate.complete(RecordingPage(listOf(record()), null)); runCurrent()
        assertFalse(m.state.value.visible); assertNull(m.state.value.recordings.data)
    }
    @Test fun openingOtherMeetingCancelsOldPageAndDetail() = runTest {
        val r = Records(); val gate = CompletableDeferred<RecordingPage>()
        r.list = { if (it == "room") withContext(NonCancellable) { gate.await() } else RecordingPage(listOf(record().copy(conferenceId = it)), null) }
        val m = RecordingsViewModel(Foundation(), Meetings(), r)
        m.show("room"); runCurrent(); m.openMeeting("second"); runCurrent()
        gate.complete(RecordingPage(listOf(record().copy(id = "stale")), null)); runCurrent()
        assertEquals("second", m.state.value.recordings.data!!.items.single().conferenceId)
    }
    @Test fun readyPlaybackAccessIsReacquiredAndClosedOnLogout() = runTest {
        val f = Foundation(); val r = Records(); val m = RecordingsViewModel(f, Meetings(), r)
        m.show("room"); runCurrent(); m.openRecording("record"); runCurrent()
        m.play(record(), media); runCurrent(); val first = r.leases.single()
        assertNotNull(m.playback.value)
        m.play(record(), media); runCurrent(); assertTrue(first.closed); assertEquals(2, r.leases.size)
        f.state.value = f.state.value.copy(session = null); runCurrent()
        assertTrue(r.leases.last().closed); assertNull(m.playback.value)
    }
    @Test fun lateAcquiredLeaseIsClosedWhenRouteChanges() = runTest {
        val r = Records(); val gate = CompletableDeferred<RecordingAccess>(); val lease = Access()
        r.access = { withContext(NonCancellable) { gate.await() } }
        val m = RecordingsViewModel(Foundation(), Meetings(), r)
        m.show("room"); runCurrent(); m.play(record(), media); runCurrent(); m.close()
        gate.complete(lease); runCurrent()
        assertTrue(lease.closed); assertNull(m.playback.value)
    }
    @Test fun failedPageKeepsLoadedRowsAndRetryStopsSpinner() = runTest {
        val r = Records(); val m = RecordingsViewModel(Foundation(), Meetings(), r)
        m.show("room"); runCurrent()
        r.list = { throw AppFailure(FailureKind.NETWORK, "Нет сети") }
        m.actions.refreshRecordings(); runCurrent()
        assertEquals(1, m.state.value.recordings.data!!.items.size)
        assertFalse(m.state.value.recordings.loading)
        assertEquals("Нет сети", m.state.value.recordings.error)
        r.list = { RecordingPage(emptyList(), null) }; m.actions.refreshRecordings(); runCurrent()
        assertNull(m.state.value.recordings.error)
    }
    @Test fun denyDetailClearsLoadedPrivateMaterials() = runTest {
        for (deniedMaterial in listOf(false, true)) {
            val r = Records(); val m = RecordingsViewModel(Foundation(), Meetings(), r)
            m.show("room"); runCurrent(); m.openRecording("record"); runCurrent(); m.actions.loadTranscript(); m.actions.loadSummary(); runCurrent()
            assertNotNull(m.state.value.summary.data)
            if (deniedMaterial) {
                r.summary = { throw AppFailure(FailureKind.FORBIDDEN, "Нет доступа к материалам") }
                m.actions.loadSummary()
            } else {
                r.detail = { throw AppFailure(FailureKind.FORBIDDEN, "Нет доступа к записи") }
                m.actions.refreshDetail()
            }
            runCurrent()
            assertNull(m.state.value.detail.data); assertNull(m.state.value.summary.data); assertNull(m.state.value.segments.data)
            // Material panels disappear together with private detail. Their local error is
            // insufficient: the route must retain a visible explanation above those panels.
            assertEquals(if (deniedMaterial) "Нет доступа к материалам" else "Нет доступа к записи", m.state.value.error)
            m.close()
        }
    }
    @Test fun transcriptsUseServerOffsetsAcrossDuplicates() = runTest {
        val r = Records(); val m = RecordingsViewModel(Foundation(), Meetings(), r)
        m.show("room"); runCurrent(); m.openRecording("record"); runCurrent(); m.actions.loadTranscript(); runCurrent()
        m.actions.moreSegments(); runCurrent(); m.actions.moreSegments(); runCurrent()
        assertEquals(listOf(0, 2, 4), r.offsets)
        assertEquals(listOf("0", "1", "2", "4"), m.state.value.segments.data!!.items.map { it.id })
        assertNull(m.state.value.segments.data!!.nextOffset)
    }
    @Test fun queuedExportIsDroppedOnAccountChange() = runTest {
        val f = Foundation(); val m = RecordingsViewModel(f, Meetings(), Records())
        m.show("room"); runCurrent(); assertNotNull(m.queueExport(record(), media))
        f.state.value = f.state.value.copy(session = null); runCurrent()
        assertFalse(m.exportTo { _, _ -> fail("Must not export") })
    }
    @Test fun failingFreshAccessRemovesEmptyCreatedDocument() = runTest {
        val r = Records().apply { access = { throw AppFailure(FailureKind.FORBIDDEN, "Нет доступа") } }
        val m = RecordingsViewModel(Foundation(), Meetings(), r); m.show("room"); runCurrent(); m.queueExport(record(), media)
        var cleanup = 0
        assertTrue(m.exportTo(cleanup = { cleanup++ }) { _, _ -> fail("Must not export") }); runCurrent()
        assertEquals(1, cleanup); assertNull(m.state.value.download); assertEquals("Нет доступа", m.state.value.error)
    }
    @Test fun cancelBeforeExportStartsStillRemovesDocument() = runTest {
        val m = RecordingsViewModel(Foundation(), Meetings(), Records()); m.show("room"); runCurrent(); m.queueExport(record(), media)
        var cleanup = 0
        m.exportTo(cleanup = { cleanup++ }) { _, _ -> fail("Must not export") }; m.cancelDownload(); runCurrent()
        assertEquals(1, cleanup); assertNull(m.state.value.download)
    }
    @Test fun successfulExportKeepsDocumentAndClosesLease() = runTest {
        val r = Records(); val m = RecordingsViewModel(Foundation(), Meetings(), r); m.show("room"); runCurrent(); m.queueExport(record(), media)
        var cleanup = 0
        m.exportTo(cleanup = { cleanup++ }) { _, progress -> progress(42) }; runCurrent()
        assertEquals(0, cleanup); assertTrue(r.leases.single().closed); assertNull(m.state.value.download); assertNotNull(m.state.value.notice)
    }
    @Test fun disabledProviderStillLoadsAuthorizedHistoricalTranscript() = runTest {
        val r = Records().apply { transcriptEnabled = false }
        val m = RecordingsViewModel(Foundation(), Meetings(), r); m.show("room"); runCurrent(); m.openRecording("record"); runCurrent()
        m.actions.loadTranscript(); runCurrent()
        assertFalse(m.state.value.transcript.data!!.enabled); assertNotNull(m.state.value.segments.data)
    }
    @Test fun activeMeetingFilterAndRepeatedCursorAreHandled() = runTest {
        val meetings = Meetings(); val m = RecordingsViewModel(Foundation(), meetings, Records())
        m.show(); runCurrent(); m.actions.filterMeetings(MeetingsView.ACTIVE); runCurrent()
        assertEquals(listOf(MeetingsView.ACTIVE), meetings.queries.map { it.view })
        m.actions.moreMeetings(); runCurrent(); assertNotNull(m.state.value.meetings.error)
    }

    @Test fun globalLibraryLoadsOneBoundedBatchAndWaitsForExplicitContinuation() = runTest {
        val meetings = Meetings().apply { page = { error("Global recordings must not depend on timeline membership") } }
        val records = Records().apply { library = { cursor, _ ->
            val range = if (cursor == null) 1..30 else 31..31
            RecordingLibraryPage(range.map { index ->
                val meeting = meeting().copy(id = "room-$index", title = "Meeting $index")
                RecordingLibraryItem(meeting, record().copy(id = "record-$index", conferenceId = meeting.id))
            }, "next".takeIf { cursor == null })
        } }
        val model = RecordingsViewModel(Foundation(), meetings, records)
        model.show(); runCurrent()
        assertNull(model.state.value.meetingId)
        assertEquals(30, model.state.value.library.meetings.size)
        assertTrue(meetings.queries.isEmpty())
        assertTrue(records.pages.isEmpty())
        assertEquals(listOf(null to 30), records.libraryPages)
        assertTrue(model.state.value.library.hasMore)
        assertTrue(records.leases.isEmpty())
        model.actions.moreLibrary(); runCurrent()
        assertEquals(31, model.state.value.library.meetings.size)
        assertEquals(listOf(null to 30, "next" to 30), records.libraryPages)
        assertFalse(model.state.value.library.hasMore)
    }

    @Test fun globalLibraryIncludesReadyRecordingsFromActiveAndNeverJoinedMeetings() = runTest {
        val active = meeting().copy(id = "active", status = "active")
        val neverJoined = meeting().copy(id = "never-joined", status = "finished")
        val meetings = Meetings().apply { page = { MeetingPage(emptyList(), null) } }
        val records = Records().apply { library = { _, _ -> RecordingLibraryPage(listOf(
            RecordingLibraryItem(active, record().copy(id = "screenshot-record", conferenceId = active.id, durationSec = 28)),
            RecordingLibraryItem(neverJoined, record().copy(id = "readable-record", conferenceId = neverJoined.id)),
        ), null) } }
        val model = RecordingsViewModel(Foundation(), meetings, records)
        model.show(); runCurrent()
        assertEquals(listOf("active", "never-joined"), model.state.value.library.meetings.map { it.meeting.id })
        assertEquals(28L, model.state.value.library.meetings.first().recordings.data!!.items.single().durationSec)
        assertTrue(meetings.queries.isEmpty()); assertTrue(records.pages.isEmpty())
        assertFalse(model.state.value.library.hasMore)
        model.actions.moreLibrary(); runCurrent()
        assertEquals(1, records.libraryPages.size)
    }

    @Test fun olderServerChecksActiveMeetingsBeforePastHistoryIsExhaustedAndWarnsAboutCoverage() = runTest {
        val active = meeting().copy(id = "active", status = "active")
        val meetings = Meetings().apply { page = { query -> when (query.view) {
            MeetingsView.PAST -> MeetingPage(listOf(meeting()), "many-more-past-meetings")
            MeetingsView.ACTIVE -> MeetingPage(listOf(active), null)
            else -> MeetingPage(emptyList(), null)
        } } }
        val records = Records().apply {
            library = { _, _ -> throw AppFailure(FailureKind.INCOMPATIBLE_SERVER, "Обновите сервер") }
            page = { id, _, _ -> RecordingPage(listOf(record().copy(id = "record-$id", conferenceId = id)), null) }
        }
        val model = RecordingsViewModel(Foundation(), meetings, records)
        model.show(); runCurrent()
        assertEquals(listOf(MeetingsView.PAST, MeetingsView.ACTIVE, MeetingsView.UPCOMING), meetings.queries.map { it.view })
        assertTrue(model.state.value.library.meetings.any { it.meeting.id == "active" })
        assertTrue(model.state.value.library.hasMore)
        assertTrue(model.state.value.notice!!.contains("для полного каталога"))
        assertNull(model.state.value.library.error)
        model.actions.moreLibrary(); runCurrent()
        assertEquals("many-more-past-meetings", meetings.queries.last().cursor)
        assertNotNull(model.state.value.library.error) // An unchanged cursor cannot loop forever.
    }

    @Test fun globalNetworkOrAccessFailureNeverFallsBackToMeetingDiscovery() = runTest {
        val meetings = Meetings()
        val records = Records()
        val model = RecordingsViewModel(Foundation(), meetings, records)
        model.show(); runCurrent()
        records.library = { _, _ -> throw AppFailure(FailureKind.NETWORK, "Нет сети") }
        model.actions.refreshLibrary(); runCurrent()
        assertEquals("Нет сети", model.state.value.library.error)
        assertEquals(1, model.state.value.library.meetings.size)
        records.library = { _, _ -> throw AppFailure(FailureKind.FORBIDDEN, "Доступ отозван") }
        model.actions.refreshLibrary(); runCurrent()
        assertTrue(model.state.value.library.meetings.isEmpty())
        assertTrue(meetings.queries.isEmpty())
        assertTrue(records.pages.isEmpty())
    }

    @Test fun globalCursorLoopPreservesRowsAndCannotSkipIntoAnotherMeeting() = runTest {
        val records = Records().apply { library = { cursor, _ ->
            RecordingLibraryPage(listOf(RecordingLibraryItem(meeting(), record().copy(id = if (cursor == null) "first" else "second"))), "same")
        } }
        val model = RecordingsViewModel(Foundation(), Meetings(), records)
        model.show(); runCurrent(); model.actions.moreLibrary(); runCurrent()
        assertEquals(listOf("first"), model.state.value.library.meetings.single().recordings.data!!.items.map { it.id })
        assertNotNull(model.state.value.library.error)
        records.library = { _, _ -> RecordingLibraryPage(listOf(RecordingLibraryItem(meeting(), record().copy(conferenceId = "other"))), null) }
        model.actions.refreshLibrary(); runCurrent()
        assertEquals(listOf("first"), model.state.value.library.meetings.single().recordings.data!!.items.map { it.id })
        assertNotNull(model.state.value.library.error)
    }

    @Test fun returningFromMeetingMaterialsRefreshesThePreviouslyLoadedCatalogue() = runTest {
        val records = Records()
        val model = RecordingsViewModel(Foundation(), Meetings(), records)
        model.show(); runCurrent(); model.openMeeting("room"); runCurrent()
        records.library = { _, _ -> RecordingLibraryPage(listOf(
            RecordingLibraryItem(meeting(), record()),
            RecordingLibraryItem(meeting(), record().copy(id = "new-record")),
        ), null) }
        model.actions.allRecordings(); runCurrent()
        assertEquals(2, records.libraryPages.size)
        assertEquals(listOf("record", "new-record"), model.state.value.library.meetings.single().recordings.data!!.items.map { it.id })
        assertNull(model.state.value.meetingId)
    }

    @Test fun lateGlobalCatalogueResponseCannotRepopulateAnotherAccount() = runTest {
        val foundation = Foundation()
        val gate = CompletableDeferred<RecordingLibraryPage>()
        val records = Records().apply { library = { _, _ -> withContext(NonCancellable) { gate.await() } } }
        val model = RecordingsViewModel(foundation, Meetings(), records)
        model.show(); runCurrent()
        foundation.state.value = foundation.state.value.copy(session = null); runCurrent()
        gate.complete(RecordingLibraryPage(listOf(RecordingLibraryItem(meeting(), record())), null)); runCurrent()
        assertFalse(model.state.value.visible)
        assertTrue(model.state.value.library.meetings.isEmpty())
    }

    @Test fun previewOnlyAcquiresTheVisibleReadyJpegAndReturnsItsOwnedLease() = runTest {
        val preview = media.copy(id = "preview", type = "preview_jpg", filename = "preview.jpg", mimeType = "image/jpeg")
        val recording = record().copy(files = listOf(media, preview))
        var accesses = 0
        val lease = Access(preview)
        val records = Records().apply { list = { RecordingPage(listOf(recording), null) }; access = { accesses++; lease } }
        val model = RecordingsViewModel(Foundation(), Meetings(), records)
        model.show("room"); runCurrent()
        suspend fun rejected(record: Recording, file: RecordingFile) {
            try { model.previewAccess(record, file); fail("Preview must not be authorised") } catch (_: CancellationException) { }
        }
        rejected(recording, media)
        rejected(recording.copy(id = "other"), preview)
        rejected(recording.copy(status = "processing"), preview)
        rejected(recording, preview.copy(available = false))
        assertEquals(0, accesses)
        assertSame(lease, model.previewAccess(recording, preview))
        assertFalse(lease.closed)
        lease.close(); assertTrue(lease.closed)
        model.close(); rejected(recording, preview)
        assertEquals(1, accesses)
    }

    @Test fun latePreviewLeaseIsClosedAfterRouteCloseOrAccountChange() = runTest {
        for (logout in listOf(false, true)) {
            val preview = media.copy(id = "preview", type = "preview_jpg", filename = "preview.jpg", mimeType = "image/jpeg")
            val recording = record().copy(files = listOf(preview))
            val gate = CompletableDeferred<RecordingAccess>()
            val lease = Access(preview)
            val foundation = Foundation()
            val records = Records().apply { list = { RecordingPage(listOf(recording), null) }; access = { withContext(NonCancellable) { gate.await() } } }
            val model = RecordingsViewModel(foundation, Meetings(), records)
            model.show("room"); runCurrent()
            val request = async { try { model.previewAccess(recording, preview); false } catch (_: CancellationException) { true } }
            runCurrent()
            if (logout) foundation.state.value = foundation.state.value.copy(session = null) else model.close()
            runCurrent(); gate.complete(lease); runCurrent()
            assertTrue(request.await()); assertTrue(lease.closed)
        }
    }

    @Test fun libraryKeepsPartialFailureExplicitAndRetriesOnlyThatMeeting() = runTest {
        val meetings = Meetings()
        var offline = true
        val records = Records().apply {
            library = { _, _ -> RecordingLibraryPage(listOf("room", "second").map { id ->
                RecordingLibraryItem(meeting().copy(id = id), record().copy(id = "record-$id", conferenceId = id))
            }, null) }
            page = { id, _, _ ->
                if (id == "second" && offline) throw AppFailure(FailureKind.NETWORK, "Нет сети для второй встречи")
                RecordingPage(listOf(record().copy(id = "record-$id", conferenceId = id)), null)
            }
        }
        val model = RecordingsViewModel(Foundation(), meetings, records)
        model.show(); runCurrent()
        model.actions.retryLibraryMeeting("second"); runCurrent()
        assertEquals(1, model.state.value.library.meetings.first().recordings.data!!.items.size)
        assertEquals("Нет сети для второй встречи", model.state.value.library.meetings.last().recordings.error)
        assertEquals(1, model.state.value.library.meetings.last().recordings.data!!.items.size)
        offline = false
        model.actions.retryLibraryMeeting("second"); runCurrent()
        assertEquals(listOf("second", "second"), records.pages.map { it.first })
        assertTrue(meetings.queries.isEmpty())
        assertNull(model.state.value.library.meetings.last().recordings.error)
        records.library = { _, _ -> throw AppFailure(FailureKind.NETWORK, "Обновление недоступно") }
        model.actions.refreshLibrary(); runCurrent()
        assertEquals(2, model.state.value.library.meetings.size)
        assertEquals("Обновление недоступно", model.state.value.library.error)
        assertFalse(model.state.value.library.loading)
    }

    @Test fun libraryRecordingPaginationUsesServerOffsetAndRejectsLoopsWithoutDroppingRows() = runTest {
        val meetings = Meetings().apply { page = { MeetingPage(listOf(meeting()), null) } }
        val records = Records().apply { page = { _, offset, _ ->
            if (offset == 0) RecordingPage(listOf(record()), 12)
            else RecordingPage(listOf(record(), record().copy(id = "second")), 12)
        } }
        val model = RecordingsViewModel(Foundation(), meetings, records)
        model.show(); runCurrent(); model.actions.retryLibraryMeeting("room"); runCurrent()
        model.actions.moreLibraryMeeting("room"); runCurrent()
        assertEquals(listOf(0, 12), records.pages.map { it.second })
        assertEquals(listOf("record"), model.state.value.library.meetings.single().recordings.data!!.items.map { it.id })
        assertNotNull(model.state.value.library.meetings.single().recordings.error)
        records.page = { _, _, _ -> RecordingPage(listOf(record(), record().copy(id = "second")), null) }
        model.actions.moreLibraryMeeting("room"); runCurrent()
        assertEquals(listOf("record", "second"), model.state.value.library.meetings.single().recordings.data!!.items.map { it.id })
        assertNull(model.state.value.library.meetings.single().recordings.data!!.nextOffset)
    }

    @Test fun libraryDenialRemovesThatMeetingsCachedRecordsAndLateLogoutResultIsDiscarded() = runTest {
        val foundation = Foundation()
        val records = Records()
        val model = RecordingsViewModel(foundation, Meetings(), records)
        model.show(); runCurrent()
        records.page = { _, _, _ -> throw AppFailure(FailureKind.FORBIDDEN, "Доступ отозван") }
        model.actions.retryLibraryMeeting("room"); runCurrent()
        assertNull(model.state.value.library.meetings.single().recordings.data)
        assertEquals("Доступ отозван", model.state.value.library.meetings.single().recordings.error)
        val gate = CompletableDeferred<RecordingPage>()
        records.page = { _, _, _ -> withContext(NonCancellable) { gate.await() } }
        model.actions.retryLibraryMeeting("room"); runCurrent()
        foundation.state.value = foundation.state.value.copy(session = null); runCurrent()
        gate.complete(RecordingPage(listOf(record()), null)); runCurrent()
        assertFalse(model.state.value.visible)
        assertTrue(model.state.value.library.meetings.isEmpty())
    }

    @Test fun libraryDetailRechecksAccessAndReturnsToLoadedGridWithoutLosingItsScope() = runTest {
        val records = Records()
        val model = RecordingsViewModel(Foundation(), Meetings(), records)
        model.show(); runCurrent()
        model.actions.openLibrary("room", "record"); runCurrent()
        assertTrue(model.state.value.detailFromLibrary)
        assertEquals(listOf("room" to "record"), records.details)
        assertEquals("room", model.state.value.meetingId)
        model.play(record(), media); runCurrent()
        val lease = records.leases.single()
        model.actions.allRecordings(); runCurrent()
        assertTrue(lease.closed)
        assertNull(model.playback.value)
        assertNull(model.state.value.detailId)
        assertNull(model.state.value.meetingId)
        assertEquals(1, model.state.value.library.meetings.size)
        assertTrue(records.pages.isEmpty())
        assertEquals(1, records.libraryPages.size)
        model.actions.openLibrary("room", "record"); runCurrent()
        model.closeDetail()
        assertNull(model.state.value.meetingId)
        assertFalse(model.state.value.detailFromLibrary)
    }

    @Test fun readyIndividualTracksWithoutPlayableMixOpenMaterialsWithoutAutoplay() = runTest {
        val archive = media.copy(id = "archive", type = "tracks_archive", filename = "tracks.zip", mimeType = "application/zip")
        val tracks = record().copy(mode = "individual_tracks", files = listOf(archive))
        assertFalse(tracks.playable)
        val records = Records().apply {
            library = { _, _ -> RecordingLibraryPage(listOf(RecordingLibraryItem(meeting(), tracks)), null) }
            detail = { tracks }
        }
        val model = RecordingsViewModel(Foundation(), Meetings(), records)
        model.show(); runCurrent(); model.actions.openLibrary("room", "record"); runCurrent()
        assertEquals("record", model.state.value.detailId)
        assertEquals(tracks, model.state.value.detail.data)
        assertTrue(model.state.value.detailFromLibrary)
        assertNull(model.playback.value); assertTrue(records.leases.isEmpty())
        model.play(tracks, archive); runCurrent()
        assertNull(model.playback.value); assertTrue(records.leases.isEmpty())
        assertNotNull(model.queueExport(tracks, archive)); model.discardExport(); model.close()
    }

    @Test fun refreshingLibraryCancelsMeetingRetryWithoutLeavingItsSpinnerOrAcceptingLateRows() = runTest {
        val meetings = Meetings()
        val records = Records()
        val model = RecordingsViewModel(Foundation(), meetings, records)
        model.show(); runCurrent()
        val gate = CompletableDeferred<RecordingPage>()
        records.page = { _, _, _ -> withContext(NonCancellable) { gate.await() } }
        model.actions.retryLibraryMeeting("room"); runCurrent()
        assertTrue(model.state.value.library.meetings.single().recordings.loading)
        records.library = { _, _ -> throw AppFailure(FailureKind.NETWORK, "Нет сети") }
        model.actions.refreshLibrary(); runCurrent()
        gate.complete(RecordingPage(listOf(record().copy(id = "late")), null)); runCurrent()
        assertFalse(model.state.value.library.loading)
        assertFalse(model.state.value.library.meetings.single().recordings.loading)
        assertEquals("record", model.state.value.library.meetings.single().recordings.data!!.items.single().id)
    }

    @Test fun lateLibraryDenialClosesLeaseAndPrivateDetailOpenedWhileItsRetryWasRunning() = runTest {
        val records = Records()
        val model = RecordingsViewModel(Foundation(), Meetings(), records)
        model.show(); runCurrent()
        val gate = CompletableDeferred<RecordingPage>()
        records.page = { _, _, _ -> gate.await() }
        model.actions.retryLibraryMeeting("room"); runCurrent()
        model.actions.openLibrary("room", "record"); runCurrent()
        model.play(record(), media); runCurrent()
        val lease = records.leases.single()
        gate.completeExceptionally(AppFailure(FailureKind.FORBIDDEN, "Доступ отозван")); runCurrent()
        assertTrue(lease.closed)
        assertNull(model.playback.value)
        assertNull(model.state.value.detail.data)
        assertNull(model.state.value.library.meetings.single().recordings.data)
        assertEquals("Доступ отозван", model.state.value.error)
    }

    @Test fun lateDownloadDenialInvalidatesItsMeetingWithoutClosingAnotherMeetingsPlayerOrMaterials() = runTest {
        // Cover both an API denial before acquiring a lease and an expired storage URL
        // while copying. Both can arrive after the user has moved to a different recording.
        for (failedWhileReading in listOf(false, true)) {
            val second = record().copy(id = "second-record", conferenceId = "second")
            val meetings = Meetings().apply { page = { MeetingPage(listOf(meeting(), meeting().copy(id = "second")), null) } }
            val accessGate = CompletableDeferred<RecordingAccess>()
            val readGate = CompletableDeferred<Unit>()
            val downloadLease = Access()
            val playerLease = Access()
            var accessCalls = 0
            val records = Records().apply {
                library = { _, _ -> RecordingLibraryPage(listOf(
                    RecordingLibraryItem(meeting(), record()),
                    RecordingLibraryItem(meeting().copy(id = "second"), second),
                ), null) }
                page = { id, _, _ -> RecordingPage(listOf(if (id == "room") record() else second), null) }
                access = { if (accessCalls++ == 0) { if (failedWhileReading) downloadLease else accessGate.await() } else playerLease }
            }
            val model = RecordingsViewModel(Foundation(), meetings, records)
            model.show(); runCurrent()
            model.actions.openLibrary("room", "record"); runCurrent()
            assertNotNull(model.queueExport(record(), media))
            var cleanup = 0
            assertTrue(model.exportTo(cleanup = { cleanup++ }) { _, _ -> readGate.await() })
            runCurrent()
            assertNotNull(model.state.value.download)

            model.actions.allRecordings()
            records.detail = { second }
            model.actions.openLibrary("second", second.id); runCurrent()
            model.actions.loadTranscript(); model.actions.loadSummary(); model.play(second, media); runCurrent()
            val before = model.state.value
            assertNotNull(before.segments.data); assertNotNull(before.summary.data)
            assertSame(playerLease, model.playback.value!!.access)

            if (failedWhileReading) readGate.completeExceptionally(RecordingReadException(FailureKind.FORBIDDEN, "Ссылка записи A истекла"))
            else accessGate.completeExceptionally(AppFailure(FailureKind.FORBIDDEN, "Доступ к записи A отозван"))
            runCurrent()

            val after = model.state.value
            assertEquals("second", after.meetingId)
            assertEquals(before.detail, after.detail)
            assertEquals(before.transcript, after.transcript)
            assertEquals(before.segments, after.segments)
            assertEquals(before.summary, after.summary)
            assertSame(playerLease, model.playback.value!!.access)
            assertFalse(playerLease.closed)
            assertEquals(listOf("second"), after.library.meetings.map { it.meeting.id })
            assertEquals(second, after.library.meetings.single().recordings.data!!.items.single())
            assertNull(after.download)
            assertEquals(1, cleanup)
            if (failedWhileReading) assertTrue(downloadLease.closed)
            model.close()
            assertTrue(playerLease.closed)
        }
    }

    @Test fun notificationOpensExactRecordingAfterMeetingAccessWithoutPagingOrAutoplay() = runTest {
        val gate = CompletableDeferred<Meeting>()
        val meetings = Meetings().apply { conference = { gate.await() } }
        val target = record().copy(id = "older-record")
        val records = Records().apply { detail = { target } }
        val model = RecordingsViewModel(Foundation(), meetings, records)
        model.openNotification("room", "older-record"); runCurrent()
        assertTrue(model.state.value.visible)
        assertEquals("older-record", model.state.value.detailId)
        assertTrue(model.state.value.detail.loading)
        assertTrue(records.details.isEmpty())
        gate.complete(meeting()); runCurrent()
        assertEquals(listOf("room" to "older-record"), records.details)
        assertEquals(target, model.state.value.detail.data)
        assertTrue(records.pages.isEmpty())
        assertTrue(records.leases.isEmpty()); assertNull(model.playback.value)
        model.back(); runCurrent()
        assertNull(model.state.value.detailId)
        assertEquals("room", model.state.value.meetingId)
        assertEquals(1, records.pages.size)
        assertNotNull(model.state.value.recordings.data)
        model.close()
    }

    @Test fun summaryNotificationLoadsMaterialAndNormalNavigationResetsItsTab() = runTest {
        val records = Records()
        val model = RecordingsViewModel(Foundation(), Meetings(), records)
        model.openNotification("room", "record", summary = true); runCurrent()
        assertTrue(model.state.value.initialSummary)
        assertNotNull(model.state.value.summary.data)
        assertEquals(1, records.summaryCalls)
        assertTrue(records.leases.isEmpty())
        model.openRecording("record"); runCurrent()
        assertFalse(model.state.value.initialSummary)
        assertNull(model.state.value.summary.data)
        assertEquals(1, records.summaryCalls)
        model.close()
    }

    @Test fun legacyNotificationWithoutRecordingIdFallsBackToMeetingRecordings() = runTest {
        val records = Records()
        val model = RecordingsViewModel(Foundation(), Meetings(), records)
        model.openNotification("room", null, summary = true); runCurrent()
        assertTrue(model.state.value.visible)
        assertEquals("room", model.state.value.meetingId)
        assertNull(model.state.value.detailId)
        assertFalse(model.state.value.initialSummary)
        assertEquals(1, records.pages.size)
        assertTrue(records.details.isEmpty())
        model.close()
    }

    @Test fun deniedNotificationMeetingNeverLoadsRecordingOrMaterials() = runTest {
        val meetings = Meetings().apply { conference = { throw AppFailure(FailureKind.FORBIDDEN, "Доступ к встрече закрыт") } }
        val records = Records()
        val model = RecordingsViewModel(Foundation(), meetings, records)
        model.openNotification("room", "record", summary = true); runCurrent()
        assertEquals("Доступ к встрече закрыт", model.state.value.detail.error)
        assertFalse(model.state.value.detail.loading)
        assertNull(model.state.value.meeting.data); assertNull(model.state.value.detail.data)
        assertTrue(records.details.isEmpty()); assertEquals(0, records.summaryCalls)
        model.close()
    }

    @Test fun notificationCannotAcceptLateMeetingAfterAccountChange() = runTest {
        val foundation = Foundation(); val gate = CompletableDeferred<Meeting>()
        val meetings = Meetings().apply { conference = { withContext(NonCancellable) { gate.await() } } }
        val records = Records()
        val model = RecordingsViewModel(foundation, meetings, records)
        model.openNotification("room", "record", summary = true); runCurrent()
        foundation.state.value = foundation.state.value.copy(session = null); runCurrent()
        gate.complete(meeting()); runCurrent()
        assertFalse(model.state.value.visible)
        assertNull(model.state.value.detailId); assertNull(model.state.value.meeting.data)
        assertTrue(records.details.isEmpty()); assertEquals(0, records.summaryCalls)
    }

    @Test fun newerNotificationDropsOldLateRecordingAndItsSummaryRequest() = runTest {
        val gate = CompletableDeferred<Recording>()
        val records = Records().apply { detail = { withContext(NonCancellable) { gate.await() } } }
        val model = RecordingsViewModel(Foundation(), Meetings(), records)
        model.openNotification("room", "record", summary = true); runCurrent()
        val second = record().copy(id = "second-record", conferenceId = "second")
        records.detail = { second }
        model.openNotification("second", "second-record"); runCurrent()
        gate.complete(record()); runCurrent()
        assertEquals(second, model.state.value.detail.data)
        assertEquals("second", model.state.value.meetingId)
        assertFalse(model.state.value.initialSummary)
        assertEquals(0, records.summaryCalls)
        model.close()
    }

    @Test fun notificationRejectsUnrelatedRecordingResponse() = runTest {
        val records = Records().apply { detail = { record().copy(conferenceId = "other-room") } }
        val model = RecordingsViewModel(Foundation(), Meetings(), records)
        model.openNotification("room", "record", summary = true); runCurrent()
        assertNull(model.state.value.detail.data)
        assertNotNull(model.state.value.detail.error)
        assertFalse(model.state.value.detail.loading)
        assertEquals(0, records.summaryCalls)
        model.close()
    }

    private class Access(override val file: RecordingFile = media) : RecordingAccess {
        var closed = false
        override fun open(position: Long, length: Long?) = RecordingStream(ByteArrayInputStream(byteArrayOf()), 0)
        override suspend fun copyTo(destination: OutputStream, onProgress: (Long) -> Unit) = 0L
        override fun close() { closed = true }
    }
    private class Records : RecordingsRepository {
        var deletion: suspend () -> Unit = {}
        override suspend fun deleteRecording(conferenceId: String, recordingId: String) = deletion()
        override suspend fun startRecording(conferenceId: String, mode: String): Recording = error("unused")
        override suspend fun stopRecording(conferenceId: String, recordingId: String): Recording = error("unused")
        var transcriptEnabled = true
        val leases = mutableListOf<Access>(); val offsets = mutableListOf<Int>()
        var list: suspend (String) -> RecordingPage = { RecordingPage(listOf(record()), null) }
        var page: (suspend (String, Int, Int) -> RecordingPage)? = null
        val pages = mutableListOf<Triple<String, Int, Int>>()
        val libraryPages = mutableListOf<Pair<String?, Int>>()
        var library: suspend (String?, Int) -> RecordingLibraryPage = { _, _ -> RecordingLibraryPage(listOf(RecordingLibraryItem(meeting(), record())), null) }
        val details = mutableListOf<Pair<String, String>>()
        var detail: suspend () -> Recording = { record() }
        var access: suspend () -> RecordingAccess = { Access().also(leases::add) }
        var summary: suspend () -> RecordingSummaryState = { RecordingSummaryState(true, RecordingSummary("summary", "transcript", "ready", "Private", emptyList(), emptyList(), emptyList())) }
        var summaryCalls = 0
        override suspend fun recordings(conferenceId: String, offset: Int, limit: Int): RecordingPage {
            pages += Triple(conferenceId, offset, limit)
            return page?.invoke(conferenceId, offset, limit) ?: list(conferenceId)
        }
        override suspend fun recordingLibrary(cursor: String?, limit: Int): RecordingLibraryPage {
            libraryPages += cursor to limit
            return library(cursor, limit)
        }
        override suspend fun recording(conferenceId: String, recordingId: String): Recording { details += conferenceId to recordingId; return detail() }
        override suspend fun recordingAccess(conferenceId: String, recordingId: String, fileId: String) = access()
        override suspend fun recordingTranscript(conferenceId: String, recordingId: String) = RecordingTranscriptState(transcriptEnabled, RecordingTranscript("transcript", recordingId, "ru", "ready"))
        override suspend fun recordingSegments(conferenceId: String, recordingId: String, offset: Int, limit: Int): RecordingSegmentPage {
            offsets += offset
            val ids = when (offset) { 0 -> listOf("0", "1"); 2 -> listOf("1", "2"); else -> listOf("4") }
            return RecordingSegmentPage(ids.map { RecordingSegment(it, "transcript", 0, 1, null, "text") }, 5, offset, 2)
        }
        override suspend fun recordingSummary(conferenceId: String, recordingId: String): RecordingSummaryState { summaryCalls++; return summary() }
    }
    private class Foundation : FoundationRepository {
        override val state = MutableStateFlow(RepositoryState(listOf(ServerProfile("p", "https://fixture.example.test")), "p", session = AccountSession(User("user", "member@example.test"), Capabilities(), true)))
        override suspend fun addServer(rawUrl: String) = state.value.selectedProfile!!
        override suspend fun selectServer(profileId: String) = Unit
        override suspend fun removeServer(profileId: String) = Unit
        override suspend fun login(email: String, password: String) = Unit
        override suspend fun restore() = Unit
        override suspend fun logout() = LogoutResult(true)
        override suspend fun setTheme(mode: ThemeMode) = Unit
    }
    private class Meetings : MeetingsRepository {
        val queries = mutableListOf<MeetingsQuery>()
        var page: suspend (MeetingsQuery) -> MeetingPage = { MeetingPage(listOf(meeting()), "same-cursor") }
        var conference: suspend (String) -> Meeting = { meeting().copy(id = it) }
        override suspend fun timeline(query: MeetingsQuery): MeetingPage { queries += query; return page(query) }
        override suspend fun conference(id: String) = conference.invoke(id)
        override suspend fun createMeeting(input: MeetingInput): Meeting = error("unused")
        override suspend fun scheduleMeeting(id: String, scheduledAt: Instant, plannedDurationMin: Int?): Meeting = error("unused")
        override suspend fun transitionMeeting(id: String, transition: MeetingTransition): Meeting = error("unused")
        override suspend fun membership(id: String): MeetingParticipant? = null
        override suspend fun joinMeeting(id: String, inviteCode: String?): MeetingParticipant = error("unused")
        override suspend fun joinInvite(rawCodeOrUrl: String): MeetingParticipant = error("unused")
        override suspend fun leaveMeeting(id: String): MeetingParticipant = error("unused")
        override suspend fun history(id: String): MeetingHistory = error("unused")
        override suspend fun updateDisplayName(displayName: String): User = error("unused")
    }
    companion object {
        private val at = Instant.parse("2026-10-11T12:00:00Z")
        private val media = RecordingFile("file", "final_mp4", "final.mp4", "video/mp4")
        private fun record() = Recording("record", "room", "mixed", "ready", at, files = listOf(media))
        private fun meeting() = Meeting("room", "owner", "Team", "", "", "finished", at, at)
    }
}
