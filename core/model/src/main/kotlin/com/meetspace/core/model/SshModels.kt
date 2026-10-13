package com.meetspace.core.model

data class SshEndpoint(val host: String, val port: Int, val user: String) {
    val pinId: String get() = "[$host]:$port"
}

data class SshRequest(val endpoint: SshEndpoint, val domain: String)

/** Public server identity only; never a user private key. */
data class SshHostKey(val algorithm: String, val keyBase64: String, val fingerprint: String)

enum class SshTrust { NEW, TRUSTED, CHANGED }
data class SshHostIdentity(val endpoint: SshEndpoint, val key: SshHostKey, val trust: SshTrust, val previous: SshHostKey? = null)

/** Owned by one operation. Call destroy in finally; never serialize or expose through UI state. */
sealed class SshCredentials {
    class Password(val password: ByteArray) : SshCredentials() {
        override fun destroy() { password.fill(0) }
    }
    class PrivateKey(val key: ByteArray, val passphrase: ByteArray = byteArrayOf()) : SshCredentials() {
        override fun destroy() { key.fill(0); passphrase.fill(0) }
    }
    abstract fun destroy()
    final override fun toString(): String = "SshCredentials(<redacted>)"
}

enum class PreflightLevel { PASS, WARN, BLOCKED, UNKNOWN }
data class PreflightCheck(val id: String, val title: String, val level: PreflightLevel, val detail: String)
data class PreflightReport(
    val endpoint: SshEndpoint,
    val domain: String,
    val hostKey: SshHostKey,
    val checks: List<PreflightCheck>,
    val inspectedAtEpochMillis: Long,
) {
    val hasBlockers: Boolean get() = checks.any { it.level == PreflightLevel.BLOCKED }
}

enum class SshFailureKind { INPUT, NETWORK, TIMEOUT, HOST_KEY_CHANGED, UNTRUSTED_HOST, AUTH, KEY_FORMAT, REMOTE_COMMAND, INVALID_RESPONSE, STORAGE }
class SshFailure(val kind: SshFailureKind, override val message: String) : Exception(message)
