package com.meetspace.app

import com.meetspace.core.model.ChatMessageInput
import java.time.Instant

/** Outgoing rows never become authoritative server messages until acknowledged. */
enum class OutgoingMessageKind { CONVERSATION, MEETING }

data class OutgoingAttachment(
    val id: String,
    val filename: String,
    val mimeType: String,
    val size: Long,
    val checksum: String = "",
)

data class LocalOutgoingMessage(
    val clientRequestId: String,
    val kind: OutgoingMessageKind,
    val targetId: String,
    val text: String,
    val senderId: String,
    val senderName: String,
    val createdAt: Instant = Instant.now(),
    val replyTo: String? = null,
    val attachments: List<OutgoingAttachment> = emptyList(),
    val failed: Boolean = false,
    val sending: Boolean = true,
) {
    val id: String get() = "local-$clientRequestId"
    val input: ChatMessageInput get() = ChatMessageInput(clientRequestId, text, replyTo, attachments.map { it.id })
    /** A process cannot know whether a request completed before it died; retry is idempotent. */
    fun restored() = copy(failed = true, sending = false)
}

interface OutgoingMessageStore {
    suspend fun messages(scope: String, kind: OutgoingMessageKind, targetId: String): List<LocalOutgoingMessage>
    suspend fun put(scope: String, message: LocalOutgoingMessage)
    suspend fun remove(scope: String, kind: OutgoingMessageKind, targetId: String, clientRequestId: String)
    suspend fun clear(scope: String)
}

/** Each ViewModel uses an isolated in-memory store unless the application injects its durable store. */
class MemoryOutgoingMessageStore : OutgoingMessageStore {
    private val rows = linkedMapOf<String, MutableList<LocalOutgoingMessage>>()
    private fun key(scope: String, kind: OutgoingMessageKind, targetId: String) = "$scope\u0000$kind\u0000$targetId"
    override suspend fun messages(scope: String, kind: OutgoingMessageKind, targetId: String) = synchronized(rows) {
        rows[key(scope, kind, targetId)].orEmpty().map { it.restored() }
    }
    override suspend fun put(scope: String, message: LocalOutgoingMessage) = synchronized(rows) {
        val items = rows.getOrPut(key(scope, message.kind, message.targetId)) { mutableListOf() }
        items.removeAll { it.clientRequestId == message.clientRequestId }
        items.add(message.restored())
        Unit
    }
    override suspend fun remove(scope: String, kind: OutgoingMessageKind, targetId: String, clientRequestId: String) = synchronized(rows) {
        rows[key(scope, kind, targetId)]?.removeAll { it.clientRequestId == clientRequestId }
        Unit
    }
    override suspend fun clear(scope: String) = synchronized(rows) {
        rows.keys.filter { it.startsWith(scope + "\u0000") }.forEach(rows::remove)
    }
}
