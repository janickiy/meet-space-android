package com.meetspace.app

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface HiddenChatMessages {
    suspend fun ids(scope: String): Set<String>
    suspend fun hide(scope: String, messageIds: Set<String>): Set<String>
}

class MemoryHiddenChatMessages : HiddenChatMessages {
    private val hidden = mutableMapOf<String, Set<String>>()

    override suspend fun ids(scope: String): Set<String> = synchronized(hidden) {
        hidden[scope].orEmpty()
    }

    override suspend fun hide(scope: String, messageIds: Set<String>): Set<String> = synchronized(hidden) {
        (hidden[scope].orEmpty() + messageIds).also { hidden[scope] = it }
    }
}

/** Stores only random message IDs in a device-private file, outside Android backup. */
internal class DeviceHiddenChatMessages(context: Context) : HiddenChatMessages {
    private val directory = File(context.applicationContext.noBackupFilesDir, "hidden-chat-messages-v1")
    private val gate = Any()

    override suspend fun ids(scope: String): Set<String> = withContext(Dispatchers.IO) {
        synchronized(gate) { read(file(scope)) }
    }

    override suspend fun hide(scope: String, messageIds: Set<String>): Set<String> = withContext(Dispatchers.IO) {
        synchronized(gate) {
            val file = file(scope)
            val previous = read(file)
            val next = previous + messageIds
            if (next.size > MAX_IDS || messageIds.any { !canonicalUuid(it) }) {
                throw IOException("Could not store hidden message IDs")
            }
            if (next.size != previous.size) {
                val stream = file.startWrite()
                try {
                    stream.write(next.joinToString(separator = "\n", postfix = "\n").encodeToByteArray())
                    file.finishWrite(stream)
                } catch (error: Exception) {
                    file.failWrite(stream)
                    throw error
                }
                if (read(file) != next) throw IOException("Hidden message IDs were not saved")
            }
            next
        }
    }

    private fun file(scope: String): AtomicFile {
        val digest = MessageDigest.getInstance("SHA-256").digest(scope.encodeToByteArray())
        return AtomicFile(File(directory, digest.joinToString("") { "%02x".format(it) }))
    }

    private fun read(file: AtomicFile): Set<String> = try {
        file.openRead().use { stream ->
            if (stream.channel.size() > MAX_IDS * 37L) throw IOException("Invalid hidden message storage size")
            stream.bufferedReader().useLines { lines ->
                lines.map { id ->
                    if (!canonicalUuid(id)) throw IOException("Invalid hidden message ID")
                    id
                }.toSet()
            }
        }
    } catch (error: FileNotFoundException) {
        if (file.baseFile.exists()) throw error
        emptySet()
    }

    private fun canonicalUuid(id: String): Boolean = runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)

    private companion object { const val MAX_IDS = 100_000 }
}
