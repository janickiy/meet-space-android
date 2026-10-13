package com.meetspace.core.data

import com.meetspace.core.model.*
import java.io.File
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Properties
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Real production repository/services, isolated private PostgreSQL/Redis/Pion fixture only. */
class LiveRegistrationDirectCallsTest {
    @Test fun `real register login discovery session restore duplicate and revoke`() = runBlocking {
        fixture { fixture ->
            val profiles = BetaProfiles(); val credentials = BetaCredentials()
            val first = fixture.repository(profiles, credentials)
            first.addServer(fixture.origin)
            val email = "android-beta010-${UUID.randomUUID()}@example.test"
            val password = "Fixture-${UUID.randomUUID()}"
            val created = first.register("😀".repeat(100), email, password)
            assertTrue(created.signedIn)
            assertEquals(email, first.state.value.session!!.user.email)
            assertEquals("😀".repeat(100), first.state.value.session!!.user.displayName)
            assertTrue(first.state.value.session!!.supportsPersistentSession)
            assertEquals(1, credentials.values.size)
            val restarted = fixture.repository(profiles, credentials)
            restarted.restore()
            assertEquals(created.user.id, restarted.state.value.session!!.user.id)
            assertTrue(restarted.logout().remoteConfirmed)
            assertTrue(credentials.values.isEmpty())
            assertNull(restarted.state.value.session)
            assertEquals(FailureKind.CONFLICT, failure { restarted.register("Duplicate", email, password) }.kind)
            // The original in-memory token references the same revoked server session.
            assertEquals(FailureKind.SESSION_EXPIRED, failure { first.activeDirectCall() }.kind)
            assertNull(first.state.value.session)
        }
    }

    @Test fun `real call incoming WSS accept endpoint authorization end and history`() = runBlocking {
        fixture { fixture ->
            val caller = fixture.actor(); val callee = fixture.actor(); val outsider = fixture.actor()
            val callerId = UUID.randomUUID().toString(); val calleeId = UUID.randomUUID().toString()
            val requests = UUID.randomUUID().toString()
            val events = callee.openUserEvents()
            var callId: String? = null
            try {
                events.await("connected")
                val ringing = caller.startDirectCall(callee.state.value.session!!.user.id, requests, callerId)
                callId = ringing.id
                assertEquals("ringing", ringing.status)
                assertEquals(ringing.id, caller.startDirectCall(callee.state.value.session!!.user.id, requests, callerId).id)
                assertEquals(ringing.id, callee.activeDirectCall()!!.id)
                events.await("call.updated", ringing.id)
                assertEquals(FailureKind.NOT_FOUND, failure { outsider.directCall(ringing.id) }.kind)
                assertEquals(FailureKind.FORBIDDEN, failure { caller.directCallAction(ringing.id, DirectCallAction.ACCEPT, callerId) }.kind)
                val accepted = callee.directCallAction(ringing.id, DirectCallAction.ACCEPT, calleeId)
                assertEquals("accepted", accepted.status)
                assertNotNull(accepted.conferenceId)
                assertTrue(accepted.ownsMedia(callee.state.value.session!!.user.id, calleeId))
                assertEquals(FailureKind.FORBIDDEN, failure {
                    caller.openDirectCall(accepted.conferenceId!!, UUID.randomUUID().toString())
                }.kind)
                val channel = caller.openDirectCall(accepted.conferenceId!!, callerId)
                try {
                    assertTrue(channel.iceConfiguration.contains("relay"))
                    withTimeout(10_000) {
                        while (!channel.messages.receive().contains("conference.state")) { /* actual WSS state */ }
                    }
                } finally { channel.close() }
                val ended = caller.directCallAction(ringing.id, DirectCallAction.END, callerId)
                assertEquals("ended", ended.status)
                assertNull(callee.activeDirectCall())
                val history = callee.messages(ended.conversationId).items.first { it.kind == "call" && it.call?.id == ended.id }
                assertEquals("ended", history.call!!.status)
                assertNotNull(history.call!!.endedAt)
            } finally {
                callId?.let { id -> runCatching { caller.directCallAction(id, DirectCallAction.CANCEL, callerId) } }
                events.close()
            }
        }
    }

