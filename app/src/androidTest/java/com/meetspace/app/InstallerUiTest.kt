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
        onConnect:(String)->Unit={},onCreateAccount:((String)->Unit)?=null,onAuthenticate:(InstallOptions,String,Boolean)->Unit={_,_,_->}) {
        compose.setContent { MeetSpaceTheme { InstallerUi(state,{}, onAuthenticate,apply,control,detach,{}, onConnect,
            onCreateAccount=onCreateAccount) } }
    }
    @Test fun planRequiresNoCertificateEmailAndIgnoresLegacyContact() {
        var submitted:InstallOptions?=null;var password="";var useKey=true
        render(InstallerUiState(active=true,screen=InstallScreen.ACCESS,endpoint=endpoint,
            options=plan.options.copy(domain="",publicIpv4="")),onAuthenticate={options,secret,key -> submitted=options;password=secret;useKey=key})
        compose.onNodeWithTag("install-email").assertDoesNotExist()
        compose.onNodeWithText("Email для сертификата",ignoreCase=true).assertDoesNotExist()
        compose.onNodeWithTag("install-domain").performScrollTo().performTextInput("meet.example.com")
        compose.onNodeWithTag("install-ip").performScrollTo().performTextInput("8.8.8.8")
        compose.onNodeWithTag("install-password").performScrollTo().performTextInput("test-only")
        compose.onNodeWithTag("install-plan").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(InstallOptions("meet.example.com","8.8.8.8",""),submitted)
            assertEquals("test-only",password);assertFalse(useKey)
        }
    }
    @Test fun accessShowsSupportedUbuntuAndMinimumAndRecommendedResources() {
        render(InstallerUiState(active=true,screen=InstallScreen.ACCESS,endpoint=endpoint,options=plan.options))
        compose.onNodeWithTag("install-requirements").performScrollTo()
            .assertTextContains("Ubuntu 22.04 или 24.04 LTS, amd64 или arm64",substring=true)
            .assertTextContains("Минимум: 2 CPU, 4 ГБ RAM и около 20 ГБ свободного диска (18 ГиБ)",substring=true)
            .assertTextContains("Рекомендуется: 4 CPU, 8 ГБ RAM и 30 ГиБ свободно",substring=true)
        compose.onNodeWithText("Для параллельных встреч и записи выбирайте рекомендуемую конфигурацию",substring=true).assertExists()
    }
    @Test fun verifiedHostnameAccessAsksOnlyForAddressWithoutRepeatingSshCredentials() {
        var submitted:InstallOptions?=null; var secret:String?=null; var useKey=true
        render(InstallerUiState(active=true,screen=InstallScreen.ACCESS,endpoint=endpoint,
            options=plan.options.copy(publicIpv4=""),needsAccess=false),onAuthenticate={options,password,key ->
            submitted=options;secret=password;useKey=key
        })
        compose.onNodeWithTag("install-password").assertDoesNotExist()
        compose.onNodeWithText("Пароль SSH").assertDoesNotExist()
        compose.onNodeWithTag("install-domain").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("install-ip").performScrollTo().performTextInput("8.8.8.8")
        compose.onNodeWithTag("install-plan").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(InstallOptions("meet.example.com","8.8.8.8",""),submitted)
            assertEquals("",secret);assertFalse(useKey)
        }
    }
    @Test fun compactPlanShowsMinimumAndCapacityGuidanceBeforeConfirmation() {
        val compactPlan=plan.copy(resourceProfile="compact-test")
        render(InstallerUiState(active=true,screen=InstallScreen.PLAN,endpoint=endpoint,plan=compactPlan,needsAccess=false))
        compose.onNodeWithTag("install-resource-profile").performScrollTo().assertTextEquals("Режим: компактный")
        compose.onNodeWithTag("install-resource-guidance").assertTextContains("Для небольшой нагрузки",substring=true)
            .assertTextContains("2 CPU, 4 ГБ RAM и около 20 ГБ свободного диска (18 ГиБ)",substring=true)
            .assertTextContains("Параллельные встречи и запись",substring=true)
            .assertTextContains("4 CPU, 8 ГБ RAM и 30 ГиБ",substring=true)
        compose.onNodeWithTag("install-apply").performScrollTo().assertIsNotEnabled()
    }
    @Test fun composeOnlyPlanRequestsPluginConsentAndReusesDocker() {
        assertPluginConsent(listOf("Compose"))
    }
    @Test fun buildxOnlyPlanRequestsPluginConsentAndReusesDocker() {
        assertPluginConsent(listOf("Buildx"))
    }
    @Test fun bothMissingDockerPluginsRequirePackageConsent() {
        assertPluginConsent(listOf("Compose","Buildx"))
    }
    private fun assertPluginConsent(plugins:List<String>) {
        val pluginPlan=plan.copy(resourceProfile="compact-test",installDockerPluginsOnly=true,missingDockerPlugins=plugins)
        val names=plugins.joinToString("/")
        var called=false
        render(InstallerUiState(active=true,screen=InstallScreen.PLAN,endpoint=endpoint,plan=pluginPlan,needsAccess=false),apply={_,_,_->called=true})
        compose.onNodeWithText("Установить недостающие компоненты Docker ($names); использовать установленный Docker",substring=true).assertExists()
        compose.onNodeWithText("Разрешаю установить недостающие компоненты Docker ($names) из официального репозитория").assertExists()
        compose.onNodeWithTag("install-confirm").performScrollTo().performClick()
        compose.onNodeWithTag("install-acme-consent").performScrollTo().performClick()
        compose.onNodeWithTag("install-apply").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("install-docker-consent").performScrollTo().performClick()
        compose.onNodeWithTag("install-apply").performScrollTo().assertIsEnabled().performClick()
        org.junit.Assert.assertTrue(called)
    }
    @Test fun eachConsentIsRequiredBeforeMutation() {
        var called=false
        render(InstallerUiState(active=true,screen=InstallScreen.PLAN,endpoint=endpoint,plan=plan,needsAccess=false),apply={_,_,_->called=true})
        compose.onNodeWithTag("install-resource-profile").assertTextEquals("Режим: стандартный")
        compose.onNodeWithTag("install-resource-guidance").assertDoesNotExist()
        compose.onNodeWithTag("install-apply").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("install-confirm").performScrollTo().performClick()
        compose.onNodeWithTag("install-apply").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("install-docker-consent").performScrollTo().performClick()
        compose.onNodeWithTag("install-apply").performScrollTo().assertIsNotEnabled();assertFalse(called)
        compose.onNodeWithText("Принимаю условия Let's Encrypt").assertExists()
        compose.onNodeWithText("email будет передан",substring=true).assertDoesNotExist()
        compose.onNodeWithTag("install-acme-consent").performScrollTo().performClick()
        compose.onNodeWithTag("install-apply").performScrollTo().assertIsEnabled().performClick()
        org.junit.Assert.assertTrue(called)
    }
    @Test fun detachAndServerCancellationAreDifferentActions() {
        var detached=0;var action=""
        render(InstallerUiState(active=true,screen=InstallScreen.PROGRESS,endpoint=endpoint,plan=plan,ticket=ticket,needsAccess=false,
            status=InstallStatus(ticket.jobId,"running","images",listOf("docker","source"),emptyList(),null)),detach={detached++},control={action=it})
        compose.onNodeWithTag("install-detach").assertDoesNotExist()
        compose.onNodeWithText("Закрыть наблюдение").assertDoesNotExist()
        androidx.test.uiautomator.UiDevice.getInstance(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()).pressBack()
        compose.runOnIdle { assertEquals(1,detached);assertEquals("",action) }
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
