package com.meetspace.app

import com.meetspace.core.model.*
import org.junit.Assert.*
import org.junit.Test

class VerifiedInstallationAccessTest {
    private val request = SshRequest(SshEndpoint("vps.example.test", 22, "root"), "meet.example.test")
    private val key = SshHostKey("ssh-ed25519", "test-public-key", "SHA256:test-key")

    @Test fun mismatchedHostPortUserOrDomainDestroysAccessInsteadOfReusingIt() {
        val invalid = listOf(
            request.copy(endpoint = request.endpoint.copy(host = "other.example.test")),
            request.copy(endpoint = request.endpoint.copy(port = 2222)),
            request.copy(endpoint = request.endpoint.copy(user = "admin")),
            request.copy(domain = "other.example.test"),
        )
        for (target in invalid) {
            val password = "test-only".toByteArray()
            val transfer = VerifiedInstallationAccess(request, key, SshCredentials.Password(password), 0) { 0 }
            assertNull(transfer.take(target)); assertNull(transfer.take(request))
            assertArrayEquals(ByteArray(password.size), password)
        }
    }

    @Test fun expiredOrBackwardsClockNeverReturnsCredentials() {
        for (now in listOf(-1L, VerifiedInstallationAccess.TTL_NANOS, VerifiedInstallationAccess.TTL_NANOS + 1)) {
            val bytes = "test-only".toByteArray()
            val transfer = VerifiedInstallationAccess(request, key, SshCredentials.Password(bytes), 0) { now }
            assertNull(transfer.take(request)); assertTrue(bytes.all { it == 0.toByte() })
        }
    }

    @Test fun privateKeyAndPassphraseAreErasedOnAbandonedTransfer() {
        val bytes = "private-test-key".toByteArray()
        val passphrase = "test-passphrase".toByteArray()
        val transfer = VerifiedInstallationAccess(request, key, SshCredentials.PrivateKey(bytes, passphrase), 0) { 0 }
        assertEquals("VerifiedInstallationAccess(<redacted>)", transfer.toString())
        transfer.destroy(); transfer.destroy()
        assertNull(transfer.take(request))
        assertTrue(bytes.all { it == 0.toByte() }); assertTrue(passphrase.all { it == 0.toByte() })
    }
}
