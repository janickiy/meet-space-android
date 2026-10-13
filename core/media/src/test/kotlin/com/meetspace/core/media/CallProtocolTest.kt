package com.meetspace.core.media

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import com.meetspace.core.model.DirectCall
import com.meetspace.core.model.ChatPeer
import java.time.Instant

class CallProtocolTest {
    private val id = "00000000-0000-4000-8000-000000000001"
    @Test fun `envelope round trip keeps scope and correlation`() {
        val raw = CallProtocol.envelope(id, "media.join", id, buildJsonObject {})
        assertEquals("media.join", CallProtocol.event(raw, id).type)
    }
    @Test fun `cross conference event cannot affect call`() = rejects {
        CallProtocol.event(CallProtocol.envelope(id, "media.join", id, buildJsonObject {}), "00000000-0000-4000-8000-000000000002")
    }
    @Test fun `malformed envelope fails closed`() = rejects { CallProtocol.event("{}", id) }
    @Test fun `oversize signaling fails closed`() = rejects { CallProtocol.event(" ".repeat(1_048_577), id) }
    @Test fun `relay policy survives parsing with expiring credentials`() {
        val config = CallProtocol.ice("""{"iceServers":[{"urls":["turn:example.test:3478?transport=udp"],"username":"ephemeral","credential":"test"}],"iceTransportPolicy":"relay","expiresAt":200}""", 100)
        assertTrue(config.relay); assertEquals(200L, config.expiresAt); assertEquals(1, config.servers.size)
    }
    @Test fun `relay without TURN is rejected`() = rejects { CallProtocol.ice("""{"iceServers":[],"iceTransportPolicy":"relay"}""") }
    @Test fun `expired credentials cannot start ICE`() = rejects { CallProtocol.ice("""{"iceServers":[],"expiresAt":129}""", 100) }
    @Test fun `unknown policy does not weaken relay`() = rejects { CallProtocol.ice("""{"iceServers":[],"iceTransportPolicy":"optional"}""") }
    @Test fun `http addresses cannot become ICE servers`() = rejects { CallProtocol.ice("""{"iceServers":[{"urls":["https://evil.test"]}]}""") }
    @Test fun `screen catalog uses distinct stream and typed source`() {
        val data = safeObject("""{"tracks":[{"id":"$id","mediaPeerId":"$id","participantId":"$id","streamId":"$id-screen","kind":"video","source":"video/screen"}]}""")
        assertEquals("video/screen", CallProtocol.catalog(data).single().source)
    }
    @Test fun `wrong screen binding is rejected`() = rejects {
        CallProtocol.catalog(safeObject("""{"tracks":[{"id":"$id","mediaPeerId":"$id","participantId":"$id","streamId":"$id","kind":"video","source":"video/screen"}]}"""))
    }
    @Test fun `only matching ready ack frees negotiation slot`() {
        val gate = NegotiationGate(); val first = gate.begin()!!
        assertNull(gate.begin()); gate.remote(5); gate.changed()
        first.ready = id
        assertFalse(gate.acknowledge("other")); assertNull(gate.begin())
        assertTrue(gate.acknowledge(id))
        val next = gate.begin()!!; assertEquals(5, next.revision); assertEquals(1, next.localRevision)
        next.ready = "second"; gate.acknowledge("second"); assertNull(gate.begin())
    }
    @Test fun `stale revision does not renegotiate`() {
        val gate = NegotiationGate(); gate.remote(10); gate.begin()!!.ready = "ack"; gate.acknowledge("ack")
        gate.remote(9); assertNull(gate.begin())
    }
    @Test fun `rollback preserves pending changes`() {
        val gate = NegotiationGate(); gate.begin(); gate.remote(3); gate.rollback()
        assertEquals(3, gate.begin()!!.revision)
    }
    @Test fun `direct media requires accepted state account endpoint and exact room together`() {
        val caller = "00000000-0000-4000-8000-000000000002"
        val callee = "00000000-0000-4000-8000-000000000003"
        val firstClient = "00000000-0000-4000-8000-000000000004"
        val answeringClient = "00000000-0000-4000-8000-000000000005"
        val accepted = DirectCall(id, id, id, caller, callee, firstClient, answeringClient,
            ChatPeer(caller, "Caller"), ChatPeer(callee, "Callee"), "accepted", 2, Instant.EPOCH,
            Instant.EPOCH.plusSeconds(45), Instant.EPOCH)
        assertTrue(CallProtocol.directEndpoint(accepted, caller, firstClient, id))
        assertTrue(CallProtocol.directEndpoint(accepted, callee, answeringClient, id))
        assertFalse(CallProtocol.directEndpoint(accepted, callee, firstClient, id))
        assertFalse(CallProtocol.directEndpoint(accepted, caller, answeringClient, id))
        assertFalse(CallProtocol.directEndpoint(accepted, id, firstClient, id))
        assertFalse(CallProtocol.directEndpoint(accepted, caller, firstClient, caller))
        assertFalse(CallProtocol.directEndpoint(accepted.copy(status = "ringing"), caller, firstClient, id))
        assertFalse(CallProtocol.directEndpoint(accepted.copy(status = "ended", endedAt = Instant.EPOCH), caller, firstClient, id))
        assertFalse(CallProtocol.directEndpoint(accepted.copy(acceptedAt = null), caller, firstClient, id))
    }
    private fun rejects(block: () -> Unit) { try { block(); fail("expected protocol rejection") } catch (_: ProtocolFailure) {} }
}
