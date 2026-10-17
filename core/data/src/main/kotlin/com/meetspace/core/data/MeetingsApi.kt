package com.meetspace.core.data

import com.meetspace.core.model.AppFailure
import com.meetspace.core.model.FailureKind
import com.meetspace.core.model.*
import com.meetspace.core.model.MeetingHistory
import com.meetspace.core.model.MeetingInput
import com.meetspace.core.model.MeetingPage
import com.meetspace.core.model.MeetingParticipant
import com.meetspace.core.model.MeetingRecordingsSummary
import com.meetspace.core.model.MeetingTransition
import com.meetspace.core.model.MeetingsQuery
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Adapter for the audited backend DTOs; it neither stores auth nor invents capabilities. */
internal class MeetingsApi(private val api: MeetSpaceApi) {
    suspend fun timeline(origin: String, token: String, query: MeetingsQuery): MeetingPage {
        val from = query.from
        val to = query.to
        if (query.limit !in 1..100 || (from != null && to != null && from > to) ||
            query.cursor?.let { it.isBlank() || it.length > 256 || it.any(Char::isISOControl) } == true) invalidInput()
        val parameters = buildMap {
            put("view", query.view.wireValue); put("scope", query.scope.wireValue); put("limit", query.limit.toString())
            query.from?.let { put("from", it.toString()) }; query.to?.let { put("to", it.toString()) }
            query.cursor?.let { put("cursor", it) }
        }
        val body = try {
            api.authenticatedRequest(origin, listOf("me", "conferences"), token, parameters)
        } catch (failure: HttpFailure) {
            if (failure.statusCode == 404) throw AppFailure(FailureKind.INCOMPATIBLE_SERVER,
                "Сервер не поддерживает список встреч текущего пользователя. Обновите MeetSpace на сервере.")
            throw failure
        }
        val page = decode<TimelineDto>(body)
        success(page.status)
        val items = page.items.orEmpty()
        if (items.size > query.limit || items.map { it.id }.distinct().size != items.size ||
            page.nextCursor?.let { it.isBlank() || it.length > 256 || it.any(Char::isISOControl) } == true) throw InvalidContract()
        return MeetingPage(items.map { it.model(origin) }, page.nextCursor)
    }

    suspend fun conference(origin: String, token: String, id: String): Meeting = meetingResponse(
        api.authenticatedRequest(origin, conferencePath(id), token), origin, id)

    suspend fun participants(origin: String, token: String, id: String): List<MeetingParticipant> {
        val result = mutableListOf<MeetingParticipant>()
        repeat(10) { page ->
            val response = decode<ParticipantsResponse>(api.authenticatedRequest(origin, conferencePath(id) + "participants", token, mapOf("limit" to "100", "offset" to "${page * 100}")))
            success(response.status)
            val items = response.items.orEmpty().map { it.model() }
            if (items.size > 100 || items.any { it.conferenceId != id }) throw InvalidContract()
            result.addAll(items)
            if (items.size < 100) { if (result.map { it.id }.distinct().size != result.size) throw InvalidContract(); return result }
        }
        throw AppFailure(FailureKind.UNAVAILABLE, "Список слишком большой. Обновите участников позже.")
    }
    suspend fun control(origin: String, token: String, id: String, participantId: String, action: MeetingModeration?, admit: Boolean?): MeetingParticipant {
        MessagingApi.inputId(participantId)
        if (action != null && (action.action !in setOf("mute", "camera", "screen", "kick", "role") ||
            action.action == "role" && action.role !in setOf("co_host", "participant") ||
            action.action in setOf("mute", "camera", "screen") && action.blocked == null)) invalidInput()
        val payload = if (action == null) MessagingApi.fields("decision" to if (admit == true) "admit" else "reject")
            else MessagingApi.fields("action" to action.action, "blocked" to action.blocked, "role" to action.role)
        val response = decode<ParticipantResponse>(api.authenticatedRequest(origin, conferencePath(id) + listOf("participants", participantId, if (action == null) "admission" else "moderation"), token, method = "POST", payload = payload.toString()))
        success(response.status)
        return response.item.model().also { if (it.id != participantId || it.conferenceId != id) throw InvalidContract() }
    }
    suspend fun create(origin: String, token: String, input: MeetingInput): Meeting {
        val title = input.title.trim()
        if (title.isEmpty() || title.codePointCount(0, title.length) > 200 || title.any(Char::isISOControl)) invalidInput()
        validateSchedule(input.scheduledAt, input.plannedDurationMin)
        return meetingResponse(api.authenticatedRequest(origin, listOf("conferences"), token, method = "POST",
            payload = wireJson.encodeToString(CreateDto(title, input.waitingRoomEnabled,
                input.scheduledAt?.toString(), input.plannedDurationMin))), origin)
    }

    suspend fun schedule(origin: String, token: String, id: String, at: Instant, duration: Int?): Meeting {
        validateSchedule(at, duration)
        return meetingResponse(api.authenticatedRequest(origin, conferencePath(id) + "schedule", token,
            method = "PUT", payload = wireJson.encodeToString(ScheduleDto(at.toString(), duration))), origin, id)
    }

