package com.meetspace.core.data

import com.meetspace.core.model.*
import java.util.concurrent.TimeUnit
import java.util.Base64
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AvatarRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: DefaultFoundationRepository
    private lateinit var origin: String
    private lateinit var certificates: HandshakeCertificates
    private lateinit var client: OkHttpClient
    private val jpeg: ByteArray get() = Base64.getDecoder().decode("/9j/4AAQSkZJRgABAQAASABIAAD/4QBMRXhpZgAATU0AKgAAAAgAAYdpAAQAAAABAAAAGgAAAAAAA6ABAAMAAAABAAEAAKACAAQAAAABAAAAAaADAAQAAAABAAAAAQAAAAD/7QA4UGhvdG9zaG9wIDMuMAA4QklNBAQAAAAAAAA4QklNBCUAAAAAABDUHYzZjwCyBOmACZjs+EJ+/8AAEQgAAQABAwEiAAIRAQMRAf/EAB8AAAEFAQEBAQEBAAAAAAAAAAABAgMEBQYHCAkKC//EALUQAAIBAwMCBAMFBQQEAAABfQECAwAEEQUSITFBBhNRYQcicRQygZGhCCNCscEVUtHwJDNicoIJChYXGBkaJSYnKCkqNDU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6g4SFhoeIiYqSk5SVlpeYmZqio6Slpqeoqaqys7S1tre4ubrCw8TFxsfIycrS09TV1tfY2drh4uPk5ebn6Onq8fLz9PX29/j5+v/EAB8BAAMBAQEBAQEBAQEAAAAAAAABAgMEBQYHCAkKC//EALURAAIBAgQEAwQHBQQEAAECdwABAgMRBAUhMQYSQVEHYXETIjKBCBRCkaGxwQkjM1LwFWJy0QoWJDThJfEXGBkaJicoKSo1Njc4OTpDREVGR0hJSlNUVVZXWFlaY2RlZmdoaWpzdHV2d3h5eoKDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uLj5OXm5+jp6vLz9PX29/j5+v/bAEMAAgICAgICAwICAwUDAwMFBgUFBQUGCAYGBgYGCAoICAgICAgKCgoKCgoKCgwMDAwMDA4ODg4ODw8PDw8PDw8PD//bAEMBAgICBAQEBwQEBxALCQsQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEP/dAAQAAf/aAAwDAQACEQMRAD8A/fyiiigD/9k=")
    private val png: ByteArray get() = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGP4//8/AAX+Av4N70a4AAAAAElFTkSuQmCC")

    @Before fun setup() {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        certificates = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        server = MockWebServer().apply { useHttps(certificates.sslSocketFactory(), false); start() }
        origin = server.url("/").toString().removeSuffix("/")
        client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
            .callTimeout(5, TimeUnit.SECONDS).build()
        repository = DefaultFoundationRepository(AvatarProfiles(), AvatarCredentials(), MeetSpaceApi(client))
    }
    @After fun stop() { server.shutdown() }

    @Test fun `profile upload and removal update the current user without changing identity`() = runBlocking {
        login()
        server.enqueue(json(user(VERSION)))
        val image = jpeg
        val uploaded = repository.putUserAvatar("image/jpeg", image)
        assertEquals(VERSION, uploaded.avatarVersion)
        assertEquals(uploaded, repository.state.value.session!!.user)
        val put = take()
        assertEquals("PUT", put.method); assertEquals("/api/v1/auth/me/avatar", put.path)
        assertEquals("image/jpeg", put.getHeader("Content-Type"))
        assertEquals("Bearer access", put.getHeader("Authorization")); assertNull(put.getHeader("Cookie"))
        assertArrayEquals(image, put.body.readByteArray())
        server.enqueue(json(user()))
        assertNull(repository.deleteUserAvatar().avatarVersion)
        assertNull(repository.state.value.session!!.user.avatarVersion)
        val delete = take(); assertEquals("DELETE", delete.method); assertEquals("/api/v1/auth/me/avatar", delete.path)
    }

    @Test fun `private profile and group images use fixed authenticated resource paths`() = runBlocking {
        login()
        val image = jpeg
        repeat(2) { server.enqueue(MockResponse().setHeader("Content-Type", "image/jpeg").setBody(Buffer().write(image))) }
        assertArrayEquals(image, repository.userAvatar(OTHER))
        assertArrayEquals(image, repository.groupAvatar(GROUP))
        val userRequest = take(); val groupRequest = take()
        assertEquals("/api/v1/users/$OTHER/avatar/content", userRequest.path)
        assertEquals("/api/v1/conversations/$GROUP/avatar/content", groupRequest.path)
        for (request in listOf(userRequest, groupRequest)) {
            assertEquals("Bearer access", request.getHeader("Authorization"))
            assertEquals("image/jpeg", request.getHeader("Accept")); assertNull(request.getHeader("Cookie"))
        }
    }

    @Test fun `group avatar mutation preserves group scope and reports version`() = runBlocking {
        login()
        server.enqueue(json(conversation(VERSION)))
        assertEquals(VERSION, repository.putGroupAvatar(GROUP, "image/png", png).avatarVersion)
        val put = take(); assertEquals("PUT", put.method)
        assertEquals("/api/v1/conversations/$GROUP/avatar", put.path); assertEquals("image/png", put.getHeader("Content-Type"))
        server.enqueue(json(conversation()))
        assertNull(repository.deleteGroupAvatar(GROUP).avatarVersion)
        assertEquals("DELETE", take().method)
        server.enqueue(json(conversation(VERSION).replace(GROUP, OTHER)))
        failure(FailureKind.INVALID_RESPONSE) { repository.putGroupAvatar(GROUP, "image/jpeg", jpeg) }
        assertNotNull(repository.state.value.session)
    }

    @Test fun `invalid selected image input is rejected before upload`() = runBlocking {
        login()
        for ((type, bytes) in listOf("image/svg+xml" to byteArrayOf(1), "image/png" to byteArrayOf(),
            "image/jpeg" to ByteArray(MAX_AVATAR_UPLOAD_BYTES + 1))) {
            failure(FailureKind.VALIDATION) { repository.putUserAvatar(type, bytes) }
            failure(FailureKind.VALIDATION) { repository.putGroupAvatar(GROUP, type, bytes) }
        }
        failure(FailureKind.VALIDATION) { repository.userAvatar("../auth/me") }
        failure(FailureKind.VALIDATION) { repository.groupAvatar("https://other.invalid") }
        assertEquals(4, server.requestCount)
    }

    @Test fun `avatar responses cannot substitute another account or omit a confirmed version`() = runBlocking {
        login()
        for (response in listOf(user(VERSION).replace(USER, OTHER), user(), user(VERSION).replace("avatarVersion\":\"$VERSION", "avatarVersion\":\"bad-version"))) {
            server.enqueue(json(response))
            failure(FailureKind.INVALID_RESPONSE) { repository.putUserAvatar("image/jpeg", jpeg) }
            assertEquals(USER, repository.state.value.session!!.user.id)
            assertNull(repository.state.value.session!!.user.avatarVersion)
        }
        server.enqueue(json(user(VERSION)))
        failure(FailureKind.INVALID_RESPONSE) { repository.deleteUserAvatar() }
    }

    @Test fun `image downloads reject unbounded or non image data without caching it`() = runBlocking {
        login()
        server.enqueue(MockResponse().setHeader("Content-Type", "image/jpeg").setChunkedBody(Buffer().write(ByteArray(256 * 1024 + 1)), 8192))
        failure(FailureKind.INVALID_RESPONSE) { repository.userAvatar(OTHER) }
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html>proxy</html>"))
        failure(FailureKind.INVALID_RESPONSE) { repository.groupAvatar(GROUP) }
        server.enqueue(MockResponse().setHeader("Content-Type", "image/jpeg").setBody(""))
        failure(FailureKind.INVALID_RESPONSE) { repository.userAvatar(OTHER) }
    }

    @Test fun `image redirect never forwards bearer or cookie to a different endpoint`() = runBlocking {
        login()
        val other = MockWebServer().apply { useHttps(certificates.sslSocketFactory(), false); start() }
        try {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/avatar")))
            try { repository.userAvatar(OTHER); fail("Redirect must not be followed") } catch (_: AppFailure) { }
            assertNull(other.takeRequest(100, TimeUnit.MILLISECONDS))
            assertEquals(5, server.requestCount)
        } finally { other.shutdown() }
    }

    private suspend fun login() {
        server.enqueue(json("""{"version":"dev","commit":"unknown","buildTime":"unknown"}"""))
        repository.addServer(origin)
        server.enqueue(json("""{"status":"success","accessToken":"access","tokenType":"Bearer","expiresIn":3600,"user":${userObject()}}""")
            .addHeader("Set-Cookie", "$SESSION_COOKIE=session-cookie; Path=$AUTH_PATH; Secure; HttpOnly; Max-Age=3600"))
        server.enqueue(json(user()))
        server.enqueue(json("""{"status":"success","buildVersion":"dev","capabilities":{"recordingModes":["composite"]}}"""))
        repository.login("test@example.com", "synthetic-password")
        repeat(4) { take() }
    }
    private fun userObject(version: String? = null) = """{"id":"$USER","email":"test@example.com","displayName":"Test","isAdmin":false,"avatarVersion":${version?.let { "\"$it\"" } ?: "null"}}"""
    private fun user(version: String? = null) = """{"status":"success","user":${userObject(version)}}"""
    private fun conversation(version: String? = null) = """{"status":"success","item":{"id":"$GROUP","type":"group","name":"Team","description":"","myRole":"owner","memberCount":2,"preview":"","unreadCount":0,"notificationsEnabled":true,"createdAt":"2026-10-30T00:00:00Z","updatedAt":"2026-10-30T00:00:00Z","avatarVersion":${version?.let { "\"$it\"" } ?: "null"}}}"""
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun take(): RecordedRequest = server.takeRequest(5, TimeUnit.SECONDS) ?: error("Missing request")
    private suspend fun failure(expected: FailureKind, block: suspend () -> Any?) {
        try { block(); fail("Expected $expected") } catch (failure: AppFailure) { assertEquals(expected, failure.kind) }
    }
    private companion object {
        const val USER = "00000000-0000-4000-8000-000000000001"
        const val GROUP = "00000000-0000-4000-8000-000000000002"
        const val OTHER = "00000000-0000-4000-8000-000000000003"
        const val VERSION = "00000000-0000-4000-8000-000000000004"
    }
}

private class AvatarProfiles : ProfileStore {
    var value = PersistedSettings()
    override suspend fun read() = value
    override suspend fun write(settings: PersistedSettings) { value = settings }
}
private class AvatarCredentials : CredentialStore {
    private val values = mutableMapOf<SessionScope, StoredCredential>()
    override suspend fun read(scope: SessionScope) = values[scope]
    override suspend fun write(scope: SessionScope, credential: StoredCredential) { values[scope] = credential }
    override suspend fun delete(scope: SessionScope) { values.remove(scope) }
}
