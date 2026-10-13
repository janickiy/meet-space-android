package com.meetspace.core.data

import com.meetspace.core.data.MessagingApi.Companion.instant
import com.meetspace.core.data.MessagingApi.Companion.id
import com.meetspace.core.data.MessagingApi.Companion.optionalId
import com.meetspace.core.data.MessagingApi.Companion.text
import com.meetspace.core.model.UserEvent
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/** Minimal bounded invalidations. Content is discarded and fetched with current REST permissions. */
internal class ScopedUserEvents(api: MeetSpaceApi, origin: String, ticket: String) : UserEvents {
    private val closed = AtomicBoolean(false)
    private val channel = Channel<UserEvent>(64)
    override val events: ReceiveChannel<UserEvent> = channel
    private val socket: WebSocket = api.userSocket(origin, ticket, object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            offer(webSocket, UserEvent("", "connected"))
        }
        override fun onMessage(webSocket: WebSocket, text: String) {
            if (closed.get()) return
            try { offer(webSocket, decodeUserEvent(text)) }
            catch (_: Exception) { terminate(webSocket, false) }
        }
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) { terminate(webSocket, false) }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            terminate(webSocket, response?.code !in setOf(401, 403))
        }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { terminate(webSocket, code !in setOf(1008, 4001, 4003)) }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { terminate(webSocket, true) }
    })
    private fun offer(webSocket: WebSocket, event: UserEvent) {
        if (!closed.get() && channel.trySend(event).isFailure) terminate(webSocket, true)
    }
    private fun terminate(webSocket: WebSocket, retryable: Boolean) {
        if (closed.compareAndSet(false, true)) channel.close(UserEventsDisconnected(retryable))
        webSocket.cancel()
    }
    override fun close() {
        if (closed.compareAndSet(false, true)) channel.cancel()
        socket.cancel()
    }
}

internal fun decodeUserEvent(text: String): UserEvent {
    if (text.length > 128 * 1024) throw InvalidContract()
    val objectValue = MessagingApi.parse(text)
    if ((objectValue["version"] as? JsonPrimitive)?.intOrNull != 1) throw InvalidContract()
    objectValue.instant("timestamp")
    val id = objectValue.id()
    val type = objectValue.text("type", 100)
    if (!type.matches(Regex("[a-z][a-z0-9_.]{0,99}"))) throw InvalidContract()
    val data = objectValue["data"] as? JsonObject ?: throw InvalidContract()
    val call = if (type == "call.updated") data["call"] as? JsonObject ?: throw InvalidContract() else null
    // Events intentionally contain no message text or attachment capability URL in client memory.
    return UserEvent(id, type, call?.id("conversationId") ?: data.optionalId("conversationId"), data.optionalId("folderId"),
        data.optionalId("userId"), if (type.startsWith("notification.")) data.optionalId("id") ?: id else null,
        objectValue.optionalId("conferenceId"), call?.id())
}
