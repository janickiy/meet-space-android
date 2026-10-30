package com.meetspace.app

import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class OutgoingMessageStoreTest {
    @Test fun scopeKindAndTargetRemainIsolatedAndRestoredRowsAreAlwaysRetryable() = runTest {
        val store = MemoryOutgoingMessageStore()
        val original = row("id")
        store.put("one", original)
        assertTrue(store.messages("two", original.kind, original.targetId).isEmpty())
        assertTrue(store.messages("one", OutgoingMessageKind.MEETING, original.targetId).isEmpty())
        assertTrue(store.messages("one", original.kind, "another").isEmpty())
        val restored = store.messages("one", original.kind, original.targetId).single()
        assertTrue(restored.failed); assertFalse(restored.sending)
        assertEquals(original.input, restored.input)
        assertEquals(original.attachments, restored.attachments)
    }
    @Test fun retryUpsertIsIdempotentAndClearingOneIdentityDoesNotClearAnother() = runTest {
        val store = MemoryOutgoingMessageStore()
        val original = row("id")
        repeat(3) { store.put("one", original) }
        store.put("two", original)
        assertEquals(1, store.messages("one", original.kind, original.targetId).size)
        store.clear("one")
        assertTrue(store.messages("one", original.kind, original.targetId).isEmpty())
        assertEquals(original.input, store.messages("two", original.kind, original.targetId).single().input)
        store.remove("two", original.kind, original.targetId, original.clientRequestId)
        assertTrue(store.messages("two", original.kind, original.targetId).isEmpty())
    }
    private fun row(id: String) = LocalOutgoingMessage(id, OutgoingMessageKind.CONVERSATION, "room", "Private text", "self", "Sender",
        Instant.parse("2026-10-30T01:00:00Z"), "reply", listOf(OutgoingAttachment("attachment", "document.txt", "text/plain", 3, "checksum")))
}
