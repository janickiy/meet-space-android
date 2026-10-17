package com.meetspace.core.data

import com.meetspace.core.model.*
import kotlinx.coroutines.channels.ReceiveChannel

/** Account/origin scoped. No access token, signed URL or cookie crosses this interface. */
interface MessagingRepository {
    suspend fun conversations(query: ChatQuery = ChatQuery()): ChatPage<ChatConversation>
    suspend fun conversation(id: String): ChatConversation
    suspend fun searchPeers(search: String): List<ChatPeer>
    suspend fun createDirect(peerId: String): ChatConversation
    suspend fun createGroup(name: String, description: String, memberIds: List<String>, clientRequestId: String): ChatConversation
    suspend fun updateGroup(id: String, name: String, description: String): ChatConversation
    suspend fun groupMembers(id: String): List<ChatMember>
    suspend fun addGroupMembers(id: String, userIds: List<String>): ChatConversation
    suspend fun removeGroupMember(id: String, userId: String): ChatConversation
    suspend fun changeGroupRole(id: String, userId: String, role: String): ChatConversation
    suspend fun transferGroupOwnership(id: String, userId: String): ChatConversation
    suspend fun leaveGroup(id: String)
    suspend fun deleteGroup(id: String)
    suspend fun setConversationNotifications(id: String, enabled: Boolean): ChatConversation
    suspend fun messages(id: String, cursor: String? = null): MessagePage
    suspend fun searchMessages(id: String, query: String, cursor: String? = null): ChatPage<ChatMessage> = throw AppFailure(FailureKind.UNAVAILABLE, "Поиск сообщений недоступен.")
    suspend fun messageContext(id: String, messageId: String): ChatPage<ChatMessage> = throw AppFailure(FailureKind.UNAVAILABLE, "Контекст сообщения недоступен.")
    suspend fun messagePins(id: String, cursor: String? = null): ChatPage<ChatMessage> = throw AppFailure(FailureKind.UNAVAILABLE, "Закрепления недоступны.")
    suspend fun pinMessage(id: String, messageId: String, pinned: Boolean): Unit = throw AppFailure(FailureKind.UNAVAILABLE, "Закрепления недоступны.")
    suspend fun clearConversation(id: String, hide: Boolean): Unit = throw AppFailure(FailureKind.UNAVAILABLE, "Действие недоступно.")
    suspend fun sendMessage(id: String, input: ChatMessageInput): ChatMessage
    suspend fun editMessage(id: String, messageId: String, text: String): ChatMessage
    suspend fun deleteMessage(id: String, messageId: String): ChatMessage
    suspend fun markConversationRead(id: String, messageId: String): ChatReadState
    suspend fun uploadAttachment(id: String, filename: String, mimeType: String, bytes: ByteArray, clientRequestId: String): ChatAttachment
    suspend fun downloadAttachment(id: String, attachment: ChatAttachment): ByteArray
    suspend fun folders(): List<ChatFolder>
    suspend fun createFolder(name: String): ChatFolder
    suspend fun renameFolder(id: String, name: String): ChatFolder
    suspend fun deleteFolder(id: String)
    suspend fun reorderFolders(ids: List<String>): List<ChatFolder>
    suspend fun folderItems(folderId: String? = null, query: FolderQuery = FolderQuery()): ChatPage<FolderEntry>
    suspend fun setFolderItem(folderId: String, kind: String, itemId: String, included: Boolean): ChatFolder
    suspend fun peerPresence(id: String): Boolean
    suspend fun notifications(cursor: String? = null): NotificationPage
    suspend fun readNotification(id: String): InboxNotification
    suspend fun notificationPreferences(): NotificationPreferences
    suspend fun saveNotificationPreferences(preferences: NotificationPreferences): NotificationPreferences
    suspend fun integrationCapabilities(): IntegrationCapabilities
    suspend fun registerPushDevice(token: String): PushDevice
    suspend fun revokePushDevice(id: String)
    suspend fun openUserEvents(): UserEvents
}

interface UserEvents {
    val events: ReceiveChannel<UserEvent>
    fun close()
}
class UserEventsDisconnected(val retryable: Boolean) : Exception("Поток обновлений недоступен.")
