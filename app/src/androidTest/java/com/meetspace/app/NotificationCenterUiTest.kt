package com.meetspace.app

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.meetspace.app.ui.*
import com.meetspace.core.designsystem.MeetSpaceTheme
import com.meetspace.core.model.InboxNotification
import com.meetspace.core.model.AccountSession
import com.meetspace.core.model.Capabilities
import com.meetspace.core.model.ChatConversation
import com.meetspace.core.model.MeetingPage
import com.meetspace.core.model.Meeting
import com.meetspace.core.model.RepositoryState
import com.meetspace.core.model.ServerProfile
import com.meetspace.core.model.ThemeMode
import com.meetspace.core.model.User
import java.io.File
import java.time.Instant
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Prototype content stays in test fixtures; production renders the server's notification projection. */
@OptIn(ExperimentalTestApi::class)
class NotificationCenterUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun bellAnnouncesServerCountCapsVisualBadgeAndRemovesZeroBadge() {
        var unread by mutableStateOf(120L)
        var expanded by mutableStateOf(false)
        compose.setContent { MeetSpaceTheme { NotificationBell(unread, expanded, { expanded = true }) } }
        compose.onNodeWithTag("notification-bell-count", useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription("Уведомления, непрочитанных: 120").assertExists().performClick()
        compose.runOnIdle { assertTrue(expanded); unread = 0 }
        compose.onNodeWithTag("notification-bell-count").assertDoesNotExist()
        compose.onNodeWithContentDescription("Уведомления, всё прочитано").assertExists()
    }

