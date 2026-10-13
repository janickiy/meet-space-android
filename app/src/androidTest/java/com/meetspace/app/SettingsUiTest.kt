package com.meetspace.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.meetspace.app.ui.*
import com.meetspace.core.designsystem.MeetSpaceTheme
import com.meetspace.core.model.ThemeMode
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SettingsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun settingsShowActualPermissionsAndRequestExistingNotificationCenter() {
        var notifications = 0
        var permissions = 0
        compose.setContent { Screen(settings, AppSettingsActions({ notifications++ }, { permissions++ })) }
        compose.onNodeWithTag("settings-notification-permission").assertTextEquals("Системные уведомления выключены")
        compose.onNodeWithTag("settings-notifications").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("Камера: доступ разрешён").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Микрофон: доступ не разрешён").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("settings-system-permissions").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, notifications); assertEquals(1, permissions) }
    }

    @Test fun missingFirebaseNeverClaimsBackgroundPushDelivery() {
        compose.setContent { Screen(settings) }
        compose.onNodeWithTag("settings-push-state").assertTextContains("Фоновые push пока не подключены", substring = true)
        compose.onNodeWithTag("settings-version").performScrollTo().assertTextEquals("Версия 0.8.0 (7)")
    }

    @Test fun connectedPushStateIsScopedToCurrentAccount() {
        compose.setContent { Screen(settings.copy(pushConfigured = true, pushEnabled = true, notificationsAllowed = true)) }
        compose.onNodeWithTag("settings-push-state").assertTextEquals("Фоновые push включены для текущего аккаунта.")
        compose.onNodeWithTag("settings-notification-permission").assertTextEquals("Системные уведомления разрешены")
    }

    @Test fun privacyExplainsExportRetentionAndDialogClosesAtLargeFontInDarkTheme() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) { Screen(settings, dark = true) }
        }
        compose.onNodeWithTag("settings-privacy").performScrollTo().performClick()
        compose.onNodeWithTag("settings-privacy-content").assertExists()
        compose.onNodeWithText("Скачанные вами файлы остаются", substring = true).performScrollTo().assertIsDisplayed()
        val bitmap = compose.onNodeWithTag("settings-privacy-content").captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "stage08-settings-privacy-dark-large-font.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithTag("settings-privacy-close").assertIsDisplayed().performClick()
        compose.onNodeWithTag("settings-privacy-content").assertDoesNotExist()
    }

    @Composable private fun Screen(state: AppSettingsUiState, actions: AppSettingsActions = AppSettingsActions(), dark: Boolean = false) {
        MeetSpaceTheme(if (dark) ThemeMode.DARK else ThemeMode.LIGHT) {
            Column(Modifier.width(360.dp).fillMaxHeight().verticalScroll(rememberScrollState())) { AppSettings(state, actions) }
        }
    }
    companion object {
        private val settings = AppSettingsUiState("0.8.0", 7, false, true, false, false, false)
    }
}
