package com.meetspace.app

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.view.WindowCompat
import com.meetspace.app.ui.*
import com.meetspace.core.designsystem.MeetSpaceTheme
import com.meetspace.core.model.*
import java.io.File
import java.time.Instant
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Synthetic presentation data only: no application repository, credentials or network. */
@OptIn(ExperimentalTestApi::class)
class HomeDashboardTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun sidebarStartsAt780DpAndNarrowLayoutStacksChatsAfterActions() {
        var width by mutableStateOf(780.dp)
        compose.setContent { Viewport(width) { Dashboard() } }
        compose.onNodeWithTag("home-wide").assertIsDisplayed()
        compose.onNodeWithTag("home-compact").assertDoesNotExist()
        val chats = compose.onNodeWithTag("home-chats").getUnclippedBoundsInRoot()
        val action = compose.onNodeWithTag("home-new-call").getUnclippedBoundsInRoot()
        assertTrue("Chat pane is beside the primary actions", chats.right <= action.left)
        compose.onNodeWithTag("home-profile").assertIsDisplayed()

        compose.runOnIdle { width = 779.dp }
        compose.onNodeWithTag("home-wide").assertDoesNotExist()
        compose.onNodeWithTag("home-compact").assertIsDisplayed()
        compose.onNodeWithTag("home-profile").assertDoesNotExist()
        val invite = compose.onNodeWithTag("home-invite-copy").getUnclippedBoundsInRoot()
        val stackedChats = compose.onNodeWithTag("home-chats").getUnclippedBoundsInRoot()
        assertTrue("Compact conversations follow the invitation and action area", stackedChats.top >= invite.bottom)
        scroll("home-chat-${direct.id}").assertIsDisplayed()
    }

    @Test fun primaryActionsRequestTheCorrectOperations() {
        var create = 0; var chat = 0; var join = 0; var schedule = 0
        compose.setContent {
            Viewport(390.dp) { Dashboard(onCreate = { create++ }, onNewChat = { chat++ }, onJoin = { join++ }, onSchedule = { schedule++ }) }
        }
        listOf("home-new-call", "home-new-chat", "home-join-call", "home-schedule").forEach {
            scroll(it).assertIsEnabled().assertHeightIsAtLeast(48.dp).performClick()
        }
        compose.runOnIdle { assertEquals(listOf(1, 1, 1, 1), listOf(create, chat, join, schedule)) }
    }

    @Test fun busyAndUncertainCreationGateOnlyTheirOwnActions() {
        var busy by mutableStateOf(true)
        var uncertain by mutableStateOf(false)
        var messaging by mutableStateOf(ready.copy(busy = true))
        compose.setContent { Viewport(390.dp) { Dashboard(messaging, busy = busy, disableCreate = uncertain) } }
        listOf("home-new-call", "home-new-chat", "home-join-call", "home-schedule").forEach { scroll(it).assertIsNotEnabled() }

        compose.runOnIdle { busy = false; uncertain = true; messaging = messaging.copy(busy = false) }
        scroll("home-new-call").assertIsNotEnabled()
        scroll("home-schedule").assertIsNotEnabled()
        scroll("home-join-call").assertIsEnabled()
        scroll("home-new-chat").assertIsEnabled()
        compose.runOnIdle { uncertain = false }
        scroll("home-new-call").assertIsEnabled()
        scroll("home-schedule").assertIsEnabled()
    }

    @Test fun chatSelectionSearchAndFiltersUseSuppliedStateAndConversationIds() {
        var state by mutableStateOf(ready)
        var opened: String? = null
        var requestedQuery: String? = null
        var requestedFilter: ChatFilter? = null
        val actions = MessagingActions(
            query = { requestedQuery = it; state = state.copy(query = it) },
            filter = { requestedFilter = it; state = state.copy(filter = it) },
        )
        compose.setContent { Viewport(1000.dp) { Dashboard(state, actions, onOpenChat = { opened = it }) } }
        scroll("home-chat-${group.id}").performClick()
        compose.runOnIdle { assertEquals(group.id, opened) }
        scroll("home-chat-search").performTextInput("Мария")
        compose.runOnIdle { assertEquals("Мария", requestedQuery) }
        scroll("home-filter-direct").performClick()
        compose.onNodeWithTag("home-filter-direct").assertIsSelected()
        compose.runOnIdle {
            assertEquals(ChatFilter.DIRECT, requestedFilter)
            // The ViewModel supplies server results; the dashboard must not invent matches.
            state = state.copy(conversations = MessagingLoad(listOf(direct)))
        }
        compose.onNodeWithTag("home-chat-${group.id}").assertDoesNotExist()
        scroll("home-chat-${direct.id}").performClick()
        compose.runOnIdle { assertEquals(direct.id, opened) }
        scroll("home-filter-unread").performClick()
        compose.onNodeWithTag("home-filter-unread").assertIsSelected()
        compose.runOnIdle { assertEquals(ChatFilter.UNREAD, requestedFilter) }
        scroll("home-filter-all").performClick()
        compose.onNodeWithTag("home-filter-all").assertIsSelected()
    }

    @Test fun foldersAndProfileUseRealNavigationCallbacks() {
        var folders = 0; var profile = 0
        compose.setContent { Viewport(1000.dp) { Dashboard(onFolders = { folders++ }, onProfile = { profile++ }) } }
        scroll("home-profile").assertHeightIsAtLeast(48.dp).performClick()
        scroll("home-folders").assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(1, folders); assertEquals(1, profile) }
    }

    @Test fun refreshFailureRetainsLoadedChatsAndOffersRetryWithoutClaimingEmpty() {
        var state by mutableStateOf(ready.copy(conversations = MessagingLoad(ready.conversations.data, loading = true)))
        var refresh = 0
        compose.setContent { Viewport(390.dp) { Dashboard(state, MessagingActions(refresh = { refresh++ })) } }
        compose.onNodeWithContentDescription("Загрузка чатов").performScrollTo().assertIsDisplayed()
        scroll("home-chat-${direct.id}").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(conversations = MessagingLoad(ready.conversations.data, error = "Не удалось обновить чаты.")) }
        compose.onNodeWithText("Не удалось обновить чаты.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Повторить").performScrollTo().performClick()
        scroll("home-chat-${direct.id}").assertIsDisplayed()
        compose.onNodeWithText("Чаты не найдены").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, refresh) }
    }

    @Test fun emptyResultsContainNoDemonstrationChatsAndMissingServerCannotBeCopied() {
        compose.setContent {
            Viewport(390.dp) {
                Dashboard(ready.copy(conversations = MessagingLoad(emptyList()), query = "Нет совпадений"), repository = authenticated.copy(profiles = emptyList(), selectedProfileId = null))
            }
        }
        scroll("home-invite-copy").assertIsNotEnabled()
        compose.onNodeWithText("Чаты не найдены").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("home-chat-${direct.id}").assertDoesNotExist()
        compose.onNodeWithTag("home-chat-${group.id}").assertDoesNotExist()
    }

    @Test fun largeFontNarrowDarkWindowKeepsCriticalTitlesUnclippedAndActionsReachable() {
        var schedule = 0
        compose.setContent { Viewport(360.dp, height = 520.dp, fontScale = 1.5f, dark = true) { Dashboard(onSchedule = { schedule++ }) } }
        compose.onNodeWithTag("home-compact").assertIsDisplayed()
        val titles = mapOf(
            "home-new-call" to "Новый звонок", "home-new-chat" to "Новый чат",
            "home-join-call" to "Подключиться к звонку", "home-schedule" to "Запланировать встречу",
            "home-invite-copy" to "Скопировать ссылку",
        )
        titles.forEach { (tag, title) ->
            scroll(tag).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            assertTextNotClipped(title, tag)
        }
        scroll("home-schedule").performClick()
        scroll("home-chat-search").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        scroll("home-filter-unread").assertIsDisplayed()
        scroll("home-folders").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.runOnIdle { assertEquals(1, schedule) }
    }

    /** Run explicitly on each real emulator viewport; no forced dimensions in the screenshot. */
    @Test fun captureApprovedHomeScreenshot() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Opt-in design screenshots", args.getString("designScreenshots") == "true")
        val theme = args.getString("designTheme", "light")
        require(theme in setOf("light", "dark")) { "designTheme must be light or dark" }
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
                FoundationUi(
                    FoundationUiState(FoundationScreen.WORKSPACE, authenticated),
                    onConnect = {}, onSelectProfile = {}, onRemoveProfile = {}, onLogin = { _, _ -> },
                    onLogout = {}, onTheme = {}, onNavigate = {}, onClearMessage = {},
                    onNotifications = {}, unreadCount = 3,
                    workspaceContent = { _, _ -> Dashboard() },
                )
            }
        }
        compose.onNodeWithTag("workspace").assertIsDisplayed()
        compose.onNodeWithTag("bottom-navigation").assertIsDisplayed()
        compose.waitForIdle()
        val configuration = instrumentation.targetContext.resources.configuration
        val directory = checkNotNull(instrumentation.targetContext.getExternalFilesDir("screenshots"))
        check(directory.isDirectory || directory.mkdirs())
        val filename = "home-$theme-${configuration.screenWidthDp}x${configuration.screenHeightDp}-font${configuration.fontScale}.png"
        val image = compose.onNodeWithTag("workspace").captureToImage().asAndroidBitmap()
        File(directory, filename).outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    private fun scroll(tag: String) = compose.onNodeWithTag(tag).performScrollTo()

    private fun assertTextNotClipped(text: String, ancestorTag: String) {
        compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag(ancestorTag)), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                val results = mutableListOf<TextLayoutResult>()
                assertTrue("Critical action title exposes text layout", action(results))
                assertTrue(results.isNotEmpty())
                results.forEach { result ->
                    val geometry = "size=${result.size}, constraints=${result.layoutInput.constraints}, lines=${result.lineCount}, " +
                        "heightOverflow=${result.didOverflowHeight}, widthOverflow=${result.didOverflowWidth}"
                    // A paragraph can retain the parent's maxWidth while the Text itself wraps
                    // to its actual lines. Check rendered line bounds, not unused paragraph space.
                    // One pixel accommodates integer rounding of scaled glyph advances.
                    assertFalse("Critical text is clipped vertically: $text; $geometry", result.didOverflowHeight)
                    (0 until result.lineCount).forEach { line ->
                        assertTrue("Critical line is clipped horizontally: $text; $geometry; right=${result.getLineRight(line)}",
                            result.getLineLeft(line) >= -1f && result.getLineRight(line) <= result.size.width + 1f)
                    }
                    assertTrue("Critical text is ellipsized: $text", (0 until result.lineCount).none(result::isLineEllipsized))
                }
            }
    }

    @Composable private fun Viewport(width: Dp, height: Dp = 900.dp, fontScale: Float = 1f, dark: Boolean = false, content: @Composable () -> Unit) {
        DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(width, height))) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MeetSpaceTheme(if (dark) ThemeMode.DARK else ThemeMode.LIGHT, content = content)
            }
        }
    }

    @Composable private fun Dashboard(
        state: MessagingUiState = ready, actions: MessagingActions = MessagingActions(),
        repository: RepositoryState = authenticated, busy: Boolean = false, disableCreate: Boolean = false,
        onCreate: () -> Unit = {}, onNewChat: () -> Unit = {}, onJoin: () -> Unit = {}, onSchedule: () -> Unit = {},
        onOpenChat: (String) -> Unit = {}, onFolders: () -> Unit = {}, onProfile: () -> Unit = {},
    ) = HomeDashboard(repository, state, actions, busy, disableCreate, onCreate, onNewChat, onJoin, onSchedule, onOpenChat, onFolders, onProfile)

    companion object {
        private val direct = ChatConversation("test-direct", "direct", ChatPeer("test-peer", "Мария Иванова"), preview = "Да, всё готово к обсуждению", unreadCount = 2, lastMessageAt = Instant.parse("2026-10-10T10:24:00Z"))
        private val group = ChatConversation("test-team", "group", name = "Команда продукта", memberCount = 8, preview = "Обновили план следующей встречи", lastMessageAt = Instant.parse("2026-10-10T09:45:00Z"))
        private val other = ChatConversation("test-design", "group", name = "Дизайн MeetSpace", memberCount = 4, preview = "Собрали комментарии по макетам", unreadCount = 1, lastMessageAt = Instant.parse("2026-10-09T16:10:00Z"))
        private val ready = MessagingUiState(selfId = "test-user", conversations = MessagingLoad(listOf(direct, group, other)), realtimeLabel = "Обновления подключены")
        private val profile = ServerProfile("test-server", "https://team.example")
        private val authenticated = RepositoryState(profiles = listOf(profile), selectedProfileId = profile.id,
            session = AccountSession(User("test-user", "member@example.test", "Александр"), Capabilities(), true))
    }
}
