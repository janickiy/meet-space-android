package com.meetspace.core.model

import java.time.Instant

/** Durable account signalling; client IDs identify this process's endpoint, never authentication. */
data class DirectCall(
    val id: String, val conversationId: String, val conferenceId: String?,
    val callerUserId: String, val calleeUserId: String,
    val callerClientId: String, val acceptedByClientId: String?,
    val caller: ChatPeer, val callee: ChatPeer, val status: String, val version: Long,
    val createdAt: Instant, val expiresAt: Instant, val acceptedAt: Instant? = null,
    val endedAt: Instant? = null, val durationSec: Long = 0,
) {
    val active: Boolean get() = status in setOf("ringing", "accepted")
    fun ownsMedia(accountId: String, clientId: String): Boolean = clientId.isNotBlank() &&
        ((callerUserId == accountId && callerClientId == clientId) ||
            (calleeUserId == accountId && acceptedByClientId == clientId))
    fun peer(accountId: String): ChatPeer? = when (accountId) {
        callerUserId -> callee
        calleeUserId -> caller
        else -> null
    }
}

/** History intentionally omits conference and endpoint identifiers. */
data class DirectCallHistory(val id: String, val callerUserId: String, val calleeUserId: String,
    val status: String, val createdAt: Instant, val acceptedAt: Instant? = null,
    val endedAt: Instant? = null, val durationSec: Long = 0)

enum class DirectCallAction(val wire: String) {
    ACCEPT("accept"), DECLINE("decline"), CANCEL("cancel"), END("end"), HEARTBEAT("heartbeat")
}
