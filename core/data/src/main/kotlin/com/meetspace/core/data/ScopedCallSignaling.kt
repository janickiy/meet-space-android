package com.meetspace.core.data

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.Channel
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

internal class ScopedCallSignaling(
    override val iceConfiguration: String,
    api: MeetSpaceApi, origin: String, conferenceId: String, ticket: String,
    private val updatePresence: suspend (String, Long, Boolean, Boolean, Boolean) -> Unit,
) : CallSignaling {
    private val closed = AtomicBoolean(false)
    override val messages = Channel<String>(128)
    private val socket: WebSocket = api.callSocket(origin, conferenceId, ticket, object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (closed.get() || response.header("Sec-WebSocket-Protocol") != "go-recorder.v1") {
                fail(webSocket, false)
            }
        }
        override fun onMessage(webSocket: WebSocket, text: String) {
            if (closed.get()) return
            if (text.length > 1_048_576 || !messages.trySend(text).isSuccess) fail(webSocket, false)
        }
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = fail(webSocket, false)
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            val transient = code != 1008 || reason in setOf("broker_unavailable", "slow_client", "state_unavailable", "authentication_unavailable", "authentication_expired")
            fail(webSocket, transient)
        }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            // Never propagate URLs/tickets, raw response bodies, SDP or ICE into errors or logs.
            fail(webSocket, response?.code !in setOf(400, 403, 404) && t !is javax.net.ssl.SSLException)
        }
    })
    private fun fail(socket: WebSocket, retryable: Boolean) {
        closed.set(true)
        messages.close(CallDisconnected(retryable))
        socket.cancel()
    }
    override fun send(envelope: String): Boolean = !closed.get() && envelope.toByteArray().size <= 65_536 && socket.send(envelope)
    override suspend fun presence(connectionId: String, sequence: Long, microphone: Boolean, camera: Boolean, screen: Boolean) {
        if (closed.get()) throw CallDisconnected(false)
        updatePresence(connectionId, sequence, microphone, camera, screen)
    }
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            messages.close(CallDisconnected(false))
            socket.close(1000, "")
            socket.cancel()
        }
    }
}