    suspend fun transition(origin: String, token: String, id: String, transition: MeetingTransition): Meeting =
        meetingResponse(api.authenticatedRequest(origin, conferencePath(id) + transition.wireValue, token,
            method = "POST"), origin, id)

    suspend fun membership(origin: String, token: String, id: String): MeetingParticipant? = try {
        participantResponse(api.authenticatedRequest(origin, conferencePath(id) + listOf("participants", "me"), token), id)
    } catch (failure: HttpFailure) {
        if (failure.statusCode == 404) null else throw failure
    }

    suspend fun join(origin: String, token: String, id: String, inviteCode: String?): MeetingParticipant {
        val code = inviteCode?.let { MeetingInvites.parse(origin, it) }
        return participantResponse(api.authenticatedRequest(origin, conferencePath(id) + "join", token,
            method = "POST", payload = wireJson.encodeToString(JoinDto(code.orEmpty()))), id)
    }

    suspend fun joinInvite(origin: String, token: String, raw: String): MeetingParticipant {
        val code = MeetingInvites.parse(origin, raw)
        return participantResponse(api.authenticatedRequest(origin, listOf("conference-invites", code, "join"), token,
            method = "POST"))
    }

    suspend fun leave(origin: String, token: String, id: String): MeetingParticipant =
        participantResponse(api.authenticatedRequest(origin, conferencePath(id) + "leave", token, method = "POST"), id)

    suspend fun history(origin: String, token: String, id: String): MeetingHistory {
        val response = decode<HistoryResponse>(api.authenticatedRequest(origin, conferencePath(id) + "history", token))
        success(response.status)
        val item = response.item
        val meeting = item.conference.model(origin)
        if (meeting.id != id || !meeting.isClosed || item.durationSec?.let { it < 0 } == true ||
            item.participantCount < 0 || item.participants.orEmpty().size > 1000 ||
            item.participants.orEmpty().size > item.participantCount) throw InvalidContract()
        wireId(item.owner.id)
        if (item.owner.id != meeting.ownerId) throw InvalidContract()
        safeText(item.owner.displayName.orEmpty(), 256)
        val participants = item.participants.orEmpty().map { it.model() }
        if (participants.any { it.conferenceId != id } || participants.map { it.id }.distinct().size != participants.size) throw InvalidContract()
        val counts = item.recordings.run { listOf(total, ready, processing, failed) }
        if (counts.any { it < 0 } || counts.drop(1).any { it > item.recordings.total }) throw InvalidContract()
        return MeetingHistory(meeting, item.owner.id, item.owner.displayName, item.durationSec, item.participantCount,
            participants, item.participantsTruncated, item.recordings.run { MeetingRecordingsSummary(total, ready, processing, failed) },
            item.chatAvailable, item.chatReadOnly)
    }

    private fun meetingResponse(body: String, origin: String, expectedId: String? = null): Meeting {
        val response = decode<MeetingResponse>(body)
        success(response.status)
        return response.item.model(origin).also { if (expectedId != null && it.id != expectedId) throw InvalidContract() }
    }

    private fun participantResponse(body: String, expectedId: String? = null): MeetingParticipant {
        val response = decode<ParticipantResponse>(body)
        success(response.status)
        return response.item.model().also { if (expectedId != null && it.conferenceId != expectedId) throw InvalidContract() }
    }
}

/** Codes and copied links are resolved locally. No request ever follows an invitation URL. */
object MeetingInvites {
    fun parse(origin: String, rawCodeOrUrl: String): String {
        val raw = rawCodeOrUrl.trim()
        if (isCode(raw)) return raw
        if (raw.length > 2048 || raw.any { it.isWhitespace() || it.isISOControl() || it == '\\' }) invalidInvite()
        val url = if (raw.startsWith('/') && !raw.startsWith("//")) (origin + raw).toHttpUrlOrNull()
            else raw.toHttpUrlOrNull()
        if (url == null || url.scheme != "https" || !ServerOrigin.matches(origin, url) ||
            url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) invalidInvite()
        val segments = url.pathSegments
        val code = when {
            segments.size == 2 && segments[0] == "i" -> segments[1]
            segments.size == 4 && segments.take(3) == listOf("api", "v1", "conference-invites") -> segments[3]
            else -> invalidInvite()
        }
        if (!isCode(code) || url.encodedPath != "/" + segments.joinToString("/")) invalidInvite()
        return code
    }

    fun shareUrl(origin: String, code: String): String = "$origin/i/${parse(origin, code)}"

    private fun isCode(value: String): Boolean = value.matches(Regex("[A-Za-z0-9_-]{32}"))
    private fun invalidInvite(): Nothing = throw AppFailure(FailureKind.VALIDATION,
        "Введите код приглашения или HTTPS-ссылку этого сервера вида /i/код.")
}

private fun conferencePath(id: String): List<String> {
    if (!isId(id)) invalidInput()
    return listOf("conferences", id)
}
private fun isId(id: String): Boolean = id.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) &&
    runCatching { UUID.fromString(id) != UUID(0, 0) }.getOrDefault(false)
