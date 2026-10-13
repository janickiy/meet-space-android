package com.meetspace.app

import android.Manifest
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.meetspace.core.data.*
import com.meetspace.core.media.CallEngine
import com.meetspace.core.media.CallPhase
import com.meetspace.core.model.*
import java.io.File
import java.lang.reflect.Proxy
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.Collections
import java.util.Properties
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in: run concurrently on TWO dedicated emulators with -e role caller/callee.
 * Uses real production data repository, HTTPS/WSS, durable calls and remote Pion SFU/TURN.
 * Only private fixture trust, RAM credential stores and transport-disconnect inspection are test bridges.
 */
class BetaDirectCallsNativeTest {
    @Test fun acceptedDirectCallOwnsNativeMediaReconnectsAndStops(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val role = InstrumentationRegistry.getArguments().getString("role")
        assumeTrue(role in setOf("caller", "callee"))
        assumeTrue(File(context.filesDir, "beta-direct-calls.properties").isFile)
        val fixture = DirectNativeFixture(context, role!!)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        val engine = CallEngine(context, fixture.repository, fixture.foundation as MeetingsRepository)
        val clientId = UUID.randomUUID().toString()
        val result = JSONObject().put("role", role).put("actualProductionDataRepository", true)
            .put("realHTTPSWebSocketSFU", true).put("physicalDevice", false)
            .put("success", false)
        var call: DirectCall? = null
        var events: UserEvents? = null
        var renewal: Job? = null
        val leaseErrors = AtomicInteger()
        try {
            fixture.login()
            val userEvents = (fixture.foundation as MessagingRepository).openUserEvents()
            events = userEvents
            assertEquals("connected", withTimeout(10_000) { userEvents.events.receive() }.type)
            ActivityScenario.launch(ComponentActivity::class.java).use {
                val ownId = fixture.foundation.state.value.session!!.user.id
                if (role == "caller") {
                    val target = requireNotNull(fixture.properties.getProperty("calleeUserId")
                        ?: fixture.properties.getProperty("peerUserId"))
                    val requestId = UUID.randomUUID().toString()
                    call = fixture.repository.startDirectCall(target, requestId, clientId)
                    val pending = call!!
                    assertEquals(pending.id, fixture.repository.startDirectCall(target, requestId, clientId).id)
                    engine.prepareDirectPreview(pending.id, pending.peer(ownId)!!.displayName)
                    waitFor(context, engine, "caller-preview-ready") { engine.state.value.phase == CallPhase.PREJOIN }
                    engine.microphone(false); engine.camera(true)
                    waitFor(context, engine, "explicit-local-camera-preview") { engine.state.value.localVideo != null }
                    assertEquals("Preview must not open a conference socket", 0, fixture.repository.mediaSessions.get())
                    assertNull(field<Any?>(engine, "pc"))
                    assertFalse(engine.state.value.microphone)
                    result.put("explicitPreviewWithoutPublication", true)
                    var accepted: DirectCall? = null
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40)
                    while (System.nanoTime() < deadline) {
                        accepted = fixture.repository.directCall(pending.id)
                        if (accepted.status != "ringing") break
                        delay(1_000)
                    }
                    check(accepted?.status == "accepted") { "Callee did not accept the fixture call" }
                    call = accepted
                } else {
                    val expectedCaller = requireNotNull(fixture.properties.getProperty("callerUserId"))
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
                    while (System.nanoTime() < deadline) {
                        call = fixture.repository.activeDirectCall()
                        if (call != null) break
                        delay(1_000)
                    }
                    val incoming = checkNotNull(call) { "No incoming fixture call" }
                    assertEquals("ringing", incoming.status)
                    assertEquals(expectedCaller, incoming.callerUserId); assertEquals(ownId, incoming.calleeUserId)
                    assertEquals(0, fixture.repository.mediaSessions.get())
                    assertNull(field<Any?>(engine, "factory"))
                    // Give the other device enough time to exercise its explicit pre-answer camera preview.
                    delay(8_000)
                    call = fixture.repository.directCallAction(incoming.id, DirectCallAction.ACCEPT, clientId)
                    assertEquals(clientId, call!!.acceptedByClientId)
                    result.put("incomingBeforeMedia", true)
                }
                val accepted = checkNotNull(call)
                assertTrue(accepted.ownsMedia(ownId, clientId)); assertEquals("accepted", accepted.status)
                val seenEvent = generateSequence { userEvents.events.tryReceive().getOrNull() }
                    .any { it.type == "call.updated" && it.callId == accepted.id }
                result.put("incomingCallUpdatedEventObserved", seenEvent)
                renewal = launch(Dispatchers.IO) {
                    while (isActive) {
                        try { fixture.repository.directCallAction(accepted.id, DirectCallAction.HEARTBEAT, clientId) }
                        catch (_: Exception) { leaseErrors.incrementAndGet() }
                        delay(7_000)
                    }
                }
                engine.joinDirect(accepted.conferenceId!!, accepted.peer(ownId)!!.displayName, clientId,
                    microphone = role == "callee", camera = role == "caller", callId = accepted.id)
                waitFor(context, engine, "direct-connected", 60_000) { engine.state.value.phase == CallPhase.CONNECTED }
                waitFor(context, engine, "native-SFU-RTP", 60_000) {
                    val state = engine.state.value
                    state.participants.count { it.online } == 2 && state.diagnostics.relay &&
                        if (role == "caller") state.camera && !state.microphone && state.diagnostics.receivedAudioBytes > 0 && state.diagnostics.sentBytes > 1_000
                        else state.microphone && !state.camera && state.diagnostics.decodedFrames > 5 && state.diagnostics.sentBytes > 0
                }
                result.put("initialRtp", diagnostics(engine))
                assertEquals(0, fixture.trace.ordinaryMeetingRequests.get())
                assertTrue(fixture.trace.ticketClientIds.contains(clientId))
                val initialRemoteVideo = if (role == "callee") {
                    waitFor(context, engine, "initial-authoritative-video-feed") {
                        val state = engine.state.value
                        val remote = state.participants.singleOrNull { it.online && it.id != state.participantId }
                        state.videos.singleOrNull { !it.screen }?.participantId == remote?.id && remote != null
                    }
                    engine.state.value.videos.single { !it.screen }.also {
                        result.put("initialVideoCatalogId", it.id).put("remoteParticipantId", it.participantId)
                    }
                } else null
                if (role == "caller") {
                    val wrongClient = UUID.randomUUID().toString()
                    val sessionsBefore = fixture.repository.mediaSessions.get()
                    try {
                        (fixture.foundation as CallRepository).openDirectCall(accepted.conferenceId!!, wrongClient)
                        fail("Server accepted a media ticket from the wrong endpoint")
                    } catch (failure: AppFailure) { assertTrue(failure.kind in setOf(FailureKind.FORBIDDEN, FailureKind.NOT_FOUND, FailureKind.CONFLICT)) }
                    val probe = CallEngine(context, fixture.repository, fixture.foundation as MeetingsRepository)
                    try {
                        probe.joinDirect(accepted.conferenceId!!, "Wrong endpoint probe", wrongClient, false, false, accepted.id)
                        waitFor(context, probe, "wrong-endpoint-rejected", allowFailure = true) { probe.state.value.phase == CallPhase.FAILED }
                        assertNull(field<Any?>(probe, "factory")); assertEquals(sessionsBefore, fixture.repository.mediaSessions.get())
                    } finally { dispose(probe, accepted.id) }
                    result.put("wrongEndpointRejectedBeforeNativeCapture", true)
                    delay(3_000)
                    val oldSessionCount = fixture.repository.mediaSessions.get()
                    fixture.repository.disconnectTransport()
                    waitFor(context, engine, "direct-reconnecting") { engine.state.value.phase == CallPhase.RECONNECTING }
                    waitFor(context, engine, "direct-reconnected", 60_000) {
                        engine.state.value.phase == CallPhase.CONNECTED && fixture.repository.mediaSessions.get() > oldSessionCount
                    }
                    waitFor(context, engine, "restored-native-SFU-RTP", 60_000) {
                        val state = engine.state.value
                        state.camera && !state.microphone && !state.sharing && state.diagnostics.relay &&
                            state.diagnostics.receivedAudioBytes > 0 && state.diagnostics.sentBytes > 1_000
                    }
                    result.put("reconnectedRtp", diagnostics(engine)).put("deviceSelectionRetained", true)
                        .put("reconnectMediaSessionCount", fixture.repository.mediaSessions.get())
                    // The other device must receive the new catalog publication on its existing
                    // native receiver, then observe a real removal and a new camera publication.
                    delay(10_000)
                    engine.camera(false)
                    waitFor(context, engine, "camera-disabled-after-reconnect") { !engine.state.value.camera && engine.state.value.localVideo == null }
                    delay(6_000)
                    engine.camera(true)
                    waitFor(context, engine, "camera-republished-after-reconnect") { engine.state.value.camera && engine.state.value.localVideo != null }
                    delay(15_000)
                    result.put("cameraRemovalAndRepublishExercised", true)
                    renewal?.cancelAndJoin(); renewal = null
                    call = fixture.repository.directCallAction(accepted.id, DirectCallAction.END, clientId)
                    assertEquals("ended", call!!.status)
                } else {
                    val original = checkNotNull(initialRemoteVideo)
                    waitFor(context, engine, "new-video-feed-on-retained-receiver", 60_000) {
                        engine.state.value.videos.any { !it.screen && it.id != original.id &&
                            it.participantId == original.participantId && it.feed !== original.feed }
                    }
                    val reconnectedVideo = engine.state.value.videos.single { !it.screen }
                    awaitNewVideoPackets(context, engine, "reconnected-video-decodes")
                    result.put("reconnectedVideoCatalogId", reconnectedVideo.id)
                        .put("reconnectedVideoAuthoritativeBinding", true)
                    waitFor(context, engine, "camera-catalog-removal", 45_000) { engine.state.value.videos.none { !it.screen } }
                    waitFor(context, engine, "camera-new-catalog-feed", 45_000) {
                        engine.state.value.videos.any { !it.screen && it.id != reconnectedVideo.id &&
                            it.participantId == original.participantId && it.feed !== reconnectedVideo.feed }
                    }
                    awaitNewVideoPackets(context, engine, "republished-camera-decodes")
                    result.put("cameraCatalogRemovalAndNewFeedVerified", true).put("republishedVideoRtp", diagnostics(engine))
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(150)
                    while (System.nanoTime() < deadline) {
                        val authoritative = fixture.repository.directCall(accepted.id)
                        if (!authoritative.active) { call = authoritative; break }
                        delay(3_000)
                    }
                    assertEquals("ended", call?.status)
                    renewal?.cancelAndJoin(); renewal = null
                }
                waitFor(context, engine, "server-end-closes-media", 30_000, allowFailure = true) {
                    engine.state.value.phase in setOf(CallPhase.FAILED, CallPhase.IDLE)
                }
                for (name in listOf("pc", "cameraCapture", "cameraTrack", "cameraSource", "cameraHelper", "micTrack", "micSource", "screenCapture", "factory")) {
                    assertNull("Server ending must release $name", actor(engine) { field<Any?>(engine, name) })
                }
                val final = fixture.repository.directCall(accepted.id)
                assertEquals("ended", final.status); assertTrue(final.durationSec > 0)
                val history = (fixture.foundation as MessagingRepository).messages(accepted.conversationId).items.filter { it.call?.id == accepted.id }
                assertEquals("One durable call must project exactly one history message", 1, history.size)
                assertEquals("call", history.single().kind); assertEquals("ended", history.single().call?.status)
                assertEquals(final.durationSec, history.single().call?.durationSec)
                result.put("durationSec", final.durationSec).put("historyRows", history.size)
                    .put("serverEndReleasedMedia", true).put("heartbeatErrors", leaseErrors.get())
                assertEquals(0, fixture.trace.ordinaryMeetingRequests.get())
                result.put("ordinaryMeetingRequests", 0).put("success", true)
            }
        } catch (failure: Throwable) {
            result.put("failure", "${failure.javaClass.simpleName}: ${failure.message}")
                .put("nativePhase", engine.state.value.phase.name)
            throw failure
        } finally {
            renewal?.cancelAndJoin()
            events?.close()
            call?.let { known ->
                runCatching {
                    val current = fixture.repository.directCall(known.id)
                    if (current.active && current.ownsMedia(fixture.foundation.state.value.session!!.user.id, clientId))
                        fixture.repository.directCallAction(current.id,
                            if (current.status == "ringing") DirectCallAction.CANCEL else DirectCallAction.END, clientId)
                }
            }
            dispose(engine, call?.id)
            fixture.close()
            File(context.cacheDir, "beta-direct-native-$role.json").writeText(result.toString(2))
        }
    }

    private fun waitFor(context: Context, engine: CallEngine, step: String, timeout: Long = 35_000,
        allowFailure: Boolean = false, condition: () -> Boolean) = runBlocking {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout)
        while (System.nanoTime() < deadline) {
            File(context.cacheDir, "beta-direct-native-progress.txt").writeText("step=$step phase=${engine.state.value.phase} ${engine.state.value.diagnostics}")
            if (condition()) return@runBlocking
            if (!allowFailure && engine.state.value.phase == CallPhase.FAILED) throw AssertionError("$step: ${engine.state.value.error}")
            delay(100)
        }
        throw AssertionError("Timed out $step: phase=${engine.state.value.phase}; error=${engine.state.value.error}")
    }
    private fun diagnostics(engine: CallEngine) = engine.state.value.diagnostics.let { value ->
        JSONObject().put("relay", value.relay).put("receivedAudioBytes", value.receivedAudioBytes)
            .put("decodedFrames", value.decodedFrames).put("sentBytes", value.sentBytes)
            .put("microphone", engine.state.value.microphone).put("camera", engine.state.value.camera)
    }
    private fun awaitNewVideoPackets(context: Context, engine: CallEngine, step: String) {
        var frames = engine.state.value.diagnostics.decodedFrames
        var bytes = engine.state.value.diagnostics.receivedVideoBytes
        waitFor(context, engine, step, 30_000) {
            val latest = engine.state.value.diagnostics
            // RTP counters can reset when SFU replaces the remote SSRC. Measure a fresh
            // increase after that reset instead of attributing the old decoder's frames.
            if (latest.decodedFrames < frames || latest.receivedVideoBytes < bytes) {
                frames = latest.decodedFrames; bytes = latest.receivedVideoBytes
            }
            latest.decodedFrames > frames + 5 && latest.receivedVideoBytes > bytes
        }
    }
    private fun dispose(engine: CallEngine, callId: String?) {
        if (callId != null) engine.leaveDirect(callId) else engine.leave(updateMembership = false)
        try { actor(engine) { assertEquals(CallPhase.IDLE, engine.state.value.phase) } }
        finally { field<CoroutineScope>(engine, "scope").cancel(); field<ExecutorCoroutineDispatcher>(engine, "dispatcher").close() }
    }
    private fun <T> actor(engine: CallEngine, operation: () -> T): T {
        val result = CompletableDeferred<T>()
        val command: suspend () -> Unit = { try { result.complete(operation()) } catch (error: Throwable) { result.completeExceptionally(error) } }
        CallEngine::class.java.declaredMethods.single { it.name == "enqueue" }.apply { isAccessible = true }.invoke(engine, command)
        return runBlocking { withTimeout(15_000) { result.await() } }
    }
    @Suppress("UNCHECKED_CAST") private fun <T> field(owner: Any, name: String): T = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(owner) as T
}

