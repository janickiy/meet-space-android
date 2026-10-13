package com.meetspace.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import com.meetspace.app.ui.InstallerUi
import com.meetspace.core.designsystem.MeetSpaceTheme
import org.junit.Rule
import androidx.test.platform.app.InstrumentationRegistry
import com.meetspace.core.model.*
import com.meetspace.core.ssh.*
import java.io.File
import java.util.Properties
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class InstallerLiveTest {
    @get:Rule val compose = createComposeRule()
    @Test fun signedPlanStatusAndIdempotentResumeOverRealPinnedSsh() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("installerLive") == "1")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val directory=File(context.filesDir,"installer-stage04")
        val input=Properties().apply { File(directory,"access.properties").inputStream().use { load(it) } }
        val endpoint=SshEndpoint(input.getProperty("host"),input.getProperty("port").toInt(),input.getProperty("user"))
        fun credentials()=SshCredentials.Password(input.getProperty("password").toByteArray())
        val ssh=SshPreflightRepositoryFactory.create(context)
        val installer=InstallerFixtureFactory.create(context,directory)
        try {
            ssh.forgetHost(endpoint)
            val identity=ssh.inspectHost(endpoint)
            assertEquals(input.getProperty("fingerprint"),identity.key.fingerprint) // independent VM console fixture
            ssh.trustHost(identity)
            val access=credentials()
            val plan=installer.plan(endpoint,access,InstallOptions("stage04.test","192.168.5.15","test@example.com"))
            assertTrue(access.password.all { it==0.toByte() }); assertNotNull(plan.existingJobId); assertNotNull(plan.existingPlanHash)
            val ticket=InstallTicket(endpoint,plan,requireNotNull(plan.existingJobId))
            installer.remember(ticket)
            assertEquals(ticket,installer.pending())
            val status=installer.control("status",ticket,credentials())
            assertEquals(input.getProperty("jobId"),status.jobId)
            assertEquals("completed",status.state)
            val retry=installer.control("resume",ticket,credentials())
            assertEquals(status.completedPhases,retry.completedPhases);assertEquals("completed",retry.state)
            compose.setContent { MeetSpaceTheme { InstallerUi(
                InstallerUiState(active=true,screen=InstallScreen.PROGRESS,endpoint=endpoint,options=plan.options,plan=plan,ticket=ticket,status=status),
                {}, {_,_,_->}, {_,_,_->}, {}, {}, {}, {},
            ) } }
            compose.onNodeWithTag("install-connect").performScrollTo().assertIsDisplayed()
            val screenshot=File(context.getExternalFilesDir(null),"stage04-live.png")
            screenshot.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        } finally { installer.forget();ssh.forgetHost(endpoint) }
    }
}
