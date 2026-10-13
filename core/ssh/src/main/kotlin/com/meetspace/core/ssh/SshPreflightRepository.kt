package com.meetspace.core.ssh

import com.meetspace.core.model.*

interface SshPreflightRepository {
    /** Handshake only; MUST NOT send password/key authentication. */
    suspend fun inspectHost(endpoint: SshEndpoint): SshHostIdentity
    /** Explicit TOFU confirmation only; a changed key must never be overwritten here. */
    suspend fun trustHost(identity: SshHostIdentity)
    /** Uses a previously stored exact pin. Owns and destroys credentials in finally. */
    suspend fun preflight(request: SshRequest, credentials: SshCredentials): PreflightReport
    suspend fun forgetHost(endpoint: SshEndpoint)
}
