package com.meetspace.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.meetspace.app.ui.InstallerUi
import com.meetspace.core.designsystem.MeetSpaceTheme
import com.meetspace.core.model.*
import com.meetspace.core.ssh.installPhases
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class InstallerUiTest {
    @get:Rule val compose = createComposeRule()
    private val endpoint=SshEndpoint("vps.example.com",22,"root")
    private val plan=InstallPlan("{}","a".repeat(64),"v0.4.0-source.test","b".repeat(40),InstallOptions("meet.example.com","8.8.8.8","owner@example.com"),"arm64",true,2000000000)
    private val ticket=InstallTicket(endpoint,plan,"6f0f7f66-ea40-406e-a582-98bb9df557ca")
    private fun render(state:InstallerUiState,apply:(Boolean,Boolean,Boolean)->Unit={_,_,_->},detach:()->Unit={},control:(String)->Unit={},
        onConnect:(String)->Unit={},onCreateAccount:((String)->Unit)?=null) {
        compose.setContent { MeetSpaceTheme { InstallerUi(state,{}, {_,_,_->},apply,control,detach,{}, onConnect,
            onCreateAccount=onCreateAccount) } }
    }
    @Test fun eachConsentIsRequiredBeforeMutation() {
        var called=false
        render(InstallerUiState(active=true,screen=InstallScreen.PLAN,endpoint=endpoint,plan=plan,needsAccess=false),apply={_,_,_->called=true})
        compose.onNodeWithTag("install-apply").performScrollTo().assertIsNotEnabled()
        for(tag in listOf("install-docker-consent","install-acme-consent")) compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.onNodeWithTag("install-apply").performScrollTo().assertIsNotEnabled();assertFalse(called)
        compose.onNodeWithTag("install-confirm").performScrollTo().performClick()
        compose.onNodeWithTag("install-apply").performScrollTo().assertIsEnabled().performClick()
        org.junit.Assert.assertTrue(called)
    }
    @Test fun detachAndServerCancellationAreDifferentActions() {
        var detached=0;var action=""
        render(InstallerUiState(active=true,screen=InstallScreen.PROGRESS,endpoint=endpoint,plan=plan,ticket=ticket,needsAccess=false,
            status=InstallStatus(ticket.jobId,"running","images",listOf("docker","source"),emptyList(),null)),detach={detached++},control={action=it})
        compose.onNodeWithTag("install-detach").performScrollTo().performClick();assertEquals(1,detached);assertEquals("",action)
        compose.onNodeWithTag("install-cancel").performScrollTo().performClick();assertEquals("cancel",action)
    }
    @Test fun unknownStateNeverShowsSuccessOrConnect() {
        render(InstallerUiState(active=true,screen=InstallScreen.PROGRESS,endpoint=endpoint,plan=plan,ticket=ticket,needsAccess=true))
        compose.onNodeWithTag("install-connect").assertDoesNotExist()
        compose.onNodeWithTag("install-reconnect").performScrollTo().assertIsEnabled()
    }
    @Test fun completedShowsHttpsConnectionOnlyAfterAllPhases() {
        render(InstallerUiState(active=true,screen=InstallScreen.PROGRESS,endpoint=endpoint,plan=plan,ticket=ticket,
            status=InstallStatus(ticket.jobId,"completed","readiness",installPhases,emptyList(),null)))
        compose.onNodeWithTag("install-connect").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("install-resume").assertDoesNotExist();compose.onNodeWithTag("install-cancel").assertDoesNotExist()
    }
    @Test fun completedOffersAccountCreationAndLoginUsingTheVerifiedInstallationDomain() {
        var registrationOrigin:String?=null;var loginOrigin:String?=null
        render(InstallerUiState(active=true,screen=InstallScreen.PROGRESS,endpoint=endpoint,plan=plan,ticket=ticket,
            status=InstallStatus(ticket.jobId,"completed","readiness",installPhases,emptyList(),null)),
            onConnect={loginOrigin=it},onCreateAccount={registrationOrigin=it})
        compose.onNodeWithTag("install-connect").assertDoesNotExist()
        compose.onNodeWithTag("install-register").performScrollTo().assertTextContains("Создать аккаунт").performClick()
        compose.runOnIdle { assertEquals("https://meet.example.com",registrationOrigin);assertEquals(null,loginOrigin) }
        compose.onNodeWithTag("install-login").performScrollTo().assertTextContains("Войти").performClick()
        compose.runOnIdle { assertEquals("https://meet.example.com",loginOrigin) }
    }
}
