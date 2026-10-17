package com.meetspace.core.data

import com.meetspace.core.model.*
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RecordingsRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: DefaultFoundationRepository
    private lateinit var origin: String
    private lateinit var certificates: HandshakeCertificates

    @Before fun setup() {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        certificates = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        server = tlsServer(); origin = server.url("/").toString().removeSuffix("/")
        val client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
            .readTimeout(3, TimeUnit.SECONDS).callTimeout(5, TimeUnit.SECONDS).build()
        repository = DefaultFoundationRepository(RecordingProfiles(), RecordingCredentials(), MeetSpaceApi(client))
    }
    @After fun stop() { server.shutdown() }

    @Test fun `delete uses scoped endpoint and conflict remains visible`() = runBlocking {
        login(); server.enqueue(json("""{"status":"success"}"""))
        repository.deleteRecording(CONFERENCE, RECORD)
        val request = take(); assertEquals("DELETE", request.method); assertEquals("/api/v1/conferences/$CONFERENCE/recordings/$RECORD", request.path)
        assertEquals("Bearer access", request.getHeader("Authorization")); assertNull(request.getHeader("Cookie"))
        server.enqueue(json("""{"error":"recording_active"}""").setResponseCode(409))
        try { repository.deleteRecording(CONFERENCE, RECORD); fail("Conflict expected") } catch (error: AppFailure) { assertEquals(FailureKind.CONFLICT, error.kind) }
    }

    @Test fun `list uses conference scoped offset and preserves next page before ready filtering`() = runBlocking {
        login(); server.enqueue(json("""{"status":"success","items":[${card(status = "processing") }]}"""))
        val page = repository.recordings(CONFERENCE, 20, 1)
        assertEquals(21, page.nextOffset); assertFalse(page.items.single().playable)
        val request = take()
        assertEquals("/api/v1/conferences/$CONFERENCE/recordings?offset=20&limit=1", request.path)
        assertEquals("Bearer access", request.getHeader("Authorization")); assertNull(request.getHeader("Cookie"))
    }
    @Test fun `recording metadata contains supported materials but never capability or diagnostic text`() = runBlocking {
        login(); server.enqueue(json(item(card(extra = ",${file().replace("final_mp4", "debug_log")}"))))
        val record = repository.recording(CONFERENCE, RECORD)
        assertTrue(record.playable); assertEquals(1, record.files.size)
        assertEquals(FILE, record.media!!.id)
        assertFalse(record.toString().contains("signature")); assertFalse(record.toString().contains("debug"))
    }
    @Test fun `malformed identity size MIME and duplicate files are rejected`() = runBlocking {
        login()
        val invalidCards = listOf(card().replace(CONFERENCE, OTHER), card().replace(RECORD, OTHER),
            card().replace("\"recordId\":12", "\"recordId\":13"), card().replace("\"sizeBytes\":5", "\"sizeBytes\":-1"),
            card().replace("video/mp4", "text/html"), card(extra = ",${file()}"))
        for (invalid in invalidCards) {
            server.enqueue(json(item(invalid))); assertFailure(FailureKind.INVALID_RESPONSE) { repository.recording(CONFERENCE, RECORD) }
        }
    }
    @Test fun `invalid paths or pagination never make a network request`() = runBlocking {
        login(); val before = server.requestCount
        assertFailure(FailureKind.VALIDATION) { repository.recordings("../auth", 0, 20) }
        assertFailure(FailureKind.VALIDATION) { repository.recordings(CONFERENCE, -1, 20) }
        assertFailure(FailureKind.VALIDATION) { repository.recordings(CONFERENCE, 0, 101) }
        assertFailure(FailureKind.VALIDATION) { repository.recordingAccess(CONFERENCE, RECORD, "../file") }
        assertEquals(before, server.requestCount)
    }
    @Test fun `insecure credentialed ambiguous and fragment URLs are rejected before transfer`() = runBlocking {
        login()
        for (url in listOf("http://localhost/file", "https://name:password@localhost/file", "//localhost/file", "https://localhost/file#fragment", "https://localhost/a b")) {
            server.enqueue(json(item(card(url = url))))
            assertFailure(FailureKind.INVALID_RESPONSE) { repository.recordingAccess(CONFERENCE, RECORD, FILE) }
        }
    }
    @Test fun `acquiring nonready or unavailable file never accesses object storage`() = runBlocking {
        login(); server.enqueue(json(item(card(status = "processing"))))
        assertFailure(FailureKind.NOT_FOUND) { repository.recordingAccess(CONFERENCE, RECORD, FILE) }
        server.enqueue(json(item(card(url = ""))))
        assertFailure(FailureKind.NOT_FOUND) { repository.recordingAccess(CONFERENCE, RECORD, FILE) }
        assertEquals(6, server.requestCount)
    }
    @Test fun `cross origin storage streams bytes and never receives account authorization cookies or auth retry`() = runBlocking {
        login(); val storage = tlsServer()
        try {
            server.enqueue(json(item(card(url = storage.url("/record.mp4?signature=private").toString()))))
            val access = repository.recordingAccess(CONFERENCE, RECORD, FILE)
            assertFalse(access.toString().contains("signature=")); assertFalse(access.toString().contains("https://")); take()
            storage.enqueue(media("hello")); val output = ByteArrayOutputStream()
            assertEquals(5L, access.copyTo(output)); assertEquals("hello", output.toString("UTF-8"))
            val request = storage.takeRequest(3, TimeUnit.SECONDS)!!
            assertNull(request.getHeader("Authorization")); assertNull(request.getHeader("Cookie"))
            assertEquals("identity", request.getHeader("Accept-Encoding"))
        } finally { storage.shutdown() }
    }
    @Test fun `byte range supports seek and validates complete resource size`() = runBlocking {
        login(); val access = access()
        server.enqueue(media("ell").setResponseCode(206).setHeader("Content-Range", "bytes 1-3/5"))
        access.open(1, 3).use { assertEquals(3L, it.length); assertEquals("ell", it.input.readBytes().toString(Charsets.UTF_8)) }
        val request = take(); assertEquals("bytes=1-3", request.getHeader("Range")); assertNull(request.getHeader("Authorization"))
        access.close()
    }
    @Test fun `range that reaches end clamps to actual file size`() = runBlocking {
        login(); val access = access()
        server.enqueue(media("lo").setResponseCode(206).setHeader("Content-Range", "bytes 3-4/5"))
        access.open(3, 10).use { assertEquals(2L, it.length); assertEquals("lo", it.input.readBytes().toString(Charsets.UTF_8)) }
        assertEquals("bytes=3-4", take().getHeader("Range")); access.close()
    }
    @Test fun `ignoring range or substituting byte interval is rejected`() = runBlocking {
        login(); val access = access()
        for (response in listOf(media("hello"), media("ell").setResponseCode(206).setHeader("Content-Range", "bytes 0-2/5"),
            media("ell").setResponseCode(206).setHeader("Content-Range", "bytes 1-3/6"),
            media("el").setResponseCode(206).setHeader("Content-Range", "bytes 1-2/5"))) {
            server.enqueue(response); assertReadFailure(FailureKind.INVALID_RESPONSE) { access.open(1, 3) }
        }
        access.close()
    }
    @Test fun `redirect is never followed and does not leak capability to target`() = runBlocking {
        login(); val target = tlsServer()
        try {
            val access = access(); server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", target.url("/steal")))
            assertReadFailure(FailureKind.INVALID_RESPONSE) { access.open() }; assertEquals(0, target.requestCount); access.close()
        } finally { target.shutdown() }
    }
    @Test fun `object 401 never clears or refreshes the account session`() = runBlocking {
        login(); val access = access(); server.enqueue(MockResponse().setResponseCode(401))
        assertReadFailure(FailureKind.FORBIDDEN) { access.open() }
        assertNotNull(repository.state.value.session); assertEquals(6, server.requestCount); access.close()
    }
    @Test fun `complete export rejects wrong content length and checksum`() = runBlocking {
        login()
        var access = access(); server.enqueue(media("longer"))
        assertReadFailure(FailureKind.INVALID_RESPONSE) { access.copyTo(ByteArrayOutputStream()) }
        access = access(); server.enqueue(media("wrong"))
        assertReadFailure(FailureKind.INVALID_RESPONSE) { access.copyTo(ByteArrayOutputStream()) }
    }
    @Test fun `export is bounded to declared size even for chunked data`() = runBlocking {
        login(); val access = access()
        server.enqueue(MockResponse().setHeader("Content-Type", "video/mp4").setChunkedBody("hellomore", 2))
        assertReadFailure(FailureKind.INVALID_RESPONSE) { access.copyTo(ByteArrayOutputStream()) }
    }
    @Test fun `truncated body is a failed export`() = runBlocking {
        login(); val access = access()
        server.enqueue(media("hel").setHeader("Content-Length", 5).setSocketPolicy(SocketPolicy.DISCONNECT_AT_END))
        try { access.copyTo(ByteArrayOutputStream()); fail("Truncated recording accepted") } catch (_: RecordingReadException) { }
    }
    @Test fun `logout closes obtained unopened and already open leases`() = runBlocking {
        login(); val waiting = access(); val running = access()
        server.enqueue(media("hello")); val stream = running.open()
        take(); server.enqueue(json("""{"status":"success"}""")); repository.logout(); take()
        assertReadFailure(FailureKind.SESSION_EXPIRED) { waiting.open() }
        assertReadFailure(FailureKind.SESSION_EXPIRED) { stream.input.read() }
        stream.close()
    }
    @Test fun `cancel download immediately closes lease and retains no completed result`() = runBlocking {
        login(); val access = access()
        server.enqueue(media("hello").setBodyDelay(2, TimeUnit.SECONDS))
        val job = launch(Dispatchers.IO) { access.copyTo(ByteArrayOutputStream()) }
        withContext(Dispatchers.IO) { take() }
        job.cancelAndJoin()
        assertReadFailure(FailureKind.SESSION_EXPIRED) { access.open() }
    }
    @Test fun `disabled transcript retains existing ready text metadata and summary`() = runBlocking {
        login(); server.enqueue(json("""{"status":"success","enabled":false,"item":{"id":"$TRANSCRIPT","conferenceId":"$CONFERENCE","recordingId":"$RECORD","language":"ru","status":"ready"}}"""))
        val transcript = repository.recordingTranscript(CONFERENCE, RECORD)
        assertFalse(transcript.enabled); assertEquals("ready", transcript.item!!.status)
        server.enqueue(json("""{"status":"success","enabled":false,"item":{"id":"$OTHER","conferenceId":"$CONFERENCE","transcriptId":"$TRANSCRIPT","status":"ready","summary":"Итог\nвстречи","keyPoints":["Пункт"],"actionItems":[{"text":"Сделать","assignee":null,"dueDate":null}],"topics":["Тема"]}}"""))
        val summary = repository.recordingSummary(CONFERENCE, RECORD)
        assertFalse(summary.enabled); assertEquals("Итог\nвстречи", summary.item!!.text); assertNull(summary.item!!.actionItems.single().assignee)
    }
    @Test fun `transcript page validates order identities and pagination`() = runBlocking {
        login(); val segment = """{"id":"$OTHER","transcriptId":"$TRANSCRIPT","startMs":1200,"endMs":2400,"speakerLabel":"Голос 1","text":"Обсуждение"}"""
        server.enqueue(json("""{"status":"success","items":[$segment],"total":3,"offset":1,"limit":1}"""))
        val page = repository.recordingSegments(CONFERENCE, RECORD, 1, 1)
        assertEquals(2, page.nextOffset); assertEquals(1200L, page.items.single().startMs)
        server.enqueue(json("""{"status":"success","items":[$segment],"total":3,"offset":0,"limit":1}"""))
        assertFailure(FailureKind.INVALID_RESPONSE) { repository.recordingSegments(CONFERENCE, RECORD, 1, 1) }
    }
    @Test fun `content absent data and mismatching conference fail safely`() = runBlocking {
        login(); server.enqueue(json("""{"status":"success","enabled":false,"item":null}"""))
        assertNull(repository.recordingTranscript(CONFERENCE, RECORD).item)
        server.enqueue(json("""{"status":"success","enabled":true,"item":{"id":"$TRANSCRIPT","conferenceId":"$OTHER","recordingId":"$RECORD","language":"ru","status":"ready"}}"""))
        assertFailure(FailureKind.INVALID_RESPONSE) { repository.recordingTranscript(CONFERENCE, RECORD) }
    }

    @Test fun `start and stop use meeting routes and preserve transitional state and requester`() = runBlocking {
        login(); server.enqueue(json(item(controlCard("starting"))).setResponseCode(202))
        val starting = repository.startRecording(CONFERENCE)
        assertEquals("starting", starting.status); assertEquals(USER, starting.requestedBy)
        val start = take()
        assertEquals("POST", start.method); assertEquals("/api/v1/conferences/$CONFERENCE/recordings", start.path)
        assertEquals("""{"mode":"composite"}""", start.body.readUtf8())
        assertEquals("Bearer access", start.getHeader("Authorization")); assertNull(start.getHeader("Cookie"))
        server.enqueue(json(item(controlCard("stopping"))).setResponseCode(202))
        assertEquals("stopping", repository.stopRecording(CONFERENCE, RECORD).status)
        val stop = take()
        assertEquals("POST", stop.method); assertEquals("/api/v1/conferences/$CONFERENCE/recordings/$RECORD/stop", stop.path)
        assertEquals("{}", stop.body.readUtf8()); assertNull(stop.getHeader("Cookie"))
    }

    @Test fun `recording control refuses substituted conference requester mode or recording ID`() = runBlocking {
        login()
        for (bad in listOf(controlCard("starting").replace(CONFERENCE, OTHER),
            controlCard("starting").replace(USER, OTHER), controlCard("starting").replace("composite", "audio_only"))) {
            server.enqueue(json(item(bad)))
            assertFailure(FailureKind.INVALID_RESPONSE) { repository.startRecording(CONFERENCE) }; take()
        }
        server.enqueue(json(item(controlCard("stopping").replace(RECORD, OTHER))))
        assertFailure(FailureKind.INVALID_RESPONSE) { repository.stopRecording(CONFERENCE, RECORD) }; take()
        server.enqueue(json(item(controlCard("ready").replace(USER, "not-a-user-uuid"))))
        assertFailure(FailureKind.INVALID_RESPONSE) { repository.recording(CONFERENCE, RECORD) }
    }

    @Test fun `active recording conflict and stop permission denial remain explicit failures`() = runBlocking {
        login()
        server.enqueue(MockResponse().setResponseCode(409))
        assertFailure(FailureKind.CONFLICT) { repository.startRecording(CONFERENCE) }; take()
        server.enqueue(MockResponse().setResponseCode(403))
        assertFailure(FailureKind.FORBIDDEN) { repository.stopRecording(CONFERENCE, RECORD) }; take()
        assertNotNull(repository.state.value.session)
        assertEquals(6, server.requestCount)
    }

    @Test fun `lost recording start acknowledgement is not replayed and can be reconciled with list`() = runBlocking {
        login(); server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertFailure(FailureKind.NETWORK) { repository.startRecording(CONFERENCE) }
        assertEquals("POST", take().method); assertEquals(5, server.requestCount)
        server.enqueue(json("""{"status":"success","items":[${controlCard("recording") }]}"""))
        assertEquals(RECORD, repository.recordings(CONFERENCE).items.single().id)
        assertEquals("GET", take().method); assertEquals(6, server.requestCount)
    }

    @Test fun `recording start 401 refreshes identity but requires explicit action before another POST`() = runBlocking {
        login(); server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(json("""{"status":"success","accessToken":"fresh","tokenType":"Bearer","expiresIn":3600,"user":{"id":"$USER","email":"test@example.com","displayName":"Test"}}""")
            .addHeader("Set-Cookie", "$SESSION_COOKIE=rotated; Path=$AUTH_PATH; Secure; HttpOnly; Max-Age=3600"))
        assertFailure(FailureKind.CONFLICT) { repository.startRecording(CONFERENCE) }
        assertEquals("POST", take().method); assertEquals("/api/v1/auth/refresh", take().path)
        assertEquals(6, server.requestCount)
        server.enqueue(json(item(controlCard("starting"))))
        repository.startRecording(CONFERENCE)
        assertEquals("Bearer fresh", take().getHeader("Authorization"))
    }

    @Test fun `unsupported recording mode and injected identity never issue requests`() = runBlocking {
        login(); val before = server.requestCount
        assertFailure(FailureKind.VALIDATION) { repository.startRecording(CONFERENCE, "pause") }
        assertFailure(FailureKind.VALIDATION) { repository.startRecording("../other") }
        assertFailure(FailureKind.VALIDATION) { repository.stopRecording(CONFERENCE, "../other") }
        assertEquals(before, server.requestCount)
    }

    private fun controlCard(status: String) = """{"id":12,"uuid":"$RECORD","conferenceId":"$CONFERENCE","requestedBy":"$USER","mode":"composite","status":"$status","createdAt":"$AT","files":null}"""

    private fun tlsServer() = MockWebServer().apply { useHttps(certificates.sslSocketFactory(), false); start() }
    private suspend fun access(): RecordingAccess {
        server.enqueue(json(item(card()))); return repository.recordingAccess(CONFERENCE, RECORD, FILE).also { take() }
    }
    private suspend fun login() {
        server.enqueue(json("""{"version":"dev","commit":"unknown","buildTime":"unknown"}""")); repository.addServer(origin)
        server.enqueue(json("""{"status":"success","accessToken":"access","tokenType":"Bearer","expiresIn":3600,"user":{"id":"$USER","email":"test@example.com","displayName":"Test"}}""")
            .addHeader("Set-Cookie", "$SESSION_COOKIE=cookie; Path=$AUTH_PATH; Secure; HttpOnly; Max-Age=3600"))
        server.enqueue(json("""{"status":"success","user":{"id":"$USER","email":"test@example.com","displayName":"Test","isAdmin":false}}"""))
        server.enqueue(json("""{"status":"success","buildVersion":"dev","capabilities":{"liveCaptions":false,"transcription":false,"aiSummary":false,"semanticSearch":false,"meetingAnalytics":false,"recordingModes":["composite"]}}"""))
        repository.login("test@example.com", "synthetic"); repeat(4) { take() }
    }
    private suspend fun assertFailure(kind: FailureKind, block: suspend () -> Any?) {
        try { block(); fail("Expected AppFailure") } catch (failure: AppFailure) { assertEquals(kind, failure.kind) }
    }
    private suspend fun assertReadFailure(kind: FailureKind, block: suspend () -> Any?) {
        try { block(); fail("Expected RecordingReadException") } catch (failure: RecordingReadException) { assertEquals(kind, failure.kind); assertNull(failure.cause) }
    }
    private fun take(): RecordedRequest = server.takeRequest(3, TimeUnit.SECONDS) ?: error("Missing request")
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun media(body: String) = MockResponse().setHeader("Content-Type", "video/mp4").setBody(body)
    private fun item(value: String) = """{"status":"success","item":$value}"""
    private fun card(status: String = "ready", url: String = server.url("/signed?signature=private").toString(), extra: String = "") =
        """{"id":12,"uuid":"$RECORD","conferenceId":"$CONFERENCE","mode":"composite","status":"$status","createdAt":"$AT","durationSec":5,"files":[${file(url)}$extra]}"""
    private fun file(url: String = server.url("/signed?signature=private").toString()) =
        """{"id":25,"uuid":"$FILE","recordId":12,"fileType":"final_mp4","fileName":"final.mp4","mimeType":"video/mp4","sizeBytes":5,"checksumSha256":"${hash("hello")}","url":"$url"}"""
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    companion object {
        private const val CONFERENCE = "11111111-1111-4111-8111-111111111111"
        private const val USER = "22222222-2222-4222-8222-222222222222"
        private const val OTHER = "33333333-3333-4333-8333-333333333333"
        private const val RECORD = "44444444-4444-4444-8444-444444444444"
        private const val TRANSCRIPT = "55555555-5555-4555-8555-555555555555"
        private const val FILE = "66666666-6666-4666-8666-666666666666"
        private const val AT = "2026-10-11T10:00:00Z"
    }
}
private class RecordingProfiles : ProfileStore {
    private var value = PersistedSettings()
    override suspend fun read() = value
    override suspend fun write(settings: PersistedSettings) { value = settings }
}
private class RecordingCredentials : CredentialStore {
    private val values = mutableMapOf<SessionScope, StoredCredential>()
    override suspend fun read(scope: SessionScope) = values[scope]
    override suspend fun write(scope: SessionScope, credential: StoredCredential) { values[scope] = credential }
    override suspend fun delete(scope: SessionScope) { values.remove(scope) }
}
