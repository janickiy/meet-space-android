package com.meetspace.core.data

import com.meetspace.core.model.*
import java.io.File
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.Base64
import java.util.Properties
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Mutations are allowed only in our marked local PostgreSQL/Redis/S3 fixture. */
class CurrentApiMutationTest {
    @Test fun registrationProfileAvatarsRestoreAndDeletion(): Unit = runBlocking {
        fixture { f ->
            val profiles = Profiles(); val credentials = Credentials(); val account = f.repository(profiles, credentials)
            val email = "audit-${UUID.randomUUID()}@example.test"; val password = "Fixture-${UUID.randomUUID()}"
            account.addServer(f.origin)
            assertTrue(account.register("Android API audit", email, password).signedIn)
            val user = account.state.value.session!!.user
            assertEquals("Updated profile", account.updateDisplayName("Updated profile").displayName)
            assertNotNull(account.putUserAvatar("image/png", png).avatarVersion)
            assertTrue(account.userAvatar(user.id).size > 100)
            assertNull(account.deleteUserAvatar().avatarVersion)
            val firstProfile = account.state.value.selectedProfileId!!
            val extraEmail = "audit-${UUID.randomUUID()}@example.test"
            val extra = account.addAccount(f.origin, extraEmail, password, "Second isolated account")
            assertTrue(extra.signedIn)
            assertEquals(2, account.state.value.profiles.size)
            account.selectServer(firstProfile)
            assertEquals(user.id, account.state.value.session!!.user.id)
            val group = account.createGroup("Avatar audit", "", listOf(extra.user.id), UUID.randomUUID().toString())
            assertNotNull(account.putGroupAvatar(group.id, "image/png", png).avatarVersion)
            assertTrue(account.groupAvatar(group.id).size > 100)
            assertNull(account.deleteGroupAvatar(group.id).avatarVersion)
            account.changeGroupRole(group.id, extra.user.id, "admin")
            assertEquals("admin", account.groupMembers(group.id).single { it.id == extra.user.id }.role)
            account.changeGroupRole(group.id, extra.user.id, "member")
            assertFalse(account.setConversationNotifications(group.id, false).notificationsEnabled)
            account.transferGroupOwnership(group.id, extra.user.id)
            val newOwner = f.repository().also { it.addServer(f.origin); it.login(extraEmail, password) }
            assertEquals("owner", newOwner.conversation(group.id).myRole)
            account.leaveGroup(group.id); newOwner.deleteGroup(group.id)
            val second = f.repository(profiles, credentials)
            second.restore(); assertEquals(user.id, second.state.value.session?.user?.id)
            second.deleteAccount(password)
            assertEquals(extra.user.id, second.state.value.session?.user?.id)
            assertEquals(1, second.state.value.profiles.size)
        }
    }
    @Test fun meetingsCommentsFilesRecordingMetadataAndCatalogue(): Unit = runBlocking {
        fixture { f ->
            val owner = f.actor(0)
            println("Current API: conference create and schedule")
            var meeting = owner.createMeeting(MeetingInput("Current Android API audit", waitingRoomEnabled = false,
                scheduledAt = Instant.now().plusSeconds(3600), plannedDurationMin = 30))
            meeting = owner.scheduleMeeting(meeting.id, Instant.now().plusSeconds(7200), 30)
            assertEquals("scheduled", meeting.status)
            println("Current API: conference start and join")
            owner.transitionMeeting(meeting.id, MeetingTransition.START)
            owner.joinMeeting(meeting.id)
            println("Current API: conference comments and read marker")
            val input = ChatMessageInput(UUID.randomUUID().toString(), "Isolated conference comment")
            val sent = owner.sendMeetingMessage(meeting.id, input)
            assertEquals(sent.id, owner.sendMeetingMessage(meeting.id, input).id)
            assertTrue(owner.meetingMessages(meeting.id).items.any { it.id == sent.id })
            assertEquals(sent.id, owner.markMeetingChatRead(meeting.id, sent.id).lastReadMessageId)
            println("Current API: conference file upload and download")
            val content = "Isolated conference file".toByteArray()
            val uploaded = owner.uploadMeetingAttachment(meeting.id, "audit.txt", "text/plain", content, UUID.randomUUID().toString())
            owner.sendMeetingMessage(meeting.id, ChatMessageInput(UUID.randomUUID().toString(), "", attachmentIds = listOf(uploaded.id)))
            val file = owner.meetingMessages(meeting.id).items.flatMap { it.attachments }.single { it.id == uploaded.id }
            assertArrayEquals(content, owner.downloadMeetingAttachment(meeting.id, file))
            println("Current API: recording start")
            val recording = owner.startRecording(meeting.id, "composite")
            println("Current API: recording read and catalogue")
            assertEquals(recording.id, owner.recording(meeting.id, recording.id).id)
            assertTrue(owner.recordings(meeting.id).items.any { it.id == recording.id })
            assertTrue(owner.recordingLibrary().items.any { it.recording.id == recording.id })
            println("Current API: recording stop and conference finish")
            owner.stopRecording(meeting.id, recording.id)
            owner.transitionMeeting(meeting.id, MeetingTransition.FINISH)
            assertEquals(meeting.id, owner.history(meeting.id).conference.id)
            assertTrue(owner.recordingLibrary().items.any { it.recording.id == recording.id })
        }
    }
    @Test fun directCallCreateAcceptHeartbeatEndAndMessageHistory(): Unit = runBlocking {
        fixture { f ->
            val caller = f.actor(0); val callee = f.actor(1)
            val client = UUID.randomUUID().toString(); val receivingClient = UUID.randomUUID().toString()
            val request = UUID.randomUUID().toString(); val target = callee.state.value.session!!.user.id
            val call = caller.startDirectCall(target, request, client)
            try {
                assertEquals(call.id, caller.startDirectCall(target, request, client).id)
                assertEquals(call.id, callee.activeDirectCall()?.id)
                val accepted = callee.directCallAction(call.id, DirectCallAction.ACCEPT, receivingClient)
                assertEquals("accepted", accepted.status)
                assertNotNull(accepted.conferenceId)
                assertEquals("accepted", caller.directCallAction(call.id, DirectCallAction.HEARTBEAT, client).status)
                assertEquals("ended", callee.directCallAction(call.id, DirectCallAction.END, receivingClient).status)
                assertEquals("ended", caller.directCall(call.id).status)
                assertTrue(caller.messages(call.conversationId).items.any { it.call?.id == call.id })
            } finally {
                if (caller.directCall(call.id).active) caller.directCallAction(call.id, DirectCallAction.CANCEL, client)
            }
        }
    }
    @Test fun guestInvitationAdmissionAndConferenceDiscussion(): Unit = runBlocking {
        fixture { f ->
            val owner = f.actor(0)
            val meeting = owner.createMeeting(MeetingInput("Isolated guest API audit"))
            owner.transitionMeeting(meeting.id, MeetingTransition.START)
            val guest = f.repository().also { it.addServer(f.origin) }
            println("Current API: guest join")
            guest.joinGuest(meeting.inviteCode, "Guest API audit")
            println("Current API: guest session established")
            assertEquals(meeting.id, guest.state.value.session?.guestMeeting?.id)
            assertFalse(guest.state.value.session!!.supportsPersistentSession)
            val message = guest.sendMeetingMessage(meeting.id, ChatMessageInput(UUID.randomUUID().toString(), "Isolated guest comment"))
            println("Current API: guest comment sent")
            assertTrue(guest.meetingMessages(meeting.id).items.any { it.id == message.id })
            guest.leaveMeeting(meeting.id)
            println("Current API: guest left")
            owner.transitionMeeting(meeting.id, MeetingTransition.FINISH)
        }
    }
    private suspend fun fixture(block: suspend (Fixture) -> Unit) {
        assumeTrue(System.getenv("MEETSPACE_CURRENT_API_FIXTURE") != null)
        val home = File(requireNotNull(System.getenv("MEETSPACE_CURRENT_API_FIXTURE")))
        require(File(home, "fixture-marker").readText().trim() == "meetspace-api-compatibility-20261101")
        val f = Fixture(home)
        try { block(f) } finally { f.close() }
    }
    private class Fixture(home: File) {
        private val input = Properties().apply { File(home, "live.properties").inputStream().use(::load) }
        val origin = input.getProperty("origin").also { require(it.startsWith("https://127.0.0.1:")) }
        private val certificate = File(input.getProperty("caPath")).inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate }
        private val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate).build()
        private val client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
        private val actors = mutableListOf<DefaultFoundationRepository>()
        fun repository(profiles: Profiles = Profiles(), credentials: Credentials = Credentials()) =
            DefaultFoundationRepository(profiles, credentials, MeetSpaceApi(client)).also(actors::add)
        suspend fun actor(index: Int) = repository().also {
            it.addServer(origin); it.login(input.getProperty("email$index"), input.getProperty("password$index"))
        }
        suspend fun close() { actors.forEach { runCatching { it.logout() } }; client.dispatcher.cancelAll(); client.connectionPool.evictAll(); input.clear() }
    }
    private class Profiles : ProfileStore {
        private var value = PersistedSettings()
        override suspend fun read() = value
        override suspend fun write(settings: PersistedSettings) { value = settings }
    }
    private class Credentials : CredentialStore {
        private val values = mutableMapOf<SessionScope, StoredCredential>()
        override suspend fun read(scope: SessionScope) = values[scope]
        override suspend fun write(scope: SessionScope, credential: StoredCredential) { values[scope] = credential }
        override suspend fun delete(scope: SessionScope) { values.remove(scope) }
    }
    private val png get() = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGP4//8/AAX+Av4N70a4AAAAAElFTkSuQmCC")
}
