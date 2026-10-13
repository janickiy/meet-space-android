package com.meetspace.core.data

import com.meetspace.core.model.AppFailure
import com.meetspace.core.model.FailureKind
import com.meetspace.core.model.ServerProfile
import com.meetspace.core.model.ThemeMode
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
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FoundationRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var serverCertificates: HandshakeCertificates
    private lateinit var api: MeetSpaceApi
    private lateinit var origin: String
    private lateinit var preferences: MemoryProfileStore
    private lateinit var credentials: MemoryCredentialStore
    private lateinit var repository: DefaultFoundationRepository

    @Before fun setup() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer().apply {
            useHttps(serverCertificates.sslSocketFactory(), false)
            start()
        }
        origin = server.url("/").toString().removeSuffix("/")
        api = MeetSpaceApi(OkHttpClient.Builder()
            .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
            .callTimeout(5, TimeUnit.SECONDS).build())
        preferences = MemoryProfileStore()
        credentials = MemoryCredentialStore()
        repository = newRepository()
    }

    @After fun cleanup() { server.shutdown() }

    @Test fun `registration creates account then obtains a real isolated session`() = runBlocking {
        enqueueVersion(); repository.addServer(origin)
        server.enqueue(json(ME, 201).addHeader("Set-Cookie", "$SESSION_COOKIE=untrusted-registration-cookie; Path=$AUTH_PATH; Secure; HttpOnly; Max-Age=3600"))
        enqueueLogin("first-access", "first-cookie"); enqueueMe(); enqueueCapabilities()
        val result = repository.register("  Test User  ", " TEST@EXAMPLE.COM ", "synthetic-password")
        assertTrue(result.signedIn)
        assertEquals("user-a", result.user.id)
        assertNotNull(repository.state.value.session)
        assertEquals("first-cookie", credentials.values.values.single().value)
        take()
        val registration = take()
        assertEquals("$AUTH_PATH/register", registration.path)
        assertNull(registration.getHeader("Authorization")); assertNull(registration.getHeader("Cookie"))
        val payload = registration.body.readUtf8()
        assertTrue(payload.contains("\"displayName\":\"Test User\""))
        assertTrue(payload.contains("\"email\":\"test@example.com\""))
        val login = take()
        assertEquals("$AUTH_PATH/login", login.path)
        assertNull(login.getHeader("Cookie")); assertNull(login.getHeader("Authorization"))
    }

    @Test fun `duplicate registration stops before login and does not expose server diagnostics`() = runBlocking {
        enqueueVersion(); repository.addServer(origin)
        server.enqueue(json("{\"message\":\"private database constraint\"}", 409))
        val error = failure { repository.register("Test", "test@example.com", "synthetic-password") }
        assertEquals(FailureKind.CONFLICT, error.kind)
        assertTrue(error.message.contains("уже существует"))
        assertFalse(error.message.contains("private"))
        assertEquals(2, server.requestCount)
        assertNull(repository.state.value.session)
        assertTrue(credentials.values.isEmpty())
    }

    @Test fun `created account is distinguished from a failed follow-up login`() = runBlocking {
        enqueueVersion(); repository.addServer(origin)
        server.enqueue(json(ME, 201)); server.enqueue(json("{}", 503))
        val result = repository.register("Test", "test@example.com", "synthetic-password")
        assertFalse(result.signedIn)
        assertEquals("user-a", result.user.id)
        assertNotNull(result.signInFailure)
        assertEquals(3, server.requestCount)
        assertNull(repository.state.value.session)
        assertTrue(credentials.values.isEmpty())
    }

    @Test fun `registration does not authenticate another account returned by login`() = runBlocking {
        enqueueVersion(); repository.addServer(origin)
        server.enqueue(json(ME, 201)); enqueueLogin("wrong-access", "wrong-cookie", "user-b")
        val result = repository.register("Test", "test@example.com", "synthetic-password")
        assertFalse(result.signedIn)
        assertNull(repository.state.value.session)
        assertTrue(credentials.values.isEmpty())
        assertEquals(3, server.requestCount)
    }

    @Test fun `registration rejects invalid input before posting any credentials`() = runBlocking {
        enqueueVersion(); repository.addServer(origin)
        listOf(Triple("", "test@example.com", "synthetic-password"), Triple("Test", "invalid-email", "synthetic-password"),
            Triple("Test", "test@example.com", "short"), Triple("😀".repeat(101), "test@example.com", "synthetic-password"))
            .forEach { (name, email, password) ->
                assertEquals(FailureKind.VALIDATION, failure { repository.register(name, email, password) }.kind)
            }
        assertEquals(1, server.requestCount)
    }

    @Test fun `registration requires successful public version before posting`() = runBlocking {
        preferences.value = PersistedSettings(listOf(ServerProfile("saved-profile", origin)), "saved-profile")
        server.enqueue(json("{}", 503))
        assertEquals(FailureKind.UNAVAILABLE, failure { repository.register("Test", "test@example.com", "synthetic-password") }.kind)
        assertEquals("/version", take().path)
        assertEquals(1, server.requestCount)
        assertNull(repository.state.value.session)
    }

    @Test fun `malformed or incorrect status registration is never treated as an account session`() = runBlocking {
        enqueueVersion(); repository.addServer(origin)
        server.enqueue(json("{\"status\":\"success\"}", 201))
        assertEquals(FailureKind.INVALID_RESPONSE, failure { repository.register("Test", "test@example.com", "synthetic-password") }.kind)
        server.enqueue(json(ME, 200))
        assertEquals(FailureKind.INVALID_RESPONSE, failure { repository.register("Test", "test@example.com", "synthetic-password") }.kind)
        assertNull(repository.state.value.session)
        assertTrue(credentials.values.isEmpty())
        assertEquals(3, server.requestCount)
    }

    @Test fun `server registration policy is respected without refresh or login`() = runBlocking {
        enqueueVersion(); repository.addServer(origin)
        server.enqueue(json("{\"message\":\"private policy\"}", 403))
        assertEquals(FailureKind.FORBIDDEN, failure { repository.register("Test", "test@example.com", "synthetic-password") }.kind)
        assertEquals(2, server.requestCount)
        assertNull(repository.state.value.session)
    }

    @Test fun `registration cannot silently replace an authenticated account`() = runBlocking {
        connectAndLogin()
        assertEquals(FailureKind.CONFLICT, failure { repository.register("Other", "other@example.com", "synthetic-password") }.kind)
        assertEquals(4, server.requestCount)
        assertEquals("user-a", repository.state.value.session!!.user.id)
    }

    @Test fun `login proves account and capabilities then stores only scoped session cookie`() = runBlocking {
        enqueueVersion()
        val profile = repository.addServer(origin)
        enqueueLogin("first-access", "first-cookie")
        enqueueMe()
        enqueueCapabilities()
        repository.login(" TEST@EXAMPLE.COM ", "synthetic-password")
        val session = repository.state.value.session!!
        assertEquals("user-a", session.user.id)
        assertTrue(session.supportsPersistentSession)
        assertTrue(session.capabilities.recordingModes.contains("composite"))
        assertEquals("first-cookie", credentials.values[SessionScope(origin, "user-a")]!!.value)
        assertEquals(profile.id, preferences.value.selectedProfileId)
        assertEquals("user-a", preferences.value.profiles.single().accountId)
        assertFalse(repository.state.value.toString().contains("first-access"))
        assertFalse(preferences.value.toString().contains("first-cookie"))
        val version = take()
        assertEquals("/version", version.path)
        assertNull(version.getHeader("Authorization")); assertNull(version.getHeader("Cookie"))
        val login = take()
        assertEquals("$AUTH_PATH/login", login.path)
        assertNull(login.getHeader("Authorization")); assertNull(login.getHeader("Cookie"))
        assertTrue(login.body.readUtf8().contains("test@example.com"))
        val me = take()
        assertEquals("Bearer first-access", me.getHeader("Authorization"))
        assertEquals("$SESSION_COOKIE=first-cookie", me.getHeader("Cookie"))
        val capabilities = take()
        assertEquals("Bearer first-access", capabilities.getHeader("Authorization"))
        assertNull(capabilities.getHeader("Cookie"))
    }

    @Test fun `restart refreshes saved session without persisting bearer token or password`() = runBlocking {
        connectAndLogin()
        repeat(4) { take() }
        val restored = newRepository()
        enqueueVersion()
        enqueueLogin("renewed-access", "rotated-cookie")
        enqueueMe(); enqueueCapabilities()
        restored.restore()
        assertEquals("user-a", restored.state.value.session!!.user.id)
        assertEquals("rotated-cookie", credentials.values.values.single().value)
        assertEquals("/version", take().path)
        val refresh = take()
        assertEquals("$AUTH_PATH/refresh", refresh.path)
        assertEquals("$SESSION_COOKIE=first-cookie", refresh.getHeader("Cookie"))
        assertNull(refresh.getHeader("Authorization"))
        assertEquals("Bearer renewed-access", take().getHeader("Authorization"))
    }

    @Test fun `account registration date comes from authenticated me and malformed dates do not prevent login`() = runBlocking {
        enqueueVersion(); repository.addServer(origin)
        for (date in listOf("2026-10-03T16:17:00Z", "invalid-date")) {
            enqueueLogin("test-access", "test-cookie")
            server.enqueue(json(ME.replace("\"isAdmin\":false", "\"isAdmin\":false,\"createdAt\":\"$date\"")))
            enqueueCapabilities()
            repository.login("test@example.com", "synthetic-password")
            assertEquals(if (date == "invalid-date") null else date, repository.state.value.session!!.user.createdAt)
        }
    }

    @Test fun `401 refreshes once and retries the safe GET with new token`() = runBlocking {
        enqueueVersion(); repository.addServer(origin)
        enqueueLogin("expired-access", "first-cookie")
        server.enqueue(json("{}", 401))
        enqueueLogin("refreshed-access", "fresh-cookie")
        enqueueMe(); enqueueCapabilities()
        repository.login("test@example.com", "synthetic-password")
        assertNotNull(repository.state.value.session)
        assertEquals(6, server.requestCount)
        repeat(3) { take() }
        val refresh = take()
        assertEquals("$AUTH_PATH/refresh", refresh.path)
        assertNull(refresh.getHeader("Authorization"))
        assertEquals("$SESSION_COOKIE=first-cookie", refresh.getHeader("Cookie"))
        assertEquals("Bearer refreshed-access", take().getHeader("Authorization"))
    }

    @Test fun `a second 401 stops without a refresh loop`() = runBlocking {
        enqueueVersion(); repository.addServer(origin)
        enqueueLogin("expired-access", "first-cookie")
        server.enqueue(json("{}", 401))
        enqueueLogin("also-expired", "fresh-cookie")
        server.enqueue(json("{}", 401))
        assertEquals(FailureKind.INVALID_CREDENTIALS, failure { repository.login("test@example.com", "synthetic-password") }.kind)
        assertEquals(5, server.requestCount)
        assertNull(repository.state.value.session)
        assertTrue(credentials.values.isEmpty())
    }

    @Test fun `403 is final and has a safe user message`() = runBlocking {
        enqueueVersion(); repository.addServer(origin)
        enqueueLogin("first-access", "first-cookie")
        server.enqueue(json("{\"message\":\"private server diagnostic\"}", 403))
        val error = failure { repository.login("test@example.com", "synthetic-password") }
        assertEquals(FailureKind.FORBIDDEN, error.kind)
        assertFalse(error.message.contains("private"))
        assertEquals(3, server.requestCount)
        assertNull(repository.state.value.session)
    }

    @Test fun `unknown commit permits basic auth and remains visible`() = runBlocking {
        server.enqueue(json("""{"version":"future-1","commit":"${"a".repeat(40)}","buildTime":"unknown"}"""))
        repository.addServer(origin)
        enqueueLogin("first-access", "first-cookie"); enqueueMe(); enqueueCapabilities()
        repository.login("test@example.com", "synthetic-password")
        assertEquals("future-1", repository.state.value.version!!.version)
        assertNotNull(repository.state.value.session)
    }

    @Test fun `wrong JSON schema and HTML never create a server profile`() = runBlocking {
        server.enqueue(json("{\"version\":\"1\"}"))
        assertEquals(FailureKind.INVALID_RESPONSE, failure { repository.addServer(origin) }.kind)
        assertTrue(repository.state.value.profiles.isEmpty())
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html>proxy</html>"))
        assertEquals(FailureKind.INVALID_RESPONSE, failure { repository.addServer(origin) }.kind)
        assertTrue(preferences.value.profiles.isEmpty())
    }

    @Test fun `login cannot send credentials until selected server version is verified`() = runBlocking {
        preferences.value = PersistedSettings(listOf(ServerProfile("saved-profile", origin)), "saved-profile")
        server.enqueue(json("{\"message\":\"unavailable\"}", 503))
        val error = failure { repository.login("test@example.com", "synthetic-password") }
        assertEquals(FailureKind.UNAVAILABLE, error.kind)
        assertNull(repository.state.value.version)
        assertNull(repository.state.value.session)
        assertEquals(1, server.requestCount)
        val request = take()
        assertEquals("/version", request.path)
        assertNull(request.getHeader("Authorization")); assertNull(request.getHeader("Cookie"))
        assertEquals(0L, request.bodySize)
    }

    @Test fun `login without a secure persistent cookie remains explicitly nonpersistent`() = runBlocking {
        enqueueVersion(); repository.addServer(origin)
        server.enqueue(json(loginBody("legacy-access")))
        enqueueMe(); enqueueCapabilities()
        repository.login("test@example.com", "synthetic-password")
        assertFalse(repository.state.value.session!!.supportsPersistentSession)
        assertTrue(credentials.values.isEmpty())
    }

    @Test fun `logout confirms server revocation and always removes local credential`() = runBlocking {
        connectAndLogin()
        server.enqueue(json("{\"status\":\"success\"}")
            .addHeader("Set-Cookie", "$SESSION_COOKIE=; Path=$AUTH_PATH; Secure; HttpOnly; Max-Age=0"))
        assertTrue(repository.logout().remoteConfirmed)
        assertNull(repository.state.value.session)
        assertTrue(credentials.values.isEmpty())
        assertNull(preferences.value.profiles.single().accountId)
        repeat(4) { take() }
        val logout = take()
        assertEquals("$AUTH_PATH/logout", logout.path)
        assertEquals("$SESSION_COOKIE=first-cookie", logout.getHeader("Cookie"))
        assertEquals("Bearer first-access", logout.getHeader("Authorization"))
    }

    @Test fun `failed remote logout returns unconfirmed and still erases local session`() = runBlocking {
        connectAndLogin()
        server.enqueue(json("{\"message\":\"unavailable\"}", 503))
        assertFalse(repository.logout().remoteConfirmed)
        assertNull(repository.state.value.session)
        assertTrue(credentials.values.isEmpty())
        assertNull(preferences.value.profiles.single().accountId)
    }

    @Test fun `logout stores a durable marker even when deleting ciphertext fails`() = runBlocking {
        connectAndLogin()
        credentials.failDelete = true
        server.enqueue(json("{\"status\":\"success\"}"))
        assertEquals(FailureKind.STORAGE, failure { repository.logout() }.kind)
        assertNull(repository.state.value.session)
        assertNull(preferences.value.profiles.single().accountId)
        assertTrue(credentials.values.isNotEmpty())
        val restarted = newRepository()
        enqueueVersion()
        restarted.restore()
        assertNull(restarted.state.value.session)
        // The surviving ciphertext is unreachable and is never used for refresh.
        assertEquals(6, server.requestCount)
    }

    @Test fun `logout erases ciphertext even when persisting its marker fails`() = runBlocking {
        connectAndLogin()
        preferences.failWrite = true
        server.enqueue(json("{\"status\":\"success\"}"))
        assertEquals(FailureKind.STORAGE, failure { repository.logout() }.kind)
        assertNull(repository.state.value.session)
        assertNull(repository.state.value.selectedProfile!!.accountId)
        assertTrue(credentials.values.isEmpty())
        val restarted = newRepository()
        enqueueVersion(); restarted.restore()
        assertNull(restarted.state.value.session)
        assertEquals(6, server.requestCount)
    }

    @Test fun `failed switch or remove cannot expose a session after memory is cleared`() = runBlocking {
        connectAndLogin()
        preferences.failWrite = true
        assertEquals(FailureKind.STORAGE, failure { repository.selectServer(repository.state.value.selectedProfileId!!) }.kind)
        assertNull(repository.state.value.session)
        preferences.failWrite = false
        enqueueVersion(); enqueueLogin("restored-access", "first-cookie"); enqueueMe(); enqueueCapabilities()
        repository.restore()
        assertNotNull(repository.state.value.session)
        credentials.failDelete = true
        assertEquals(FailureKind.STORAGE, failure { repository.removeServer(repository.state.value.selectedProfileId!!) }.kind)
        assertNull(repository.state.value.session)
    }

    @Test fun `revoked saved session is deleted and is never retried automatically`() = runBlocking {
        connectAndLogin()
        val restarted = newRepository()
        enqueueVersion(); server.enqueue(json("{}", 401))
        assertEquals(FailureKind.SESSION_EXPIRED, failure { restarted.restore() }.kind)
        assertTrue(credentials.values.isEmpty())
        assertNull(restarted.state.value.session)
        assertEquals(6, server.requestCount)
    }

    @Test fun `restore rejects a credential refreshed as another account`() = runBlocking {
        connectAndLogin()
        val restarted = newRepository()
        enqueueVersion(); enqueueLogin("wrong-access", "wrong-cookie", "user-b")
        assertEquals(FailureKind.INVALID_RESPONSE, failure { restarted.restore() }.kind)
        assertTrue(credentials.values.isEmpty())
        assertNull(restarted.state.value.session)
    }

    @Test fun `rotated refresh cookie survives a later discovery failure`() = runBlocking {
        connectAndLogin()
        val restarted = newRepository()
        enqueueVersion(); enqueueLogin("renewed-access", "rotated-cookie")
        enqueueMe(); server.enqueue(json("{}", 503))
        assertEquals(FailureKind.UNAVAILABLE, failure { restarted.restore() }.kind)
        assertNull(restarted.state.value.session)
        assertEquals("rotated-cookie", credentials.values.values.single().value)
    }

    @Test fun `switching origins never sends old cookie or bearer to the new server`() = runBlocking {
        connectAndLogin()
        repeat(4) { take() }
        // Different port is a different origin even with the same hostname and trusted certificate.
        val second = MockWebServer()
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCerts = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientCerts = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        second.useHttps(serverCerts.sslSocketFactory(), false); second.start()
        try {
            val secondOrigin = second.url("/").toString().removeSuffix("/")
            val allTrustedApi = MeetSpaceApi(OkHttpClient.Builder()
                .sslSocketFactory(clientCerts.sslSocketFactory(), clientCerts.trustManager).build())
            val fresh = DefaultFoundationRepository(preferences, credentials, allTrustedApi)
            second.enqueue(json(VERSION))
            fresh.addServer(secondOrigin)
            assertNull(fresh.state.value.session)
            val request = second.takeRequest(5, TimeUnit.SECONDS)!!
            assertNull(request.getHeader("Authorization")); assertNull(request.getHeader("Cookie"))
            assertEquals("first-cookie", credentials.values[SessionScope(origin, "user-a")]!!.value)
            assertEquals(2, fresh.state.value.profiles.size)
        } finally { second.shutdown() }
    }

    @Test fun `another account login starts empty and replaces only its scoped local credential`() = runBlocking {
        connectAndLogin()
        repeat(4) { take() }
        enqueueLogin("second-access", "second-cookie", "user-b")
        server.enqueue(json(ME.replace("user-a", "user-b")))
        enqueueCapabilities()
        repository.login("other@example.com", "synthetic-password")
        val login = take()
        assertNull(login.getHeader("Cookie")); assertNull(login.getHeader("Authorization"))
        assertEquals("user-b", repository.state.value.session!!.user.id)
        assertNull(credentials.values[SessionScope(origin, "user-a")])
        assertEquals("second-cookie", credentials.values[SessionScope(origin, "user-b")]!!.value)
        assertEquals("Bearer second-access", take().getHeader("Authorization"))
    }

    @Test fun `same server accounts keep isolated sessions and deletion switches to the next saved account`() = runBlocking {
        enqueueVersion()
        val first = repository.addServer(origin)
        enqueueLogin("first-access", "first-cookie"); enqueueMe(); enqueueCapabilities()
        repository.login("test@example.com", "synthetic-password")
        repeat(4) { take() }

        enqueueVersion()
        server.enqueue(json(loginBody("second-access", "user-b").replace("test@example.com", "other@example.com"))
            .addHeader("Set-Cookie", "$SESSION_COOKIE=second-cookie; Path=$AUTH_PATH; Secure; HttpOnly; Max-Age=3600"))
        server.enqueue(json(ME.replace("user-a", "user-b").replace("test@example.com", "other@example.com")))
        enqueueCapabilities()
        repository.addAccount(origin, "other@example.com", "another-password")
        val second = repository.state.value.selectedProfile!!
        assertEquals(2, repository.state.value.profiles.size)
        assertEquals(first.origin, second.origin)
        assertEquals("/version", take().path)
        val secondLogin = take()
        assertEquals("$AUTH_PATH/login", secondLogin.path)
        assertNull(secondLogin.getHeader("Cookie")); assertNull(secondLogin.getHeader("Authorization"))
        take(); take()
        assertEquals("first-cookie", credentials.values[SessionScope(origin, "user-a")]!!.value)
        assertEquals("second-cookie", credentials.values[SessionScope(origin, "user-b")]!!.value)

        // A fresh process must accept multiple identities on the same origin.
        enqueueVersion(); enqueueLogin("restart-access", "restart-cookie", "user-b")
        server.enqueue(json(ME.replace("user-a", "user-b").replace("test@example.com", "other@example.com")))
        enqueueCapabilities()
        repository = newRepository()
        repository.restore()
        assertEquals(2, repository.state.value.profiles.size)
        assertEquals("user-b", repository.state.value.session!!.user.id)
        take()
        assertEquals("$SESSION_COOKIE=second-cookie", take().getHeader("Cookie"))
        take(); take()

        // Switching restores the first account using only its own stored cookie.
        enqueueVersion(); enqueueLogin("first-refresh", "first-rotated"); enqueueMe(); enqueueCapabilities()
        repository.selectServer(first.id)
        assertEquals("user-a", repository.state.value.session!!.user.id)
        assertEquals("/version", take().path)
        val firstRefresh = take()
        assertEquals("$AUTH_PATH/refresh", firstRefresh.path)
        assertEquals("$SESSION_COOKIE=first-cookie", firstRefresh.getHeader("Cookie"))
        take(); take()

        server.enqueue(json("""{"status":"success"}"""))
        enqueueVersion(); enqueueLogin("second-refresh", "second-rotated", "user-b")
        server.enqueue(json(ME.replace("user-a", "user-b").replace("test@example.com", "other@example.com")))
        enqueueCapabilities()
        repository.deleteAccount("synthetic-password")
        val deletion = take()
        assertEquals("DELETE", deletion.method)
        assertEquals("$AUTH_PATH/me", deletion.path)
        assertEquals("Bearer first-refresh", deletion.getHeader("Authorization"))
        assertEquals("$SESSION_COOKIE=first-rotated", deletion.getHeader("Cookie"))
        assertTrue(deletion.body.readUtf8().contains("\"password\":\"synthetic-password\""))
        assertEquals("/version", take().path)
        val nextRefresh = take()
        assertEquals("$AUTH_PATH/refresh", nextRefresh.path)
        assertEquals("$SESSION_COOKIE=restart-cookie", nextRefresh.getHeader("Cookie"))
        take(); take()
        assertEquals(listOf(second.id), repository.state.value.profiles.map { it.id })
        assertEquals("user-b", repository.state.value.session!!.user.id)
        assertNull(credentials.values[SessionScope(origin, "user-a")])
        assertEquals("second-rotated", credentials.values[SessionScope(origin, "user-b")]!!.value)
    }

    @Test fun `failed second login leaves the current account and persistent sessions intact`() = runBlocking {
        connectAndLogin(); repeat(4) { take() }
        val before = repository.state.value
        enqueueVersion(); server.enqueue(json("{}", 401))
        assertEquals(FailureKind.INVALID_CREDENTIALS, failure { repository.addAccount(origin, "other@example.com", "wrong-password") }.kind)
        assertEquals(before, repository.state.value)
        assertEquals(before.profiles, preferences.value.profiles)
        assertEquals("first-cookie", credentials.values[SessionScope(origin, "user-a")]!!.value)
        take()
        assertNull(take().getHeader("Cookie"))
    }

    @Test fun `adding existing identity replaces its profile without duplicating it and survives restart`() = runBlocking {
        connectAndLogin(); repeat(4) { take() }
        enqueueVersion(); enqueueLogin("second-access", "second-cookie"); enqueueMe(); enqueueCapabilities()
        repository.addAccount(origin, "test@example.com", "synthetic-password")
        repeat(4) { take() }
        assertEquals(1, repository.state.value.profiles.size)
        enqueueVersion(); enqueueLogin("restored-access", "restored-cookie"); enqueueMe(); enqueueCapabilities()
        val restarted = newRepository()
        restarted.restore()
        assertEquals(repository.state.value.profiles, restarted.state.value.profiles)
        assertEquals("user-a", restarted.state.value.session!!.user.id)
        take()
        assertEquals("$SESSION_COOKIE=second-cookie", take().getHeader("Cookie"))
    }

    @Test fun `server conflict keeps the account and never reports deletion as successful`() = runBlocking {
        connectAndLogin(); repeat(4) { take() }
        server.enqueue(json("{}", 409))
        assertEquals(FailureKind.CONFLICT, failure { repository.deleteAccount("synthetic-password") }.kind)
        assertNotNull(repository.state.value.session)
        assertNotNull(credentials.values[SessionScope(origin, "user-a")])
    }

    @Test fun `deleting last account clears the selected profile and credentials`() = runBlocking {
        connectAndLogin(); repeat(4) { take() }
        server.enqueue(json("""{"status":"success"}"""))
        repository.deleteAccount("synthetic-password")
        assertNull(repository.state.value.session)
        assertNull(repository.state.value.selectedProfileId)
        assertTrue(preferences.value.profiles.isEmpty())
        assertTrue(credentials.values.isEmpty())
    }

    @Test fun `expired token refreshes without replaying account deletion`() = runBlocking {
        connectAndLogin(); repeat(4) { take() }
        server.enqueue(json("{}", 401)); enqueueLogin("fresh-token", "fresh-cookie")
        assertEquals(FailureKind.CONFLICT, failure { repository.deleteAccount("synthetic-password") }.kind)
        assertEquals("DELETE", take().method)
        assertEquals("$AUTH_PATH/refresh", take().path)
        assertEquals(6, server.requestCount)
        assertNotNull(repository.state.value.session)
        server.enqueue(json("""{"status":"success"}"""))
        repository.deleteAccount("synthetic-password")
        assertEquals("Bearer fresh-token", take().getHeader("Authorization"))
        assertTrue(repository.state.value.profiles.isEmpty())
    }

    @Test fun `confirmed deletion stays removed from memory even when settings write fails`() = runBlocking {
        connectAndLogin(); repeat(4) { take() }
        server.enqueue(json("""{"status":"success"}"""))
        preferences.failWrite = true
        assertEquals(FailureKind.STORAGE, failure { repository.deleteAccount("synthetic-password") }.kind)
        assertNull(repository.state.value.session)
        assertNull(repository.state.value.selectedProfile)
        assertTrue(credentials.values.isEmpty())
    }

    @Test fun `failed account persistence rolls credentials back and preserves current workspace`() = runBlocking {
        connectAndLogin(); repeat(4) { take() }
        val before = repository.state.value
        enqueueVersion(); enqueueLogin("other-token", "other-cookie", "user-b")
        server.enqueue(json(ME.replace("user-a", "user-b"))); enqueueCapabilities()
        preferences.failWrite = true
        assertEquals(FailureKind.STORAGE, failure { repository.addAccount(origin, "other@example.com", "synthetic-password") }.kind)
        assertEquals(before, repository.state.value)
        assertEquals(setOf(SessionScope(origin, "user-a")), credentials.values.keys)
        assertEquals("first-cookie", credentials.values.values.single().value)
    }

    @Test fun `wrong password does not remove account or local credentials`() = runBlocking {
        connectAndLogin()
        repeat(4) { take() }
        server.enqueue(json("{}", 422))
        val failure = failure { repository.deleteAccount("wrong-password") }
        assertEquals(FailureKind.INVALID_CREDENTIALS, failure.kind)
        assertTrue(failure.message.contains("неверный пароль", ignoreCase = true))
        assertEquals("user-a", repository.state.value.selectedProfile?.accountId)
        assertNotNull(repository.state.value.session)
        assertNotNull(credentials.values[SessionScope(origin, "user-a")])
        val request = take()
        assertEquals("DELETE", request.method)
        assertEquals("$AUTH_PATH/me", request.path)
    }

    @Test fun `redirects cannot forward login credentials to another origin`() = runBlocking {
        enqueueVersion(); repository.addServer(origin)
        val destination = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setResponseCode(307).addHeader("Location", destination.url("/capture")))
            failure { repository.login("test@example.com", "synthetic-password") }
            assertEquals(0, destination.requestCount)
            assertNull(repository.state.value.session)
        } finally { destination.shutdown() }
    }

    @Test fun `production trust refuses a self signed certificate`() = runBlocking {
        val defaultTrust = DefaultFoundationRepository(preferences, credentials, MeetSpaceApi())
        server.enqueue(json(VERSION))
        assertEquals(FailureKind.TLS, failure { defaultTrust.addServer(origin) }.kind)
        assertTrue(preferences.value.profiles.isEmpty())
    }

    @Test fun `theme and profile removal persist without credentials in settings`() = runBlocking {
        connectAndLogin()
        repository.setTheme(ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, preferences.value.theme)
        repository.removeServer(repository.state.value.selectedProfileId!!)
        assertTrue(credentials.values.isEmpty())
        assertTrue(preferences.value.profiles.isEmpty())
        assertNull(repository.state.value.session)
        assertNull(repository.state.value.selectedProfileId)
    }

    @Test fun `serialized login and server switch cannot activate a stale account`() = runBlocking {
        enqueueVersion(); val first = repository.addServer(origin)
        val second = MockWebServer().apply {
            useHttps(serverCertificates.sslSocketFactory(), false); start()
        }
        try {
            val secondOrigin = second.url("/").toString().removeSuffix("/")
            second.enqueue(json(VERSION))
            val secondProfile = repository.addServer(secondOrigin)
            enqueueVersion(); repository.selectServer(first.id)
            enqueueLogin("first-access", "first-cookie")
            server.enqueue(json(ME).setBodyDelay(200, TimeUnit.MILLISECONDS))
            enqueueCapabilities()
            second.enqueue(json(VERSION))
            val login = async { repository.login("test@example.com", "synthetic-password") }
            val select = async { repository.selectServer(secondProfile.id) }
            login.await(); select.await()
            assertEquals(secondProfile.id, repository.state.value.selectedProfileId)
            assertNull(repository.state.value.session)
            assertEquals("first-cookie", credentials.values[SessionScope(origin, "user-a")]!!.value)
            repeat(2) {
                val request = second.takeRequest(5, TimeUnit.SECONDS)!!
                assertNull(request.getHeader("Authorization")); assertNull(request.getHeader("Cookie"))
            }
        } finally { second.shutdown() }
    }

    @Test fun `cancellation during a slow response body releases repository mutex promptly`() = runBlocking {
        enqueueVersion(); repository.addServer(origin); take()
        enqueueLogin("first-access", "first-cookie")
        server.enqueue(json(ME).setBodyDelay(3, TimeUnit.SECONDS))
        val login = async { repository.login("test@example.com", "synthetic-password") }
        withContext(Dispatchers.IO) {
            assertEquals("$AUTH_PATH/login", take().path)
            assertEquals("$AUTH_PATH/me", take().path)
        }
        withTimeout(2_000) {
            login.cancelAndJoin()
            repository.setTheme(ThemeMode.LIGHT)
        }
        assertTrue(login.isCancelled)
        assertNull(repository.state.value.session)
        assertTrue(credentials.values.isEmpty())
    }

    private fun newRepository() = DefaultFoundationRepository(preferences, credentials, api)
    private suspend fun connectAndLogin() {
        enqueueVersion(); repository.addServer(origin)
        enqueueLogin("first-access", "first-cookie"); enqueueMe(); enqueueCapabilities()
        repository.login("test@example.com", "synthetic-password")
    }
    private fun enqueueVersion() { server.enqueue(json(VERSION)) }
    private fun enqueueMe() { server.enqueue(json(ME)) }
    private fun enqueueCapabilities() { server.enqueue(json(CAPABILITIES)) }
    private fun enqueueLogin(token: String, cookie: String, user: String = "user-a") {
        server.enqueue(json(loginBody(token, user))
            .addHeader("Set-Cookie", "$SESSION_COOKIE=$cookie; Path=$AUTH_PATH; Secure; HttpOnly; Max-Age=3600"))
    }
    private fun take(): RecordedRequest = server.takeRequest(5, TimeUnit.SECONDS) ?: error("Missing request")
    private suspend fun failure(block: suspend () -> Any?): AppFailure = try {
        block(); error("Expected AppFailure")
    } catch (failure: AppFailure) { failure }
    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code)
        .setHeader("Content-Type", "application/json").setBody(body)
    private fun loginBody(token: String, id: String = "user-a") = """{"status":"success","accessToken":"$token","tokenType":"Bearer","expiresIn":3600,"user":{"id":"$id","email":"test@example.com","displayName":"Test"}}"""

    private companion object {
        const val VERSION = """{"version":"dev","commit":"unknown","buildTime":"unknown"}"""
        const val ME = """{"status":"success","user":{"id":"user-a","email":"test@example.com","displayName":"Test","isAdmin":false}}"""
        const val CAPABILITIES = """{"status":"success","buildVersion":"dev","capabilities":{"liveCaptions":false,"transcription":false,"aiSummary":false,"semanticSearch":false,"meetingAnalytics":false,"recordingModes":["composite"]}}"""
    }
}

private class MemoryProfileStore : ProfileStore {
    var value = PersistedSettings()
    var failWrite = false
    override suspend fun read() = value
    override suspend fun write(settings: PersistedSettings) {
        if (failWrite) throw storageFailure()
        value = settings
    }
}

private class MemoryCredentialStore : CredentialStore {
    val values = mutableMapOf<SessionScope, StoredCredential>()
    var failDelete = false
    override suspend fun read(scope: SessionScope) = values[scope]
    override suspend fun write(scope: SessionScope, credential: StoredCredential) { values[scope] = credential }
    override suspend fun delete(scope: SessionScope) {
        if (failDelete) throw storageFailure()
        values.remove(scope)
    }
}
