package com.meetspace.app.ui

import com.meetspace.core.model.ChatAttachment
import com.meetspace.core.model.ChatConversation
import com.meetspace.core.model.ChatFolder
import com.meetspace.core.model.ChatMember
import com.meetspace.core.model.ChatMessage
import com.meetspace.core.model.ChatPeer
import com.meetspace.core.model.FolderEntry
import com.meetspace.core.model.InboxNotification
import com.meetspace.core.model.NotificationPreferences

/** Private messaging content is transient: no rememberSaveable or saved-state persistence. */
data class MessagingLoad<T>(val data: T? = null, val loading: Boolean = false, val error: String? = null)

enum class ChatFilter { ALL, DIRECT, UNREAD }

data class MessagingUiState(
    val selfId: String = "",
    val conversations: MessagingLoad<List<ChatConversation>> = MessagingLoad(),
    val conversationCursor: String? = null,
    val query: String = "",
    val filter: ChatFilter = ChatFilter.ALL,
    val selectedId: String? = null,
    val detail: MessagingLoad<ChatConversation> = MessagingLoad(),
    val messages: MessagingLoad<List<ChatMessage>> = MessagingLoad(),
    val messageCursor: String? = null,
    val draft: String = "",
    val replyTo: ChatMessage? = null,
    val editing: ChatMessage? = null,
    val attachments: List<ChatAttachment> = emptyList(),
    val members: MessagingLoad<List<ChatMember>> = MessagingLoad(),
    val memberCursor: String? = null,
    val peerOnline: Boolean? = null,
    val users: MessagingLoad<List<ChatPeer>> = MessagingLoad(),
    val userQuery: String = "",
    val folders: MessagingLoad<List<ChatFolder>> = MessagingLoad(),
    val selectedFolderId: String? = null,
    val folderEntries: MessagingLoad<List<FolderEntry>> = MessagingLoad(),
    val folderCursor: String? = null,
    val folderCandidates: MessagingLoad<List<FolderEntry>> = MessagingLoad(),
    val candidateCursor: String? = null,
    val candidateQuery: String = "",
    val notifications: MessagingLoad<List<InboxNotification>> = MessagingLoad(),
    val notificationCursor: String? = null,
    val notificationUnread: Long = 0,
    val notificationPreferences: MessagingLoad<NotificationPreferences> = MessagingLoad(),
    val notificationPermissionEnabled: Boolean = false,
    val pushConfigured: Boolean = false,
    val pushEnabled: Boolean = false,
    val pushBusy: Boolean = false,
    val pushMessage: String? = null,
    val realtimeLabel: String = "Подключаем обновления…",
    val busy: Boolean = false,
    val sending: Boolean = false,
    val attachmentBusy: Boolean = false,
    val pendingSend: Boolean = false,
    val mutationRevision: Long = 0,
    val error: String? = null,
    val notice: String? = null,
)

/** The ViewModel owns effects and server acknowledgments; the UI only requests actions. */
data class MessagingActions(
    val activate: () -> Unit = {},
    val refresh: () -> Unit = {},
    val query: (String) -> Unit = {},
    val filter: (ChatFilter) -> Unit = {},
    val moreConversations: () -> Unit = {},
    val open: (String) -> Unit = {},
    val back: () -> Unit = {},
    val refreshMessages: () -> Unit = {},
    val moreMessages: () -> Unit = {},
    val draft: (String) -> Unit = {},
    val send: () -> Unit = {},
    val reply: (ChatMessage) -> Unit = {},
    val edit: (ChatMessage) -> Unit = {},
    val cancelCompose: () -> Unit = {},
    val delete: (ChatMessage) -> Unit = {},
    val markRead: () -> Unit = {},
    val pickAttachment: () -> Unit = {},
    val removeAttachment: (String) -> Unit = {},
    val download: (ChatAttachment) -> Unit = {},
    val searchUsers: (String) -> Unit = {},
    val createDirect: (String) -> Unit = {},
    val createGroup: (String, String, List<String>) -> Unit = { _, _, _ -> },
    val loadMembers: () -> Unit = {},
    val moreMembers: () -> Unit = {},
    val editGroup: (String, String) -> Unit = { _, _ -> },
    val addMember: (String) -> Unit = {},
    val removeMember: (String) -> Unit = {},
    val leaveGroup: () -> Unit = {},
    val deleteGroup: () -> Unit = {},
    val conversationNotifications: (Boolean) -> Unit = {},
    val selectFolder: (String?) -> Unit = {},
    val createFolder: (String) -> Unit = {},
    val renameFolder: (String, String) -> Unit = { _, _ -> },
    val deleteFolder: (String) -> Unit = {},
    val reorderFolders: (List<String>) -> Unit = {},
    val moreFolderItems: () -> Unit = {},
    val searchFolderCandidates: (String) -> Unit = {},
    val moreFolderCandidates: () -> Unit = {},
    val folderMembership: (String, String, String, Boolean) -> Unit = { _, _, _, _ -> },
    val openMeeting: (String) -> Unit = {},
    val loadNotifications: () -> Unit = {},
    val moreNotifications: () -> Unit = {},
    val readNotification: (String) -> Unit = {},
    val readAllNotifications: () -> Unit = {},
    val openNotification: (InboxNotification) -> Unit = {},
    val saveNotificationPreferences: (NotificationPreferences) -> Unit = {},
    val requestSystemNotifications: () -> Unit = {},
    val enablePush: () -> Unit = {},
    val disablePush: () -> Unit = {},
    val clearMessage: () -> Unit = {},
)