private class DirectNativeFixture(context: Context, private val role: String) {
    val properties = Properties().apply { File(context.filesDir, "beta-direct-calls.properties").inputStream().use(::load) }
    private val origin = requireNotNull(properties.getProperty("origin")).also {
        require(it in setOf("https://10.0.2.2:18410", "https://127.0.0.1:18410")) { "Only the disposable local fixture is authorised" }
    }
    val trace = DirectNativeTrace()
    private val client: OkHttpClient
    val foundation: FoundationRepository
    val repository: TrackedDirectRepository
    init {
        val ca = File(context.filesDir, "beta-direct-calls-ca.pem").inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("beta-fixture", ca) }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }.trustManagers.single() as X509TrustManager
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        client = OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory, trust).followRedirects(false)
            .followSslRedirects(false).retryOnConnectionFailure(false).connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).addInterceptor { chain ->
                val request = chain.request()
                val path = request.url.encodedPath
                if (path.startsWith("/api/v1/conferences/") && !path.endsWith("/ws-ticket") &&
                    !path.endsWith("/ws") && !path.endsWith("/participants/me/media")) trace.ordinaryMeetingRequests.incrementAndGet()
                if (path.endsWith("/ws-ticket") && path.startsWith("/api/v1/conferences/")) {
                    val buffer = Buffer(); request.body?.writeTo(buffer)
                    trace.ticketClientIds.add(JSONObject(buffer.readUtf8()).optString("clientId"))
                }
                chain.proceed(request)
            }.build()
        foundation = productionRepository(client)
        repository = TrackedDirectRepository(foundation)
    }
    suspend fun login() {
        foundation.addServer(origin)
        foundation.login(requireNotNull(properties.getProperty(if (role == "caller") "email" else "peerEmail")),
            requireNotNull(properties.getProperty(if (role == "caller") "password" else "peerPassword")))
    }
    suspend fun close() {
        runCatching { foundation.logout() }
        client.dispatcher.cancelAll(); client.connectionPool.evictAll()
    }
    /** Same internal constructor bridge as LiveMessagingBackend; all production methods stay intact. */
    private fun productionRepository(client: OkHttpClient): FoundationRepository {
        val loader = FoundationRepository::class.java.classLoader
        fun type(name: String) = Class.forName("com.meetspace.core.data.$name", true, loader)
        val profilesType = type("ProfileStore")
        var settings: Any = type("PersistedSettings").getDeclaredConstructor().newInstance()
        val profiles = Proxy.newProxyInstance(loader, arrayOf(profilesType)) { proxy, method, arguments ->
            when (method.name) {
                "read" -> settings
                "write" -> { settings = arguments!![0]; Unit }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.get(0)
                "toString" -> "Private beta fixture profile store"
                else -> error("Unexpected profile operation")
            }
        }
        val credentialsType = type("CredentialStore")
        val credentials = mutableMapOf<Any, Any>()
        val credentialStore = Proxy.newProxyInstance(loader, arrayOf(credentialsType)) { proxy, method, arguments ->
            when (method.name) {
                "read" -> credentials[arguments!![0]]
                "write" -> { credentials[arguments!![0]] = arguments[1]; Unit }
                "delete" -> { credentials.remove(arguments!![0]); Unit }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.get(0)
                "toString" -> "Private beta fixture credentials"
                else -> error("Unexpected credential operation")
            }
        }
        val apiType = type("MeetSpaceApi")
        val api = apiType.getDeclaredConstructor(OkHttpClient::class.java).newInstance(client)
        return type("DefaultFoundationRepository").getDeclaredConstructor(profilesType, credentialsType, apiType)
            .newInstance(profiles, credentialStore, api) as FoundationRepository
    }
}
private class DirectNativeTrace {
    val ordinaryMeetingRequests = AtomicInteger()
    val ticketClientIds: MutableList<String> = Collections.synchronizedList(mutableListOf())
}
private class TrackedDirectRepository(private val base: FoundationRepository) :
    FoundationRepository by base, DirectCallsRepository by (base as DirectCallsRepository), CallRepository by (base as CallRepository) {
    val mediaSessions = AtomicInteger()
    @Volatile private var latest: CallSignaling? = null
    override suspend fun openDirectCall(conferenceId: String, clientId: String): CallSignaling =
        (base as CallRepository).openDirectCall(conferenceId, clientId).also { latest = it; mediaSessions.incrementAndGet() }
    fun disconnectTransport() {
        val session = checkNotNull(latest)
        (session.javaClass.getDeclaredField("socket").apply { isAccessible = true }.get(session) as WebSocket).cancel()
    }
}
