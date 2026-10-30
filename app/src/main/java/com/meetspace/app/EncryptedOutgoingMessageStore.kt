package com.meetspace.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.security.MessageDigest
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Private unsent content is authenticated/encrypted, excluded from Android backup, and never logged. */
class EncryptedOutgoingMessageStore(context: Context) : OutgoingMessageStore {
    private val directory = File(context.applicationContext.noBackupFilesDir, "outgoing-v1")
    private val gate = Any()
    private val json = Json
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun file(scope: String, kind: OutgoingMessageKind, target: String) = File(File(directory, digest(scope)), digest("$kind\u0000$target") + ".bin")
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    override suspend fun messages(scope: String, kind: OutgoingMessageKind, targetId: String): List<LocalOutgoingMessage> = withContext(Dispatchers.IO) {
        synchronized(gate) { read(file(scope, kind, targetId), kind, targetId) }
    }
    override suspend fun put(scope: String, message: LocalOutgoingMessage) = withContext(Dispatchers.IO) {
        synchronized(gate) {
            val path = file(scope, message.kind, message.targetId)
            val old = read(path, message.kind, message.targetId).filterNot { it.clientRequestId == message.clientRequestId }
            if (old.size >= MAX_MESSAGES) throw IOException("Outgoing message storage is full")
            write(path, old + message.restored())
        }
    }
    override suspend fun remove(scope: String, kind: OutgoingMessageKind, targetId: String, clientRequestId: String) = withContext(Dispatchers.IO) {
        synchronized(gate) {
            val path = file(scope, kind, targetId)
            val old = read(path, kind, targetId)
            if (old.any { it.clientRequestId == clientRequestId }) write(path, old.filterNot { it.clientRequestId == clientRequestId })
        }
    }
    override suspend fun clear(scope: String) = withContext(Dispatchers.IO) {
        synchronized(gate) {
            val path = File(directory, digest(scope))
            if (path.exists() && !path.deleteRecursively()) throw IOException("Could not clear outgoing messages")
            Unit
        }
    }
    private fun read(path: File, kind: OutgoingMessageKind, target: String): List<LocalOutgoingMessage> {
        val atomic = AtomicFile(path)
        if (!path.exists() && !File(path.path + ".bak").exists()) return emptyList()
        val encrypted = atomic.openRead().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > MAX_BYTES) throw IOException("Invalid outgoing storage size")
                output.write(buffer, 0, count)
            }
            output.toByteArray().also { if (it.size < 29) throw IOException("Invalid outgoing storage size") }
        }
        val plain = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, encrypted.copyOfRange(0, 12)))
            updateAAD((path.parentFile!!.name + "/" + path.name).toByteArray())
            doFinal(encrypted, 12, encrypted.size - 12)
        }
        try {
            val entries = json.parseToJsonElement(plain.decodeToString()).jsonArray
            if (entries.size > MAX_MESSAGES) throw IOException("Invalid outgoing storage count")
            return entries.map { element ->
                val row = element.jsonObject
                val attachments = row.getValue("attachments").jsonArray.map { item -> item.jsonObject.let {
                    OutgoingAttachment(it.text("id"), it.text("filename"), it.text("mimeType"), it.getValue("size").jsonPrimitive.long, it.text("checksum"))
                } }
                LocalOutgoingMessage(row.text("clientRequestId"), kind, target, row.text("text"), row.text("senderId"), row.text("senderName"),
                    Instant.parse(row.text("createdAt")), row["replyTo"]?.jsonPrimitive?.contentOrNull, attachments).also {
                    if (it.clientRequestId.length !in 1..128 || it.text.length > 8000 || it.senderName.length > 256 || attachments.size > 5) throw IOException("Invalid outgoing row")
                }.restored()
            }
        } finally { plain.fill(0); encrypted.fill(0) }
    }
    private fun write(path: File, rows: List<LocalOutgoingMessage>) {
        val atomic = AtomicFile(path)
        if (rows.isEmpty()) { atomic.delete(); return }
        val plain = buildJsonArray {
            rows.forEach { row -> add(buildJsonObject {
                put("clientRequestId", row.clientRequestId); put("text", row.text); put("senderId", row.senderId); put("senderName", row.senderName)
                put("createdAt", row.createdAt.toString()); row.replyTo?.let { put("replyTo", it) }
                putJsonArray("attachments") { row.attachments.forEach { item -> add(buildJsonObject {
                    put("id", item.id); put("filename", item.filename); put("mimeType", item.mimeType); put("size", item.size); put("checksum", item.checksum)
                }) } }
            }) }
        }.toString().toByteArray()
        try {
            if (plain.size > MAX_BYTES - 28) throw IOException("Outgoing message storage is full")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()); updateAAD((path.parentFile!!.name + "/" + path.name).toByteArray()) }
            val encrypted = cipher.iv + cipher.doFinal(plain)
            path.parentFile?.mkdirs()
            val stream = atomic.startWrite()
            try { stream.write(encrypted); atomic.finishWrite(stream) }
            catch (error: Exception) { atomic.failWrite(stream); throw error }
            finally { encrypted.fill(0) }
        } finally { plain.fill(0) }
    }
    private fun JsonObject.text(name: String) = getValue(name).jsonPrimitive.content
    private companion object { const val KEY_ALIAS = "meetspace-outgoing-v1"; const val MAX_MESSAGES = 100; const val MAX_BYTES = 4 * 1024 * 1024 }
}
