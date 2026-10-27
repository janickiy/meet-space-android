package com.meetspace.app

import com.meetspace.core.media.CallPhase
import com.meetspace.core.media.CallState
import org.junit.Assert.*
import org.junit.Test

class CallPermissionRequestTest {
    private val call = CallState(CallPhase.PREJOIN, "meeting", "Meeting")

    @Test fun normalPermissionDialogResultForTheSameStartedCallIsAcceptedExactlyOnce() {
        val request = CallPermissionRequest()
        assertTrue(request.begin("server|account", call))
        assertTrue(request.consume("server|account", call, foreground = true))
        assertFalse(request.consume("server|account", call, foreground = true))
    }

    @Test fun stoppedHostOrInvalidatedRequestCannotEnableDevicesOnResume() {
        val request = CallPermissionRequest()
        assertTrue(request.begin("server|account", call))
        assertFalse(request.consume("server|account", call, foreground = false))
        assertFalse(request.consume("server|account", call, foreground = true))
        assertTrue(request.begin("server|account", call))
        request.invalidate()
        assertFalse(request.consume("server|account", call, foreground = true))
    }

    @Test fun accountMeetingParticipantPhaseAndDirectCallAreAllPartOfTheRequestIdentity() {
        val changed = listOf(
            "other-server|account" to call,
            "server|other-account" to call,
            "server|account" to call.copy(conferenceId = "other-meeting"),
            "server|account" to call.copy(participantId = "other-participant"),
            "server|account" to call.copy(phase = CallPhase.CONNECTED),
            "server|account" to call.copy(directCallId = "direct-call"),
        )
        for ((account, current) in changed) {
            val request = CallPermissionRequest()
            assertTrue(request.begin("server|account", call))
            assertFalse(request.consume(account, current, foreground = true))
        }
    }

    @Test fun invalidatingAnOutstandingDialogDoesNotRebindItsResultToANewLaunch() {
        val request = CallPermissionRequest()
        assertTrue(request.begin("server|account", call))
        request.invalidate()
        assertTrue(request.awaitingResult)
        assertFalse(request.begin("server|account", call.copy(conferenceId = "new-meeting")))
        assertFalse(request.consume("server|account", call.copy(conferenceId = "new-meeting"), foreground = true))
        assertTrue(request.begin("server|account", call.copy(conferenceId = "new-meeting")))
        assertTrue(request.consume("server|account", call.copy(conferenceId = "new-meeting"), foreground = true))
    }

    @Test fun explicitProjectionRequestCanSurviveItsSystemPickerWhenForegroundAndTargetAreRestored() {
        val connected = call.copy(phase = CallPhase.CONNECTED, participantId = "participant")
        val request = CallPermissionRequest()
        assertTrue(request.begin("server|account", connected))
        // Unlike device toggles, the host keeps this explicit request while a full-screen
        // system projection picker owns the display; it still requires foreground on return.
        assertTrue(request.consume("server|account", connected, foreground = true))
        assertTrue(request.begin("server|account", connected))
        assertFalse(request.consume("server|account", connected, foreground = false))
    }
}
