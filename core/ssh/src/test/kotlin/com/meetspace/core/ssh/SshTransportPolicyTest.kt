package com.meetspace.core.ssh

import com.jcraft.jsch.HostKeyRepository
import com.meetspace.core.model.*
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SshTransportPolicyTest {
    private val endpoint = SshEndpoint("vps.example.com", 22, "ubuntu")
    private val request = SshRequest(endpoint, "meet.example.com")

    @Test fun inspectNewHostNeverCallsAuthentication() = runBlocking {
        val store = MemoryPins()
        val transport = FakeTransport(publicKey(1))
        val identity = repository(store, transport).inspectHost(endpoint)
        assertEquals(SshTrust.NEW, identity.trust)
        assertEquals(1, transport.inspections)
        assertEquals(0, transport.authentications)
        assertTrue(store.keys.isEmpty())
    }

    @Test fun inspectKnownHostAlsoNeverCallsAuthentication() = runBlocking {
        val key = publicKey(1)
        val store = MemoryPins().apply { keys[endpoint.pinId] = key }
        val transport = FakeTransport(key)
        assertEquals(SshTrust.TRUSTED, repository(store, transport).inspectHost(endpoint).trust)
        assertEquals(0, transport.authentications)
    }

    @Test fun inspectReportsChangedKeyWithoutOverwritingPin() = runBlocking {
        val before = publicKey(1)
        val actual = publicKey(2)
        val store = MemoryPins().apply { keys[endpoint.pinId] = before }
        val transport = FakeTransport(actual)
        val identity = repository(store, transport).inspectHost(endpoint)
        assertEquals(SshTrust.CHANGED, identity.trust)
        assertEquals(before, identity.previous)
        assertEquals(actual, identity.key)
        assertEquals(before, store.keys[endpoint.pinId])
        assertEquals(0, transport.authentications)
    }

    @Test fun unconfirmedHostRejectsPreflightAndDestroysPassword() = runBlocking {
        val password = "test-only-password".toByteArray()
        val transport = FakeTransport(publicKey(1))
        failure(SshFailureKind.UNTRUSTED_HOST) { repository(MemoryPins(), transport).preflight(request, SshCredentials.Password(password)) }
        assertEquals(0, transport.authentications)
        assertTrue(password.all { it == 0.toByte() })
    }

    @Test fun changedHostRejectsPreflightBeforeAuthenticationAndDestroysKey() = runBlocking {
        val transport = FakeTransport(publicKey(2))
        val store = MemoryPins().apply { keys[endpoint.pinId] = publicKey(1) }
        val key = "test-only-private-key".toByteArray()
        val passphrase = "test-only-passphrase".toByteArray()
        failure(SshFailureKind.HOST_KEY_CHANGED) { repository(store, transport).preflight(request, SshCredentials.PrivateKey(key, passphrase)) }
        assertEquals(0, transport.authentications)
        assertTrue(key.all { it == 0.toByte() })
        assertTrue(passphrase.all { it == 0.toByte() })
    }

    @Test fun trustConfirmationIsAtomicInsertAndCannotReplaceConcurrentPin() = runBlocking {
        val key = publicKey(1)
        val store = MemoryPins()
        val repo = repository(store, FakeTransport(key))
        val identity = repo.inspectHost(endpoint)
        store.keys[endpoint.pinId] = publicKey(2)
        failure(SshFailureKind.HOST_KEY_CHANGED) { repo.trustHost(identity) }
        assertEquals(publicKey(2), store.keys[endpoint.pinId])
    }

    @Test fun changedIdentityCannotBeTrustedAndPublicFingerprintCannotBeForged() = runBlocking {
        val store = MemoryPins()
        val repo = repository(store, FakeTransport(publicKey(1)))
        failure(SshFailureKind.HOST_KEY_CHANGED) { repo.trustHost(SshHostIdentity(endpoint, publicKey(2), SshTrust.CHANGED, publicKey(1))) }
        failure(SshFailureKind.INVALID_RESPONSE) { repo.trustHost(SshHostIdentity(endpoint, publicKey(1).copy(fingerprint = "SHA256:forged"), SshTrust.NEW)) }
        assertTrue(store.keys.isEmpty())
    }

    @Test fun pinIsIsolatedByPortAndHostAndCanonicalizesHostname() = runBlocking {
        val key = publicKey(1)
        val store = MemoryPins()
        val transport = FakeTransport(key)
        val repo = repository(store, transport)
        repo.trustHost(SshHostIdentity(endpoint.copy(host = "VPS.EXAMPLE.COM"), key, SshTrust.NEW))
        assertEquals(SshTrust.TRUSTED, repo.inspectHost(endpoint).trust)
        assertEquals(SshTrust.NEW, repo.inspectHost(endpoint.copy(port = 2222)).trust)
        assertEquals(SshTrust.NEW, repo.inspectHost(endpoint.copy(host = "other.example.com")).trust)
        failure(SshFailureKind.UNTRUSTED_HOST) { repo.preflight(request.copy(endpoint = endpoint.copy(port = 2222)), SshCredentials.Password(byteArrayOf(1))) }
        assertEquals(0, transport.authentications)
    }

    @Test fun forgetDeletesOnlyExactHostAndPort() = runBlocking {
        val store = MemoryPins().apply { keys[endpoint.pinId] = publicKey(1); keys[endpoint.copy(port = 2222).pinId] = publicKey(2) }
        repository(store, FakeTransport(publicKey(1))).forgetHost(endpoint)
        assertNull(store.keys[endpoint.pinId])
        assertEquals(publicKey(2), store.keys[endpoint.copy(port = 2222).pinId])
    }

    @Test fun storageFailurePreventsAuthenticationAndClearsSecrets() = runBlocking {
        val store = MemoryPins().apply { failRead = true }
        val transport = FakeTransport(publicKey(1))
        val password = byteArrayOf(1, 2, 3)
        failure(SshFailureKind.STORAGE) { repository(store, transport).preflight(request, SshCredentials.Password(password)) }
        assertEquals(0, transport.authentications)
        assertTrue(password.all { it == 0.toByte() })
    }

    @Test fun storageWriteFailureDoesNotCreateTrust() = runBlocking {
        val store = MemoryPins().apply { failWrite = true }
        val repo = repository(store, FakeTransport(publicKey(1)))
        failure(SshFailureKind.STORAGE) { repo.trustHost(repo.inspectHost(endpoint)) }
        failure(SshFailureKind.UNTRUSTED_HOST) { repo.preflight(request, SshCredentials.Password(byteArrayOf(1))) }
        Unit
    }

    @Test fun validatedRequestAndExactPinReachTransportAndSecretsAreDestroyedAfterSuccess() = runBlocking {
        val key = publicKey(1)
        val store = MemoryPins().apply { keys[endpoint.pinId] = key }
        val transport = FakeTransport(key)
        val password = byteArrayOf(4, 5, 6)
        val report = repository(store, transport).preflight(request.copy(domain = "MEET.EXAMPLE.COM"), SshCredentials.Password(password))
        assertEquals("meet.example.com", report.domain)
        assertEquals(key, report.hostKey)
        assertEquals(1, transport.authentications)
        assertTrue(password.all { it == 0.toByte() })
    }

    @Test fun invalidInputIsRejectedBeforeTransportAndClearsCredentials() = runBlocking {
        val transport = FakeTransport(publicKey(1))
        val key = byteArrayOf(1, 2)
        val passphrase = byteArrayOf(3, 4)
        failure(SshFailureKind.INPUT) { repository(MemoryPins(), transport).preflight(request.copy(domain = "x';rm -rf /"), SshCredentials.PrivateKey(key, passphrase)) }
        assertEquals(0, transport.authentications)
        assertTrue(key.all { it == 0.toByte() } && passphrase.all { it == 0.toByte() })
    }

    @Test fun authenticationFailureUsesSafeTextAndClearsCredentials() = runBlocking {
        val secret = "test-only-password"
        val password = secret.toByteArray()
        val transport = FakeTransport(publicKey(1)).apply { authFailure = true }
        val store = MemoryPins().apply { keys[endpoint.pinId] = publicKey(1) }
        val failure = failure(SshFailureKind.AUTH) { repository(store, transport).preflight(request, SshCredentials.Password(password)) }
        assertFalse(failure.message.contains(secret))
        assertTrue(password.all { it == 0.toByte() })
    }

    @Test fun ownDeadlineReportsTimeoutAndCancelsTransport() = runBlocking {
        val transport = FakeTransport(publicKey(1)).apply { stall = true }
        val store = MemoryPins().apply { keys[endpoint.pinId] = publicKey(1) }
        val password = byteArrayOf(1, 2, 3)
        failure(SshFailureKind.TIMEOUT) { repository(store, transport, 50).preflight(request, SshCredentials.Password(password)) }
        assertTrue(transport.cancelled)
        assertTrue(password.all { it == 0.toByte() })
    }

    @Test fun callerCancellationPropagatesAndClearsKeyAndPassphrase() = runBlocking {
        val transport = FakeTransport(publicKey(1)).apply { stall = true }
        val store = MemoryPins().apply { keys[endpoint.pinId] = publicKey(1) }
        val key = byteArrayOf(1, 2)
        val passphrase = byteArrayOf(3, 4)
        try {
            withTimeout(50) { repository(store, transport).preflight(request, SshCredentials.PrivateKey(key, passphrase)) }
            fail("Cancellation must propagate")
        } catch (_: TimeoutCancellationException) { }
        assertTrue(transport.cancelled)
        assertTrue(key.all { it == 0.toByte() } && passphrase.all { it == 0.toByte() })
    }

    @Test fun inspectionCallbackAbortsBeforeReturningOkEvenWhenPinMatches() {
        val key = publicKey(1)
        val guard = PinnedHostKeyRepository(endpoint, key, inspectionOnly = true)
        try { guard.check(endpoint.host, HostKeyCodec.validate(key)); fail("Must abort before authentication") }
        catch (_: InspectionComplete) { }
        assertFalse(guard.verified)
        assertEquals(key, guard.observed)
    }

    @Test fun pinnedCallbackOnlyAcceptsExactKeyAndExactHostAndPort() {
        val key = publicKey(1)
        val guard = PinnedHostKeyRepository(endpoint.copy(port = 2222), key, inspectionOnly = false)
        assertEquals(HostKeyRepository.NOT_INCLUDED, guard.check(endpoint.host, HostKeyCodec.validate(key)))
        assertEquals(HostKeyRepository.NOT_INCLUDED, guard.check("[${endpoint.host}]:2223", HostKeyCodec.validate(key)))
        assertFalse(guard.verified)
        assertEquals(HostKeyRepository.CHANGED, guard.check("[${endpoint.host}]:2222", HostKeyCodec.validate(publicKey(2))))
        assertTrue(guard.changed)
        assertFalse(guard.verified)
        val accepted = PinnedHostKeyRepository(endpoint.copy(port = 2222), key, inspectionOnly = false)
        assertEquals(HostKeyRepository.OK, accepted.check("[${endpoint.host}]:2222", HostKeyCodec.validate(key)))
        assertTrue(accepted.verified)
    }

    @Test fun stdoutAndDiscardedStderrAreBothBounded() {
        val operation = SshOperation()
        val stdout = BoundedProbeOutput(8, operation)
        stdout.write("12345678".toByteArray())
        assertEquals("12345678", stdout.text())
        try { stdout.write(9); fail("Must bound stdout") } catch (_: java.io.IOException) { }
        assertTrue(stdout.exceeded)
        val stderr = BoundedProbeOutput(4, operation, discard = true)
        stderr.write("abcd".toByteArray())
        assertEquals("", stderr.text())
        try { stderr.write(5); fail("Must bound stderr") } catch (_: java.io.IOException) { }
        assertTrue(stderr.exceeded)
    }

    @Test fun cancellationClosesActualTcpSocketDuringStalledHandshake() = runBlocking {
        ServerSocket(0).use { server ->
            val accepted = CountDownLatch(1)
            val eof = CountDownLatch(1)
            val reader = Thread {
                server.accept().use { socket ->
                    accepted.countDown()
                    socket.soTimeout = 3000
                    try { while (socket.getInputStream().read() != -1) { } ; eof.countDown() }
                    catch (_: Exception) { }
                }
            }.apply { isDaemon = true; start() }
            val stalled = endpoint.copy(host = "127.0.0.1", port = server.localPort)
            val started = System.nanoTime()
            try { withTimeout(300) { JschSshTransport().inspect(stalled) }; fail("Must cancel") }
            catch (_: TimeoutCancellationException) { }
            assertTrue(accepted.await(1, TimeUnit.SECONDS))
            assertTrue("TCP socket must close on cancellation", eof.await(2, TimeUnit.SECONDS))
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 3000)
            reader.join(1000)
        }
    }

    private fun repository(store: MemoryPins, transport: FakeTransport, timeout: Long = 45_000) =
        DefaultSshPreflightRepository(store, transport, { NetworkReadiness(listOf("192.0.2.1"), TlsReadiness.VALID) }, timeout)

    private suspend fun failure(kind: SshFailureKind, operation: suspend () -> Any?): SshFailure {
        try { operation(); fail("Expected $kind") }
        catch (failure: SshFailure) { assertEquals(kind, failure.kind); return failure }
        throw AssertionError("Expected failure")
    }

    private class MemoryPins : HostPinStore {
        val keys = mutableMapOf<String, SshHostKey>()
        var failRead = false
        var failWrite = false
        override suspend fun read(endpoint: SshEndpoint): SshHostKey? { if (failRead) throw storageFailure(); return keys[endpoint.pinId] }
        override suspend fun trustNew(endpoint: SshEndpoint, key: SshHostKey) {
            if (failWrite) throw storageFailure()
            val existing = keys[endpoint.pinId]
            if (existing != null && existing != key) throw changedHostFailure()
            if (existing == null) keys[endpoint.pinId] = key
        }
        override suspend fun delete(endpoint: SshEndpoint) { keys.remove(endpoint.pinId) }
    }

    private class FakeTransport(private val actual: SshHostKey) : SshTransport {
        var inspections = 0
        var authentications = 0
        var authFailure = false
        var stall = false
        var cancelled = false
        override suspend fun inspect(endpoint: SshEndpoint): SshHostKey { inspections++; return actual }
        override suspend fun executeProbe(request: SshRequest, credentials: SshCredentials, pin: SshHostKey): String {
            if (actual != pin) throw changedHostFailure()
            authentications++
            if (authFailure) throw SshFailure(SshFailureKind.AUTH, "SSH-сервер отклонил учётные данные.")
            if (stall) try { awaitCancellation() } finally { cancelled = true }
            return validProbe()
        }
    }

    companion object {
        internal fun publicKey(value: Byte): SshHostKey {
            val type = "ssh-ed25519".toByteArray()
            val bytes = ByteBuffer.allocate(4 + type.size + 4 + 32).putInt(type.size).put(type).putInt(32).put(ByteArray(32) { value }).array()
            return HostKeyCodec.fromBytes(bytes)
        }
        private fun validProbe(): String = linkedMapOf(
            "protocol" to "1", "kernel" to "Linux", "arch" to "aarch64", "distro" to "debian", "distro_version" to "13",
            "cpu" to "4", "ram_kib" to "8388608", "disk_kib" to "41943040", "uid" to "1000", "sudo" to "noninteractive",
            "docker" to "missing", "docker_access" to "unknown", "compose" to "missing", "existing_containers" to "unknown", "existing_paths" to "no",
            "ports_known" to "yes", "tcp_80" to "free", "tcp_443" to "free", "tcp_3478" to "free", "tcp_5349" to "free", "tcp_50010" to "free",
            "udp_3478" to "free", "udp_50010" to "free", "udp_relay_busy" to "0", "dns_domain" to "192.0.2.1", "dns_ssh" to "192.0.2.1", "firewall" to "unknown", "complete" to "1",
        ).entries.joinToString("\n", postfix = "\n") { "MSPF1\t${it.key}\t${it.value}" }
    }
}
