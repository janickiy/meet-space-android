package com.meetspace.app

import com.meetspace.core.model.InboxNotification

/** Only scoped application routes are accepted; notification payloads never open external URLs. */
internal sealed interface NotificationDestination {
    data class Meeting(val id: String) : NotificationDestination
    data class Conversation(val id: String, val messageId: String?) : NotificationDestination
    data class Recording(val meetingId: String, val recordingId: String?, val summary: Boolean) : NotificationDestination
}

internal fun InboxNotification.destination(): NotificationDestination? {
    val meeting = conferenceId?.takeIf(String::isNotBlank)
    val conversation = conversationId?.takeIf(String::isNotBlank)
    if (type.startsWith("recording.") || type.startsWith("transcript.") || type.startsWith("summary.")) {
        return meeting?.let { NotificationDestination.Recording(it, recordingId?.takeIf(String::isNotBlank), type.startsWith("summary.")) }
    }
    if (conversation != null) return NotificationDestination.Conversation(conversation, messageId?.takeIf(String::isNotBlank))
    return meeting?.let(NotificationDestination::Meeting)
}
