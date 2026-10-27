package com.meetspace.app

import com.meetspace.core.model.InboxNotification
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class NotificationDestinationTest {
    private fun event(type: String, meeting: String? = null, conversation: String? = null, record: String? = null) =
        InboxNotification("event", type, Instant.EPOCH, conferenceId = meeting, conversationId = conversation,
            messageId = "message", recordingId = record)

    @Test fun meetingEventsOpenMeetingDetails() {
        listOf("conference.invited", "conference.soon", "conference.rescheduled", "conference.cancelled", "admission.decided").forEach {
            assertEquals(NotificationDestination.Meeting("meeting"), event(it, meeting = "meeting").destination())
        }
    }

    @Test fun chatEventsPreferConversationAndKeepMessageContext() {
        assertEquals(NotificationDestination.Conversation("chat", "message"), event("chat.message", "legacy", "chat").destination())
        assertEquals(NotificationDestination.Meeting("legacy"), event("chat.message", "legacy").destination())
    }

    @Test fun materialsOpenExactRecordingAndSummaryTab() {
        listOf("recording.ready", "transcript.ready", "transcript.failed").forEach {
            assertEquals(NotificationDestination.Recording("meeting", "record", false), event(it, "meeting", record = "record").destination())
        }
        listOf("summary.ready", "summary.failed").forEach {
            assertEquals(NotificationDestination.Recording("meeting", "record", true), event(it, "meeting", record = "record").destination())
        }
    }

    @Test fun legacyMaterialEventFallsBackToMeetingRecordings() {
        assertEquals(NotificationDestination.Recording("meeting", null, false), event("recording.ready", "meeting").destination())
    }

    @Test fun missingResourceDoesNotInventAnExternalRoute() {
        assertNull(event("unknown").destination())
        assertNull(event("recording.ready", record = "record").destination())
        assertNull(event("chat.message", meeting = "", conversation = " ").destination())
    }
}
