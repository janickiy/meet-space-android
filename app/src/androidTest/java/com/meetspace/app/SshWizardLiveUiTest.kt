package com.meetspace.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.meetspace.core.model.SshEndpoint
import java.io.File
import java.util.Properties
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Actual wizard + real disposable VM, enabled only by a private runtime fixture. */
class SshWizardLiveUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun wizardConnectsToLinuxAndShowsActualReadOnlyResults() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("sshLive") == "1")
        val file = File(context.filesDir, "ssh-stage03/private-fixture.properties")
        assumeTrue(file.isFile)
        val input = Properties().apply { file.inputStream().use { load(it) } }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor("welcome")
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("welcome-installer").fetchSemanticsNodes().firstOrNull()?.config?.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) == false }
            compose.onNodeWithTag("welcome-installer").performClick()
            waitFor("ssh-details")
            compose.onNodeWithTag("ssh-host").performScrollTo().performTextInput(input.getProperty("host"))
            compose.onNodeWithTag("ssh-port").performScrollTo().performTextClearance()
            compose.onNodeWithTag("ssh-port").performTextInput(input.getProperty("port"))
            compose.onNodeWithTag("ssh-user").performScrollTo().performTextClearance()
            compose.onNodeWithTag("ssh-user").performTextInput(input.getProperty("user"))
            compose.onNodeWithTag("ssh-domain").performScrollTo().performTextInput(input.getProperty("domain"))
            screenshot("ssh-details.png")
            compose.onNodeWithTag("ssh-probe").performScrollTo().performClick()
            waitFor("ssh-host-verification")
            compose.onNodeWithTag("ssh-trust").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithTag("ssh-new-fingerprint").assert(hasText(input.getProperty("primaryKeyFingerprint")))
            screenshot("ssh-fingerprint.png")
            compose.onNodeWithTag("ssh-fingerprint-compared").performScrollTo().performClick()
            compose.onNodeWithTag("ssh-trust").performScrollTo().performClick()
            waitFor("ssh-auth")
            compose.onNodeWithTag("ssh-password").performScrollTo().performTextInput(input.getProperty("password"))
            compose.onNodeWithTag("ssh-preflight").performScrollTo().performClick()
            waitFor("ssh-results")
            compose.onNodeWithTag("ssh-check-os").performScrollTo().assert(hasAnyDescendant(hasText("ubuntu 24.04", substring = true)))
            compose.onNodeWithTag("ssh-check-arch").performScrollTo().assert(hasAnyDescendant(hasText("aarch64", substring = true)))
            compose.onNodeWithTag("ssh-no-install").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("ssh-password").assertDoesNotExist()
            screenshot("ssh-results.png")
            scenario.recreate()
            waitFor("ssh-results")
            compose.onNodeWithTag("ssh-check-os").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("ssh-exit").performScrollTo().performClick()
            waitFor("welcome")
        }
        runBlocking {
            (context.applicationContext as MeetSpaceApplication).sshRepository.forgetHost(
                SshEndpoint(input.getProperty("host"), input.getProperty("port").toInt(), input.getProperty("user")),
            )
        }
    }

    private fun waitFor(tag: String) {
        compose.waitUntil(60_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val directory = File(context.filesDir, "ssh-stage03/screenshots").apply { mkdirs() }
        assertTrue(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(File(directory, name)))
    }
}
