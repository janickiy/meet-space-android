package com.meetspace.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.platform.app.InstrumentationRegistry
import com.meetspace.app.ui.*
import com.meetspace.core.designsystem.MeetSpaceTheme
import com.meetspace.core.model.*
import java.io.File
import java.time.Instant
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Presentation fixtures remain test-only; callbacks assert requests, not successful server effects. */
@OptIn(ExperimentalTestApi::class)
class RecordingsUiTest {
    @Test fun removedSpeechCapabilitiesHideMaterialsButKeepRecordingPlayback() {
        compose.setContent { Screen(materials.copy(capabilities = Capabilities()), RecordingsActions()) }
        compose.onNodeWithTag("recording-tab-transcript").assertDoesNotExist()
        compose.onNodeWithTag("recording-tab-summary").assertDoesNotExist()
        compose.onNodeWithTag("recording-load-transcript").assertDoesNotExist()
        compose.onNodeWithTag("recording-load-summary").assertDoesNotExist()
        compose.onNodeWithTag("recording-play-${file.id}").assertExists()
    }
    @get:Rule val compose = createComposeRule()

    @Test fun recordingActionsStayCenteredWhenTabletRotates() {
        var landscape by mutableStateOf(false)
        var opened: String? = null
        compose.setContent {
            Screen(library.copy(selfId = meeting.ownerId), RecordingsActions(openLibrary = { _, id -> opened = id }),
                width = if (landscape) 1194 else 834, height = if (landscape) 834 else 1194)
        }
        val cardTag = "recording-row-${record.id}"
        fun verticalCenterPx(tag: String): Float {
            val node = compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode()
            return node.positionInRoot.y + node.size.height / 2f
        }
        for (rotated in listOf(false, true)) {
            compose.runOnIdle { landscape = rotated }
            compose.onNodeWithTag("recordings-library").performScrollToNode(hasTestTag(cardTag))
            val center = verticalCenterPx(cardTag)
            // Two nested centering layouts can round their offsets to adjacent pixels.
            assertEquals("Open button in landscape=$rotated", center, verticalCenterPx("recording-open-${record.id}"), 1f)
            assertEquals("Delete button in landscape=$rotated", center, verticalCenterPx("recording-delete-${record.id}"), 1f)
        }
        compose.onNodeWithTag("recording-open-${record.id}").performClick()
        compose.runOnIdle { assertEquals(record.id, opened) }
    }

    @Test fun phoneUsesEqualFiltersAndConfirmsDeletionFromTheList() {
        var deleted: Recording? = null
        var opened: String? = null
        val foreign = record.copy(id = "foreign", conferenceId = "other")
        val other = meeting.copy(id = foreign.conferenceId, ownerId = "another-user")
        val state = library.copy(selfId = meeting.ownerId, library = library.library.copy(
            meetings = library.library.meetings + RecordingLibraryMeeting(other, RecordingsLoad(RecordingPage(listOf(foreign), null))),
        ))
        compose.setContent {
            Screen(state, RecordingsActions(delete = { deleted = it }, openLibrary = { _, id -> opened = id }), width = 390, height = 844)
        }
        val list = compose.onNodeWithTag("recordings-library")
        list.performScrollToNode(hasTestTag("recordings-period-filter"))
        val period = compose.onNodeWithTag("recordings-period-filter").getUnclippedBoundsInRoot()
        val sort = compose.onNodeWithTag("recordings-sort-filter").getUnclippedBoundsInRoot()
        assertEquals(period.top.value, sort.top.value, 1f)
        assertEquals(period.width.value, sort.width.value, 1f)
        assertEquals(48f, period.height.value, 1f)
        assertEquals(48f, sort.height.value, 1f)
        compose.onNodeWithTag("recordings-view-grid").assertDoesNotExist()
        compose.onNodeWithTag("recordings-view-list").assertDoesNotExist()

        list.performScrollToNode(hasTestTag("recording-row-${record.id}"))
        compose.onNodeWithTag("recording-open-${record.id}").assertDoesNotExist()
        val trash = compose.onNodeWithTag("recording-delete-${record.id}")
        trash.performClick()
        compose.onNodeWithText("Отмена").performClick()
        compose.runOnIdle { assertNull(deleted); assertNull(opened) }
        trash.performClick()
        compose.onNodeWithTag("recording-delete-confirm").performClick()
        compose.runOnIdle { assertEquals(record, deleted); assertNull(opened) }
        compose.onNodeWithText("Отмена").performClick()
        list.performScrollToNode(hasTestTag("recording-row-${foreign.id}"))
        compose.onNodeWithTag("recording-delete-${foreign.id}").assertDoesNotExist()
        compose.onNodeWithTag("recording-row-${foreign.id}").performClick()
        compose.runOnIdle { assertEquals(foreign.id, opened) }
    }

