package com.meetspace.core.ssh

import com.meetspace.core.model.*
import java.nio.ByteBuffer
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PrivateKeyPolicyTest {
    @Test fun acceptsDefaultOpenSshKdfAndExplicitUpperBound() {
        PrivateKeyPolicy.validate(openSsh(16))
        PrivateKeyPolicy.validate(openSsh(64))
        PrivateKeyPolicy.validate(openSsh(cipher = "none", kdf = "none"))
    }

    @Test fun rejectsExpensiveOrOverflowingKdfBeforeDecryption() {
        for (rounds in listOf(0, -1, 65, Int.MAX_VALUE)) {
            val failure = rejected(openSsh(rounds))
            assertTrue(failure.message.contains("64"))
        }
    }

    @Test fun rejectsMalformedWireLengthsKeyCountAndTrailingBytes() {
        rejected(openSsh(publicLength = Int.MAX_VALUE))
        rejected(openSsh(keyCount = 2))
        rejected(openSsh(extra = byteArrayOf(1)))
        rejected(openSsh(saltLength = 1))
    }

    @Test fun rejectsUnsupportedKdfCipherAndInconsistentUnencryptedHeader() {
        rejected(openSsh(kdf = "argon2id"))
        rejected(openSsh(cipher = "arbitrary"))
        rejected(openSsh(cipher = "none", kdf = "bcrypt"))
        rejected(openSsh(cipher = "aes256-ctr", kdf = "none"))
    }

    @Test fun rejectsPkcs8PuttyAndUnsupportedPemWithUsefulSafeFormatMessage() {
        for (header in listOf("-----BEGIN ENCRYPTED PRIVATE KEY-----", "-----BEGIN PRIVATE KEY-----", "PuTTY-User-Key-File-3: ssh-ed25519", "-----BEGIN DSA PRIVATE KEY-----")) {
            val failure = rejected((header + "\nTEST-ONLY-SECRET-CONTENT\n").toByteArray())
            assertTrue(failure.message.contains("PKCS8"))
            assertTrue(failure.message.contains("PuTTY"))
            assertFalse(failure.message.contains("TEST-ONLY-SECRET-CONTENT"))
        }
    }

    @Test fun acceptsTraditionalRsaAndEcPemWithBoundedDer() {
        val integers = (listOf(byteArrayOf(0), ByteArray(17) { 1 }) + List(7) { byteArrayOf(1) })
            .fold(byteArrayOf()) { output, value -> output + byteArrayOf(2, value.size.toByte()) + value }
        val rsa = byteArrayOf(0x30, integers.size.toByte()) + integers
        PrivateKeyPolicy.validate(pem("RSA", rsa))
        val fields = byteArrayOf(2, 1, 1, 4, 32) + ByteArray(32) { 1 }
        val ec = byteArrayOf(0x30, fields.size.toByte()) + fields
        PrivateKeyPolicy.validate(pem("EC", ec))
    }

    @Test fun rejectsEncryptedTraditionalPemAndExplainsOpenSshAlternative() {
        val encoded = Base64.getEncoder().encodeToString(ByteArray(64) { 1 })
        val failure = rejected("-----BEGIN RSA PRIVATE KEY-----\nProc-Type: 4,ENCRYPTED\nDEK-Info: AES-256-CBC,0123456789ABCDEF0123456789ABCDEF\n\n$encoded\n-----END RSA PRIVATE KEY-----\n".toByteArray())
        assertTrue(failure.message.contains("OpenSSH v1"))
        assertTrue(failure.message.contains("парольной фразой"))
    }

    @Test fun rejectsClaimedDerLengthsAndDeepNestedFieldsBeforeJschAllocates() {
        rejected(pem("RSA", byteArrayOf(0x30, 0x84.toByte(), 0x7f, 0xff.toByte(), 0xff.toByte(), 0xff.toByte()) + ByteArray(32)))
        val fields = byteArrayOf(2, 1, 1, 4, 32) + ByteArray(32) + byteArrayOf(0xa0.toByte(), 6, 6, 0x84.toByte(), 0x7f, 0xff.toByte(), 0xff.toByte(), 0xff.toByte())
        rejected(pem("EC", byteArrayOf(0x30, fields.size.toByte()) + fields))
    }

    @Test fun rejectsIncompletePemUnexpectedPayloadAndOversizeKey() {
        rejected("-----BEGIN RSA PRIVATE KEY-----\nProc-Type: 4,ENCRYPTED\nAAAAAAAA\n-----END RSA PRIVATE KEY-----\n".toByteArray())
        rejected("-----BEGIN RSA PRIVATE KEY-----\nINVALID: payload\n-----END RSA PRIVATE KEY-----\n".toByteArray())
        rejected(openSsh().let { it.copyOf(it.size - 40) })
        rejected(ByteArray(256 * 1024 + 1) { 1 })
    }

    @Test fun maliciousIterationsLeaveNoCredentialBytesAfterRepositoryFailure() = runBlocking {
        val endpoint = SshEndpoint("127.0.0.1", 2222, "testuser")
        val pin = SshTransportPolicyTest.publicKey(1)
        val store = object : HostPinStore {
            override suspend fun read(endpoint: SshEndpoint) = pin
            override suspend fun trustNew(endpoint: SshEndpoint, key: SshHostKey) = Unit
            override suspend fun delete(endpoint: SshEndpoint) = Unit
        }
        val repo = DefaultSshPreflightRepository(store, JschSshTransport(), { NetworkReadiness() })
        val key = openSsh(Int.MAX_VALUE)
        val phrase = "test-only-phrase".toByteArray()
        val started = System.nanoTime()
        try { repo.preflight(SshRequest(endpoint, "meet.example.com"), SshCredentials.PrivateKey(key, phrase)); fail("Reject expensive KDF") }
        catch (failure: SshFailure) { assertEquals(SshFailureKind.KEY_FORMAT, failure.kind) }
        assertTrue((System.nanoTime() - started) / 1_000_000 < 3000)
        assertTrue(key.all { it == 0.toByte() })
        assertTrue(phrase.all { it == 0.toByte() })
    }

    private fun rejected(bytes: ByteArray): SshFailure {
        try { PrivateKeyPolicy.validate(bytes); fail("Key should be rejected") }
        catch (failure: SshFailure) { assertEquals(SshFailureKind.KEY_FORMAT, failure.kind); return failure }
        throw AssertionError("Expected failure")
    }

    private fun pem(type: String, body: ByteArray) = "-----BEGIN $type PRIVATE KEY-----\n${Base64.getEncoder().encodeToString(body)}\n-----END $type PRIVATE KEY-----\n".toByteArray()

    private fun openSsh(rounds: Int = 16, cipher: String = "aes256-ctr", kdf: String = "bcrypt", keyCount: Int = 1,
                        publicLength: Int = 32, saltLength: Int = 16, extra: ByteArray = byteArrayOf()): ByteArray {
        fun integer(value: Int) = ByteBuffer.allocate(4).putInt(value).array()
        fun field(value: ByteArray) = integer(value.size) + value
        val options = if (kdf == "none") byteArrayOf() else field(ByteArray(saltLength) { 1 }) + integer(rounds)
        val body = "openssh-key-v1\u0000".toByteArray() + field(cipher.toByteArray()) + field(kdf.toByteArray()) + field(options) + integer(keyCount) +
            integer(publicLength) + ByteArray(32) { 2 } + field(ByteArray(32) { 3 }) + extra
        return "-----BEGIN OPENSSH PRIVATE KEY-----\n${Base64.getEncoder().encodeToString(body)}\n-----END OPENSSH PRIVATE KEY-----\n".toByteArray()
    }
}
