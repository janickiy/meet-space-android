package com.meetspace.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.meetspace.app.ui.FoundationScreen
import com.meetspace.app.ui.FoundationUi
import com.meetspace.app.ui.FoundationUiState
import com.meetspace.core.designsystem.MeetSpaceTheme
import com.meetspace.core.model.RepositoryState
import com.meetspace.core.model.ServerProfile
import com.meetspace.core.model.ServerVersion
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Registration fixtures perform no network requests and create no real accounts. */
@OptIn(ExperimentalTestApi::class)
class RegistrationUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun loginOffersRegistrationAndRegistrationReturnsToLogin() {
        var screen by mutableStateOf(FoundationScreen.LOGIN)
        compose.setContent { Ui(screen, onNavigate = { screen = it }) }
        compose.onNodeWithTag("open-register").performScrollTo().performClick()
        compose.onNodeWithTag("register").assertIsDisplayed()
        compose.onNodeWithTag("register-name").assertExists()
        compose.onNodeWithTag("register-login").performScrollTo().performClick()
        compose.onNodeWithTag("login").assertIsDisplayed()
    }

    @Test fun validationBlocksMismatchedPasswordsAndFocusesRepeatField() {
        var requests = 0
        val editor = FormEditorWindow()
        compose.setContent { editor.Observe(); Ui(onRegister = { _, _, _, _ -> requests++ }) }
        fill("Иван", "member@example.test", "synthetic-password", "different")
        compose.settleFormEditorForClick(editor, compose.onNodeWithTag("register-submit")).performClick()
        compose.onNodeWithTag("register-error").assertTextContains("Пароли не совпадают", substring = true)
        compose.onNodeWithTag("register-confirm").assertIsFocused()
        compose.runOnIdle { assertEquals(0, requests) }
    }

    @Test fun submitUsesTypedCredentialsAndClearsBothPasswords() {
        var submitted: List<String>? = null
        val editor = FormEditorWindow()
        compose.setContent { editor.Observe(); Ui(onRegister = { name, email, password, confirmation ->
            submitted = listOf(name, email, password, confirmation)
        }) }
        fill(" Иван ", " member@example.test ", "synthetic-password", "synthetic-password")
        compose.settleFormEditorForClick(editor, compose.onNodeWithTag("register-submit")).performClick()
        compose.runOnIdle { assertEquals(listOf("Иван", "member@example.test", "synthetic-password", "synthetic-password"), submitted) }
        assertEmpty("register-password"); assertEmpty("register-confirm")
    }

    @Test fun savedStateKeepsNameAndEmailButNeverEitherPasswordOrVisibility() {
        val restoration = StateRestorationTester(compose)
        val editor = FormEditorWindow()
        restoration.setContent { editor.Observe(); Ui() }
        fill("Иван", "member@example.test", "synthetic-password", "synthetic-password")
        compose.settleFormEditorForClick(editor, compose.onNodeWithContentDescription("Показать пароль")).performClick()
        assertRenderedPassword("synthetic-password")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("register-name").assertTextContains("Иван")
        compose.onNodeWithTag("register-email").assertTextContains("member@example.test")
        assertEmpty("register-password"); assertEmpty("register-confirm")
        compose.onNodeWithTag("register-password").performScrollTo().performTextInput("synthetic-password")
        assertRenderedPassword("•".repeat("synthetic-password".length))
    }

    @Test fun pendingRequestDisablesFormAndRepeatedSubmission() {
        compose.setContent { Ui(busy = true) }
        listOf("register-name", "register-email", "register-password", "register-confirm", "register-submit", "register-login", "back")
            .forEach { compose.onNodeWithTag(it).assertIsNotEnabled() }
    }

    @Test fun registrationAdaptsBetweenPhoneAndTabletLandscape() {
        var width by mutableStateOf(360.dp)
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(width, 800.dp))) { Ui() }
        }
        compose.onNodeWithTag("register").assertIsDisplayed()
        compose.onNodeWithTag("register-submit").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { width = 1100.dp }
        compose.onNodeWithTag("register-name").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("register-submit").performScrollTo().assertIsDisplayed()
    }

    private fun fill(name: String, email: String, password: String, confirmation: String) {
        listOf("register-name" to name, "register-email" to email, "register-password" to password, "register-confirm" to confirmation)
            .forEach { (tag, value) -> compose.onNodeWithTag(tag).performScrollTo().performTextInput(value) }
    }
    private fun assertEmpty(tag: String) {
        compose.onNodeWithTag(tag).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    }
    private fun assertRenderedPassword(expected: String) {
        compose.onNodeWithTag("register-password").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
            val result = mutableListOf<TextLayoutResult>()
            action(result)
            assertEquals(expected, result.single().layoutInput.text.text)
        }
    }
    @Composable private fun Ui(screen: FoundationScreen = FoundationScreen.REGISTER, busy: Boolean = false,
        onNavigate: (FoundationScreen) -> Unit = {}, onRegister: (String, String, String, String) -> Unit = { _, _, _, _ -> }) {
        MeetSpaceTheme {
            FoundationUi(FoundationUiState(screen = screen, repository = connected, busy = busy),
                onConnect = {}, onSelectProfile = {}, onRemoveProfile = {}, onLogin = { _, _ -> },
                onRegister = onRegister, onLogout = {}, onTheme = {}, onNavigate = onNavigate, onClearMessage = {})
        }
    }
    private companion object {
        val profile = ServerProfile("registration-server", "https://team.example")
        val connected = RepositoryState(listOf(profile), profile.id, ServerVersion("test", "unknown", "unknown"))
    }
}
