package com.meetspace.core.data

import com.meetspace.core.data.MessagingApi.Companion.count
import com.meetspace.core.data.MessagingApi.Companion.fields
import com.meetspace.core.data.MessagingApi.Companion.fileInput
import com.meetspace.core.data.MessagingApi.Companion.id
import com.meetspace.core.data.MessagingApi.Companion.input
import com.meetspace.core.data.MessagingApi.Companion.inputId
import com.meetspace.core.data.MessagingApi.Companion.inputText
import com.meetspace.core.data.MessagingApi.Companion.instant
import com.meetspace.core.data.MessagingApi.Companion.item
import com.meetspace.core.data.MessagingApi.Companion.items
import com.meetspace.core.data.MessagingApi.Companion.optionalId
import com.meetspace.core.data.MessagingApi.Companion.optionalInstant
import com.meetspace.core.data.MessagingApi.Companion.optionalText
import com.meetspace.core.data.MessagingApi.Companion.optionalBool
import com.meetspace.core.data.MessagingApi.Companion.sha256
import com.meetspace.core.data.MessagingApi.Companion.parse
import com.meetspace.core.data.MessagingApi.Companion.bool
import com.meetspace.core.data.MessagingApi.Companion.text
import com.meetspace.core.model.*
import java.util.Base64
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Adapter for Go's /conferences/:id/messages and /chat/read, not /conversations. */
internal class MeetingChatApi(private val api: MeetSpaceApi) {
    suspend fun list(origin: String, token: String, id: String, cursor: String?, limit: Int): MeetingChatPage {
        inputId(id)
        if (limit !in 1..100 || cursor != null && cursorSequence(cursor, id) == null) input()
        val before = cursor?.let { cursorSequence(it, id) }
        val parameters = buildMap { put("limit", limit.toString()); cursor?.let { put("before", it) } }
        val body = request(origin, token, path(id) + "messages", query = parameters)
        val messages = body.items(limit).map { message(it, id) }
        if (messages.map { it.id }.distinct().size != messages.size ||
            messages.zipWithNext().any { (first, second) -> first.sequence >= second.sequence } ||
            before != null && messages.any { it.sequence >= before }) throw InvalidContract()
        val next = body.optionalText("nextCursor", 128)?.takeIf { it.isNotEmpty() }
        if (next != null && (messages.isEmpty() || cursorSequence(next, id) != messages.first().sequence || next == cursor)) throw InvalidContract()
        return MeetingChatPage(messages, next, body.count("unreadCount"), body.optionalId("lastReadMessageId"))
    }

    suspend fun send(origin: String, token: String, id: String, input: ChatMessageInput): MeetingChatMessage {
        if (input.attachmentIds.size > 5 || input.attachmentIds.distinct().size != input.attachmentIds.size) input()
        input.attachmentIds.forEach(::inputId)
        val payload = fields("clientRequestId" to inputId(input.clientRequestId),
            "text" to inputText(input.text, 4000, input.attachmentIds.isEmpty(), multiline = true),
            "replyTo" to input.replyTo?.let(::inputId), "attachmentIds" to input.attachmentIds)
        return message(request(origin, token, path(id) + "messages", "POST", payload).item(), id)
    }

    suspend fun read(origin: String, token: String, id: String, messageId: String? = null): ChatReadState {
        val body = request(origin, token, path(id) + listOf("chat", "read"),
            if (messageId == null) "GET" else "PUT", messageId?.let { fields("messageId" to inputId(it)) }).item()
        return ChatReadState(body.count("unreadCount"), body.optionalId("lastReadMessageId"))
    }

    suspend fun upload(origin: String, token: String, accountId: String, id: String, filename: String,
        mimeType: String, bytes: ByteArray, requestId: String): MeetingChatAttachment {
        val (name, expectedMime) = fileInput(filename, mimeType, bytes.size)
        val target = path(id) + "attachments"
        val digest = sha256(bytes)
        fun checked(body: JsonObject, expectedId: String? = null): MeetingChatAttachment {
            if (body.id("ownerId") != accountId || body.id("clientRequestId") != requestId) throw InvalidContract()
            return attachment(body, id, allowedStatuses = setOf("pending", "ready")).also {
                if (expectedId != null && it.id != expectedId || it.size != bytes.size.toLong() ||
                    it.filename != name || it.mimeType != expectedMime) throw InvalidContract()
                if (it.checksum.isNotEmpty() && it.checksum != digest) throw InvalidContract()
            }
        }
        val init = request(origin, token, target + "init", "POST",
            fields("clientRequestId" to inputId(requestId), "filename" to name, "mimeType" to expectedMime, "size" to bytes.size))
        val pending = checked(init.item())
        val content = target + listOf(pending.id, "content")
        // Never send file bytes to a destination supplied by the response.
        if (init.text("uploadUrl", 1000) != "/api/v1/" + content.joinToString("/")) throw InvalidContract()
        // A retry after a lost finalize acknowledgement finds the same ready resource.
        // Go rejects PUT for ready attachments, so do not upload it again.
        if (pending.status == "ready") {
            if (pending.checksum != digest) throw InvalidContract()
            return pending
        }
        val uploaded = checked(parse(api.upload(origin, content, token, bytes)).item(), pending.id)
        if (uploaded.status != "pending" || uploaded.checksum != digest) throw InvalidContract()
        return checked(request(origin, token, target + listOf(pending.id, "finalize"), "POST").item(), pending.id).also {
            if (it.status != "ready" || it.checksum != digest) throw InvalidContract()
        }
    }

