package com.meetspace.core.data

import com.meetspace.core.model.*
import com.meetspace.core.model.MeetingHistory
import com.meetspace.core.model.MeetingInput
import com.meetspace.core.model.MeetingPage
import com.meetspace.core.model.MeetingParticipant
import com.meetspace.core.model.MeetingTransition
import com.meetspace.core.model.MeetingsQuery
import com.meetspace.core.model.User
import java.time.Instant

/** Uses the foundation's selected account; credentials never cross this boundary. */
interface MeetingsRepository {
    suspend fun timeline(query: MeetingsQuery = MeetingsQuery()): MeetingPage
    suspend fun conference(id: String): Meeting
    suspend fun createMeeting(input: MeetingInput): Meeting
    suspend fun scheduleMeeting(id: String, scheduledAt: Instant, plannedDurationMin: Int?): Meeting
    suspend fun transitionMeeting(id: String, transition: MeetingTransition): Meeting
    /** Only a server 404 represents absent membership; 403 stays an error. */
    suspend fun membership(id: String): MeetingParticipant?
    suspend fun joinMeeting(id: String, inviteCode: String? = null): MeetingParticipant
    suspend fun joinInvite(rawCodeOrUrl: String): MeetingParticipant
    suspend fun leaveMeeting(id: String): MeetingParticipant
    suspend fun history(id: String): MeetingHistory
    suspend fun participants(id: String): List<MeetingParticipant> = throw AppFailure(FailureKind.UNAVAILABLE, "Список участников недоступен.")
    suspend fun admitParticipant(id: String, participantId: String, admit: Boolean): MeetingParticipant = throw AppFailure(FailureKind.UNAVAILABLE, "Зал ожидания недоступен.")
    suspend fun moderateParticipant(id: String, participantId: String, action: MeetingModeration): MeetingParticipant = throw AppFailure(FailureKind.UNAVAILABLE, "Управление участниками недоступно.")
    suspend fun invitationUsers(id: String, query: String): List<MeetingInviteUser> = throw AppFailure(FailureKind.UNAVAILABLE, "Поиск приглашений недоступен.")
    suspend fun inviteParticipants(id: String, userIds: List<String>, emails: List<String>): Unit = throw AppFailure(FailureKind.UNAVAILABLE, "Приглашения недоступны.")
    suspend fun updateDisplayName(displayName: String): User
}
