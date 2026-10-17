package com.meetspace.core.model

import java.time.Instant

enum class MeetingsView(val wireValue: String) { UPCOMING("upcoming"), ACTIVE("active"), PAST("past") }
enum class MeetingsScope(val wireValue: String) { ALL("all"), OWNED("owned"), PARTICIPATING("participating") }
enum class MeetingTransition(val wireValue: String) { START("start"), FINISH("finish"), CANCEL("cancel") }

data class MeetingsQuery(
    val view: MeetingsView = MeetingsView.UPCOMING,
    val scope: MeetingsScope = MeetingsScope.ALL,
    val from: Instant? = null,
    val to: Instant? = null,
    val cursor: String? = null,
    val limit: Int = 20,
)

/** Unknown server states stay visible and never grant new actions to this client. */
data class Meeting(
    val id: String,
    val ownerId: String,
    val title: String,
    val inviteCode: String,
    val inviteUrl: String,
    val status: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val startedAt: Instant? = null,
    val finishedAt: Instant? = null,
    val scheduledAt: Instant? = null,
    val plannedDurationMin: Int? = null,
    val waitingRoomEnabled: Boolean = false,
    val participantCount: Int? = null,
) {
    val isClosed: Boolean get() = status == "finished" || status == "cancelled"
    val isKnownStatus: Boolean get() = status in setOf("created", "scheduled", "active", "finished", "cancelled")
    val timelineAt: Instant get() = finishedAt ?: scheduledAt ?: createdAt
}

data class MeetingInput(
    val title: String,
    val waitingRoomEnabled: Boolean = false,
    val scheduledAt: Instant? = null,
    val plannedDurationMin: Int? = null,
)

data class MeetingPage(val items: List<Meeting>, val nextCursor: String?)

data class MeetingParticipant(
    val id: String,
    val conferenceId: String,
    val userId: String?,
    val displayName: String,
    val role: String,
    val status: String,
    val admissionState: String? = null,
    val joinedAt: Instant? = null,
    val leftAt: Instant? = null,
    val microphoneBlocked: Boolean = false, val cameraBlocked: Boolean = false, val screenBlocked: Boolean = false,
) {
    val isAdmitted: Boolean get() = (admissionState.isNullOrEmpty() || admissionState == "admitted") &&
        status in setOf("joined", "left")
    val canParticipate: Boolean get() = isAdmitted && status == "joined"
}

data class MeetingRecordingsSummary(val total: Long, val ready: Long, val processing: Long, val failed: Long)

data class MeetingHistory(
    val conference: Meeting,
    val ownerId: String,
    val ownerDisplayName: String?,
    val durationSec: Long?,
    val participantCount: Int,
    val participants: List<MeetingParticipant>,
    val participantsTruncated: Boolean,
    val recordings: MeetingRecordingsSummary,
    val chatAvailable: Boolean,
    val chatReadOnly: Boolean,
)


data class MeetingModeration(val action: String, val blocked: Boolean? = null, val role: String? = null)
data class MeetingInviteUser(val id: String, val displayName: String, val email: String)
fun canModerateMeeting(actor: MeetingParticipant?, target: MeetingParticipant, action: String): Boolean =
    action in setOf("mute", "camera", "screen", "kick", "role") && actor?.canParticipate == true && actor.conferenceId == target.conferenceId && actor.id != target.id && target.role != "owner" &&
        target.isAdmitted && (actor.role == "owner" || actor.role == "co_host" && target.role == "participant" && action in setOf("mute", "screen", "kick"))
