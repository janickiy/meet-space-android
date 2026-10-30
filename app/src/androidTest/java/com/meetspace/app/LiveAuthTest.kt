package com.meetspace.app

import android.content.res.Configuration
import androidx.test.uiautomator.UiDevice
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.*
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.meetspace.core.model.ThemeMode
import java.io.File
import java.util.Properties
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in only: credentials are supplied as a temporary private app file, never as source/runner arguments. */
class LiveAuthTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val application get() = context.applicationContext as MeetSpaceApplication

    private fun input(): Properties {
        val file = File(context.filesDir, "live-auth.properties")
        assumeTrue("Live server test requires a private runtime credential file", file.isFile)
        return Properties().apply { file.inputStream().use { load(it) } }
    }

    /** Run before restoreCurrentSessionAndLogout in a separate instrumentation process. */
    @Test fun connectLoginAndRecreateAgainstCurrentServer() {
        val input = input()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor("welcome")
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("welcome-connect").fetchSemanticsNodes().firstOrNull()
                    ?.config?.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) == false
            }
            compose.onNodeWithTag("welcome-connect").performClick()
            compose.onNodeWithTag("server-url").performTextInput(input.getProperty("server"))
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            compose.waitForIdle()
            compose.onNodeWithTag("connect-submit").performScrollTo().performClick()
            waitFor("login")
            compose.onNodeWithTag("email").performTextClearance()
            compose.onNodeWithTag("email").performTextInput(input.getProperty("email"))
            compose.onNodeWithTag("password").performTextInput(input.getProperty("password"))
            // Keep the system IME from intercepting the submit button's touch coordinates.
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            compose.waitForIdle()
            compose.onNodeWithTag("login-submit").assertIsEnabled()
            compose.onNodeWithTag("login-submit").performScrollTo().performClick()
            waitFor("workspace")
            assertEquals(input.getProperty("email"), application.repository.state.value.session?.user?.email)
            assertTrue(application.repository.state.value.session!!.supportsPersistentSession)
            assertTrue(application.repository.state.value.session!!.capabilities.recordingModes.isNotEmpty())
            scenario.recreate()
            waitFor("workspace")
            assertEquals(input.getProperty("email"), application.repository.state.value.session?.user?.email)
        }
    }

    @Test fun restoreCurrentSessionAndLogout() {
        val input = input()
        ActivityScenario.launch(MainActivity::class.java).use {
            waitFor("workspace")
            assertEquals(input.getProperty("email"), application.repository.state.value.session?.user?.email)
            assertTrue(application.repository.state.value.session!!.supportsPersistentSession)
            val logout = kotlinx.coroutines.runBlocking { application.repository.logout() }
            assertTrue("The test session must be revoked on the server", logout.remoteConfirmed)
            waitFor("login")
            assertNull(application.repository.state.value.session)
        }
        File(context.filesDir, "live-auth.properties").delete()
    }

    @Test fun connectLoginAndRotate() {
        val input = input()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.setOrientationNatural()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor("welcome")
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("welcome-connect").fetchSemanticsNodes().firstOrNull()
                    ?.config?.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) == false
            }
            compose.onNodeWithTag("welcome-connect").performClick()
            compose.onNodeWithTag("server-url").performTextInput(input.getProperty("server"))
            compose.onNodeWithTag("connect-submit").performScrollTo().performClick()
            waitFor("login")
            compose.onNodeWithTag("email").performTextClearance()
            compose.onNodeWithTag("email").performTextInput(input.getProperty("email"))
            compose.onNodeWithTag("password").performTextInput(input.getProperty("password"))
            compose.onNodeWithTag("login-submit").performScrollTo().performClick()
            waitFor("workspace")
            val state = application.repository.state.value
            assertEquals(input.getProperty("email"), state.session?.user?.email)
            assertNotNull(state.version)
            assertFalse(state.session!!.capabilities.buildVersion.isBlank())
            assertTrue("Server must provide a persistent session", state.session!!.supportsPersistentSession)
            // Read-only stage 05 compatibility: live data must settle without a fake empty state.
            waitFor("meetings-home")
            waitForMeetingLoads()
            assertNoMeetingErrors()
            device.takeScreenshot(File(context.cacheDir, "stage05-live-home.png"))
            compose.onNodeWithTag("tab-calls").performClick()
            waitFor("calls")
            waitForMeetingLoads()
            compose.onNodeWithTag("meeting-view-active").performScrollTo().performClick()
            waitForMeetingLoads()
            assertNoMeetingErrors()
            device.takeScreenshot(File(context.cacheDir, "stage05-live-meetings.png"))
            compose.onNodeWithTag("tab-calendar").performClick()
            waitFor("calendar")
            waitForMeetingLoads()
            assertNoMeetingErrors()
            device.takeScreenshot(File(context.cacheDir, "stage05-live-calendar.png"))
            compose.onNodeWithTag("tab-profile").performClick()
            compose.onNodeWithTag("profile").assertExists()
            compose.onNodeWithTag("account-editor").assertExists()
            device.takeScreenshot(File(context.cacheDir, "stage05-live-account.png"))
            device.setOrientationLeft()
            compose.waitUntil(10_000) { context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
            waitFor("profile")
            compose.onNodeWithTag("tab-profile").assertIsSelected()
            device.setOrientationNatural()
            compose.waitUntil(10_000) { context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
            waitFor("profile")
            scenario.recreate()
            waitFor("profile")
            compose.onNodeWithTag("tab-profile").assertIsSelected()
            compose.onNodeWithTag("theme-dark").performScrollTo().performClick()
            compose.waitUntil(10_000) { application.repository.state.value.theme == ThemeMode.DARK }
        }
        device.unfreezeRotation()
        // A second instrumentation process checks persisted session and then explicitly logs out.
    }

    @Test fun restoreAfterProcessRestartAndLogout() {
        val input = input()
        ActivityScenario.launch(MainActivity::class.java).use {
            waitFor("workspace")
            assertEquals(input.getProperty("email"), application.repository.state.value.session?.user?.email)
            assertEquals(ThemeMode.DARK, application.repository.state.value.theme)
            compose.onNodeWithTag("tab-profile").performClick()
            compose.onNodeWithTag("logout").performScrollTo().performClick()
            waitFor("login")
            assertNull(application.repository.state.value.session)
            assertNull(application.repository.state.value.selectedProfile?.accountId)
            assertNull("Remote logout should be confirmed", compose.onAllNodesWithTag("notice").fetchSemanticsNodes().firstOrNull())
        }
        File(context.filesDir, "live-auth.properties").delete()
    }

    private fun waitFor(tag: String) {
        compose.waitUntil(60_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun waitForMeetingLoads() {
        compose.waitUntil(60_000) { compose.onAllNodesWithText("Загружаем…").fetchSemanticsNodes().isEmpty() }
        compose.waitForIdle()
    }

    private fun assertNoMeetingErrors() {
        assertTrue("Live meetings API must load successfully", compose.onAllNodesWithTag("meeting-load-error").fetchSemanticsNodes().isEmpty())
        assertTrue("No meeting mutation or server error expected in read-only flow", compose.onAllNodesWithTag("meeting-message").fetchSemanticsNodes().isEmpty())
    }
}
