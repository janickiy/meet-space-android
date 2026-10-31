package com.meetspace.app

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.meetspace.app.ui.*
import com.meetspace.core.designsystem.MeetSpaceTheme
import com.meetspace.core.model.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class AccountNotificationSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val initial = AppSettingsUiState("test", 0, true, false, false, false, false)
    private val original = NotificationPreferences(summary = false, email = true, push = true)

    @Test fun soundOnlyDraftIsSavedExplicitlyWithoutANetworkMutation() {
        var settings by mutableStateOf(initial); var requests = 0; var saves = 0
        compose.setContent { Frame { AccountNotificationPanel(MessagingUiState(notificationPreferences = MessagingLoad(original)),
            MessagingActions(saveNotificationPreferences = { _, _ -> requests++ }), settings,
            AppSettingsActions(updateNotificationSound = { saves++; settings = settings.copy(notificationSoundEnabled = it) }), false) } }
        compose.onNodeWithTag("account-notification-save").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("account-sound-enabled").performScrollTo().assertIsOn().performClick().assertIsOff()
        compose.runOnIdle { assertEquals(0, saves); assertTrue(settings.notificationSoundEnabled) }
        compose.onNodeWithTag("account-test-notification").performScrollTo().performClick()
        compose.onNodeWithTag("account-notification-test-status").assertTextEquals("Включите звуковой сигнал для проверки")
        compose.onNodeWithTag("account-notification-save").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, saves); assertEquals(0, requests); assertFalse(settings.notificationSoundEnabled) }
        compose.onNodeWithTag("account-notification-save").assertIsNotEnabled()
        compose.onNodeWithTag("account-notification-saved").assertIsDisplayed()
    }

    @Test fun combinedDraftRetainsHiddenPreferencesAndWaitsForTheServerBeforeSavingSound() {
        var settings by mutableStateOf(initial)
        var state by mutableStateOf(MessagingUiState(notificationPreferences = MessagingLoad(original)))
        var request: NotificationPreferences? = null; var confirmed: (() -> Unit)? = null; var soundSaves = 0
        compose.setContent { Frame { AccountNotificationPanel(state, MessagingActions(saveNotificationPreferences = { prefs, done ->
            request = prefs; confirmed = done; state = state.copy(busy = true)
        }), settings, AppSettingsActions(updateNotificationSound = { soundSaves++; settings = settings.copy(notificationSoundEnabled = it) }), false) } }
        compose.onNodeWithTag("notification-pref-invitation").performScrollTo().performClick()
        compose.onNodeWithTag("account-sound-enabled").performScrollTo().performClick()
        compose.onNodeWithTag("account-notification-save").performScrollTo().performClick().assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(original.copy(invitation = false), request)
            assertEquals(0, soundSaves)
            state = state.copy(busy = false, notificationPreferences = MessagingLoad(checkNotNull(request)))
            checkNotNull(confirmed).invoke()
        }
        compose.onNodeWithTag("account-notification-save").assertIsNotEnabled()
        compose.onNodeWithTag("account-notification-saved").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, soundSaves); assertFalse(settings.notificationSoundEnabled) }
    }

    @Test fun failedSaveKeepsDraftsAndLocalOnlyRetryClearsTheOldError() {
        var state by mutableStateOf(MessagingUiState(notificationPreferences = MessagingLoad(original)))
        var settings by mutableStateOf(initial)
        var soundSaves = 0
        compose.setContent { Frame { AccountNotificationPanel(state, MessagingActions(saveNotificationPreferences = { _, _ ->
            state = state.copy(error = "Нет связи")
        }, clearMessage = { state = state.copy(error = null) }), settings,
            AppSettingsActions(updateNotificationSound = { soundSaves++; settings = settings.copy(notificationSoundEnabled = it) }), false) } }
        compose.onNodeWithTag("notification-pref-reminder").performScrollTo().performClick()
        compose.onNodeWithTag("account-sound-enabled").performScrollTo().performClick()
        compose.onNodeWithTag("account-notification-save").performScrollTo().performClick().assertIsEnabled()
        compose.onNodeWithTag("account-notification-error").performScrollTo().assertTextEquals("Нет связи")
        compose.onNodeWithTag("account-notification-saved").assertDoesNotExist()
        compose.onNodeWithTag("notification-pref-reminder").assertIsOff()
        compose.onNodeWithTag("account-sound-enabled").assertIsOff()
        compose.runOnIdle { assertEquals(0, soundSaves) }
        compose.onNodeWithTag("notification-pref-reminder").performScrollTo().performClick()
        compose.onNodeWithTag("account-notification-save").performScrollTo().performClick()
        compose.onNodeWithTag("account-notification-error").assertDoesNotExist()
        compose.onNodeWithTag("account-notification-saved").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, soundSaves); assertFalse(settings.notificationSoundEnabled) }
    }

    @Test fun accountSwitchDiscardsThePreviousAccountsUnsavedSoundAndEventDrafts() {
        fun repository(id: String) = RepositoryState(profiles = listOf(ServerProfile(id, "https://fixture.invalid", id)), selectedProfileId = id,
            session = AccountSession(User(id, "$id@example.test", id), Capabilities(), true))
        var account by mutableStateOf(repository("one"))
        compose.setContent { MeetSpaceTheme { ProfileWorkspace(account, MeetingsUiState(), MeetingsActions(), initial, AppSettingsActions(),
            onTheme = {}, onLogout = {}, onServers = {}, onFolders = {}, initialSection = "notifications",
            messaging = MessagingUiState(notificationPreferences = MessagingLoad(original))) } }
        compose.onNodeWithTag("notification-pref-recording").performScrollTo().performClick()
        compose.onNodeWithTag("account-sound-enabled").performScrollTo().performClick()
        compose.runOnIdle { account = repository("two") }
        compose.onNodeWithTag("notification-pref-recording").assertIsOn()
        compose.onNodeWithTag("account-sound-enabled").assertIsOn()
        compose.onNodeWithTag("account-notification-save").performScrollTo().assertIsNotEnabled()
    }

    @Test fun prototypeLayoutHasExactlyFourSwitchesAndRemainsReadableOnPhonesTabletsAndLargeFonts() {
        var size by mutableStateOf(DpSize(390.dp, 844.dp)); var dark by mutableStateOf(false); var scale by mutableFloatStateOf(1f)
        compose.setContent { DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(size)) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) { MeetSpaceTheme(if (dark) ThemeMode.DARK else ThemeMode.LIGHT) {
                val server = ServerProfile("fixture", "https://fixture.invalid", "user")
                ProfileWorkspace(RepositoryState(profiles = listOf(server), selectedProfileId = server.id,
                    session = AccountSession(User("user", "member@example.test", "Участник"), Capabilities(), true)),
                    MeetingsUiState(), MeetingsActions(), initial, AppSettingsActions(), onTheme = {}, onLogout = {}, onServers = {}, onFolders = {},
                    initialSection = "notifications", messaging = MessagingUiState(notificationPreferences = MessagingLoad(original)))
            } }
        } }
        for ((width, height, textScale) in listOf(Triple(390, 844, 1f), Triple(1194, 834, 1f), Triple(320, 640, 1.5f))) {
            for (night in listOf(false, true)) {
                compose.runOnIdle { size = DpSize(width.dp, height.dp); scale = textScale; dark = night }
                for ((tag, label) in listOf("notification-pref-invitation" to "Приглашения и изменения встреч", "notification-pref-reminder" to "Напоминания о встречах",
                    "notification-pref-recording" to "Готовность записи", "account-sound-enabled" to "Звуковой сигнал")) {
                    val row = compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
                    row.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
                    assertReadableTextAtActualBounds(compose.onNodeWithText(label, useUnmergedTree = true), label)
                    if (textScale == 1f) row.assertHeightIsEqualTo(64.dp)
                }
                listOf("notification-pref-summary", "account-notification-email", "account-notification-push", "account-ringtone-meter", "settings-notifications").forEach {
                    compose.onNodeWithTag(it).assertDoesNotExist()
                }
                compose.onNodeWithTag("account-notification-save").performScrollTo().assertIsDisplayed()
                if (textScale == 1f) {
                    compose.onNode(hasText("Уведомления") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading), useUnmergedTree = true).performScrollTo()
                    val prefix = "account-notifications-$width-${if (night) "dark" else "light"}"
                    val image = compose.onNodeWithTag("profile").captureToImage().asAndroidBitmap()
                    File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "$prefix.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            }
        }
    }

    @Composable private fun Frame(content: @Composable () -> Unit) {
        MeetSpaceTheme { Column(Modifier.fillMaxWidth().fillMaxHeight().verticalScroll(rememberScrollState()).padding(20.dp)) { content() } }
    }
}
