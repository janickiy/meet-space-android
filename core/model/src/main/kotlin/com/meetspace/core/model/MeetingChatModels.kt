package com.meetspace.core.model

import java.time.Instant

/** Persisted discussion belonging to a conference, separate from personal/group conversations. */
data class MeetingChatMessage(
    val id: String,
    val conferenceId: String,
    val senderId: String,
    val senderName: String,
    val clientRequestId: String,
    val text: String,
    val sequence: Long,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant? = null,
    val replyTo: String? = null,
    val replyPreview: ChatReply? = null,
    val attachments: List<MeetingChatAttachment> = emptyList(),
)

data class MeetingChatAttachment(
    val id: String, val conferenceId: String, val filename: String, val mimeType: String,
    val size: Long, val status: String, val checksum: String = "", val messageId: String? = null,
)

/** Items are chronological; nextCursor loads messages older than this page. */
data class MeetingChatPage(
    val items: List<MeetingChatMessage>, val nextCursor: String? = null,
    val unreadCount: Long = 0, val lastReadMessageId: String? = null,
)
