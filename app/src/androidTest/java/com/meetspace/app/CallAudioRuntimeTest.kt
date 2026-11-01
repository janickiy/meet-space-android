package com.meetspace.app

import android.Manifest
import android.content.Context
import android.media.AudioFormat
import android.os.Build
import android.os.Process
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.meetspace.app.ui.AccountDevicePreferences
import com.meetspace.app.ui.AccountAudioPanel
import com.meetspace.core.designsystem.MeetSpaceTheme
import com.meetspace.core.media.CallDeviceSettings
import com.meetspace.core.media.CallEngine
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.DoubleAdder
import java.util.concurrent.atomic.LongAdder
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule

/** Opt-in, disposable-emulator verification of the shipped application's audio path.
 * Run the three methods separately, force-stopping the app between them (never clear data).
 * The production factory, APM, microphone source, gain and preference listener are unchanged.
 * Only the ADM's input and output observation callbacks are injected before capture starts.
 * Input is entirely synthetic; no server, physical microphone or audio recordings are used.
 */
class CallAudioRuntimeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val store get() = AccountDevicePreferencesStore(context.getSharedPreferences("account-device-preferences", Context.MODE_PRIVATE))
    private val engine get() = (context.applicationContext as MeetSpaceApplication).callEngine
    private val directory get() = File(context.getExternalFilesDir(null), "audio-runtime").apply { mkdirs() }
    private val saved = AccountDevicePreferences(microphoneVolume = 65, speakerVolume = 25, signalVolume = 35, noiseSuppression = false)

    @Test fun saveSettingsForRestart() {
        requireOwnedEmulator()
        showSettings()
        setSlider("microphone", saved.microphoneVolume)
        setSlider("speaker", saved.speakerVolume)
        setSlider("signal", saved.signalVolume)
        if (store.read().noiseSuppression) compose.onNodeWithTag("account-noise").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(saved, store.read())
        actor { assertEquals(saved.mediaSettings(), field<CallDeviceSettings>(engine, "deviceSettings")) }
        awaitPreferencesOnDisk(saved)
        evidence("before-restart", JSONObject().put("pid", Process.myPid()).put("preferences", preferences()))
    }

    @Test fun restoredSettingsDriveLiveAudio() {
        requireOwnedEmulator()
        assertDifferentProcess("before-restart")
        assertEquals("Read persistent values before creating the call engine", saved, store.read())
        actor { assertEquals(saved.mediaSettings(), field<CallDeviceSettings>(engine, "deviceSettings")) }
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        showSettings()
        val signal = AtomicReference("tone")
        val decoded = AtomicReference<Meter?>()
        val playout = AtomicReference<Meter?>()
        var phase = 0.0
        var random = 0x53454544L
        val capture = JavaAudioDeviceModule.AudioBufferCallback { buffer, format, channels, sampleRate, bytes, timestamp ->
            check(format == AudioFormat.ENCODING_PCM_16BIT && channels == 1)
            buffer.order(ByteOrder.nativeOrder())
            val kind = signal.get()
            for (index in 0 until minOf(bytes, buffer.capacity()) / 2) {
                random = (random * 1664525L + 1013904223L) and 0xffffffffL
                val value = if (kind == "noise") ((random.toDouble() / 0xffffffffL) * 2 - 1) * 2200
                    else 3500 * sin(phase)
                buffer.putShort(index * 2, value.toInt().toShort())
                phase = (phase + 2 * PI * 440 / sampleRate) % (2 * PI)
            }
            timestamp
        }
        val playback = JavaAudioDeviceModule.PlaybackSamplesReadyCallback { samples ->
            check(samples.audioFormat == AudioFormat.ENCODING_PCM_16BIT)
            playout.get()?.add(ByteBuffer.wrap(samples.data).order(ByteOrder.nativeOrder()), samples.data.size / 2)
        }
        val sink = AudioTrackSink { buffer, bits, _, channels, frames, _ ->
            check(bits == 16)
            decoded.get()?.add(buffer.order(ByteOrder.nativeOrder()), channels * frames)
        }
        var receiver: PeerConnection? = null
        var receiverFactory: PeerConnectionFactory? = null
        var receiverAdm: JavaAudioDeviceModule? = null
        var remote: AudioTrack? = null
        val measurements = JSONArray()
        val report = JSONObject().put("pid", Process.myPid()).put("restoredPreferences", preferences()).put("measurements", measurements)
        try {
            val factory = actor {
                invoke("ensureFactory")
                val adm = field<JavaAudioDeviceModule>(engine, "audioModule")
                setField(field<Any>(adm, "audioInput"), "audioBufferCallback", capture)
                field<PeerConnectionFactory>(engine, "factory")
            }
            // Separate receiver APM: feeding our own delayed voice into the sender's reverse
            // stream would let echo cancellation (not just microphone gain) attenuate it.
            // Received track gain is still applied by production CallEngine.syncVideos.
            receiverAdm = JavaAudioDeviceModule.builder(context).setUseHardwareAcousticEchoCanceler(true)
                .setUseHardwareNoiseSuppressor(true).setPlaybackSamplesReadyCallback(playback).createAudioDeviceModule()
            receiverAdm.setAudioRecordEnabled(false)
            receiverFactory = PeerConnectionFactory.builder().setAudioDeviceModule(receiverAdm).createPeerConnectionFactory()
            val config = PeerConnection.RTCConfiguration(emptyList()).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN }
            val sender = checkNotNull(factory.createPeerConnection(config, Observer()))
            receiver = checkNotNull(receiverFactory.createPeerConnection(config, Observer()))
            val sending = sender.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO)
            actor {
                setField(engine, "pc", sender)
                setField(engine, "peerId", "55555555-5555-4555-8555-555555555555")
                field<MutableMap<String, RtpTransceiver>>(engine, "slots")["microphone"] = sending
                invoke("replaceMic", true)
            }
            negotiate(sender, receiver)
            val receiving = receiver.transceivers.single()
            remote = receiving.receiver.track() as AudioTrack
            remote.addSink(sink)
            actor {
                setField(engine, "peerTransceivers", listOf(sending, receiving))
                invoke("syncVideos")
            }
            fun sample(label: String, settleMs: Long = 2000): JSONObject {
                SystemClock.sleep(settleMs) // Test runner only; never blocks capture/playback.
                val incoming = Meter(); val output = Meter()
                decoded.set(incoming); playout.set(output)
                SystemClock.sleep(2500)
                decoded.set(null); playout.set(null)
                assertTrue("Decoded PCM present: $label", incoming.count.sum() > 16000)
                assertTrue("Playback PCM present: $label", output.count.sum() > 16000)
                val apm = actor { factory.audioProcessingState.noiseSuppression }
                return JSONObject().put("label", label).put("decodedRms", incoming.rms()).put("playoutRms", output.rms())
                    .put("decodedSamples", incoming.count.sum()).put("playoutSamples", output.count.sum())
                    .put("noiseRequested", checkNotNull(apm.requested).isEnabled).put("noiseEffective", apm.effective.name)
                    .put("noiseSoftwareActive", apm.isSoftwareActive).put("noisePlatformActive", apm.isPlatformActive)
                    .also { measurements.put(it); evidence("runtime", report) }
            }
            val restored = sample("restored-65-25-noise-off", 4000)
            assertFalse(restored.getBoolean("noiseRequested"))
            assertEquals("Restored speaker gain affects actual playback PCM", .25,
                restored.getDouble("playoutRms") / restored.getDouble("decodedRms"), .06)

            update(saved.copy(microphoneVolume = 100, speakerVolume = 100))
            val full = sample("microphone-100")
            update(store.read().copy(microphoneVolume = 50))
            val half = sample("microphone-50")
            update(store.read().copy(microphoneVolume = 0))
            val muted = sample("microphone-0")
            update(store.read().copy(microphoneVolume = 100))
            val fullAgain = sample("microphone-100-again")
            val baseline = (full.getDouble("decodedRms") + fullAgain.getDouble("decodedRms")) / 2
            assertTrue("The sender transmits an audible synthetic tone", baseline > 100)
            val micRatio = half.getDouble("decodedRms") / baseline
            report.put("microphoneHalfRatio", micRatio)
            assertEquals("Microphone 50% halves encoded/decoded amplitude", .5, micRatio, .12)
            assertTrue("Microphone zero silences encoded audio", muted.getDouble("decodedRms") < baseline * .01)

            update(store.read().copy(speakerVolume = 25))
            val quiet = sample("speaker-25")
            update(store.read().copy(speakerVolume = 0))
            val silent = sample("speaker-0")
            update(store.read().copy(speakerVolume = 100))
            val loud = sample("speaker-100-again")
            val speakerRatio = quiet.getDouble("playoutRms") / quiet.getDouble("decodedRms")
            report.put("speakerQuarterRatio", speakerRatio)
            assertEquals("Speaker 25% scales actual mixed playback", .25, speakerRatio, .06)
            assertTrue("Speaker zero silences playback", silent.getDouble("playoutRms") < 1)
            assertTrue("Speaker zero leaves received audio intact", silent.getDouble("decodedRms") > baseline * .6)
            assertEquals("Speaker 100% restores playback", 1.0, loud.getDouble("playoutRms") / loud.getDouble("decodedRms"), .08)

            signal.set("noise")
            val noiseOff = sample("noise-off", 5000)
            update(store.read().copy(noiseSuppression = true))
            val noiseOn = sample("noise-on", 5000)
            update(store.read().copy(noiseSuppression = false))
            val noiseOffAgain = sample("noise-off-again", 5000)
            assertTrue(noiseOn.getBoolean("noiseRequested"))
            assertTrue("Software NS is active for synthetic post-platform input", noiseOn.getBoolean("noiseSoftwareActive"))
            assertFalse(noiseOffAgain.getBoolean("noiseSoftwareActive"))
            val noiseBaseline = (noiseOff.getDouble("decodedRms") + noiseOffAgain.getDouble("decodedRms")) / 2
            val noiseRatio = noiseOn.getDouble("decodedRms") / noiseBaseline
            report.put("noiseAmplitudeRatio", noiseRatio).put("noiseReductionDb", -20 * log10(noiseRatio))
            assertTrue("Enabled NS reduces stationary noise in received PCM", noiseRatio < .65)

            update(saved.copy(noiseSuppression = true))
            awaitPreferencesOnDisk(store.read())
            actor { invoke("replaceMic", false); invoke("replaceMic", true) }
            signal.set("tone")
            val recreated = sample("recreated-microphone-65-25-noise-on", 4000)
            assertTrue(recreated.getBoolean("noiseSoftwareActive"))
            assertEquals(.25, recreated.getDouble("playoutRms") / recreated.getDouble("decodedRms"), .06)
            report.put("passed", true)
        } finally {
            decoded.set(null); playout.set(null)
            remote?.removeSink(sink)
            // Drop the receiving peer's borrowed wrappers before disposing its owner.
            actor { setField(engine, "peerTransceivers", emptyList<RtpTransceiver>()) }
            receiver?.close(); receiver?.dispose()
            receiverFactory?.dispose(); receiverAdm?.release()
            engine.leave(updateMembership = false)
            actor {
                assertNull(field<Any?>(engine, "factory"))
                assertNull(field<Any?>(engine, "audioModule"))
                assertNull(field<Any?>(engine, "micSource"))
                assertNull(field<Any?>(engine, "micTrack"))
            }
            update(saved.copy(noiseSuppression = true))
            awaitPreferencesOnDisk(store.read())
            report.put("finalPreferences", preferences())
            evidence("runtime", report)
        }
    }

    @Test fun verifyLastSettingsAfterRestart() {
        requireOwnedEmulator()
        assertDifferentProcess("runtime")
        assertTrue(JSONObject(File(directory, "runtime.json").readText()).getBoolean("passed"))
        assertEquals(saved.copy(noiseSuppression = true), store.read())
        actor { assertEquals(store.read().mediaSettings(), field<CallDeviceSettings>(engine, "deviceSettings")) }
        showSettings()
        for ((name, value) in listOf("microphone" to 65, "speaker" to 25, "signal" to 35)) {
            val node = compose.onNodeWithTag("account-$name-volume").performScrollTo()
            assertEquals(value.toFloat(), node.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current)
        }
        compose.onNodeWithTag("account-noise").performScrollTo().assertIsOn()
        evidence("after-restart", JSONObject().put("pid", Process.myPid()).put("preferences", preferences()).put("passed", true))
    }

    private fun requireOwnedEmulator() {
        assumeTrue("Run explicitly with -e audioRuntime true on a disposable emulator",
            InstrumentationRegistry.getArguments().getString("audioRuntime") == "true" &&
                (Build.FINGERPRINT.startsWith("generic") || Build.MODEL.contains("sdk", true)))
    }
    private fun showSettings() {
        compose.setContent {
            MeetSpaceTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    SettingsHost(PushState(false), content = { state, actions -> AccountAudioPanel(state, actions) }, openNotifications = {})
                }
            }
        }
    }
    private fun setSlider(name: String, value: Int) {
        compose.onNodeWithTag("account-$name-volume").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(value.toFloat()) }
    }
    private fun assertDifferentProcess(previous: String) {
        assertNotEquals("Host must force-stop the app between test stages", Process.myPid(), JSONObject(File(directory, "$previous.json").readText()).getInt("pid"))
    }
    private fun preferences(): JSONObject = store.read().let { JSONObject().put("microphoneVolume", it.microphoneVolume)
        .put("speakerVolume", it.speakerVolume).put("signalVolume", it.signalVolume).put("noiseSuppression", it.noiseSuppression) }
    private fun evidence(name: String, value: JSONObject) { File(directory, "$name.json").writeText(value.toString(2)) }
    private fun awaitPreferencesOnDisk(expected: AccountDevicePreferences) {
        // Instrumentation can exit before SharedPreferences.apply's asynchronous disk write.
        // Observe completion without a commit/flush that could mask persistence failures.
        val file = File(context.applicationInfo.dataDir, "shared_prefs/account-device-preferences.xml")
        waitFor {
            runCatching {
                val values = mutableMapOf<String, String?>()
                file.inputStream().use { input ->
                    val parser = android.util.Xml.newPullParser().apply { setInput(input, "UTF-8") }
                    while (parser.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                        if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG)
                            parser.getAttributeValue(null, "name")?.let { values[it] = parser.getAttributeValue(null, "value") }
                    }
                }
                values["microphoneVolume"] == expected.microphoneVolume.toString() &&
                    values["speakerVolume"] == expected.speakerVolume.toString() &&
                    values["signalVolume"] == expected.signalVolume.toString() &&
                    values["noiseSuppression"] == expected.noiseSuppression.toString()
            }.getOrDefault(false)
        }
    }
    private fun update(value: AccountDevicePreferences) {
        // Exercise real controls → SettingsHost → disk store → Application listener → CallEngine.
        // No direct writes to preferences or configureDevices during the call.
        if (store.read().microphoneVolume != value.microphoneVolume) setSlider("microphone", value.microphoneVolume)
        if (store.read().speakerVolume != value.speakerVolume) setSlider("speaker", value.speakerVolume)
        if (store.read().signalVolume != value.signalVolume) setSlider("signal", value.signalVolume)
        if (store.read().noiseSuppression != value.noiseSuppression) compose.onNodeWithTag("account-noise").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(value, store.read())
        actor { assertEquals(value.mediaSettings(), field<CallDeviceSettings>(engine, "deviceSettings")) }
    }
    private class Meter {
        val count = LongAdder()
        private val squares = DoubleAdder()
        fun add(buffer: ByteBuffer, samples: Int) {
            var sum = 0.0
            val size = minOf(samples, buffer.capacity() / 2)
            for (index in 0 until size) { val value = buffer.getShort(index * 2).toDouble(); sum += value * value }
            squares.add(sum); count.add(size.toLong())
        }
        fun rms() = sqrt(squares.sum() / maxOf(1, count.sum()))
    }
    private fun invoke(name: String, enabled: Boolean? = null) {
        val method = if (enabled == null) CallEngine::class.java.getDeclaredMethod(name)
            else CallEngine::class.java.getDeclaredMethod(name, Boolean::class.javaPrimitiveType)
        method.isAccessible = true
        if (enabled == null) method.invoke(engine) else method.invoke(engine, enabled)
    }
    private fun <T> actor(operation: () -> T): T {
        val result = CompletableDeferred<T>()
        val action: suspend () -> Unit = { try { result.complete(operation()) } catch (error: Throwable) { result.completeExceptionally(error) } }
        CallEngine::class.java.getDeclaredMethod("enqueue", kotlin.jvm.functions.Function1::class.java).apply { isAccessible = true }.invoke(engine, action)
        return runBlocking { withTimeout(10000) { result.await() } }
    }
    @Suppress("UNCHECKED_CAST")
    private fun <T> field(owner: Any, name: String): T = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner) as T
    private fun setField(owner: Any, name: String, value: Any) { owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(owner, value) }
    private fun negotiate(sender: PeerConnection, receiver: PeerConnection) {
        set(sender, create(sender, true), true)
        waitFor { sender.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE }
        set(receiver, sender.localDescription, false)
        set(receiver, create(receiver, false), true)
        waitFor { receiver.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE }
        set(sender, receiver.localDescription, false)
        waitFor { sender.connectionState() == PeerConnection.PeerConnectionState.CONNECTED && receiver.connectionState() == PeerConnection.PeerConnectionState.CONNECTED }
    }
    private fun waitFor(predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 12000
        while (SystemClock.elapsedRealtime() < deadline) { if (predicate()) return; SystemClock.sleep(100) }
        fail("Local WebRTC connection timed out")
    }
    private fun create(peer: PeerConnection, offer: Boolean): SessionDescription {
        val result = AtomicReference<SessionDescription>(); val error = AtomicReference<String>(); val done = CountDownLatch(1)
        val observer = object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) { result.set(sdp); done.countDown() }
            override fun onCreateFailure(message: String) { error.set(message); done.countDown() }
            override fun onSetSuccess() = Unit
            override fun onSetFailure(message: String) = Unit
        }
        if (offer) peer.createOffer(observer, MediaConstraints()) else peer.createAnswer(observer, MediaConstraints())
        assertTrue(done.await(5, TimeUnit.SECONDS)); assertNull(error.get()); return checkNotNull(result.get())
    }
    private fun set(peer: PeerConnection, description: SessionDescription, local: Boolean) {
        val error = AtomicReference<String>(); val done = CountDownLatch(1)
        val observer = object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) = Unit
            override fun onCreateFailure(message: String) = Unit
            override fun onSetSuccess() { done.countDown() }
            override fun onSetFailure(message: String) { error.set(message); done.countDown() }
        }
        if (local) peer.setLocalDescription(observer, description) else peer.setRemoteDescription(observer, description)
        assertTrue(done.await(5, TimeUnit.SECONDS)); assertNull(error.get())
    }
    private class Observer : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidate(candidate: IceCandidate) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(channel: DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
    }
}
