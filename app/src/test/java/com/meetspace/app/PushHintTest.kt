package com.meetspace.app

import org.junit.Assert.*
import org.junit.Test

class PushHintTest {
    private val origin = "https://meeting.example.test"
    private val id = "11111111-1111-4111-8111-111111111111"
    @Test fun `actual gateway conference link and conversation extension are accepted as hints`() {
        assertEquals(PushHint("conferences", id), PushHint.parse(origin, "$origin/conferences/$id"))
        assertEquals(PushHint("conversations", id), PushHint.parse(origin, "$origin/conversations/$id"))
        assertNotNull(PushHint.parse(origin, "$origin/conferences/$id?recording=$id&tab=summary"))
    }
    @Test fun `foreign origin user info insecure transport and misleading host never authorize a hint`() {
        listOf("http://meeting.example.test", "https://meeting.example.test.attacker.test", "https://other.example.test",
            "https://user:password@meeting.example.test", "https://meeting.example.test:444").forEach {
            assertNull(PushHint.parse(origin, "$it/conferences/$id"))
        }
    }
    @Test fun `unexpected resources invalid ids extra query and fragments are rejected`() {
        listOf("/api/v1/auth/me", "/conferences/../$id", "/conferences/%31$id", "/conferences/$id/download",
            "/conferences/$id?redirect=evil", "/conferences/$id#message", "/conferences/$id?tab=summary&tab=transcript").forEach {
            assertNull(PushHint.parse(origin, origin + it))
        }
    }
    @Test fun `explicit default TLS port is same origin and alternate server port must match`() {
        assertNotNull(PushHint.parse(origin, "$origin:443/conferences/$id"))
        assertNotNull(PushHint.parse("$origin:8443", "$origin:8443/conferences/$id"))
        assertNull(PushHint.parse("$origin:8443", "$origin/conferences/$id"))
    }
    @Test fun `oversized malformed and control character payloads are dropped`() {
        listOf("a".repeat(1025), "$origin/conferences/$id\n", "https://[").forEach { assertNull(PushHint.parse(origin, it)) }
    }
}