    @Test fun `real ringing decline and caller cancellation preserve server outcomes`() = runBlocking {
        fixture { fixture ->
            val caller = fixture.actor(); val callee = fixture.actor()
            val callerId = UUID.randomUUID().toString(); val calleeId = UUID.randomUUID().toString()
            val declined = caller.startDirectCall(callee.state.value.session!!.user.id, UUID.randomUUID().toString(), callerId)
            assertEquals("declined", callee.directCallAction(declined.id, DirectCallAction.DECLINE, calleeId).status)
            val cancelled = caller.startDirectCall(callee.state.value.session!!.user.id, UUID.randomUUID().toString(), callerId)
            assertEquals("cancelled", caller.directCallAction(cancelled.id, DirectCallAction.CANCEL, callerId).status)
            assertNull(caller.activeDirectCall()); assertNull(callee.activeDirectCall())
            val history = caller.messages(cancelled.conversationId).items.mapNotNull { it.call }
            assertTrue(history.any { it.id == declined.id && it.status == "declined" })
            assertTrue(history.any { it.id == cancelled.id && it.status == "cancelled" })
        }
    }

    private suspend fun UserEvents.await(type: String, id: String? = null) = withTimeout(10_000) {
        while (true) {
            val event = events.receive()
            if (event.type == type && (id == null || event.callId == id)) break
        }
    }
    private suspend fun failure(block: suspend () -> Any?): AppFailure = try {
        block(); error("Expected fixture authorization failure")
    } catch (failure: AppFailure) { failure }

    private suspend fun fixture(block: suspend (BetaFixture) -> Unit) {
        assumeTrue("Disposable beta010 integration is opt-in", System.getenv("MEETSPACE_BETA010_LIVE") == "1")
        val home = File(requireNotNull(System.getenv("MEETSPACE_BETA010_FIXTURE"))).canonicalFile
        check(File(home, "fixture-marker").readText().trim() == "meetspace-disposable-beta010")
        val properties = Properties().apply { File(home, "live.properties").inputStream().use(::load) }
        val fixture = BetaFixture(properties)
        try { block(fixture) } finally { fixture.close(); properties.clear() }
    }
    private class BetaFixture(properties: Properties) {
        val origin = properties.getProperty("origin").also { check(it == "https://127.0.0.1:18410") }
        private val certificate = File(properties.getProperty("caPath")).inputStream().use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate }
        private val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate).build()
        private val client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
            .callTimeout(15, TimeUnit.SECONDS).build()
        private val actors = mutableListOf<DefaultFoundationRepository>()
        fun repository(profiles: BetaProfiles = BetaProfiles(), credentials: BetaCredentials = BetaCredentials()) =
            DefaultFoundationRepository(profiles, credentials, MeetSpaceApi(client)).also(actors::add)
        suspend fun actor(): DefaultFoundationRepository = repository().also {
            it.addServer(origin)
            val result = it.register("Disposable Android actor", "android-beta010-${UUID.randomUUID()}@example.test", "Fixture-${UUID.randomUUID()}")
            check(result.signedIn)
        }
        suspend fun close() {
            actors.forEach { if (it.state.value.session != null) runCatching { it.logout() } }
            client.dispatcher.cancelAll(); client.connectionPool.evictAll()
        }
    }
}
private class BetaProfiles : ProfileStore {
    var settings = PersistedSettings()
    override suspend fun read() = settings
    override suspend fun write(settings: PersistedSettings) { this.settings = settings }
}
private class BetaCredentials : CredentialStore {
    val values = mutableMapOf<SessionScope, StoredCredential>()
    override suspend fun read(scope: SessionScope) = values[scope]
    override suspend fun write(scope: SessionScope, credential: StoredCredential) { values[scope] = credential }
    override suspend fun delete(scope: SessionScope) { values.remove(scope) }
}
