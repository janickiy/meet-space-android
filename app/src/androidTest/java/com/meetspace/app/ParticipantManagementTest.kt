package com.meetspace.app

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.meetspace.app.ui.*
import com.meetspace.core.designsystem.MeetSpaceTheme
import com.meetspace.core.media.CallState
import com.meetspace.core.model.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ParticipantManagementTest {
    @get:Rule val compose = createComposeRule()
    private val owner = MeetingParticipant("owner", "room", "user", "Организатор", "owner", "joined", "admitted")
    private val peer = MeetingParticipant("peer", "room", "member", "Александр Константинопольский", "participant", "joined", "admitted")

    @Test fun supportedActionsRespectRolesAndRefreshAfterSuccessfulRoleChange() {
        var room by mutableStateOf(MeetingRoomUiState(listOf(owner, peer), owner.id))
        var command: Pair<String, MeetingModeration>? = null
        compose.setContent { MeetSpaceTheme { MeetingRoomPanel(CallState(), room,
            MeetingRoomActions(moderate = { id, action -> command = id to action }), Modifier.fillMaxSize(), {}) } }
        compose.onNodeWithTag("moderate-peer").performClick()
        listOf("Отключить микрофон", "Отключить камеру", "Отключить экран", "Разрешить микрофон", "Разрешить камеру", "Разрешить экран").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
        compose.onNodeWithTag("participant-role").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("peer" to MeetingModeration("role", role = "co_host"), command)
            room = room.copy(participants = listOf(owner, peer.copy(role = "co_host")))
        }
        compose.onNodeWithText("Снять роль соорганизатора").assertExists()
        compose.onNodeWithTag("participant-role").performClick()
        compose.runOnIdle { assertEquals("peer" to MeetingModeration("role", role = "participant"), command) }
        compose.runOnIdle { room = room.copy(selfId = peer.id) }
        compose.onNodeWithTag("participant-management").assertDoesNotExist()
        compose.onNodeWithTag("moderate-owner").assertDoesNotExist()
    }

    @Test fun removalNeedsConfirmationKeepsErrorsAndClosesOnlyAfterParticipantIsRemoved() {
        var room by mutableStateOf(MeetingRoomUiState(listOf(owner, peer), owner.id))
        val commands = mutableListOf<Pair<String, MeetingModeration>>()
        compose.setContent { MeetSpaceTheme { MeetingRoomPanel(CallState(), room, MeetingRoomActions(moderate = { id, command ->
            commands += id to command; room = room.copy(busy = true, error = null)
        }), Modifier.fillMaxSize(), {}) } }
        compose.onNodeWithTag("moderate-peer").performClick()
        compose.onNodeWithTag("participant-remove").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(commands.isEmpty()) }
        compose.onNodeWithTag("participant-cancel-remove").performClick()
        compose.onNodeWithTag("participant-remove").performClick()
        compose.onNodeWithTag("participant-confirm-remove").performClick().assertIsNotEnabled()
        compose.onNodeWithTag("participant-cancel-remove").assertIsNotEnabled()
        compose.onNodeWithTag("participant-close").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(listOf("peer" to MeetingModeration("kick")), commands)
            room = room.copy(busy = false, error = "Не удалось связаться с сервером")
        }
        compose.onNodeWithTag("participant-error").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("participant-confirm-remove").assertIsEnabled().performClick()
        compose.runOnIdle { room = room.copy(busy = false, participants = listOf(owner, peer.copy(status = "kicked"))) }
        compose.onNodeWithTag("participant-management").assertDoesNotExist()
        compose.runOnIdle { assertEquals(2, commands.size) }
    }

    @Test fun guestsAndCoHostsOnlyExposeSupportedAuthorizedActions() {
        var target by mutableStateOf(peer.copy(role = "guest", userId = null))
        var actor by mutableStateOf(owner)
        compose.setContent { MeetSpaceTheme { ParticipantManagement(target, MeetingRoomUiState(listOf(actor, target), actor.id), MeetingRoomActions(), {}) } }
        compose.onNodeWithTag("participant-role").assertDoesNotExist()
        compose.onNodeWithTag("participant-remove").assertExists()
        compose.runOnIdle { target = peer; actor = owner.copy(role = "co_host") }
        compose.onNodeWithTag("participant-role").assertDoesNotExist()
        compose.onNodeWithTag("participant-remove").assertExists()
    }

    @Test fun layoutKeepsLongNamesAndActionsReadableInBothThemes() {
        var dark by mutableStateOf(false)
        compose.setContent { MeetSpaceTheme(if (dark) ThemeMode.DARK else ThemeMode.LIGHT) {
            ParticipantManagement(peer, MeetingRoomUiState(listOf(owner, peer), owner.id), MeetingRoomActions(), {})
        } }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val label = InstrumentationRegistry.getArguments().getString("participantLayout", "device")
        for (night in listOf(false, true)) {
            compose.runOnIdle { dark = night }
            compose.onNodeWithTag("participant-name").performScrollTo().assertIsDisplayed()
            assertReadableTextAtActualBounds(compose.onNodeWithTag("participant-name", useUnmergedTree = true), peer.displayName)
            listOf("Сделать соорганизатором", "Разрешить управление участниками встречи", "Удалить из встречи", "Закрыть доступ к этой встрече").forEach { label ->
                val node = compose.onNodeWithText(label, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
                assertReadableTextAtActualBounds(node, label)
            }
            compose.onNodeWithText("Готово").assertIsDisplayed()
            compose.onNodeWithTag("participant-name").performScrollTo()
            val image = compose.onNodeWithTag("participant-management").captureToImage().asAndroidBitmap()
            File(instrumentation.targetContext.cacheDir, "participant-$label-${if (night) "dark" else "light"}.png")
                .outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
