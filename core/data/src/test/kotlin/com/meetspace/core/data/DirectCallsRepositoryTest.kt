package com.meetspace.core.data

import com.meetspace.core.model.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject

class DirectCallsRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: DefaultFoundationRepository
    @Before fun setUp() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer().apply { useHttps(tls.sslSocketFactory(), false); start() }
        val client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
            .callTimeout(3, TimeUnit.SECONDS).build()
        repository = DefaultFoundationRepository(DirectProfiles(), DirectCredentials(), MeetSpaceApi(client))
    }
    @After fun tearDown() { server.shutdown() }

    @Test fun `create carries all endpoint and idempotency identifiers and explicit retry keeps them`() = runBlocking {
        login()
        server.enqueue(response(item(call()), 201))
        val first = repository.startDirectCall(CALLEE, REQUEST, CLIENT)
        val request = take()
        assertEquals("POST", request.method); assertEquals("/api/v1/calls", request.path)
        assertEquals("Bearer access", request.getHeader("Authorization")); assertNull(request.getHeader("Cookie"))
        assertEquals("""{"targetUserId":"$CALLEE","clientRequestId":"$REQUEST","clientId":"$CLIENT"}""", request.body.readUtf8())
        server.enqueue(response(item(call())))
        assertEquals(first, repository.startDirectCall(CALLEE, REQUEST, CLIENT))
        assertEquals("/api/v1/calls", take().path)
        assertTrue(first.ownsMedia(CALLER, CLIENT)); assertFalse(first.ownsMedia(CALLEE, CLIENT))
        assertEquals(CALLEE, first.peer(CALLER)?.id)
    }
    @Test fun `active null is legitimate while a foreign account projection is rejected`(): Unit = runBlocking {
        login(); server.enqueue(response("""{"status":"success","item":null}"""))
        assertNull(repository.activeDirectCall()); assertEquals("/api/v1/calls/active", take().path)
        server.enqueue(response(item(call(accepted = true))))
        assertEquals(CONFERENCE, repository.activeDirectCall()?.conferenceId); take()
        server.enqueue(response(item(call().replace(CALLER, OTHER))))
        assertEquals(FailureKind.INVALID_RESPONSE, failure { repository.activeDirectCall() }.kind); take()
    }
    @Test fun `all commands use their scoped route and decline sends no endpoint credentials`() = runBlocking {
        login()
        for (action in DirectCallAction.entries) {
            server.enqueue(response(item(call(accepted = action in setOf(DirectCallAction.ACCEPT, DirectCallAction.END, DirectCallAction.HEARTBEAT)))))
            assertEquals(CALL, repository.directCallAction(CALL, action, CLIENT).id)
            val request = take()
            assertEquals("POST", request.method); assertEquals("/api/v1/calls/$CALL/${action.wire}", request.path)
            assertEquals(if (action == DirectCallAction.DECLINE) "{}" else """{"clientId":"$CLIENT"}""", request.body.readUtf8())
        }
    }
    @Test fun `lost mutation acknowledgement is never replayed and a fresh read reconciles server state`() = runBlocking {
        login()
        val before = server.requestCount
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertTrue(failure { repository.directCallAction(CALL, DirectCallAction.ACCEPT, CLIENT) }.kind in
            setOf(FailureKind.NETWORK, FailureKind.TIMEOUT, FailureKind.UNAVAILABLE))
        assertEquals(before + 1, server.requestCount); assertEquals("/api/v1/calls/$CALL/accept", take().path)
        server.enqueue(response(item(call(accepted = true))))
        assertEquals("accepted", repository.directCall(CALL).status)
        assertEquals("GET", take().method)
        assertEquals(before + 2, server.requestCount)
    }
    @Test fun `refresh after a rejected mutation never replays the call command`() = runBlocking {
        login(); val before = server.requestCount
        server.enqueue(response("{}", 401)); server.enqueue(loginResponse("fresh"))
        assertEquals(FailureKind.CONFLICT, failure { repository.directCallAction(CALL, DirectCallAction.END, CLIENT) }.kind)
        assertEquals("/api/v1/calls/$CALL/end", take().path)
        assertEquals("/api/v1/auth/refresh", take().path)
        assertEquals(before + 2, server.requestCount)
    }
    @Test fun `malformed ownership lifecycle and resource substitutions fail closed`() = runBlocking {
        login()
        for (bad in listOf(call().replace(CALL, OTHER), call().replace("\"version\":1", "\"version\":0"),
            call().replace("\"status\":\"ringing\"", "\"status\":\"accepted\""),
            call(accepted = true).replace("\"acceptedByClientId\":\"$ACCEPTING_CLIENT\"", "\"acceptedByClientId\":null"),
            call().replace("\"caller\":{\"id\":\"$CALLER\"", "\"caller\":{\"id\":\"$OTHER\""))) {
            server.enqueue(response(item(bad)))
            assertEquals(FailureKind.INVALID_RESPONSE, failure { repository.directCall(CALL) }.kind); take()
        }
    }
    @Test fun `invalid endpoint identifiers and calling self fail before any request`() = runBlocking {
        login(); val before = server.requestCount
        assertEquals(FailureKind.VALIDATION, failure { repository.startDirectCall(CALLER, REQUEST, CLIENT) }.kind)
        assertEquals(FailureKind.VALIDATION, failure { repository.startDirectCall(CALLEE, "bad", CLIENT) }.kind)
        assertEquals(FailureKind.VALIDATION, failure { repository.directCallAction(CALL, DirectCallAction.END, "bad") }.kind)
        assertEquals(before, server.requestCount)
    }
    @Test fun `personal call events expose only identifiers for authoritative reconciliation`() {
        val event = """{"version":1,"id":"$REQUEST","type":"call.updated","conferenceId":"","timestamp":"$AT","data":{"call":${call()}}}"""
        val decoded = decodeUserEvent(event)
        assertEquals("call.updated", decoded.type); assertEquals(CALL, decoded.callId)
        assertEquals(CONVERSATION, decoded.conversationId)
        for (bad in listOf(event.replace(CALL, "not-a-uuid"), event.replace("\"call\":${call()}", "\"call\":null"))) {
            try { decodeUserEvent(bad); fail("Invalid call event accepted") } catch (_: InvalidContract) { }
        }
    }
    @Test fun `call history is projected as a read-only call and keeps its chronological message metadata`() = runBlocking {
        login()
        val history = """{"id":"$CALL","callerUserId":"$CALLER","calleeUserId":"$CALLEE","status":"ended","createdAt":"$AT","acceptedAt":"$AT","endedAt":"2026-10-12T10:00:12Z","durationSec":12}"""
        val message = """{"id":"$REQUEST","conversationId":"$CONVERSATION","senderId":"$CALLER","senderName":"Caller","clientRequestId":"$CLIENT","text":"","sequence":"9","version":3,"createdAt":"$AT","updatedAt":"2026-10-12T10:00:12Z","deletedAt":null,"replyTo":null,"attachments":null,"call":$history}"""
        server.enqueue(response("""{"status":"success","items":[$message],"unreadCount":0}"""))
        val row = repository.messages(CONVERSATION).items.single(); take()
        assertEquals("call", row.kind); assertEquals(9L, row.sequence); assertEquals(3L, row.version)
        assertEquals(12L, row.call?.durationSec); assertEquals("ended", row.call?.status)
    }
    @Test fun `direct media ticket carries endpoint binding while ordinary meeting tickets stay unchanged`() = runBlocking {
        login()
        server.enqueue(response("""{"iceServers":[],"iceTransportPolicy":"all"}"""))
        server.enqueue(response("""{"ticket":"${"a".repeat(43)}"}"""))
        server.enqueue(MockResponse().setHeader("Sec-WebSocket-Protocol", "go-recorder.v1").withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send("fixture") }
        }))
        val signaling = repository.openDirectCall(CONFERENCE, CLIENT)
        try {
            assertEquals("fixture", withTimeout(5_000) { signaling.messages.receive() })
            assertEquals("/api/v1/webrtc/config", take().path)
            val ticket = take()
            assertEquals("/api/v1/conferences/$CONFERENCE/ws-ticket", ticket.path)
            assertEquals("""{"clientId":"$CLIENT"}""", ticket.body.readUtf8())
            assertEquals("Bearer access", ticket.getHeader("Authorization"))
            assertEquals("go-recorder.v1", take().getHeader("Sec-WebSocket-Protocol"))
        } finally { signaling.close() }
    }

    private suspend fun login() {
        server.enqueue(response("""{"version":"dev","commit":"unknown","buildTime":"unknown"}"""))
        repository.addServer(server.url("/").toString().removeSuffix("/"))
        server.enqueue(loginResponse())
        server.enqueue(response("""{"status":"success","user":{"id":"$CALLER","email":"test@example.com","displayName":"Caller","isAdmin":false}}"""))
        server.enqueue(response("""{"status":"success","buildVersion":"dev","capabilities":{"liveCaptions":false,"transcription":false,"aiSummary":false,"semanticSearch":false,"meetingAnalytics":false,"recordingModes":["composite"]}}"""))
        repository.login("test@example.com", "synthetic"); repeat(4) { take() }
    }
    private fun loginResponse(token: String = "access") = response("""{"status":"success","accessToken":"$token","tokenType":"Bearer","expiresIn":3600,"user":{"id":"$CALLER","email":"test@example.com","displayName":"Caller"}}""")
        .addHeader("Set-Cookie", "$SESSION_COOKIE=cookie; Path=$AUTH_PATH; Secure; HttpOnly; Max-Age=3600")
    private fun call(accepted: Boolean = false) = """{"id":"$CALL","conversationId":"$CONVERSATION","conferenceId":${if (accepted) "\"$CONFERENCE\"" else "null"},"callerUserId":"$CALLER","calleeUserId":"$CALLEE","callerClientId":"$CLIENT","acceptedByClientId":${if (accepted) "\"$ACCEPTING_CLIENT\"" else "null"},"caller":{"id":"$CALLER","displayName":"Caller"},"callee":{"id":"$CALLEE","displayName":"Callee"},"status":"${if (accepted) "accepted" else "ringing"}","version":1,"createdAt":"$AT","expiresAt":"2026-10-12T10:00:45Z","acceptedAt":${if (accepted) "\"$AT\"" else "null"},"endedAt":null,"durationSec":0}"""
    private fun item(call: String) = """{"status":"success","item":$call}"""
    private fun response(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
    private fun take(): RecordedRequest = server.takeRequest(3, TimeUnit.SECONDS) ?: error("Missing request")
    private suspend fun failure(block: suspend () -> Any?): AppFailure = try { block(); error("Expected failure") } catch (failure: AppFailure) { failure }
    companion object {
        private const val CALL = "11111111-1111-4111-8111-111111111111"
        private const val CALLER = "22222222-2222-4222-8222-222222222222"
        private const val CALLEE = "33333333-3333-4333-8333-333333333333"
        private const val CLIENT = "44444444-4444-4444-8444-444444444444"
        private const val ACCEPTING_CLIENT = "55555555-5555-4555-8555-555555555555"
        private const val CONVERSATION = "66666666-6666-4666-8666-666666666666"
        private const val CONFERENCE = "77777777-7777-4777-8777-777777777777"
        private const val REQUEST = "88888888-8888-4888-8888-888888888888"
        private const val OTHER = "99999999-9999-4999-8999-999999999999"
        private const val AT = "2026-10-12T10:00:00Z"
    }
}
private class DirectProfiles : ProfileStore {
    private var settings = PersistedSettings()
    override suspend fun read() = settings
    override suspend fun write(settings: PersistedSettings) { this.settings = settings }
}
private class DirectCredentials : CredentialStore {
    private val values = mutableMapOf<SessionScope, StoredCredential>()
    override suspend fun read(scope: SessionScope) = values[scope]
    override suspend fun write(scope: SessionScope, credential: StoredCredential) { values[scope] = credential }
    override suspend fun delete(scope: SessionScope) { values.remove(scope) }
}
