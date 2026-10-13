package com.meetspace.core.media

import java.time.Instant
import java.util.UUID
import com.meetspace.core.model.DirectCall
import kotlinx.serialization.json.*

internal class ProtocolFailure : Exception("Несовместимый ответ сервера звонка")
internal val callJson = Json { ignoreUnknownKeys = true }
internal fun JsonObject.string(key: String): String = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw ProtocolFailure()
internal fun JsonObject.number(key: String): Long = (get(key) as? JsonPrimitive)?.longOrNull ?: throw ProtocolFailure()
internal fun JsonObject.flag(key: String, default: Boolean = false): Boolean = get(key)?.let { (it as? JsonPrimitive)?.booleanOrNull ?: throw ProtocolFailure() } ?: default
internal fun JsonObject.obj(key: String): JsonObject = get(key) as? JsonObject ?: throw ProtocolFailure()
internal fun JsonObject.array(key: String): JsonArray = get(key) as? JsonArray ?: throw ProtocolFailure()
internal fun validId(id: String): String = id.takeIf { runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false) } ?: throw ProtocolFailure()
internal fun safeObject(text: String): JsonObject = try { callJson.parseToJsonElement(text) as? JsonObject ?: throw ProtocolFailure() } catch (_: Exception) { throw ProtocolFailure() }

internal data class CallEvent(val type: String, val reply: String?, val data: JsonObject)
internal data class IceServer(val urls: List<String>, val username: String, val credential: String)
internal class IceConfiguration(val servers: List<IceServer>, val relay: Boolean, val expiresAt: Long?)
internal data class CatalogTrack(val id: String, val streamId: String, val participantId: String, val source: String, val kind: String)

internal object CallProtocol {
    /** A read from this account must confirm endpoint ownership before direct media is captured. */
    fun directEndpoint(call: DirectCall, accountId: String, clientId: String, conferenceId: String): Boolean =
        call.status == "accepted" && call.conferenceId == conferenceId &&
            call.ownsMedia(accountId, clientId) && call.acceptedAt != null && call.endedAt == null
    fun event(raw: String, conferenceId: String): CallEvent {
        if (raw.toByteArray().size > 1_048_576) throw ProtocolFailure()
        val json = safeObject(raw)
        if (json.number("version") != 1L || json.string("conferenceId") != conferenceId) throw ProtocolFailure()
        validId(json.string("id"))
        try { Instant.parse(json.string("timestamp")) } catch (_: Exception) { throw ProtocolFailure() }
        val type = json.string("type").takeIf { it.length <= 100 } ?: throw ProtocolFailure()
        return CallEvent(type, (json["replyTo"] as? JsonPrimitive)?.content?.let(::validId), json.obj("data"))
    }
    fun envelope(id: String, type: String, conferenceId: String, data: JsonObject): String = buildJsonObject {
        put("version", 1); put("id", id); put("type", type); put("conferenceId", conferenceId)
        put("timestamp", Instant.now().toString()); put("data", data)
    }.toString()
    fun ice(raw: String, now: Long = Instant.now().epochSecond): IceConfiguration {
        val json = safeObject(raw)
        val policy = (json["iceTransportPolicy"] as? JsonPrimitive)?.content ?: "all"
        if (policy !in setOf("all", "relay")) throw ProtocolFailure()
        val expiry = json["expiresAt"]?.let { (it as? JsonPrimitive)?.longOrNull ?: throw ProtocolFailure() }
        if (expiry != null && (expiry <= now + 30 || expiry > now + 86_400)) throw ProtocolFailure()
        val entries = json.array("iceServers")
        if (entries.size > 16) throw ProtocolFailure()
        val servers = entries.map { entry ->
            val server = entry as? JsonObject ?: throw ProtocolFailure()
            val urls = when (val value = server["urls"]) {
                is JsonArray -> value.map { (it as? JsonPrimitive)?.content ?: throw ProtocolFailure() }
                is JsonPrimitive -> listOf(value.content)
                else -> throw ProtocolFailure()
            }
            if (urls.isEmpty() || urls.size > 16 || urls.any { it.length > 2048 || it.any(Char::isWhitespace) || !it.matches(Regex("(?i)(stun|stuns|turn|turns):[^\\s]+")) }) throw ProtocolFailure()
            val user = (server["username"] as? JsonPrimitive)?.content.orEmpty()
            val credential = (server["credential"] as? JsonPrimitive)?.content.orEmpty()
            if (user.length > 2048 || credential.length > 4096) throw ProtocolFailure()
            IceServer(urls, user, credential)
        }
        if (policy == "relay" && servers.none { s -> s.urls.any { it.startsWith("turn:") || it.startsWith("turns:") } }) throw ProtocolFailure()
        return IceConfiguration(servers, policy == "relay", expiry)
    }
    fun catalog(data: JsonObject): List<CatalogTrack> {
        val entries = data.array("tracks")
        if (entries.size > 128) throw ProtocolFailure()
        return entries.map {
            val t = it as? JsonObject ?: throw ProtocolFailure()
            val peer = validId(t.string("mediaPeerId")); val source = t.string("source"); val kind = t.string("kind")
            if (source !in setOf("microphone", "camera", "video/screen", "audio/screen") ||
                kind != if (source in setOf("microphone", "audio/screen")) "audio" else "video") throw ProtocolFailure()
            val stream = t.string("streamId")
            if (stream != peer + if (source.endsWith("/screen")) "-screen" else "") throw ProtocolFailure()
            CatalogTrack(validId(t.string("id")), stream, validId(t.string("participantId")), source, kind)
        }.also { if (it.map(CatalogTrack::id).distinct().size != it.size) throw ProtocolFailure() }
    }
}

/** One offer/answer/ready transaction at a time, with monotonic coalesced revisions. */
internal class NegotiationGate {
    data class Pending(val id: String, val revision: Long, val localRevision: Long, var request: String = "", var ready: String? = null)
    var pending: Pending? = null; private set
    private var wanted = 0L
    private var local = 0L
    private var answered = -1L
    private var answeredLocal = -1L
    fun remote(revision: Long) { if (revision < 0) throw ProtocolFailure(); wanted = maxOf(wanted, revision) }
    fun changed() { local++ }
    fun begin(): Pending? {
        if (pending != null || wanted <= answered && local <= answeredLocal) return null
        return Pending(UUID.randomUUID().toString(), wanted, local).also { pending = it }
    }
    fun acknowledge(request: String): Boolean {
        val p = pending ?: return false
        if (p.ready != request) return false
        answered = p.revision; answeredLocal = p.localRevision; pending = null
        return true
    }
    fun rollback() { pending = null; local++ }
}
