package com.meetspace.core.data

import com.meetspace.core.model.Meeting
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
    suspend fun updateDisplayName(displayName: String): User
}
