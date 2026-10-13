package com.meetspace.app

import android.Manifest
import android.content.res.Configuration
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import java.util.regex.Pattern
import android.os.Build
import com.meetspace.core.media.CallPhase
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Before
import org.junit.After

/** Real Android libwebrtc ↔ HTTPS/WSS backend ↔ Pion SFU ↔ four Pion peers, forced TURN. */
class LiveCallTest {
    @Before fun dismissSystemSurfaces() { UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).apply { pressBack(); pressHome(); setOrientationNatural() } }
    @After fun closeSystemSurfaces() { UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).apply { pressBack(); pressHome(); setOrientationNatural(); unfreezeRotation() } }
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val app get() = context.applicationContext as LiveCallApplication
    private fun waitFor(label: String, timeout: Long = 45_000, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < until) {
            val state = app.callEngine.state.value
            File(context.cacheDir, "stage06-progress.txt").writeText("step=$label; phase=${state.phase}; stats=${state.diagnostics}; events=${app.liveBackend.eventCounts}")
            if (condition()) return
            if (app.callEngine.state.value.phase == CallPhase.FAILED) fail("$label: ${app.callEngine.state.value.error}")
            Thread.sleep(100)
        }
        fail("Timed out: $label; phase=${app.callEngine.state.value.phase}; error=${app.callEngine.state.value.error}")
    }
    @Test fun nativeCameraRelayReconnectAndCleanup() = runBlocking {
        assumeTrue(File(context.filesDir, "live-call.properties").isFile)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val engine = app.callEngine
        val backend = app.liveBackend
        backend.login()
        val meeting = backend.conference(backend.input.getProperty("conferenceId"))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            Thread.sleep(1500)
            engine.prepare(meeting)
            waitFor("prejoin") { engine.state.value.phase == CallPhase.PREJOIN }
            engine.camera(true); engine.microphone(true)
            waitFor("preview") { engine.state.value.localVideo != null && engine.state.value.microphone }
            engine.connect()
            waitFor("connected", 60_000) { engine.state.value.phase == CallPhase.CONNECTED }
            waitFor("relay + decoded echo", 60_000) { engine.state.value.diagnostics.relay && engine.state.value.diagnostics.decodedFrames > 5 && engine.state.value.diagnostics.receivedAudioBytes > 0 && engine.state.value.diagnostics.sentBytes > 0 }
            File(context.cacheDir, "stage06-native-evidence.txt").writeText("initial=${engine.state.value.diagnostics}\n")
            assertEquals(backend.input.getProperty("expectedPeers", "5").toInt(), engine.state.value.participants.count { it.online })
            if (backend.input.getProperty("expectRemoteScreen") == "true") assertTrue(engine.state.value.videos.any { it.screen })
            val device = UiDevice.getInstance(instrumentation)
            device.takeScreenshot(File(context.cacheDir, "stage06-live-call.png"))
            device.setOrientationLeft()
            waitFor("portrait call") { context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT && engine.state.value.phase == CallPhase.CONNECTED }
            device.setOrientationNatural()
            waitFor("landscape call") { context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && engine.state.value.phase == CallPhase.CONNECTED }
            scenario.recreate()
            waitFor("rotation retains media") { engine.state.value.phase == CallPhase.CONNECTED && engine.state.value.localVideo != null }
            engine.camera(false); engine.microphone(false)
            waitFor("capture off") { !engine.state.value.camera && !engine.state.value.microphone && engine.state.value.localVideo == null }
            backend.lastSocket!!.cancel()
            waitFor("reconnecting observed") { engine.state.value.phase == CallPhase.RECONNECTING }
            waitFor("reconnected", 60_000) { engine.state.value.phase == CallPhase.CONNECTED && engine.state.value.participants.size == backend.input.getProperty("expectedPeers", "5").toInt() && engine.state.value.diagnostics.relay }
            engine.camera(true)
            waitFor("decode after reconnect", 60_000) { engine.state.value.diagnostics.decodedFrames > 5 }
            File(context.cacheDir, "stage06-native-evidence.txt").appendText("reconnected=${engine.state.value.diagnostics}\n")
            engine.leave()
            waitFor("left") { engine.state.value.phase == CallPhase.IDLE }
            assertNull(engine.state.value.localVideo)
            assertTrue(engine.state.value.videos.isEmpty())
            assertTrue(engine.state.value.routes.isEmpty())
            engine.prepare(meeting); waitFor("prepare again") { engine.state.value.phase == CallPhase.PREJOIN }
            engine.connect(); waitFor("rejoin", 60_000) { engine.state.value.phase == CallPhase.CONNECTED }
            engine.leave(); waitFor("second cleanup") { engine.state.value.phase == CallPhase.IDLE }
        }
    }
    @Test fun screenConsentBackgroundAndStop() = runBlocking {
        assumeTrue(File(context.filesDir, "live-call.properties").isFile)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val engine = app.callEngine; val backend = app.liveBackend
        backend.login(); val meeting = backend.conference(backend.input.getProperty("conferenceId"))
        val device = UiDevice.getInstance(instrumentation)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            Thread.sleep(1500)
            engine.prepare(meeting); waitFor("screen prejoin") { engine.state.value.phase == CallPhase.PREJOIN }
            engine.connect(); waitFor("screen connected") { engine.state.value.phase == CallPhase.CONNECTED }
            val shareControl = device.wait(Until.findObject(By.desc("Экран")), 1_500)
            if (shareControl != null) shareControl.click()
            else {
                val menu = device.wait(Until.findObject(By.desc("Информация о встрече")), 10_000)
                assertNotNull("The compact call must expose screen sharing in its meeting menu", menu)
                menu!!.click()
                val shareAction = device.wait(Until.findObject(By.text("Демонстрация экрана")), 5_000)
                assertNotNull("Screen capture must remain an explicit user action", shareAction)
                shareAction!!.click()
            }
            Thread.sleep(1000)
            device.dumpWindowHierarchy(File(context.cacheDir, "stage06-consent.xml"))
            val spinner = device.findObject(By.res("com.android.systemui:id/screen_share_mode_options"))
            spinner?.click()
            device.wait(Until.findObject(By.text(Pattern.compile("Entire screen|Весь экран|Share entire screen|Share your entire screen", Pattern.CASE_INSENSITIVE))), 2000)?.click()
            val start = device.wait(Until.findObject(By.text(Pattern.compile("Start now|Share screen|Start|Начать|Начать сейчас|Поделиться экраном", Pattern.CASE_INSENSITIVE))), 5000)
            if (start != null) start.click()
            else device.findObject(By.res("android:id/button1"))?.click()
            waitFor("projection active", 60_000) { engine.state.value.sharing && engine.state.value.localScreen != null }
            waitFor("screen RTP", 30_000) { engine.state.value.diagnostics.sentBytes > 1000 }
            device.takeScreenshot(File(context.cacheDir, "stage06-live-screen.png"))
            scenario.recreate()
            waitFor("screen survives recreation") { engine.state.value.sharing && engine.state.value.phase == CallPhase.CONNECTED }
            device.setOrientationLeft()
            waitFor("portrait projection") { context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT && engine.state.value.sharing }
            device.setOrientationNatural()
            waitFor("landscape projection") { context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && engine.state.value.sharing }
            device.pressHome(); Thread.sleep(1500)
            assertTrue(engine.state.value.sharing)
            assertFalse(engine.state.value.microphone); assertFalse(engine.state.value.camera)
            device.openNotification(); Thread.sleep(500)
            device.dumpWindowHierarchy(File(context.cacheDir, "stage06-notification.xml"))
            val stop = device.wait(Until.findObject(By.desc("Остановить")), 5000)
            assertNotNull("Foreground notification stop action", stop)
            stop!!.click()
            waitFor("notification stops projection") { !engine.state.value.sharing && engine.state.value.localScreen == null }
            engine.leave(); waitFor("screen cleanup") { engine.state.value.phase == CallPhase.IDLE }
        }
    }

    @Test fun deniedCameraAllowsListening() = runBlocking {
        assumeTrue(File(context.filesDir, "live-call.properties").isFile)
        val engine = app.callEngine; val backend = app.liveBackend
        backend.login(); val meeting = backend.conference(backend.input.getProperty("conferenceId"))
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        ActivityScenario.launch(MainActivity::class.java).use {
            Thread.sleep(1500)
            engine.prepare(meeting); waitFor("permission prejoin") { engine.state.value.phase == CallPhase.PREJOIN }
            device.wait(Until.findObject(By.desc("Без видео")), 10_000)!!.click()
            val deny = device.wait(Until.findObject(By.res("com.android.permissioncontroller:id/permission_deny_button")), 5000)
            assertNotNull("Revoke camera permission before this opt-in test", deny)
            deny!!.click()
            waitFor("denial visible") { engine.state.value.error != null }
            assertFalse(engine.state.value.camera); assertNull(engine.state.value.localVideo)
            engine.connect(); waitFor("listening without camera") { engine.state.value.phase == CallPhase.CONNECTED }
            engine.leave(); waitFor("denial cleanup") { engine.state.value.phase == CallPhase.IDLE }
        }
    }

    @Test fun lostNetworkReconnectsWithDevicesOff() = runBlocking {
        assumeTrue(File(context.filesDir, "live-call.properties").isFile)
        val engine = app.callEngine; val backend = app.liveBackend
        backend.login(); val meeting = backend.conference(backend.input.getProperty("conferenceId"))
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        ActivityScenario.launch(MainActivity::class.java).use {
            Thread.sleep(1500); engine.prepare(meeting)
            waitFor("network prejoin") { engine.state.value.phase == CallPhase.PREJOIN }
            engine.connect(); waitFor("before network loss") { engine.state.value.phase == CallPhase.CONNECTED }
            try {
                device.executeShellCommand("svc data disable"); device.executeShellCommand("svc wifi disable")
                waitFor("network loss observed", 40_000) { engine.state.value.phase == CallPhase.RECONNECTING }
            } finally { device.executeShellCommand("svc wifi enable"); device.executeShellCommand("svc data enable") }
            waitFor("network restored", 60_000) { engine.state.value.phase == CallPhase.CONNECTED && engine.state.value.diagnostics.relay }
            assertFalse(engine.state.value.microphone); assertFalse(engine.state.value.camera)
            engine.leave(); waitFor("network cleanup") { engine.state.value.phase == CallPhase.IDLE }
        }
    }
    @Test fun unreachableTurnFailsAndRetryRecovers() = runBlocking {
        assumeTrue(File(context.filesDir, "live-call.properties").isFile)
        val engine = app.callEngine; val backend = app.liveBackend
        backend.login(); val meeting = backend.conference(backend.input.getProperty("conferenceId"))
        backend.forceIceFailure = true
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                Thread.sleep(1500); engine.prepare(meeting)
                waitFor("failed ICE prejoin") { engine.state.value.phase == CallPhase.PREJOIN }
                engine.connect()
                val deadline = System.currentTimeMillis() + 120_000
                while (engine.state.value.phase != CallPhase.FAILED && System.currentTimeMillis() < deadline) Thread.sleep(100)
                assertEquals(CallPhase.FAILED, engine.state.value.phase)
                assertNull(engine.state.value.localVideo); assertTrue(engine.state.value.videos.isEmpty()); assertTrue(engine.state.value.routes.isEmpty())
                val failedAttempt = engine.state.value
                backend.forceIceFailure = false
                engine.retry()
                // retry() queues work on the media actor; the old FAILED snapshot is
                // expected until that command runs. A new FAILED snapshot still fails.
                val retryState = withTimeout(10_000) { engine.state.first { it !== failedAttempt } }
                assertTrue("Retry was not accepted: phase=${retryState.phase}; error=${retryState.error}",
                    retryState.phase in setOf(CallPhase.CONNECTING, CallPhase.RECONNECTING, CallPhase.CONNECTED))
                waitFor("retry after ICE failure", 60_000) { engine.state.value.phase == CallPhase.CONNECTED && engine.state.value.diagnostics.relay }
                engine.leave(); waitFor("ICE cleanup") { engine.state.value.phase == CallPhase.IDLE }
            }
        } finally {
            backend.forceIceFailure = false
        }
    }

}
