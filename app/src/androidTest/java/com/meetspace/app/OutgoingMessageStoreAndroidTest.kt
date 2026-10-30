package com.meetspace.app

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OutgoingMessageStoreAndroidTest {
    @Test fun encryptedOutboxSurvivesStoreRecreationRejectsMovedCiphertextAndClearsOnlyItsScope() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scopeA = "outbox-test-a-${UUID.randomUUID()}"
        val scopeB = "outbox-test-b-${UUID.randomUUID()}"
        val store = EncryptedOutgoingMessageStore(context)
        val row = LocalOutgoingMessage(UUID.randomUUID().toString(), OutgoingMessageKind.CONVERSATION, "room", "Закрытый текст ${UUID.randomUUID()}", "self", "Автор",
            attachments = listOf(OutgoingAttachment("file", "файл.txt", "text/plain", 23)))
        try {
            store.put(scopeA, row)
            val loaded = EncryptedOutgoingMessageStore(context).messages(scopeA, row.kind, row.targetId).single()
            assertEquals(row.input, loaded.input)
            assertEquals(row.attachments, loaded.attachments)
            assertTrue(loaded.failed); assertFalse(loaded.sending)
            val fileA = path(context.noBackupFilesDir, scopeA, row)
            assertFalse(fileA.readBytes().toString(Charsets.UTF_8).contains(row.text))
            val fileB = path(context.noBackupFilesDir, scopeB, row)
            fileB.parentFile!!.mkdirs(); fileA.copyTo(fileB)
            var rejected = false
            try { store.messages(scopeB, row.kind, row.targetId) } catch (_: Exception) { rejected = true }
            assertTrue("Ciphertext is bound to its account and destination", rejected)
            store.clear(scopeB)
            store.put(scopeB, row.copy(text = "Another account"))
            store.clear(scopeA)
            assertTrue(store.messages(scopeA, row.kind, row.targetId).isEmpty())
            assertEquals("Another account", store.messages(scopeB, row.kind, row.targetId).single().text)
        } finally { store.clear(scopeA); store.clear(scopeB) }
    }
    private fun path(root: File, scope: String, row: LocalOutgoingMessage): File {
        fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(File(File(root, "outgoing-v1"), digest(scope)), digest("${row.kind}\u0000${row.targetId}") + ".bin")
    }
}
