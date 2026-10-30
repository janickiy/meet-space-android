package com.meetspace.app

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.meetspace.app.ui.AvatarPhotoCard
import com.meetspace.core.designsystem.MeetSpaceTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class AvatarPhotoCardUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun profilePreviewAndActionsMatchTheMockAndWorkAtPhoneAndTabletWidths() {
        var width by mutableStateOf(320)
        var version by mutableStateOf<String?>(null)
        var picked = 0
        var removed = 0
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(width.dp, 800.dp))) {
                MeetSpaceTheme { Box(Modifier.fillMaxSize().padding(20.dp)) {
                    AvatarPhotoCard("Анна Смирнова", "self", version, true, false, { picked++ }, { removed++ })
                } }
            }
        }
        for (size in listOf(320, 390, 1000)) {
            compose.runOnIdle { width = size }
            compose.onNodeWithTag("account-avatar-card").assertIsDisplayed()
            compose.onNodeWithText("Фото профиля").assertIsDisplayed()
            compose.onNodeWithText("Ваше фото видно участникам встреч и чатов.").assertIsDisplayed()
            compose.onNodeWithTag("account-avatar-preview").assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithTag("account-avatar-pick").assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithTag("account-avatar-remove").assertDoesNotExist()
        }
        compose.onNodeWithTag("account-avatar-preview").performClick()
        compose.runOnIdle { assertEquals(1, picked); version = "photo-1" }
        compose.onNodeWithText("Изменить фото").assertIsDisplayed()
        compose.onNodeWithTag("account-avatar-remove").performClick()
        compose.runOnIdle { assertEquals(0, removed) }
        compose.onNodeWithTag("account-avatar-remove-cancel").performClick()
        compose.runOnIdle { assertEquals(0, removed) }
    }

    @Test fun groupPhotoControlsRespectPermissionsAndDeletionRequiresAnExplicitConfirmation() {
        var manager by mutableStateOf(false)
        var removed = 0
        var picked = 0
        compose.setContent { MeetSpaceTheme { Box(Modifier.width(340.dp).padding(15.dp)) {
            AvatarPhotoCard("Проект", "room", "photo-1", true, false, { picked++ }, { removed++ }, group = true, canManage = manager)
        } } }
        compose.onNodeWithTag("group-avatar-permission").assertIsDisplayed()
        compose.onNodeWithTag("group-avatar-pick").assertDoesNotExist()
        compose.onNodeWithTag("group-avatar-delete").assertDoesNotExist()
        compose.onNodeWithTag("group-avatar-preview").assertIsNotEnabled()
        compose.runOnIdle { manager = true }
        compose.onNodeWithTag("group-avatar-preview").performClick()
        compose.runOnIdle { assertEquals(1, picked) }
        compose.onNodeWithTag("group-avatar-delete").performClick()
        compose.onNodeWithText("Удалить фото?").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, removed) }
        compose.onNodeWithTag("group-avatar-remove-confirm").performClick()
        compose.runOnIdle { assertEquals(1, removed) }
    }
}
