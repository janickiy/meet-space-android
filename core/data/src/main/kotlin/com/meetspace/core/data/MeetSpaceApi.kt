package com.meetspace.core.data

import com.meetspace.core.model.Capabilities
import com.meetspace.core.model.ServerVersion
import com.meetspace.core.model.User
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

internal val wireJson = Json { ignoreUnknownKeys = true }
internal class HttpFailure(val statusCode: Int) : Exception()
internal class InvalidContract : Exception()

internal data class GuestCredentials(val token: String, val user: User, val conferenceId: String)

internal data class LoginCredentials(val token: String, val user: User)

/** No redirects, credential logging or TLS overrides. The injectable client is internal for HTTPS tests. */
internal class MeetSpaceApi(
    private val baseClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    internal fun recordingClient(): OkHttpClient = client(CookieJar.NO_COOKIES).newBuilder()
        .authenticator(okhttp3.Authenticator.NONE).proxyAuthenticator(okhttp3.Authenticator.NONE)
        .callTimeout(0, TimeUnit.MILLISECONDS).build()

    internal fun callSocket(origin: String, conferenceId: String, ticket: String, listener: okhttp3.WebSocketListener): okhttp3.WebSocket {
        val url = ServerOrigin.url(origin, "/api/v1").newBuilder()
            .addPathSegment("conferences").addPathSegment(conferenceId).addPathSegment("ws")
            .addQueryParameter("ticket", ticket).build()
        if (!ServerOrigin.matches(origin, url)) throw InvalidContract()
        return client(CookieJar.NO_COOKIES).newBuilder().pingInterval(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS).build().newWebSocket(
                Request.Builder().url(url).header("Sec-WebSocket-Protocol", "go-recorder.v1")
                    .header("Origin", origin).build(), listener)
    }
    internal fun userSocket(origin: String, ticket: String, listener: okhttp3.WebSocketListener): okhttp3.WebSocket {
        val url = ServerOrigin.url(origin, "/api/v1/ws").newBuilder().addQueryParameter("ticket", ticket).build()
        if (!ServerOrigin.matches(origin, url)) throw InvalidContract()
        // This server-only stream responds to server pings; its backend ignores client pings.
        return client(CookieJar.NO_COOKIES).newBuilder().pingInterval(0, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS).build().newWebSocket(
                Request.Builder().url(url).header("Origin", origin).build(), listener)
    }

    internal suspend fun upload(origin: String, segments: List<String>, token: String, bytes: ByteArray): String =
        withContext(Dispatchers.IO) {
            val url = ServerOrigin.url(origin, "/api/v1").newBuilder().apply { segments.forEach(::addPathSegment) }.build()
            if (!ServerOrigin.matches(origin, url)) throw InvalidContract()
            val request = Request.Builder().url(url).header("Authorization", "Bearer $token")
                .header("Accept", "application/json").put(bytes.toRequestBody("application/octet-stream".toMediaType())).build()
            client(CookieJar.NO_COOKIES).newBuilder().callTimeout(60, TimeUnit.SECONDS).build()
                .newCall(request).awaitJsonBody()
        }

    internal suspend fun download(url: okhttp3.HttpUrl, token: String?, expectedBytes: Long): ByteArray =
        withContext(Dispatchers.IO) {
            if (!url.isHttps || expectedBytes !in 1..MessagingApi.MAX_FILE_BYTES.toLong()) throw InvalidContract()
            val request = Request.Builder().url(url).apply { token?.let { header("Authorization", "Bearer $it") } }.build()
            client(CookieJar.NO_COOKIES).newCall(request).awaitFile(expectedBytes)
        }

    private fun client(jar: CookieJar): OkHttpClient = baseClient.newBuilder()
        .cookieJar(jar).followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).cache(null).build()

    suspend fun version(origin: String): ServerVersion {
        val response = decode<VersionDto>(get(origin, "/version", null, CookieJar.NO_COOKIES))
        if (!response.version.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+\\-]{0,63}")) ||
            !(response.commit == "unknown" || response.commit.matches(Regex("(?:[a-f0-9]{40}|[a-f0-9]{64})"))) ||
            !(response.buildTime == "unknown" || runCatching { Instant.parse(response.buildTime) }.isSuccess)) {
            throw InvalidContract()
        }
        return ServerVersion(response.version, response.commit, response.buildTime)
    }

    suspend fun login(origin: String, email: String, password: String, jar: SessionCookieJar): LoginCredentials =
        loginResponse(post(origin, "$AUTH_PATH/login", wireJson.encodeToString(LoginRequest(email, password)), null, jar))

    suspend fun register(origin: String, displayName: String, email: String, password: String): User {
        // Registration grants no session; existing credentials must never accompany this request.
        val response = decode<MeDto>(post(origin, "$AUTH_PATH/register",
            wireJson.encodeToString(RegisterRequest(email, password, displayName)), null, CookieJar.NO_COOKIES, expectedStatus = 201))
        if (response.status != "success") throw InvalidContract()
        return response.user.asAccount()
    }

    suspend fun refresh(origin: String, jar: SessionCookieJar): LoginCredentials =
        loginResponse(post(origin, "$AUTH_PATH/refresh", "{}", null, jar))

    suspend fun me(origin: String, token: String, jar: SessionCookieJar): User {
        val response = decode<MeDto>(get(origin, "$AUTH_PATH/me", token, jar))
        if (response.status != "success") throw InvalidContract()
        return response.user.asAccount()
    }

    suspend fun capabilities(origin: String, token: String, jar: SessionCookieJar): Capabilities {
        val response = decode<CapabilitiesResponse>(get(origin, "/api/v1/capabilities", token, jar))
        if (response.status != "success" || response.buildVersion.length > 64 ||
            response.buildVersion.any(Char::isISOControl) || response.capabilities.recordingModes.size > 32 ||
            response.capabilities.recordingModes.any { it.length > 64 || it.any(Char::isISOControl) }) throw InvalidContract()
        return response.capabilities.run {
            Capabilities(liveCaptions, transcription, aiSummary, semanticSearch, meetingAnalytics,
                recordingModes, response.buildVersion)
        }
    }

    suspend fun logout(origin: String, token: String?, jar: SessionCookieJar) {
        val response = decode<StatusResponse>(post(origin, "$AUTH_PATH/logout", "{}", token, jar))
        if (response.status != "success") throw InvalidContract()
    }

    suspend fun updateDisplayName(origin: String, token: String, jar: SessionCookieJar, displayName: String): User {
        val response = decode<MeDto>(authenticatedRequest(origin, listOf("auth", "me"), token,
            method = "PATCH", payload = wireJson.encodeToString(ProfileRequest(displayName)), jar = jar))
        if (response.status != "success") throw InvalidContract()
        return response.user.asAccount()
    }

    suspend fun deleteAccount(origin: String, token: String, jar: SessionCookieJar, password: String) {
        val response = decode<StatusResponse>(authenticatedRequest(origin, listOf("auth", "me"), token,
            method = "DELETE", payload = wireJson.encodeToString(DeleteAccountRequest(password)), jar = jar))
        if (response.status != "success") throw InvalidContract()
    }

    /** Every segment/query value is encoded separately and retains the selected HTTPS origin. */
    internal suspend fun authenticatedRequest(
        origin: String,
        segments: List<String>,
        token: String,
        query: Map<String, String> = emptyMap(),
        method: String = "GET",
        payload: String? = null,
        jar: CookieJar = CookieJar.NO_COOKIES,
    ): String = withContext(Dispatchers.IO) {
        val url = ServerOrigin.url(origin, "/api/v1").newBuilder().apply {
            segments.forEach(::addPathSegment)
            query.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        if (!ServerOrigin.matches(origin, url)) throw InvalidContract()
        val body = payload?.toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url(url).header("Accept", "application/json")
            .header("User-Agent", "MeetSpace-Android/0.3.0-beta")
            .header("Authorization", "Bearer $token")
            .method(method, if (method == "GET") null else body ?: "{}".toRequestBody("application/json".toMediaType()))
            .build()
        client(jar).newCall(request).awaitJsonBody(
            when {
                segments.firstOrNull() == "auth" -> MAX_RESPONSE_BYTES
                // Up to 100 conference messages can each contain 4,000 Unicode characters
                // plus a reply preview and bounded attachment metadata.
                segments.firstOrNull() == "conferences" && segments.getOrNull(2) == "messages" -> 4L * 1024 * 1024
                segments.firstOrNull() == "conversations" || "recordings" in segments -> 2L * 1024 * 1024
                else -> MAX_MEETING_RESPONSE_BYTES
            })
    }

    internal suspend fun joinGuest(origin: String, code: String, displayName: String): GuestCredentials {
        // code has already been validated by MeetingInvites; no cookies or account bearer are sent.
        val response = decode<LoginDto>(post(origin, "/api/v1/conference-invites/$code/guest",
            wireJson.encodeToString(ProfileRequest(displayName)), null, CookieJar.NO_COOKIES))
        val conferenceId = response.user.guestConferenceId ?: throw InvalidContract()
        if (!MessagingApi.validId(conferenceId)) throw InvalidContract()
        if (response.status != "success" || !response.tokenType.equals("Bearer", true) || response.expiresIn !in 1..86400 ||
            response.accessToken.isBlank() || response.accessToken.length > 16384 || response.accessToken.any { it.isWhitespace() || it.isISOControl() } || response.user.isAdmin) throw InvalidContract()
        return GuestCredentials(response.accessToken, response.user.copy(guestConferenceId = null).asAccount(), conferenceId)
    }

    private fun loginResponse(body: String): LoginCredentials {
        val response = decode<LoginDto>(body)
        if (response.status != "success" || !response.tokenType.equals("Bearer", ignoreCase = true) ||
            response.expiresIn <= 0 || response.accessToken.isBlank() || response.accessToken.length > 16_384 ||
            response.accessToken.any { it.isWhitespace() || it.isISOControl() }) throw InvalidContract()
        return LoginCredentials(response.accessToken, response.user.asAccount())
    }

    private suspend fun get(origin: String, path: String, token: String?, jar: CookieJar): String =
        execute(origin, path, token, jar, null)

    private suspend fun post(origin: String, path: String, body: String, token: String?, jar: CookieJar, expectedStatus: Int? = null): String =
        execute(origin, path, token, jar, body, expectedStatus)

    private suspend fun execute(origin: String, path: String, token: String?, jar: CookieJar, payload: String?, expectedStatus: Int? = null): String =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(ServerOrigin.url(origin, path))
                .header("Accept", "application/json")
                .header("User-Agent", "MeetSpace-Android/0.3.0-beta")
            token?.let { request.header("Authorization", "Bearer $it") }
            payload?.let { request.post(it.toRequestBody("application/json; charset=utf-8".toMediaType())) }
            client(jar).newCall(request.build()).awaitJsonBody(expectedStatus = expectedStatus)
        }

    private inline fun <reified T> decode(body: String): T = try {
        wireJson.decodeFromString<T>(body)
    } catch (_: SerializationException) {
        throw InvalidContract()
    } catch (_: IllegalArgumentException) {
        throw InvalidContract()
    }

}

