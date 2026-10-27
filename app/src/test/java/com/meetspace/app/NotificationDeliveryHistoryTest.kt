package com.meetspace.app

import org.junit.Assert.*
import org.junit.Test

class NotificationDeliveryHistoryTest {
    @Test fun newEventsAreIndependentButDuplicateDeliveryIsRejected() {
        val history = NotificationDeliveryHistory()
        assertFalse(history.contains("https://server.test|user", "first"))
        history.record("https://server.test|user", "first")
        assertTrue(history.contains("https://server.test|user", "first"))
        assertFalse(history.contains("https://server.test|user", "second"))
        history.record("https://server.test|user", "first")
        assertEquals(1, history.snapshot().lines().size)
    }

    @Test fun restartRetainsDeduplicationWithoutStoringAccountOrEventContent() {
        val history = NotificationDeliveryHistory()
        history.record("https://private.test|account", "notification-id")
        val saved = history.snapshot()
        assertTrue(saved.matches(Regex("[0-9a-f]{64}")))
        assertFalse(saved.contains("private"))
        assertFalse(saved.contains("notification-id"))
        assertTrue(NotificationDeliveryHistory(saved).contains("https://private.test|account", "notification-id"))
    }

    @Test fun serverAndAccountArePartOfIdentity() {
        val history = NotificationDeliveryHistory()
        history.record("https://first.test|alice", "event")
        assertFalse(history.contains("https://first.test|bob", "event"))
        assertFalse(history.contains("https://second.test|alice", "event"))
        assertFalse(history.contains("https://first.test|alice", "other-event"))
    }

    @Test fun journalIsBoundedAndMalformedPersistedRowsAreIgnored() {
        val history = NotificationDeliveryHistory(limit = 2)
        repeat(3) { history.record("scope", "event-$it") }
        assertFalse(history.contains("scope", "event-0"))
        assertTrue(history.contains("scope", "event-1"))
        assertTrue(history.contains("scope", "event-2"))
        val restored = NotificationDeliveryHistory("bad\n${history.snapshot()}\n", limit = 1)
        assertFalse(restored.contains("scope", "event-1"))
        assertTrue(restored.contains("scope", "event-2"))
    }
}
