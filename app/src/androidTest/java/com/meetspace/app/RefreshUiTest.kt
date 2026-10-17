package com.meetspace.app

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.meetspace.app.ui.*
import com.meetspace.core.designsystem.MeetSpaceTheme
import com.meetspace.core.media.*
import com.meetspace.core.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.Instant

class RefreshUiTest {
    @get:Rule val compose = createComposeRule()
    private val owner = MeetingParticipant("owner", "room", "user", "Организатор", "owner", "joined", "admitted")
    private val waiting = MeetingParticipant("guest", "room", null, "Гость", "guest", "waiting", "waiting")
    private val peer = MeetingParticipant("peer", "room", "peer-user", "Коллега", "participant", "joined", "admitted")

    @Test fun guestJoinRequiresNameAndInvitationWithoutSubmittingOnTyping() {
        var sent: Pair<String, String>? = null
        compose.setContent { MeetSpaceTheme { GuestJoinDialog(FoundationUiState(), { code, name -> sent = code to name }, {}) } }
        compose.onNodeWithTag("guest-join").assertIsNotEnabled()
        compose.onNodeWithTag("guest-name").performTextInput("Гость")
        compose.onNodeWithTag("guest-join").assertIsNotEnabled()
        compose.onNodeWithTag("guest-invitation").performTextInput("invitation-code")
        compose.runOnIdle { assertNull(sent) }
        compose.onNodeWithTag("guest-join").performClick()
        compose.runOnIdle { assertEquals("invitation-code" to "Гость", sent) }
    }
    @Test fun guestLobbyIsScopedAndDoesNotExposeAccountNavigation() {
        val meeting = Meeting("room", "owner", "Гостевая встреча", "code", "https://example.test/join/code", "active", Instant.EPOCH, Instant.EPOCH)
        var state by mutableStateOf(FoundationUiState(repository = RepositoryState(session = AccountSession(User("guest", "guest@example.test", "Гость"), Capabilities(), false, meeting))))
        var joined = false; var left = false
        compose.setContent { MeetSpaceTheme { GuestMeetingLobby(state, { joined = true }, {}, { left = true }) } }
        compose.onNodeWithTag("bottom-navigation").assertDoesNotExist()
        compose.onNodeWithTag("guest-enter-call").performClick()
        compose.runOnIdle { assertTrue(joined); state = state.copy(repository = state.repository.copy(session = state.repository.session!!.copy(guestMeeting = meeting.copy(status = "scheduled")))) }
        compose.onNodeWithTag("guest-enter-call").assertDoesNotExist()
        compose.onNodeWithTag("guest-leave").performClick()
        compose.runOnIdle { assertTrue(left) }
    }

    @Test fun waitingAdmissionAndModerationUseParticipantIdsAndRespectRoles() {
        var admitted: Pair<String, Boolean>? = null
        var command: Pair<String, MeetingModeration>? = null
        var room by mutableStateOf(MeetingRoomUiState(listOf(owner, waiting, peer), owner.id))
        compose.setContent { MeetSpaceTheme { MeetingRoomPanel(CallState(), room,
            MeetingRoomActions(admit = { id, allow -> admitted = id to allow }, moderate = { id, action -> command = id to action }), Modifier.width(400.dp).fillMaxHeight(), {}) } }
        compose.onNodeWithTag("admit-guest").performClick()
        compose.runOnIdle { assertEquals("guest" to true, admitted) }
        compose.onNodeWithTag("moderate-peer").performClick()
        compose.onNodeWithText("Отключить камеру").performClick()
        compose.runOnIdle { assertEquals("peer" to MeetingModeration("camera", true), command) }
        compose.onNodeWithText("Готово").performClick()
        compose.runOnIdle { room = room.copy(selfId = peer.id) }
        compose.onNodeWithTag("admit-guest").assertDoesNotExist()
        compose.onNodeWithTag("moderate-owner").assertDoesNotExist()
        compose.onNodeWithTag("call-invite").assertDoesNotExist()
    }
    @Test fun invitationRequiresValidRecipientAndDoesNotSendOnTyping() {
        var sent: List<String>? = null
        compose.setContent { MeetSpaceTheme { MeetingInvitationDialog(MeetingRoomUiState(), MeetingRoomActions(invite = { _, emails -> sent = emails }), {}) } }
        compose.onNodeWithTag("invitation-send").assertIsNotEnabled()
        compose.onNodeWithTag("invitation-emails").performTextInput("not-email")
        compose.onNodeWithTag("invitation-send").assertIsNotEnabled()
        compose.onNodeWithTag("invitation-emails").performTextReplacement("colleague@example.test")
        compose.runOnIdle { assertNull(sent) }
        compose.onNodeWithTag("invitation-send").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf("colleague@example.test"), sent) }
    }
    @Test fun recordingModeSelectionIsExplicitAndDoesNotStartCapture() {
        var mode: String? = null
        var starts = 0
        compose.setContent { MeetSpaceTheme { CallRecordingDialog("Встреча", CallRecordingUiState(
            status = CallRecordingStatus.STOPPED, canStart = true, modes = listOf("composite", "audio_only", "individual_tracks", "screen_focus")),
            CallRecordingActions(selectMode = { mode = it }, start = { starts++ }), phone = false, close = {}) } }
        compose.onNodeWithTag("recording-mode-individual_tracks").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("individual_tracks", mode); assertEquals(0, starts) }
    }
}
