package com.meetspace.app

import android.Manifest
import android.media.AudioFormat
import android.os.Build
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.meetspace.app.ui.CallScreen
import com.meetspace.core.data.*
import com.meetspace.core.designsystem.MeetSpaceTheme
import com.meetspace.core.media.*
import com.meetspace.core.model.*
import java.io.File
import java.net.URI
import java.nio.ByteOrder
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.time.Instant
import java.util.Properties
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.webrtc.audio.JavaAudioDeviceModule

/** Two production CallEngines through the real Pion SFU on a disposable loopback fixture.
 * Input is synthetic PCM, never the host microphone. No production account/server is used.
 * Configure sfu-speaking.properties + sfu-speaking-ca.pem in the emulator's app files directory.
 */
@OptIn(ExperimentalTestApi::class)
class RemoteSpeakingSfuTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun receivedVoiceDrivesRealParticipantTileThroughSfuMuteAndReconnect() = verifyVoice(100, false)
    @Test fun receivedVoiceRemainsVisibleWhenLocalSpeakerVolumeIsZero() = verifyVoice(0, false)
    @Test fun receivedVoiceRemainsVisibleWhenPresenceUpdateFails() = verifyVoice(100, true)

    private fun verifyVoice(speakerVolume: Int, failPresence: Boolean) {
        val config = File(context.filesDir, "sfu-speaking.properties")
        assumeTrue(config.isFile && (Build.FINGERPRINT.startsWith("generic") || Build.MODEL.contains("sdk", true)))
        val settings = Properties().apply { config.inputStream().use(::load) }
        val origin = settings.getProperty("origin")
        check(URI(origin).host in setOf("10.0.2.2", "127.0.0.1", "localhost"))
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val sourceBackend = Fixture(origin, SOURCE)
        val listenerBackend = Fixture(origin, LISTENER)
        val source = CallEngine(context, sourceBackend, sourceBackend)
        val listener = CallEngine(context, listenerBackend, listenerBackend)
        val amplitude = AtomicInteger(0)
        var viewport by mutableStateOf(DpSize(1194.dp, 834.dp))
        var phase = 0.0
        try {
            sourceBackend.failPresence(failPresence)
            source.configureDevices(CallDeviceSettings(microphoneVolume = 100, speakerVolume = 0, noiseSuppression = false))
            listener.configureDevices(CallDeviceSettings(speakerVolume = speakerVolume))
            source.prepare(sourceBackend.meeting)
            await("sender prejoin", source) { it.phase == CallPhase.PREJOIN }
            actor(source) {
                // Two synthetic endpoints share one test process/device. The listener's
                // audio focus must not mute the simulated remote device's source.
                val audio = field<Any>(source, "audio")
                audio.javaClass.getDeclaredField("onFocusLost").apply { isAccessible = true }.set(audio, {})
                CallEngine::class.java.getDeclaredMethod("ensureFactory").apply { isAccessible = true }.invoke(source)
                val adm = field<JavaAudioDeviceModule>(source, "audioModule")
                val capture = JavaAudioDeviceModule.AudioBufferCallback { buffer, format, channels, rate, bytes, timestamp ->
                    check(format == AudioFormat.ENCODING_PCM_16BIT && channels == 1)
                    buffer.order(ByteOrder.nativeOrder())
                    for (index in 0 until minOf(bytes, buffer.capacity()) / 2) {
                        buffer.putShort(index * 2, (amplitude.get() * sin(phase)).toInt().toShort())
                        phase = (phase + 2 * PI * 440 / rate) % (2 * PI)
                    }
                    timestamp
                }
                field<Any>(adm, "audioInput").javaClass.getDeclaredField("audioBufferCallback")
                    .apply { isAccessible = true }.set(field<Any>(adm, "audioInput"), capture)
            }
            source.microphone(true)
            await("sender microphone armed", source) { it.microphone }
            source.connect()
            await("sender connected", source) { it.phase == CallPhase.CONNECTED }
            listener.prepare(listenerBackend.meeting)
            await("listener prejoin", listener) { it.phase == CallPhase.PREJOIN }
            actor(listener) {
                // Production has one endpoint per process. Use a separate receiver APM
                // here so the SDK's process-global external APM cannot feed this synthetic
                // remote voice into the sender's own echo canceller.
                CallEngine::class.java.getDeclaredMethod("ensureFactory").apply { isAccessible = true }.invoke(listener)
                field<org.webrtc.PeerConnectionFactory>(listener, "factory").dispose()
                val egl = field<org.webrtc.EglBase>(listener, "egl").eglBaseContext
                val factory = org.webrtc.PeerConnectionFactory.builder()
                    .setAudioDeviceModule(field<JavaAudioDeviceModule>(listener, "audioModule"))
                    .setVideoEncoderFactory(org.webrtc.DefaultVideoEncoderFactory(egl, false, false))
                    .setVideoDecoderFactory(org.webrtc.DefaultVideoDecoderFactory(egl)).createPeerConnectionFactory()
                CallEngine::class.java.getDeclaredField("factory").apply { isAccessible = true }.set(listener, factory)
            }
            listener.connect()
            await("listener connected", listener) { it.phase == CallPhase.CONNECTED }
            compose.setContent {
                val state by listener.state.collectAsState()
                DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(viewport)) {
                    MeetSpaceTheme { CallScreen(state) }
                }
            }
            amplitude.set(3000)
            await("remote quiet voice", listener) { it.microphoneLevel(SOURCE) > .02f }
            val quiet = listener.state.value.microphoneLevel(SOURCE)
            val mic = "call-participant-$SOURCE-mic"
            val tile = "call-participant-$SOURCE"
            awaitGreen(mic, tile)
            amplitude.set(12000)
            await("louder remote voice", listener) { it.microphoneLevel(SOURCE) > quiet * 1.6f }
            awaitGreen(mic, tile)
            assertFalse("The listener microphone remains off", listener.state.value.microphone)

            compose.runOnIdle { viewport = DpSize(834.dp, 1194.dp) }
            compose.onNodeWithTag("call-layout-gallery").assertIsSelected()
            awaitGreen(mic, tile)
            compose.onNodeWithTag("call-layout-speaker").performClick()
            awaitGreen(mic, tile)
            compose.runOnIdle { viewport = DpSize(1194.dp, 834.dp) }
            compose.onNodeWithTag("call-layout-speaker").assertIsSelected()

            // An explicit mute must win even before SFU removes the old publication.
            listenerBackend.mediaState(SOURCE, false)
            await("explicit remote mute clears old source", listener) { !it.isSpeaking(SOURCE) }
            Thread.sleep(250) // Let several fresh PCM blocks arrive from the still-published source.
            assertEquals(0f, listener.state.value.microphoneLevel(SOURCE), 0f)
            compose.onNodeWithTag(mic, useUnmergedTree = true).assertContentDescriptionEquals("Без звука")
            assertEquals(0, greenPixels(mic))
            listenerBackend.mediaState(SOURCE, true)
            await("explicit unmute restores remote peaks", listener) { it.microphoneLevel(SOURCE) > .02f }

            amplitude.set(0)
            await("silence clears remote voice", listener) { !it.isSpeaking(SOURCE) && it.microphoneLevel(SOURCE) < .012f }
            assertEquals(0, greenPixels(mic))
            assertFalse(greenBorder(tile))

            amplitude.set(6000)
            await("speech resumes", listener) { it.isSpeaking(SOURCE) }
            sourceBackend.failPresence(false)
            source.microphone(false)
            await("remote mute", listener) { !it.isSpeaking(SOURCE) && it.participants.any { p -> p.id == SOURCE && !p.microphone } }
            compose.waitUntil(5_000) {
                compose.onAllNodes(hasTestTag(mic) and hasContentDescription("Без звука"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag(mic, useUnmergedTree = true).assertContentDescriptionEquals("Без звука")
            assertEquals(0, greenPixels(mic))
            source.microphone(true)
            await("remote microphone recreated", listener) { it.microphoneLevel(SOURCE) > .02f }
            val opens = listenerBackend.opened.get()
            listener.reconnectMeeting()
            await("listener rejoins and measures speech", listener) { listenerBackend.opened.get() > opens && it.phase == CallPhase.CONNECTED && it.microphoneLevel(SOURCE) > .02f }
            awaitGreen(mic, tile)
        } finally {
            amplitude.set(0)
            sourceBackend.failPresence(false)
            source.leave(false); listener.leave(false)
            await("sender cleaned up", source) { it.phase == CallPhase.IDLE }
            await("listener cleaned up", listener) { it.phase == CallPhase.IDLE }
            sourceBackend.close(); listenerBackend.close()
        }
    }

    private fun greenPixels(tag: String): Int {
        val pixels = compose.onNodeWithTag(tag, useUnmergedTree = true).captureToImage().toPixelMap()
        return (0 until pixels.width).sumOf { x -> (0 until pixels.height).count { y ->
            pixels[x, y].let { it.green > it.red + .15f && it.green > it.blue + .1f }
        } }
    }
    private fun awaitGreen(mic: String, tile: String) {
        // StateFlow delivery/layout can trail the audio thread; inspect actual rendered
        // frames instead of assuming that the first sampled frame is already up to date.
        compose.waitUntil(5_000) { greenPixels(mic) > 0 && greenBorder(tile) }
    }
    private fun greenBorder(tag: String): Boolean {
        val image = compose.onNodeWithTag(tag, useUnmergedTree = true).captureToImage().toPixelMap()
        return (0 until minOf(5, image.height)).any { y ->
            image[image.width / 2, y].let { it.green > it.red + .1f && it.green > it.blue + .1f }
        }
    }
    private fun await(label: String, engine: CallEngine, predicate: (CallState) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate(engine.state.value)) return
            if (engine.state.value.phase == CallPhase.FAILED) fail("$label: ${engine.state.value.error}")
            Thread.sleep(100)
        }
        val state = engine.state.value
        // Metadata only; never save SDP, credentials, chat or audio samples.
        File(context.cacheDir, "sfu-speaking-failure.txt").writeText("$label phase=${state.phase} participants=${state.participants} levels=${state.microphoneLevels} received=${state.diagnostics.receivedAudioBytes}")
        actor(engine) {
            val detail = File(context.cacheDir, "sfu-speaking-bindings.txt")
            detail.writeText("bindings=${field<Any>(engine, "remoteMicrophones")}\ntracks=${field<Any>(engine, "remoteTracksByMid")}\ncatalog=${field<Any>(engine, "catalog")}\n")
            field<org.webrtc.PeerConnection?>(engine, "pc")?.getStats { report ->
                detail.appendText(report.statsMap.values.filter { it.type == "inbound-rtp" && it.members["kind"] == "audio" }.joinToString("\n"))
            }
        }
        fail("Timed out: $label; phase=${state.phase}; receivedAudio=${state.diagnostics.receivedAudioBytes}; mic=${state.participants.map { it.microphone }}")
    }
    private fun <T> actor(engine: CallEngine, operation: () -> T): T {
        val result = CompletableDeferred<T>()
        val action: suspend () -> Unit = { try { result.complete(operation()) } catch (error: Throwable) { result.completeExceptionally(error) } }
        CallEngine::class.java.getDeclaredMethod("enqueue", kotlin.jvm.functions.Function1::class.java)
            .apply { isAccessible = true }.invoke(engine, action)
        return runBlocking { withTimeout(10_000) { result.await() } }
    }
    @Suppress("UNCHECKED_CAST")
    private fun <T> field(owner: Any, name: String): T = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner) as T

    private inner class Fixture(private val origin: String, private val participant: String) : CallRepository, MeetingsRepository {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val json = Json { ignoreUnknownKeys = true }
        private val client: OkHttpClient
        val opened = AtomicInteger()
        val meeting = Meeting(CONFERENCE, SOURCE, "SFU speaking fixture", "", "", "active", Instant.now(), Instant.now())
        init {
            val ca = File(context.filesDir, "sfu-speaking-ca.pem").inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
            val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("fixture", ca) }
            val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }.trustManagers.single() as X509TrustManager
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
            client = OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory, trust).followRedirects(false).followSslRedirects(false).build()
        }
        private suspend fun request(path: String, body: String? = null): JsonElement = withContext(Dispatchers.IO) {
            val request = Request.Builder().url("$origin/$path").apply {
                if (body != null) post(body.toRequestBody("application/json".toMediaType()))
            }.build()
            client.newCall(request).execute().use {
                check(it.isSuccessful) { "Local SFU fixture HTTP ${it.code}" }
                val text = it.body!!.string()
                if (text.isBlank()) JsonNull else json.parseToJsonElement(text)
            }
        }
        override suspend fun openCall(conferenceId: String): CallSignaling {
            val data = request("open?participantId=$participant").jsonObject
            opened.incrementAndGet()
            val connection = data.getValue("connectionId").jsonPrimitive.content
            val inbound = Channel<String>(256)
            val outbound = Channel<String>(256)
            val session = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
            session.launch {
                try { while (isActive) request("events?connectionId=$connection").jsonArray.forEach { inbound.send(it.toString()) } }
                catch (error: Exception) { inbound.close(error) }
            }
            session.launch { for (message in outbound) request("send?connectionId=$connection", message) }
            return object : CallSignaling {
                override val iceConfiguration = data.getValue("iceConfiguration").let { if (it is JsonPrimitive) it.content else it.toString() }
                override val messages = inbound
                override fun send(envelope: String) = outbound.trySend(envelope).isSuccess
                override suspend fun presence(connectionId: String, sequence: Long, microphone: Boolean, camera: Boolean, screen: Boolean) {
                    request("presence?connectionId=$connection", buildJsonObject {
                        put("sequence", sequence); put("microphoneEnabled", microphone); put("cameraEnabled", camera); put("screenSharing", screen)
                    }.toString())
                }
                override fun close() {
                    session.cancel(); inbound.close(); outbound.close()
                    scope.launch { request("close?connectionId=$connection", "{}") }
                }
            }
        }
        fun failPresence(fail: Boolean) = runBlocking { request("presence-mode?ignore=$fail", "{}") }
        fun mediaState(id: String, microphone: Boolean) = runBlocking { request("media-state?participantId=$id&microphone=$microphone", "{}") }
        fun close() { runBlocking { delay(300) }; scope.cancel(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
        private fun member() = MeetingParticipant(participant, CONFERENCE, participant, "Fixture participant", "member", "joined", "admitted")
        override suspend fun conference(id: String) = meeting
        override suspend fun membership(id: String) = member()
        override suspend fun joinMeeting(id: String, inviteCode: String?) = member()
        override suspend fun leaveMeeting(id: String) = member()
        override suspend fun createMeeting(input: MeetingInput): Meeting = error("Not used")
        override suspend fun timeline(query: MeetingsQuery): MeetingPage = error("Not used")
        override suspend fun scheduleMeeting(id: String, scheduledAt: Instant, plannedDurationMin: Int?): Meeting = error("Not used")
        override suspend fun transitionMeeting(id: String, transition: MeetingTransition): Meeting = error("Not used")
        override suspend fun joinInvite(rawCodeOrUrl: String): MeetingParticipant = error("Not used")
        override suspend fun history(id: String): MeetingHistory = error("Not used")
        override suspend fun updateDisplayName(displayName: String): User = error("Not used")
    }
    companion object {
        private const val CONFERENCE = "00000000-0000-4000-8000-000000000001"
        private const val SOURCE = "00000000-0000-4000-8000-000000000011"
        private const val LISTENER = "00000000-0000-4000-8000-000000000012"
    }
}
