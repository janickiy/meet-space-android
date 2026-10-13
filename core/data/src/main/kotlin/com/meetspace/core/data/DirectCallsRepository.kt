package com.meetspace.core.data

import com.meetspace.core.model.DirectCall
import com.meetspace.core.model.DirectCallAction

/** Scoped to the authenticated account/origin. No command is retried automatically. */
interface DirectCallsRepository {
    suspend fun startDirectCall(targetUserId: String, clientRequestId: String, clientId: String): DirectCall
    suspend fun activeDirectCall(): DirectCall?
    suspend fun directCall(id: String): DirectCall
    suspend fun directCallAction(id: String, action: DirectCallAction, clientId: String): DirectCall
}
