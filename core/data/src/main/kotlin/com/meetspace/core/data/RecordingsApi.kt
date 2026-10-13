package com.meetspace.core.data

import com.meetspace.core.model.*
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal class RecordingCard(val recording: Recording, val urls: Map<String, HttpUrl>) {
    override fun toString() = "RecordingCard([private])"
}

internal class RecordingsApi(private val api: MeetSpaceApi) {
    suspend fun start(origin: String, token: String, cid: String, mode: String): Recording {
        if (mode !in setOf("composite", "audio_only", "individual_tracks", "screen_focus")) input()
        val body = request(origin, token, path(cid), method = "POST", payload = buildJsonObject { put("mode", mode) }.toString())
        return card(body["item"]?.obj() ?: invalid(), origin, cid).recording.also {
            if (it.mode != mode) invalid()
        }
    }

    suspend fun stop(origin: String, token: String, cid: String, rid: String): Recording {
        val body = request(origin, token, path(cid, rid) + "stop", method = "POST", payload = "{}")
        return card(body["item"]?.obj() ?: invalid(), origin, cid).recording.also { if (it.id != rid) invalid() }
    }

    suspend fun list(origin: String, token: String, cid: String, offset: Int, limit: Int): RecordingPage {
        if (offset < 0 || offset > Int.MAX_VALUE - 100 || limit !in 1..100) input()
        val body = request(origin, token, path(cid), mapOf("offset" to "$offset", "limit" to "$limit"))
        val cards = body.array("items", limit).map { card(it.obj(), origin, cid) }
        if (cards.map { it.recording.id }.distinct().size != cards.size) invalid()
        return RecordingPage(cards.map { it.recording }, (offset + cards.size).takeIf { cards.size == limit })
    }

    suspend fun read(origin: String, token: String, cid: String, rid: String): RecordingCard {
        val body = request(origin, token, path(cid, rid))
        return card(body["item"]?.obj() ?: invalid(), origin, cid).also { if (it.recording.id != rid) invalid() }
    }

    suspend fun transcript(origin: String, token: String, cid: String, rid: String): RecordingTranscriptState {
        val body = request(origin, token, path(cid, rid) + "transcript")
        val item = body["item"].takeUnless { it == null || it is JsonNull }?.obj()?.let {
            if (it.id("conferenceId") != cid || it.id("recordingId") != rid) invalid()
            RecordingTranscript(it.id(), rid, it.text("language", 64), it.text("status", 64))
        }
        return RecordingTranscriptState(body.bool("enabled"), item)
    }

    suspend fun segments(origin: String, token: String, cid: String, rid: String, offset: Int, limit: Int): RecordingSegmentPage {
        if (offset !in 0..100000 || limit !in 1..200) input()
        val body = request(origin, token, path(cid, rid) + listOf("transcript", "segments"), mapOf("offset" to "$offset", "limit" to "$limit"))
        if (body.long("offset") != offset.toLong() || body.long("limit") != limit.toLong()) invalid()
        val items = body.array("items", limit).map {
            val obj = it.obj(); val start = obj.long("startMs"); val end = obj.long("endMs")
            if (start < 0 || end < start) invalid()
            RecordingSegment(obj.id(), obj.id("transcriptId"), start, end,
                obj.optionalText("speakerLabel", 256), obj.text("text", 65536, multiline = true))
        }
        val total = body.long("total")
        if (total < 0 || items.size > total || items.map { it.id }.distinct().size != items.size ||
            items.map { it.transcriptId }.distinct().size > 1 || items.zipWithNext().any { it.first.startMs > it.second.startMs }) invalid()
        return RecordingSegmentPage(items, total, offset, limit)
    }

    suspend fun summary(origin: String, token: String, cid: String, rid: String): RecordingSummaryState {
        val body = request(origin, token, path(cid, rid) + "summary")
        val item = body["item"].takeUnless { it == null || it is JsonNull }?.obj()?.let { value ->
            if (value.id("conferenceId") != cid) invalid()
            RecordingSummary(value.id(), value.id("transcriptId"), value.text("status", 64),
                value.text("summary", 131072, multiline = true), value.texts("keyPoints", 200, 8192),
                value.array("actionItems", 200).map {
                    val action = it.obj()
                    RecordingActionItem(action.text("text", 8192, multiline = true), action.optionalText("assignee", 512), action.optionalText("dueDate", 128))
                }, value.texts("topics", 200, 512))
        }
        return RecordingSummaryState(body.bool("enabled"), item)
    }

    private suspend fun request(origin: String, token: String, path: List<String>, query: Map<String, String> = emptyMap(),
        method: String = "GET", payload: String? = null): JsonObject {
        val body = try { wireJson.parseToJsonElement(api.authenticatedRequest(origin, path, token, query, method, payload)).obj() }
        catch (_: kotlinx.serialization.SerializationException) { invalid() }
        if (body.text("status", 16) != "success") invalid()
        return body
    }

