package com.meetspace.core.data

import kotlinx.coroutines.channels.ReceiveChannel

/** Credentials stay in the data layer. A session is bound to one account, origin and conference. */
interface CallRepository {
    suspend fun openCall(conferenceId: String): CallSignaling
    /** Explicit endpoint ownership is mandatory for direct-call room tickets. */
    suspend fun openDirectCall(conferenceId: String, clientId: String): CallSignaling =
        throw com.meetspace.core.model.AppFailure(com.meetspace.core.model.FailureKind.UNAVAILABLE,
            "Личные звонки недоступны в этом подключении.")
}

interface CallSignaling {
    val iceConfiguration: String
    val messages: ReceiveChannel<String>
    fun send(envelope: String): Boolean
    suspend fun presence(connectionId: String, sequence: Long, microphone: Boolean, camera: Boolean, screen: Boolean)
    fun close()
}

class CallDisconnected(val retryable: Boolean) : Exception("Соединение звонка закрыто")
