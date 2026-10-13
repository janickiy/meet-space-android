package com.meetspace.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import com.meetspace.app.ui.FoundationScreen
import com.meetspace.app.ui.FoundationUi
import com.meetspace.app.ui.FoundationUiState
import com.meetspace.core.designsystem.MeetSpaceTheme
import com.meetspace.core.model.AccountSession
import com.meetspace.core.model.Capabilities
import com.meetspace.core.model.RepositoryState
import com.meetspace.core.model.ServerProfile
import com.meetspace.core.model.ServerVersion
import com.meetspace.core.model.ThemeMode
import com.meetspace.core.model.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** UI fixtures are deliberately confined to androidTest; all production connections use the API. */
@OptIn(ExperimentalTestApi::class)
class FoundationUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun firstRunConnectOpensServerForm() {
        var screen by mutableStateOf(FoundationScreen.WELCOME)
        compose.setContent {
            TestUi(state = FoundationUiState(screen = screen), onNavigate = { screen = it })
        }

        compose.onNodeWithTag("welcome").assertIsDisplayed()
        compose.onNodeWithTag("welcome-connect").performScrollTo().performClick()

        compose.onNodeWithTag("connect").assertIsDisplayed()
        compose.onNodeWithTag("server-url").assertIsDisplayed()
        compose.onNodeWithTag("login").assertDoesNotExist()
    }

    @Test
    fun firstRunInstallerShowsItsActualReadiness() {
        var screen by mutableStateOf(FoundationScreen.WELCOME)
        compose.setContent {
            TestUi(state = FoundationUiState(screen = screen), onNavigate = { screen = it })
        }

        compose.onNodeWithTag("welcome-installer").performScrollTo().performClick()

        compose.onNodeWithTag("installer").assertIsDisplayed()
        compose.onNode(hasText("Установка через Android появится в отдельном этапе. Сейчас приложение подключается к уже развёрнутому серверу.")).assertIsDisplayed()
        compose.onNodeWithTag("connect-submit").assertDoesNotExist()
        compose.onNodeWithTag("workspace").assertDoesNotExist()
    }

    @Test
    fun connectSubmitsEnteredOriginAndBlocksRepeatedSubmissionWhileBusy() {
        var busy by mutableStateOf(false)
        var submitted: String? = null
        val editor = FormEditorWindow()
        compose.setContent {
            editor.Observe()
            TestUi(
                state = FoundationUiState(screen = FoundationScreen.CONNECT, busy = busy),
                onConnect = { submitted = it },
            )
        }

        compose.onNodeWithTag("server-url").performTextInput("https://team.example")
        compose.settleFormEditorForClick(editor, compose.onNodeWithTag("connect-submit"))
            .assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals("https://team.example", submitted)
            busy = true
        }
        compose.onNodeWithTag("connect-submit").assertIsNotEnabled()
        compose.onNodeWithTag("server-url").assertIsNotEnabled()
    }

    @Test
    fun loginKeepsPasswordHiddenUntilExplicitlyRevealedAndSubmitsTypedCredentials() {
        var submitted: Pair<String, String>? = null
        val editor = FormEditorWindow()
        compose.setContent {
            editor.Observe()
            TestUi(
                state = FoundationUiState(screen = FoundationScreen.LOGIN, repository = connected),
                onLogin = { email, password -> submitted = email to password },
            )
        }

        compose.onNodeWithTag("login-submit").assertIsNotEnabled()
        compose.onNodeWithTag("email").performScrollTo().performTextInput("member@example.test")
        compose.onNodeWithTag("password").performScrollTo().performTextInput("test-only-password")
        compose.onNodeWithTag("password").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        assertRenderedPassword("•".repeat("test-only-password".length))
        compose.settleFormEditorForClick(editor, compose.onNodeWithContentDescription("Показать пароль"))
            .performClick()
        assertRenderedPassword("test-only-password")
        compose.onNodeWithContentDescription("Скрыть пароль").performClick()
        assertRenderedPassword("•".repeat("test-only-password".length))
        compose.onNodeWithTag("login-submit").performScrollTo().assertIsEnabled().performClick()

        compose.runOnIdle {
            assertEquals("member@example.test" to "test-only-password", submitted)
        }
    }

    @Test
    fun savedInstanceStateRestoresEmailButDoesNotPersistPassword() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            TestUi(state = FoundationUiState(screen = FoundationScreen.LOGIN, repository = connected))
        }
        compose.onNodeWithTag("email").performTextInput("member@example.test")
        compose.onNodeWithTag("password").performTextInput("test-only-password")

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithTag("email").assert(hasText("member@example.test"))
        compose.onNodeWithTag("password").assert(hasText(""))
        compose.onNodeWithTag("login-submit").assertIsNotEnabled()
    }

    @Test
    fun switchingAccountProfileClearsPasswordAndUsesThatAccountsEmail() {
        val first = profile.copy(id = "first-account", accountId = "first-user", accountEmail = "first@example.test")
        val second = profile.copy(id = "second-account", accountId = "second-user", accountEmail = "second@example.test")
        var repository by mutableStateOf(connected.copy(profiles = listOf(first, second), selectedProfileId = first.id))
        compose.setContent {
            TestUi(state = FoundationUiState(screen = FoundationScreen.LOGIN, repository = repository))
        }
        compose.onNodeWithTag("email").assert(hasText("first@example.test"))
        compose.onNodeWithTag("password").performScrollTo().performTextInput("test-only-password")
        compose.onNodeWithContentDescription("Показать пароль").performClick()

        compose.runOnIdle { repository = repository.copy(selectedProfileId = second.id) }

        compose.onNodeWithTag("email").assert(hasText("second@example.test"))
        compose.onNodeWithTag("password").assert(hasText(""))
        compose.onNodeWithTag("password").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        compose.onNodeWithTag("login-submit").assertIsNotEnabled()
    }

    @Test
    fun compactWindowUsesBottomNavigation() {
        compose.setContent { Workspace(width = 0) }
        assertThreeBottomDestinations()
        compose.onNodeWithTag("tab-calls").assertIsSelected()
        compose.onNodeWithTag("calls").assertIsDisplayed()
    }

    @Test
    fun mediumWindowKeepsThreeBottomDestinations() {
        compose.setContent { Workspace(width = 600) }
        assertThreeBottomDestinations()
    }

    @Test
    fun expandedWindowKeepsThreeBottomDestinations() {
        compose.setContent { Workspace(width = 840) }
        assertThreeBottomDestinations()
    }

    @Test
    fun selectedTabSurvivesWindowChangesAndSavedInstanceStateRecreation() {
        var width by mutableStateOf(0)
        val restoration = StateRestorationTester(compose)
        restoration.setContent { Workspace(width = width) }
        compose.onNodeWithTag("tab-profile").performClick()
        compose.onNodeWithTag("profile").assertIsDisplayed()

        compose.runOnIdle { width = 840 }
        assertThreeBottomDestinations()
        compose.onNodeWithTag("tab-profile").assertIsSelected()
        compose.onNodeWithTag("profile").assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("tab-profile").assertIsSelected()
        compose.onNodeWithTag("profile").assertIsDisplayed()
    }

    @Test
    fun calendarIsNestedUnderCallsAndBackKeepsCallsSelected() {
        compose.setContent { Workspace(width = 840) }
        compose.onNodeWithTag("tab-calls").performClick()
        compose.onNodeWithTag("calls-calendar").assertIsDisplayed().performClick()
        compose.onNodeWithTag("calendar").assertIsDisplayed()
        compose.onNodeWithTag("tab-calls").assertIsSelected()
        assertThreeBottomDestinations()
        compose.onNodeWithTag("calendar-back").performClick()
        compose.onNodeWithTag("calls").assertIsDisplayed()
        compose.onNodeWithTag("tab-calls").assertIsSelected()
        compose.onNodeWithTag("tab-calendar").assertDoesNotExist()
    }

    private fun assertThreeBottomDestinations() {
        compose.onNodeWithTag("bottom-navigation").assertIsDisplayed()
        compose.onNodeWithTag("navigation-rail").assertDoesNotExist()
        compose.onNodeWithTag("navigation-drawer").assertDoesNotExist()
        compose.onNodeWithTag("tab-calendar").assertDoesNotExist()
        compose.onNodeWithTag("tab-home").assertDoesNotExist()
        listOf("tab-calls", "tab-messages", "tab-profile").forEach {
            compose.onNodeWithTag(it).assertIsDisplayed().assertIsEnabled()
        }
        compose.onNodeWithTag("tab-calls").assertTextContains("Встречи")
        compose.onAllNodes(hasAnyAncestor(hasTestTag("bottom-navigation")) and hasClickAction()).assertCountEquals(3)
    }

    @Composable
    private fun Workspace(width: Int) {
        DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize((if (width == 0) 360 else width).dp, 900.dp))) {
            TestUi(
                state = FoundationUiState(screen = FoundationScreen.WORKSPACE, repository = authenticated),
                windowSizeClass = WindowSizeClass(width, 900),
            )
        }
    }

    private fun assertRenderedPassword(expected: String) {
        // Password keyboard semantics stay defined even when visualTransformation reveals the value.
        compose.onNodeWithTag("password").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
            val layouts = mutableListOf<TextLayoutResult>()
            assertTrue("The password field exposes its rendered text layout", action(layouts))
            assertEquals(expected, layouts.single().layoutInput.text.text)
        }
    }

    @Composable
    private fun TestUi(
        state: FoundationUiState,
        windowSizeClass: WindowSizeClass = WindowSizeClass(0, 480),
        onConnect: (String) -> Unit = {},
        onLogin: (String, String) -> Unit = { _, _ -> },
        onNavigate: (FoundationScreen) -> Unit = {},
    ) {
        MeetSpaceTheme(ThemeMode.LIGHT) {
            FoundationUi(
                state = state,
                onConnect = onConnect,
                onSelectProfile = {},
                onRemoveProfile = {},
                onLogin = onLogin,
                onLogout = {},
                onTheme = {},
                onNavigate = onNavigate,
                onClearMessage = {},
                windowSizeClass = windowSizeClass,
            )
        }
    }

    companion object {
        private val profile = ServerProfile(id = "test-server", origin = "https://team.example")
        private val connected = RepositoryState(
            profiles = listOf(profile),
            selectedProfileId = profile.id,
            version = ServerVersion(version = "test-build", commit = "fixture", buildTime = ""),
        )
        private val authenticated = connected.copy(
            session = AccountSession(
                user = User(id = "test-user", email = "member@example.test", displayName = "Участник"),
                capabilities = Capabilities(),
                supportsPersistentSession = true,
            ),
        )
    }
}
