package com.meetspace.app

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.meetspace.core.model.AppFailure
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in, read-only live regression. Credentials never enter Compose, logs or evidence. */
class RecordingCatalogLiveTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val app get() = context.applicationContext as LiveCallApplication

    @Test fun actualCatalogAndMeetingMaterialsDisplayTheSameRecordingAndPoster(): Unit = runBlocking {
        val emulator = Build.HARDWARE in setOf("ranchu", "goldfish") || Build.MODEL.contains("Android SDK") || Build.MODEL.startsWith("sdk_")
        assumeTrue("Requires explicit opt-in on an owned disposable emulator",
            emulator && InstrumentationRegistry.getArguments().getString("verifyRecordingCatalogLive") == "true")
        val fixture = File(context.filesDir, "recording-catalog-live.json")
        assumeTrue("Requires the private live-catalog fixture", fixture.isFile)
        var ownsSession = false
        var createdProfile: String? = null
        var model: RecordingsViewModel? = null
        var inputs: Inputs? = null
        try {
            val input = readInputs(fixture)
            inputs = input
            assertTrue("Legacy fixture overrides must be absent on this disposable emulator",
                listOf("live-recordings.properties", "live-messaging.properties", "live-call.properties")
                    .none { File(context.filesDir, it).exists() })
            val repository = app.repository
            assertEquals("The actual production repository is required", "com.meetspace.core.data.DefaultFoundationRepository", repository.javaClass.name)
            repository.restore()
            assertNull("Do not replace another session", repository.state.value.session)
            val previousProfiles = repository.state.value.profiles.map { it.id }.toSet()
            try {
                val profile = repository.addServer(input.origin)
                if (profile.id !in previousProfiles) createdProfile = profile.id
                repository.login(input.email, input.password)
                ownsSession = true
            } catch (failure: Exception) {
                val kind = (failure as? AppFailure)?.kind?.name ?: "UNAVAILABLE"
                throw AssertionError("Live repository sign-in failed: $kind")
            }

            // A successful direct catalogue request proves the server endpoint is available;
            // this test must not pass via the old meeting-timeline fallback.
            val serverPage = app.recordingsRepository.recordingLibrary(limit = 30)
            val serverRecord = serverPage.items.firstOrNull { it.recording.id == input.recordingId }
            assertNotNull("The first live catalogue page contains the reported recording", serverRecord)
            assertEquals(input.conferenceId, serverRecord!!.meeting.id)
            assertTrue("The server publishes an available JPEG poster", serverRecord.recording.files.any {
                it.type == "preview_jpg" && it.mimeType == "image/jpeg" && it.available
            })

            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                await("Authenticated workspace is visible") { exists("tab-calls") }
                scenario.onActivity { activity -> model = ViewModelProvider(activity)[RecordingsViewModel::class.java] }
                val vm = checkNotNull(model)
                scenario.onActivity { vm.show() }
                await("Reported recording appears in the global catalogue") {
                    !vm.state.value.library.loading && vm.state.value.library.meetings.any { row ->
                        row.meeting.id == input.conferenceId && row.recordings.data?.items?.any { it.id == input.recordingId } == true
                    }
                }
                assertNull("Global catalogue does not use the legacy fallback", vm.state.value.notice)
                assertNull(vm.state.value.library.error)
                showPoster("recordings-library", input.recordingId)
                assertNoMediaStarted(vm)
                screenshot("global-catalogue.png")

                scenario.onActivity { vm.show(input.conferenceId) }
                await("The same recording appears in meeting materials") {
                    vm.state.value.meetingId == input.conferenceId && !vm.state.value.recordings.loading &&
                        vm.state.value.recordings.data?.items?.any { it.id == input.recordingId } == true
                }
                showPoster("recordings-list", input.recordingId)
                assertNoMediaStarted(vm)
                screenshot("meeting-materials.png")

                scenario.onActivity { vm.actions.allRecordings() }
                await("Returning to all recordings retains the reported recording") {
                    vm.state.value.meetingId == null && !vm.state.value.library.loading && vm.state.value.library.meetings.any { row ->
                        row.recordings.data?.items?.any { it.id == input.recordingId } == true
                    }
                }
                showPoster("recordings-library", input.recordingId)
                assertNull(vm.state.value.notice)
                assertNoMediaStarted(vm)
                screenshot("global-after-materials.png")
                evidence(JSONObject().put("productionRepository", true).put("liveCatalogEndpoint", true)
                    .put("recordingId", input.recordingId).put("conferenceId", input.conferenceId)
                    .put("serverPosterMetadata", true).put("globalPosterDisplayed", true)
                    .put("meetingMaterialsPosterDisplayed", true).put("returnedToCatalog", true)
                    .put("legacyTimelineFallback", false).put("autoplayOrCallStarted", false))
                scenario.onActivity { vm.close() }
            }
        } finally {
            instrumentation.runOnMainSync { model?.close() }
            val expected = inputs
            if (ownsSession && expected != null) {
                val current = app.repository.state.value
                if (current.selectedProfile?.origin == expected.origin && current.session?.user?.email?.equals(expected.email, ignoreCase = true) == true)
                    runCatching { app.repository.logout() }
            }
            createdProfile?.let { id ->
                if (app.repository.state.value.session == null) runCatching { app.repository.removeServer(id) }
            }
            if (ownsSession) app.messagingNotifications.clear()
            assertTrue("The private live-catalog fixture is removed", !fixture.exists() || fixture.delete())
        }
    }

    private fun showPoster(list: String, recording: String) {
        compose.onNodeWithTag(list).performScrollToNode(hasTestTag("recording-row-$recording"))
        compose.onNodeWithTag("recording-row-$recording").assertIsDisplayed()
        await("The authorized JPEG is decoded and displayed") { exists("recording-cover-$recording", unmerged = true) }
        compose.onNodeWithTag("recording-cover-$recording", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("recording-cover-placeholder-$recording", useUnmergedTree = true).assertDoesNotExist()
    }

    private fun assertNoMediaStarted(vm: RecordingsViewModel) {
        assertNull("The read-only check must not play media", vm.playback.value)
        assertFalse("The read-only check must not join a call", app.callEngine.state.value.visible)
    }

    private fun exists(tag: String, unmerged: Boolean = false): Boolean =
        compose.onAllNodesWithTag(tag, useUnmergedTree = unmerged).fetchSemanticsNodes().isNotEmpty()

    private fun await(description: String, condition: () -> Boolean) {
        compose.waitUntil(timeoutMillis = 60_000) { condition() }
        assertTrue(description, condition())
    }

    private fun screenshot(name: String) {
        val folder = File(context.filesDir, "recordings-live-evidence").apply { check(isDirectory || mkdirs()) }
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(folder, name).outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }

    private fun evidence(result: JSONObject) {
        val folder = File(context.filesDir, "recordings-live-evidence").apply { check(isDirectory || mkdirs()) }
        File(folder, "catalogue-result.json").writeText(result.toString(2))
    }

    private class Inputs(val origin: String, val email: String, val password: String, val conferenceId: String, val recordingId: String)
    private fun readInputs(file: File): Inputs = try {
        require(file.length() in 1L..16_384L)
        val data = JSONObject(file.readText())
        require(data.keys().asSequence().toSet() == setOf("origin", "email", "password", "conferenceId", "recordingId"))
        val origin = data.getString("origin").also { require(it == "https://chat.stage.org.ru") }
        val email = data.getString("email").also { require(it.length in 3..254 && !it.any(Char::isISOControl)) }
        val password = data.getString("password").also { require(it.length in 1..4096) }
        val conference = data.getString("conferenceId").also(::requireUuid)
        val recording = data.getString("recordingId").also(::requireUuid)
        Inputs(origin, email, password, conference, recording)
    } catch (_: Exception) { throw AssertionError("Private live-catalog fixture is invalid") }

    private fun requireUuid(value: String) { require(UUID.fromString(value).toString() == value && UUID.fromString(value) != UUID(0, 0)) }
}
