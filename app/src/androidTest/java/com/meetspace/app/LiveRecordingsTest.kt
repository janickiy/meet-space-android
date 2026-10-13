package com.meetspace.app

import android.content.pm.ActivityInfo
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.meetspace.core.model.ThemeMode
import com.meetspace.core.data.RecordingReadException
import java.io.File
import java.util.UUID
import java.util.regex.Pattern
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Actual app/repository/player and Android document picker; no fake media/player/provider success. */
@androidx.annotation.OptIn(UnstableApi::class)
@SdkSuppress(minSdkVersion = 29)
class LiveRecordingsTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val app get() = context.applicationContext as LiveCallApplication
    private val backend get() = app.liveRecordingsBackend
    private val device get() = UiDevice.getInstance(instrumentation)
    private var model: RecordingsViewModel? = null
    private var exportedName: String? = null
    private var exportSaved = false

    @Before fun prepare() {
        assumeTrue("Private stage08 fixture is opt-in", File(context.filesDir, "live-recordings.properties").isFile)
        check(app.repository === backend.primary) { "Restart instrumentation after installing private fixture inputs" }
        device.pressBack(); device.pressHome(); device.setOrientationNatural()
    }
    @After fun cleanup() {
        if (File(context.filesDir, "live-recordings.properties").isFile) {
            onMain { model?.close() }
            runBlocking { backend.finish() }
            app.messagingNotifications.clear()
        }
        exportedName?.let { name ->
            val path = ownExportPath(name)
            device.executeShellCommand("rm -f $path")
            assertFalse("Only this test's exact UUID export must be removed", name in device.executeShellCommand("ls -1 /sdcard/Download").lineSequence().toSet())
        }
        model = null
        device.pressBack(); device.pressHome(); device.setOrientationNatural(); device.unfreezeRotation()
    }

    @Test fun realVideoPlaysSeeksRotatesAndReleases() = runBlocking {
        backend.loginPrimary()
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
            val vm = openRecord(scenario)
            detailClick("recording-play-${backend.resource("videoFileId")}")
            await("real MP4 player ready", 45_000) { exists("recording-player-ready") }
            await("real decoded video frame", 20_000) { exists("recording-video-frame") && playerValue(scenario) { it.videoSize.width > 0 && (it.videoDecoderCounters?.renderedOutputBufferCount ?: 0) > 0 } == true }
            screenshot("stage08-live-player.png")
            scenario.onActivity { activity -> playerView(activity.window.decorView)!!.player!!.seekTo(5_000) }
            await("native seek reaches requested position") { (playerValue(scenario) { it.currentPosition } ?: 0) >= 4_800 }
            scenario.onActivity { activity -> playerView(activity.window.decorView)!!.player!!.pause() }
            await("explicit user pause before rotation") { playerValue(scenario) { !it.playWhenReady && !it.isPlaying } == true }
            val pausedPosition = checkNotNull(playerValue(scenario) { it.currentPosition })
            val beforeOrientation = context.resources.configuration.orientation
            device.setOrientationLeft()
            await("real player after orientation change", 45_000) { context.resources.configuration.orientation != beforeOrientation && exists("recording-player-ready") }
            await("video rendered after rotation", 20_000) { exists("recording-video-frame") && playerValue(scenario) { it.videoSize.width > 0 && (it.videoDecoderCounters?.renderedOutputBufferCount ?: 0) > 0 } == true }
            assertTrue("Recreated player retains explicit user pause", playerValue(scenario) { !it.playWhenReady && !it.isPlaying } == true)
            assertTrue("Recreated player retains seek position", playerValue(scenario) { it.currentPosition in 4_800..(pausedPosition + 1_000) } == true)
            var retained = false
            scenario.onActivity { retained = ViewModelProvider(it)[RecordingsViewModel::class.java] === vm }
            assertTrue("Rotation retains the scoped recording ViewModel", retained)
            screenshot("stage08-live-player-landscape.png")
            compose.onNodeWithTag("recording-player-close").performClick()
            await("closed player releases playback capability") { vm.playback.value == null && exists("recording-detail") }
            assertFalse(exists("recording-player-screen"))
            detailClick("recording-play-${backend.resource("videoFileId")}")
            await("player reopened before inbox navigation", 45_000) { exists("recording-player-ready") && vm.playback.value != null }
            val activeAccess = checkNotNull(vm.playback.value).access
            // Exercise the actual Activity entry point without requiring another person's
            // message. The stage07 suite covers the WSS -> OS PendingIntent delivery chain.
            scenario.onActivity { activity ->
                assertNull("This ActivityScenario starts without an action", activity.intent.action)
                activity.startActivity(Intent(activity, MainActivity::class.java).setAction(MessagingNotifications.OPEN_INBOX)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
            await("inbox intent replaces recording overlay") { !vm.state.value.visible && vm.playback.value == null && exists("messaging-workspace") && exists("notifications-list") }
            scenario.onActivity { assertNull("Handled inbox action must not survive in launch Intent", it.intent.action) }
            assertThrows(RecordingReadException::class.java) { activeAccess.open().close() }
            // Real rotation avoids ActivityScenario's intent-filter bookkeeping: it ignores
            // the transient resume while the delivered inbox action is being consumed.
            val inboxOrientation = context.resources.configuration.orientation
            device.setOrientationNatural()
            await("workspace restored after consumed inbox action") {
                context.resources.configuration.orientation != inboxOrientation && exists("messaging-workspace")
            }
            assertFalse("Recreation must not replay an already consumed inbox event", exists("notifications-list"))
            evidence("video", "realMp4=true;nativeDecoderFrames=true;seek=true;rotation=true;pausedPositionRetained=true;closedCapability=true;inboxIntentClosesPlayer=true;consumedIntentNotReplayed=true")
        }
    }

    @Test fun realHistoricalMaterialsPaginateAndSettingsReflectActualState() = runBlocking {
        backend.loginPrimary()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val vm = openRecord(scenario)
            detailClick("recording-load-transcript")
            await("historical transcript loads even with disabled provider") { vm.state.value.segments.data?.items?.size == 100 && !vm.state.value.segments.loading }
            assertFalse(vm.state.value.transcript.data!!.enabled)
            assertEquals("ready", vm.state.value.transcript.data!!.item!!.status)
            val firstId = vm.state.value.segments.data!!.items.first().id
            compose.onNodeWithTag("recording-detail").performScrollToNode(hasTestTag("recording-segment-$firstId"))
            compose.onNodeWithTag("recording-segment-$firstId").assertIsDisplayed()
            screenshot("stage08-live-transcript.png")
            detailClick("recording-segments-more")
            await("second real segment page appended") { vm.state.value.segments.data?.items?.size == 200 && !vm.state.value.segments.loading }
            detailClick("recording-segments-more")
            await("last real segment page appended") { vm.state.value.segments.data?.items?.size == backend.resource("segmentCount").toInt() && !vm.state.value.segments.loading }
            assertNull(vm.state.value.segments.data!!.nextOffset)
            assertEquals(205, vm.state.value.segments.data!!.items.map { it.id }.toSet().size)
            detailClick("recording-tab-summary")
            detailClick("recording-load-summary")
            await("actual historical summary ready") { vm.state.value.summary.data?.item?.status == "ready" }
            assertFalse(vm.state.value.summary.data!!.enabled)
            compose.onNodeWithTag("recording-detail").performScrollToNode(hasTestTag("recording-summary"))
            compose.onNodeWithTag("recording-summary").assertIsDisplayed()
            screenshot("stage08-live-summary.png")
            assertTrue(vm.state.value.detail.data!!.files.none { it.id == backend.resource("debugFileId") })
            compose.onNodeWithTag("recordings-close").performClick()
            await("back to actual workspace") { exists("tab-profile") }
            compose.onNodeWithTag("tab-profile").performClick()
            compose.onNodeWithTag("theme-dark").performScrollTo().performClick()
            await("actual dark preference stored") { backend.primary.state.value.theme == ThemeMode.DARK }
            compose.onNodeWithTag("settings-push-state").performScrollTo().assertTextContains("Фоновые push пока не подключены", substring = true)
            screenshot("stage08-live-settings-dark.png")
            compose.onNodeWithTag("settings-privacy").performScrollTo().performClick()
            compose.onNodeWithTag("settings-privacy-content").assertExists()
            compose.onNodeWithTag("settings-privacy-close").performClick()
            compose.onNodeWithTag("theme-light").performScrollTo().performClick()
            await("actual light preference stored") { backend.primary.state.value.theme == ThemeMode.LIGHT }
            compose.onNodeWithTag("settings-version").performScrollTo().assertTextContains(BuildConfig.VERSION_NAME, substring = true)
            screenshot("stage08-live-settings-light.png")
            evidence("materials", "historicalReadyWithProviderDisabled=true;segments=205;uniquePages=true;summary=true;debugArtifactHidden=true;actualThemePreferences=true;privacyDialog=true")
        }
    }

    @Test fun actualSystemCreateDocumentDownloadsVerifiedMp4AndCleansItsFile() = runBlocking {
        backend.loginPrimary()
        val name = "stage08-${UUID.randomUUID()}-export.mp4"
        exportedName = name
        File(context.cacheDir, "stage08-saf-owned-files.txt").writeText("export=$name\n")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val vm = openRecord(scenario)
            detailClick("recording-download-${backend.resource("videoFileId")}")
            awaitDocumentsUi(); chooseDownloads()
            val documentPackage = device.currentPackageName
            withFreshDocumentObject("DocumentsUI filename field", {
                device.findObject(By.res("android", "title").clazz("android.widget.EditText"))
                    ?: device.findObjects(By.clazz("android.widget.EditText")).singleOrNull()
            }) { it.text = name }
            screenshot("stage08-live-save-document.png")
            withFreshDocumentObject("DocumentsUI Save button", {
                device.findObject(By.res(documentPackage, "button1")) ?: device.findObject(By.res("android", "button1"))
                    ?: device.findObject(By.text("SAVE")) ?: device.findObject(By.text("СОХРАНИТЬ"))
            }) { assertTrue(it.isEnabled); it.click() }
            await("real verified MP4 download completed", 45_000) { vm.state.value.notice == "Запись сохранена в выбранную папку." && vm.state.value.download == null }
            exportSaved = true
            assertEquals(backend.resource("mediaSha256"), shellExportHash(name))
            screenshot("stage08-live-export-complete.png")
            evidence("download", "systemCreateDocument=true;actualMinioStream=true;sha256Match=true;exactTestExportCleanup=true")
        }
    }

    @Test fun realExistingServerAndUnavailableSshOnboarding() = runBlocking {
        backend.resetPrimaryForOnboarding()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var foundation: FoundationViewModel
            scenario.onActivity { foundation = ViewModelProvider(it)[FoundationViewModel::class.java] }
            await("welcome for actual existing-server path") { exists("welcome-connect") && !foundation.state.value.busy }
            compose.onNodeWithTag("welcome-connect").performScrollTo().performClick()
            compose.onNodeWithTag("server-url").performScrollTo().performTextInput(backend.connectOrigin())
            compose.onNodeWithTag("connect-submit").performScrollTo().performClick()
            await("actual HTTPS server capability check") { exists("login-submit") && !foundation.state.value.busy }
            // Compose action failures can dump TextField semantics. Suppress causes for this
            // small private-input block; do not capture login screenshots or enter raw diagnostics.
            try {
                backend.withPrimaryLoginInput { email, password ->
                    compose.onNodeWithTag("email").performScrollTo().performTextInput(email)
                    compose.onNodeWithTag("password").performScrollTo().performTextInput(password)
                    compose.onNodeWithTag("login-submit").performScrollTo().performClick()
                }
                await("actual UI login completed") { exists("tab-profile") && backend.primary.state.value.session != null && !foundation.state.value.busy }
            } catch (_: Throwable) {
                throw AssertionError("Private test login did not complete; input diagnostics suppressed")
            }
            compose.onNodeWithTag("tab-profile").performClick()
            compose.onNodeWithTag("logout").performScrollTo().performClick()
            await("actual UI logout completed") { exists("login-submit") && backend.primary.state.value.session == null && !foundation.state.value.busy }
        }
        backend.resetPrimaryForOnboarding()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var foundation: FoundationViewModel
            lateinit var ssh: SshWizardViewModel
            scenario.onActivity {
                foundation = ViewModelProvider(it)[FoundationViewModel::class.java]
                ssh = ViewModelProvider(it)[SshWizardViewModel::class.java]
            }
            await("welcome for actual installer path") { exists("welcome-installer") && !foundation.state.value.busy }
            compose.onNodeWithTag("welcome-installer").performScrollTo().performClick()
            await("actual SSH details form") { exists("ssh-host") }
            // Device-local closed port: a real TCP failure without contacting any external VPS.
            compose.onNodeWithTag("ssh-host").performScrollTo().performTextInput("127.0.0.1")
            compose.onNodeWithTag("ssh-port").performScrollTo().performTextReplacement("1")
            compose.onNodeWithTag("ssh-user").performScrollTo().performTextReplacement("fixture")
            compose.onNodeWithTag("ssh-domain").performScrollTo().performTextInput("meetspace.example.invalid")
            compose.onNodeWithTag("ssh-probe").performScrollTo().performClick()
            await("actual SSH connection failure is actionable", 45_000) { !ssh.state.value.busy && ssh.state.value.request != null && ssh.state.value.error != null }
            assertEquals(SshWizardStep.DETAILS, ssh.state.value.step)
            assertNull(ssh.state.value.identity)
            assertNull(ssh.state.value.report)
            assertEquals("Не удалось подключиться к SSH-серверу. Проверьте адрес, порт и сеть.", ssh.state.value.error)
            compose.onNodeWithTag("ssh-error").performScrollTo().assertIsDisplayed()
            assertFalse(exists("ssh-auth")); assertFalse(exists("ssh-results")); assertFalse(exists("ssh-install"))
            screenshot("stage08-live-ssh-unavailable.png")
            compose.onNodeWithTag("ssh-exit").performScrollTo().assertIsDisplayed().performClick()
            await("installer exits safely to welcome") { exists("welcome-connect") }
            evidence("onboarding", "actualHttpsUiLogin=true;actualUiLogout=true;actualLocalSshNetworkFailure=true;actionableError=true;installationAttempted=false")
        }
    }

    private fun openRecord(scenario: ActivityScenario<MainActivity>): RecordingsViewModel {
        await("authenticated workspace") { exists("tab-calls") }
        compose.onNodeWithTag("tab-calls").performClick()
        compose.onNodeWithTag("meeting-recordings").performClick()
        await("real recordings library") { exists("recordings-library") }
        compose.onNodeWithTag("recordings-library").performScrollToNode(hasTestTag("recordings-choose-meeting"))
        compose.onNodeWithTag("recordings-choose-meeting").performClick()
        await("real recording meeting picker") { exists("recordings-meetings") }
        var vm: RecordingsViewModel? = null
        scenario.onActivity { vm = ViewModelProvider(it)[RecordingsViewModel::class.java] }
        val active = checkNotNull(vm); model = active
        await("fixture meeting loaded") { active.state.value.meetings.data?.items?.any { it.id == backend.resource("conferenceId") } == true }
        val meeting = "recordings-meeting-${backend.resource("conferenceId")}";
        compose.onNodeWithTag("recordings-meetings").performScrollToNode(hasTestTag(meeting)); compose.onNodeWithTag(meeting).performClick()
        await("real ready records loaded") { active.state.value.recordings.data?.items?.any { it.id == backend.resource("recordingId") } == true }
        val row = "recording-row-${backend.resource("recordingId")}";
        compose.onNodeWithTag("recordings-list").performScrollToNode(hasTestTag(row)); compose.onNodeWithTag(row).performClick()
        await("real selected recording detail") { active.state.value.detail.data?.id == backend.resource("recordingId") }
        return active
    }
    private fun detailClick(tag: String) { compose.onNodeWithTag("recording-detail").performScrollToNode(hasTestTag(tag)); compose.onNodeWithTag(tag).performClick() }
    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun playerView(view: View): PlayerView? = if (view is PlayerView) view else if (view is ViewGroup) (0 until view.childCount).firstNotNullOfOrNull { playerView(view.getChildAt(it)) } else null
    private fun <T> playerValue(scenario: ActivityScenario<MainActivity>, operation: (ExoPlayer) -> T): T? {
        var value: T? = null
        scenario.onActivity { activity -> (playerView(activity.window.decorView)?.player as? ExoPlayer)?.let { value = operation(it) } }
        return value
    }
    private fun ownExportPath(name: String): String {
        require(name == exportedName && Regex("stage08-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}-export\\.mp4").matches(name))
        return "/sdcard/Download/$name"
    }
    private fun shellExportHash(name: String): String {
        check(exportSaved)
        val path = ownExportPath(name)
        val output = device.executeShellCommand("sha256sum $path").trim()
        return Regex("^([0-9a-fA-F]{64})\\s+${Regex.escape(path)}$").matchEntire(output)?.groupValues?.get(1)?.lowercase()
            ?: error("Could not hash the test's exact CreateDocument export")
    }
    private fun awaitDocumentsUi() {
        assertTrue("System document picker must be foreground", device.wait(Until.hasObject(By.pkg("com.google.android.documentsui")), 8_000)
            || device.wait(Until.hasObject(By.pkg("com.android.documentsui")), 8_000)); device.waitForIdle()
    }
    private fun chooseDownloads() {
        val pkg = device.currentPackageName; check(pkg in setOf("com.google.android.documentsui", "com.android.documentsui"))
        val downloads = By.text(Pattern.compile("Downloads|Загрузки")).hasAncestor(By.res(pkg, "roots_list"))
        if (!device.hasObject(downloads)) withFreshDocumentObject("DocumentsUI roots drawer", {
            device.findObject(By.desc("Show roots")) ?: device.findObject(By.desc("Открыть панель навигации")) ?: device.findObject(By.desc("Показать корни"))
        }) { it.click() }
        withFreshDocumentObject("Downloads entry", { device.wait(Until.findObject(downloads), 3_000) }) { it.click() }
        device.waitForIdle()
    }
    private fun withFreshDocumentObject(label: String, find: () -> UiObject2?, action: (UiObject2) -> Unit) {
        var stale: StaleObjectException? = null
        repeat(3) { device.waitForIdle(1_500); try { find()?.let { action(it); return } } catch (e: StaleObjectException) { stale = e } }
        throw AssertionError("$label absent after three fresh lookups", stale)
    }
    private fun await(label: String, timeout: Long = 30_000, predicate: () -> Boolean) { File(context.cacheDir, "stage08-progress.txt").writeText("step=$label"); compose.waitUntil(timeoutMillis = timeout, condition = predicate) }
    private fun onMain(block: () -> Unit) { instrumentation.runOnMainSync(block) }
    private fun screenshot(name: String) { assertTrue(device.takeScreenshot(File(context.cacheDir, name))) }
    private fun evidence(section: String, safe: String) { File(context.cacheDir, "stage08-live-evidence.txt").appendText("$section:$safe\n") }
}