    suspend fun download(origin: String, token: String, id: String, attachment: MeetingChatAttachment): ByteArray {
        inputId(id); inputId(attachment.id)
        val messageId = attachment.messageId
        if (id != attachment.conferenceId || attachment.status != "attached" || messageId == null ||
            attachment.size !in 1..MessagingApi.MAX_FILE_BYTES.toLong() ||
            !attachment.checksum.matches(Regex("[a-f0-9]{64}"))) input()
        inputId(messageId)
        val body = request(origin, token, path(id) + listOf("attachments", attachment.id, "download"))
        val now = Instant.now()
        val expiry = body.instant("expiresAt")
        if (expiry <= now || expiry > now.plusSeconds(3600)) throw InvalidContract()
        // Conference attachments use signed storage URLs. Only personal/group files expose
        // the alternative authenticated content route; never invent that route here.
        if (body.optionalBool("authenticated") == true) throw InvalidContract()
        val raw = body.text("url", 8192)
        if (raw.any { it.isWhitespace() || it.isISOControl() || it == '\\' }) throw InvalidContract()
        val url = raw.toHttpUrlOrNull()?.takeIf {
            it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.fragment == null
        } ?: throw InvalidContract()
        val bytes = try { api.download(url, null, attachment.size) } catch (failure: HttpFailure) {
            if (failure.statusCode == 401) throw AppFailure(FailureKind.FORBIDDEN, "Ссылка на файл истекла. Повторите скачивание.")
            throw failure
        }
        if (sha256(bytes) != attachment.checksum) { bytes.fill(0); throw InvalidContract() }
        return bytes
    }

    private suspend fun request(origin: String, token: String, path: List<String>, method: String = "GET",
        payload: JsonObject? = null, query: Map<String, String> = emptyMap()): JsonObject =
        parse(api.authenticatedRequest(origin, path, token, query, method, payload?.toString()))

    private fun path(id: String) = listOf("conferences", inputId(id))

    private fun message(body: JsonObject, conferenceId: String): MeetingChatMessage {
        if (body.id("conferenceId") != conferenceId || body.optionalId("conversationId") != null) throw InvalidContract()
        val id = body.id()
        val deletedAt = body.optionalInstant("deletedAt")
        val attachments = when (val raw = body["attachments"]) {
            null, JsonNull -> emptyList()
            is JsonArray -> raw.map { attachment(it as? JsonObject ?: throw InvalidContract(), conferenceId, id) }
            else -> throw InvalidContract()
        }
        if (attachments.size > 5 || attachments.map { it.id }.distinct().size != attachments.size) throw InvalidContract()
        val replyTo = body.optionalId("replyTo")
        val reply = when (val raw = body["replyPreview"]) {
            null, JsonNull -> null
            is JsonObject -> {
                if (raw.id() != replyTo) throw InvalidContract()
                val deleted = raw.bool("deleted")
                ChatReply(raw.id(), if (deleted) "" else raw.text("text", 16000), raw.text("senderName", 400), deleted)
            }
            else -> throw InvalidContract()
        }
        val sequence = body.count("sequence")
        val version = body.count("version")
        if (sequence == 0L || version == 0L) throw InvalidContract()
        val text = body.text("text", 16000)
        return MeetingChatMessage(id, conferenceId, body.id("senderId"), body.text("senderName", 400), body.id("clientRequestId"),
            if (deletedAt == null) text else "", sequence, version, body.instant("createdAt"), body.instant("updatedAt"),
            deletedAt, replyTo, reply, if (deletedAt == null) attachments else emptyList())
    }

    private fun attachment(body: JsonObject, conferenceId: String, messageId: String? = null,
        allowedStatuses: Set<String> = setOf("attached")): MeetingChatAttachment {
        if (body.id("conferenceId") != conferenceId || body.optionalId("conversationId") != null || body.optionalId("messageId") != messageId) throw InvalidContract()
        val size = body.count("size")
        val checksum = body.optionalText("checksum", 64).orEmpty()
        if (size !in 1..MessagingApi.MAX_FILE_BYTES.toLong() || checksum.isNotEmpty() && !checksum.matches(Regex("[a-f0-9]{64}"))) throw InvalidContract()
        val status = body.text("status", 30)
        if (status !in allowedStatuses) throw InvalidContract()
        return MeetingChatAttachment(body.id(), conferenceId, body.text("filename", 720), body.text("mimeType", 200), size, status, checksum, messageId)
    }

    /** The audited backend binds its URL-safe cursor to conference ID and oldest sequence. */
    private fun cursorSequence(cursor: String, conferenceId: String): Long? {
        if (cursor.isEmpty() || cursor.length > 128 || !cursor.matches(Regex("[A-Za-z0-9_-]+"))) return null
        return runCatching {
            val fields = String(Base64.getUrlDecoder().decode(cursor), Charsets.UTF_8).split(':')
            if (fields.size != 2 || fields[0] != conferenceId) null else fields[1].toLongOrNull()?.takeIf { it > 0 }
        }.getOrNull()
    }
}
