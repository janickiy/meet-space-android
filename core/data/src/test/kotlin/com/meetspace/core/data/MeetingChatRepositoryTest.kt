package com.meetspace.core.data

import com.meetspace.core.model.AppFailure
import com.meetspace.core.model.ChatMessageInput
import com.meetspace.core.model.FailureKind
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
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
import com.meetspace.core.model.MeetingChatAttachment
import java.time.Instant

class MeetingChatRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: DefaultFoundationRepository
    private lateinit var certificates: HandshakeCertificates
    private lateinit var origin: String

    @Before fun setUp() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        certificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trusted = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = tlsServer()
        origin = server.url("/").toString().removeSuffix("/")
        val client = OkHttpClient.Builder().sslSocketFactory(trusted.sslSocketFactory(), trusted.trustManager)
            .callTimeout(5, TimeUnit.SECONDS).build()
        repository = DefaultFoundationRepository(MeetingChatProfiles(), MeetingChatCredentials(), MeetSpaceApi(client))
    }

    @After fun tearDown() { server.shutdown() }

    @Test fun `history is chronological and older pages retain the meeting scope and read state`() = runBlocking {
        login()
        server.enqueue(json(page(listOf(message(sequence = 8), message(id = MESSAGE_2, sequence = 9)), cursor(8))))
        val latest = repository.meetingMessages(CONFERENCE, limit = 2)
        assertEquals(listOf(8L, 9L), latest.items.map { it.sequence })
        assertEquals(2L, latest.unreadCount)
        assertEquals(MESSAGE, latest.lastReadMessageId)
        val first = take()
        assertEquals("/api/v1/conferences/$CONFERENCE/messages", first.requestUrl!!.encodedPath)
        assertEquals("2", first.requestUrl!!.queryParameter("limit"))
        assertNull(first.requestUrl!!.queryParameter("before"))
        assertEquals("Bearer access", first.getHeader("Authorization"))
        assertNull(first.getHeader("Cookie"))

        server.enqueue(json(page(listOf(message(sequence = 7)))))
        val older = repository.meetingMessages(CONFERENCE, latest.nextCursor, 2)
        assertEquals(7L, older.items.single().sequence)
        assertNull(older.nextCursor)
        assertEquals(cursor(8), take().requestUrl!!.queryParameter("before"))
    }

    @Test fun `lost acknowledgement is not replayed automatically and explicit retry retains its idempotency key`() = runBlocking {
        login()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        val input = ChatMessageInput(REQUEST, "Привет")
        assertTrue(failure { repository.sendMeetingMessage(CONFERENCE, input) }.kind in
            setOf(FailureKind.NETWORK, FailureKind.TIMEOUT, FailureKind.UNAVAILABLE))
        val first = take()
        assertEquals(5, server.requestCount)
        server.enqueue(json(item(message())))
        val confirmed = repository.sendMeetingMessage(CONFERENCE, input)
        val repeated = take()
        assertEquals("POST", repeated.method)
        assertEquals("/api/v1/conferences/$CONFERENCE/messages", repeated.path)
        assertEquals(first.body.readUtf8(), repeated.body.readUtf8())
        assertEquals(REQUEST, confirmed.clientRequestId)

        // A subsequent read uses the server's persisted record; there is no optimistic data cache.
        server.enqueue(json(page(listOf(message()))))
        assertEquals(confirmed, repository.meetingMessages(CONFERENCE).items.single())
        assertEquals("GET", take().method)
    }

    @Test fun `send preserves reply and attachment identifiers in the server payload`() = runBlocking {
        login()
        server.enqueue(json(item(message(replyTo = MESSAGE_2, attachments = "[${attachment()}]"))))
        val confirmed = repository.sendMeetingMessage(CONFERENCE, ChatMessageInput(REQUEST, "Привет", MESSAGE_2, listOf(FILE)))
        assertEquals(CONFERENCE, confirmed.attachments.single().conferenceId)
        assertEquals(MESSAGE, confirmed.attachments.single().messageId)
        assertEquals(MESSAGE_2, confirmed.replyPreview!!.id)
        val body = wireJson.parseToJsonElement(take().body.readUtf8()).jsonObject
        assertEquals(wireJson.parseToJsonElement("""{"clientRequestId":"$REQUEST","text":"Привет","replyTo":"$MESSAGE_2","attachmentIds":["$FILE"]}"""), body)
    }

    @Test fun `read uses conference GET and PUT routes and confirmed server state`() = runBlocking {
        login()
        server.enqueue(json("""{"item":{"lastReadMessageId":null,"unreadCount":2}}"""))
        assertEquals(2L, repository.meetingChatReadState(CONFERENCE).unreadCount)
        val get = take()
        assertEquals("GET", get.method)
        assertEquals("/api/v1/conferences/$CONFERENCE/chat/read", get.path)
        server.enqueue(json("""{"item":{"lastReadMessageId":"$MESSAGE","unreadCount":0}}"""))
        val marked = repository.markMeetingChatRead(CONFERENCE, MESSAGE)
        assertEquals(MESSAGE, marked.lastReadMessageId)
        assertEquals(0L, marked.unreadCount)
        val put = take()
        assertEquals("PUT", put.method)
        assertEquals(get.path, put.path)
        assertEquals("""{"messageId":"$MESSAGE"}""", put.body.readUtf8())
        assertEquals("Bearer access", put.getHeader("Authorization"))
        assertNull(put.getHeader("Cookie"))
    }

    @Test fun `send response cannot replace the meeting account or idempotency key`() = runBlocking {
        login()
        for (bad in listOf(message(conferenceId = OTHER), message(senderId = OTHER),
            message().replace(REQUEST, OTHER), message().replace("\"conferenceId\":", "\"conversationId\":"))) {
            server.enqueue(json(item(bad)))
            assertEquals(FailureKind.INVALID_RESPONSE, failure { repository.sendMeetingMessage(CONFERENCE, ChatMessageInput(REQUEST, "Привет")) }.kind)
            take()
        }
    }

    @Test fun `history rejects mixed scopes duplicate messages and backwards or repeated page boundaries`() = runBlocking {
        login()
        val invalid = listOf(
            page(listOf(message(conferenceId = OTHER))),
            page(listOf(message(), message())),
            page(listOf(message(sequence = 9), message(id = MESSAGE_2, sequence = 8))),
            page(listOf(message()), cursor(7)),
            page(listOf(message()), cursor(9, OTHER)),
            page(emptyList(), cursor(9)),
        )
        for (body in invalid) {
            server.enqueue(json(body))
            assertEquals(FailureKind.INVALID_RESPONSE, failure { repository.meetingMessages(CONFERENCE) }.kind)
            take()
        }
        server.enqueue(json(page(listOf(message(sequence = 9)), cursor(9))))
        assertEquals(FailureKind.INVALID_RESPONSE, failure { repository.meetingMessages(CONFERENCE, cursor(9)) }.kind)
    }

    @Test fun `deleted text attachments and deleted reply previews never leak from history`() = runBlocking {
        login()
        val deleted = message(attachments = "[${attachment()}]").replace("\"deletedAt\":null", "\"deletedAt\":\"$AT\"")
        server.enqueue(json(page(listOf(deleted))))
        val tombstone = repository.meetingMessages(CONFERENCE).items.single()
        assertEquals("", tombstone.text)
        assertTrue(tombstone.attachments.isEmpty())
        assertNotNull(tombstone.deletedAt)
        take()
        val reply = message(replyTo = MESSAGE_2).replace("\"deleted\":false", "\"deleted\":true")
        server.enqueue(json(page(listOf(reply))))
        assertEquals("", repository.meetingMessages(CONFERENCE).items.single().replyPreview!!.text)
    }

    @Test fun `attachment or reply from another message cannot enter the meeting history`() = runBlocking {
        login()
        for (bad in listOf(
            message(attachments = "[${attachment().replace(CONFERENCE, OTHER)}]"),
            message(attachments = "[${attachment().replace(MESSAGE, MESSAGE_2)}]"),
            message(replyTo = MESSAGE_2).replace("\"id\":\"$MESSAGE_2\"", "\"id\":\"$OTHER\""),
        )) {
            server.enqueue(json(page(listOf(bad))))
            assertEquals(FailureKind.INVALID_RESPONSE, failure { repository.meetingMessages(CONFERENCE) }.kind)
            take()
        }
    }

    @Test fun `bounded pages preserve full sequence precision and maximum Unicode text`() = runBlocking {
        login()
        val items = (1..100).map { number -> message(
            id = "77777777-7777-4777-8777-${number.toString().padStart(12, '0')}",
            sequence = 9_007_199_254_740_992L + number,
            text = "😀".repeat(4000),
        ) }
        server.enqueue(json(page(items)))
        val result = repository.meetingMessages(CONFERENCE, limit = 100)
        assertEquals(100, result.items.size)
        assertEquals(9_007_199_254_740_993L, result.items.first().sequence)
        assertEquals(4000, result.items.first().text.codePointCount(0, result.items.first().text.length))
        take()
        server.enqueue(json("""{"items":null,"unreadCount":0,"lastReadMessageId":null}"""))
        assertTrue(repository.meetingMessages(CONFERENCE).items.isEmpty())
    }

    @Test fun `invalid inputs fail before network and cannot inject paths or foreign page cursors`() = runBlocking {
        login()
        val count = server.requestCount
        val invalidCalls: List<suspend () -> Any?> = listOf(
            { repository.meetingMessages("../auth/me") },
            { repository.meetingMessages(CONFERENCE, limit = 101) },
            { repository.meetingMessages(CONFERENCE, limit = 0) },
            { repository.meetingMessages(CONFERENCE, "invalid+/=") },
            { repository.meetingMessages(CONFERENCE, cursor(9, OTHER)) },
            { repository.sendMeetingMessage(CONFERENCE, ChatMessageInput(REQUEST, "😀".repeat(4001))) },
            { repository.sendMeetingMessage(CONFERENCE, ChatMessageInput(REQUEST, "  ")) },
            { repository.sendMeetingMessage(CONFERENCE, ChatMessageInput(REQUEST, "", attachmentIds = listOf(FILE, FILE))) },
            { repository.sendMeetingMessage(CONFERENCE, ChatMessageInput(REQUEST, "text", replyTo = "../../auth")) },
            { repository.markMeetingChatRead(CONFERENCE, "../../auth") },
        )
        invalidCalls.forEach { assertEquals(FailureKind.VALIDATION, failure(it).kind) }
        assertEquals(count, server.requestCount)
    }

    @Test fun `membership denial and closed meeting are failures rather than successful empty history or local sends`() = runBlocking {
        login()
        server.enqueue(json("{}", 403))
        assertEquals(FailureKind.FORBIDDEN, failure { repository.meetingMessages(CONFERENCE) }.kind)
        take()
        server.enqueue(json("{}", 409))
        assertEquals(FailureKind.CONFLICT, failure { repository.sendMeetingMessage(CONFERENCE, ChatMessageInput(REQUEST, "Привет")) }.kind)
        take()
        server.enqueue(json("{}", 404))
        assertEquals(FailureKind.NOT_FOUND, failure { repository.meetingMessages(CONFERENCE) }.kind)
        assertNotNull(repository.state.value.session)
    }

    @Test fun `GET refresh retries once but sending requires explicit retry with the original key`() = runBlocking {
        login()
        server.enqueue(json("{}", 401)); enqueueLogin(token = "fresh"); server.enqueue(json(page(listOf(message()))))
        assertEquals(MESSAGE, repository.meetingMessages(CONFERENCE).items.single().id)
        assertEquals("Bearer access", take().getHeader("Authorization"))
        val refresh = take()
        assertEquals("/api/v1/auth/refresh", refresh.path)
        assertNull(refresh.getHeader("Authorization"))
        assertEquals("Bearer fresh", take().getHeader("Authorization"))

        server.enqueue(json("{}", 401)); enqueueLogin(token = "newer")
        val input = ChatMessageInput(REQUEST, "Привет")
        assertEquals(FailureKind.CONFLICT, failure { repository.sendMeetingMessage(CONFERENCE, input) }.kind)
        val rejected = take()
        assertEquals("/api/v1/auth/refresh", take().path)
        assertEquals(9, server.requestCount)
        server.enqueue(json(item(message())))
        repository.sendMeetingMessage(CONFERENCE, input)
        val retried = take()
        assertEquals(rejected.body.readUtf8(), retried.body.readUtf8())
        assertEquals("Bearer newer", retried.getHeader("Authorization"))
    }

    @Test fun `refresh cannot change accounts and subsequent requests stay unauthenticated`() = runBlocking {
        login()
        server.enqueue(json("{}", 401)); enqueueLogin(userId = OTHER, token = "foreign")
        assertEquals(FailureKind.INVALID_RESPONSE, failure { repository.meetingMessages(CONFERENCE) }.kind)
        assertNull(repository.state.value.session)
        assertEquals(6, server.requestCount)
        assertEquals(FailureKind.SESSION_EXPIRED, failure { repository.meetingMessages(CONFERENCE) }.kind)
        assertEquals(6, server.requestCount)
    }

    @Test fun `logout clears access and another account uses only its new bearer`() = runBlocking {
        login()
        server.enqueue(json("{}")); repository.logout(); take()
        val count = server.requestCount
        assertEquals(FailureKind.SESSION_EXPIRED, failure { repository.sendMeetingMessage(CONFERENCE, ChatMessageInput(REQUEST, "Привет")) }.kind)
        assertEquals(count, server.requestCount)
        enqueueSignedIn(userId = OTHER, token = "other-access")
        repository.login("test@example.com", "synthetic"); repeat(3) { take() }
        server.enqueue(json(item(message(senderId = OTHER))))
        assertEquals(OTHER, repository.sendMeetingMessage(CONFERENCE, ChatMessageInput(REQUEST, "Привет")).senderId)
        val request = take()
        assertEquals("Bearer other-access", request.getHeader("Authorization"))
        assertNull(request.getHeader("Cookie"))
    }

    @Test fun `switching origin and cross origin redirects cannot disclose the bearer`() = runBlocking {
        login()
        val other = tlsServer()
        try {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/steal")))
            assertEquals(FailureKind.UNAVAILABLE, failure { repository.meetingMessages(CONFERENCE) }.kind)
            take()
            assertEquals(0, other.requestCount)
            other.enqueue(json(VERSION))
            repository.addServer(other.url("/").toString())
            val probe = other.takeRequest(3, TimeUnit.SECONDS)!!
            assertNull(probe.getHeader("Authorization")); assertNull(probe.getHeader("Cookie"))
            assertEquals(FailureKind.SESSION_EXPIRED, failure { repository.meetingMessages(CONFERENCE) }.kind)
            assertEquals(1, other.requestCount)
        } finally { other.shutdown() }
    }

    @Test fun `meeting file upload prepares scoped metadata verifies bytes and finalizes once`() = runBlocking {
        login()
        server.enqueue(uploadInit())
        server.enqueue(json(item(uploadedAttachment("pending", HASH))))
        server.enqueue(json(item(uploadedAttachment("ready", HASH))))
        val result = repository.uploadMeetingAttachment(CONFERENCE, "a.txt", "text/plain", "hello".toByteArray(), REQUEST)
        assertEquals("ready", result.status); assertEquals(HASH, result.checksum)
        assertEquals(CONFERENCE, result.conferenceId); assertNull(result.messageId)
        val init = take()
        assertEquals("/api/v1/conferences/$CONFERENCE/attachments/init", init.path)
        assertEquals(wireJson.parseToJsonElement("""{"clientRequestId":"$REQUEST","filename":"a.txt","mimeType":"text/plain","size":5}"""), wireJson.parseToJsonElement(init.body.readUtf8()))
        val upload = take()
        assertEquals("PUT", upload.method); assertEquals("Bearer access", upload.getHeader("Authorization"))
        assertNull(upload.getHeader("Cookie")); assertArrayEquals("hello".toByteArray(), upload.body.readByteArray())
        assertEquals("/api/v1/conferences/$CONFERENCE/attachments/$FILE/content", upload.path)
        assertEquals("/api/v1/conferences/$CONFERENCE/attachments/$FILE/finalize", take().path)
    }

    @Test fun `lost finalize acknowledgement retries same attachment without another upload`() = runBlocking {
        login()
        server.enqueue(uploadInit()); server.enqueue(json(item(uploadedAttachment("pending", HASH))))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertEquals(FailureKind.NETWORK, failure {
            repository.uploadMeetingAttachment(CONFERENCE, "a.txt", "text/plain", "hello".toByteArray(), REQUEST)
        }.kind)
        val first = take(); take(); take()
        server.enqueue(uploadInit("ready", HASH))
        assertEquals(FILE, repository.uploadMeetingAttachment(CONFERENCE, "a.txt", "text/plain", "hello".toByteArray(), REQUEST).id)
        assertEquals(first.body.readUtf8(), take().body.readUtf8())
        assertEquals(8, server.requestCount)
    }

    @Test fun `file transfer refuses substituted account scope destination and checksum`() = runBlocking {
        login()
        val other = tlsServer()
        try {
            for (bad in listOf(
                uploadedAttachment("pending").replace(USER, OTHER),
                uploadedAttachment("pending").replace(CONFERENCE, OTHER),
                uploadedAttachment("pending").replace(REQUEST, OTHER),
                uploadedAttachment("ready", "b".repeat(64)),
            )) {
                server.enqueue(json("""{"item":$bad,"uploadUrl":"/api/v1/conferences/$CONFERENCE/attachments/$FILE/content"}"""))
                assertEquals(FailureKind.INVALID_RESPONSE, failure {
                    repository.uploadMeetingAttachment(CONFERENCE, "a.txt", "text/plain", "hello".toByteArray(), REQUEST)
                }.kind)
                take()
            }
            server.enqueue(json("""{"item":${uploadedAttachment("pending")},"uploadUrl":"${other.url("/steal")}"}"""))
            assertEquals(FailureKind.INVALID_RESPONSE, failure {
                repository.uploadMeetingAttachment(CONFERENCE, "a.txt", "text/plain", "hello".toByteArray(), REQUEST)
            }.kind)
            take(); assertEquals(0, other.requestCount)
            server.enqueue(uploadInit()); server.enqueue(json(item(uploadedAttachment("pending", HASH).replace(FILE, OTHER))))
            assertEquals(FailureKind.INVALID_RESPONSE, failure {
                repository.uploadMeetingAttachment(CONFERENCE, "a.txt", "text/plain", "hello".toByteArray(), REQUEST)
            }.kind)
            take(); take()
            assertEquals(11, server.requestCount)
        } finally { other.shutdown() }
    }

    @Test fun `attachment restrictions reject unsupported content metadata before network`() = runBlocking {
        login(); val before = server.requestCount
        for ((name, mime, bytes) in listOf(
            Triple("a.exe", "application/octet-stream", byteArrayOf(1)),
            Triple("../a.txt", "text/plain", byteArrayOf(1)),
            Triple("a.txt", "image/png", byteArrayOf(1)),
            Triple("a.txt", "text/plain", byteArrayOf()),
            Triple("a.txt", "text/plain", ByteArray(10 * 1024 * 1024 + 1)),
        )) assertEquals(FailureKind.VALIDATION, failure {
            repository.uploadMeetingAttachment(CONFERENCE, name, mime, bytes, REQUEST)
        }.kind)
        assertEquals(before, server.requestCount)
    }

    @Test fun `cancelling an upload cancels the socket and never starts finalize`() = runBlocking {
        login(); server.enqueue(uploadInit()); server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val upload = async(Dispatchers.Default) {
            repository.uploadMeetingAttachment(CONFERENCE, "a.txt", "text/plain", "hello".toByteArray(), REQUEST)
        }
        take(); assertEquals("PUT", take().method)
        upload.cancelAndJoin()
        assertTrue(upload.isCancelled); assertEquals(6, server.requestCount)
    }

    @Test fun `signed meeting file download never sends bearer or cookie to storage`() = runBlocking {
        login(); val storage = tlsServer()
        try {
            server.enqueue(fileDownload(storage.url("/signed?fixture=only").toString()))
            storage.enqueue(MockResponse().setBody("hello"))
            assertArrayEquals("hello".toByteArray(), repository.downloadMeetingAttachment(CONFERENCE, fileModel()))
            val authorization = take()
            assertEquals("Bearer access", authorization.getHeader("Authorization"))
            assertEquals("/api/v1/conferences/$CONFERENCE/attachments/$FILE/download", authorization.path)
            val transfer = storage.takeRequest(3, TimeUnit.SECONDS)!!
            assertNull(transfer.getHeader("Authorization")); assertNull(transfer.getHeader("Cookie"))
        } finally { storage.shutdown() }
    }

    @Test fun `meeting download rejects foreign scope expired or authenticated URL and corrupt bytes`() = runBlocking {
        login(); val before = server.requestCount
        assertEquals(FailureKind.VALIDATION, failure { repository.downloadMeetingAttachment(CONFERENCE, fileModel().copy(conferenceId = OTHER)) }.kind)
        assertEquals(before, server.requestCount)
        for (response in listOf(
            fileDownload("http://localhost/unsafe"),
            fileDownload(server.url("/signed").toString(), Instant.now().minusSeconds(1)),
            fileDownload("/api/v1/conferences/$CONFERENCE/attachments/$FILE/content", authenticated = true),
        )) {
            server.enqueue(response)
            assertEquals(FailureKind.INVALID_RESPONSE, failure { repository.downloadMeetingAttachment(CONFERENCE, fileModel()) }.kind)
            take()
        }
        server.enqueue(fileDownload(server.url("/signed").toString())); server.enqueue(MockResponse().setBody("other"))
        assertEquals(FailureKind.INVALID_RESPONSE, failure { repository.downloadMeetingAttachment(CONFERENCE, fileModel()) }.kind)
        take(); take()
        server.enqueue(fileDownload(server.url("/signed").toString())); server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(FailureKind.FORBIDDEN, failure { repository.downloadMeetingAttachment(CONFERENCE, fileModel()) }.kind)
        take(); take(); assertNotNull(repository.state.value.session)
        assertEquals(before + 7, server.requestCount)
    }

    private fun uploadedAttachment(status: String, checksum: String = "") =
        """{"id":"$FILE","conferenceId":"$CONFERENCE","ownerId":"$USER","clientRequestId":"$REQUEST","filename":"a.txt","mimeType":"text/plain","size":5,"status":"$status","checksum":"$checksum"}"""
    private fun uploadInit(status: String = "pending", checksum: String = "") = json(
        """{"item":${uploadedAttachment(status, checksum)},"uploadUrl":"/api/v1/conferences/$CONFERENCE/attachments/$FILE/content"}""")
    private fun fileModel() = MeetingChatAttachment(FILE, CONFERENCE, "a.txt", "text/plain", 5, "attached", HASH, MESSAGE)
    private fun fileDownload(url: String, expiry: Instant = Instant.now().plusSeconds(120), authenticated: Boolean = false) =
        json("""{"status":"success","url":"$url","expiresAt":"$expiry","authenticated":$authenticated}""")

    private fun tlsServer() = MockWebServer().apply { useHttps(certificates.sslSocketFactory(), false); start() }
    private suspend fun login() {
        server.enqueue(json(VERSION)); repository.addServer(origin)
        enqueueSignedIn()
        repository.login("test@example.com", "synthetic")
        repeat(4) { take() }
    }
    private fun enqueueSignedIn(userId: String = USER, token: String = "access") {
        enqueueLogin(userId, token)
        server.enqueue(json("""{"status":"success","user":{"id":"$userId","email":"test@example.com","displayName":"Test","isAdmin":false}}"""))
        server.enqueue(json("""{"status":"success","buildVersion":"dev","capabilities":{"liveCaptions":false,"transcription":false,"aiSummary":false,"semanticSearch":false,"meetingAnalytics":false,"recordingModes":["composite"]}}"""))
    }
    private fun enqueueLogin(userId: String = USER, token: String = "access") {
        server.enqueue(json("""{"status":"success","accessToken":"$token","tokenType":"Bearer","expiresIn":3600,"user":{"id":"$userId","email":"test@example.com","displayName":"Test"}}""")
            .addHeader("Set-Cookie", "$SESSION_COOKIE=cookie; Path=$AUTH_PATH; Secure; HttpOnly; Max-Age=3600"))
    }
    private suspend fun failure(call: suspend () -> Any?): AppFailure = try {
        call(); error("Expected AppFailure")
    } catch (failure: AppFailure) { failure }
    private fun take(): RecordedRequest = server.takeRequest(3, TimeUnit.SECONDS) ?: error("Missing request")
    private fun json(body: String, status: Int = 200) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)
    private fun item(body: String) = """{"status":"success","item":$body}"""
    private fun page(items: List<String>, next: String? = null) = """{"status":"success","items":[${items.joinToString(",")}],"nextCursor":${next?.let { "\"$it\"" } ?: "null"},"unreadCount":2,"lastReadMessageId":"$MESSAGE"}"""
    private fun cursor(sequence: Long, conferenceId: String = CONFERENCE) = Base64.getUrlEncoder().withoutPadding()
        .encodeToString("$conferenceId:$sequence".toByteArray())
    private fun message(id: String = MESSAGE, conferenceId: String = CONFERENCE, senderId: String = USER,
        sequence: Long = 9, text: String = "Привет", replyTo: String? = null, attachments: String = "null"): String {
        val reply = replyTo?.let { """{"id":"$it","text":"Исходное сообщение","senderName":"Test","deleted":false}""" } ?: "null"
        return """{"id":"$id","conferenceId":"$conferenceId","senderId":"$senderId","senderName":"Test","clientRequestId":"$REQUEST","text":"$text","sequence":"$sequence","version":1,"createdAt":"$AT","updatedAt":"$AT","deletedAt":null,"replyTo":${replyTo?.let { "\"$it\"" } ?: "null"},"replyPreview":$reply,"attachments":$attachments}"""
    }
    private fun attachment() = """{"id":"$FILE","conferenceId":"$CONFERENCE","messageId":"$MESSAGE","filename":"a.txt","mimeType":"text/plain","size":5,"status":"attached","checksum":"${"a".repeat(64)}"}"""

    companion object {
        private const val CONFERENCE = "11111111-1111-4111-8111-111111111111"
        private const val USER = "22222222-2222-4222-8222-222222222222"
        private const val OTHER = "33333333-3333-4333-8333-333333333333"
        private const val MESSAGE = "44444444-4444-8444-8444-444444444444"
        private const val MESSAGE_2 = "44444444-4444-8444-8444-444444444445"
        private const val REQUEST = "55555555-5555-4555-8555-555555555555"
        private const val FILE = "66666666-6666-4666-8666-666666666666"
        private const val HASH = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
        private const val AT = "2026-10-11T10:00:00Z"
        private const val VERSION = """{"version":"dev","commit":"unknown","buildTime":"unknown"}"""
    }
}

private class MeetingChatProfiles : ProfileStore {
    private var value = PersistedSettings()
    override suspend fun read() = value
    override suspend fun write(settings: PersistedSettings) { value = settings }
}
private class MeetingChatCredentials : CredentialStore {
    private val values = mutableMapOf<SessionScope, StoredCredential>()
    override suspend fun read(scope: SessionScope) = values[scope]
    override suspend fun write(scope: SessionScope, credential: StoredCredential) { values[scope] = credential }
    override suspend fun delete(scope: SessionScope) { values.remove(scope) }
}
