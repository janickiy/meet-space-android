package com.meetspace.app

import com.meetspace.core.media.CallPhase
import com.meetspace.core.media.CallState

/** An ActivityResult belongs to the call/action that opened its system dialog. */
internal class CallPermissionRequest {
    private data class Target(val account: String, val conference: String, val direct: String?, val participant: String, val phase: CallPhase)
    private var target: Target? = null
    private var awaiting = false

    val awaitingResult get() = awaiting

    fun begin(account: String, state: CallState): Boolean {
        if (awaiting || account.isBlank() || state.conferenceId.isBlank() || state.phase !in setOf(CallPhase.PREJOIN, CallPhase.CONNECTED)) return false
        target = Target(account, state.conferenceId, state.directCallId, state.participantId, state.phase)
        awaiting = true
        return true
    }

    fun consume(account: String, state: CallState, foreground: Boolean): Boolean {
        val requested = target
        target = null
        val hadRequest = awaiting
        awaiting = false
        return hadRequest && foreground && requested != null &&
            requested == Target(account, state.conferenceId, state.directCallId, state.participantId, state.phase)
    }

    fun invalidate() {
        target = null
        // Keep the launcher occupied until its old result is consumed, so that result can
        // never be mistaken for a second request in a different call.
    }
}