private const val MAX_RESPONSE_BYTES = 128L * 1024
private const val MAX_MEETING_RESPONSE_BYTES = 512L * 1024

private suspend fun Call.awaitJsonBody(maxResponseBytes: Long = MAX_RESPONSE_BYTES, expectedStatus: Int? = null): String = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, error: IOException) {
            if (continuation.isActive) continuation.resumeWithException(error)
        }
        override fun onResponse(call: Call, response: Response) {
            // Keep the cancellable continuation alive through the body read, not just headers.
            try {
                val text = response.use {
                    if (!it.isSuccessful) throw HttpFailure(it.code)
                    if (expectedStatus != null && it.code != expectedStatus) throw InvalidContract()
                    if (it.code == 204) return@use "{}"
                    val body = it.body ?: throw InvalidContract()
                    if (body.contentType()?.subtype != "json") throw InvalidContract()
                    val source = body.source()
                    source.request(maxResponseBytes + 1)
                    if (source.buffer.size > maxResponseBytes) throw InvalidContract()
                    source.buffer.readUtf8()
                }
                continuation.resume(text)
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
    })
}

@Serializable
private data class VersionDto(val version: String, val commit: String, val buildTime: String)
@Serializable
private data class LoginRequest(val email: String, val password: String)
@Serializable
private data class RegisterRequest(val email: String, val password: String, val displayName: String)
@Serializable
private data class ProfileRequest(val displayName: String)
@Serializable
private data class DeleteAccountRequest(val password: String)
@Serializable
private data class UserDto(
    val id: String,
    val email: String,
    val displayName: String? = null,
    val isAdmin: Boolean = false,
    val guestConferenceId: String? = null,
    val createdAt: String? = null,
) {
    fun asAccount(): User {
        if (id.isBlank() || id.length > 128 || id.any(Char::isISOControl) || email.isBlank() ||
            email.length > 254 || email.any(Char::isISOControl) || guestConferenceId != null ||
            (displayName?.length ?: 0) > 256 || displayName?.any(Char::isISOControl) == true) throw InvalidContract()
        return User(id, email, displayName, isAdmin, createdAt?.takeIf { it.length <= 64 && runCatching { java.time.Instant.parse(it) }.isSuccess })
    }
}
@Serializable
private data class LoginDto(
    val status: String,
    val accessToken: String,
    val tokenType: String,
    val expiresIn: Long,
    val user: UserDto,
)
@Serializable
private data class MeDto(val status: String, val user: UserDto)
@Serializable
private data class CapabilitiesDto(
    val liveCaptions: Boolean,
    val transcription: Boolean,
    val aiSummary: Boolean,
    val semanticSearch: Boolean,
    val meetingAnalytics: Boolean,
    val recordingModes: List<String>,
)
@Serializable
private data class CapabilitiesResponse(
    val status: String,
    val capabilities: CapabilitiesDto,
    val buildVersion: String,
)
@Serializable
private data class StatusResponse(val status: String)

/** The response stays cancellable while its bounded body is read. No body is persisted or logged. */
private suspend fun Call.awaitFile(expectedBytes: Long): ByteArray = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            try {
                val bytes = response.use {
                    if (!it.isSuccessful) throw HttpFailure(it.code)
                    val body = it.body ?: throw InvalidContract()
                    if (body.contentLength() > expectedBytes) throw InvalidContract()
                    val source = body.source()
                    source.request(expectedBytes + 1)
                    if (source.buffer.size != expectedBytes) throw InvalidContract()
                    source.buffer.readByteArray()
                }
                if (continuation.isActive) continuation.resume(bytes) else bytes.fill(0)
            } catch (failure: Exception) {
                if (continuation.isActive) continuation.resumeWithException(failure)
            }
        }
    })
}
