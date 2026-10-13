package com.meetspace.app

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
    private fun setupModel(repo: Fake): InstallerViewModel = InstallerViewModel(repo).also { it.foreground(true); it.start(endpoint,options.domain); it.authenticate(options,"test-only",false) }
    @Test fun noApplyWithoutEachConsent() = runTest(dispatcher) {
        val repo=Fake();val model=setupModel(repo);runCurrent()
        model.apply(false,true,true);model.apply(true,false,true);model.apply(true,true,false);runCurrent()
        assertEquals(0,repo.applyCount); assertEquals(InstallScreen.PLAN,model.state.value.screen);model.detach()
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
        var statusGate:CompletableDeferred<Unit>?=null;var applyGate:CompletableDeferred<Unit>?=null;var ignoreStatusCancellation=false
        val mutations=mutableListOf<String>();val captured=mutableListOf<SshCredentials.Password>();val controls=mutableListOf<String>()
        private fun destroy(credentials:SshCredentials) { captured += credentials as SshCredentials.Password;credentials.destroy() }
        private fun status(ticket:InstallTicket,state:String=remoteState)=InstallStatus(ticket.jobId,state,"images",emptyList(),emptyList(),null)
        override suspend fun plan(endpoint:SshEndpoint,credentials:SshCredentials,options:InstallOptions):InstallPlan { destroy(credentials);return if(expired)plan.copy(expiresAt=0) else plan }
        override suspend fun apply(ticket:InstallTicket,credentials:SshCredentials,dockerConsent:Boolean,acmeConsent:Boolean):InstallStatus {
            destroy(credentials);applyCount++;mutations+="apply";check(saved==ticket);if(loseApply)throw java.io.IOException();applyGate?.await();return status(ticket)
        }
        override suspend fun control(action:String,ticket:InstallTicket,credentials:SshCredentials):InstallStatus {
            lastControl=action;controls+=action;if(action=="status")statusCalls++
            val snapshot=remoteState
            try {
                if(action=="status")statusGate?.let { gate -> if(ignoreStatusCancellation)withContext(NonCancellable) { gate.await() } else gate.await() }
                if(missing)throw InstallFailure("STATE_UNAVAILABLE")
                return status(ticket,if(action=="cancel")"cancel_requested" else snapshot)
            } finally { destroy(credentials) }
        }
        override suspend fun pending()=saved
        override suspend fun remember(ticket:InstallTicket) { mutations+="remember";saved=ticket }
        override suspend fun forget() { saved=null }
    }
}
