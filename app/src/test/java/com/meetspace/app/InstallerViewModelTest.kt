package com.meetspace.app

import androidx.lifecycle.ViewModelStore
import com.meetspace.core.model.*
import com.meetspace.core.ssh.SshInstallerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InstallerViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    private val endpoint = SshEndpoint("vps.example.com",22,"root")
    private val options = InstallOptions("meet.example.com","8.8.8.8","owner@example.com")
    private val plan get() = InstallPlan("{}","a".repeat(64),"v0.4.0-source.test","b".repeat(40),options,"arm64",true,Long.MAX_VALUE)
    private val hostKey = SshHostKey("ssh-ed25519", "test-key", "SHA256:test-key")
    private fun verified(target: SshEndpoint = endpoint, domain: String = options.domain, bytes: ByteArray = "test-only".toByteArray()) =
        VerifiedInstallationAccess(SshRequest(target, domain), hostKey, SshCredentials.Password(bytes), 0) { 0 }
    private fun setupModel(repo: Fake): InstallerViewModel = InstallerViewModel(repo).also { it.foreground(true); it.start(endpoint,options.domain); it.authenticate(options,"test-only",false) }
    @Test fun noApplyWithoutEachConsent() = runTest(dispatcher) {
        val repo=Fake();val model=setupModel(repo);runCurrent()
        model.apply(false,true,true);model.apply(true,false,true);model.apply(true,true,false);runCurrent()
        assertEquals(0,repo.applyCount); assertEquals(InstallScreen.PLAN,model.state.value.screen);model.detach()
    }
    @Test fun verifiedIpAccessPreparesPlanWithoutSecondPasswordAndStillRequiresConsent() = runTest(dispatcher) {
        val target = endpoint.copy(host = "8.8.8.8")
        val original = "test-only".toByteArray()
        val repo = Fake(); val model = InstallerViewModel(repo)
        val transfer = verified(target, bytes = original)
        model.start(target, options.domain, transfer); runCurrent()
        model.start(target, options.domain) // A duplicate wizard tap must not reopen access.
        runCurrent()
        assertEquals(InstallScreen.PLAN, model.state.value.screen)
        assertFalse(model.state.value.needsAccess); assertEquals(1, repo.planCalls)
        assertEquals(listOf(hostKey), repo.hostKeys)
        assertEquals(0, repo.applyCount); assertNull(repo.saved)
        assertTrue(original.any { it != 0.toByte() })
        assertTrue(repo.captured.all { it.password.all { byte -> byte == 0.toByte() } })
        assertNull(transfer.take(SshRequest(target, options.domain)))
        model.apply(false, true, true); runCurrent(); assertEquals(0, repo.applyCount)
        model.apply(true, true, true); runCurrent(); assertEquals(1, repo.applyCount)
        assertTrue(repo.hostKeys.all { it == hostKey })
        model.detach(); assertTrue(original.all { it == 0.toByte() })
    }

    @Test fun verifiedHostNameAsksOnlyForIpAndUsesExistingAccess() = runTest(dispatcher) {
        val repo = Fake(); val model = InstallerViewModel(repo)
        model.start(endpoint, options.domain, verified()); runCurrent()
        assertEquals(InstallScreen.ACCESS, model.state.value.screen)
        assertFalse(model.state.value.needsAccess); assertEquals(0, repo.planCalls)
        model.authenticate(options, "", false); runCurrent()
        assertEquals(1, repo.planCalls); assertEquals(InstallScreen.PLAN, model.state.value.screen)
        assertEquals(hostKey, repo.hostKeys.single()); model.detach()
    }

    @Test fun verifiedPrivateKeyAndPassphrasePrepareWithoutReimportAndAreErasedOnDetach() = runTest(dispatcher) {
        val target = endpoint.copy(host = "8.8.8.8")
        val bytes = "test-private-key".toByteArray(); val phrase = "test-passphrase".toByteArray()
        val transfer = VerifiedInstallationAccess(SshRequest(target, options.domain), hostKey,
            SshCredentials.PrivateKey(bytes, phrase), 0) { 0 }
        val repo = Fake(); val model = InstallerViewModel(repo)
        model.start(target, options.domain, transfer); runCurrent()
        assertEquals(1, repo.planCalls); assertFalse(model.state.value.needsAccess)
        val copy = repo.allCaptured.single() as SshCredentials.PrivateKey
        assertTrue(copy.key.all { it == 0.toByte() }); assertTrue(copy.passphrase.all { it == 0.toByte() })
        assertTrue(bytes.any { it != 0.toByte() }); assertTrue(phrase.any { it != 0.toByte() })
        model.detach()
        assertTrue(bytes.all { it == 0.toByte() }); assertTrue(phrase.all { it == 0.toByte() })
    }

    @Test fun waitingForHostnameIpExpiresTransferredAccessWithoutContactingServer() = runTest(dispatcher) {
        val bytes = "test-only".toByteArray(); val repo = Fake(); val model = InstallerViewModel(repo)
        model.start(endpoint, options.domain, verified(bytes = bytes)); runCurrent()
        advanceTimeBy(VerifiedInstallationAccess.TTL_MILLIS); runCurrent()
        assertEquals(0, repo.planCalls); assertTrue(model.state.value.needsAccess)
        assertTrue(bytes.all { it == 0.toByte() }); assertNotNull(model.state.value.error)
        model.detach()
    }

    @Test fun closingViewModelErasesTransferredAccess() = runTest(dispatcher) {
        val bytes = "test-only".toByteArray(); val model = InstallerViewModel(Fake())
        val store = ViewModelStore().apply { put("install", model) }
        model.start(endpoint, options.domain, verified(bytes = bytes)); runCurrent()
        store.clear(); runCurrent()
        assertTrue(bytes.all { it == 0.toByte() }); assertTrue(model.state.value.needsAccess)
        assertFalse(model.state.value.active)
    }

    @Test fun changedDomainCannotReuseVerifiedHostNameAccess() = runTest(dispatcher) {
        val original = "test-only".toByteArray(); val repo = Fake(); val model = InstallerViewModel(repo)
        model.start(endpoint, options.domain, verified(bytes = original)); runCurrent()
        model.authenticate(options.copy(domain = "different.example.com"), "", false); runCurrent()
        assertEquals(0, repo.planCalls); assertTrue(model.state.value.needsAccess)
        assertTrue(original.all { it == 0.toByte() }); model.detach()
    }

    @Test fun mismatchedTransferCannotAuthenticateAndIsErased() = runTest(dispatcher) {
        val original = "test-only".toByteArray(); val repo = Fake(); val model = InstallerViewModel(repo)
        model.start(endpoint.copy(user = "other"), options.domain, verified(bytes = original)); runCurrent()
        assertTrue(model.state.value.needsAccess); assertEquals(0, repo.planCalls)
        assertTrue(original.all { it == 0.toByte() }); model.detach()
    }

    @Test fun savedJobOnAnotherEndpointNeverReceivesTransferredCredentialsEvenDuringPendingLoad() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val repo = Fake().apply { saved = InstallTicket(endpoint.copy(user = "other"), plan, "6f0f7f66-ea40-406e-a582-98bb9df557ca"); pendingGate = gate }
        val original = "test-only".toByteArray(); val target = endpoint.copy(host = "8.8.8.8")
        val model = InstallerViewModel(repo)
        model.start(target, options.domain, verified(target, bytes = original)); runCurrent()
        assertTrue(model.state.value.busy)
        gate.complete(Unit); runCurrent()
        assertEquals(0, repo.planCalls); assertTrue(repo.controls.isEmpty())
        assertEquals(repo.saved!!.endpoint, model.state.value.endpoint)
        assertTrue(model.state.value.needsAccess); assertTrue(original.all { it == 0.toByte() })
        model.detach()
    }

    @Test fun matchingPendingJobIsReconciledReadOnlyWithTransferredAccess() = runTest(dispatcher) {
        val repo = Fake().apply { saved = InstallTicket(endpoint, plan, "6f0f7f66-ea40-406e-a582-98bb9df557ca") }
        val model = InstallerViewModel(repo); runCurrent()
        model.start(endpoint, options.domain, verified()); runCurrent()
        assertEquals(0, repo.planCalls); assertEquals(listOf("status"), repo.controls)
        assertEquals(listOf(hostKey), repo.hostKeys); assertEquals(0, repo.applyCount)
        assertFalse(model.state.value.needsAccess); model.detach()
    }

    @Test fun lateCancelledPlanCannotOverwriteNewSessionOrRetainOldSecret() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>(); val target = endpoint.copy(host = "8.8.8.8")
        val repo = Fake().apply { planGate = gate; ignorePlanCancellation = true }
        val model = InstallerViewModel(repo); val oldBytes = "old-test-only".toByteArray()
        model.start(target, options.domain, verified(target, bytes = oldBytes)); runCurrent()
        model.detach(); assertTrue(oldBytes.all { it == 0.toByte() })
        repo.planGate = null; repo.ignorePlanCancellation = false
        model.start(endpoint, options.domain); runCurrent()
        gate.complete(Unit); runCurrent()
        assertEquals(endpoint, model.state.value.endpoint); assertEquals(InstallScreen.ACCESS, model.state.value.screen)
        assertNull(model.state.value.plan); assertFalse(model.state.value.busy); assertTrue(model.state.value.needsAccess)
        assertTrue(repo.captured.all { it.password.all { byte -> byte == 0.toByte() } })
        model.detach()
    }

    @Test fun completedCancelAndErrorsEraseTransferredOriginal() = runTest(dispatcher) {
        for (outcome in listOf("completed", "cancelled", "failed", "cancel", "error")) {
            val target = endpoint.copy(host = "8.8.8.8"); val bytes = "test-only".toByteArray()
            val repo = Fake(); val model = InstallerViewModel(repo)
            if (outcome == "error") repo.planFailure = "SOURCE_INVALID"
            model.start(target, options.domain, verified(target, bytes = bytes)); runCurrent()
            if (outcome != "error") {
                repo.remoteState = if (outcome == "cancel") "running" else outcome
                model.apply(true, true, true); runCurrent()
                if (outcome == "cancel") { model.control("cancel"); runCurrent() }
            }
            assertTrue("$outcome must erase original", bytes.all { it == 0.toByte() })
            assertTrue(model.state.value.needsAccess); model.detach()
        }
    }
    @Test fun rejectedPlanStaysOnAccessClearsCredentialsAndExplainsRecovery() = runTest(dispatcher) {
        val guidance = mapOf(
            "INVALID_REQUEST" to listOf("Обновите Android-приложение", "повторите получение плана"),
            "SOURCE_INVALID" to listOf("GitHub", "некорректные данные", "Повторите получение плана"),
            "SOURCE_INCOMPATIBLE" to listOf("несовместима", "Обновите Android-приложение"),
            "UNPINNED_BASE_IMAGE" to listOf("несовместима", "Обновите Android-приложение"),
            "DISK_SPACE" to listOf("18 ГиБ", "рекомендуется 30 ГиБ", "Освободите место"),
            "RESOURCES_INSUFFICIENT" to listOf("2 CPU", "4 ГБ RAM", "3 ГиБ", "рекомендуются 4 CPU и 8 ГБ RAM"),
            "UNSUPPORTED_PLATFORM" to listOf("Ubuntu 22.04 и 24.04 LTS", "systemd", "amd64 или arm64"),
            "SYSTEMD_REQUIRED" to listOf("Ubuntu 22.04 и 24.04 LTS", "systemd", "amd64 или arm64"),
        )
        // Retain the actual secret buffer to verify erasure, beyond the UI reset signal.
        val credentialsField = InstallerViewModel::class.java.getDeclaredField("credentials").apply { isAccessible = true }
        for ((code, expectedGuidance) in guidance) {
            val repo = Fake().apply { planFailure = code }
            val model = setupModel(repo)
            val retained = credentialsField.get(model) as SshCredentials.Password
            val resetBeforeFailure = model.state.value.credentialReset
            runCurrent()

            val rejected = model.state.value
            assertEquals(code, rejected.errorCode)
            assertEquals(InstallScreen.ACCESS, rejected.screen)
            assertNull(rejected.plan); assertNull(rejected.ticket); assertNull(rejected.pending); assertNull(rejected.status)
            assertTrue(rejected.needsAccess); assertFalse(rejected.busy); assertFalse(rejected.observing); assertFalse(rejected.keyLoaded)
            assertEquals(resetBeforeFailure + 1, rejected.credentialReset)
            assertNull(credentialsField.get(model))
            assertArrayEquals(ByteArray(retained.password.size), retained.password)
            assertEquals(1, repo.captured.size)
            assertTrue(repo.captured.all { it.password.all { byte -> byte == 0.toByte() } })
            expectedGuidance.forEach { assertTrue("$code should explain $it", rejected.error.orEmpty().contains(it)) }

            model.apply(true, true, true); runCurrent()
            assertEquals(0, repo.applyCount); assertNull(repo.saved); assertTrue(repo.mutations.isEmpty()); assertTrue(repo.controls.isEmpty())

            repo.planFailure = null
            model.authenticate(options, "retry-only", false); runCurrent()
            assertEquals(InstallScreen.PLAN, model.state.value.screen)
            assertFalse(model.state.value.needsAccess); assertNull(model.state.value.error)
            assertEquals(0, repo.applyCount)
            model.detach()
        }
    }
    @Test fun ticketPersistedBeforeApplyAndLostResponseDoesNotRetryAutomatically() = runTest(dispatcher) {
        val repo=Fake().apply { loseApply=true };val model=setupModel(repo);runCurrent()
        model.apply(true,true,true);runCurrent()
        assertEquals(listOf("remember","apply"),repo.mutations);assertNotNull(model.state.value.pending)
        assertTrue(model.state.value.needsAccess);assertNull(model.state.value.status)
        assertTrue(repo.captured.all { it.password.all { byte -> byte==0.toByte() } })
        model.reconnect();model.authenticate(options,"test-only",false);runCurrent()
        assertEquals(1,repo.applyCount);assertEquals("status",repo.lastControl);model.detach()
    }
    @Test fun detachDoesNotCancelRemoteJob() = runTest(dispatcher) {
        val repo=Fake();val model=setupModel(repo);runCurrent();model.apply(true,true,true);runCurrent()
        model.detach();runCurrent();assertNull(repo.lastControl);assertNotNull(model.state.value.pending);assertFalse(model.state.value.active)
    }
    @Test fun explicitCancelUsesDurableRemoteSignal() = runTest(dispatcher) {
        val repo=Fake();val model=setupModel(repo);runCurrent();model.apply(true,true,true);runCurrent()
        model.control("cancel");runCurrent();assertEquals("cancel",repo.lastControl);assertEquals("cancel_requested",model.state.value.status!!.state);model.detach()
    }
    @Test fun reconnectToRecoveryDoesNotResumeWithoutUserAction() = runTest(dispatcher) {
        val repo=Fake().apply { saved=InstallTicket(endpoint,plan,"6f0f7f66-ea40-406e-a582-98bb9df557ca"); remoteState="needs_recovery" }
        val model=InstallerViewModel(repo);runCurrent();model.reconnect();model.authenticate(options,"test-only",false);runCurrent()
        assertEquals("status",repo.lastControl);assertEquals(0,repo.applyCount);assertFalse(model.state.value.needsAccess)
        model.control("resume");runCurrent();assertEquals("resume",repo.lastControl);model.detach()
    }
    @Test fun missingJobAllowsOnlyExplicitSameIdRetry() = runTest(dispatcher) {
        val repo=Fake().apply { saved=InstallTicket(endpoint,plan,"6f0f7f66-ea40-406e-a582-98bb9df557ca");missing=true }
        val model=InstallerViewModel(repo);runCurrent();model.reconnect();model.authenticate(options,"test-only",false);runCurrent()
        assertEquals(0,repo.applyCount);assertEquals("STATE_UNAVAILABLE",model.state.value.errorCode);assertFalse(model.state.value.needsAccess)
        val before=repo.saved!!.jobId;model.apply(true,true,true);runCurrent();assertEquals(before,repo.saved!!.jobId);model.detach()
    }
    @Test fun expiredPlanCanBeRefreshedWithoutCreatingAJob() = runTest(dispatcher) {
        val repo=Fake().apply { expired=true };val model=setupModel(repo);runCurrent()
        model.apply(true,true,true);runCurrent();assertEquals(0,repo.applyCount);assertTrue(model.state.value.needsAccess)
        model.reconnect();runCurrent();assertEquals(InstallScreen.ACCESS,model.state.value.screen);assertNull(model.state.value.plan);model.detach()
    }
    @Test fun expiredUnsentTicketReconcilesReadOnlyBeforeNewConfirmation() = runTest(dispatcher) {
        val repo=Fake().apply { saved=InstallTicket(endpoint,plan.copy(expiresAt=0),"6f0f7f66-ea40-406e-a582-98bb9df557ca");missing=true }
        val model=InstallerViewModel(repo);runCurrent();model.reconnect();model.authenticate(options,"test-only",false);runCurrent()
        model.refreshMissingPlan();runCurrent()
        assertEquals(0,repo.applyCount);assertNull(repo.saved);assertNull(model.state.value.pending)
        assertEquals(InstallScreen.PLAN,model.state.value.screen);assertFalse(model.state.value.needsAccess);model.detach()
    }
    @Test fun backgroundObservationStopsFor120SecondsAndResumesWithoutRemoteCancellation() = runTest(dispatcher) {
        val repo=Fake();val model=setupModel(repo);runCurrent()
        model.apply(true,true,true);runCurrent()
        model.foreground(false);runCurrent()
        assertEquals(0,repo.statusCalls)
        advanceTimeBy(120_000);runCurrent()
        assertEquals(0,repo.statusCalls)
        assertFalse(model.state.value.observing)
        assertNotNull(model.state.value.pending)
        assertFalse(model.state.value.needsAccess)
        model.foreground(true);runCurrent()
        advanceTimeBy(120_000);runCurrent()
        assertEquals(40,repo.statusCalls)
        assertEquals(1,repo.applyCount)
        assertTrue(repo.controls.all { it=="status" })
        model.detach()
    }
    @Test fun repeatedForegroundCallbacksKeepExactlyOneObservationLoop() = runTest(dispatcher) {
        val repo=Fake();val model=setupModel(repo);runCurrent();model.apply(true,true,true);runCurrent()
        repeat(10) { model.foreground(true) }
        advanceTimeBy(30_000);runCurrent()
        assertEquals(10,repo.statusCalls)
        repeat(3) {
            model.foreground(false);runCurrent();model.foreground(true);model.foreground(true);runCurrent()
            advanceTimeBy(3_000);runCurrent()
        }
        assertEquals(13,repo.statusCalls)
        assertFalse(model.state.value.needsAccess)
        model.detach()
    }
    @Test fun applyFinishingInBackgroundRetainsTicketAndWaitsForForegroundToObserve() = runTest(dispatcher) {
        val gate=CompletableDeferred<Unit>();val repo=Fake().apply { applyGate=gate }
        val model=setupModel(repo);runCurrent();model.apply(true,true,true);runCurrent()
        assertTrue(model.state.value.busy);assertNotNull(model.state.value.pending)
        model.foreground(false);gate.complete(Unit);runCurrent()
        assertFalse(model.state.value.busy);assertFalse(model.state.value.observing)
        advanceTimeBy(120_000);runCurrent();assertEquals(0,repo.statusCalls)
        model.foreground(true);advanceTimeBy(3_000);runCurrent()
        assertEquals(1,repo.statusCalls);assertEquals(1,repo.applyCount);model.detach()
    }
    @Test fun lateCancelledObserverCannotOverwriteStatusOrClearReplacementLoop() = runTest(dispatcher) {
        val gate=CompletableDeferred<Unit>();val repo=Fake();val model=setupModel(repo);runCurrent();model.apply(true,true,true);runCurrent()
        repo.remoteState="completed";repo.statusGate=gate;repo.ignoreStatusCancellation=true
        advanceTimeBy(3_000);runCurrent();assertEquals(1,repo.statusCalls)
        model.foreground(false);runCurrent()
        repo.statusGate=null;repo.remoteState="running";repo.ignoreStatusCancellation=false
        model.foreground(true);advanceTimeBy(3_000);runCurrent();assertEquals(2,repo.statusCalls)
        gate.complete(Unit);runCurrent()
        assertEquals("running",model.state.value.status!!.state)
        assertTrue(model.state.value.observing);assertFalse(model.state.value.needsAccess)
        advanceTimeBy(6_000);runCurrent();assertEquals(4,repo.statusCalls)
        assertTrue(repo.captured.all { it.password.all { byte -> byte==0.toByte() } })
        model.detach()
    }
    @Test fun terminalStatusDoesNotRestartObservationOnForeground() = runTest(dispatcher) {
        val repo=Fake();val model=setupModel(repo);runCurrent();model.apply(true,true,true);runCurrent()
        repo.remoteState="completed";advanceTimeBy(3_000);runCurrent()
        assertTrue(model.state.value.needsAccess);assertFalse(model.state.value.observing)
        model.foreground(false);model.foreground(true);advanceTimeBy(120_000);runCurrent()
        assertEquals(1,repo.statusCalls);model.detach()
    }
    private inner class Fake : SshInstallerRepository {
        var saved:InstallTicket?=null;var applyCount=0;var statusCalls=0;var loseApply=false;var missing=false;var expired=false;var lastControl:String?=null;var remoteState="running"
        var statusGate:CompletableDeferred<Unit>?=null;var applyGate:CompletableDeferred<Unit>?=null;var ignoreStatusCancellation=false;var planFailure:String?=null
        var planGate:CompletableDeferred<Unit>?=null;var pendingGate:CompletableDeferred<Unit>?=null;var ignorePlanCancellation=false;var planCalls=0
        val mutations=mutableListOf<String>();val captured=mutableListOf<SshCredentials.Password>();val controls=mutableListOf<String>()
        val allCaptured=mutableListOf<SshCredentials>()
        val hostKeys=mutableListOf<SshHostKey?>()
        private fun destroy(credentials:SshCredentials) { allCaptured+=credentials;if(credentials is SshCredentials.Password)captured+=credentials;credentials.destroy() }
        private fun status(ticket:InstallTicket,state:String=remoteState)=InstallStatus(ticket.jobId,state,"images",emptyList(),emptyList(),null)
        override suspend fun plan(endpoint:SshEndpoint,credentials:SshCredentials,options:InstallOptions,expectedHostKey:SshHostKey?):InstallPlan {
            planCalls++;hostKeys+=expectedHostKey
            try {
                planGate?.let { gate -> if(ignorePlanCancellation)withContext(NonCancellable) { gate.await() } else gate.await() }
                planFailure?.let { throw InstallFailure(it) };return if(expired)plan.copy(expiresAt=0) else plan
            } finally { destroy(credentials) }
        }
        override suspend fun apply(ticket:InstallTicket,credentials:SshCredentials,dockerConsent:Boolean,acmeConsent:Boolean,expectedHostKey:SshHostKey?):InstallStatus {
            hostKeys+=expectedHostKey;destroy(credentials);applyCount++;mutations+="apply";check(saved==ticket);if(loseApply)throw java.io.IOException();applyGate?.await();return status(ticket)
        }
        override suspend fun control(action:String,ticket:InstallTicket,credentials:SshCredentials,expectedHostKey:SshHostKey?):InstallStatus {
            hostKeys+=expectedHostKey
            lastControl=action;controls+=action;if(action=="status")statusCalls++
            val snapshot=remoteState
            try {
                if(action=="status")statusGate?.let { gate -> if(ignoreStatusCancellation)withContext(NonCancellable) { gate.await() } else gate.await() }
                if(missing)throw InstallFailure("STATE_UNAVAILABLE")
                return status(ticket,if(action=="cancel")"cancel_requested" else snapshot)
            } finally { destroy(credentials) }
        }
        override suspend fun pending():InstallTicket? { pendingGate?.await();return saved }
        override suspend fun remember(ticket:InstallTicket) { mutations+="remember";saved=ticket }
        override suspend fun forget() { saved=null }
    }
}
