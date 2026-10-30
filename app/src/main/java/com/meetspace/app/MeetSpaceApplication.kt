package com.meetspace.app

import android.app.Application
import com.meetspace.core.data.FoundationRepository
import com.meetspace.core.data.FoundationRepositoryFactory
import com.meetspace.core.data.MeetingsRepository
import com.meetspace.core.ssh.SshPreflightRepository
import com.meetspace.core.ssh.SshPreflightRepositoryFactory

open class MeetSpaceApplication : Application() {
    val outgoingMessageStore: OutgoingMessageStore by lazy { EncryptedOutgoingMessageStore(this) }
    open val callEngine: com.meetspace.core.media.CallEngine by lazy {
        com.meetspace.core.media.CallEngine(this, repository as com.meetspace.core.data.CallRepository, meetingsRepository) {
            stopService(android.content.Intent(this, ScreenShareService::class.java))
        }
    }
    val installerRepository: com.meetspace.core.ssh.SshInstallerRepository by lazy { com.meetspace.core.ssh.SshInstallerRepositoryFactory.create(this) }
    open val repository: FoundationRepository by lazy { FoundationRepositoryFactory.create(this) }
    val meetingsRepository: MeetingsRepository get() = repository as MeetingsRepository
    val meetingChatRepository: com.meetspace.core.data.MeetingChatRepository get() = repository as com.meetspace.core.data.MeetingChatRepository
    val recordingsRepository: com.meetspace.core.data.RecordingsRepository get() = repository as com.meetspace.core.data.RecordingsRepository
    val messagingRepository: com.meetspace.core.data.MessagingRepository get() = repository as com.meetspace.core.data.MessagingRepository
    val directCallsRepository: com.meetspace.core.data.DirectCallsRepository get() = repository as com.meetspace.core.data.DirectCallsRepository
    val pushController by lazy { PushController(this, repository, messagingRepository, messagingNotifications) }
    val messagingNotifications by lazy { MessagingNotifications(this, identity = {
        val current = repository.state.value
        if (current.session?.guestMeeting != null) null else {
            val origin = current.selectedProfile?.origin
            val user = current.session?.user?.id
            if (origin != null && user != null) "$origin|$user" else null
        }
    }) }
    val sshRepository: SshPreflightRepository by lazy { SshPreflightRepositoryFactory.create(this) }
}
