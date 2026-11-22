package com.meetspace.app

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.meetspace.app.ui.*
import com.meetspace.core.designsystem.MeetSpaceTheme
import com.meetspace.core.media.CallParticipant
import com.meetspace.core.media.CallPhase
import com.meetspace.core.media.CallState
import com.meetspace.core.model.*
import java.io.File
import java.time.Instant
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Deterministic, test-only presentation data. No repository, native media, credentials or network. */
class CallRecordingDesignTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun captureApprovedPrejoinScreenshot() = capture("prejoin", "call-screen") {
        CallScreen(call.copy(phase = CallPhase.PREJOIN, status = "Подготовка к встрече", participants = emptyList()), onWorkspaceTab = {}, profileLabel = self.name)
    }

    @Test fun captureApprovedCallScreenshot() = capture("call", "call-screen", darkBars = true) {
        CallScreen(call, onWorkspaceTab = {})
    }

    @Test fun captureApprovedCallShareScreenshot() = capture("call-share", "call-screen", darkBars = true) {
        FixtureCall()
    }

    @Test fun captureApprovedCallParticipantsScreenshot() = capture("call-participants", "call-screen", darkBars = true,
        afterRender = { compose.onNodeWithTag("call-people").performClick(); compose.onNodeWithTag("call-participants-panel").assertIsDisplayed() }) {
        FixtureCall()
    }

    @Test fun captureApprovedCallChatScreenshot() = capture("call-chat", "call-screen", darkBars = true,
        afterRender = { compose.onNodeWithTag("call-chat").performClick(); compose.onNodeWithTag("call-chat-panel").assertIsDisplayed() }) {
        CallScreen(call, onWorkspaceTab = {}, details = callDetails)
    }

    @Test fun captureApprovedCallSpeakerScreenshot() {
        assumeTrue("Tablet speaker layout", InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.screenWidthDp >= 780)
        capture("call-speaker", "call-screen", darkBars = true, afterRender = {
            compose.onNodeWithTag("call-layout-speaker").performClick().assertIsSelected()
            compose.onNodeWithTag("call-chat").performClick()
            compose.onNodeWithTag("call-featured-video").assertIsDisplayed()
            compose.onNodeWithTag("call-chat-panel").assertIsDisplayed()
        }) {
            CallScreen(call, onWorkspaceTab = {}, details = callDetails)
        }
    }

    @Test fun captureApprovedCallRecordingScreenshot() = capture("call-recording", "call-screen", darkBars = true,
        afterRender = { compose.onNodeWithTag("call-recording-control").performClick(); compose.onNodeWithTag("call-recording-dialog").assertIsDisplayed() }) {
        FixtureCall()
    }

    @Composable private fun FixtureCall() {
        CallScreen(call, onWorkspaceTab = {}, details = callDetails, stageContentOverride = { CallPresentationFixture() }, sharePreviewName = "Анна Кузнецова")
    }

    @Test fun captureApprovedRecordingsScreenshot() = capture("recordings", "recordings-workspace") {
        RecordingsWorkspace(catalogue, RecordingsActions(), onWorkspaceTab = {}, profileLabel = self.name)
    }

    @Test fun captureMeetingRecordingsListScreenshot() = capture("meeting-recordings-list", "recordings-workspace") {
        RecordingsWorkspace(meetingMaterials, RecordingsActions(), onWorkspaceTab = {}, profileLabel = self.name)
    }

    @Test fun captureMeetingRecordingsGridScreenshot() = capture("meeting-recordings-grid", "recordings-workspace",
        afterRender = { compose.onNodeWithTag("recordings-view-grid").performClick().assertIsSelected() }) {
        RecordingsWorkspace(meetingMaterials, RecordingsActions(), onWorkspaceTab = {}, profileLabel = self.name)
    }

    @Test fun captureApprovedRecordingDetailScreenshot() = capture("recording-detail", "recordings-workspace") {
        RecordingsWorkspace(recordings.copy(detailId = record.id, detail = RecordingsLoad(record)), RecordingsActions(), onWorkspaceTab = {}, profileLabel = self.name)
    }

    private fun capture(name: String, tag: String, darkBars: Boolean = false, afterRender: () -> Unit = {}, content: @Composable () -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Opt-in design screenshots", args.getString("designScreenshots") == "true")
        args.getString("designScreen")?.let { assumeTrue("Selected screenshot", it == name) }
        val theme = args.getString("designTheme", "light")
        require(theme in setOf("light", "dark")) { "designTheme must be light or dark" }
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
        compose.setContent {
            MeetSpaceTheme(if (theme == "dark") ThemeMode.DARK else ThemeMode.LIGHT) {
                val lightBars = !darkBars && MaterialTheme.colorScheme.background.luminance() > .5f
                SideEffect {
                    WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView).apply {
                        isAppearanceLightStatusBars = lightBars
                        isAppearanceLightNavigationBars = lightBars
                    }
                }
                content()
            }
        }
        compose.onNodeWithTag(tag).assertIsDisplayed()
        if (name.startsWith("call")) {
            compose.onNodeWithTag("bottom-navigation").assertDoesNotExist()
            compose.onNodeWithTag("call-back-to-meetings").assertIsDisplayed()
            compose.onNodeWithTag("call-share").assertDoesNotExist()
            compose.onNodeWithTag("call-settings").assertDoesNotExist()
        } else {
            compose.onNodeWithTag("bottom-navigation").assertIsDisplayed()
            compose.onNodeWithTag("tab-calls").assertIsSelected()
        }
        afterRender()
        compose.waitForIdle()
        if (name == "call-share") {
            assertReadableTextAtActualBounds(compose.onNodeWithTag("call-fixture-presentation-title", useUnmergedTree = true), "В одной\nкоманде.\nНа одной волне.")
            assertReadableTextAtActualBounds(compose.onNodeWithTag("call-fixture-presentation-footer", useUnmergedTree = true), "ОКТЯБРЬ — ДЕКАБРЬ / 01", singleLine = true)
        }
        // Finish interaction ripples before freezing the Compose clock for a deterministic image.
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        val configuration = instrumentation.targetContext.resources.configuration
        val directory = checkNotNull(instrumentation.targetContext.getExternalFilesDir("screenshots"))
        check(directory.isDirectory || directory.mkdirs())
        val target = File(directory, "$name-$theme-${configuration.screenWidthDp}x${configuration.screenHeightDp}-font${configuration.fontScale}.png")
        if (name in setOf("call-participants", "call-recording")) {
            // The native Dialog lives in a separate window; capture its dimmed backdrop as well.
            android.os.SystemClock.sleep(450)
            val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            try { target.outputStream().use { stream -> check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, stream)) } }
            finally { screenshot.recycle() }
        } else target.outputStream().use { stream ->
            check(compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
    }

    companion object {
        private val self = CallParticipant("self", "Александр Соколов", true, true, false)
        private val call = CallState(CallPhase.CONNECTED, conferenceId = "design-fixture", title = "Дизайн-синхронизация", participantId = self.id,
            microphone = true, activeSpeakerIds = setOf("anna"),
            status = "Вы в звонке", participants = listOf(self,
                CallParticipant("anna", "Анна Кузнецова", true, true, false),
                CallParticipant("maria", "Мария Полякова", true, false, false),
                CallParticipant("dmitry", "Дмитрий Волков", true, false, false),
                CallParticipant("maxim", "Максим Петров", true, false, false)))
        private val callDetails = CallUiDetails(elapsedSeconds = 768, recording = true,
            room = MeetingRoomUiState(selfId = "self", participants = call.participants.map { MeetingParticipant(it.id, "design-fixture", it.id, it.name, if (it.id == "self") "owner" else "participant", "joined", "admitted") } + MeetingParticipant("waiting", "design-fixture", null, "Елена Смирнова", "guest", "waiting", "waiting")),
            recordingState = CallRecordingUiState(status = CallRecordingStatus.STOPPED, canStart = true, modes = listOf("composite", "audio_only", "individual_tracks")),
            chat = CallTextPanelState(canSend = true, canAttach = true, participantCount = 5, entries = listOf(
                CallTextEntry("chat-one", "Мария Полякова", "Хорошо видно, можем начинать!", "10:02", dateLabel = "Сегодня"),
                CallTextEntry("chat-two", "Анна Кузнецова", "Прикрепляю повестку. Здесь же можно оставить вопросы к встрече.", "10:05",
                    attachments = listOf(CallAttachmentUi("agenda", "Повестка встречи.txt", "text/plain", 452))),
                CallTextEntry("chat-three", "Дмитрий Волков", "Давайте начнём со сценария первого входа.", "10:08"))))
        private val at = Instant.parse("2026-05-15T11:00:00Z")
        private val meeting = Meeting("design-fixture", "self", "Дизайн-ревью", "", "", "finished", at, at)
        private val file = RecordingFile("design-video", "final_mp4", "design-review.mp4", "video/mp4", 134217728, durationSec = 2835)
        private val record = Recording("design-recording", meeting.id, "mixed", "ready", at, durationSec = 2835, files = listOf(file))

        private fun recordingFixture(ownerMeeting: Meeting, mode: String, started: String, duration: Long): Recording {
            val id = "${ownerMeeting.id}-$mode"
            val media = when (mode) {
                "audio_only" -> RecordingFile("$id-file", "final_audio", "meeting.m4a", "audio/mp4", 26214400, durationSec = duration)
                "individual_tracks" -> RecordingFile("$id-file", "tracks_archive", "tracks.zip", "application/zip", 44040192)
                else -> file.copy(id = "$id-file", durationSec = duration)
            }
            return record.copy(id = id, conferenceId = ownerMeeting.id, mode = mode, createdAt = Instant.parse(started),
                durationSec = duration, files = listOf(media))
        }
        private val product = meeting.copy(id = "product", title = "Продукт. Следующая глава", participantCount = 5)
        private val design = meeting.copy(id = "design", title = "Дизайн-ревью мобильного приложения", ownerId = "anna", participantCount = 4)
        private val plans = meeting.copy(id = "plans", title = "Планы на октябрь", participantCount = 4)
        private val demo = meeting.copy(id = "demo", title = "Демо новой версии", ownerId = "maxim", participantCount = 8)
        private val catalogue = RecordingsUiState(visible = true, selfId = self.id, library = RecordingLibrary(loaded = true, hasMore = false,
            meetings = listOf(
                product to listOf(recordingFixture(product, "individual_tracks", "2026-10-15T07:45:00Z", 720),
                    recordingFixture(product, "composite", "2026-10-15T07:00:00Z", 2538)),
                design to listOf(recordingFixture(design, "composite", "2026-10-14T11:30:00Z", 1866)),
                plans to listOf(recordingFixture(plans, "audio_only", "2026-10-12T06:00:00Z", 1722)),
                demo to listOf(recordingFixture(demo, "composite", "2026-10-11T13:00:00Z", 3252)),
            ).map { (meeting, records) -> RecordingLibraryMeeting(meeting, RecordingsLoad(RecordingPage(records, null))) }))
        private val scopedMeeting = meeting.copy(title = "встреча")
        private val meetingMaterials = RecordingsUiState(visible = true, selfId = self.id, meetingId = scopedMeeting.id,
            meeting = RecordingsLoad(scopedMeeting), recordings = RecordingsLoad(RecordingPage(listOf(
                recordingFixture(scopedMeeting, "individual_tracks", "2026-11-20T15:22:30Z", 20),
                recordingFixture(scopedMeeting, "audio_only", "2026-11-20T15:22:00Z", 13),
                recordingFixture(scopedMeeting, "composite", "2026-11-20T15:05:00Z", 25),
            ), null)))
        private val recordings = RecordingsUiState(visible = true, meetingId = meeting.id, meeting = RecordingsLoad(meeting),
            recordings = RecordingsLoad(RecordingPage(listOf(record, record.copy(id = "second-recording", createdAt = at.minusSeconds(604800), durationSec = 2412,
                files = listOf(file.copy(id = "second-video", durationSec = 2412)))), null)))
    }
}