    @Test fun globalEntryShowsRealRecordingsAcrossMeetingsWithoutRequiringSelection() {
        val secondMeeting = meeting.copy(id = "second-meeting", title = "Обзор продукта")
        val secondRecord = record.copy(id = "second-recording", conferenceId = secondMeeting.id)
        var selected: Pair<String, String>? = null
        var more = 0
        compose.setContent { Screen(library.copy(library = library.library.copy(meetings = library.library.meetings +
            RecordingLibraryMeeting(secondMeeting, RecordingsLoad(RecordingPage(listOf(secondRecord), null))))),
            RecordingsActions(openLibrary = { meeting, recording -> selected = meeting to recording }, moreLibrary = { more++ }), width = 1000) }
        compose.onNodeWithTag("recordings-meetings").assertDoesNotExist()
        compose.onNodeWithTag("recordings-library").performScrollToNode(hasTestTag("recording-row-${secondRecord.id}"))
        val first = compose.onNodeWithTag("recording-row-${record.id}").getUnclippedBoundsInRoot()
        val second = compose.onNodeWithTag("recording-row-${secondRecord.id}").getUnclippedBoundsInRoot()
        assertEquals(first.left, second.left)
        assertTrue(second.top > first.top)
        compose.onNodeWithTag("recording-row-${secondRecord.id}").performClick()
        compose.onNodeWithTag("recordings-library").performScrollToNode(hasTestTag("recordings-library-more"))
        compose.onNodeWithTag("recordings-library-more").performClick()
        compose.runOnIdle { assertEquals(secondMeeting.id to secondRecord.id, selected); assertEquals(1, more) }
    }

    @Test fun libraryPartialFailureNeverClaimsEmptyAndExposesMeetingRetryAndContinuation() {
        var retry: String? = null
        var more: String? = null
        val broken = RecordingLibraryMeeting(meeting, RecordingsLoad<RecordingPage>(error = "Нет доступа к сети"))
        var state by mutableStateOf(library.copy(library = RecordingLibrary(listOf(broken), loaded = true, hasMore = false)))
        compose.setContent { Screen(state, RecordingsActions(retryLibraryMeeting = { retry = it }, moreLibraryMeeting = { more = it })) }
        compose.onNodeWithTag("recordings-library-empty").assertDoesNotExist()
        compose.onNodeWithTag("recordings-library").performScrollToNode(hasTestTag("recordings-library-retry-${meeting.id}"))
        compose.onNodeWithTag("recordings-library-retry-${meeting.id}").performClick()
        compose.runOnIdle {
            assertEquals(meeting.id, retry)
            state = state.copy(library = RecordingLibrary(listOf(broken.copy(recordings = RecordingsLoad(RecordingPage(listOf(record), 12)))), loaded = true, hasMore = false))
        }
        compose.onNodeWithTag("recordings-library").performScrollToNode(hasTestTag("recordings-library-more-${meeting.id}"))
        compose.onNodeWithTag("recordings-library-more-${meeting.id}").performClick()
        compose.runOnIdle { assertEquals(meeting.id, more) }
    }

