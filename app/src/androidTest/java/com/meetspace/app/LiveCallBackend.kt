package com.meetspace.app

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.runner.AndroidJUnitRunner
import com.meetspace.core.data.*
import com.meetspace.core.media.CallEngine
import com.meetspace.core.model.*
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.time.Instant
import java.util.Properties
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class MeetSpaceTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
        super.newApplication(LiveCallApplication::class.java.classLoader, LiveCallApplication::class.java.name, context)
}
class LiveCallApplication : MeetSpaceApplication() {
    val liveRecordingsBackend by lazy { LiveRecordingsBackend(this) }
    val liveMessagingBackend by lazy { LiveMessagingBackend(this) }
    override val repository: FoundationRepository by lazy {
        if (File(filesDir, "live-recordings.properties").isFile) liveRecordingsBackend.primary
        else if (File(filesDir, "live-messaging.properties").isFile) liveMessagingBackend.primary
        else super.repository
    }
    val liveBackend by lazy { LiveCallBackend(this) }
    /** A dedicated, backend-free native engine used only by MediaProjection instrumentation. */
    @Volatile var mediaProjectionTestEngine: CallEngine? = null
    private val configuredCallEngine: CallEngine by lazy {
        if (File(filesDir, "live-call.properties").isFile)
            CallEngine(this, liveBackend, liveBackend) { stopService(Intent(this, ScreenShareService::class.java)) }
        else super.callEngine
    }
    override val callEngine: CallEngine get() = mediaProjectionTestEngine ?: configuredCallEngine
}
/** Real disposable HTTPS/WSS backend, with only the fixture CA trusted. Never included in the app APK. */
class LiveCallBackend(context: Context) : CallRepository, MeetingsRepository {
    val input = Properties().apply { File(context.filesDir, "live-call.properties").inputStream().use(::load) }
    private val origin = input.getProperty("origin")
    private val json = Json { ignoreUnknownKeys = true }
    private var token = ""
    private val client: OkHttpClient
    val eventCounts = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
    var forceIceFailure = false
    /** Opt-in quality fault: real WSS is opened, only the returned ICE document is invalid. */
    var malformedIceForQualityTest = false
    val openedSessionCount = java.util.concurrent.atomic.AtomicInteger()
    val closedSessionCount = java.util.concurrent.atomic.AtomicInteger()
    var lastSocket: WebSocket? = null
    init {
        val ca = File(context.filesDir, "live-call-ca.pem").inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("fixture", ca) }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }.trustManagers.single() as X509TrustManager
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        client = OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory, trust).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    }
    suspend fun login() { token = request("auth/login", "POST", buildJsonObject { put("email", input.getProperty("email")); put("password", input.getProperty("password")) }).s("accessToken") }
    private suspend fun request(path: String, method: String = "GET", data: JsonObject = buildJsonObject {}): JsonObject = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$origin/api/v1/$path").header("Authorization", "Bearer $token")
            .method(method, if (method == "GET") null else data.toString().toRequestBody("application/json".toMediaType())).build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Fixture API status ${response.code}" }
            json.parseToJsonElement(response.body!!.string()).jsonObject
        }
    }
    override suspend fun openCall(conferenceId: String): CallSignaling {
        val ice = if (forceIceFailure) """{"iceServers":[{"urls":["turn:127.0.0.1:9"],"username":"unreachable","credential":"synthetic"}],"iceTransportPolicy":"relay"}""" else request("webrtc/config").toString()
        val ticket = request("conferences/$conferenceId/ws-ticket", "POST").s("ticket")
        val channel = Channel<String>(128)
        val socket = client.newWebSocket(Request.Builder().url("$origin/api/v1/conferences/$conferenceId/ws?ticket=$ticket")
            .header("Sec-WebSocket-Protocol", "go-recorder.v1").build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) { val event = json.parseToJsonElement(text).jsonObject; val type = event["type"]!!.jsonPrimitive.content; if (type == "error") eventCounts.computeIfAbsent("error:" + event["data"]!!.jsonObject["code"]!!.jsonPrimitive.content) { java.util.concurrent.atomic.AtomicInteger() }.incrementAndGet(); eventCounts.computeIfAbsent(type) { java.util.concurrent.atomic.AtomicInteger() }.incrementAndGet(); channel.trySend(text) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { channel.close(CallDisconnected(true)) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { channel.close(CallDisconnected(code != 1008)); webSocket.cancel() }
        })
        lastSocket = socket
        openedSessionCount.incrementAndGet()
        val closed = java.util.concurrent.atomic.AtomicBoolean()
        return object : CallSignaling {
            override val iceConfiguration = if (malformedIceForQualityTest) """{"iceServers":[],"iceTransportPolicy":"unsupported"}""" else ice
            override val messages = channel
            override fun send(envelope: String) = socket.send(envelope)
            override fun close() { if (closed.compareAndSet(false, true)) closedSessionCount.incrementAndGet(); socket.close(1000, ""); socket.cancel(); channel.close() }
            override suspend fun presence(connectionId: String, sequence: Long, microphone: Boolean, camera: Boolean, screen: Boolean) {
                request("conferences/$conferenceId/participants/me/media", "PUT", buildJsonObject {
                    put("connectionId", connectionId); put("sequence", sequence); put("microphoneEnabled", microphone); put("cameraEnabled", camera); put("screenSharing", screen)
                })
            }
        }
    }
    private fun meeting(j: JsonObject) = Meeting(j.s("id"), j.s("ownerId"), j.s("title"), j.s("inviteCode"), "", j.s("status"), Instant.parse(j.s("createdAt")), Instant.parse(j.s("updatedAt")))
    private fun member(j: JsonObject) = MeetingParticipant(j.s("id"), j.s("conferenceId"), j.s("userId"), j.s("displayName"), j.s("role"), j.s("status"), j["admissionState"]?.jsonPrimitive?.content)
    override suspend fun conference(id: String) = meeting(request("conferences/$id").getValue("item").jsonObject)
    override suspend fun membership(id: String) = member(request("conferences/$id/participants/me").getValue("item").jsonObject)
    override suspend fun joinMeeting(id: String, inviteCode: String?) = member(request("conferences/$id/join", "POST", buildJsonObject { if (inviteCode != null) put("inviteCode", inviteCode) }).getValue("item").jsonObject)
    override suspend fun leaveMeeting(id: String) = member(request("conferences/$id/leave", "POST").getValue("item").jsonObject)
    override suspend fun createMeeting(input: MeetingInput) = meeting(request("conferences", "POST", buildJsonObject { put("title", input.title) }).getValue("item").jsonObject)
    override suspend fun transitionMeeting(id: String, transition: MeetingTransition) = meeting(request("conferences/$id/${transition.name.lowercase()}", "POST").getValue("item").jsonObject)
    override suspend fun timeline(query: MeetingsQuery): MeetingPage = error("Not used by this live media harness")
    override suspend fun scheduleMeeting(id: String, scheduledAt: Instant, plannedDurationMin: Int?): Meeting = error("Not used")
    override suspend fun joinInvite(rawCodeOrUrl: String): MeetingParticipant = error("Not used")
    override suspend fun history(id: String): MeetingHistory = error("Not used")
    override suspend fun updateDisplayName(displayName: String): User = error("Not used")
    private fun JsonObject.s(key: String) = getValue(key).jsonPrimitive.content
}
