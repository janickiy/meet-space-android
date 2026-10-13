package com.meetspace.app

import androidx.compose.foundation.layout.Column
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.luminance
import androidx.core.view.WindowCompat
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.DpSize
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import com.meetspace.app.ui.*
import com.meetspace.core.designsystem.MeetSpaceTheme
import com.meetspace.core.model.*
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Fake screen projections are confined to androidTest; mutations are checked through callbacks. */
@OptIn(ExperimentalTestApi::class)
class MeetingsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun returningHomeWithExistingDetailDoesNotRedirectButNewCreationOpensCalls() {
        var state by mutableStateOf(detailState(meeting))
        var navigated: WorkspaceTab? = null
        compose.setContent {
            MeetSpaceTheme(ThemeMode.DARK) {
                Column(Modifier.width(420.dp).fillMaxHeight()) {
                    MeetingsWorkspace(WorkspaceTab.HOME, state, account, MeetingsActions(), { navigated = it })
                }
            }
        }
        compose.onNodeWithTag("home-dashboard").assertIsDisplayed()
        compose.runOnIdle { assertNull(navigated); state = detailState(meeting.copy(id = "new-meeting")) }
        compose.runOnIdle { assertEquals(WorkspaceTab.CALLS, navigated) }
    }

    @Test fun joiningPreviouslyOpenedMeetingFromHomeNavigatesAfterSuccess() {
        var state by mutableStateOf(detailState(meeting))
        var navigated: WorkspaceTab? = null
        compose.setContent {
            MeetSpaceTheme(ThemeMode.DARK) {
                Column(Modifier.width(420.dp).fillMaxHeight()) {
                    MeetingsWorkspace(WorkspaceTab.HOME, state, account,
                        MeetingsActions(join = { state = state.copy(mutationRevision = 1) }), { navigated = it })
                }
            }
        }
        compose.onNodeWithTag("home-join-call").performScrollTo().performClick()
        compose.onNodeWithTag("meeting-join-input").performTextInput("existing-invite")
        compose.onNodeWithTag("meeting-join-submit").performClick()
        compose.runOnIdle { assertEquals(WorkspaceTab.CALLS, navigated) }
        compose.onNodeWithTag("meeting-join-input").assertDoesNotExist()
    }

    @Test fun createFormValidatesEmptyTitleThenSubmitsTrimmedInputAndBlocksWhileBusy() {
        var state by mutableStateOf(ready)
        var sent: MeetingInput? = null
        compose.setContent { Screen(WorkspaceTab.HOME, state, MeetingsActions(create = { sent = it })) }
        compose.onNodeWithTag("home-new-call").performScrollTo().performClick()
        compose.onNodeWithTag("meeting-form-submit").performClick()
        compose.onNodeWithTag("meeting-form-error").assertExists()
        compose.runOnIdle { assertNull(sent) }
        compose.onNodeWithTag("meeting-form-title").performTextInput("  Team planning  ")
        compose.onNodeWithTag("meeting-form-submit").performClick()
        compose.runOnIdle {
            assertEquals("Team planning", sent!!.title)
            assertNull(sent!!.scheduledAt)
            state = state.copy(actionBusy = true)
        }
        compose.onNodeWithTag("meeting-form-submit").assertIsNotEnabled()
        compose.onNodeWithTag("meeting-form-title").assertIsNotEnabled()
    }

    @Test fun scheduleFormConvertsSelectedZoneToInstantAndChecksDuration() {
        var sent: MeetingInput? = null
        compose.setContent { Screen(WorkspaceTab.HOME, ready, MeetingsActions(create = { sent = it })) }
        compose.onNodeWithTag("home-schedule").performScrollTo().performClick()
        compose.onNodeWithTag("meeting-form-title").performTextInput("Future meeting")
        replace("meeting-form-date", "2030-10-11")
        replace("meeting-form-time", "15:30")
        compose.onNodeWithTag("meeting-form-zone-toggle").performScrollTo().performClick()
        replace("meeting-form-zone", "Europe/Moscow")
        replace("meeting-form-duration", "1441")
        compose.onNodeWithTag("meeting-form-submit").performClick()
        compose.onNodeWithTag("meeting-form-error").assertExists()
        compose.runOnIdle { assertNull(sent) }
        replace("meeting-form-duration", "45")
        compose.onNodeWithTag("meeting-form-submit").performClick()
        compose.runOnIdle {
            assertEquals(Instant.parse("2030-10-11T12:30:00Z"), sent!!.scheduledAt)
            assertEquals(45, sent!!.plannedDurationMin)
        }
    }

    @Test fun ambiguousDstFormRequiresExplicitOffsetBeforeSubmitting() {
        var sent: MeetingInput? = null
        compose.setContent { Screen(WorkspaceTab.HOME, ready, MeetingsActions(create = { sent = it })) }
        compose.onNodeWithTag("home-schedule").performScrollTo().performClick()
        compose.onNodeWithTag("meeting-form-title").performTextInput("DST planning")
        replace("meeting-form-date", "2030-11-03")
        replace("meeting-form-time", "01:30")
        compose.onNodeWithTag("meeting-form-zone-toggle").performScrollTo().performClick()
        replace("meeting-form-zone", "America/New_York")
        compose.onNodeWithTag("meeting-form-offset").assertExists()
        compose.onNodeWithTag("meeting-form-submit").performClick()
        compose.runOnIdle { assertNull(sent) }
        replace("meeting-form-offset", "-05:00")
        compose.onNodeWithTag("meeting-form-submit").performClick()
        compose.runOnIdle { assertEquals(Instant.parse("2030-11-03T06:30:00Z"), sent!!.scheduledAt) }
    }

    @Test fun invitationFormSubmitsEnteredCodeAndIsDisabledWhileServerPending() {
        var state by mutableStateOf(ready)
        var code: String? = null
        compose.setContent { Screen(WorkspaceTab.HOME, state, MeetingsActions(join = { code = it })) }
        compose.onNodeWithTag("home-join-call").performScrollTo().performClick()
        compose.onNodeWithTag("meeting-join-submit").assertIsNotEnabled()
        compose.onNodeWithTag("meeting-join-input").performTextInput("  fixture-invite  ")
        compose.onNodeWithTag("meeting-join-submit").performClick()
        compose.runOnIdle { assertEquals("fixture-invite", code); state = state.copy(actionBusy = true) }
        compose.onNodeWithTag("meeting-join-input").assertIsNotEnabled()
        compose.onNodeWithTag("meeting-join-submit").assertIsNotEnabled()
    }

    @Test fun listFiltersAndPaginationInvokeTheirActualCallbacks() {
        var filter: Pair<MeetingsView, MeetingsScope>? = null
        var more = 0
        val state = ready.copy(list = MeetingsLoad(MeetingPage(listOf(meeting), "opaque-next")))
        compose.setContent { Screen(WorkspaceTab.CALLS, state, MeetingsActions(listFilter = { view, scope -> filter = view to scope }, moreList = { more++ })) }
        compose.onNodeWithTag("meeting-view-past").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(MeetingsView.PAST to MeetingsScope.ALL, filter) }
        compose.onNodeWithTag("meeting-scope-picker").performScrollTo().performClick()
        compose.onNodeWithTag("meeting-scope-owned").performClick()
        compose.runOnIdle { assertEquals(MeetingsView.UPCOMING to MeetingsScope.OWNED, filter) }
        compose.onNodeWithTag("meeting-row-${meeting.id}").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("meeting-more").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, more) }
    }

    @Test fun failedListPreservesRowsAndDoesNotMisrepresentFailureAsEmptyData() {
        val state = ready.copy(list = MeetingsLoad(MeetingPage(listOf(meeting), null), error = "Сервер недоступен."))
        compose.setContent { Screen(WorkspaceTab.CALLS, state) }
        compose.onNodeWithTag("meeting-load-error").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("meeting-row-${meeting.id}").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("В этом разделе пока нет встреч").assertDoesNotExist()
    }

    @Test fun periodDialogRejectsReversedDatesAndAppliesInclusiveCalendarSelection() {
        var period: Pair<LocalDate?, LocalDate?>? = null
        compose.setContent { Screen(WorkspaceTab.CALLS, ready, MeetingsActions(listPeriod = { start, end -> period = start to end })) }
        compose.onNodeWithTag("calls-filters").performScrollTo().performClick()
        replace("meeting-filter-from", "11.03.2030")
        replace("meeting-filter-to", "10.03.2030")
        compose.onNodeWithTag("meeting-filter-apply").assertIsNotEnabled()
        compose.runOnIdle { assertNull(period) }
        replace("meeting-filter-from", "31.02.2030")
        compose.onNodeWithTag("meeting-filter-apply").assertIsNotEnabled()
        replace("meeting-filter-from", "10.03.2030")
        compose.onNodeWithTag("meeting-filter-from").assertTextEquals("10.03.2030")
        compose.onNodeWithTag("meeting-filter-from-calendar").performClick()
        compose.onNodeWithTag("meeting-filter-from-calendar-confirm").performClick()
        compose.onNodeWithTag("meeting-filter-from").assertTextEquals("10.03.2030")
        compose.onNodeWithTag("meeting-filter-apply").performClick()
        compose.runOnIdle { assertEquals(LocalDate.of(2030, 3, 10) to LocalDate.of(2030, 3, 10), period) }
        compose.onNodeWithTag("meeting-filter-apply").assertDoesNotExist()
    }

    @Test fun compactScopePickerDoesNotCollapseInsideScrollingFiltersAt390Dp() {
        var fontScale by mutableStateOf(1f)
        var chosen: MeetingsScope? = null
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(390.dp, 844.dp))) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    MeetSpaceTheme { MeetingsWorkspace(WorkspaceTab.CALLS, ready, account,
                        MeetingsActions(listFilter = { _, scope -> chosen = scope }), {}) }
                }
            }
        }
        for (scale in listOf(1f, 1.5f)) {
            compose.runOnIdle { fontScale = scale }
            compose.onNodeWithTag("meeting-schedule").performScrollTo()
            assertReadableTextAtActualBounds(compose.onNodeWithText("Запланировать", useUnmergedTree = true), "Запланировать", singleLine = true)
            compose.onNodeWithTag("meeting-scope-picker").performScrollTo().assertIsDisplayed()
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithText("Все мои встречи", useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals("The compact filter label must stay on one line at fontScale=$scale", 1, layouts.single().lineCount)
            val bounds = compose.onNodeWithTag("meeting-scope-picker").getUnclippedBoundsInRoot()
            assertTrue("The compact filter must not push the list away at fontScale=$scale: $bounds", bounds.bottom - bounds.top <= 56.dp)
        }
        compose.onNodeWithTag("meeting-scope-picker").performClick()
        compose.onNodeWithTag("meeting-scope-owned").performClick()
        compose.runOnIdle { assertEquals(MeetingsScope.OWNED, chosen) }
    }

    @Test fun planningSwitchClearsScheduledPayloadWithoutPersistingTheForm() {
        var sent: MeetingInput? = null
        compose.setContent { Screen(WorkspaceTab.CALLS, ready, MeetingsActions(create = { sent = it })) }
        compose.onNodeWithTag("meeting-schedule").performScrollTo().performClick()
        compose.onNodeWithTag("meeting-form-title").performTextInput("Now")
        compose.onNodeWithTag("meeting-form-planned").performScrollTo().performClick()
        compose.onNodeWithTag("meeting-form-date").assertDoesNotExist()
        compose.onNodeWithTag("meeting-form-submit").performClick()
        compose.runOnIdle { assertEquals("Now", sent!!.title); assertNull(sent!!.scheduledAt); assertNull(sent!!.plannedDurationMin) }
    }

    @Test fun pendingListDoesNotShowSuccessfulEmptyState() {
        compose.setContent { Screen(WorkspaceTab.CALLS, ready.copy(list = MeetingsLoad(MeetingPage(emptyList(), null), loading = true))) }
        compose.onNodeWithText("В этом разделе пока нет встреч").assertDoesNotExist()
    }

    @Test fun calendarDayNavigationShowsLocalDayAndIncompletePageAction() {
        var chosenDay: LocalDate? = null
        var more = 0
        val date = LocalDate.of(2026, 10, 11)
        val calendarMeeting = meeting.copy(createdAt = Instant.parse("2026-10-10T22:00:00Z"))
        val feeds = MeetingsView.entries.associateWith { MeetingsLoad(MeetingPage(if (it == MeetingsView.UPCOMING) listOf(calendarMeeting) else emptyList(), if (it == MeetingsView.PAST) "another-page" else null)) }
        val state = ready.copy(calendarFeeds = feeds, calendarMonth = YearMonth.of(2026, 10), calendarDay = date, zone = ZoneId.of("Europe/Moscow"))
        compose.setContent { Screen(WorkspaceTab.CALENDAR, state, MeetingsActions(day = { chosenDay = it }, moreCalendar = { more++ })) }
        compose.onNodeWithTag("calendar-day-$date").performScrollTo().assertIsSelected().performClick()
        compose.runOnIdle { assertEquals(date, chosenDay) }
        compose.onNodeWithTag("meeting-row-${meeting.id}").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("calendar-more").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, more) }
        compose.onNodeWithTag("calendar-next").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(date.plusWeeks(1), chosenDay) }
    }

    @Test fun calendarMenuKeepsFiltersAndRefreshAccessibleWithLargeText() {
        var scope: MeetingsScope? = null
        var refreshed = 0
        var state by mutableStateOf(screenshotState)
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(430.dp, 932.dp))) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                    MeetSpaceTheme { MeetingsWorkspace(WorkspaceTab.CALENDAR, state, account,
                        MeetingsActions(calendarFilter = { scope = it }, refreshCalendar = { refreshed++ }), {}) }
                }
            }
        }
        compose.onNodeWithTag("calendar-menu").performClick()
        compose.onNodeWithTag("calendar-filters").performClick()
        compose.onNodeWithTag("meeting-scope-owned").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(MeetingsScope.OWNED, scope) }
        compose.onNodeWithTag("calendar-menu").performScrollTo().performClick()
        compose.onNodeWithTag("calendar-refresh").performClick()
        compose.runOnIdle {
            assertEquals(1, refreshed)
            state = state.copy(calendarFeeds = state.calendarFeeds.mapValues { it.value.copy(loading = true) })
        }
        compose.onNodeWithTag("calendar-menu").performClick()
        compose.onNodeWithTag("calendar-refresh").assertIsNotEnabled()
    }

    @Test fun ownerScheduledDetailsAllowScheduleAndRequireCancelConfirmation() {
        var changed: Pair<String, MeetingTransition>? = null
        compose.setContent { Screen(WorkspaceTab.CALLS, detailState(meeting.copy(status = "scheduled", scheduledAt = at.plusSeconds(3600))), MeetingsActions(transition = { id, transition -> changed = id to transition })) }
        compose.onNodeWithTag("meeting-detail-start").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("meeting-detail-edit").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("meeting-detail-join").assertDoesNotExist()
        compose.onNodeWithTag("meeting-detail-cancel").performScrollTo().performClick()
        compose.runOnIdle { assertNull(changed) }
        compose.onNodeWithTag("meeting-confirm-transition").performClick()
        compose.runOnIdle { assertEquals(meeting.id to MeetingTransition.CANCEL, changed) }
    }

    @Test fun cohostAndUnknownStatusesDoNotGrantOwnerActions() {
        var state by mutableStateOf(detailState(meeting.copy(ownerId = "someone-else", status = "active")).copy(self = MeetingsLoad(member.copy(role = "co_host", status = "joined"))))
        compose.setContent { Screen(WorkspaceTab.CALLS, state) }
        compose.onNodeWithTag("meeting-detail-finish").assertDoesNotExist()
        compose.onNodeWithTag("meeting-detail-edit").assertDoesNotExist()
        compose.onNodeWithTag("meeting-detail-leave").assertExists()
        compose.runOnIdle { state = detailState(meeting.copy(status = "future-state")) }
        compose.onNodeWithTag("meeting-detail-start").assertDoesNotExist()
        compose.onNodeWithTag("meeting-detail-join").assertDoesNotExist()
        compose.onNodeWithTag("meeting-detail-cancel").assertDoesNotExist()
    }

    @Test fun waitingMemberSeesAdmissionAndCannotJoinAsAdmitted() {
        val state = detailState(meeting.copy(ownerId = "other", status = "active")).copy(self = MeetingsLoad(member.copy(role = "participant", status = "waiting", admissionState = "waiting")))
        compose.setContent { Screen(WorkspaceTab.CALLS, state) }
        compose.onNodeWithTag("meeting-membership").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("meeting-detail-join").assertDoesNotExist()
        compose.onNodeWithTag("meeting-detail-leave").assertExists()
    }

    @Test fun accountNameValidationTrimAndBusyState() {
        var state by mutableStateOf(ready)
        var sent: String? = null
        compose.setContent { Screen(WorkspaceTab.PROFILE, state, MeetingsActions(updateProfile = { sent = it })) }
        compose.onNodeWithTag("account-save").assertIsNotEnabled()
        replace("account-name", " ")
        compose.onNodeWithTag("account-save").assertIsNotEnabled()
        replace("account-name", "A".repeat(101))
        compose.onNodeWithTag("account-save").assertIsNotEnabled()
        replace("account-name", "  Updated name  ")
        compose.onNodeWithTag("account-save").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("Updated name", sent); state = state.copy(profileBusy = true) }
        compose.onNodeWithTag("account-name").assertIsNotEnabled()
        compose.onNodeWithTag("account-save").assertIsNotEnabled()
    }

    @Test fun uncertainCreationDisablesCreateButKeepsExplicitReconciliationAction() {
        var acknowledged = 0
        compose.setContent { Screen(WorkspaceTab.HOME, ready.copy(creationUncertain = true), MeetingsActions(acknowledgeUncertainCreate = { acknowledged++ })) }
        compose.onNodeWithTag("home-new-call").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("home-schedule").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("meeting-acknowledge-uncertain").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, acknowledged) }
    }

    @Test fun compactDetailsUseModalAndTransientFormDoesNotSurviveRestoration() {
        var state by mutableStateOf(detailState(meeting))
        val restoration = StateRestorationTester(compose)
        restoration.setContent { Screen(WorkspaceTab.CALLS, state, width = 360) }
        compose.onNodeWithTag("meeting-detail-dialog").assertExists()
        screenshot("stage05-compact-details.png")
        compose.runOnIdle { state = ready }
        compose.onNodeWithTag("meeting-create").performScrollTo().performClick()
        compose.onNodeWithTag("meeting-form-title").performTextInput("Temporary private draft")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("meeting-form-title").assertDoesNotExist()
    }

    @Test fun tabletShowsModalAndRetainsOwnerActions() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        org.junit.Assume.assumeTrue("Two-pane check requires a wide device window", context.resources.configuration.screenWidthDp >= 780)
        var transition: MeetingTransition? = null
        compose.setContent {
            Screen(WorkspaceTab.CALLS, detailState(meeting), MeetingsActions(transition = { _, value -> transition = value }), width = 1000)
        }
        compose.onNodeWithTag("meeting-detail-dialog").assertExists()
        screenshot("stage05-tablet-details.png")
        compose.onNodeWithTag("meeting-detail-start").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(MeetingTransition.START, transition) }
    }

    @Test fun recordingsShortcutOpensMeetingSelectorAndHistoryOpensScopedRecordings() {
        var state by mutableStateOf(ready)
        var selected: String? = "not-called"
        compose.setContent { Screen(WorkspaceTab.CALLS, state, MeetingsActions(recordings = { selected = it })) }
        compose.onNodeWithTag("meeting-recordings").performClick()
        compose.runOnIdle {
            assertNull(selected)
            state = detailState(meeting.copy(status = "finished"))
        }
        compose.onNodeWithTag("meeting-detail-recordings").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(meeting.id, selected) }
    }

    @Test fun callSearchOnlyFiltersLoadedRowsAndKeepsPaginationAvailable() {
        var more = 0
        val state = ready.copy(list = MeetingsLoad(MeetingPage(listOf(meeting, meeting.copy(id = "other", title = "Разработка")), "next")), listView = MeetingsView.PAST)
        compose.setContent {
            MeetSpaceTheme(ThemeMode.DARK) {
                Column(Modifier.width(420.dp).fillMaxHeight()) {
                    MeetingsWorkspace(WorkspaceTab.CALLS, state, account, MeetingsActions(moreList = { more++ }), {}, callsSearchVisible = true)
                }
            }
        }
        compose.onNodeWithTag("calls-search").performScrollTo().performTextInput("Разработка")
        compose.onNodeWithTag("meeting-row-other").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("meeting-row-${meeting.id}").assertDoesNotExist()
        compose.onNodeWithTag("meeting-more").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, more) }
        compose.onNodeWithTag("calls-search").performScrollTo().performTextClearance()
        compose.onNodeWithTag("meeting-row-${meeting.id}").performScrollTo().assertIsDisplayed()
    }

    @Test fun primaryCreationAndSchedulingShareUncertainResultGuard() {
        var state by mutableStateOf(ready.copy(creationUncertain = true))
        var filter: Pair<MeetingsView, MeetingsScope>? = null
        compose.setContent { Screen(WorkspaceTab.CALLS, state, MeetingsActions(listFilter = { view, scope -> filter = view to scope })) }
        compose.onNodeWithTag("meeting-create").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("meeting-schedule").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("meeting-join").performScrollTo().assertIsEnabled()
        compose.runOnIdle { state = state.copy(listView = MeetingsView.PAST) }
        compose.onNodeWithText("Проверить список встреч").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(MeetingsView.UPCOMING to MeetingsScope.ALL, filter) }
    }

    @Test fun callsDateRowsDoNotInventDurationOrParticipants() {
        val state = ready.copy(listView = MeetingsView.PAST, list = MeetingsLoad(MeetingPage(listOf(meeting.copy(status = "finished")), null)))
        compose.setContent { Screen(WorkspaceTab.CALLS, state) }
        compose.onNodeWithTag("meeting-row-${meeting.id}").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Завершена").assertExists()
        compose.onAllNodes(hasText("мин", substring = true)).assertCountEquals(0)
        compose.onAllNodes(hasText("Участников:", substring = true)).assertCountEquals(0)
    }

    @Test fun catalogueKeepsStatusScopeAndMeetingNavigationAvailable() {
        var opened: String? = null
        compose.setContent { Screen(WorkspaceTab.CALLS, ready.copy(list = MeetingsLoad(MeetingPage(listOf(meeting), null))), MeetingsActions(open = { opened = it })) }
        compose.onNodeWithTag("calls").assertIsDisplayed()
        compose.onNodeWithTag("meeting-view-upcoming").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Открыть", substring = false).assertDoesNotExist()
        compose.onNodeWithTag("meeting-title-${meeting.id}").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(meeting.id, opened) }
        compose.onNodeWithTag("calls").assertIsDisplayed()
    }

    @Test fun meetingsStageUses780DpButCalendarSidebarRequires840Dp() {
        var width by mutableStateOf(780.dp)
        var tab by mutableStateOf(WorkspaceTab.CALLS)
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(width, 900.dp))) {
                MeetSpaceTheme { MeetingsWorkspace(tab, screenshotState, account, MeetingsActions(), {}) }
            }
        }
        compose.onNodeWithTag("calls-wide").assertIsDisplayed()
        compose.runOnIdle { width = 779.dp }
        compose.onNodeWithTag("calls-compact").assertIsDisplayed()
        compose.runOnIdle { tab = WorkspaceTab.CALENDAR; width = 780.dp }
        compose.onNodeWithTag("calendar-compact").assertExists()
        compose.runOnIdle { width = 839.dp }
        compose.onNodeWithTag("calendar-wide").assertDoesNotExist()
        compose.runOnIdle { width = 840.dp }
        compose.onNodeWithTag("calendar-wide").assertExists()
    }

    /** Explicit fixture capture only; no repository or network is used. */
    @Test fun captureCallsCalendarAndMeetingsScreenshots() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue("Opt-in design screenshots", args.getString("designScreenshots") == "true")
        val theme = args.getString("designTheme", "dark")
        require(theme in setOf("light", "dark"))
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
        compose.setContent {
            MeetSpaceTheme(if (theme == "dark") ThemeMode.DARK else ThemeMode.LIGHT) {
                val lightBars = MaterialTheme.colorScheme.background.luminance() > .5f
                SideEffect {
                    WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView).apply {
                        isAppearanceLightStatusBars = lightBars
                        isAppearanceLightNavigationBars = lightBars
                    }
                }
                FoundationUi(FoundationUiState(FoundationScreen.WORKSPACE, account),
                    onConnect = {}, onSelectProfile = {}, onRemoveProfile = {}, onLogin = { _, _ -> }, onLogout = {}, onTheme = {}, onNavigate = {}, onClearMessage = {},
                    onCallsSearch = {},
                    workspaceContent = { tab, onTab -> MeetingsWorkspace(tab, screenshotState, account, MeetingsActions(), onTab) })
            }
        }
        // Fixture navigation invokes callbacks without creating pointer press/ripple effects.
        compose.onNodeWithTag("tab-calls").performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        captureDesign("calls", theme)
        captureDesign("meetings", theme)
        compose.onNodeWithTag("calls-calendar").performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        captureDesign("calendar", theme)
        compose.onNodeWithTag("calendar-schedule").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        captureDesignDialog("schedule", theme)
    }

    private fun captureDesignDialog(screen: String, theme: String) {
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        // Dialog windows have a platform fade independent of the Compose test clock.
        android.os.SystemClock.sleep(450)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = checkNotNull(context.getExternalFilesDir("screenshots"))
        check(directory.isDirectory || directory.mkdirs())
        val configuration = context.resources.configuration
        val file = File(directory, "$screen-$theme-${configuration.screenWidthDp}x${configuration.screenHeightDp}-font${configuration.fontScale}.png")
        assertTrue(androidx.test.uiautomator.UiDevice.getInstance(instrumentation).takeScreenshot(file))
    }

    private fun captureDesign(screen: String, theme: String) {
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = checkNotNull(context.getExternalFilesDir("screenshots"))
        check(directory.isDirectory || directory.mkdirs())
        val configuration = context.resources.configuration
        val file = File(directory, "$screen-$theme-${configuration.screenWidthDp}x${configuration.screenHeightDp}-font${configuration.fontScale}.png")
        val image = compose.onNodeWithTag("workspace").captureToImage().asAndroidBitmap()
        file.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    private fun screenshot(name: String) {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val device = androidx.test.uiautomator.UiDevice.getInstance(instrumentation)
        compose.waitForIdle()
        android.os.SystemClock.sleep(450)
        assertTrue(device.takeScreenshot(java.io.File(instrumentation.targetContext.cacheDir, name)))
    }

    private fun replace(tag: String, value: String) {
        compose.onNodeWithTag(tag).performScrollTo().performTextClearance()
        compose.onNodeWithTag(tag).performTextInput(value)
    }

    @Composable private fun Screen(tab: WorkspaceTab, state: MeetingsUiState, actions: MeetingsActions = MeetingsActions(), width: Int = 360) {
        MeetSpaceTheme(ThemeMode.LIGHT) {
            Column(Modifier.width(width.dp).fillMaxHeight()) {
                MeetingsWorkspace(tab, state, account, actions, {})
            }
        }
    }

    companion object {
        private val at = Instant.parse("2026-10-11T12:00:00Z")
        private val meeting = Meeting("fixture-meeting", "current-user", "Planning", "", "", "created", at, at)
        private val member = MeetingParticipant("member", meeting.id, "current-user", "Member", "owner", "left", "admitted")
        private val account = RepositoryState(profiles = listOf(ServerProfile("fixture", "https://fixture.example.test")), selectedProfileId = "fixture", session = AccountSession(User("current-user", "member@example.test", "Original name"), Capabilities(), true))
        private val ready = MeetingsUiState(upcoming = MeetingsLoad(MeetingPage(emptyList(), null)), active = MeetingsLoad(MeetingPage(emptyList(), null)), list = MeetingsLoad(MeetingPage(emptyList(), null)), listView = MeetingsView.UPCOMING, zone = ZoneId.of("Europe/Moscow"))
        private val screenshotState = ready.copy(
            listView = MeetingsView.UPCOMING,
            list = MeetingsLoad(MeetingPage(listOf(
                meeting.copy(id = "demo-planning", title = "Дизайн-синхронизация", ownerId = "another-user", status = "scheduled", scheduledAt = Instant.parse("2026-10-12T07:00:00Z"), plannedDurationMin = 45, participantCount = 5),
                meeting.copy(id = "demo-review", title = "Обсуждение новой версии", status = "scheduled", scheduledAt = Instant.parse("2026-10-12T10:30:00Z"), plannedDurationMin = 30, participantCount = 8),
                meeting.copy(id = "demo-team", title = "Планы на неделю", status = "scheduled", scheduledAt = Instant.parse("2026-10-13T08:00:00Z"), plannedDurationMin = 30, participantCount = 4),
                meeting.copy(id = "demo-room", title = "Комната команды", status = "created", participantCount = 5),
            ), null)),
            calendarMonth = YearMonth.of(2026, 10), calendarDay = LocalDate.of(2026, 10, 12),
            calendarFeeds = MeetingsView.entries.associateWith { view -> MeetingsLoad(MeetingPage(if (view == MeetingsView.UPCOMING) listOf(
                meeting.copy(id = "demo-calendar-1", title = "Планирование недели", status = "scheduled", scheduledAt = Instant.parse("2026-10-12T07:00:00Z"), plannedDurationMin = 45),
                meeting.copy(id = "demo-calendar-2", title = "Обсуждение дизайна", status = "scheduled", scheduledAt = Instant.parse("2026-10-12T10:00:00Z"), plannedDurationMin = 30),
                meeting.copy(id = "demo-calendar-3", title = "Встреча команды", status = "scheduled", scheduledAt = Instant.parse("2026-10-12T13:00:00Z"), plannedDurationMin = 60),
            ) else emptyList(), null)) },
        )
        private fun detailState(meeting: Meeting) = ready.copy(detailId = meeting.id, detail = MeetingsLoad(meeting), self = MeetingsLoad(member))
    }
}
