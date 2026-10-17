package com.meetspace.core.data

import com.meetspace.core.model.*
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Audited stage07 wire mapping. Reject resource substitution before data reaches UI. */
internal class MessagingApi(private val api: MeetSpaceApi) {
    suspend fun request(origin: String, token: String, path: List<String>, method: String = "GET",
        payload: JsonObject? = null, query: Map<String, String> = emptyMap()): JsonObject =
        parse(api.authenticatedRequest(origin, path, token, query, method, payload?.toString()))

    suspend fun conversations(origin: String, token: String, query: ChatQuery): ChatPage<ChatConversation> {
        if (query.type != null && query.type !in setOf("direct", "group")) input()
        val args = pageQuery(query.cursor, query.limit, query.search).toMutableMap()
        query.type?.let { args["type"] = it }; if (query.unreadOnly) args["unreadOnly"] = "true"
        val body = request(origin, token, listOf("conversations"), query = args)
        return ChatPage(body.items(query.limit).map(::conversation), body.cursor(), body.count("unreadCount"))
    }
    suspend fun conversation(origin: String, token: String, id: String): ChatConversation =
        conversation(request(origin, token, path(id)).item()).also { same(it.id, id) }
    suspend fun peers(origin: String, token: String, search: String): List<ChatPeer> {
        val text = inputText(search, 100, true)
        return request(origin, token, listOf("users"), query = mapOf("search" to text)).items(100).map(::peer)
    }
    suspend fun mutateConversation(origin: String, token: String, path: List<String>, method: String,
        payload: JsonObject?, expected: String? = null): ChatConversation =
        conversation(request(origin, token, path, method, payload).item()).also { expected?.let { id -> same(it.id, id) } }
    suspend fun members(origin: String, token: String, id: String): List<ChatMember> =
        request(origin, token, path(id) + "members").items(100).map {
            ChatMember(it.id(), it.text("displayName", 100), it.text("role", 30), it.optionalBool("online"))
        }
    suspend fun messages(origin: String, token: String, id: String, cursor: String?): MessagePage {
        val body = request(origin, token, path(id) + "messages", query = pageQuery(cursor, 30))
        val items = body.items(30).map { message(it, id) }
        distinct(items.map { it.id })
        return MessagePage(items, body.cursor(), body.count("unreadCount"), body.optionalId("lastReadMessageId"))
    }
    suspend fun messagePage(origin: String, token: String, id: String, suffix: List<String>, query: Map<String, String> = emptyMap()): ChatPage<ChatMessage> {
        val body = request(origin, token, path(id) + suffix, query = query)
        val items = body.items(100).map { message(it, id) }
        distinct(items.map { it.id })
        return ChatPage(items, body.cursor())
    }
    suspend fun mutateMessage(origin: String, token: String, id: String, messageId: String?, method: String,
        payload: JsonObject? = null): ChatMessage {
        val target = path(id) + "messages" + listOfNotNull(messageId?.let(::inputId))
        return message(request(origin, token, target, method, payload).item(), id).also {
            messageId?.let { expected -> same(expected, it.id) }
        }
    }
    suspend fun read(origin: String, token: String, id: String, messageId: String): ChatReadState {
        val item = request(origin, token, path(id) + "read", "POST", fields("messageId" to inputId(messageId))).item()
        return ChatReadState(item.count("unreadCount"), item.optionalId("lastReadMessageId"))
    }
    suspend fun upload(origin: String, token: String, id: String, filename: String, mimeType: String,
        bytes: ByteArray, requestId: String): ChatAttachment {
        val (name, expectedMime) = fileInput(filename, mimeType, bytes.size)
        val init = request(origin, token, path(id) + listOf("attachments", "init"), "POST",
            fields("clientRequestId" to inputId(requestId), "filename" to name, "mimeType" to expectedMime, "size" to bytes.size))
        val pending = attachment(init.item(), id)
        val target = path(id) + listOf("attachments", pending.id, "content")
        // Never use an untrusted server URL as an upload destination.
        if (init.text("uploadUrl", 1000) != "/api/v1/" + target.joinToString("/")) throw InvalidContract()
        if (pending.size != bytes.size.toLong() || pending.filename != name || pending.mimeType != expectedMime) throw InvalidContract()
        val uploaded = attachment(parse(api.upload(origin, target, token, bytes)).item(), id)
        same(uploaded.id, pending.id)
        val final = attachment(request(origin, token, path(id) + listOf("attachments", pending.id, "finalize"), "POST").item(), id)
        same(final.id, pending.id)
        val digest = sha256(bytes)
        if (final.status != "ready" || final.size != bytes.size.toLong() || final.checksum != digest) throw InvalidContract()
        return final
    }
    suspend fun download(origin: String, token: String, id: String, attachment: ChatAttachment): ByteArray {
        same(id, attachment.conversationId); inputId(attachment.id)
        if (attachment.size !in 1..MAX_FILE_BYTES.toLong()) input()
        val body = request(origin, token, path(id) + listOf("attachments", attachment.id, "download"))
        val expires = body.instant("expiresAt")
        if (expires <= Instant.now() || expires > Instant.now().plusSeconds(3600)) throw InvalidContract()
        val raw = body.text("url", 8192)
        val authenticated = body.optionalBool("authenticated") == true
        val url = if (authenticated) {
            val expected = "/api/v1/conversations/$id/attachments/${attachment.id}/content"
            if (raw != expected) throw InvalidContract()
            ServerOrigin.url(origin, expected)
        } else {
            raw.toHttpUrlOrNull()?.takeIf { it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.fragment == null }
                ?: throw InvalidContract()
        }
        val bytes = try { api.download(url, if (authenticated) token else null, attachment.size) }
        catch (failure: HttpFailure) {
            // A signed object's expiry is not an expired account session.
            if (!authenticated && failure.statusCode == 401) throw AppFailure(FailureKind.FORBIDDEN, "Ссылка на файл истекла. Повторите скачивание.")
            throw failure
        }
        if (attachment.checksum.isNotEmpty() && sha256(bytes) != attachment.checksum) {
            bytes.fill(0); throw InvalidContract()
        }
        return bytes
    }
    suspend fun folders(origin: String, token: String): List<ChatFolder> = request(origin, token, listOf("folders")).items(100).map(::folder)
    suspend fun mutateFolder(origin: String, token: String, target: List<String>, method: String,
        payload: JsonObject? = null, expected: String? = null): ChatFolder =
        folder(request(origin, token, target, method, payload).item()).also { expected?.let { id -> same(id, it.id) } }
    suspend fun folderItems(origin: String, token: String, folderId: String?, query: FolderQuery): ChatPage<FolderEntry> {
        if (query.type !in setOf("all", "conversation", "conference")) input()
        val args = pageQuery(query.cursor, query.limit, query.search).toMutableMap().apply { put("type", query.type) }
        val target = if (folderId == null || query.candidates) {
            folderId?.let { args["folderId"] = inputId(it) }; listOf("folder-items")
        } else listOf("folders", inputId(folderId), "items")
        val response = request(origin, token, target, query = args)
        return ChatPage(response.items(query.limit).map {
            val type = it.text("type", 30); val item = it.item(); val included = it.optionalBool("inFolder") ?: (folderId != null && !query.candidates)
            when (type) {
                "conversation" -> FolderEntry(type, conversation = conversation(item), inFolder = included)
                "conference" -> FolderEntry(type, meeting = folderMeeting(item), inFolder = included)
                else -> throw InvalidContract()
            }
        }, response.cursor())
    }
    suspend fun notifications(origin: String, token: String, accountId: String, cursor: String?): NotificationPage {
        val args = pageQuery(cursor, 30).toMutableMap().apply { remove("before")?.let { put("cursor", it) } }
        val response = request(origin, token, listOf("notifications"), query = args)
        return NotificationPage(response.items(30).map { notification(it, accountId) }, response.cursor(), response.count("unreadCount"))
    }
    suspend fun preferences(origin: String, token: String, value: NotificationPreferences? = null): NotificationPreferences {
        val response = request(origin, token, listOf("notifications", "preferences"), if (value == null) "GET" else "PUT",
            value?.let { fields("invitation" to it.invitation, "reminder" to it.reminder, "recording" to it.recording,
                "summary" to it.summary, "email" to it.email, "push" to it.push) })
        return NotificationPreferences(response.bool("invitation"), response.bool("reminder"), response.bool("recording"),
            response.bool("summary"), response.bool("email"), response.bool("push"))
    }

