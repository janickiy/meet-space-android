package com.meetspace.core.model

import java.time.Instant

data class ChatPeer(val id: String, val displayName: String, val avatarVersion: String? = null)
data class ChatMember(val id: String, val displayName: String, val role: String, val online: Boolean? = null,
    val avatarVersion: String? = null)
data class ChatConversation(
    val id: String, val type: String, val peer: ChatPeer? = null, val name: String = "",
    val description: String = "", val myRole: String = "", val memberCount: Int = 0,
    val preview: String = "", val unreadCount: Long = 0, val notificationsEnabled: Boolean = true,
    val createdAt: Instant = Instant.EPOCH, val updatedAt: Instant = Instant.EPOCH,
    val lastMessageAt: Instant? = null, val lastMessageId: String? = null,
    val avatarVersion: String? = null,
) {
    val title: String get() = if (type == "direct") peer?.displayName.orEmpty() else name
    val canManage: Boolean get() = type == "group" && myRole in setOf("owner", "admin")
}
data class ChatQuery(val search: String = "", val type: String? = null, val unreadOnly: Boolean = false,
    val cursor: String? = null, val limit: Int = 30)
data class ChatPage<T>(val items: List<T>, val nextCursor: String? = null, val unreadCount: Long = 0)
data class ChatAttachment(val id: String, val conversationId: String, val filename: String,
    val mimeType: String, val size: Long, val status: String, val checksum: String = "", val messageId: String? = null)
data class ChatReply(val id: String, val text: String, val senderName: String, val deleted: Boolean)
data class ChatMessage(
    val id: String, val conversationId: String, val senderId: String, val senderName: String,
    val clientRequestId: String, val text: String, val sequence: Long, val version: Long,
    val createdAt: Instant, val updatedAt: Instant, val deletedAt: Instant? = null,
    val replyTo: String? = null, val replyPreview: ChatReply? = null,
    val attachments: List<ChatAttachment> = emptyList(),
    val kind: String = "message", val call: DirectCallHistory? = null,
    val pinned: Boolean = false,
)
data class ChatMessageInput(val clientRequestId: String, val text: String,
    val replyTo: String? = null, val attachmentIds: List<String> = emptyList())
data class MessagePage(val items: List<ChatMessage>, val nextCursor: String? = null,
    val unreadCount: Long = 0, val lastReadMessageId: String? = null)
data class ChatReadState(val unreadCount: Long, val lastReadMessageId: String?)
data class ChatFolder(val id: String, val name: String, val position: Int = 0, val itemCount: Long = 0,
    val conversationCount: Long = 0, val conferenceCount: Long = 0)
data class FolderQuery(val type: String = "all", val search: String = "", val cursor: String? = null,
    val limit: Int = 30, val candidates: Boolean = false)
data class FolderEntry(val type: String, val conversation: ChatConversation? = null,
    val meeting: Meeting? = null, val inFolder: Boolean = false) {
    val id: String get() = conversation?.id ?: meeting?.id.orEmpty()
    val title: String get() = conversation?.title ?: meeting?.title.orEmpty()
}
data class InboxNotification(val id: String, val type: String, val createdAt: Instant,
    val readAt: Instant? = null, val conferenceId: String? = null, val conversationId: String? = null,
    val messageId: String? = null, val scheduledAt: Instant? = null,
    val recordingId: String? = null, val transcriptId: String? = null, val summaryId: String? = null)
data class NotificationPage(val items: List<InboxNotification>, val nextCursor: String? = null, val unreadCount: Long = 0)
data class NotificationPreferences(val invitation: Boolean = true, val reminder: Boolean = true,
    val recording: Boolean = true, val summary: Boolean = true, val email: Boolean = false, val push: Boolean = false)
data class IntegrationCapabilities(val push: String, val email: String = "noop", val calendar: String = "noop")
data class PushDevice(val id: String, val platform: String, val provider: String)
/** Minimal invalidation event: authoritative contents are always read with current REST permissions. */
data class UserEvent(val id: String, val type: String, val conversationId: String? = null,
    val folderId: String? = null, val userId: String? = null, val notificationId: String? = null,
    val conferenceId: String? = null, val callId: String? = null)
