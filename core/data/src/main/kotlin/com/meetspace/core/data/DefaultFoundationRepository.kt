package com.meetspace.core.data

import com.meetspace.core.model.*
import com.meetspace.core.data.MessagingApi.Companion.fields
import com.meetspace.core.data.MessagingApi.Companion.inputId
import com.meetspace.core.data.MessagingApi.Companion.inputText
import com.meetspace.core.data.MessagingApi.Companion.input
import com.meetspace.core.data.MessagingApi.Companion.path
import com.meetspace.core.data.MessagingApi.Companion.items
import com.meetspace.core.data.MessagingApi.Companion.item
import com.meetspace.core.data.MessagingApi.Companion.id
import com.meetspace.core.data.MessagingApi.Companion.bool
import com.meetspace.core.data.MessagingApi.Companion.text
import com.meetspace.core.model.AccountSession
import com.meetspace.core.model.AppFailure
import com.meetspace.core.model.FailureKind
import com.meetspace.core.model.LogoutResult
import com.meetspace.core.model.RepositoryState
import com.meetspace.core.model.ServerProfile
import com.meetspace.core.model.ThemeMode
import com.meetspace.core.model.Meeting
import com.meetspace.core.model.MeetingHistory
import com.meetspace.core.model.MeetingInput
import com.meetspace.core.model.MeetingPage
import com.meetspace.core.model.MeetingParticipant
import com.meetspace.core.model.MeetingTransition
import com.meetspace.core.model.MeetingsQuery
import com.meetspace.core.model.User
import java.time.Instant
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.UUID
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Serial operations ensure that a completed switch cannot receive an older origin's result. */
internal class DefaultFoundationRepository(
    private val preferences: ProfileStore,
    private val credentials: CredentialStore,
    private val api: MeetSpaceApi,
) : FoundationRepository, MeetingsRepository, CallRepository, MessagingRepository, RecordingsRepository, MeetingChatRepository, DirectCallsRepository {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(RepositoryState())
    override val state = mutableState.asStateFlow()
    private var initialized = false
    private var active: AuthContext? = null
    private val meetingsApi = MeetingsApi(api)
    private var call: ScopedCallSignaling? = null

    private val messagingApi = MessagingApi(api)
    private val directCallsApi = DirectCallsApi(api)
    private val meetingChatApi = MeetingChatApi(api)
    private var userEvents: ScopedUserEvents? = null
    private val recordingsApi = RecordingsApi(api)
    private val recordingLeases = linkedSetOf<ScopedRecordingAccess>()

    override suspend fun meetingMessages(conferenceId: String, cursor: String?, limit: Int) = meetingOperation(true) {
        meetingChatApi.list(it.scope.origin, it.token, conferenceId, cursor, limit)
    }
    override suspend fun sendMeetingMessage(conferenceId: String, input: ChatMessageInput) = meetingOperation(false) {
        meetingChatApi.send(it.scope.origin, it.token, conferenceId, input).also { message ->
            if (message.clientRequestId != input.clientRequestId || message.senderId != it.scope.accountId) throw InvalidContract()
        }
    }
    override suspend fun meetingChatReadState(conferenceId: String) = meetingOperation(true) {
        meetingChatApi.read(it.scope.origin, it.token, conferenceId)
    }
    override suspend fun markMeetingChatRead(conferenceId: String, messageId: String) = meetingOperation(false) {
        meetingChatApi.read(it.scope.origin, it.token, conferenceId, messageId)
    }
    override suspend fun uploadMeetingAttachment(conferenceId: String, filename: String, mimeType: String,
        bytes: ByteArray, clientRequestId: String) = meetingOperation(false) {
        meetingChatApi.upload(it.scope.origin, it.token, it.scope.accountId, conferenceId, filename, mimeType, bytes, clientRequestId)
    }
    override suspend fun downloadMeetingAttachment(conferenceId: String, attachment: MeetingChatAttachment) = meetingOperation(true) {
        meetingChatApi.download(it.scope.origin, it.token, conferenceId, attachment)
    }

    override suspend fun startRecording(conferenceId: String, mode: String) = meetingOperation(false) {
        recordingsApi.start(it.scope.origin, it.token, conferenceId, mode).also { recording ->
            if (recording.requestedBy != it.scope.accountId) throw InvalidContract()
        }
    }
    override suspend fun stopRecording(conferenceId: String, recordingId: String) = meetingOperation(false) {
        recordingsApi.stop(it.scope.origin, it.token, conferenceId, recordingId)
    }

    override suspend fun recordings(conferenceId: String, offset: Int, limit: Int) = meetingOperation(true) {
        recordingsApi.list(it.scope.origin, it.token, conferenceId, offset, limit)
    }
    override suspend fun recording(conferenceId: String, recordingId: String) = meetingOperation(true) {
        recordingsApi.read(it.scope.origin, it.token, conferenceId, recordingId).recording
    }
    override suspend fun recordingAccess(conferenceId: String, recordingId: String, fileId: String): RecordingAccess = meetingOperation(true) {
        RecordingsApi.fileId(fileId)
        val card = recordingsApi.read(it.scope.origin, it.token, conferenceId, recordingId)
        val file = card.recording.files.firstOrNull { item -> item.id == fileId && item.available }
        val url = card.urls[fileId]
        if (card.recording.status != "ready" || file == null || url == null) {
            throw AppFailure(FailureKind.NOT_FOUND, "Файл записи пока недоступен.")
        }
        val lease = ScopedRecordingAccess(file, url, api.recordingClient()) { ended ->
            synchronized(recordingLeases) { recordingLeases.remove(ended) }
        }
        val old = synchronized(recordingLeases) {
            val previous = recordingLeases.firstOrNull().takeIf { recordingLeases.size >= 8 }
            if (previous != null) recordingLeases.remove(previous)
            recordingLeases.add(lease)
            previous
        }
        old?.close()
        lease
    }
    override suspend fun recordingTranscript(conferenceId: String, recordingId: String) = meetingOperation(true) {
        recordingsApi.transcript(it.scope.origin, it.token, conferenceId, recordingId)
    }
    override suspend fun recordingSegments(conferenceId: String, recordingId: String, offset: Int, limit: Int) = meetingOperation(true) {
        recordingsApi.segments(it.scope.origin, it.token, conferenceId, recordingId, offset, limit)
    }
    override suspend fun recordingSummary(conferenceId: String, recordingId: String) = meetingOperation(true) {
        recordingsApi.summary(it.scope.origin, it.token, conferenceId, recordingId)
    }

    override suspend fun conversations(query: ChatQuery) = meetingOperation(true) { messagingApi.conversations(it.scope.origin, it.token, query) }
    override suspend fun conversation(id: String) = meetingOperation(true) { messagingApi.conversation(it.scope.origin, it.token, id) }
    override suspend fun searchPeers(search: String) = meetingOperation(true) { messagingApi.peers(it.scope.origin, it.token, search) }
    override suspend fun createDirect(peerId: String) = meetingOperation(false) {
        messagingApi.mutateConversation(it.scope.origin, it.token, listOf("conversations", "direct"), "POST", fields("userId" to inputId(peerId)))
    }
    override suspend fun createGroup(name: String, description: String, memberIds: List<String>, clientRequestId: String) = meetingOperation(false) {
        validateMembers(memberIds)
        messagingApi.mutateConversation(it.scope.origin, it.token, listOf("conversations", "group"), "POST",
            fields("name" to inputText(name, 50, true), "description" to inputText(description, 200, multiline = true),
                "memberIds" to memberIds, "clientRequestId" to inputId(clientRequestId)))
    }
    override suspend fun updateGroup(id: String, name: String, description: String) = meetingOperation(false) {
        messagingApi.mutateConversation(it.scope.origin, it.token, path(id), "PATCH",
            fields("name" to inputText(name, 50, true), "description" to inputText(description, 200, multiline = true)), id)
    }
    override suspend fun groupMembers(id: String) = meetingOperation(true) { messagingApi.members(it.scope.origin, it.token, id) }
    override suspend fun addGroupMembers(id: String, userIds: List<String>) = meetingOperation(false) {
        validateMembers(userIds)
        messagingApi.mutateConversation(it.scope.origin, it.token, path(id) + "members", "POST", fields("userIds" to userIds), id)
    }
    override suspend fun removeGroupMember(id: String, userId: String) = meetingOperation(false) {
        messagingApi.mutateConversation(it.scope.origin, it.token, path(id) + listOf("members", inputId(userId)), "DELETE", null, id)
    }
    override suspend fun changeGroupRole(id: String, userId: String, role: String) = meetingOperation(false) {
        if (role !in setOf("admin", "member")) input()
        messagingApi.mutateConversation(it.scope.origin, it.token, path(id) + listOf("members", inputId(userId)), "PATCH", fields("role" to role), id)
    }
    override suspend fun transferGroupOwnership(id: String, userId: String) = meetingOperation(false) {
        messagingApi.mutateConversation(it.scope.origin, it.token, path(id) + "ownership", "POST", fields("userId" to inputId(userId)), id)
    }
    override suspend fun leaveGroup(id: String) { meetingOperation(false) { messagingApi.request(it.scope.origin, it.token, path(id) + "leave", "POST") } }
    override suspend fun deleteGroup(id: String) { meetingOperation(false) { messagingApi.request(it.scope.origin, it.token, path(id), "DELETE") } }
    override suspend fun setConversationNotifications(id: String, enabled: Boolean) = meetingOperation(false) {
        messagingApi.mutateConversation(it.scope.origin, it.token, path(id) + "preferences", "PATCH", fields("notificationsEnabled" to enabled), id)
    }
    override suspend fun messages(id: String, cursor: String?) = meetingOperation(true) { messagingApi.messages(it.scope.origin, it.token, id, cursor) }
    override suspend fun sendMessage(id: String, input: ChatMessageInput) = meetingOperation(false) {
        if (input.attachmentIds.size > 5 || input.attachmentIds.distinct().size != input.attachmentIds.size) input()
        input.attachmentIds.forEach(::inputId)
        val body = fields("clientRequestId" to inputId(input.clientRequestId),
            "text" to inputText(input.text, 4000, input.attachmentIds.isEmpty(), multiline = true),
            "replyTo" to input.replyTo?.let(::inputId), "attachmentIds" to input.attachmentIds)
        messagingApi.mutateMessage(it.scope.origin, it.token, id, null, "POST", body).also { result ->
            if (result.clientRequestId != input.clientRequestId || result.senderId != it.scope.accountId) throw InvalidContract()
        }
    }
    override suspend fun editMessage(id: String, messageId: String, text: String) = meetingOperation(false) {
        messagingApi.mutateMessage(it.scope.origin, it.token, id, messageId, "PATCH", fields("text" to inputText(text, 4000, multiline = true)))
    }
    override suspend fun deleteMessage(id: String, messageId: String) = meetingOperation(false) {
        messagingApi.mutateMessage(it.scope.origin, it.token, id, messageId, "DELETE")
    }
    override suspend fun markConversationRead(id: String, messageId: String) = meetingOperation(false) { messagingApi.read(it.scope.origin, it.token, id, messageId) }
    override suspend fun uploadAttachment(id: String, filename: String, mimeType: String, bytes: ByteArray, clientRequestId: String) = meetingOperation(false) {
        messagingApi.upload(it.scope.origin, it.token, id, filename, mimeType, bytes, clientRequestId)
    }
    override suspend fun downloadAttachment(id: String, attachment: ChatAttachment) = meetingOperation(true) {
        messagingApi.download(it.scope.origin, it.token, id, attachment)
    }
    override suspend fun folders() = meetingOperation(true) { messagingApi.folders(it.scope.origin, it.token) }
    override suspend fun createFolder(name: String) = meetingOperation(false) {
        messagingApi.mutateFolder(it.scope.origin, it.token, listOf("folders"), "POST", fields("name" to inputText(name, 50, true)))
    }
    override suspend fun renameFolder(id: String, name: String) = meetingOperation(false) {
        messagingApi.mutateFolder(it.scope.origin, it.token, listOf("folders", inputId(id)), "PATCH", fields("name" to inputText(name, 50, true)), id)
    }
    override suspend fun deleteFolder(id: String) { meetingOperation(false) { messagingApi.request(it.scope.origin, it.token, listOf("folders", inputId(id)), "DELETE") } }
    override suspend fun reorderFolders(ids: List<String>) = meetingOperation(false) {
        validateMembers(ids)
        messagingApi.request(it.scope.origin, it.token, listOf("folders", "order"), "PUT", fields("ids" to ids)).items(100).map(MessagingApi::folder)
    }
    override suspend fun folderItems(folderId: String?, query: FolderQuery) = meetingOperation(true) { messagingApi.folderItems(it.scope.origin, it.token, folderId, query) }
    override suspend fun setFolderItem(folderId: String, kind: String, itemId: String, included: Boolean) = meetingOperation(false) {
        if (kind !in setOf("conversation", "conference")) input()
        messagingApi.mutateFolder(it.scope.origin, it.token, listOf("folders", inputId(folderId), "items", kind, inputId(itemId)),
            if (included) "PUT" else "DELETE", expected = folderId)
    }
    override suspend fun peerPresence(id: String) = meetingOperation(true) {
        val value = messagingApi.request(it.scope.origin, it.token, path(id) + "peer-presence").item()
        if (value.id("conversationId") != id) throw InvalidContract()
        value.id("peerId"); value.bool("online")
    }
    override suspend fun notifications(cursor: String?) = meetingOperation(true) { messagingApi.notifications(it.scope.origin, it.token, it.scope.accountId, cursor) }
    override suspend fun readNotification(id: String) = meetingOperation(false) {
        val item = messagingApi.request(it.scope.origin, it.token, listOf("notifications", inputId(id), "read"), "POST").item()
        MessagingApi.notification(item, it.scope.accountId).also { result -> if (result.id != id) throw InvalidContract() }
    }
    override suspend fun notificationPreferences() = meetingOperation(true) { messagingApi.preferences(it.scope.origin, it.token) }
    override suspend fun saveNotificationPreferences(preferences: NotificationPreferences) = meetingOperation(false) { messagingApi.preferences(it.scope.origin, it.token, preferences) }
    override suspend fun integrationCapabilities() = meetingOperation(true) {
        val body = messagingApi.request(it.scope.origin, it.token, listOf("integrations", "capabilities"))
        IntegrationCapabilities(body.text("push", 20), body.text("email", 20), body.text("calendar", 20))
    }
    override suspend fun registerPushDevice(token: String) = meetingOperation(false) {
        if (token.isBlank() || token.length > 4096 || token.any(Char::isISOControl)) input()
        val item = messagingApi.request(it.scope.origin, it.token, listOf("notifications", "devices"), "POST",
            fields("platform" to "android", "token" to token)).item()
        PushDevice(item.id(), item.text("platform", 20), item.text("provider", 20))
    }
    override suspend fun revokePushDevice(id: String) { meetingOperation(false) {
        messagingApi.request(it.scope.origin, it.token, listOf("notifications", "devices", inputId(id)), "DELETE")
    } }
    override suspend fun openUserEvents(): UserEvents = meetingOperation(true) {
        val response = messagingApi.request(it.scope.origin, it.token, listOf("ws-ticket"), "POST")
        val ticket = response.text("ticket", 43)
        if (!ticket.matches(Regex("[A-Za-z0-9_-]{43}"))) throw InvalidContract()
        userEvents?.close()
        ScopedUserEvents(api, it.scope.origin, ticket).also { events -> userEvents = events }
    }
    private fun validateMembers(ids: List<String>) {
        if (ids.size > 100 || ids.distinct().size != ids.size) input()
        ids.forEach(::inputId)
    }

    override suspend fun startDirectCall(targetUserId: String, clientRequestId: String, clientId: String): DirectCall = meetingOperation(false) { context ->
        if (targetUserId == context.scope.accountId) throw AppFailure(FailureKind.VALIDATION, "Выберите другого пользователя для звонка.")
        scopedDirectCall(directCallsApi.start(context.scope.origin, context.token, targetUserId, clientRequestId, clientId), context)
            .also { if (it.callerUserId != context.scope.accountId) throw InvalidContract() }
    }
    override suspend fun activeDirectCall(): DirectCall? = meetingOperation(true) { context ->
        directCallsApi.active(context.scope.origin, context.token)?.let { scopedDirectCall(it, context) }
    }
    override suspend fun directCall(id: String): DirectCall = meetingOperation(true) { context ->
        scopedDirectCall(directCallsApi.read(context.scope.origin, context.token, id), context)
    }
    override suspend fun directCallAction(id: String, action: DirectCallAction, clientId: String): DirectCall = meetingOperation(false) { context ->
        scopedDirectCall(directCallsApi.action(context.scope.origin, context.token, id, action, clientId), context)
    }
    private fun scopedDirectCall(call: DirectCall, context: AuthContext): DirectCall = call.also {
        if (context.scope.accountId !in setOf(it.callerUserId, it.calleeUserId)) throw InvalidContract()
    }

    override suspend fun openCall(conferenceId: String): CallSignaling = openScopedCall(conferenceId, null)
    override suspend fun openDirectCall(conferenceId: String, clientId: String): CallSignaling = openScopedCall(conferenceId, inputId(clientId))
    private suspend fun openScopedCall(conferenceId: String, clientId: String?): CallSignaling = meetingOperation(readOnly = true) { context ->
        if (runCatching { UUID.fromString(conferenceId).toString() }.getOrNull() != conferenceId) throw InvalidContract()
        val configuration = api.authenticatedRequest(context.scope.origin, listOf("webrtc", "config"), context.token)
        val response = wireJson.parseToJsonElement(api.authenticatedRequest(context.scope.origin,
            listOf("conferences", conferenceId, "ws-ticket"), context.token, method = "POST",
            payload = clientId?.let { fields("clientId" to it).toString() }))
        val ticket = (response as? kotlinx.serialization.json.JsonObject)?.get("ticket")
            ?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content ?: throw InvalidContract()
        if (ticket.isBlank() || ticket.length > 4096 || ticket.any { it.isWhitespace() || it.isISOControl() }) throw InvalidContract()
        call?.close()
        ScopedCallSignaling(configuration, api, context.scope.origin, conferenceId, ticket) { connection, sequence, mic, camera, screen ->
            meetingOperation(readOnly = false) { current ->
                if (current !== context) throw AppFailure(FailureKind.SESSION_EXPIRED, "Учётная запись изменилась.")
                if (runCatching { UUID.fromString(connection).toString() }.getOrNull() != connection) throw InvalidContract()
                api.authenticatedRequest(current.scope.origin, listOf("conferences", conferenceId, "participants", "me", "media"),
                    current.token, method = "PUT", payload = "{\"connectionId\":\"$connection\",\"sequence\":$sequence,\"microphoneEnabled\":$mic,\"cameraEnabled\":$camera,\"screenSharing\":$screen}")
                Unit
            }
        }.also { call = it }
    }

    override suspend fun timeline(query: MeetingsQuery): MeetingPage = meetingOperation(readOnly = true) {
        meetingsApi.timeline(it.scope.origin, it.token, query)
    }

    override suspend fun conference(id: String): Meeting = meetingOperation(readOnly = true) {
        meetingsApi.conference(it.scope.origin, it.token, id)
    }

    override suspend fun createMeeting(input: MeetingInput): Meeting = meetingOperation(readOnly = false) {
        meetingsApi.create(it.scope.origin, it.token, input)
    }

    override suspend fun scheduleMeeting(id: String, scheduledAt: Instant, plannedDurationMin: Int?): Meeting =
        meetingOperation(readOnly = false) { meetingsApi.schedule(it.scope.origin, it.token, id, scheduledAt, plannedDurationMin) }

    override suspend fun transitionMeeting(id: String, transition: MeetingTransition): Meeting =
        meetingOperation(readOnly = false) { meetingsApi.transition(it.scope.origin, it.token, id, transition) }

    override suspend fun membership(id: String): MeetingParticipant? = meetingOperation(readOnly = true) {
        meetingsApi.membership(it.scope.origin, it.token, id)?.also { member ->
            if (member.userId != it.scope.accountId) throw InvalidContract()
        }
    }

    override suspend fun joinMeeting(id: String, inviteCode: String?): MeetingParticipant = meetingOperation(readOnly = false) {
        meetingsApi.join(it.scope.origin, it.token, id, inviteCode).also { member ->
            if (member.userId != it.scope.accountId) throw InvalidContract()
        }
    }

    override suspend fun joinInvite(rawCodeOrUrl: String): MeetingParticipant = meetingOperation(readOnly = false) {
        meetingsApi.joinInvite(it.scope.origin, it.token, rawCodeOrUrl).also { member ->
            if (member.userId != it.scope.accountId) throw InvalidContract()
        }
    }

    override suspend fun leaveMeeting(id: String): MeetingParticipant = meetingOperation(readOnly = false) {
        meetingsApi.leave(it.scope.origin, it.token, id).also { member ->
            if (member.userId != it.scope.accountId) throw InvalidContract()
        }
    }

    override suspend fun history(id: String): MeetingHistory = meetingOperation(readOnly = true) {
        meetingsApi.history(it.scope.origin, it.token, id)
    }

    override suspend fun updateDisplayName(displayName: String): User = meetingOperation(readOnly = false) { context ->
        val name = displayName.trim()
        if (name.isEmpty() || name.codePointCount(0, name.length) > 100 || name.any(Char::isISOControl)) {
            throw AppFailure(FailureKind.VALIDATION, "Имя должно содержать от 1 до 100 символов без управляющих знаков.")
        }
        val user = api.updateDisplayName(context.scope.origin, context.token, context.jar, name)
        val previous = state.value.session ?: throw InvalidContract()
        if (user.id != context.scope.accountId || user.email != previous.user.email || user.isAdmin != previous.user.isAdmin) {
            throw InvalidContract()
        }
        context.jar.stored()?.let { credentials.write(context.scope, it) }
        mutableState.value = state.value.copy(session = previous.copy(user = user))
        user
    }

    /** Serialised with login/switch/logout, so requests cannot cross an account or origin change. */
    private suspend fun <T> meetingOperation(readOnly: Boolean, block: suspend (AuthContext) -> T): T = mutex.withLock {
        protectMeeting(readOnly) {
            val context = active ?: throw AppFailure(FailureKind.SESSION_EXPIRED, "Войдите в учётную запись MeetSpace.")
            if (state.value.selectedProfile?.origin != context.scope.origin ||
                state.value.session?.user?.id != context.scope.accountId) throw InvalidContract()
            try {
                block(context)
            } catch (failure: HttpFailure) {
                if (failure.statusCode != 401) throw failure
                if (context.jar.stored() == null) {
                    expire(context)
                    throw failure
                }
                try {
                    val fresh = api.refresh(context.scope.origin, context.jar)
                    if (fresh.user.id != context.scope.accountId) throw InvalidContract()
                    val cookie = context.jar.stored() ?: throw InvalidContract()
                    credentials.write(context.scope, cookie)
                    context.token = fresh.token
                } catch (refreshFailure: HttpFailure) {
                    if (refreshFailure.statusCode == 401) expire(context)
                    throw refreshFailure
                } catch (invalid: InvalidContract) {
                    expire(context)
                    throw invalid
                }
                if (!readOnly) throw AppFailure(FailureKind.CONFLICT, "Сессия обновлена. Повторите действие.")
                try {
                    block(context)
                } catch (retryFailure: HttpFailure) {
                    if (retryFailure.statusCode == 401) expire(context)
                    throw retryFailure
                }
            }
        }
    }

    private suspend fun expire(context: AuthContext) = withContext(NonCancellable) {
        clearMemory()
        val cleared = state.value.copy(profiles = state.value.profiles.map {
            if (it.origin == context.scope.origin && it.accountId == context.scope.accountId)
                it.copy(accountId = null, accountEmail = null) else it
        })
        mutableState.value = cleared
        var cleanupFailed = false
        try { persist(cleared) } catch (_: Exception) { cleanupFailed = true }
        try { credentials.delete(context.scope) } catch (_: Exception) { cleanupFailed = true }
        if (cleanupFailed) throw storageFailure()
    }

    private suspend fun <T> protectMeeting(readOnly: Boolean, block: suspend () -> T): T = try {
        protect {
            try { block() } catch (failure: HttpFailure) {
                throw when (failure.statusCode) {
                    400, 422 -> AppFailure(FailureKind.VALIDATION, "Проверьте введённые данные.")
                    404 -> AppFailure(FailureKind.NOT_FOUND, "Запрошенные данные не найдены либо больше недоступны.")
                    409 -> AppFailure(FailureKind.CONFLICT, "Состояние изменилось. Обновите данные перед повтором действия.")
                    else -> failure
                }
            }
        }
    } catch (failure: AppFailure) {
        if (!readOnly && failure.kind in setOf(FailureKind.NETWORK, FailureKind.TIMEOUT, FailureKind.UNAVAILABLE)) {
            throw AppFailure(failure.kind, "Ответ сервера не подтверждён. Обновите данные перед повторным действием.")
        }
        throw failure
    }

    override suspend fun addServer(rawUrl: String): ServerProfile = mutex.withLock {
        protect {
            loadSettings()
            val origin = ServerOrigin.normalize(rawUrl)
            // This public request has neither cookies nor Authorization, even for an existing profile.
            val version = api.version(origin)
            val previous = state.value
            val profile = previous.profiles.firstOrNull { it.origin == origin }
                ?: ServerProfile(UUID.randomUUID().toString(), origin)
            clearMemory()
            persist(previous.copy(
                profiles = if (profile in previous.profiles) previous.profiles else previous.profiles + profile,
                selectedProfileId = profile.id, version = version, session = null,
            ))
            if (profile.accountId != null) restoreCurrent(probe = false)
            profile
        }
    }

    override suspend fun selectServer(profileId: String) = mutex.withLock {
        protect {
            loadSettings()
            if (state.value.profiles.none { it.id == profileId }) throw AppFailure(
                FailureKind.INVALID_URL, "Выбранный профиль сервера больше не существует.")
            clearMemory()
            persist(state.value.copy(selectedProfileId = profileId, version = null, session = null))
            restoreCurrent(probe = true)
        }
    }

    override suspend fun removeServer(profileId: String) = mutex.withLock {
        protect {
            loadSettings()
            val profile = state.value.profiles.firstOrNull { it.id == profileId } ?: return@protect
            val selected = profile.id == state.value.selectedProfileId
            if (selected) clearMemory()
            profile.scope()?.let { credentials.delete(it) }
            persist(state.value.copy(
                profiles = state.value.profiles.filterNot { it.id == profileId },
                selectedProfileId = state.value.selectedProfileId.takeUnless { selected },
                version = state.value.version.takeUnless { selected },
                session = state.value.session.takeUnless { selected },
            ))
        }
    }

    override suspend fun login(email: String, password: String) = mutex.withLock {
        protect(isLogin = true) {
            loadSettings()
            val profile = selected()
            val address = email.trim().lowercase(Locale.ROOT)
            if (address.isEmpty() || address.length > 254 || '@' !in address ||
                address.any { it.isWhitespace() || it.isISOControl() } || password.isEmpty() || password.length > 1024) {
                throw AppFailure(FailureKind.INVALID_CREDENTIALS, "Введите email и пароль учётной записи.")
            }
            if (state.value.version == null) {
                // A failed restart/switch probe must not make credentials the first request.
                mutableState.value = state.value.copy(version = api.version(profile.origin))
            }
            signIn(profile, address, password)
        }
    }

    override suspend fun register(displayName: String, email: String, password: String): RegistrationResult = mutex.withLock {
        protect {
            loadSettings()
            val profile = selected()
            if (state.value.session != null) throw AppFailure(FailureKind.CONFLICT, "Сначала выйдите из текущего аккаунта.")
            RegistrationValidation.error(displayName, email, password)?.let {
                throw AppFailure(FailureKind.VALIDATION, it.message)
            }
            val address = email.trim().lowercase(Locale.ROOT)
            RegistrationValidation.error(displayName, address, password)?.let {
                throw AppFailure(FailureKind.VALIDATION, it.message)
            }
            if (state.value.version == null) mutableState.value = state.value.copy(version = api.version(profile.origin))
            val user = protect(isRegistration = true) {
                api.register(profile.origin, displayName.trim(), address, password).also {
                    if (it.email != address) throw InvalidContract()
                }
            }
            try {
                protect(isLogin = true) { signIn(profile, address, password, expectedUserId = user.id) }
                RegistrationResult(user, signedIn = true)
            } catch (failure: AppFailure) {
                // POST /register already succeeded. Never ask the user to create that account again.
                RegistrationResult(user, signedIn = false, signInFailure = failure.message)
            }
        }
    }

    private suspend fun signIn(profile: ServerProfile, address: String, password: String, expectedUserId: String? = null) {
        val jar = SessionCookieJar(profile.origin)
        val signedIn = api.login(profile.origin, address, password, jar)
        if (expectedUserId != null && signedIn.user.id != expectedUserId) throw InvalidContract()
        val candidate = AuthContext(SessionScope(profile.origin, signedIn.user.id), signedIn.token, jar)
        val user = authenticatedGet(candidate) { api.me(profile.origin, candidate.token, jar) }
        if (user.id != candidate.scope.accountId) throw InvalidContract()
        val capabilities = authenticatedGet(candidate) { api.capabilities(profile.origin, candidate.token, jar) }
        val stored = jar.stored()
        val updatedProfile = profile.copy(accountId = user.id, accountEmail = user.email)
        if (stored != null) credentials.write(candidate.scope, stored) else credentials.delete(candidate.scope)
        profile.scope()?.takeIf { it != candidate.scope }?.let { credentials.delete(it) }
        persist(state.value.copy(
            profiles = state.value.profiles.map { if (it.id == profile.id) updatedProfile else it },
            session = AccountSession(user, capabilities, stored != null),
        ))
        clearMemory(clearSessionState = false)
        active = candidate
    }

    override suspend fun restore() = mutex.withLock {
        protect {
            clearMemory()
            initialized = false
            loadSettings()
            restoreCurrent(probe = true)
        }
    }

    override suspend fun logout(): LogoutResult = mutex.withLock {
        protect {
            loadSettings()
            val profile = state.value.selectedProfile ?: return@protect LogoutResult(remoteConfirmed = false)
            val scope = profile.scope()
            val context = active
            var confirmed = false
            try {
                val stored = if (context == null && scope != null) credentials.read(scope) else null
                val jar = context?.jar ?: SessionCookieJar(profile.origin, stored)
                if (context != null || stored != null) {
                    api.logout(profile.origin, context?.token, jar)
                    confirmed = true
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The result distinguishes remote revocation from a local/offline logout.
            } finally {
                withContext(NonCancellable) {
                    clearMemory()
                    val cleared = state.value.copy(profiles = state.value.profiles.map {
                        if (it.id == profile.id) it.copy(accountId = null, accountEmail = null) else it
                    })
                    mutableState.value = cleared
                    // Try both durable protections. A failed file deletion must not leave a profile
                    // pointing at that credential; a failed preference write must still erase it.
                    var cleanupFailed = false
                    try { persist(cleared) } catch (_: Exception) { cleanupFailed = true }
                    try { scope?.let { credentials.delete(it) } } catch (_: Exception) { cleanupFailed = true }
                    if (cleanupFailed) throw storageFailure()
                }
            }
            LogoutResult(confirmed)
        }
    }

    override suspend fun setTheme(mode: ThemeMode) = mutex.withLock {
        protect {
            loadSettings()
            persist(state.value.copy(theme = mode))
        }
    }

    private suspend fun restoreCurrent(probe: Boolean) {
        val profile = state.value.selectedProfile ?: return
        if (probe) mutableState.value = state.value.copy(version = api.version(profile.origin))
        val scope = profile.scope() ?: return
        val stored = credentials.read(scope) ?: return
        val jar = SessionCookieJar(profile.origin, stored)
        try {
            val signedIn = api.refresh(profile.origin, jar)
            if (signedIn.user.id != scope.accountId) throw InvalidContract()
            // Preserve a rotated cookie even if a later discovery GET fails.
            jar.stored()?.let { credentials.write(scope, it) }
            val candidate = AuthContext(scope, signedIn.token, jar, refreshed = true)
            val user = authenticatedGet(candidate) { api.me(profile.origin, candidate.token, jar) }
            if (user.id != scope.accountId) throw InvalidContract()
            val capabilities = authenticatedGet(candidate) { api.capabilities(profile.origin, candidate.token, jar) }
            val fresh = jar.stored() ?: throw InvalidContract()
            credentials.write(scope, fresh)
            persist(state.value.copy(
                profiles = state.value.profiles.map { if (it.id == profile.id) it.copy(accountEmail = user.email) else it },
                session = AccountSession(user, capabilities, supportsPersistentSession = true),
            ))
            active = candidate
        } catch (error: HttpFailure) {
            if (error.statusCode == 401) {
                credentials.delete(scope)
                jar.clear()
            }
            throw error
        } catch (error: InvalidContract) {
            // A refresh returning another account must not be reused on subsequent starts.
            credentials.delete(scope)
            jar.clear()
            throw error
        }
    }

    /** Only safe GET requests retry; 403 is final and an operation refreshes at most once. */
    private suspend fun <T> authenticatedGet(context: AuthContext, request: suspend () -> T): T = try {
        request()
    } catch (error: HttpFailure) {
        if (error.statusCode != 401 || context.refreshed || context.jar.stored() == null) throw error
        context.refreshed = true
        val fresh = api.refresh(context.scope.origin, context.jar)
        if (fresh.user.id != context.scope.accountId) throw InvalidContract()
        context.token = fresh.token
        request()
    }

    private suspend fun loadSettings() {
        if (initialized) return
        val saved = preferences.read()
        // Treat a corrupt/hand-edited profile file as unavailable, never as a credential routing source.
        if (saved.profiles.map { it.id }.distinct().size != saved.profiles.size ||
            saved.profiles.map { it.origin }.distinct().size != saved.profiles.size ||
            saved.profiles.any { it.id.isBlank() || ServerOrigin.normalize(it.origin) != it.origin ||
                it.accountId?.let { id -> id.isBlank() || id.length > 128 || id.any(Char::isISOControl) } == true }) throw storageFailure()
        mutableState.value = RepositoryState(saved.profiles,
            saved.selectedProfileId?.takeIf { id -> saved.profiles.any { it.id == id } }, theme = saved.theme)
        initialized = true
    }

    private suspend fun persist(next: RepositoryState) {
        preferences.write(PersistedSettings(next.profiles, next.selectedProfileId, next.theme))
        mutableState.value = next
    }

    private fun selected(): ServerProfile = state.value.selectedProfile ?: throw AppFailure(
        FailureKind.INVALID_URL, "Сначала подключитесь к серверу MeetSpace.")

    private fun ServerProfile.scope(): SessionScope? = accountId?.let { SessionScope(origin, it) }

    private fun clearMemory(clearSessionState: Boolean = true) {
        val leases = synchronized(recordingLeases) { recordingLeases.toList().also { recordingLeases.clear() } }
        leases.forEach { it.close() }
        call?.close()
        call = null
        userEvents?.close()
        userEvents = null
        active?.token = ""
        active?.jar?.clear()
        active = null
        if (clearSessionState) mutableState.value = state.value.copy(session = null)
    }

    private suspend fun <T> protect(isLogin: Boolean = false, isRegistration: Boolean = false, block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (safe: AppFailure) { throw safe }
    catch (error: HttpFailure) {
        throw when (error.statusCode) {
            409 -> AppFailure(FailureKind.CONFLICT, if (isRegistration) "Аккаунт с таким email уже существует. Войдите или используйте другой адрес." else "Состояние изменилось. Попробуйте ещё раз.")
            401, 403 -> if (isRegistration) AppFailure(FailureKind.FORBIDDEN, "Сервер не разрешил регистрацию. Обратитесь к администратору.")
                else if (error.statusCode == 403) AppFailure(FailureKind.FORBIDDEN, "Сервер запретил доступ к этому действию.")
                else if (isLogin) AppFailure(FailureKind.INVALID_CREDENTIALS, "Неверный email или пароль, либо сессия истекла.")
                else AppFailure(FailureKind.SESSION_EXPIRED, "Сессия истекла. Войдите снова.")
            429 -> AppFailure(FailureKind.RATE_LIMITED, "Слишком много запросов. Попробуйте позже.")
            404, 405, 410 -> AppFailure(FailureKind.INCOMPATIBLE_SERVER, "Сервер не поддерживает необходимый API MeetSpace.")
            400, 422 -> AppFailure(if (isRegistration) FailureKind.VALIDATION else FailureKind.INVALID_CREDENTIALS, "Проверьте введённые данные.")
            else -> AppFailure(FailureKind.UNAVAILABLE, "Сервер временно недоступен. Попробуйте позже.")
        }
    } catch (_: SSLException) {
        throw AppFailure(FailureKind.TLS, "Не удалось подтвердить защищённое соединение. Проверьте сертификат сервера.")
    } catch (_: SocketTimeoutException) {
        throw AppFailure(FailureKind.TIMEOUT, if (isRegistration) "Подтверждение регистрации не получено. Аккаунт мог быть создан — попробуйте войти." else "Сервер не ответил вовремя. Попробуйте ещё раз.")
    } catch (_: IOException) {
        throw AppFailure(FailureKind.NETWORK, if (isRegistration) "Подтверждение регистрации не получено. Проверьте соединение и попробуйте войти." else "Не удалось связаться с сервером. Проверьте адрес и соединение.")
    } catch (_: InvalidContract) {
        throw AppFailure(FailureKind.INVALID_RESPONSE, if (isRegistration) "Ответ регистрации не подтверждён. Попробуйте войти с указанными данными." else "Ответ сервера не соответствует API MeetSpace.")
    } catch (_: Exception) {
        throw AppFailure(FailureKind.UNAVAILABLE, "Не удалось выполнить действие. Попробуйте ещё раз.")
    }

    private class AuthContext(
        val scope: SessionScope,
        var token: String,
        val jar: SessionCookieJar,
        var refreshed: Boolean = false,
    )
}