    companion object {
        const val MAX_FILE_BYTES = 10 * 1024 * 1024
        /** Shared filename/MIME/size contract for personal and conference chat files. */
        fun fileInput(filename: String, mimeType: String, size: Int): Pair<String, String> {
            val name = inputText(filename, 180, true)
            val expectedMime = mapOf("jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png",
                "webp" to "image/webp", "pdf" to "application/pdf", "txt" to "text/plain", "csv" to "text/csv")
                .get(name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT))
                ?: throw AppFailure(FailureKind.VALIDATION, "Можно прикрепить JPG, PNG, WebP, PDF, TXT или CSV.")
            val declaredMime = mimeType.substringBefore(';').trim().lowercase(java.util.Locale.ROOT)
            if (name.contains('/') || name.contains('\\') || name.contains("..") ||
                name.any { Character.getType(it) == Character.FORMAT.toInt() } || size !in 1..MAX_FILE_BYTES ||
                (declaredMime !in setOf("", "application/octet-stream", expectedMime) &&
                    !(expectedMime == "text/csv" && declaredMime in setOf("text/plain", "text/comma-separated-values")))) input()
            return name to expectedMime
        }
        fun input(): Nothing = throw AppFailure(FailureKind.VALIDATION, "Проверьте данные чата, папки или файла.")
        fun inputId(id: String): String { if (!validId(id)) input(); return id }
        fun validId(id: String): Boolean = id != "00000000-0000-0000-0000-000000000000" &&
            runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)
        fun inputText(raw: String, max: Int, required: Boolean = false, multiline: Boolean = false): String {
            val value = raw.trim()
            if ((required && value.isEmpty()) || value.codePointCount(0, value.length) > max ||
                value.any { it.isISOControl() && !(multiline && it in "\n\r\t") }) input()
            return value
        }
        fun path(id: String) = listOf("conversations", inputId(id))
        fun fields(vararg entries: Pair<String, Any?>): JsonObject = buildJsonObject {
            entries.forEach { (key, value) -> when (value) {
                null -> Unit
                is Boolean -> put(key, value)
                is Number -> put(key, value)
                is List<*> -> put(key, JsonArray(value.map { JsonPrimitive(it.toString()) }))
                else -> put(key, value.toString())
            } }
        }
        fun parse(raw: String): JsonObject = try {
            (wireJson.parseToJsonElement(raw) as? JsonObject ?: throw InvalidContract()).also {
                it.optionalText("status", 30)?.let { status -> if (status != "success") throw InvalidContract() }
            }
        } catch (_: IllegalArgumentException) { throw InvalidContract() }
        fun JsonObject.item() = this["item"] as? JsonObject ?: throw InvalidContract()
        fun JsonObject.items(limit: Int): List<JsonObject> {
            val array = this["items"]
            if (array == JsonNull) return emptyList()
            return (array as? JsonArray ?: throw InvalidContract()).also { if (it.size > limit) throw InvalidContract() }
                .map { it as? JsonObject ?: throw InvalidContract() }
        }
        fun JsonObject.optionalText(name: String, max: Int): String? {
            val value = this[name] ?: return null
            if (value == JsonNull) return null
            val primitive = value as? JsonPrimitive ?: throw InvalidContract()
            if (!primitive.isString || primitive.content.length > max || primitive.content.contains('\u0000')) throw InvalidContract()
            return primitive.content
        }
        fun JsonObject.text(name: String, max: Int) = optionalText(name, max) ?: throw InvalidContract()
        fun JsonObject.optionalId(name: String): String? = optionalText(name, 36)?.takeIf { it.isNotEmpty() }?.also { if (!validId(it)) throw InvalidContract() }
        fun JsonObject.id(name: String = "id") = optionalId(name) ?: throw InvalidContract()
        fun JsonObject.bool(name: String) = optionalBool(name) ?: throw InvalidContract()
        fun JsonObject.optionalBool(name: String): Boolean? {
            val value = this[name] ?: return null
            if (value == JsonNull) return null
            return (value as? JsonPrimitive)?.booleanOrNull ?: throw InvalidContract()
        }
        fun JsonObject.count(name: String, default: Long? = null): Long {
            val value = (this[name] as? JsonPrimitive)?.longOrNull ?: default ?: throw InvalidContract()
            if (value < 0) throw InvalidContract(); return value
        }
        fun JsonObject.instant(name: String): Instant = optionalInstant(name) ?: throw InvalidContract()
        fun JsonObject.optionalInstant(name: String): Instant? = optionalText(name, 64)?.let {
            try { Instant.parse(it) } catch (_: Exception) { throw InvalidContract() }
        }
        fun JsonObject.cursor(): String? = optionalText("nextCursor", 2048)?.takeIf(String::isNotEmpty)?.also {
            if (it.any(Char::isISOControl)) throw InvalidContract()
        }
        fun pageQuery(cursor: String?, limit: Int, search: String = ""): Map<String, String> {
            if (limit !in 1..100 || cursor?.let { it.length > 2048 || it.isBlank() || it.any(Char::isISOControl) } == true) input()
            return buildMap { put("limit", limit.toString()); cursor?.let { put("before", it) }
                inputText(search, 100).takeIf(String::isNotEmpty)?.let { put("search", it) } }
        }
        fun same(actual: String, expected: String) { if (actual != expected) throw InvalidContract() }
        fun distinct(ids: List<String>) { if (ids.distinct().size != ids.size) throw InvalidContract() }
        fun peer(o: JsonObject) = ChatPeer(o.id(), o.text("displayName", 400))
        fun conversation(o: JsonObject): ChatConversation {
            val type = o.text("type", 30)
            val peer = (o["peer"] as? JsonObject)?.let(::peer)
            if (type == "direct" && peer == null) throw InvalidContract()
            val count = o.count("memberCount", 0)
            if (count > 100) throw InvalidContract()
            return ChatConversation(o.id(), type, peer, o.optionalText("name", 200).orEmpty(),
                o.optionalText("description", 800).orEmpty(), o.optionalText("myRole", 30).orEmpty(), count.toInt(),
                o.text("preview", 16000), o.count("unreadCount"), o.bool("notificationsEnabled"),
                o.instant("createdAt"), o.instant("updatedAt"), o.optionalInstant("lastMessageAt"), o.optionalId("lastMessageId"))
        }
        fun attachment(o: JsonObject, scope: String): ChatAttachment {
            same(o.id("conversationId"), scope)
            val size = o.count("size")
            if (size !in 1..MAX_FILE_BYTES.toLong()) throw InvalidContract()
            val checksum = o.optionalText("checksum", 64).orEmpty()
            if (checksum.isNotEmpty() && !checksum.matches(Regex("[a-f0-9]{64}"))) throw InvalidContract()
            return ChatAttachment(o.id(), scope, o.text("filename", 720), o.text("mimeType", 200), size,
                o.text("status", 30), checksum, o.optionalId("messageId"))
        }
        fun message(o: JsonObject, scope: String): ChatMessage {
            same(o.id("conversationId"), scope)
            val raw = o["attachments"]
            val attachments = when (raw) { null, JsonNull -> emptyList(); is JsonArray -> raw.map {
                attachment(it as? JsonObject ?: throw InvalidContract(), scope) }; else -> throw InvalidContract() }
            if (attachments.size > 5) throw InvalidContract()
            val reply = (o["replyPreview"] as? JsonObject)?.let { ChatReply(it.id(), it.text("text", 16000),
                it.text("senderName", 400), it.bool("deleted")) }
            val version = o.count("version"); val sequence = o.count("sequence")
            if (version == 0L || sequence == 0L) throw InvalidContract()
            val call = when (val value = o["call"]) {
                null, JsonNull -> null
                is JsonObject -> DirectCallsApi.history(value)
                else -> throw InvalidContract()
            }
            if (call != null && (attachments.isNotEmpty() || o.optionalId("replyTo") != null)) throw InvalidContract()
            return ChatMessage(o.id(), scope, o.id("senderId"), o.text("senderName", 400), o.id("clientRequestId"),
                o.text("text", 16000), sequence, version, o.instant("createdAt"), o.instant("updatedAt"),
                o.optionalInstant("deletedAt"), o.optionalId("replyTo"), reply, attachments,
                if (call == null) "message" else "call", call, o.optionalBool("pinned") ?: false)
        }
        fun folder(o: JsonObject): ChatFolder = ChatFolder(o.id(), o.text("name", 200), o.count("position").toInt(),
            o.count("itemCount"), o.count("conversationCount"), o.count("conferenceCount"))
        private fun folderMeeting(o: JsonObject): Meeting = Meeting(o.id(), o.id("ownerId"), o.text("title", 800), "", "",
            o.text("status", 30), o.instant("createdAt"), o.instant("updatedAt"), o.optionalInstant("startedAt"),
            o.optionalInstant("finishedAt"), o.optionalInstant("scheduledAt"),
            (o["plannedDurationMin"] as? JsonPrimitive)?.intOrNull, o.optionalBool("waitingRoomEnabled") ?: false,
            (o["participantCount"] as? JsonPrimitive)?.intOrNull)
        fun notification(o: JsonObject, account: String): InboxNotification {
            same(o.id("userId"), account)
            val payload = o["payload"] as? JsonObject ?: throw InvalidContract()
            return InboxNotification(o.id(), o.text("type", 100), o.instant("createdAt"), o.optionalInstant("readAt"),
                payload.optionalId("conferenceId"), payload.optionalId("conversationId"), payload.optionalId("messageId"),
                payload.optionalInstant("scheduledAt"))
        }
        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
