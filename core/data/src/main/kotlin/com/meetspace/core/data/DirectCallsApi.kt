package com.meetspace.core.data

import com.meetspace.core.data.MessagingApi.Companion.count
import com.meetspace.core.data.MessagingApi.Companion.fields
import com.meetspace.core.data.MessagingApi.Companion.id
import com.meetspace.core.data.MessagingApi.Companion.inputId
import com.meetspace.core.data.MessagingApi.Companion.instant
import com.meetspace.core.data.MessagingApi.Companion.item
import com.meetspace.core.data.MessagingApi.Companion.optionalId
import com.meetspace.core.data.MessagingApi.Companion.optionalInstant
import com.meetspace.core.data.MessagingApi.Companion.text
import com.meetspace.core.model.DirectCall
import com.meetspace.core.model.DirectCallAction
import com.meetspace.core.model.DirectCallHistory
import com.meetspace.core.model.ChatPeer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

internal class DirectCallsApi(api: MeetSpaceApi) {
    private val wire = MessagingApi(api)
    suspend fun start(origin: String, token: String, target: String, requestId: String, clientId: String): DirectCall =
        call(wire.request(origin, token, listOf("calls"), "POST", fields(
            "targetUserId" to inputId(target), "clientRequestId" to inputId(requestId), "clientId" to inputId(clientId))).item())
            .also { if (it.calleeUserId != target || it.callerClientId != clientId) throw InvalidContract() }
    suspend fun active(origin: String, token: String): DirectCall? {
        val response = wire.request(origin, token, listOf("calls", "active"))
        return when (val value = response["item"]) {
            JsonNull -> null
            is JsonObject -> call(value).also { if (!it.active) throw InvalidContract() }
            else -> throw InvalidContract()
        }
    }
    suspend fun read(origin: String, token: String, id: String): DirectCall =
        call(wire.request(origin, token, listOf("calls", inputId(id))).item())
            .also { if (it.id != id) throw InvalidContract() }
    suspend fun action(origin: String, token: String, id: String, action: DirectCallAction, clientId: String): DirectCall {
        val body = if (action == DirectCallAction.DECLINE) fields() else fields("clientId" to inputId(clientId))
        return call(wire.request(origin, token, listOf("calls", inputId(id), action.wire), "POST", body).item())
            .also { if (it.id != id) throw InvalidContract() }
    }

    companion object {
        private val statuses = setOf("ringing", "accepted", "declined", "cancelled", "ended", "missed")
        fun history(o: JsonObject): DirectCallHistory {
            val caller = o.id("callerUserId"); val callee = o.id("calleeUserId")
            val status = o.text("status", 30)
            if (caller == callee || status !in statuses) throw InvalidContract()
            val created = o.instant("createdAt")
            val accepted = o.optionalInstant("acceptedAt"); val ended = o.optionalInstant("endedAt")
            val duration = o.count("durationSec", 0)
            if (accepted != null && accepted < created || ended != null && ended < (accepted ?: created)) throw InvalidContract()
            if (status == "accepted" && (accepted == null || ended != null)) throw InvalidContract()
            if (status == "ringing" && (accepted != null || ended != null || duration != 0L)) throw InvalidContract()
            if (status in setOf("declined", "cancelled", "missed") && (accepted != null || duration != 0L)) throw InvalidContract()
            if (status == "ended" && accepted == null) throw InvalidContract()
            if (status !in setOf("ringing", "accepted") && ended == null) throw InvalidContract()
            return DirectCallHistory(o.id(), caller, callee, status, created, accepted, ended, duration)
        }
        fun call(o: JsonObject): DirectCall {
            val history = history(o)
            val caller = o["caller"] as? JsonObject ?: throw InvalidContract()
            val callee = o["callee"] as? JsonObject ?: throw InvalidContract()
            if (caller.id() != history.callerUserId || callee.id() != history.calleeUserId) throw InvalidContract()
            val conference = o.optionalId("conferenceId")
            val acceptingClient = o.optionalId("acceptedByClientId")
            val version = o.count("version")
            val expires = o.instant("expiresAt")
            if (version == 0L || expires < history.createdAt) throw InvalidContract()
            if ((history.acceptedAt != null) != (conference != null) ||
                (history.acceptedAt != null) != (acceptingClient != null)) throw InvalidContract()
            return DirectCall(history.id, o.id("conversationId"), conference, history.callerUserId, history.calleeUserId,
                o.id("callerClientId"), acceptingClient, ChatPeer(caller.id(), caller.text("displayName", 400)),
                ChatPeer(callee.id(), callee.text("displayName", 400)), history.status, version, history.createdAt,
                expires, history.acceptedAt, history.endedAt, history.durationSec)
        }
    }
}