    private fun card(value: JsonObject, origin: String, cid: String): RecordingCard {
        val recordId = value.long("id")
        if (recordId <= 0 || value.id("conferenceId") != cid) invalid()
        val urls = mutableMapOf<String, HttpUrl>()
        val all = value.array("files", 32)
        val files = all.mapNotNull {
            val obj = it.obj(); val type = obj.text("fileType", 64)
            val expectedMime = when (type) {
                "final_mp4" -> "video/mp4"; "final_audio" -> "audio/mp4"
                "preview_jpg" -> "image/jpeg"; "tracks_archive" -> "application/zip"
                else -> return@mapNotNull null
            }
            val id = obj.id("uuid")
            if (obj.long("recordId") != recordId || obj.text("mimeType", 128) != expectedMime) invalid()
            val filename = obj.text("fileName", 255)
            if (filename.isBlank() || filename in setOf(".", "..") || filename.any { it == '/' || it == '\\' }) invalid()
            val size = obj.optionalLong("sizeBytes")
            val duration = obj.optionalLong("durationSec")
            if (size?.let { it <= 0 } == true || duration?.let { it < 0 } == true) invalid()
            val checksum = obj.optionalText("checksumSha256", 64)?.takeIf { it.isNotEmpty() }
            if (checksum != null && !checksum.matches(Regex("[A-Fa-f0-9]{64}"))) invalid()
            val url = obj.optionalText("url", 16384)?.takeIf { it.isNotEmpty() }?.let { safeUrl(origin, it) }
            if (url != null) urls[id] = url
            RecordingFile(id, type, filename, expectedMime, size, checksum?.lowercase(), duration, url != null)
        }
        if (files.map { it.id }.distinct().size != files.size) invalid()
        val duration = value.optionalLong("durationSec")
        if (duration?.let { it < 0 } == true) invalid()
        return RecordingCard(Recording(value.id("uuid"), cid, value.text("mode", 64), value.text("status", 64),
            value.instant("createdAt"), value.optionalText("startedAt", 64)?.let(::parseInstant), duration, files,
            value.optionalText("requestedBy", 36)?.also { if (!isId(it)) invalid() }), urls)
    }

    companion object {
        fun fileId(id: String): String = inputId(id)
        private fun path(cid: String, rid: String? = null) = listOf("conferences", inputId(cid), "recordings") + listOfNotNull(rid?.let(::inputId))
        private fun inputId(value: String): String { if (!isId(value)) input(); return value }
        private fun isId(value: String) = value.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) &&
            runCatching { UUID.fromString(value) != UUID(0, 0) }.getOrDefault(false)
        private fun JsonObject.id(key: String = "id") = text(key, 36).also { if (!isId(it)) invalid() }
        private fun JsonElement.obj(): JsonObject = this as? JsonObject ?: invalid()
        private fun JsonObject.array(key: String, max: Int): List<JsonElement> {
            val element = get(key)
            if (element == null || element is JsonNull) return emptyList()
            return (element as? JsonArray ?: invalid()).also { if (it.size > max) invalid() }
        }
        private fun JsonObject.text(key: String, max: Int, multiline: Boolean = false): String =
            (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content?.also { validateText(it, max, multiline) } ?: invalid()
        private fun JsonObject.optionalText(key: String, max: Int): String? =
            get(key).takeUnless { it == null || it is JsonNull }?.let { text(key, max) }
        private fun JsonObject.texts(key: String, max: Int, length: Int) = array(key, max).map {
            (it as? JsonPrimitive)?.takeIf { it.isString }?.content?.also { validateText(it, length, true) } ?: invalid()
        }
        private fun validateText(text: String, max: Int, multiline: Boolean) {
            if (text.length > max || text.any { it.isISOControl() && !(multiline && it in "\n\r\t") }) invalid()
        }
        private fun JsonObject.long(key: String) = (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: invalid()
        private fun JsonObject.optionalLong(key: String): Long? = get(key).takeUnless { it == null || it is JsonNull }?.let { long(key) }
        private fun JsonObject.bool(key: String) = (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: invalid()
        private fun JsonObject.instant(key: String): Instant = parseInstant(text(key, 64))
        private fun parseInstant(raw: String): Instant = try { Instant.parse(raw).also {
            if (it < Instant.parse("0001-01-01T00:00:00Z") || it > Instant.parse("9999-12-31T23:59:59.999999999Z")) invalid()
        } } catch (_: Exception) { invalid() }
        private fun safeUrl(origin: String, raw: String): HttpUrl {
            if (raw.any { it.isWhitespace() || it.isISOControl() || it == '\\' } || raw.startsWith("//")) invalid()
            val url = (if (raw.startsWith('/')) origin + raw else raw).toHttpUrlOrNull() ?: invalid()
            if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null) invalid()
            return url
        }
        private fun invalid(): Nothing = throw InvalidContract()
        private fun input(): Nothing = throw AppFailure(FailureKind.VALIDATION, "Проверьте выбранную встречу и запись.")
    }
}
