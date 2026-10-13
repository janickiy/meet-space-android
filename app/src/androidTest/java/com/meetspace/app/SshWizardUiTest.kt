package com.meetspace.app

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import com.meetspace.app.ui.SshWizardUi
import com.meetspace.core.designsystem.MeetSpaceTheme
import com.meetspace.core.model.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** All identities and credentials below are test fixtures, never production defaults. */
class SshWizardUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun detailsSubmitOnlyNonSecretInputsAndNoCredentialsExistBeforeTrust() {
        var values: List<String>? = null
        val editor = FormEditorWindow()
        compose.setContent {
            editor.Observe()
            TestWizard(SshWizardUiState(), onProbe = { h, p, u, d -> values = listOf(h, p, u, d) })
        }
        compose.onNodeWithTag("ssh-host").performScrollTo().performTextInput("vps.example.test")
        compose.onNodeWithTag("ssh-user").performScrollTo().performTextClearance()
        compose.onNodeWithTag("ssh-user").performTextInput("admin")
        compose.onNodeWithTag("ssh-domain").performScrollTo().performTextInput("meet.example.test")
        compose.onNodeWithTag("ssh-password").assertDoesNotExist()
        compose.onNodeWithTag("ssh-import-key").assertDoesNotExist()
        compose.settleFormEditorForClick(editor, compose.onNodeWithTag("ssh-probe")).performClick()
        compose.runOnIdle { assertEquals(listOf("vps.example.test", "22", "admin", "meet.example.test"), values) }
    }

    @Test fun independentFingerprintComparisonIsRequiredForNewTrust() {
        var confirmations = 0
        compose.setContent { TestWizard(verify, onTrust = { confirmations++ }) }
        compose.onNodeWithTag("ssh-trust").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("ssh-fingerprint-compared").performScrollTo().performClick()
        compose.onNodeWithTag("ssh-trust").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, confirmations) }
        compose.onNodeWithTag("ssh-password").assertDoesNotExist()
    }

    @Test fun changedKeyShowsBothFingerprintsAndRequiresSeparateForgetDialog() {
        var forgotten = 0
        compose.setContent { TestWizard(verify.copy(identity = identity.copy(trust = SshTrust.CHANGED, previous = oldKey)), onForget = { forgotten++ }) }
        compose.onNodeWithTag("ssh-host-changed").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("ssh-old-fingerprint").performScrollTo().assert(hasText(oldKey.fingerprint))
        compose.onNodeWithTag("ssh-new-fingerprint").performScrollTo().assert(hasText(key.fingerprint))
        compose.onNodeWithTag("ssh-trust").assertDoesNotExist()
        compose.onNodeWithTag("ssh-password").assertDoesNotExist()
        compose.onNodeWithTag("ssh-forget-trust").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, forgotten) }
        compose.onNodeWithTag("ssh-forget-confirm").performClick()
        compose.runOnIdle { assertEquals(1, forgotten) }
    }

    @Test fun passwordIsClearedOnSubmitAndIsNotSavedDuringRecreation() {
        var submitted: String? = null
        val editor = FormEditorWindow()
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            editor.Observe()
            TestWizard(auth, onPassword = { submitted = it })
        }
        compose.onNodeWithTag("ssh-password").performScrollTo().performTextInput("test-only-password")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("ssh-password").assert(hasText(""))
        compose.onNodeWithTag("ssh-preflight").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("ssh-password").performScrollTo().performTextInput("second-test-only")
        compose.settleFormEditorForClick(editor, compose.onNodeWithTag("ssh-preflight")).performClick()
        compose.runOnIdle { assertEquals("second-test-only", submitted) }
        compose.onNodeWithTag("ssh-password").assert(hasText(""))
    }

    @Test fun cancellationClearsPasswordAndLeavesNoEnabledOperationWhileBusy() {
        var state by mutableStateOf(auth)
        var cancellations = 0
        compose.setContent { TestWizard(state, onCancel = { cancellations++; state = state.copy(busy = false, credentialReset = state.credentialReset + 1) }) }
        compose.onNodeWithTag("ssh-password").performScrollTo().performTextInput("test-only-password")
        compose.runOnIdle { state = state.copy(busy = true, operation = "Тест проверки") }
        compose.onNodeWithTag("ssh-preflight").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("ssh-cancel").performScrollTo().performClick()
        compose.onNodeWithTag("ssh-password").assert(hasText(""))
        compose.runOnIdle { assertEquals(1, cancellations) }
    }

    @Test fun structuredResultsExposeAllLevelsAndNoInstallAction() {
        val checks = PreflightLevel.entries.map { PreflightCheck(it.name.lowercase(), "Проверка ${it.name}", it, "Тестовый результат") }
        compose.setContent { TestWizard(auth.copy(step = SshWizardStep.RESULTS, report = PreflightReport(endpoint, request.domain, key, checks, 0))) }
        checks.forEach { compose.onNodeWithTag("ssh-check-${it.id}").performScrollTo().assertIsDisplayed() }
        compose.onNodeWithTag("ssh-no-install").performScrollTo().assert(hasText("План установки ещё не создан. Изменения на сервере не выполнялись."))
        compose.onNodeWithText("Установить").assertDoesNotExist()
        compose.onNodeWithTag("ssh-preflight").assertDoesNotExist()
    }

    @Composable
    private fun TestWizard(
        state: SshWizardUiState,
        onProbe: (String, String, String, String) -> Unit = { _, _, _, _ -> },
        onTrust: () -> Unit = {}, onForget: () -> Unit = {}, onImport: (Uri) -> Unit = {},
        onPassword: (String) -> Unit = {}, onCancel: () -> Unit = {},
    ) {
        MeetSpaceTheme {
            SshWizardUi(state, onProbe, onTrust, onForget, onImport, {}, onPassword, {}, onCancel, {}, {}, {})
        }
    }

    companion object {
        private val endpoint = SshEndpoint("vps.example.test", 22, "admin")
        private val request = SshRequest(endpoint, "meet.example.test")
        private val key = SshHostKey("ssh-ed25519", "test", "SHA256:new-test-fingerprint")
        private val oldKey = key.copy(fingerprint = "SHA256:old-test-fingerprint")
        private val identity = SshHostIdentity(endpoint, key, SshTrust.NEW)
        private val verify = SshWizardUiState(step = SshWizardStep.VERIFY_HOST, request = request, identity = identity)
        private val auth = verify.copy(step = SshWizardStep.AUTH, identity = identity.copy(trust = SshTrust.TRUSTED))
    }
}
