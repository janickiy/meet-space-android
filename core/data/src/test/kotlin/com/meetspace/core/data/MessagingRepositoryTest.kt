package com.meetspace.core.data

import com.meetspace.core.model.*
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MessagingRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: DefaultFoundationRepository
    private lateinit var origin: String
    private lateinit var client: OkHttpClient
    private lateinit var certificates: HandshakeCertificates

    @Before fun setup() {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        certificates = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        server = tlsServer()
        origin = server.url("/").toString().removeSuffix("/")
        client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
            .callTimeout(5, TimeUnit.SECONDS).build()
        repository = DefaultFoundationRepository(MessagingProfiles(), MessagingCredentials(), MeetSpaceApi(client))
    }
    @After fun stop() { server.shutdown() }

    @Test fun `search context and shared pins remain scoped and encode query`() = runBlocking {
        login()
        server.enqueue(json("""{"items":[${message().dropLast(1)},"pinned":true}],"nextCursor":"next+/="}"""))
        val page = repository.searchMessages(CHAT, "text & query", "old+/=")
        assertTrue(page.items.single().pinned)
        val search = take(); assertEquals("/api/v1/conversations/$CHAT/messages/search", search.requestUrl!!.encodedPath)
        assertEquals("text & query", search.requestUrl!!.queryParameter("q")); assertEquals("old+/=", search.requestUrl!!.queryParameter("before"))
        server.enqueue(json("""{"items":[${message()}]}""")); repository.messageContext(CHAT, MESSAGE)
        assertEquals("/api/v1/conversations/$CHAT/messages/$MESSAGE/context", take().path)
        server.enqueue(json("""{"items":[${message()}]}""")); repository.messagePins(CHAT)
        assertEquals("/api/v1/conversations/$CHAT/pins", take().requestUrl!!.encodedPath)
        repeat(2) { i -> server.enqueue(json("{}")); repository.pinMessage(CHAT, MESSAGE, i == 0)
            val req = take(); assertEquals(if (i == 0) "PUT" else "DELETE", req.method); assertEquals("/api/v1/conversations/$CHAT/pins/$MESSAGE", req.path) }
    }
    @Test fun `foreign search and pinned message responses are rejected`() = runBlocking {
        login(); repeat(2) { server.enqueue(json("""{"items":[${message().replace(CHAT, OTHER)}]}""")) }
        assertFailure(FailureKind.INVALID_RESPONSE) { repository.searchMessages(CHAT, "query") }
        assertFailure(FailureKind.INVALID_RESPONSE) { repository.messagePins(CHAT) }
    }
    @Test fun `clear and hide use personal endpoints and not group deletion`() = runBlocking {
        login(); server.enqueue(json("{}")); repository.clearConversation(CHAT, false)
        val clear = take(); assertEquals("POST", clear.method); assertEquals("/api/v1/conversations/$CHAT/clear-history", clear.path)
        server.enqueue(json("{}")); repository.clearConversation(CHAT, true)
        val hide = take(); assertEquals("POST", hide.method); assertEquals("/api/v1/conversations/$CHAT/hide", hide.path)
    }

    @Test fun `conversation filters and cursor are encoded and bearer is scoped without cookie`() = runBlocking {
        login(); server.enqueue(json("""{"items":[${conversation()}],"nextCursor":"new+/=","unreadCount":3}"""))
        val page = repository.conversations(ChatQuery("Team & A", "group", true, "old+/="))
        assertEquals(3L, page.unreadCount); assertEquals("new+/=", page.nextCursor)
        val req = take(); assertEquals("/api/v1/conversations", req.requestUrl!!.encodedPath)
        assertEquals("old+/=", req.requestUrl!!.queryParameter("before")); assertEquals("Team & A", req.requestUrl!!.queryParameter("search"))
        assertEquals("true", req.requestUrl!!.queryParameter("unreadOnly")); assertEquals("Bearer access", req.getHeader("Authorization"))
        assertNull(req.getHeader("Cookie"))
    }
    @Test fun `stable request id is retained for repeated sends with reply and attachments`() = runBlocking {
        login(); repeat(2) { server.enqueue(json(item(message()))); repository.sendMessage(CHAT, ChatMessageInput(REQUEST, "Привет")) }
        val first = take(); val second = take()
        assertEquals(first.body.readUtf8(), second.body.readUtf8())
        assertEquals("POST", first.method); assertEquals("/api/v1/conversations/$CHAT/messages", first.path)
    }
    @Test fun `message response cannot substitute account or scope`() = runBlocking {
        login()
        server.enqueue(json(item(message().replace(CHAT, OTHER)))); assertFailure(FailureKind.INVALID_RESPONSE) { repository.sendMessage(CHAT, ChatMessageInput(REQUEST, "Привет")) }
        server.enqueue(json(item(message().replace(USER, OTHER)))); assertFailure(FailureKind.INVALID_RESPONSE) { repository.sendMessage(CHAT, ChatMessageInput(REQUEST, "Привет")) }
    }
    @Test fun `message sequence uses full 64 bit string and null attachment list is empty`() = runBlocking {
        login(); server.enqueue(json("""{"items":[${message().replace("\"sequence\":\"9\"", "\"sequence\":\"9007199254740993\"")}],"unreadCount":1,"lastReadMessageId":null}"""))
        val page = repository.messages(CHAT)
        assertEquals(9007199254740993L, page.items.single().sequence); assertTrue(page.items.single().attachments.isEmpty())
    }
    @Test fun `foreign conversation message in a history page is rejected`() = runBlocking {
        login(); server.enqueue(json("""{"items":[${message().replace(CHAT, OTHER)}],"unreadCount":1,"lastReadMessageId":null}"""))
        assertFailure(FailureKind.INVALID_RESPONSE) { repository.messages(CHAT) }
    }
    @Test fun `validation rejects path injection oversized text and duplicate attachment ids before network`() = runBlocking {
        login(); val before = server.requestCount
        assertFailure(FailureKind.VALIDATION) { repository.messages("../auth/me") }
        assertFailure(FailureKind.VALIDATION) { repository.sendMessage(CHAT, ChatMessageInput(REQUEST, "a".repeat(4001))) }
        assertFailure(FailureKind.VALIDATION) { repository.sendMessage(CHAT, ChatMessageInput(REQUEST, "", attachmentIds = listOf(FILE, FILE))) }
        assertEquals(before, server.requestCount)
    }
    @Test fun `read endpoint uses server message id and returns confirmed unread state`() = runBlocking {
        login(); server.enqueue(json("""{"item":{"lastReadMessageId":"$MESSAGE","unreadCount":0}}"""))
        assertEquals(0L, repository.markConversationRead(CHAT, MESSAGE).unreadCount)
        val request = take(); assertEquals("/api/v1/conversations/$CHAT/read", request.path)
        assertEquals("""{"messageId":"$MESSAGE"}""", request.body.readUtf8())
    }
    @Test fun `group creation preserves idempotency and never invents management permissions`() = runBlocking {
        login(); server.enqueue(json(item(conversation().replace("\"myRole\":\"owner\"", "\"myRole\":\"future\""))))
        val group = repository.createGroup("Команда", "Описание", listOf(OTHER), REQUEST)
        assertFalse(group.canManage)
        val body = wireJson.parseToJsonElement(take().body.readUtf8()).jsonObject
        assertEquals(REQUEST, body["clientRequestId"]!!.toString().trim('"')); assertTrue(body["memberIds"].toString().contains(OTHER))
    }
    @Test fun `attachment upload uses trusted constructed path and checks digest after finalize`() = runBlocking {
        login(); val bytes = "hello".toByteArray(); val digest = hash(bytes)
        server.enqueue(json("""{"item":${attachment("pending")},"uploadUrl":"/api/v1/conversations/$CHAT/attachments/$FILE/content"}"""))
        server.enqueue(json(item(attachment("pending", digest)))); server.enqueue(json(item(attachment("ready", digest))))
        val uploaded = repository.uploadAttachment(CHAT, "a.txt", "text/plain", bytes, REQUEST)
        assertEquals(digest, uploaded.checksum); assertEquals("ready", uploaded.status)
        take(); val request = take(); assertEquals("PUT", request.method); assertArrayEquals(bytes, request.body.readByteArray())
        assertEquals("Bearer access", request.getHeader("Authorization")); assertNull(request.getHeader("Cookie"))
        assertEquals("/api/v1/conversations/$CHAT/attachments/$FILE/finalize", take().path)
    }
    @Test fun `CSV picker plain text MIME is normalized before init and checksum validation`() = runBlocking {
        login(); val bytes = "a,b\nc".toByteArray(); val digest = hash(bytes)
        fun csv(status: String, sum: String = "") = attachment(status, sum).replace("a.txt", "a.csv").replace("text/plain", "text/csv")
        server.enqueue(json("""{"item":${csv("pending")},"uploadUrl":"/api/v1/conversations/$CHAT/attachments/$FILE/content"}"""))
        server.enqueue(json(item(csv("pending", digest)))); server.enqueue(json(item(csv("ready", digest))))
        assertEquals("text/csv", repository.uploadAttachment(CHAT, "a.csv", "text/plain; charset=utf-8", bytes, REQUEST).mimeType)
        assertTrue(take().body.readUtf8().contains("text/csv"))
    }
    @Test fun `server supplied foreign upload URL never receives file or token`() = runBlocking {
        login(); val other = tlsServer()
        try {
            server.enqueue(json("""{"item":${attachment("pending")},"uploadUrl":"${other.url("/steal")}"}"""))
            assertFailure(FailureKind.INVALID_RESPONSE) { repository.uploadAttachment(CHAT, "a.txt", "text/plain", "hello".toByteArray(), REQUEST) }
            assertEquals(0, other.requestCount)
        } finally { other.shutdown() }
    }
    @Test fun `signed attachment download never forwards bearer or cookies even on same origin`() = runBlocking {
        login(); val bytes = "hello".toByteArray()
        server.enqueue(downloadResponse(server.url("/signed?capability=synthetic").toString()))
        server.enqueue(MockResponse().setBody("hello"))
        assertArrayEquals(bytes, repository.downloadAttachment(CHAT, attachmentModel(bytes)))
        take(); val download = take(); assertNull(download.getHeader("Authorization")); assertNull(download.getHeader("Cookie"))
    }
    @Test fun `signed cross origin HTTPS download retains hostname verification without sending JWT`() = runBlocking {
        login(); val other = tlsServer(); val bytes = "hello".toByteArray()
        try {
            server.enqueue(downloadResponse(other.url("/signed").toString())); other.enqueue(MockResponse().setBody("hello"))
            assertArrayEquals(bytes, repository.downloadAttachment(CHAT, attachmentModel(bytes)))
            val request = other.takeRequest(3, TimeUnit.SECONDS)!!
            assertNull(request.getHeader("Authorization")); assertNull(request.getHeader("Cookie"))
        } finally { other.shutdown() }
    }
    @Test fun `authenticated download requires exact resource content path`() = runBlocking {
        login(); val bytes = "hello".toByteArray()
        server.enqueue(downloadResponse("/api/v1/conversations/$CHAT/attachments/$FILE/content", true)); server.enqueue(MockResponse().setBody("hello"))
        assertArrayEquals(bytes, repository.downloadAttachment(CHAT, attachmentModel(bytes)))
        take(); assertEquals("Bearer access", take().getHeader("Authorization"))
        server.enqueue(downloadResponse("/api/v1/auth/me", true))
        assertFailure(FailureKind.INVALID_RESPONSE) { repository.downloadAttachment(CHAT, attachmentModel(bytes)) }
    }
    @Test fun `download rejects insecure URL redirect size mismatch and incorrect digest`() = runBlocking {
        login(); val bytes = "hello".toByteArray()
        server.enqueue(downloadResponse("http://localhost/unsafe"))
        assertFailure(FailureKind.INVALID_RESPONSE) { repository.downloadAttachment(CHAT, attachmentModel(bytes)) }
        server.enqueue(downloadResponse(server.url("/signed").toString())); server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://localhost/unsafe"))
        assertFailure(FailureKind.UNAVAILABLE) { repository.downloadAttachment(CHAT, attachmentModel(bytes)) }
        server.enqueue(downloadResponse(server.url("/signed").toString())); server.enqueue(MockResponse().setBody("toolong"))
        assertFailure(FailureKind.INVALID_RESPONSE) { repository.downloadAttachment(CHAT, attachmentModel(bytes)) }
        server.enqueue(downloadResponse(server.url("/signed").toString())); server.enqueue(MockResponse().setBody("other"))
        assertFailure(FailureKind.INVALID_RESPONSE) { repository.downloadAttachment(CHAT, attachmentModel(bytes)) }
    }
    @Test fun `signed object 401 cannot expire or refresh account session`() = runBlocking {
        login(); server.enqueue(downloadResponse(server.url("/signed").toString())); server.enqueue(MockResponse().setResponseCode(401))
        assertFailure(FailureKind.FORBIDDEN) { repository.downloadAttachment(CHAT, attachmentModel("hello".toByteArray())) }
        assertNotNull(repository.state.value.session); assertEquals(6, server.requestCount)
    }
    @Test fun `notification prefs use raw envelope and preserve external channel settings`() = runBlocking {
        login(); val raw = """{"invitation":true,"reminder":false,"recording":true,"summary":false,"email":true,"push":true}"""
        server.enqueue(json(raw)); val prefs = repository.notificationPreferences(); assertTrue(prefs.email); assertTrue(prefs.push)
        server.enqueue(json(raw)); assertEquals(prefs, repository.saveNotificationPreferences(prefs))
        take(); val request = take(); assertEquals("PUT", request.method); assertEquals(wireJson.parseToJsonElement(raw), wireJson.parseToJsonElement(request.body.readUtf8()))
    }
    @Test fun `notifications are account bound and cursor uses its own parameter`() = runBlocking {
        login(); val notification = """{"id":"$MESSAGE","userId":"$USER","type":"conference.soon","createdAt":"$AT","readAt":null,"payload":{"conferenceId":"$OTHER"}}"""
        server.enqueue(json("""{"items":[$notification],"unreadCount":1,"nextCursor":null}"""))
        assertEquals(OTHER, repository.notifications("cursor+/=").items.single().conferenceId)
        assertEquals("cursor+/=", take().requestUrl!!.queryParameter("cursor"))
        server.enqueue(json("""{"items":[${notification.replace(USER, OTHER)}],"unreadCount":1}"""))
        assertFailure(FailureKind.INVALID_RESPONSE) { repository.notifications() }
    }
    @Test fun `notification resource references retain recording transcript and summary destinations`() = runBlocking {
        login()
        val notification = """{"id":"$MESSAGE","userId":"$USER","type":"summary.ready","createdAt":"$AT","readAt":null,"payload":{"conferenceId":"$CHAT","recordingId":"$OTHER","transcriptId":"$MESSAGE","summaryId":"$USER"}}"""
        server.enqueue(json("""{"items":[$notification],"unreadCount":1,"nextCursor":null}"""))
        val item = repository.notifications().items.single()
        assertEquals(OTHER, item.recordingId); assertEquals(MESSAGE, item.transcriptId); assertEquals(USER, item.summaryId)
        take()
        server.enqueue(json("""{"items":[${notification.replace("\"recordingId\":\"$OTHER\"", "\"recordingId\":\"https://untrusted.invalid/record\"")}],"unreadCount":1}"""))
        assertFailure(FailureKind.INVALID_RESPONSE) { repository.notifications() }
    }
    @Test fun `folder candidate filters preserve mixed kinds and membership`() = runBlocking {
        login(); server.enqueue(json("""{"items":[{"type":"conversation","item":${conversation()},"inFolder":true}],"nextCursor":"next"}"""))
        val page = repository.folderItems(OTHER, FolderQuery(candidates = true, search = "Team"))
        assertTrue(page.items.single().inFolder); assertEquals(CHAT, page.items.single().id)
        val req = take(); assertEquals("/api/v1/folder-items", req.requestUrl!!.encodedPath); assertEquals(OTHER, req.requestUrl!!.queryParameter("folderId"))
    }
    @Test fun `global socket uses only ticket without conference subprotocol and closes on logout`() = runBlocking {
        login(); server.enqueue(json("""{"ticket":"${"a".repeat(43)}"}"""))
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send(event()) }
        }))
        val events = repository.openUserEvents()
        assertEquals("connected", withTimeout(3000) { events.events.receive() }.type)
        assertEquals(CHAT, withTimeout(3000) { events.events.receive() }.conversationId)
        assertEquals("/api/v1/ws-ticket", take().path)
        val socket = take(); assertEquals("/api/v1/ws", socket.requestUrl!!.encodedPath)
        assertNull(socket.getHeader("Authorization")); assertNull(socket.getHeader("Cookie")); assertNull(socket.getHeader("Sec-WebSocket-Protocol"))
        server.enqueue(json("""{"status":"success"}""")); repository.logout()
        assertTrue(withTimeout(1000) { events.events.receiveCatching().isClosed })
    }
    @Test fun `global event rejects malformed envelope but ignores untrusted message text`() {
        val parsed = decodeUserEvent(event())
        assertEquals(CHAT, parsed.conversationId); assertEquals("message.created", parsed.type)
        for (bad in listOf(event().replace("\"version\":1", "\"version\":2"), event().replace(CHAT, "../../auth"), "[1]", "a".repeat(131073))) {
            try { decodeUserEvent(bad); fail("Malformed event accepted") } catch (_: InvalidContract) { }
        }
    }
    @Test fun `push registration response and 204 revocation have no token exposure`() = runBlocking {
        login(); server.enqueue(json("""{"item":{"id":"$OTHER","platform":"android","provider":"http"}}"""))
        assertEquals(OTHER, repository.registerPushDevice("synthetic-fcm-registration").id); take()
        server.enqueue(MockResponse().setResponseCode(204)); repository.revokePushDevice(OTHER)
        assertEquals("DELETE", take().method)
    }

    private fun tlsServer() = MockWebServer().apply { useHttps(certificates.sslSocketFactory(), false); start() }
    private suspend fun login() {
        server.enqueue(json("""{"version":"dev","commit":"unknown","buildTime":"unknown"}""")); repository.addServer(origin)
        server.enqueue(json("""{"status":"success","accessToken":"access","tokenType":"Bearer","expiresIn":3600,"user":{"id":"$USER","email":"test@example.com","displayName":"Test"}}""")
            .addHeader("Set-Cookie", "$SESSION_COOKIE=cookie; Path=$AUTH_PATH; Secure; HttpOnly; Max-Age=3600"))
        server.enqueue(json("""{"status":"success","user":{"id":"$USER","email":"test@example.com","displayName":"Test","isAdmin":false}}"""))
        server.enqueue(json("""{"status":"success","buildVersion":"dev","capabilities":{"liveCaptions":false,"transcription":false,"aiSummary":false,"semanticSearch":false,"meetingAnalytics":false,"recordingModes":["composite"]}}"""))
        repository.login("test@example.com", "synthetic"); repeat(4) { take() }
    }
    private suspend fun assertFailure(kind: FailureKind, call: suspend () -> Any?) {
        try { call(); fail("Expected failure") } catch (failure: AppFailure) { assertEquals(kind, failure.kind) }
    }
    private fun take(): RecordedRequest = server.takeRequest(3, TimeUnit.SECONDS) ?: error("Missing request")
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun item(body: String) = """{"status":"success","item":$body}"""
    private fun downloadResponse(url: String, authenticated: Boolean = false) = json("""{"url":"$url","expiresAt":"${Instant.now().plusSeconds(120)}","authenticated":$authenticated}""")
    private fun conversation() = """{"id":"$CHAT","type":"group","name":"Team","description":"","myRole":"owner","memberCount":2,"preview":"","unreadCount":3,"notificationsEnabled":true,"createdAt":"$AT","updatedAt":"$AT"}"""
    private fun message() = """{"id":"$MESSAGE","conversationId":"$CHAT","senderId":"$USER","senderName":"Test","clientRequestId":"$REQUEST","text":"Привет","sequence":"9","version":1,"createdAt":"$AT","updatedAt":"$AT","deletedAt":null,"replyTo":null,"attachments":null}"""
    private fun attachment(status: String, digest: String = "") = """{"id":"$FILE","conversationId":"$CHAT","filename":"a.txt","mimeType":"text/plain","size":5,"status":"$status","checksum":"$digest"}"""
    private fun attachmentModel(bytes: ByteArray) = ChatAttachment(FILE, CHAT, "a.txt", "text/plain", bytes.size.toLong(), "attached", hash(bytes))
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun event() = """{"version":1,"id":"$MESSAGE","type":"message.created","timestamp":"$AT","conferenceId":"","data":{"conversationId":"$CHAT","message":{"text":"untrusted content"}}}"""
    companion object {
        private const val CHAT = "11111111-1111-4111-8111-111111111111"
        private const val USER = "22222222-2222-4222-8222-222222222222"
        private const val OTHER = "33333333-3333-4333-8333-333333333333"
        private const val MESSAGE = "44444444-4444-4444-8444-444444444444"
        private const val REQUEST = "55555555-5555-4555-8555-555555555555"
        private const val FILE = "66666666-6666-4666-8666-666666666666"
        private const val AT = "2026-10-11T10:00:00Z"
    }
}
private class MessagingProfiles : ProfileStore {
    private var value = PersistedSettings()
    override suspend fun read() = value
    override suspend fun write(settings: PersistedSettings) { value = settings }
}
private class MessagingCredentials : CredentialStore {
    private val values = mutableMapOf<SessionScope, StoredCredential>()
    override suspend fun read(scope: SessionScope) = values[scope]
    override suspend fun write(scope: SessionScope, credential: StoredCredential) { values[scope] = credential }
    override suspend fun delete(scope: SessionScope) { values.remove(scope) }
}