    @Test fun filtersAndReadAllUseCallbacksAndServerWideCount() {
        var state by mutableStateOf(ready.copy(notificationUnread = 87))
        var selected: NotificationFilter? = null
        var readAll = 0
        compose.setContent { MeetSpaceTheme {
            NotificationCenter(state, MessagingActions(
                notificationFilter = { filter ->
                    selected = filter
                    state = state.copy(notificationFilter = filter,
                        notifications = MessagingLoad(if (filter == NotificationFilter.UNREAD) events.filter { it.readAt == null } else events))
                }, readAllNotifications = { readAll++; state = state.copy(notificationUnread = 0, notifications = MessagingLoad(emptyList())) },
            ), {}, {})
        } }
        compose.onNodeWithTag("notification-total").assertTextContains("87 новых")
        compose.onNodeWithTag("notification-filter-unread").performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(NotificationFilter.UNREAD, selected) }
        compose.onNodeWithTag("notification-event-read").assertDoesNotExist()
        compose.onNodeWithTag("notification-read-all").performClick().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, readAll) }
        compose.onNodeWithTag("notification-total").assertTextContains("Всё прочитано")
        compose.onNodeWithTag("notification-empty").assertIsDisplayed()
    }

    @Test fun eventSettingsAndCloseHaveSeparateCallbacks() {
        var show by mutableStateOf(true)
        var opened: InboxNotification? = null
        var settings = 0
        var dismissed = 0
        compose.setContent { MeetSpaceTheme {
            if (show) NotificationCenter(ready, MessagingActions(openNotification = { opened = it }),
                { dismissed++; show = false }, { settings++ })
        } }
        compose.onNodeWithTag("notification-event-reminder").performClick()
        compose.runOnIdle { assertEquals("meeting", opened?.conferenceId); assertEquals(1, dismissed); show = true }
        compose.onNodeWithTag("notification-settings").performClick()
        compose.runOnIdle { assertEquals(1, settings); assertEquals(2, dismissed); show = true }
        compose.onNodeWithTag("notification-close").performClick()
        compose.runOnIdle { assertEquals(1, settings); assertEquals(3, dismissed) }
        compose.onNodeWithTag("notification-center").assertDoesNotExist()
    }

    @Test fun unreadPaginationDoesNotClaimEverythingReadUntilLastPageAndAllowsRetry() {
        var state by mutableStateOf(ready.copy(notificationFilter = NotificationFilter.UNREAD,
            notifications = MessagingLoad(emptyList()), notificationCursor = "older"))
        var more = 0
        var retry = 0
        compose.setContent { MeetSpaceTheme {
            NotificationCenter(state, MessagingActions(moreNotifications = { more++ }, loadNotifications = { retry++ }), {}, {})
        } }
        compose.onNodeWithText("Здесь всё прочитано").assertIsDisplayed()
        compose.onNodeWithTag("notification-more").performClick()
        compose.runOnIdle { assertEquals(1, more); state = state.copy(notifications = MessagingLoad(error = "Нет связи")) }
        compose.onNodeWithTag("notification-retry").performClick()
        compose.runOnIdle { assertEquals(1, retry); state = state.copy(notificationBusy = true) }
        compose.onNodeWithTag("notification-read-all").assertIsNotEnabled()
        compose.onNodeWithTag("notification-filter-all").assertIsNotEnabled()
        compose.onNodeWithTag("notification-loading").assertExists()
    }

    @Test fun failedMaterialEventsNeverClaimTheMaterialIsReady() {
        val failures = listOf(
            InboxNotification("summary", "summary.failed", now, conferenceId = "meeting"),
            InboxNotification("transcript", "transcript.failed", now, conferenceId = "meeting"),
        )
        compose.setContent { MeetSpaceTheme {
            NotificationCenter(ready.copy(notifications = MessagingLoad(failures)), MessagingActions(), {}, {})
        } }
        compose.onNodeWithText("Не удалось подготовить итоги", substring = true).assertExists()
        compose.onNodeWithText("Не удалось подготовить расшифровку", substring = true).assertExists()
        compose.onNodeWithText("Итоги встречи готовы", substring = true).assertDoesNotExist()
    }

    @Test fun phoneTabletThemesAndLargeTextKeepControlsVisibleAndListScrollable() {
        var size by mutableStateOf(DpSize(390.dp, 844.dp))
        var fontScale by mutableStateOf(1f)
        var theme by mutableStateOf(ThemeMode.LIGHT)
        val many = (0..19).map { events.first().copy(id = "event-$it", createdAt = now.minusSeconds(it * 3_600L)) }
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(size)) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    MeetSpaceTheme(theme) {
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                            NotificationCenter(ready.copy(notifications = MessagingLoad(many)), MessagingActions(), {}, {},
                                meetingTitles = mapOf("meeting" to "Обсуждение новой версии приложения и планирование следующего квартала"))
                        }
                    }
                }
            }
        }
        for (viewport in listOf(DpSize(390.dp, 844.dp), DpSize(1194.dp, 834.dp))) {
            for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) for (scale in listOf(1f, 1.5f)) {
                compose.runOnIdle { size = viewport; theme = mode; fontScale = scale }
                compose.onNodeWithTag("notification-close").assertIsDisplayed()
                compose.onNodeWithTag("notification-filter-all").assertIsDisplayed()
                compose.onNodeWithTag("notification-filter-unread").assertIsDisplayed()
                compose.onNodeWithTag("notification-read-all").assertIsDisplayed()
                compose.onNodeWithTag("notification-settings").assertIsDisplayed()
                val panel = compose.onNodeWithTag("notification-center").getUnclippedBoundsInRoot()
                val overlay = compose.onNodeWithTag("notification-overlay").getUnclippedBoundsInRoot()
                assertTrue("Panel is bounded by overlay: $panel / $overlay", panel.left >= overlay.left && panel.right <= overlay.right && panel.bottom <= overlay.bottom)
                val panelNode = compose.onNodeWithTag("notification-center").fetchSemanticsNode()
                val maxPanelPixels = with(panelNode.layoutInfo.density) { 400.dp.roundToPx() }
                // ForcedSize can use fractional density. Compare the same rounded pixel
                // constraint as Modifier.width, not two subtracted floating-point dp positions.
                assertTrue("Panel ${panelNode.size.width}px exceeds 400dp=$maxPanelPixels px at $viewport/$scale ($panel)",
                    panelNode.size.width <= maxPanelPixels)
                val close = compose.onNodeWithTag("notification-close").fetchSemanticsNode()
                val touchPixels = with(close.layoutInfo.density) { 48.dp.roundToPx() }
                assertTrue(close.size.width >= touchPixels && close.size.height >= touchPixels)
                compose.onNodeWithTag("notification-events").performScrollToNode(hasTestTag("notification-event-event-19"))
                compose.onNodeWithTag("notification-event-event-19").assertIsDisplayed()
                compose.onNodeWithTag("notification-settings").assertIsDisplayed()
            }
        }
    }

    @Test fun meetingAndChatHeaderBellsFollowExistingActionsWithoutOverlappingAtAnyWidth() {
        var size by mutableStateOf(DpSize(390.dp, 844.dp))
        var scale by mutableStateOf(1f)
        var chats by mutableStateOf(false)
        var clicks = 0
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(size)) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                    MeetSpaceTheme(ThemeMode.LIGHT) {
                        val bell: @Composable () -> Unit = { NotificationBell(3, false, { clicks++ }) }
                        if (chats) MessagingWorkspace(ready, MessagingActions(), notificationBell = bell)
                        else MeetingsWorkspace(WorkspaceTab.CALLS,
                            MeetingsUiState(list = MeetingsLoad(MeetingPage(emptyList(), null))),
                            RepositoryState(profiles = listOf(ServerProfile("server", "https://fixture.example.test")),
                                selectedProfileId = "server", session = AccountSession(User("user", "test@example.test", "Имя"), Capabilities(), true)),
                            MeetingsActions(), {}, notificationBell = bell)
                    }
                }
            }
        }
        for (width in listOf(320.dp, 390.dp, 1194.dp)) for (font in listOf(1f, 1.5f)) for (showChats in listOf(false, true)) {
            compose.runOnIdle { size = DpSize(width, 844.dp); scale = font; chats = showChats }
            compose.onNodeWithTag("notification-bell").assertIsDisplayed().performClick()
            compose.onNodeWithTag("notification-bell-count", useUnmergedTree = true).assertExists()
            val header = compose.onNodeWithTag(if (showChats) "messaging-header" else "meetings-header").getUnclippedBoundsInRoot()
            val bell = compose.onNodeWithTag("notification-bell").getUnclippedBoundsInRoot()
            val previous = compose.onNodeWithTag(if (showChats) "messaging-search-toggle" else "meeting-recordings").getUnclippedBoundsInRoot()
            assertTrue("Bell follows previous header action at $width/$font: $previous / $bell", bell.left >= previous.right)
            assertTrue("Bell stays within header", bell.right <= header.right && bell.bottom <= header.bottom)
            assertTrue("Bell has full touch target", bell.right - bell.left >= 48.dp && bell.bottom - bell.top >= 48.dp)
            val title = compose.onNodeWithText(if (showChats) "Чаты" else "Встречи", useUnmergedTree = true).getUnclippedBoundsInRoot()
            val firstAction = compose.onNodeWithTag(if (showChats) "messaging-search-toggle" else "calls-calendar").getUnclippedBoundsInRoot()
            assertTrue("Header title never overlaps actions", title.right <= firstAction.left)
        }
        compose.runOnIdle { assertEquals(12, clicks) }
    }

    @Test fun notificationToAlreadySelectedChatClosesFullScreenFolders() {
        var requestFolders by mutableStateOf(true)
        var navigationRevision by mutableStateOf(0)
        val selected = ready.copy(selectedId = "chat", detail = MessagingLoad(ChatConversation("chat", "group", name = "Команда")),
            messages = MessagingLoad(emptyList()), folders = MessagingLoad(emptyList()))
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(390.dp, 844.dp))) {
                MeetSpaceTheme(ThemeMode.LIGHT) {
                    MessagingWorkspace(selected, MessagingActions(), foldersRequested = requestFolders,
                        consumeWorkspaceRequest = { requestFolders = false }, conversationNavigationRevision = navigationRevision)
                }
            }
        }
        compose.onNodeWithTag("folders-workspace").assertIsDisplayed()
        compose.runOnIdle { assertFalse(requestFolders); navigationRevision++ }
        compose.onNodeWithTag("folders-workspace").assertDoesNotExist()
        compose.onNodeWithTag("conversation-detail").assertIsDisplayed()
        compose.onNodeWithTag("message-draft").assertIsDisplayed()
    }

    @Test fun shortLandscapeAndSplitWindowWithLargeTextKeepEveryActionReachable() {
        var size by mutableStateOf(DpSize(844.dp, 390.dp))
        var scale by mutableStateOf(1.5f)
        var mode by mutableStateOf(ThemeMode.LIGHT)
        var readAll = 0
        var settings = 0
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(size)) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                    MeetSpaceTheme(mode) {
                        NotificationCenter(ready, MessagingActions(readAllNotifications = { readAll++ }), {}, { settings++ })
                    }
                }
            }
        }
        for (viewport in listOf(DpSize(844.dp, 390.dp), DpSize(390.dp, 390.dp))) for (font in listOf(1.5f, 2f)) {
            compose.runOnIdle { size = viewport; scale = font; mode = if (font == 2f) ThemeMode.DARK else ThemeMode.LIGHT }
            fun reveal(tag: String) {
                if (!compose.onNodeWithTag(tag).isDisplayed()) compose.onNodeWithTag("notification-events").performScrollToNode(hasTestTag(tag))
                compose.onNodeWithTag(tag).assertIsDisplayed()
            }
            reveal("notification-event-recording")
            reveal("notification-read-all")
            compose.onNodeWithTag("notification-read-all").performClick()
            reveal("notification-filter-unread")
            reveal("notification-settings")
            compose.onNodeWithTag("notification-settings").performClick()
            reveal("notification-close")
            val panel = compose.onNodeWithTag("notification-center").fetchSemanticsNode().boundsInRoot
            val overlay = compose.onNodeWithTag("notification-overlay").fetchSemanticsNode().boundsInRoot
            assertTrue("Short-window panel remains inside overlay", panel.left >= overlay.left && panel.right <= overlay.right && panel.bottom <= overlay.bottom)
        }
        compose.runOnIdle { assertEquals(4, readAll); assertEquals(4, settings) }
    }

    @Test fun captureNotificationDesignFixtures() {
        assumeTrue("Opt-in notification screenshots", InstrumentationRegistry.getArguments().getString("notificationScreenshots") == "true")
        val actualWindow = InstrumentationRegistry.getArguments().getString("actualWindow") == "true"
        var size by mutableStateOf(DpSize(390.dp, 844.dp))
        var theme by mutableStateOf(ThemeMode.LIGHT)
        var anchor by mutableStateOf<IntRect?>(null)
        val meeting = Meeting("meeting", "user", "Дизайн-синхронизация", "", "", "scheduled", now, now,
            scheduledAt = now.plusSeconds(1_200), plannedDurationMin = 45, participantCount = 5)
        val meetings = MeetingsUiState(list = MeetingsLoad(MeetingPage(listOf(meeting), null)))
        compose.setContent {
            val content: @Composable () -> Unit = {
                MeetSpaceTheme(theme) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Box {
                            MeetingsWorkspace(WorkspaceTab.CALLS, meetings, RepositoryState(), MeetingsActions(), {},
                                notificationBell = { NotificationBell(3, true, {}, Modifier.onGloballyPositioned {
                                    val rect = it.boundsInWindow()
                                    anchor = IntRect(rect.left.roundToInt(), rect.top.roundToInt(), rect.right.roundToInt(), rect.bottom.roundToInt())
                                }) })
                            NotificationCenter(ready, MessagingActions(), {}, {}, anchorBounds = anchor,
                                meetingTitles = mapOf("meeting" to "Дизайн-синхронизация"))
                        }
                    }
                }
            }
            if (actualWindow) content() else DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(size)) { content() }
        }
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "notification-layout")
        assertTrue(directory.isDirectory || directory.mkdirs())
        for (viewport in if (actualWindow) listOf(size) else listOf(DpSize(390.dp, 844.dp), DpSize(1194.dp, 834.dp))) for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
            compose.runOnIdle { size = viewport; theme = mode }
            compose.waitForIdle()
            val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            val dimensions = if (actualWindow) "actual-${bitmap.width}x${bitmap.height}" else "${viewport.width.value.toInt()}x${viewport.height.value.toInt()}"
            File(directory, "$dimensions-${mode.name.lowercase()}.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        }
    }

    private companion object {
        val now: Instant = Instant.now()
        val events = listOf(
            InboxNotification("reminder", "conference.soon", now.minusSeconds(600), conferenceId = "meeting", scheduledAt = now.plusSeconds(1_200)),
            InboxNotification("invitation", "conference.invited", now.minusSeconds(1_500), conferenceId = "meeting"),
            InboxNotification("recording", "recording.ready", now.minusSeconds(3_600), conferenceId = "meeting"),
            InboxNotification("read", "chat.message", now.minusSeconds(172_800), readAt = now, conversationId = "chat"),
        )
        val ready = MessagingUiState(notifications = MessagingLoad(events), notificationUnread = 3)
    }
}
