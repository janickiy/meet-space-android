package com.meetspace.core.data

import com.meetspace.core.model.ChatMessageInput
import com.meetspace.core.model.ChatReadState
import com.meetspace.core.model.MeetingChatMessage
import com.meetspace.core.model.MeetingChatPage
import com.meetspace.core.model.MeetingChatAttachment

/**
 * Authenticated, account/origin-scoped persistent conference discussion.
 * The backend has one message stream: chat and comment panels are views of the same records.
 * Sending requires an active conference and a joined participant; the server enforces it.
 * Reuse clientRequestId when an acknowledgement is lost. Never retry a mutation with a new ID.
 */
interface MeetingChatRepository {
    suspend fun meetingMessages(conferenceId: String, cursor: String? = null, limit: Int = 30): MeetingChatPage
    suspend fun sendMeetingMessage(conferenceId: String, input: ChatMessageInput): MeetingChatMessage
    suspend fun meetingChatReadState(conferenceId: String): ChatReadState
    suspend fun markMeetingChatRead(conferenceId: String, messageId: String): ChatReadState
    /** Bounded init/upload/finalize flow. Keep the same request ID and bytes when retrying. */
    suspend fun uploadMeetingAttachment(conferenceId: String, filename: String, mimeType: String,
        bytes: ByteArray, clientRequestId: String): MeetingChatAttachment
    suspend fun downloadMeetingAttachment(conferenceId: String, attachment: MeetingChatAttachment): ByteArray
}