    @Test fun libraryFiltersByPeriodSortOrderAndLiveStatus() {
        val now = Instant.now()
        val newest = record.copy(id = "newest", createdAt = now)
        val older = record.copy(id = "older", createdAt = now.minusSeconds(10L * 86400))
        val live = record.copy(id = "on-air", createdAt = now, status = "recording", files = emptyList())
        val row = RecordingLibraryMeeting(meeting, RecordingsLoad(RecordingPage(listOf(older, live, newest), null)))
        compose.setContent { Screen(library.copy(library = RecordingLibrary(listOf(row), loaded = true, hasMore = false)), width = 834) }
        val libraryList = compose.onNodeWithTag("recordings-library")
        fun choose(tag: String, label: String) {
            libraryList.performScrollToNode(hasTestTag(tag))
            compose.onNodeWithTag(tag).performClick()
            compose.onNode(hasText(label) and hasAnyAncestor(isPopup())).performClick()
        }
        fun top(id: String) = compose.onNodeWithTag("recording-row-$id").getUnclippedBoundsInRoot().top
        libraryList.performScrollToNode(hasTestTag("recording-row-older"))
        assertTrue(top(newest.id) < top(older.id))
        choose("recordings-sort-filter", "Сначала старые")
        libraryList.performScrollToNode(hasTestTag("recording-row-newest"))
        assertTrue(top(older.id) < top(newest.id))
        choose("recordings-period-filter", "За 7 дней")
        libraryList.performScrollToNode(hasTestTag("recording-row-newest"))
        compose.onNodeWithTag("recording-row-older").assertDoesNotExist()
        libraryList.performScrollToNode(hasTestTag("recordings-live"))
        compose.onNodeWithTag("recordings-live").performClick()
        libraryList.performScrollToNode(hasTestTag("recording-row-on-air"))
        compose.onNodeWithTag("recording-row-on-air").assertIsDisplayed()
        compose.onNodeWithTag("recording-row-newest").assertDoesNotExist()
    }

