package com.meetspace.app

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.meetspace.app.ui.*
import com.meetspace.core.designsystem.MeetSpaceTheme
import com.meetspace.core.model.*
import com.meetspace.core.ssh.installPhases
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class ProfileDesignTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val server = ServerProfile("test-server", "https://team.example", "test-user", "member@example.test", "Александр Соколов")
    private val repository = RepositoryState(profiles = listOf(server), selectedProfileId = server.id,
        session = AccountSession(User("test-user", "member@example.test", "Александр Соколов", createdAt = "2026-10-03T16:17:00Z"), Capabilities(), true), theme = ThemeMode.DARK)
    private val settings = AppSettingsUiState("0.2.0-beta", 17, true, true, false, false, false)

    @Composable private fun Profile(repo: RepositoryState = repository, onTheme: (ThemeMode) -> Unit = {}, onLogout: () -> Unit = {}, onFolders: () -> Unit = {}, updateName: (String) -> Unit = {}, section: String? = null,
        onSelectAccount: (String) -> Unit = {}, onAddAccount: (String, String, String, String?, String) -> Unit = { _, _, _, _, _ -> }, onDeleteAccount: (String) -> Unit = {}) {
        ProfileWorkspace(repo, MeetingsUiState(), MeetingsActions(updateProfile = updateName), settings, AppSettingsActions(),
            onTheme = onTheme, onLogout = onLogout, onServers = {}, onFolders = onFolders,
            initialSection = section, messaging = MessagingUiState(notificationPreferences = MessagingLoad(NotificationPreferences())), onServerAddress = {},
            onSelectAccount = onSelectAccount, onAddAccount = onAddAccount, onDeleteAccount = onDeleteAccount)
    }
    @Test fun tabletProfileUsesSidebarAndCompactShowsMenu() {
        var width by mutableStateOf(780.dp)
        compose.setContent { DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(width, 600.dp))) { MeetSpaceTheme(ThemeMode.DARK) { Profile() } } }
        compose.onNodeWithTag("profile-wide").assertExists()
        val identity = compose.onNodeWithTag("profile-edit").getUnclippedBoundsInRoot()
        val field = compose.onNodeWithTag("account-name").getUnclippedBoundsInRoot()
        assertTrue(identity.right < field.left)
        // ForcedSize may quantize one dp to the same pixel on a physical phone viewport.
        compose.runOnIdle { width = 778.dp }
        compose.onNodeWithTag("profile-compact").assertExists()
        compose.onNodeWithTag("account-name").assertDoesNotExist()
        compose.onNodeWithTag("profile-profile").assertIsDisplayed()
    }
    @Test fun identityEditorValidatesNameAndSendsOnlyAfterSave() {
        var saved: String? = null
        compose.setContent { DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(390.dp, 700.dp))) { MeetSpaceTheme { Profile(updateName = { saved = it }) } } }
        compose.onNodeWithTag("account-name").assertDoesNotExist()
        compose.onNodeWithTag("profile-edit").performScrollTo().performClick()
        compose.onNodeWithTag("account-save").assertIsNotEnabled()
        compose.onNodeWithTag("account-name").performTextReplacement("  Новое имя  ")
        assertNull(saved)
        compose.onNodeWithTag("account-save").performScrollTo().performClick()
        assertEquals("Новое имя", saved)
        compose.onNodeWithTag("account-back").performScrollTo().performClick()
        compose.onNodeWithTag("account-name").assertDoesNotExist()
    }
    @Test fun themeIsImmediateAndLogoutRequiresChoice() {
        var theme: ThemeMode? = null; var exits = 0
        compose.setContent { DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(390.dp, 700.dp))) { MeetSpaceTheme { Profile(onTheme = { theme = it }, onLogout = { exits++ }) } } }
        compose.onNodeWithTag("profile-appearance").performScrollTo().performClick()
        compose.onNodeWithTag("theme-light").performScrollTo().performClick(); assertEquals(ThemeMode.LIGHT, theme)
        compose.onNodeWithTag("account-back").performScrollTo().performClick()
        compose.onNodeWithTag("logout").performScrollTo().performClick(); assertEquals(0, exits)
        compose.onNodeWithTag("logout-confirm").performClick(); assertEquals(1, exits)
    }
    @Test fun notificationDraftWaitsForExplicitSave() {
        var saved: NotificationPreferences? = null
        val prefs = NotificationPreferences()
        compose.setContent { MeetSpaceTheme { ProfileWorkspace(repository, MeetingsUiState(), MeetingsActions(), settings, AppSettingsActions(),
            onTheme = {}, onLogout = {}, onServers = {}, onFolders = {}, initialSection = "notifications",
            messaging = MessagingUiState(notificationPreferences = MessagingLoad(prefs)), messagingActions = MessagingActions(saveNotificationPreferences = { value, done -> saved = value; done() })) } }
        compose.onNodeWithTag("account-notification-save").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("notification-pref-reminder").performScrollTo().performClick()
        compose.onNodeWithTag("notification-pref-reminder").assertIsOff().assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Switch))
        assertNull(saved)
        compose.onNodeWithTag("account-notification-save").performScrollTo().performClick()
        assertEquals(prefs.copy(reminder = false), saved)
    }
    @Test fun accountsCanBeSwitchedAddedAndDeletedAfterPasswordConfirmation() {
        val second = server.copy(id = "second", accountId = "second-user", accountEmail = "second@example.test", accountName = "Мария Полякова")
        var selected: String? = null; var added = 0; var deleted: String? = null
        compose.setContent { MeetSpaceTheme { Profile(repo = repository.copy(profiles = listOf(server.copy(accountId = "test-user", accountEmail = "member@example.test", accountName = "Александр Соколов"), second)),
            section = "accounts", onSelectAccount = { selected = it }, onAddAccount = { _, _, _, _, _ -> added++ }, onDeleteAccount = { deleted = it }) } }
        compose.onNodeWithTag("account-card-second").performScrollTo().performClick()
        assertEquals("second", selected)
        compose.onNodeWithTag("account-add").performScrollTo().performClick()
        assertEquals(0, added)
        compose.onNodeWithTag("account-add-email").performScrollTo().performTextInput("second@example.test")
        compose.onNodeWithTag("account-add-password").performScrollTo().performTextInput("synthetic-password")
        compose.onNodeWithTag("account-add-submit").performScrollTo().performClick()
        assertEquals(1, added)
        compose.onNodeWithTag("account-add-cancel").performScrollTo().performClick()
        compose.onNodeWithTag("account-delete").performScrollTo().performClick()
        compose.onNodeWithTag("account-delete-password").performTextInput("synthetic-password")
        compose.onNodeWithTag("account-delete-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("account-delete-acknowledge").performClick()
        compose.onNodeWithTag("account-delete-confirm").performClick()
        assertEquals("synthetic-password", deleted)
    }
    @Test fun registrationFormPassesFieldsAndCancelDoesNotCreateAccount() {
        var submitted: List<String?>? = null
        compose.setContent { DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(390.dp, 700.dp))) { MeetSpaceTheme { Profile(
            section = "add-account", onAddAccount = { server, email, password, name, confirmation -> submitted = listOf(server, email, password, name, confirmation) }) } } }
        compose.onNodeWithTag("account-add-register-tab").performScrollTo().performClick()
        compose.onNodeWithTag("account-add-name").performScrollTo().performTextInput("Новое имя")
        compose.onNodeWithTag("account-add-email").performScrollTo().performTextInput("new@example.test")
        compose.onNodeWithTag("account-add-password").performScrollTo().performTextInput("synthetic-password")
        compose.onNodeWithTag("account-add-confirm").performScrollTo().performTextInput("synthetic-password")
        assertNull(submitted)
        compose.onNodeWithTag("account-add-submit").performScrollTo().performClick()
        assertEquals(listOf("https://team.example", "new@example.test", "synthetic-password", "Новое имя", "synthetic-password"), submitted)
        compose.onNodeWithTag("account-add-cancel").performScrollTo().performClick()
        compose.onNodeWithTag("account-add").performScrollTo().assertIsDisplayed()
    }

    @Test fun connectionRejectsNonHttpsAndOnlySavesChangedAddress() {
        var saved: String? = null
        compose.setContent { MeetSpaceTheme { ProfileWorkspace(repository, MeetingsUiState(), MeetingsActions(), settings, AppSettingsActions(),
            onTheme = {}, onLogout = {}, onServers = {}, onFolders = {}, initialSection = "connection", onServerAddress = { saved = it }) } }
        compose.onNodeWithTag("account-save-connection").assertIsNotEnabled()
        compose.onNodeWithTag("account-server-address").performTextReplacement("http://example.test")
        compose.onNodeWithTag("account-save-connection").assertIsNotEnabled()
        compose.onNodeWithTag("account-server-address").performTextReplacement("https://other.example.test")
        assertNull(saved)
        compose.onNodeWithTag("account-save-connection").performClick()
        assertEquals("https://other.example.test", saved)
    }
    @Test fun phoneThreadHidesBottomNavigationAndTabletKeepsIt() {
        var width by mutableStateOf(390.dp)
        compose.setContent { DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(width, 900.dp))) {
            MeetSpaceTheme { FoundationUi(FoundationUiState(FoundationScreen.WORKSPACE, repository), {}, {}, {}, {_,_->}, {}, {}, {}, {},
                messageThreadOpen = true, workspaceContent = { _, _ -> }) }
        } }
        compose.onNodeWithTag("tab-messages").performClick()
        compose.onNodeWithTag("bottom-navigation").assertDoesNotExist()
        compose.runOnIdle { width = 780.dp }
        compose.onNodeWithTag("bottom-navigation").assertIsDisplayed()
        compose.onNodeWithTag("tab-messages").assertIsSelected()
    }

    @Test fun captureProfileAndOnboardingScreenshot() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("designScreenshots") == "true")
        val screen = args.getString("designScreen", "profile")
        val theme = args.getString("designTheme", "dark")
        val captureRepository = if (screen.startsWith("account-")) repository.copy(profiles = repository.profiles +
            ServerProfile("studio", "https://studio.example.test", "studio-user", "alex@studio.example.test", "Александр · Студия")) else repository
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
        compose.setContent { MeetSpaceTheme(if (theme == "dark") ThemeMode.DARK else ThemeMode.LIGHT) {
            val light = MaterialTheme.colorScheme.background.luminance() > .5f
            SideEffect { WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView).apply { isAppearanceLightStatusBars = light; isAppearanceLightNavigationBars = light } }
            when (screen) {
                "profile", "account-profile", "account-accounts", "account-add-account", "account-audio", "account-video", "account-notifications", "account-appearance", "account-connection", "welcome", "connect", "login" -> FoundationUi(FoundationUiState(when (screen) {
                    "welcome" -> FoundationScreen.WELCOME; "connect" -> FoundationScreen.CONNECT; "login" -> FoundationScreen.LOGIN; else -> FoundationScreen.WORKSPACE
                }, repository.copy(theme = if (theme == "dark") ThemeMode.DARK else ThemeMode.LIGHT)), {}, {}, {}, {_,_->}, {}, {}, {}, {},
                    workspaceContent = { _, _ -> Profile(captureRepository.copy(theme = if (theme == "dark") ThemeMode.DARK else ThemeMode.LIGHT), section = screen.removePrefix("account-").takeIf { screen.startsWith("account-") }) })
                "deploy", "hostkey", "sshauth", "preflight" -> SshWizardUi(sshFixture(screen), {_,_,_,_->}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
                else -> InstallerUi(installFixture(screen), {}, {_,_,_->}, {_,_,_,_->}, {}, {}, {}, {})
            }
        } }
        // Capture the settled destination without injecting a transient pressed/ripple state.
        if (screen == "profile" || screen.startsWith("account-")) compose.onNodeWithTag("tab-profile").performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        val config = instrumentation.targetContext.resources.configuration
        val dir = checkNotNull(instrumentation.targetContext.getExternalFilesDir("screenshots")); dir.mkdirs()
        val file = File(dir, "$screen-$theme-${config.screenWidthDp}x${config.screenHeightDp}-font${config.fontScale}.png")
        file.outputStream().use { assertTrue(compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
    private val endpoint = SshEndpoint("vps.example.test", 22, "deploy")
    private val key = SshHostKey("ssh-ed25519", "test-only", "SHA256:K7s4bXoYZQ6nPf3wJLa81cMeVrT5Au09HdE2gIxyzAb")
    private fun sshFixture(screen: String): SshWizardUiState {
        val checks = listOf("Linux" to "Ubuntu 24.04", "Память" to "4 ГБ", "Диск" to "40 ГБ свободно", "Docker" to "Готов к установке")
            .mapIndexed { i, (title, detail) -> PreflightCheck("check-$i", title, PreflightLevel.PASS, detail) }
        return SshWizardUiState(step = when (screen) { "hostkey" -> SshWizardStep.VERIFY_HOST; "sshauth" -> SshWizardStep.AUTH; "preflight" -> SshWizardStep.RESULTS; else -> SshWizardStep.DETAILS },
            request = SshRequest(endpoint, "meet.example.test"), identity = SshHostIdentity(endpoint, key, if (screen == "hostkey") SshTrust.NEW else SshTrust.TRUSTED),
            report = PreflightReport(endpoint, "meet.example.test", key, checks, 1791792000000))
    }
    private fun installFixture(screen: String): InstallerUiState {
        val plan = InstallPlan("{}", "a".repeat(64), "v0.4.0", "b".repeat(40), InstallOptions("meet.example.test", "203.0.113.10", "owner@example.test"), "arm64", true, 2000000000)
        val state = when (screen) { "complete" -> "completed"; "installerror" -> "failed"; else -> "running" }
        return InstallerUiState(active = true, screen = InstallScreen.PROGRESS, endpoint = endpoint, plan = plan,
            ticket = InstallTicket(endpoint, plan, "test-job"), needsAccess = false,
            status = InstallStatus("test-job", state, if (state == "completed") "readiness" else "images", if (state == "completed") installPhases else listOf("docker", "source"), emptyList(), if (state == "failed") "IMAGE_BUILD_FAILED" else null))
    }
}
