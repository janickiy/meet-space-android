package com.meetspace.app

import com.meetspace.core.model.*
import com.meetspace.core.ssh.SshPreflightRepository
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SshWizardViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    @Test fun firstHostRequiresExplicitTrustBeforeCredentials() = runTest(dispatcher) {
        val repository = FakeSshRepository()
        val model = SshWizardViewModel(repository)
        model.probe("vps.example.test", "22", "admin", "meet.example.test")
        runCurrent()
        assertEquals(SshWizardStep.VERIFY_HOST, model.state.value.step)
        model.preflightPassword("test-only")
        runCurrent()
        assertEquals(0, repository.preflightCalls)
        assertEquals(0, repository.trustCalls)
        model.confirmTrust()
        runCurrent()
        assertEquals(1, repository.trustCalls)
        assertEquals(SshWizardStep.AUTH, model.state.value.step)
    }

    @Test fun changedHostCannotBeConfirmedOrAuthenticated() = runTest(dispatcher) {
        val repository = FakeSshRepository().apply { identity = changedIdentity }
        val model = SshWizardViewModel(repository)
        probe(model)
        model.confirmTrust()
        model.preflightPassword("test-only")
        runCurrent()
        assertEquals(0, repository.trustCalls)
        assertEquals(0, repository.preflightCalls)
        assertEquals(oldKey.fingerprint, model.state.value.identity!!.previous!!.fingerprint)
        assertEquals(newKey.fingerprint, model.state.value.identity!!.key.fingerprint)
    }

    @Test fun cancelledPreflightDestroysOperationCredentialsAndDoesNotPublishResult() = runTest(dispatcher) {
        val repository = FakeSshRepository().apply { identity = trustedIdentity; preflightGate = CompletableDeferred() }
        val model = SshWizardViewModel(repository)
        probe(model)
        model.preflightPassword("test-only")
        model.preflightPassword("second-test-only")
        runCurrent()
        assertEquals(1, repository.preflightCalls)
        assertTrue(repository.lastPassword!!.any { it != 0.toByte() })
        model.cancelOperation()
        runCurrent()
        assertTrue(repository.lastPassword!!.all { it == 0.toByte() })
        repository.preflightGate!!.complete(Unit)
        runCurrent()
        assertNull(model.state.value.report)
        assertFalse(model.state.value.busy)
        assertEquals(SshWizardStep.AUTH, model.state.value.step)
    }

    @Test fun successfulPreflightDestroysCredentialsAndReturnsStructuredReport() = runTest(dispatcher) {
        val repository = FakeSshRepository().apply { identity = trustedIdentity }
        val model = SshWizardViewModel(repository)
        probe(model)
        model.preflightPassword("test-only")
        runCurrent()
        assertEquals(SshWizardStep.RESULTS, model.state.value.step)
        assertTrue(model.state.value.report!!.hasBlockers)
        assertTrue(repository.lastPassword!!.all { it == 0.toByte() })
        assertFalse(model.state.value.toString().contains("test-only"))
    }

    @Test fun hostKeyChangeDuringAuthReturnsBothFingerprintsAndBlocksRetry() = runTest(dispatcher) {
        val repository = FakeSshRepository().apply {
            identity = trustedIdentity
            preflightFailure = SshFailure(SshFailureKind.HOST_KEY_CHANGED, "Ключ сервера изменился.")
            identityAfterAuth = changedIdentity
        }
        val model = SshWizardViewModel(repository)
        probe(model)
        model.preflightPassword("test-only")
        runCurrent()
        assertEquals(SshWizardStep.VERIFY_HOST, model.state.value.step)
        assertEquals(SshTrust.CHANGED, model.state.value.identity!!.trust)
        assertNotNull(model.state.value.identity!!.previous)
        assertTrue(repository.lastPassword!!.all { it == 0.toByte() })
        model.preflightPassword("another-test-only")
        runCurrent()
        assertEquals(1, repository.preflightCalls)
    }

    @Test fun genericFailureDoesNotExposeRawException() = runTest(dispatcher) {
        val repository = FakeSshRepository().apply { inspectFailure = IllegalStateException("secret raw remote output") }
        val model = SshWizardViewModel(repository)
        probe(model)
        assertNotNull(model.state.value.error)
        assertFalse(model.state.value.toString().contains("secret raw remote output"))
        assertEquals(SshWizardStep.DETAILS, model.state.value.step)
    }

    @Test fun cancellingChangedKeyReprobeCannotReenableTrustedAuthentication() = runTest(dispatcher) {
        val repository = FakeSshRepository().apply {
            identity = trustedIdentity
            preflightFailure = SshFailure(SshFailureKind.HOST_KEY_CHANGED, "Ключ сервера изменился.")
            identityAfterAuth = changedIdentity
            inspectAfterAuthGate = CompletableDeferred()
        }
        val model = SshWizardViewModel(repository)
        probe(model)
        model.preflightPassword("test-only")
        runCurrent()
        assertTrue(model.state.value.busy)
        assertEquals(SshWizardStep.DETAILS, model.state.value.step)
        assertNull(model.state.value.identity)
        assertTrue(repository.lastPassword!!.all { it == 0.toByte() })

        model.cancelOperation()
        model.preflightPassword("retry-test-only")
        runCurrent()
        repository.inspectAfterAuthGate!!.complete(Unit)
        runCurrent()

        assertFalse(model.state.value.busy)
        assertEquals(SshWizardStep.DETAILS, model.state.value.step)
        assertNull(model.state.value.identity)
        assertNull(model.state.value.report)
        assertEquals(1, repository.preflightCalls)
    }

    @Test fun privateKeyImportRejectsOversizedFileAndClosesStream() = runTest(dispatcher) {
        val repository = FakeSshRepository().apply { identity = trustedIdentity }
        val model = SshWizardViewModel(repository)
        probe(model)
        val input = RecordingStream(ByteArray(SshWizardViewModel.MAX_PRIVATE_KEY_BYTES + 1) { 7 })
        model.importPrivateKey { input }
        model.state.first { !it.busy }
        assertTrue(input.closed)
        assertFalse(model.state.value.keyLoaded)
        assertTrue(model.state.value.error!!.contains("256"))
        model.preflightKey("test-passphrase")
        runCurrent()
        assertEquals(0, repository.preflightCalls)
    }

    @Test fun importedKeyIsClearedOnBackAndCannotAuthenticateLater() = runTest(dispatcher) {
        val repository = FakeSshRepository().apply { identity = trustedIdentity }
        val model = SshWizardViewModel(repository)
        probe(model)
        model.importPrivateKey { ByteArrayInputStream("fake-test-key".toByteArray()) }
        model.state.first { !it.busy }
        assertTrue(model.state.value.keyLoaded)
        model.back()
        assertFalse(model.state.value.keyLoaded)
        probe(model)
        model.preflightKey("")
        runCurrent()
        assertEquals(0, repository.preflightCalls)
    }

    @Test fun invalidInputsNeverStartHandshake() = runTest(dispatcher) {
        val repository = FakeSshRepository()
        val model = SshWizardViewModel(repository)
        model.probe("host;touch x", "22", "admin", "meet.example.test")
        runCurrent()
        assertEquals(0, repository.inspectCalls)
        assertNotNull(model.state.value.error)
    }

    private fun TestScope.probe(model: SshWizardViewModel) {
        model.probe("vps.example.test", "22", "admin", "meet.example.test")
        runCurrent()
    }

    private class RecordingStream(bytes: ByteArray) : InputStream() {
        private val input = ByteArrayInputStream(bytes)
        var closed = false
        override fun read(): Int = input.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = input.read(b, off, len)
        override fun close() { closed = true; input.close() }
    }

    private class FakeSshRepository : SshPreflightRepository {
        var identity = newIdentity
        var identityAfterAuth: SshHostIdentity? = null
        var inspectFailure: Exception? = null
        var preflightFailure: SshFailure? = null
        var inspectCalls = 0
        var trustCalls = 0
        var preflightCalls = 0
        var lastPassword: ByteArray? = null
        var preflightGate: CompletableDeferred<Unit>? = null
        var inspectAfterAuthGate: CompletableDeferred<Unit>? = null
        override suspend fun inspectHost(endpoint: SshEndpoint): SshHostIdentity {
            inspectCalls++
            if (preflightCalls > 0) inspectAfterAuthGate?.await()
            inspectFailure?.let { throw it }
            return identity
        }
        override suspend fun trustHost(identity: SshHostIdentity) { trustCalls++ }
        override suspend fun forgetHost(endpoint: SshEndpoint) { identity = newIdentity }
        override suspend fun preflight(request: SshRequest, credentials: SshCredentials): PreflightReport {
            preflightCalls++
            lastPassword = (credentials as? SshCredentials.Password)?.password
            identityAfterAuth?.let { identity = it }
            preflightGate?.await()
            preflightFailure?.let { throw it }
            return PreflightReport(endpoint, request.domain, newKey,
                listOf(PreflightCheck("root", "Права", PreflightLevel.BLOCKED, "Нет прав")), 0)
        }
    }

    companion object {
        private val endpoint = SshEndpoint("vps.example.test", 22, "admin")
        private val newKey = SshHostKey("ssh-ed25519", "test-new", "SHA256:test-new")
        private val oldKey = SshHostKey("ssh-ed25519", "test-old", "SHA256:test-old")
        private val newIdentity = SshHostIdentity(endpoint, newKey, SshTrust.NEW)
        private val trustedIdentity = newIdentity.copy(trust = SshTrust.TRUSTED)
        private val changedIdentity = newIdentity.copy(trust = SshTrust.CHANGED, previous = oldKey)
    }
}