    @Test fun librarySearchAndLargeTextKeepCardsReachableAndPrivateQueryIsNotRestored() {
        val restoration = StateRestorationTester(compose)
        val editor = FormEditorWindow()
        restoration.setContent {
            editor.Observe()
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) { Screen(library, width = 430, dark = true) }
        }
        compose.onAllNodesWithTag("recordings-library-pair").assertCountEquals(0)
        compose.onNodeWithTag("recordings-library").performScrollToNode(hasTestTag("recording-row-${record.id}"))
        compose.onNodeWithTag("recording-row-${record.id}").assertIsDisplayed()
        compose.onNodeWithTag("recordings-library").performScrollToNode(hasTestTag("recordings-library-search"))
        compose.onNodeWithTag("recordings-library-search").performTextInput("private missing title")
        // Dismiss the editor before scrolling to content below the filters: Compose
        // idle alone does not wait for the platform IME to restore the viewport.
        compose.runOnIdle { editor.dismiss() }
        compose.waitUntil(timeoutMillis = 5_000) { editor.imeBottom.get() == 0 }
        compose.onNodeWithTag("recordings-library").performScrollToNode(hasTestTag("recordings-library-empty"))
        compose.settleFormEditorForClick(editor, compose.onNodeWithText("На загруженных страницах совпадений нет")).assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("recordings-library").performScrollToNode(hasTestTag("recordings-library-search"))
        compose.onNodeWithTag("recordings-library-search").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    }

    @Test fun meetingSelectorSearchesLoadedPagesAndRetainsPaginationAndActiveFilter() {
        var selected: String? = null
        var filter: MeetingsView? = null
        var more = 0
        compose.setContent { Screen(selector, RecordingsActions(openMeeting = { selected = it }, filterMeetings = { filter = it }, moreMeetings = { more++ })) }
        compose.onNodeWithTag("recordings-meetings").performScrollToNode(hasTestTag("recordings-search"))
        compose.onNodeWithTag("recordings-search").performTextInput("planning")
        compose.onNodeWithTag("recordings-meetings").performScrollToNode(hasTestTag("recordings-meeting-${meeting.id}"))
        compose.onNodeWithTag("recordings-meeting-${meeting.id}").performClick()
        compose.runOnIdle { assertEquals(meeting.id, selected) }
        compose.onNodeWithTag("recordings-meetings").performScrollToNode(hasTestTag("recordings-meetings-more"))
        compose.onNodeWithTag("recordings-meetings-more").performClick()
        compose.onNodeWithTag("recordings-meetings").performScrollToNode(hasTestTag("recordings-view-active"))
        compose.onNodeWithTag("recordings-view-active").performClick()
        compose.runOnIdle { assertEquals(1, more); assertEquals(MeetingsView.ACTIVE, filter) }
    }

    @Test fun searchIsExplicitlyPageScopedAndPrivateQueryIsNotRestored() {
        val restore = StateRestorationTester(compose)
        restore.setContent { Screen(selector) }
        compose.onNodeWithTag("recordings-search").performTextInput("private missing meeting")
        compose.onNodeWithTag("recordings-meetings").performScrollToNode(hasTestTag("recordings-meetings-empty"))
        compose.onNodeWithText("На загруженных страницах совпадений нет").assertExists()
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("recordings-search").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    }

    @Test fun onlyReadyAvailableFinalMediaIsShownAndOffsetContinuationRemainsAccessible() {
        var selected: String? = null
        var more = 0
        val items = listOf(record, record.copy(id = "processing", status = "processing"), record.copy(id = "missing", files = listOf(file.copy(available = false))), record.copy(id = "raw", files = listOf(file.copy(type = "raw_video"))))
        compose.setContent { Screen(list.copy(recordings = RecordingsLoad(RecordingPage(items, 20))), RecordingsActions(open = { selected = it }, moreRecordings = { more++ })) }
        compose.onNodeWithTag("recordings-list").performScrollToNode(hasTestTag("recording-row-${record.id}"))
        compose.onNodeWithTag("recording-row-${record.id}").performClick()
        compose.onNodeWithTag("recording-row-processing").assertDoesNotExist()
        compose.onNodeWithTag("recording-row-missing").assertDoesNotExist()
        compose.onNodeWithTag("recording-row-raw").assertDoesNotExist()
        compose.onNodeWithTag("recordings-list").performScrollToNode(hasTestTag("recordings-more"))
        compose.onNodeWithTag("recordings-more").performClick()
        compose.runOnIdle { assertEquals(record.id, selected); assertEquals(1, more) }
    }

    @Test fun failedRefreshRetainsReadyRowsAndDoesNotClaimEmpty() {
        var refresh = 0
        compose.setContent { Screen(list.copy(recordings = RecordingsLoad(RecordingPage(listOf(record), null), error = "Сервер недоступен.")), RecordingsActions(refreshRecordings = { refresh++ })) }
        compose.onNodeWithTag("recordings-list").performScrollToNode(hasText("Сервер недоступен."))
        compose.onNodeWithText("Повторить").performClick()
        compose.onNodeWithTag("recordings-list").performScrollToNode(hasTestTag("recording-row-${record.id}"))
        compose.onNodeWithTag("recording-row-${record.id}").assertIsDisplayed()
        compose.onNodeWithTag("recordings-empty").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, refresh) }
    }

    @Test fun meetingMaterialsOpenTheAvailableTracksArchiveFromTheCard() {
        val archive = RecordingFile("tracks", "tracks_archive", "meeting-tracks.zip", "application/zip", 4096)
        val tracks = record.copy(mode = "individual_tracks", files = listOf(archive))
        var downloaded: Pair<Recording, RecordingFile>? = null
        var state by mutableStateOf(list.copy(selfId = "guest", recordings = RecordingsLoad(RecordingPage(listOf(tracks), null))))
        compose.setContent {
            Screen(state, RecordingsActions(
                open = { state = state.copy(detailId = it, detail = RecordingsLoad(tracks)) },
                download = { recording, file -> downloaded = recording to file },
            ), width = 1194)
        }
        compose.onNodeWithTag("recordings-list").performScrollToNode(hasTestTag("recording-row-${tracks.id}"))
        compose.onNodeWithTag("recording-delete-${tracks.id}").assertDoesNotExist()
        compose.onNodeWithTag("recording-open-${tracks.id}").assertTextContains("Материалы").performClick()
        scrollDetail("recording-download-${archive.id}")
        compose.onNodeWithTag("recording-download-${archive.id}").performClick()
        compose.runOnIdle { assertEquals(tracks to archive, downloaded) }
    }

    @Test fun tabletCentersDetailAndResizeKeepsTheSameSelection() {
        var width by mutableStateOf(1000)
        compose.setContent { Screen(detail, width = width) }
        compose.onNodeWithTag("recordings-two-pane").assertDoesNotExist()
        compose.onNodeWithTag("recordings-single-pane").assertExists()
        compose.onNodeWithTag("recordings-list").assertDoesNotExist()
        compose.onNodeWithTag("recording-detail").assertIsDisplayed()
        val detailBounds = compose.onNodeWithTag("recording-detail").getUnclippedBoundsInRoot()
        assertTrue(detailBounds.right - detailBounds.left <= 1140.dp)
        capture("stage08-recordings-tablet.png")
        compose.runOnIdle { width = 360 }
        scrollDetail("recording-play-${file.id}")
        compose.onNodeWithTag("recording-play-${file.id}").assertIsDisplayed()
    }

    @Test fun meetingMaterialsUseListByDefaultAndGridAdaptsToTheTabletAndPhone() {
        var width by mutableIntStateOf(834)
        var selected: String? = null
        val second = record.copy(id = "second")
        val third = record.copy(id = "third")
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            Screen(list.copy(recordings = RecordingsLoad(RecordingPage(listOf(record, second, third), null))),
                RecordingsActions(open = { selected = it }), width = width, height = 1194)
        }
        val scroll = compose.onNodeWithTag("recordings-list")
        fun bounds(id: String) = compose.onNodeWithTag("recording-row-$id").getUnclippedBoundsInRoot()
        scroll.performScrollToNode(hasTestTag("recordings-view-list"))
        compose.onNodeWithTag("recordings-view-list").assertIsSelected()
        scroll.performScrollToNode(hasTestTag("recording-row-${second.id}"))
        assertEquals(bounds(record.id).left, bounds(second.id).left)
        assertTrue(bounds(record.id).top < bounds(second.id).top)

        scroll.performScrollToNode(hasTestTag("recordings-view-grid"))
        compose.onNodeWithTag("recordings-view-grid").performClick().assertIsSelected()
        scroll.performScrollToNode(hasTestTag("recording-row-${record.id}"))
        assertEquals(bounds(record.id).top, bounds(second.id).top)
        assertTrue(bounds(record.id).left < bounds(second.id).left)
        assertEquals(bounds(record.id).left, bounds(third.id).left)
        assertTrue(bounds(second.id).top < bounds(third.id).top)

        compose.runOnIdle { width = 1194 }
        restoration.emulateSavedInstanceStateRestore()
        scroll.performScrollToNode(hasTestTag("recordings-view-grid"))
        compose.onNodeWithTag("recordings-view-grid").assertIsSelected()
        scroll.performScrollToNode(hasTestTag("recording-row-${third.id}"))
        assertEquals(bounds(record.id).top, bounds(third.id).top)
        assertTrue(bounds(second.id).left < bounds(third.id).left)
        compose.onNodeWithTag("recording-row-${third.id}").performClick()
        compose.runOnIdle { assertEquals(third.id, selected); width = 390 }
        compose.onNodeWithTag("recordings-view-grid").assertDoesNotExist()
        compose.onNodeWithTag("recordings-view-list").assertDoesNotExist()
        scroll.performScrollToNode(hasTestTag("recording-row-${second.id}"))
        assertEquals(bounds(record.id).left, bounds(second.id).left)
        assertTrue(bounds(record.id).top < bounds(second.id).top)

        compose.runOnIdle { width = 834 }
        scroll.performScrollToNode(hasTestTag("recordings-view-grid"))
        compose.onNodeWithTag("recordings-view-grid").assertIsSelected()
    }

    @Test fun meetingFilterStartsAtOpenedMeetingAndAllMeetingsOpensTheCatalogue() {
        val otherMeeting = meeting.copy(id = "other", title = "Другая встреча")
        val otherRecord = record.copy(id = "other-recording", conferenceId = otherMeeting.id)
        val catalogue = library.copy(library = library.library.copy(meetings = library.library.meetings +
            RecordingLibraryMeeting(otherMeeting, RecordingsLoad(RecordingPage(listOf(otherRecord), null)))))
        var state by mutableStateOf(list)
        var allRequests = 0
        var opened: Pair<String, String>? = null
        compose.setContent { Screen(state, RecordingsActions(
            allRecordings = { allRequests++; state = catalogue },
            openLibrary = { meetingId, recordId -> opened = meetingId to recordId },
        ), width = 834) }
        compose.onNodeWithTag("recordings-list").performScrollToNode(hasTestTag("recordings-meeting-filter"))
        compose.onNodeWithTag("recordings-meeting-filter").assert(hasText(meeting.title)).performClick()
        compose.onNodeWithText("Все встречи").performClick()
        compose.runOnIdle { assertEquals(1, allRequests) }
        val libraryList = compose.onNodeWithTag("recordings-library")
        libraryList.performScrollToNode(hasTestTag("recordings-meeting-filter"))
        compose.onNodeWithTag("recordings-meeting-filter").assert(hasText("Все встречи")).performClick()
        compose.onNode(hasText(otherMeeting.title) and hasAnyAncestor(isPopup())).performClick()
        libraryList.performScrollToNode(hasTestTag("recording-row-${otherRecord.id}"))
        compose.onNodeWithTag("recording-row-${record.id}").assertDoesNotExist()
        compose.onNodeWithTag("recording-row-${otherRecord.id}").performClick()
        compose.runOnIdle { assertEquals(otherMeeting.id to otherRecord.id, opened) }
    }

    @Test fun recordingsKeepCallsSelectedAndNavigationUsesTheRequestedDestination() {
        var requested: WorkspaceTab? = null
        compose.setContent { MeetSpaceTheme { RecordingsWorkspace(list, RecordingsActions(), onWorkspaceTab = { requested = it }) } }
        compose.onNodeWithTag("tab-calls").assertIsSelected()
        compose.onNodeWithTag("tab-messages").performClick()
        compose.runOnIdle { assertEquals(WorkspaceTab.MESSAGES, requested) }
    }

    @Test fun playAndDownloadRequestSelectedFileAndBlockDuplicateDownload() {
        var state by mutableStateOf(detail)
        var played: Pair<Recording, RecordingFile>? = null
        var downloaded: Pair<Recording, RecordingFile>? = null
        compose.setContent { Screen(state, RecordingsActions(play = { record, file -> played = record to file }, download = { record, file -> downloaded = record to file })) }
        scrollDetail("recording-play-${file.id}")
        compose.onNodeWithTag("recording-play-${file.id}").assertHeightIsAtLeast(48.dp).performClick()
        scrollDetail("recording-download-${file.id}")
        compose.onNodeWithTag("recording-download-${file.id}").assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(record to file, played); assertEquals(record to file, downloaded); state = state.copy(download = RecordingDownloadUi("meeting.mp4", 512, 1024)) }
        scrollDetail("recording-download-${file.id}")
        compose.onNodeWithTag("recording-download-${file.id}").assertIsNotEnabled()
    }

    @Test fun unavailableDetailOffersNoPlayOrDownloadEvenWhenOldFilesExist() {
        compose.setContent { Screen(detail.copy(detail = RecordingsLoad(record.copy(status = "failed")))) }
        scrollDetail("recording-unavailable")
        compose.onNodeWithTag("recording-play-${file.id}").assertDoesNotExist()
        compose.onNodeWithTag("recording-download-${file.id}").assertDoesNotExist()
        compose.onNodeWithText("failed").assertDoesNotExist()
    }

    @Test fun materialsAreReadOnlyAndShowSegmentsSummaryAndNextPage() {
        var more = 0
        compose.setContent { Screen(materials, RecordingsActions(moreSegments = { more++ })) }
        scrollDetail("recording-segment-segment")
        compose.onNodeWithText("Обсудили следующий выпуск.").assertIsDisplayed()
        scrollDetail("recording-segments-more")
        compose.onNodeWithTag("recording-segments-more").performClick()
        selectSummary()
        scrollDetail("recording-summary")
        compose.onNodeWithTag("recording-summary").assertTextEquals("Команда согласовала план.")
        compose.runOnIdle { assertEquals(1, more) }
    }

    @Test fun unavailableMaterialsExplainServerSupportAndNeverExposePipelineStates() {
        val state = detail.copy(capabilities = Capabilities(transcription = true, aiSummary = true), transcript = RecordingsLoad(RecordingTranscriptState(false, null)), summary = RecordingsLoad(RecordingSummaryState(true, RecordingSummary("sum", "trans", "processing", "private incomplete", emptyList(), emptyList(), emptyList()))))
        compose.setContent { Screen(state) }
        compose.onNodeWithTag("recording-detail").performScrollToNode(hasText("Расшифровки отключены на сервере."))
        compose.onNodeWithText("Расшифровки отключены на сервере.").assertIsDisplayed()
        selectSummary()
        compose.onNodeWithTag("recording-detail").performScrollToNode(hasText("Готовых итогов пока нет."))
        compose.onNodeWithText("Готовых итогов пока нет.").assertIsDisplayed()
        compose.onNodeWithText("private incomplete").assertDoesNotExist()
    }

    @Test fun historicalReadyMaterialsRemainReadableWhenNewGenerationIsDisabled() {
        compose.setContent { Screen(materials.copy(transcript = RecordingsLoad(materials.transcript.data!!.copy(enabled = false)), summary = RecordingsLoad(materials.summary.data!!.copy(enabled = false)))) }
        scrollDetail("recording-segment-segment")
        compose.onNodeWithText("Обсудили следующий выпуск.").assertIsDisplayed()
        selectSummary()
        scrollDetail("recording-summary")
        compose.onNodeWithTag("recording-summary").assertTextEquals("Команда согласовала план.")
        compose.onNodeWithText("Итоги встреч отключены на сервере.").assertDoesNotExist()
    }

    @Test fun largeFontDarkLandscapeKeepsDownloadCancelReachableAndBackAccessible() {
        var cancel = 0
        var back = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                Screen(detail.copy(download = RecordingDownloadUi("Запись встречи с длинным названием.mp4", 1024, 4096)), RecordingsActions(cancelDownload = { cancel++ }, close = { back++ }), width = 600, height = 280, dark = true)
            }
        }
        compose.onNodeWithTag("recordings-single-pane").assertExists()
        scrollDetail("recording-download-cancel")
        compose.onNodeWithTag("recording-download-cancel").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        capture("stage08-recordings-landscape-dark-large-font.png")
        compose.onNodeWithContentDescription("Назад к встречам").performClick()
        compose.runOnIdle { assertEquals(1, cancel); assertEquals(1, back) }
    }

    @Test fun libraryLinkReturnsToAllRecordingsInOneClickWhileInlinePlayerIsOpen() {
        var closed = 0
        var playerBack = 0
        compose.setContent {
            MeetSpaceTheme(ThemeMode.LIGHT) {
                RecordingsWorkspace(detail.copy(detailFromLibrary = true),
                    RecordingsActions(allRecordings = { closed++ }, back = { playerBack++ }),
                    playingFileId = file.id,
                    inlinePlayer = { androidx.compose.material3.Text("Inline playback") })
            }
        }
        compose.onNodeWithTag("recordings-all").performClick()
        compose.runOnIdle { assertEquals(1, closed); assertEquals(0, playerBack) }
    }

    @Test fun keyboardCanActivateARecordingWithoutTouch() {
        var opened: String? = null
        lateinit var inputMode: InputModeManager
        compose.setContent {
            inputMode = LocalInputModeManager.current
            Screen(list, RecordingsActions(open = { opened = it }))
        }
        compose.onNodeWithTag("recordings-list").performScrollToNode(hasTestTag("recording-row-${record.id}"))
        // KeyInjectionScope injects directly into Compose; unlike a hardware key it does not
        // switch the Android window out of touch mode. Match the official API sample first.
        compose.runOnIdle { assertTrue(inputMode.requestInputMode(InputMode.Keyboard)) }
        try {
            compose.onNodeWithTag("recording-row-${record.id}").performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
            compose.onNodeWithTag("recording-row-${record.id}").assertIsFocused().performKeyInput { keyDown(Key.Enter) }
            compose.runOnIdle { assertNull(opened) }
            compose.onNodeWithTag("recording-row-${record.id}").performKeyInput { keyUp(Key.Enter) }
            compose.runOnIdle { assertEquals(record.id, opened) }
        } finally { compose.runOnIdle { inputMode.requestInputMode(InputMode.Touch) } }
    }

    private fun scrollDetail(tag: String) { compose.onNodeWithTag("recording-detail").performScrollToNode(hasTestTag(tag)) }
    private fun selectSummary() {
        scrollDetail("recording-tab-summary")
        compose.onNodeWithTag("recording-tab-summary").performClick()
    }
    private fun capture(name: String) {
        val bitmap = compose.onNodeWithTag("recordings-workspace").captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Composable private fun Screen(state: RecordingsUiState, actions: RecordingsActions = RecordingsActions(), width: Int = 360, height: Int? = null, dark: Boolean = false) {
        DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(width.dp, (height ?: 900).dp))) {
            MeetSpaceTheme(if (dark) ThemeMode.DARK else ThemeMode.LIGHT) { RecordingsWorkspace(state, actions) }
        }
    }
    companion object {
        private val at = Instant.parse("2026-10-11T09:00:00Z")
        private val meeting = Meeting("meeting", "owner", "Planning команды и выпуска", "", "", "finished", at, at)
        private val file = RecordingFile("video", "final_mp4", "meeting.mp4", "video/mp4", 5242880)
        private val record = Recording("recording", meeting.id, "mixed", "ready", at, durationSec = 2502, files = listOf(file))
        private val library = RecordingsUiState(visible = true, library = RecordingLibrary(listOf(RecordingLibraryMeeting(meeting, RecordingsLoad(RecordingPage(listOf(record), null)))), loaded = true))
        private val selector = RecordingsUiState(visible = true, meetingPicker = true, meetings = RecordingsLoad(MeetingPage(listOf(meeting), "next")))
        private val list = selector.copy(meetingId = meeting.id, meeting = RecordingsLoad(meeting), recordings = RecordingsLoad(RecordingPage(listOf(record), null)))
        private val detail = list.copy(detailId = record.id, detail = RecordingsLoad(record))
        private val materials = detail.copy(capabilities = Capabilities(transcription = true, aiSummary = true), transcript = RecordingsLoad(RecordingTranscriptState(true, RecordingTranscript("trans", record.id, "ru", "ready"))), segments = RecordingsLoad(RecordingSegmentPage(listOf(RecordingSegment("segment", "trans", 1000, 2000, "Анна", "Обсудили следующий выпуск.")), 2, 0, 1)), summary = RecordingsLoad(RecordingSummaryState(true, RecordingSummary("sum", "trans", "ready", "Команда согласовала план.", listOf("Подготовить выпуск"), listOf(RecordingActionItem("Обновить документацию", "Анна", null)), listOf("Релиз")))))
    }
}
