package com.meetspace.app

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.meetspace.app.ui.*
import com.meetspace.core.designsystem.MeetSpaceTheme
import com.meetspace.core.model.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ProfileAvatarUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun profileOffersAddReplaceAndRemoveOnlyForItsCurrentAvatar() {
        var user by mutableStateOf(User("user", "member@example.test", "Анна Смирнова"))
        var deleted = 0
        compose.setContent {
            val repository = RepositoryState(profiles = listOf(ServerProfile("profile", "https://fixture.example.test", accountId = "user")),
                selectedProfileId = "profile", session = AccountSession(user, Capabilities(), true))
            MeetSpaceTheme(ThemeMode.LIGHT) { Box(Modifier.fillMaxSize()) {
                ProfileWorkspace(repository, MeetingsUiState(), MeetingsActions(deleteAvatar = { deleted++; user = user.copy(avatarVersion = null) }),
                    AppSettingsUiState("test", 0, false, false, false, false, false), AppSettingsActions(),
                    onTheme = {}, onLogout = {}, onServers = {}, onFolders = {}, initialSection = "profile")
            } }
        }
        compose.onNodeWithTag("account-avatar-pick").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Добавить фото").assertExists()
        compose.onNodeWithTag("account-avatar-remove").assertDoesNotExist()
        compose.runOnIdle { user = user.copy(avatarVersion = "version-1") }
        compose.onNodeWithText("Изменить фото").assertExists()
        compose.onNodeWithTag("account-avatar-remove").performScrollTo().performClick()
        compose.onNodeWithText("Удалить фото?").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, deleted) }
        compose.onNodeWithTag("account-avatar-remove-cancel").performClick()
        compose.runOnIdle { assertEquals(0, deleted) }
        compose.onNodeWithTag("account-avatar-remove").performScrollTo().performClick()
        compose.onNodeWithTag("account-avatar-remove-confirm").performClick()
        compose.runOnIdle { assertEquals(1, deleted) }
        compose.onNodeWithText("Добавить фото").assertExists()
        compose.onNodeWithTag("account-avatar-remove").assertDoesNotExist()
    }
}