/** Artwork from the design preview is deliberately confined to androidTest, never a video fallback. */
@Composable
private fun CallPresentationFixture() {
    val viewportWidth = LocalConfiguration.current.screenWidthDp
    val phone = viewportWidth < 600
    val density = LocalDensity.current
    // A shared screen is a fixed image: Android's accessibility text scale must not change its pixels.
    CompositionLocalProvider(LocalDensity provides Density(density.density, 1f)) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // CallStage already applies the reference's 90%/85% share bounds.
            val slideWidth = if (phone) minOf(maxWidth, maxHeight * 4f / 3f) else maxWidth
            val slideHeight = if (phone) slideWidth * 3f / 4f else maxHeight
            val innerShareWidth = if (phone) slideWidth else slideWidth / .9f
            val inset = innerShareWidth * if (phone) .07f else .05f
            val contentWidth = slideWidth - inset * 2
            val cssHeading = if (phone) 26f else if (viewportWidth < 840) 30f else (viewportWidth * .04f).coerceIn(24f, 54f)
            val heading = minOf(cssHeading, ((slideHeight.value - 52f) / 3.24f).coerceAtLeast(1f))
            val headingSize = heading.sp
            val headingHeight = (heading * 3.24f).dp
            val verticalInset = minOf(inset, ((slideHeight - headingHeight - 52.dp) / 2).coerceAtLeast(0.dp))
            val verticalSpace = (slideHeight - verticalInset * 2 - headingHeight - 52.dp).coerceAtLeast(0.dp)
            val marginBottom = minOf(contentWidth * .03f, verticalSpace)
            // Android's safe insets can leave less height than the browser preview. Contract only
            // surplus spacing in that case; retain the CSS type sizes and every line of artwork.
            val marginTop = minOf(contentWidth * if (phone) .12f else .08f, (verticalSpace - marginBottom).coerceAtLeast(0.dp))
            Box(Modifier.size(slideWidth, slideHeight).clipToBounds().background(Color(0xFFF7F5EE)).testTag("call-fixture-presentation")) {
                Canvas(Modifier.fillMaxSize()) {
                    val diameter = size.width * if (phone) .6f else .54f
                    val center = Offset(size.width * 1.09f - diameter / 2, size.height * 1.2f - diameter / 2)
                    drawCircle(Color(0xFFD6E6A9), diameter / 2, center)
                    val stroke = if (phone) 16.dp.toPx() else 20.dp.toPx()
                    drawCircle(Color(0xFF92AC6F), diameter * .4f - stroke / 2, center, style = Stroke(stroke))
                }
                Column(Modifier.fillMaxSize().padding(horizontal = inset, vertical = verticalInset)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("MEETSPACE / PRODUCT", fontSize = 8.sp, lineHeight = 12.sp, letterSpacing = 2.sp, color = Color(0xFF24342E))
                        Text("2026", fontSize = 8.sp, lineHeight = 12.sp, letterSpacing = 2.sp, color = Color(0xFF24342E))
                    }
                    Spacer(Modifier.height(marginTop))
                    Text("В одной\nкоманде.\nНа одной волне.", fontSize = headingSize, lineHeight = headingSize * 1.08f,
                        letterSpacing = ((if (phone) -1f else -2f) * (heading / cssHeading)).sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF24342E), modifier = Modifier.testTag("call-fixture-presentation-title"))
                    Spacer(Modifier.height(marginBottom))
                    Text("Следующая глава нашего продукта", fontSize = 10.sp, lineHeight = 15.sp, color = Color(0xFF859188))
                    Spacer(Modifier.weight(1f))
                    Text("ОКТЯБРЬ — ДЕКАБРЬ / 01", fontSize = 8.sp, lineHeight = 12.sp, letterSpacing = 1.sp, color = Color(0xFF24342E), modifier = Modifier.testTag("call-fixture-presentation-footer"))
                }
            }
        }
    }
}
