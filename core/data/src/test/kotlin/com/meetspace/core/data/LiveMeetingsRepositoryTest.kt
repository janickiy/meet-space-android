package com.meetspace.core.data

import com.meetspace.core.model.AppFailure
import com.meetspace.core.model.FailureKind
import com.meetspace.core.model.MeetingInput
import com.meetspace.core.model.MeetingTransition
import com.meetspace.core.model.MeetingsQuery
import com.meetspace.core.model.MeetingsScope
import com.meetspace.core.model.MeetingsView
import java.io.File
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.Properties
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in against the disposable real Gin/PostgreSQL fixture, never a public server. */
class LiveMeetingsRepositoryTest {
    @Test fun `actual WSS returns scoped snapshot and SFU admission with relay REST configuration`() = runBlocking {
        assumeTrue("Stage06 live fixture is opt-in", System.getenv("MEETSPACE_STAGE06_LIVE") == "1")
        val fixture = Properties().apply { File(requireNotNull(System.getenv("MEETSPACE_STAGE06_FIXTURE"))).inputStream().use(::load) }
        val origin = fixture.getProperty("origin")
        assertTrue(origin.startsWith("https://127.0.0.1:"))
        val certificate = File(fixture.getProperty("caPath")).inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate }
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate).build()
        val api = MeetSpaceApi(OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build())
        val repository = DefaultFoundationRepository(LiveProfiles(), LiveCredentials(), api)
        repository.addServer(origin); repository.login(fixture.getProperty("email"), fixture.getProperty("password"))
        val meeting = repository.createMeeting(MeetingInput("Disposable Android WSS contract"))
        try {
            repository.transitionMeeting(meeting.id, MeetingTransition.START); repository.joinMeeting(meeting.id)
            val socket = repository.openCall(meeting.id)
            assertTrue(socket.iceConfiguration.contains("\"iceTransportPolicy\":\"relay\""))
            val snapshot = kotlinx.coroutines.withTimeout(5000) { socket.messages.receive() }
            assertTrue(snapshot.contains("conference.state")); assertTrue(snapshot.contains(meeting.id))
            val requestId = UUID.randomUUID().toString()
            assertTrue(socket.send("""{"version":1,"id":"$requestId","type":"media.join","conferenceId":"${meeting.id}","timestamp":"${Instant.now()}","data":{}}"""))
            val joined = kotlinx.coroutines.withTimeout(5000) { var value = socket.messages.receive(); while (!value.contains("media.joined")) value = socket.messages.receive(); value }
            assertTrue(joined.contains(requestId)); assertTrue(joined.contains("mediaPeerId"))
            socket.close()
            assertFalse(socket.send("late"))
        } finally {
            repository.leaveMeeting(meeting.id); repository.transitionMeeting(meeting.id, MeetingTransition.FINISH); repository.logout()
        }
    }

    @Test fun `actual HTTPS backend supports account isolated meeting lifecycle and durable refresh`() = runBlocking {
        assumeTrue("Disposable staging fixture is opt-in", System.getenv("MEETSPACE_STAGE05_LIVE") == "1")
        val privateFile = File(requireNotNull(System.getenv("MEETSPACE_STAGE05_FIXTURE")))
        val fixture = Properties().apply { privateFile.inputStream().use(::load) }
        val origin = ServerOrigin.normalize(requireNotNull(fixture.getProperty("origin")))
        // The fixture may only target localhost. Its private CA is injected in this test client only.
        assertTrue(origin.startsWith("https://127.0.0.1:") || origin.startsWith("https://localhost:"))
        val certificate = File(requireNotNull(fixture.getProperty("caPath"))).inputStream().use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate).build()
        val api = MeetSpaceApi(OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
            .callTimeout(10, TimeUnit.SECONDS).build())
        val ownerProfiles = LiveProfiles(); val ownerCredentials = LiveCredentials()
        var owner = DefaultFoundationRepository(ownerProfiles, ownerCredentials, api)
        val member = DefaultFoundationRepository(LiveProfiles(), LiveCredentials(), api)
        var restoreName: String? = null
        var signedIn = false
        try {
            owner.addServer(origin)
            owner.login(requireNotNull(fixture.getProperty("email")), requireNotNull(fixture.getProperty("password")))
            signedIn = true
            member.addServer(origin)
            member.login(requireNotNull(fixture.getProperty("secondEmail")), requireNotNull(fixture.getProperty("secondPassword")))
            val ownerId = owner.state.value.session!!.user.id
            val memberId = member.state.value.session!!.user.id
            assertNotEquals(ownerId, memberId)
            restoreName = owner.state.value.session!!.user.displayName
            val unique = UUID.randomUUID().toString().take(8)
            assertEquals("Проверка Android $unique", owner.updateDisplayName("Проверка Android $unique").displayName)

            val instant = owner.createMeeting(MeetingInput("Android staging instant $unique"))
            assertEquals("created", instant.status); assertEquals(ownerId, instant.ownerId)
            assertEquals(instant.id, owner.conference(instant.id).id)
            assertEquals(FailureKind.FORBIDDEN, failure { member.conference(instant.id) }.kind)
            assertEquals(FailureKind.FORBIDDEN, failure { member.transitionMeeting(instant.id, MeetingTransition.START) }.kind)
            assertEquals("active", owner.transitionMeeting(instant.id, MeetingTransition.START).status)
            assertTrue(owner.joinMeeting(instant.id).canParticipate)
            val joined = member.joinInvite(MeetingInvites.shareUrl(origin, instant.inviteCode))
            assertEquals(instant.id, joined.conferenceId); assertEquals(memberId, joined.userId); assertTrue(joined.canParticipate)
            assertTrue(member.membership(instant.id)!!.canParticipate)
            assertEquals("left", member.leaveMeeting(instant.id).status)
            assertEquals("finished", owner.transitionMeeting(instant.id, MeetingTransition.FINISH).status)
            val history = owner.history(instant.id)
            assertEquals(instant.id, history.conference.id)
            assertEquals(ownerId, history.ownerId); assertTrue(history.participantCount >= 2)
            assertEquals(0L, history.recordings.total)
            assertTrue(history.chatReadOnly)
            assertEquals(instant.id, member.history(instant.id).conference.id)

            val waitingRoom = owner.createMeeting(MeetingInput("Android staging waiting $unique", waitingRoomEnabled = true))
            owner.transitionMeeting(waitingRoom.id, MeetingTransition.START)
            assertEquals(FailureKind.FORBIDDEN, failure { member.joinMeeting(waitingRoom.id) }.kind)
            // Audited policy: a valid invitation itself grants admission through the waiting room.
            val invited = member.joinInvite(waitingRoom.inviteCode)
            assertEquals("admitted", invited.admissionState); assertTrue(invited.canParticipate)
            assertTrue(member.membership(waitingRoom.id)!!.canParticipate)
            assertEquals(waitingRoom.id, member.conference(waitingRoom.id).id)
            assertEquals(FailureKind.CONFLICT, failure { owner.transitionMeeting(waitingRoom.id, MeetingTransition.CANCEL) }.kind)
            owner.transitionMeeting(waitingRoom.id, MeetingTransition.FINISH)
            assertEquals("finished", member.history(waitingRoom.id).conference.status)

            val plannedAt = Instant.now().plusSeconds(3 * 86400).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
            val planned = owner.createMeeting(MeetingInput("Android staging schedule $unique", true, plannedAt, 45))
            assertEquals("scheduled", planned.status); assertEquals(plannedAt, planned.scheduledAt)
            val moved = owner.scheduleMeeting(planned.id, plannedAt.plusSeconds(3600), 60)
            assertEquals(plannedAt.plusSeconds(3600), moved.scheduledAt); assertEquals(60, moved.plannedDurationMin)
            val calendar = owner.timeline(MeetingsQuery(MeetingsView.UPCOMING, MeetingsScope.OWNED,
                plannedAt.minusSeconds(3600), plannedAt.plusSeconds(7200), limit = 100))
            assertTrue(calendar.items.any { it.id == planned.id })
            assertEquals("cancelled", owner.transitionMeeting(planned.id, MeetingTransition.CANCEL).status)
            assertEquals("cancelled", owner.history(planned.id).conference.status)

            val pastQuery = MeetingsQuery(MeetingsView.PAST, MeetingsScope.OWNED,
                Instant.now().minusSeconds(600), Instant.now().plusSeconds(600), limit = 1)
            var page = owner.timeline(pastQuery)
            val seen = mutableSetOf<String>()
            var pages = 0
            while (true) {
                page.items.forEach { assertTrue("Cursor pages must not repeat resources", seen.add(it.id)) }
                val next = page.nextCursor ?: break
                assertTrue("Fixture pagination is bounded", ++pages < 100)
                page = owner.timeline(pastQuery.copy(cursor = next))
            }
            assertTrue(seen.contains(instant.id)); assertTrue(seen.contains(planned.id))

            // Restoration exercises the real HttpOnly-cookie refresh flow and rotated persistence.
            assertTrue(ownerCredentials.values.isNotEmpty())
            val restored = DefaultFoundationRepository(ownerProfiles, ownerCredentials, api)
            restored.restore()
            assertEquals(ownerId, restored.state.value.session!!.user.id)
            owner = restored
            assertEquals(instant.id, owner.conference(instant.id).id)
            restoreName?.takeIf(String::isNotBlank)?.let { owner.updateDisplayName(it) }
            assertTrue(owner.logout().remoteConfirmed)
            signedIn = false
            assertTrue(ownerCredentials.values.isEmpty()); assertNull(owner.state.value.session)
            assertEquals(FailureKind.SESSION_EXPIRED, failure { owner.timeline() }.kind)
            assertTrue(member.logout().remoteConfirmed)
        } finally {
            // Only this disposable fixture's account profile is touched; QA drops its database.
            if (signedIn && owner.state.value.session != null) {
                restoreName?.takeIf(String::isNotBlank)?.let { runCatching { owner.updateDisplayName(it) } }
                runCatching { owner.logout() }
            }
            if (member.state.value.session != null) runCatching { member.logout() }
            fixture.clear()
        }
    }

    private suspend fun failure(block: suspend () -> Any?): AppFailure = try {
        block(); error("Expected curated failure")
    } catch (error: AppFailure) { error }
}

private class LiveProfiles : ProfileStore {
    var settings = PersistedSettings()
    override suspend fun read() = settings
    override suspend fun write(settings: PersistedSettings) { this.settings = settings }
}
private class LiveCredentials : CredentialStore {
    val values = mutableMapOf<SessionScope, StoredCredential>()
    override suspend fun read(scope: SessionScope) = values[scope]
    override suspend fun write(scope: SessionScope, credential: StoredCredential) { values[scope] = credential }
    override suspend fun delete(scope: SessionScope) { values.remove(scope) }
}