private fun wireId(id: String) { if (!isId(id)) throw InvalidContract() }
private fun safeText(text: String, max: Int) { if (text.length > max || text.any(Char::isISOControl)) throw InvalidContract() }
private fun success(status: String) { if (status != "success") throw InvalidContract() }
private fun instant(raw: String): Instant = try {
    Instant.parse(raw).also {
        if (it < Instant.parse("0001-01-01T00:00:00Z") || it > Instant.parse("9999-12-31T23:59:59.999999999Z")) throw InvalidContract()
    }
} catch (_: Exception) { throw InvalidContract() }
private fun validateSchedule(at: Instant?, duration: Int?) {
    if (duration != null && (duration !in 1..1440 || at == null) || at != null &&
        (at <= Instant.now() || at > Instant.parse("9999-12-31T23:59:59Z"))) invalidInput()
}
private fun invalidInput(): Nothing = throw AppFailure(FailureKind.VALIDATION, "Проверьте название, время и длительность встречи.")
private inline fun <reified T> decode(body: String): T = try { wireJson.decodeFromString<T>(body) }
catch (_: SerializationException) { throw InvalidContract() }
catch (_: IllegalArgumentException) { throw InvalidContract() }

@Serializable private data class TimelineDto(val status: String, val items: List<MeetingDto>?, val nextCursor: String?)
@Serializable private data class MeetingResponse(val status: String, val item: MeetingDto)
@Serializable private data class ParticipantResponse(val status: String, val item: ParticipantDto)
@Serializable private data class CreateDto(val title: String, val waitingRoomEnabled: Boolean, val scheduledAt: String?, val plannedDurationMin: Int?)
@Serializable private data class ScheduleDto(val scheduledAt: String, val plannedDurationMin: Int?)
@Serializable private data class JoinDto(val inviteCode: String)
@Serializable private data class MeetingDto(
    val id: String, val ownerId: String, val title: String, val inviteCode: String, val inviteUrl: String,
    val status: String, val createdAt: String, val updatedAt: String, val startedAt: String? = null,
    val finishedAt: String? = null, val scheduledAt: String? = null, val plannedDurationMin: Int? = null,
    val waitingRoomEnabled: Boolean = false, val participantCount: Int? = null,
) {
    fun model(origin: String): Meeting {
        wireId(id); wireId(ownerId); safeText(title, 800); safeText(status, 64)
        if (title.isBlank() || title.codePointCount(0, title.length) > 200 || status.isBlank() ||
            plannedDurationMin?.let { it !in 1..1440 } == true || participantCount?.let { it < 0 } == true ||
            (plannedDurationMin != null && scheduledAt == null)) throw InvalidContract()
        try {
            if (inviteCode.isNotEmpty()) MeetingInvites.parse(origin, inviteCode)
            if (inviteUrl.isNotEmpty()) {
                val parsed = MeetingInvites.parse(origin, inviteUrl)
                if (inviteCode.isNotEmpty() && parsed != inviteCode) throw InvalidContract()
            }
        } catch (_: AppFailure) { throw InvalidContract() }
        return Meeting(id, ownerId, title, inviteCode, inviteUrl, status, instant(createdAt), instant(updatedAt),
            startedAt?.let(::instant), finishedAt?.let(::instant), scheduledAt?.let(::instant),
            plannedDurationMin, waitingRoomEnabled, participantCount)
    }
}
@Serializable private data class ParticipantDto(
    val id: String, val conferenceId: String, val userId: String?, val displayName: String,
    val role: String, val status: String, val admissionState: String? = null,
    val joinedAt: String? = null, val leftAt: String? = null,
    val microphoneBlocked: Boolean = false, val cameraBlocked: Boolean = false, val screenBlocked: Boolean = false,
) {
    fun model(): MeetingParticipant {
        wireId(id); wireId(conferenceId); userId?.let(::wireId)
        safeText(displayName, 256); safeText(role, 64); safeText(status, 64); admissionState?.let { safeText(it, 64) }
        if (role.isBlank() || status.isBlank()) throw InvalidContract()
        return MeetingParticipant(id, conferenceId, userId, displayName, role, status, admissionState,
            joinedAt?.let(::instant), leftAt?.let(::instant), microphoneBlocked, cameraBlocked, screenBlocked)
    }
}
@Serializable private data class HistoryResponse(val status: String, val item: HistoryDto)
@Serializable private data class HistoryDto(val conference: MeetingDto, val owner: OwnerDto, val durationSec: Long?,
    val participantCount: Int, val participants: List<ParticipantDto>?, val participantsTruncated: Boolean,
    val recordings: RecordingDto, val chatAvailable: Boolean, val chatReadOnly: Boolean)
@Serializable private data class OwnerDto(val id: String, val displayName: String? = null)
@Serializable private data class RecordingDto(val total: Long, val ready: Long, val processing: Long, val failed: Long)

@Serializable private data class ParticipantsResponse(val status: String, val items: List<ParticipantDto>? = null)
