package com.meetspace.core.data

import com.meetspace.core.model.AppFailure
import com.meetspace.core.model.FailureKind
import com.meetspace.core.model.MeetingInput
import com.meetspace.core.model.MeetingTransition
import com.meetspace.core.model.MeetingsQuery
import com.meetspace.core.model.MeetingsScope
import com.meetspace.core.model.MeetingsView
import com.meetspace.core.model.ThemeMode
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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

class MeetingsRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: DefaultFoundationRepository
    private lateinit var origin: String
    private lateinit var credentials: MeetingsCredentials
    private lateinit var profiles: MeetingsProfiles

    @Before fun setUp() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCert = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trusted = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer().apply { useHttps(serverCert.sslSocketFactory(), false); start() }
        origin = server.url("/").toString().removeSuffix("/")
        credentials = MeetingsCredentials(); profiles = MeetingsProfiles()
        val api = MeetSpaceApi(OkHttpClient.Builder().sslSocketFactory(trusted.sslSocketFactory(), trusted.trustManager)
            .callTimeout(5, TimeUnit.SECONDS).build())
        repository = DefaultFoundationRepository(profiles, credentials, api)
    }

    @After fun tearDown() { server.shutdown() }

    @Test fun `timeline sends exact scoped filters UTC range and encoded cursor without cookie`() = runBlocking {
        signIn()
        server.enqueue(json(page(meeting(), "opaque+cursor/==")))
        val from = Instant.parse("2026-10-01T00:00:00Z")
        val to = Instant.parse("2026-10-31T23:59:59Z")
        val result = repository.timeline(MeetingsQuery(MeetingsView.PAST, MeetingsScope.PARTICIPATING,
            from, to, "previous+cursor/==", 30))
        assertEquals("opaque+cursor/==", result.nextCursor)
        assertEquals(Instant.parse("2026-10-09T12:00:00Z"), result.items.single().createdAt)
        val request = take()
        assertEquals("/api/v1/me/conferences", request.requestUrl!!.encodedPath)
        assertEquals("past", request.requestUrl!!.queryParameter("view"))
        assertEquals("participating", request.requestUrl!!.queryParameter("scope"))
        assertEquals(from.toString(), request.requestUrl!!.queryParameter("from"))
        assertEquals(to.toString(), request.requestUrl!!.queryParameter("to"))
        assertEquals("previous+cursor/==", request.requestUrl!!.queryParameter("cursor"))
        assertEquals("30", request.requestUrl!!.queryParameter("limit"))
        assertEquals("Bearer access-one", request.getHeader("Authorization")); assertNull(request.getHeader("Cookie"))
    }

    @Test fun `Go null items is a real empty page and unknown status grants no actions`() = runBlocking {
        signIn()
        server.enqueue(json("""{"status":"success","items":null,"nextCursor":null}"""))
        assertTrue(repository.timeline().items.isEmpty()); take()
        server.enqueue(json(page(meeting(status = "future_status"))))
        val item = repository.timeline().items.single()
        assertEquals("future_status", item.status); assertFalse(item.isKnownStatus); assertFalse(item.isClosed)
    }

    @Test fun `maximum Unicode titles fit a bounded hundred-item calendar page`() = runBlocking {
        signIn()
        val items = (1..100).joinToString(",") { index ->
            meeting().replace(ID, "11111111-1111-4111-8111-${index.toString().padStart(12, '0')}")
                .replace("A title", "😀".repeat(200))
        }
        server.enqueue(json(page(items)))
        assertEquals(100, repository.timeline(MeetingsQuery(limit = 100)).items.size)
    }

    @Test fun `unknown admission states remain visible but never grant participation`() = runBlocking {
        signIn(); server.enqueue(json(participantBody(admission = "future_admission")))
        val member = repository.membership(ID)!!
        assertEquals("future_admission", member.admissionState)
        assertFalse(member.isAdmitted); assertFalse(member.canParticipate)
    }

    @Test fun `each GET operation refreshes once and persists cookie before retry`() = runBlocking {
        signIn()
        repeat(2) { index ->
            server.enqueue(json("{}", 401)); enqueueLogin("access-${index + 2}", "cookie-${index + 2}")
            server.enqueue(json(page(meeting())))
            assertEquals(ID, repository.timeline().items.single().id)
            val failed = take(); val refreshed = take(); val retried = take()
            assertEquals("GET", failed.method)
            assertEquals("/api/v1/auth/refresh", refreshed.path)
            assertNull(refreshed.getHeader("Authorization"))
            assertEquals("$SESSION_COOKIE=${if (index == 0) "cookie-one" else "cookie-2"}", refreshed.getHeader("Cookie"))
            assertEquals("Bearer access-${index + 2}", retried.getHeader("Authorization"))
            assertNull(retried.getHeader("Cookie"))
            assertEquals("cookie-${index + 2}", credentials.values.values.single().value)
        }
    }

    @Test fun `second unauthorized response deletes stored credential and clears account session`() = runBlocking {
        signIn(); server.enqueue(json("{}", 401)); enqueueLogin("expired-again", "rotated")
        server.enqueue(json("{}", 401))
        assertEquals(FailureKind.SESSION_EXPIRED, failure { repository.timeline() }.kind)
        assertNull(repository.state.value.session); assertTrue(credentials.values.isEmpty())
        val before = server.requestCount
        assertEquals(FailureKind.SESSION_EXPIRED, failure { repository.timeline() }.kind)
        assertEquals(before, server.requestCount)
    }

    @Test fun `expired session drops durable profile link even if credential deletion fails`() = runBlocking {
        signIn(); credentials.failDelete = true
        server.enqueue(json("{}", 401)); server.enqueue(json("{}", 401))
        assertEquals(FailureKind.STORAGE, failure { repository.timeline() }.kind)
        assertNull(repository.state.value.session)
        assertNull(profiles.value.profiles.single().accountId)
        assertTrue(credentials.values.isNotEmpty())
    }

    @Test fun `expired session erases credential even if profile persistence fails`() = runBlocking {
        signIn(); profiles.failWrite = true
        server.enqueue(json("{}", 401)); server.enqueue(json("{}", 401))
        assertEquals(FailureKind.STORAGE, failure { repository.timeline() }.kind)
        assertNull(repository.state.value.session)
        assertNull(repository.state.value.selectedProfile!!.accountId)
        assertTrue(credentials.values.isEmpty())
    }

    @Test fun `missing timeline route is an API gap rather than an empty successful list`() = runBlocking {
        signIn(); server.enqueue(json("{}", 404))
        assertEquals(FailureKind.INCOMPATIBLE_SERVER, failure { repository.timeline() }.kind)
        assertNotNull(repository.state.value.session)
    }

    @Test fun `refresh for another account clears the prior account instead of using its token`() = runBlocking {
        signIn(); server.enqueue(json("{}", 401)); enqueueLogin("other-access", "other-cookie", OTHER_USER)
        assertEquals(FailureKind.INVALID_RESPONSE, failure { repository.timeline() }.kind)
        assertNull(repository.state.value.session); assertTrue(credentials.values.isEmpty())
        assertEquals(6, server.requestCount)
    }

    @Test fun `unauthorized mutation refreshes session and requires explicit user retry`() = runBlocking {
        signIn(); server.enqueue(json("{}", 401)); enqueueLogin("refreshed", "fresh-cookie")
        assertEquals(FailureKind.CONFLICT, failure { repository.createMeeting(MeetingInput("A title")) }.kind)
        assertNotNull(repository.state.value.session)
        assertEquals("POST", take().method); assertEquals("/api/v1/auth/refresh", take().path)
        assertEquals(6, server.requestCount)
        server.enqueue(json("""{"status":"success","item":${meeting()}}"""))
        assertEquals(ID, repository.createMeeting(MeetingInput("A title")).id)
        assertEquals("Bearer refreshed", take().getHeader("Authorization"))
    }

    @Test fun `connection loss after submitted create has unknown outcome and is never replayed`() = runBlocking {
        signIn()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        val failure = failure { repository.createMeeting(MeetingInput("Submitted once")) }
        assertEquals(FailureKind.NETWORK, failure.kind)
        assertTrue(failure.message.contains("не подтверждён"))
        assertEquals(5, server.requestCount); assertEquals("POST", take().method)
        assertNotNull(repository.state.value.session)
    }

    @Test fun `forbidden conflict and missing resource errors expose no server diagnostic`() = runBlocking {
        signIn()
        listOf(403 to FailureKind.FORBIDDEN, 409 to FailureKind.CONFLICT, 404 to FailureKind.NOT_FOUND,
            400 to FailureKind.VALIDATION, 405 to FailureKind.INCOMPATIBLE_SERVER).forEach { (code, kind) ->
            server.enqueue(json("""{"message":"sensitive internal diagnostic"}""", code))
            val error = failure { repository.conference(ID) }
            assertEquals(kind, error.kind); assertFalse(error.message.contains("sensitive"))
            assertEquals("/api/v1/conferences/$ID", take().path)
        }
        assertEquals(9, server.requestCount); assertNotNull(repository.state.value.session)
    }

    @Test fun `only membership 404 is nullable and membership user cannot cross accounts`() = runBlocking {
        signIn(); server.enqueue(json("{}", 404))
        assertNull(repository.membership(ID)); assertEquals("/api/v1/conferences/$ID/participants/me", take().path)
        server.enqueue(json("{}", 403)); assertEquals(FailureKind.FORBIDDEN, failure { repository.membership(ID) }.kind); take()
        server.enqueue(json(participantBody(userId = OTHER_USER)))
        assertEquals(FailureKind.INVALID_RESPONSE, failure { repository.membership(ID) }.kind)
    }

    @Test fun `create and schedule serialize UTC instants and explicit duration constraints`() = runBlocking {
        signIn()
        val at = Instant.now().plusSeconds(3 * 86400).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
        val scheduled = meeting(status = "scheduled", scheduledAt = at.toString(), duration = 45)
        server.enqueue(json("""{"status":"success","item":$scheduled}"""))
        assertEquals(at, repository.createMeeting(MeetingInput("  Новая встреча  ", true, at, 45)).scheduledAt)
        val create = take(); val createBody = create.body.readUtf8()
        assertEquals("POST", create.method); assertEquals("/api/v1/conferences", create.path)
        assertTrue(createBody.contains("\"title\":\"Новая встреча\""))
        assertTrue(createBody.contains("\"waitingRoomEnabled\":true")); assertTrue(createBody.contains(at.toString()))
        server.enqueue(json("""{"status":"success","item":$scheduled}"""))
        repository.scheduleMeeting(ID, at, 45)
        val schedule = take(); assertEquals("PUT", schedule.method); assertEquals("/api/v1/conferences/$ID/schedule", schedule.path)
        assertTrue(schedule.body.readUtf8().contains("\"plannedDurationMin\":45"))
        val before = server.requestCount
        listOf(MeetingInput(" "), MeetingInput("A", plannedDurationMin = 20), MeetingInput("A", scheduledAt = Instant.EPOCH),
            MeetingInput("A", scheduledAt = at, plannedDurationMin = 1441)).forEach {
            assertEquals(FailureKind.VALIDATION, failure { repository.createMeeting(it) }.kind)
        }
        assertEquals(before, server.requestCount)
    }

    @Test fun `transition and membership use only audited methods and paths`() = runBlocking {
        signIn()
        MeetingTransition.entries.forEach { action ->
            server.enqueue(json("""{"status":"success","item":${meeting()}}"""))
            repository.transitionMeeting(ID, action)
            val request = take(); assertEquals("POST", request.method)
            assertEquals("/api/v1/conferences/$ID/${action.wireValue}", request.path)
        }
        server.enqueue(json(participantBody()))
        assertTrue(repository.joinMeeting(ID).canParticipate)
        val join = take(); assertEquals("/api/v1/conferences/$ID/join", join.path)
        assertEquals("{}", join.body.readUtf8().replace("\"inviteCode\":\"\"", ""))
        server.enqueue(json(participantBody(status = "left")))
        assertFalse(repository.leaveMeeting(ID).canParticipate)
        assertEquals("/api/v1/conferences/$ID/leave", take().path)
    }

    @Test fun `copied invitation stays on selected origin and rejects foreign URLs before network`() = runBlocking {
        signIn()
        server.enqueue(json(participantBody(status = "waiting", admission = "waiting")))
        val waiting = repository.joinInvite("$origin/i/$CODE")
        assertFalse(waiting.canParticipate); assertEquals("waiting", waiting.admissionState)
        val request = take(); assertEquals("POST", request.method)
        assertEquals("/api/v1/conference-invites/$CODE/join", request.path)
        assertNull(request.getHeader("Cookie"))
        val before = server.requestCount
        listOf("https://foreign.example/i/$CODE", "http://localhost/i/$CODE", "$origin/i/$CODE?access_token=private",
            "$origin/i/$CODE#token", "$origin@foreign.example/i/$CODE", "/i/%41${CODE.drop(1)}", "../$CODE").forEach {
            assertEquals(FailureKind.VALIDATION, failure { repository.joinInvite(it) }.kind)
        }
        assertEquals(before, server.requestCount)
        assertEquals(CODE, MeetingInvites.parse(origin, "/api/v1/conference-invites/$CODE"))
        assertEquals("$origin/i/$CODE", MeetingInvites.shareUrl(origin, CODE))
    }

    @Test fun `profile changes only supported name and preserves account capabilities`() = runBlocking {
        signIn()
        val capabilities = repository.state.value.session!!.capabilities
        server.enqueue(json(meBody("Новое имя")))
        assertEquals("Новое имя", repository.updateDisplayName("  Новое имя  ").displayName)
        val request = take(); assertEquals("PATCH", request.method); assertEquals("/api/v1/auth/me", request.path)
        assertEquals("""{"displayName":"Новое имя"}""", request.body.readUtf8())
        assertEquals(USER, repository.state.value.session!!.user.id)
        assertEquals(capabilities, repository.state.value.session!!.capabilities)
        val before = server.requestCount
        listOf("", "bad\nname", "x".repeat(101)).forEach {
            assertEquals(FailureKind.VALIDATION, failure { repository.updateDisplayName(it) }.kind)
        }
        assertEquals(before, server.requestCount)
        server.enqueue(json(meBody("Someone", OTHER_USER)))
        assertEquals(FailureKind.INVALID_RESPONSE, failure { repository.updateDisplayName("Someone") }.kind)
        assertEquals(USER, repository.state.value.session!!.user.id)
    }

    @Test fun `history maps counts participants and timestamps without implementing record download`() = runBlocking {
        signIn()
        val history = """{"status":"success","item":{"conference":${meeting(status = "finished")},"owner":{"id":"$USER","displayName":"Owner"},"durationSec":120,"participantCount":1,"participants":[${participant(status = "left")}],"participantsTruncated":false,"recordings":{"total":3,"ready":1,"processing":1,"failed":1},"chatAvailable":true,"chatReadOnly":true}}"""
        server.enqueue(json(history))
        val result = repository.history(ID)
        assertEquals(120L, result.durationSec); assertEquals(3L, result.recordings.total)
        assertEquals("Owner", result.ownerDisplayName); assertTrue(result.chatReadOnly)
        assertEquals(USER, result.participants.single().userId)
        assertEquals("/api/v1/conferences/$ID/history", take().path)
        server.enqueue(json(history.replace("\"participantCount\":1", "\"participantCount\":-1")))
        assertEquals(FailureKind.INVALID_RESPONSE, failure { repository.history(ID) }.kind)
    }

    @Test fun `wrong resource malformed instant duplicates and oversize response fail contract`() = runBlocking {
        signIn()
        server.enqueue(json("""{"status":"success","item":${meeting().replace(ID, OTHER_ID)}}"""))
        assertEquals(FailureKind.INVALID_RESPONSE, failure { repository.conference(ID) }.kind); take()
        server.enqueue(json(page(meeting().replace("2026-10-09T12:00:00Z", "2026-10-09 12:00"))))
        assertEquals(FailureKind.INVALID_RESPONSE, failure { repository.timeline() }.kind); take()
        server.enqueue(json(page("${meeting()},${meeting()}")))
        assertEquals(FailureKind.INVALID_RESPONSE, failure { repository.timeline() }.kind); take()
        server.enqueue(json("{\"large\":\"${"x".repeat(524_289)}\"}"))
        assertEquals(FailureKind.INVALID_RESPONSE, failure { repository.timeline() }.kind)
    }

    @Test fun `extreme or year zero timestamps cannot crash calendar date conversion`() = runBlocking {
        signIn()
        listOf("+1000000000-01-01T00:00:00Z", "0000-01-01T00:00:00Z", "+10000-01-01T00:00:00Z").forEach { at ->
            server.enqueue(json(page(meeting().replace("2026-10-09T12:00:00Z", at))))
            assertEquals(FailureKind.INVALID_RESPONSE, failure { repository.timeline() }.kind)
            take()
        }
    }

    @Test fun `unsafe resource path never receives bearer and redirect never reaches another server`() = runBlocking {
        signIn(); val before = server.requestCount
        assertEquals(FailureKind.VALIDATION, failure { repository.conference("../../auth/logout") }.kind)
        assertEquals(before, server.requestCount)
        val receiver = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", receiver.url("/stolen")))
            assertEquals(FailureKind.UNAVAILABLE, failure { repository.timeline() }.kind)
            assertEquals(0, receiver.requestCount)
        } finally { receiver.shutdown() }
    }

    @Test fun `cancelled meeting request releases account mutex without retry`() = runBlocking {
        signIn(); server.enqueue(json(page(meeting())).setBodyDelay(3, TimeUnit.SECONDS))
        val request = async { repository.timeline() }
        withContext(Dispatchers.IO) { assertEquals("GET", take().method) }
        withTimeout(2000) {
            request.cancelAndJoin(); repository.setTheme(ThemeMode.DARK)
        }
        assertTrue(request.isCancelled); assertNotNull(repository.state.value.session)
        assertEquals(5, server.requestCount)
    }

    @Test fun `call socket uses scoped one time ticket and preserves relay configuration`() = runBlocking {
        signIn()
        val ice = """{"iceServers":[{"urls":["turn:example.test:3478"],"username":"short","credential":"synthetic"}],"iceTransportPolicy":"relay","expiresAt":2000000000}"""
        server.enqueue(json(ice)); server.enqueue(json("""{"ticket":"one-time-ticket"}"""))
        server.enqueue(MockResponse().withWebSocketUpgrade(object : okhttp3.WebSocketListener() {
            override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) { webSocket.send("fixture-frame") }
        }).setHeader("Sec-WebSocket-Protocol", "go-recorder.v1"))
        val call = repository.openCall(ID)
        assertEquals(ice, call.iceConfiguration)
        assertEquals("fixture-frame", withTimeout(3000) { call.messages.receive() })
        assertEquals("Bearer access-one", take().getHeader("Authorization"))
        assertEquals("/api/v1/conferences/$ID/ws-ticket", take().path)
        val upgrade = take()
        assertEquals("/api/v1/conferences/$ID/ws?ticket=one-time-ticket", upgrade.path)
        assertNull(upgrade.getHeader("Authorization")); assertNull(upgrade.getHeader("Cookie"))
        assertEquals("go-recorder.v1", upgrade.getHeader("Sec-WebSocket-Protocol"))
        call.close(); assertFalse(call.send("late"))
    }

    @Test fun `logout invalidates active call before clearing credentials`() = runBlocking {
        signIn()
        server.enqueue(json("""{"iceServers":[]}""")); server.enqueue(json("""{"ticket":"one-time-ticket"}"""))
        server.enqueue(MockResponse().withWebSocketUpgrade(object : okhttp3.WebSocketListener() {
            override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) { webSocket.send("open") }
        }).setHeader("Sec-WebSocket-Protocol", "go-recorder.v1"))
        val call = repository.openCall(ID)
        withTimeout(3000) { call.messages.receive() }; repeat(3) { take() }
        server.enqueue(json("""{"status":"success"}"""))
        repository.logout()
        assertFalse(call.send("late")); assertNull(repository.state.value.session)
        try { withTimeout(1000) { call.messages.receive() }; fail("closed channel expected") } catch (_: CallDisconnected) {}
    }

    @Test fun `call redirect cannot leak a ticket or bearer to another origin`() = runBlocking {
        signIn()
        val other = MockWebServer().apply { start() }
        try {
            server.enqueue(json("""{"iceServers":[]}"""))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/ticket")))
            assertEquals(FailureKind.UNAVAILABLE, failure { repository.openCall(ID) }.kind)
            assertEquals(0, other.requestCount)
        } finally { other.shutdown() }
    }

    @Test fun `bad call identifier never reaches network`() = runBlocking {
        signIn(); val before = server.requestCount
        failure { repository.openCall("../auth/me") }
        assertEquals(before, server.requestCount)
    }

    @Test fun `moderation and admission validate identities and send exact commands`() = runBlocking {
        signIn()
        val blocked = participant().dropLast(1) + ",\"microphoneBlocked\":true,\"cameraBlocked\":true,\"screenBlocked\":false}"
        server.enqueue(json("""{"status":"success","items":[$blocked]}"""))
        val member = repository.participants(ID).single()
        assertTrue(member.microphoneBlocked); assertTrue(member.cameraBlocked); assertFalse(member.screenBlocked)
        assertEquals("/api/v1/conferences/$ID/participants?limit=100&offset=0", take().path)
        server.enqueue(json(participantBody()))
        repository.admitParticipant(ID, PARTICIPANT, false)
        val reject = take(); assertEquals("POST", reject.method); assertTrue(reject.path!!.endsWith("/admission")); assertEquals("{\"decision\":\"reject\"}", reject.body.readUtf8())
        server.enqueue(json(participantBody()))
        repository.moderateParticipant(ID, PARTICIPANT, com.meetspace.core.model.MeetingModeration("screen", true))
        val moderation = take(); assertTrue(moderation.path!!.endsWith("/moderation")); assertTrue(moderation.body.readUtf8().contains("\"blocked\":true"))
        server.enqueue(json(participantBody().replace(ID, OTHER_ID)))
        assertEquals(FailureKind.INVALID_RESPONSE, failure { repository.moderateParticipant(ID, PARTICIPANT, com.meetspace.core.model.MeetingModeration("kick")) }.kind)
    }
    @Test fun `invitation search encodes query and emails are validated before posting`() = runBlocking {
        signIn(); server.enqueue(json("""{"status":"success","items":[{"id":"$OTHER_USER","displayName":"Peer","email":"peer@example.test"}]}"""))
        assertEquals(OTHER_USER, repository.invitationUsers(ID, "Peer & team").single().id)
        val search = take(); assertEquals("Peer & team", search.requestUrl!!.queryParameter("query")); assertEquals("Bearer access-one", search.getHeader("Authorization"))
        val before = server.requestCount
        failure { repository.inviteParticipants(ID, emptyList(), listOf("bad-address")) }; assertEquals(before, server.requestCount)
        server.enqueue(json("""{"status":"success","items":[]}""", 202))
        repository.inviteParticipants(ID, listOf(OTHER_USER), listOf("external@example.test"))
        val request = take(); assertEquals("POST", request.method); assertEquals("/api/v1/conferences/$ID/invitations", request.path)
        assertTrue(request.body.readUtf8().contains("external@example.test"))
    }

    @Test fun `guest session is conference scoped and never persists credentials or identity`() = runBlocking {
        server.enqueue(json("""{"version":"dev","commit":"unknown","buildTime":"unknown"}""")); repository.addServer(origin); take()
        server.enqueue(json("""{"status":"success","accessToken":"guest-access","tokenType":"Bearer","expiresIn":3600,"user":{"id":"$OTHER_USER","email":"guest@guest.invalid","displayName":"Visitor","guestConferenceId":"$ID"}}"""))
        server.enqueue(json("""{"status":"success","item":${meeting("active")}}"""))
        server.enqueue(json(participantBody(userId = OTHER_USER).replace("\"role\":\"participant\"", "\"role\":\"guest\"")))
        repository.joinGuest(CODE, "Visitor")
        val joined = take(); assertEquals("/api/v1/conference-invites/$CODE/guest", joined.path); assertNull(joined.getHeader("Authorization")); assertNull(joined.getHeader("Cookie"))
        repeat(2) { assertEquals("Bearer guest-access", take().getHeader("Authorization")) }
        assertEquals(ID, repository.state.value.session!!.guestMeeting!!.id); assertFalse(repository.state.value.session!!.supportsPersistentSession)
        assertTrue(credentials.values.isEmpty()); assertNull(profiles.value.profiles.single().accountId)
        server.enqueue(json("""{"status":"success"}""")); repository.logout(); take()
        assertNull(repository.state.value.session); assertTrue(credentials.values.isEmpty())
    }
    @Test fun `guest join cannot replace a signed in account or send credentials to foreign origin`() = runBlocking {
        signIn(); val before = server.requestCount
        assertEquals(FailureKind.CONFLICT, failure { repository.joinGuest(CODE, "Visitor") }.kind)
        assertEquals(before, server.requestCount)
        server.enqueue(json("""{"status":"success"}""")); repository.logout(); take()
        val after = server.requestCount
        failure { repository.joinGuest("https://foreign.example/i/$CODE", "Visitor") }; assertEquals(after, server.requestCount)
    }

    private suspend fun signIn() {
        server.enqueue(json("""{"version":"dev","commit":"unknown","buildTime":"unknown"}"""))
        repository.addServer(origin)
        enqueueLogin("access-one", "cookie-one")
        server.enqueue(json(meBody("Test")))
        server.enqueue(json("""{"status":"success","buildVersion":"dev","capabilities":{"liveCaptions":false,"transcription":false,"aiSummary":false,"semanticSearch":false,"meetingAnalytics":false,"recordingModes":["composite"]}}"""))
        repository.login("test@example.com", "synthetic-password"); repeat(4) { take() }
    }
    private fun enqueueLogin(token: String, cookie: String, user: String = USER) {
        server.enqueue(json("""{"status":"success","accessToken":"$token","tokenType":"Bearer","expiresIn":3600,"user":{"id":"$user","email":"test@example.com","displayName":"Test"}}""")
            .addHeader("Set-Cookie", "$SESSION_COOKIE=$cookie; Path=$AUTH_PATH; Secure; HttpOnly; Max-Age=3600"))
    }
    private fun meBody(name: String, user: String = USER) = """{"status":"success","user":{"id":"$user","email":"test@example.com","displayName":"$name","isAdmin":false}}"""
    private fun take(): RecordedRequest = server.takeRequest(5, TimeUnit.SECONDS) ?: error("Missing request")
    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
    private suspend fun failure(block: suspend () -> Any?): AppFailure = try { block(); error("Expected AppFailure") } catch (error: AppFailure) { error }
    private fun page(items: String, cursor: String? = null) = """{"status":"success","items":[$items],"nextCursor":${cursor?.let { "\"$it\"" } ?: "null"}}"""
    private fun meeting(status: String = "created", scheduledAt: String? = null, duration: Int? = null) = """{"id":"$ID","ownerId":"$USER","title":"A title","inviteCode":"$CODE","inviteUrl":"/api/v1/conference-invites/$CODE","status":"$status","createdAt":"2026-10-09T12:00:00Z","updatedAt":"2026-10-09T12:00:00Z","startedAt":null,"finishedAt":null,"scheduledAt":${scheduledAt?.let { "\"$it\"" } ?: "null"},"plannedDurationMin":${duration ?: "null"},"waitingRoomEnabled":true}"""
    private fun participant(status: String = "joined", admission: String = "admitted", userId: String = USER) = """{"id":"$PARTICIPANT","conferenceId":"$ID","userId":"$userId","displayName":"Test","role":"participant","status":"$status","joinedAt":"2026-10-09T12:00:00Z","leftAt":null,"admissionState":"$admission"}"""
    private fun participantBody(status: String = "joined", admission: String = "admitted", userId: String = USER) = """{"status":"success","item":${participant(status, admission, userId)}}"""
    private companion object {
        const val ID = "11111111-1111-4111-8111-111111111111"
        const val OTHER_ID = "22222222-2222-4222-8222-222222222222"
        const val USER = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val OTHER_USER = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        const val PARTICIPANT = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        const val CODE = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    }
}

private class MeetingsProfiles : ProfileStore {
    var value = PersistedSettings()
    var failWrite = false
    override suspend fun read() = value
    override suspend fun write(settings: PersistedSettings) {
        if (failWrite) throw storageFailure()
        value = settings
    }
}
private class MeetingsCredentials : CredentialStore {
    val values = mutableMapOf<SessionScope, StoredCredential>()
    var failDelete = false
    override suspend fun read(scope: SessionScope) = values[scope]
    override suspend fun write(scope: SessionScope, credential: StoredCredential) { values[scope] = credential }
    override suspend fun delete(scope: SessionScope) {
        if (failDelete) throw storageFailure()
        values.remove(scope)
    }
}
