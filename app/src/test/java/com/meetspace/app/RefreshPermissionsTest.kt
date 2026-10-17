package com.meetspace.app

import com.meetspace.core.model.*
import com.meetspace.app.ui.canDeleteRecording
import java.time.Instant
import org.junit.Test
import org.junit.Assert.*

class RefreshPermissionsTest {
    private val actor = MeetingParticipant("self", "room", "user", "Self", "owner", "joined", "admitted")
    private val target = actor.copy(id = "other", userId = "peer", role = "participant")
    @Test fun moderationMatchesOwnerAndCohostMatrix() {
        assertTrue(canModerateMeeting(actor, target, "camera"))
        val cohost = actor.copy(role = "co_host")
        listOf("mute", "screen", "kick").forEach { assertTrue(canModerateMeeting(cohost, target, it)) }
        listOf("camera", "role").forEach { assertFalse(canModerateMeeting(cohost, target, it)) }
        assertFalse(canModerateMeeting(actor, actor, "mute"))
        assertFalse(canModerateMeeting(actor, target.copy(role = "owner"), "kick"))
        assertFalse(canModerateMeeting(actor, target.copy(admissionState = "waiting"), "mute"))
        assertFalse(canModerateMeeting(actor.copy(status = "left"), target, "mute"))
    }
    @Test fun recordingDeletionRequiresTerminalStateAndOwnerOrInitiator() {
        val record = Recording("r", "room", "composite", "ready", Instant.EPOCH, requestedBy = "requester")
        val meeting = Meeting("room", "owner", "Meeting", "", "", "active", Instant.EPOCH, Instant.EPOCH)
        assertTrue(canDeleteRecording(record, meeting, "owner")); assertTrue(canDeleteRecording(record, meeting, "requester"))
        assertFalse(canDeleteRecording(record, meeting, "peer")); assertFalse(canDeleteRecording(record, meeting, ""))
        assertFalse(canDeleteRecording(record.copy(status = "processing"), meeting, "owner"))
    }
}
