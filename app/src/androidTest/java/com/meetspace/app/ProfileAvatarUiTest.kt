package com.meetspace.app

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.meetspace.app.ui.*
import com.meetspace.core.designsystem.MeetSpaceTheme
import com.meetspace.core.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @OptIn(ExperimentalTestApi::class)
    @Test fun profileBioKeepsItsDraftAcrossLayoutsAndSavesUpTo500Characters() {
        var size by mutableStateOf(DpSize(390.dp, 844.dp))
        var user by mutableStateOf(User("user", "member@example.test", "Анна Смирнова", bio = ""))
        val updates = mutableListOf<Pair<String?, String?>>()
        val actions = MeetingsActions(updateAccountProfile = { name, bio ->
            updates += name to bio
            user = user.copy(bio = bio ?: user.bio)
        })
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(size)) {
                MeetSpaceTheme(ThemeMode.LIGHT) { ProfileEditor(user, actions) }
            }
        }

        val bio = compose.onNodeWithTag("account-bio")
        val name = compose.onNodeWithTag("account-name")
        val email = compose.onNodeWithTag("account-email")
        val save = compose.onNodeWithTag("account-save")
        fun assertBioBetweenNameAndEmail() {
            val bioBounds = bio.performScrollTo().getUnclippedBoundsInRoot()
            assertTrue(name.getUnclippedBoundsInRoot().bottom < bioBounds.top)
            assertTrue(bioBounds.bottom < email.getUnclippedBoundsInRoot().top)
            assertTrue(bioBounds.bottom - bioBounds.top >= 90.dp)
        }
        assertBioBetweenNameAndEmail()
        save.performScrollTo().assertIsNotEnabled()
        val capped = "Связь\n" + "🚀".repeat(494)
        bio.performScrollTo().performTextReplacement(capped + "ещё")
        bio.assertTextEquals(capped)
        compose.runOnIdle {
            assertTrue(updates.isEmpty())
            size = DpSize(1194.dp, 834.dp)
        }
        assertBioBetweenNameAndEmail()
        bio.assertTextEquals(capped)
        save.performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(null to capped), updates) }
        save.assertIsNotEnabled()

        bio.performScrollTo().performTextClearance()
        save.performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(null to capped, null to ""), updates) }
        save.assertIsNotEnabled()
    }

    @Test fun changingTheNameDoesNotOverwriteAnExistingLongBio() {
        val existingBio = "Я".repeat(550)
        val user = User("user", "member@example.test", "Анна Смирнова", bio = existingBio)
        var update: Pair<String?, String?>? = null
        compose.setContent {
            MeetSpaceTheme(ThemeMode.LIGHT) {
                ProfileEditor(user, MeetingsActions(updateAccountProfile = { name, bio -> update = name to bio }))
            }
        }

        compose.onNodeWithTag("account-bio").performScrollTo().assertTextEquals(existingBio)
        compose.onNodeWithTag("account-save").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("account-name").performScrollTo().performTextReplacement("Анна")
        compose.onNodeWithTag("account-save").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("Анна" to null, update) }
    }

    @Composable private fun ProfileEditor(user: User, actions: MeetingsActions) {
        val repository = RepositoryState(profiles = listOf(ServerProfile("profile", "https://fixture.example.test", accountId = user.id)),
            selectedProfileId = "profile", session = AccountSession(user, Capabilities(), true))
        ProfileWorkspace(repository, MeetingsUiState(), actions,
            AppSettingsUiState("test", 0, false, false, false, false, false), AppSettingsActions(),
            onTheme = {}, onLogout = {}, onServers = {}, onFolders = {}, initialSection = "profile")
    }
}
