package com.meetspace.app

import com.meetspace.core.model.SshCredentials
import com.meetspace.core.model.SshHostKey
import com.meetspace.core.model.SshRequest

/** A single-use, short-lived transfer between onboarding steps; never public/saved UI state. */
class VerifiedInstallationAccess internal constructor(
    private val request: SshRequest,
    private val hostKey: SshHostKey,
    credentials: SshCredentials,
    private val createdAtNanos: Long,
    private val clock: () -> Long = System::nanoTime,
) {
    private var credentials: SshCredentials? = credentials

    internal fun take(expected: SshRequest): Access? {
        val owned = credentials ?: return null
        credentials = null
        val elapsed = clock() - createdAtNanos
        if (expected != request || elapsed < 0 || elapsed >= TTL_NANOS) {
            owned.destroy()
            return null
        }
        return Access(owned, hostKey, (TTL_NANOS - elapsed) / 1_000_000L)
    }

    internal fun destroy() { credentials?.destroy(); credentials = null }
    override fun toString(): String = "VerifiedInstallationAccess(<redacted>)"

    internal class Access(val credentials: SshCredentials, val hostKey: SshHostKey, val remainingMillis: Long)
    internal companion object { const val TTL_MILLIS = 10 * 60 * 1000L; const val TTL_NANOS = TTL_MILLIS * 1_000_000L }
}

internal fun SshCredentials.copyOwned(): SshCredentials = when (this) {
    is SshCredentials.Password -> SshCredentials.Password(password.copyOf())
    is SshCredentials.PrivateKey -> SshCredentials.PrivateKey(key.copyOf(), passphrase.copyOf())
}
