package com.meetspace.app

import androidx.test.platform.app.InstrumentationRegistry
import com.meetspace.core.model.PreflightLevel
import com.meetspace.core.model.SshCredentials
import com.meetspace.core.model.SshEndpoint
import com.meetspace.core.model.SshFailure
import com.meetspace.core.model.SshFailureKind
import com.meetspace.core.model.SshHostIdentity
import com.meetspace.core.model.SshHostKey
import com.meetspace.core.model.SshRequest
import com.meetspace.core.model.SshTrust
import com.meetspace.core.ssh.SshPreflightRepository
import com.meetspace.core.ssh.SshPreflightRepositoryFactory
import java.io.File
import java.util.Properties
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Explicitly opt-in integration against a disposable Linux VM. Secrets arrive in private app
 * files, never source, BuildConfig, runner arguments, test names, assertions, or HTTP credentials.
 * Runner: -e sshLive 1. Fixture directory: targetContext.filesDir/ssh-stage03.
 */
class SshLiveIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixtureDirectory get() = File(context.filesDir, "ssh-stage03")

    private fun input(): Properties {
        assumeTrue("Disposable SSH integration is opt-in", InstrumentationRegistry.getArguments().getString("sshLive") == "1")
        val file = File(fixtureDirectory, "private-fixture.properties")
        assumeTrue("Disposable SSH integration requires a private runtime fixture", file.isFile)
        return Properties().apply { file.inputStream().use { load(it) } }
    }

    private fun endpoint(properties: Properties, portName: String = "port") = SshEndpoint(
        properties.getProperty("host"), properties.getProperty(portName).toInt(), properties.getProperty("user"),
    )

    private suspend fun pin(repository: SshPreflightRepository, endpoint: SshEndpoint): SshHostIdentity {
        repository.forgetHost(endpoint)
        val identity = repository.inspectHost(endpoint)
        assertEquals(SshTrust.NEW, identity.trust)
        assertTrue(identity.key.fingerprint.startsWith("SHA256:"))
        repository.trustHost(identity)
        assertEquals(SshTrust.TRUSTED, repository.inspectHost(endpoint).trust)
        return identity
    }

    @Test
    fun firstUnknownHostIsInspectedWithoutAuthentication() = runBlocking {
        val input = input()
        val endpoint = endpoint(input)
        val repository = SshPreflightRepositoryFactory.create(context)
        repository.forgetHost(endpoint)
        try {
            val identity = repository.inspectHost(endpoint)
            assertEquals(SshTrust.NEW, identity.trust)
            assertEquals("ssh-ed25519", identity.key.algorithm)
            assertTrue(identity.key.fingerprint.startsWith("SHA256:"))
            // The fixture's independent sshd log is captured by the host harness for this method.
            // It must contain zero accepted/failed authentication entries.
        } finally {
            repository.forgetHost(endpoint)
        }
    }

    @Test
    fun explicitPinAllowsPasswordPreflightWithActualLinuxFacts() = runBlocking {
        val input = input()
        val endpoint = endpoint(input)
        val repository = SshPreflightRepositoryFactory.create(context)
        try {
            val identity = pin(repository, endpoint)
            val password = input.getProperty("password").toByteArray(Charsets.UTF_8)
            val report = repository.preflight(SshRequest(endpoint, input.getProperty("domain")), SshCredentials.Password(password))
            assertEquals(endpoint, report.endpoint)
            assertEquals(identity.key, report.hostKey)
            assertTrue(report.inspectedAtEpochMillis > 0)
            assertTrue("Probe must return actual checks", report.checks.isNotEmpty())
            val checks = report.checks.associateBy { it.id }
            assertEquals(PreflightLevel.PASS, checks.getValue("os").level)
            assertTrue("Probe must include Linux OS facts", checks.getValue("os").detail.contains("ubuntu 24.04", ignoreCase = true))
            assertEquals(PreflightLevel.PASS, checks.getValue("arch").level)
            assertTrue("Probe must include ARM64 architecture", checks.getValue("arch").detail.contains("aarch64", ignoreCase = true) || checks.getValue("arch").detail.contains("arm64", ignoreCase = true))
            assertEquals(PreflightLevel.WARN, checks.getValue("cpu").level)
            assertTrue(checks.getValue("cpu").detail.contains("2 логических CPU"))
            assertEquals(PreflightLevel.WARN, checks.getValue("ram").level)
            assertEquals(PreflightLevel.WARN, checks.getValue("disk").level)
            assertEquals(PreflightLevel.PASS, checks.getValue("sudo").level)
            assertEquals(PreflightLevel.WARN, checks.getValue("docker").level)
            assertEquals(PreflightLevel.WARN, checks.getValue("compose").level)
            assertTrue("Password bytes must be zeroed after the operation", password.all { it == 0.toByte() })
        } finally {
            repository.forgetHost(endpoint)
        }
    }

    @Test
    fun encryptedOpenSshRsaKeyAuthenticatesAgainstRealSshd() = encryptedKeyPreflight("rsa")

    @Test
    fun encryptedOpenSshEd25519KeyAuthenticatesAgainstRealSshd() = encryptedKeyPreflight("ed25519")

    private fun encryptedKeyPreflight(kind: String) = runBlocking {
        val input = input()
        val endpoint = endpoint(input)
        val repository = SshPreflightRepositoryFactory.create(context)
        try {
            pin(repository, endpoint)
            val key = File(fixtureDirectory, "client_$kind").readBytes()
            val passphrase = input.getProperty("${kind}Passphrase").toByteArray(Charsets.UTF_8)
            val report = repository.preflight(SshRequest(endpoint, input.getProperty("domain")), SshCredentials.PrivateKey(key, passphrase))
            assertTrue(report.checks.isNotEmpty())
            assertTrue("Private key bytes must be zeroed after the operation", key.all { it == 0.toByte() })
            assertTrue("Passphrase bytes must be zeroed after the operation", passphrase.all { it == 0.toByte() })
        } finally {
            repository.forgetHost(endpoint)
        }
    }

    @Test
    fun wrongPasswordProducesSafeAuthFailure() = runBlocking {
        val input = input()
        val endpoint = endpoint(input)
        val repository = SshPreflightRepositoryFactory.create(context)
        val invalidPassword = "intentionally-invalid-disposable-test-password".toByteArray(Charsets.UTF_8)
        try {
            pin(repository, endpoint)
            try {
                repository.preflight(SshRequest(endpoint, input.getProperty("domain")), SshCredentials.Password(invalidPassword))
                throw AssertionError("Wrong password must never produce preflight success")
            } catch (failure: SshFailure) {
                assertEquals(SshFailureKind.AUTH, failure.kind)
                assertFalse("Failures must not expose fixture credentials", failure.message.contains(input.getProperty("password")))
                assertFalse("Failures must not expose private key content", failure.message.contains("PRIVATE KEY"))
            }
            assertTrue(invalidPassword.all { it == 0.toByte() })
        } finally {
            repository.forgetHost(endpoint)
        }
    }

    @Test
    fun changedHostKeyIsBlockedBeforeAuthenticationAndCannotBeConfirmed() = runBlocking {
        val input = input()
        val endpoint = endpoint(input)
        val repository = SshPreflightRepositoryFactory.create(context)
        val previousKey = SshHostKey(
            input.getProperty("alternateKeyAlgorithm"), input.getProperty("alternateKeyBase64"), input.getProperty("alternateKeyFingerprint"),
        )
        repository.forgetHost(endpoint)
        try {
            // Represents the same endpoint's key pinned before a real host key rotation.
            repository.trustHost(SshHostIdentity(endpoint, previousKey, SshTrust.NEW))
            val changed = repository.inspectHost(endpoint)
            assertEquals(SshTrust.CHANGED, changed.trust)
            assertEquals(previousKey, changed.previous)
            assertNotEquals(previousKey, changed.key)
            try {
                repository.trustHost(changed)
                throw AssertionError("A changed host key must not be confirmed through TOFU")
            } catch (failure: SshFailure) {
                assertEquals(SshFailureKind.HOST_KEY_CHANGED, failure.kind)
            }
            val password = input.getProperty("password").toByteArray(Charsets.UTF_8)
            try {
                repository.preflight(SshRequest(endpoint, input.getProperty("domain")), SshCredentials.Password(password))
                throw AssertionError("A changed host key must block authentication")
            } catch (failure: SshFailure) {
                assertEquals(SshFailureKind.HOST_KEY_CHANGED, failure.kind)
            }
            assertTrue(password.all { it == 0.toByte() })
        } finally {
            repository.forgetHost(endpoint)
        }
    }

    @Test
    fun cancellationClosesStalledHandshakeAndClearsCredentialBytes() = runBlocking {
        val input = input()
        val stalledEndpoint = endpoint(input, "stallPort")
        val repository = SshPreflightRepositoryFactory.create(context)
        val actualIdentity = repository.inspectHost(endpoint(input))
        repository.forgetHost(stalledEndpoint)
        val password = input.getProperty("password").toByteArray(Charsets.UTF_8)
        try {
            repository.trustHost(SshHostIdentity(stalledEndpoint, actualIdentity.key, SshTrust.NEW))
            val started = System.nanoTime()
            try {
                withTimeout(700) {
                    repository.preflight(SshRequest(stalledEndpoint, input.getProperty("domain")), SshCredentials.Password(password))
                }
                throw AssertionError("A stalled handshake must be cancellable")
            } catch (_: TimeoutCancellationException) {
                val elapsedMillis = (System.nanoTime() - started) / 1_000_000
                assertTrue("Cancellation must not wait for the SSH timeout", elapsedMillis < 5_000)
            }
            assertTrue("Cancelled credentials must be zeroed", password.all { it == 0.toByte() })
        } finally {
            repository.forgetHost(stalledEndpoint)
        }
    }
}
