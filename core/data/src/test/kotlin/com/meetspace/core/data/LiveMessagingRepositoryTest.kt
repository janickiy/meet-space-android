package com.meetspace.core.data

import com.meetspace.core.model.*
import java.io.File
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Properties
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in real pinned backend. Custom CA is injected only into this localhost test client. */
class LiveMessagingRepositoryTest {
    @Test fun `actual account WSS plus idempotent message reply edit read removal and reconnect`() = runBlocking {
        withFixture { fixture ->
            val owner = fixture.actor(0); val member = fixture.actor(1); val outsider = fixture.actor(2)
            val ownerSocket = owner.openUserEvents(); val memberSocket = member.openUserEvents()
            try {
                ownerSocket.await("connected"); memberSocket.await("connected")
                val direct = owner.createDirect(member.state.value.session!!.user.id)
                assertEquals(direct.id, member.createDirect(owner.state.value.session!!.user.id).id)
                val request = ChatMessageInput(UUID.randomUUID().toString(), "Android real message ${UUID.randomUUID()}")
                val first = owner.sendMessage(direct.id, request)
                assertEquals(first.id, owner.sendMessage(direct.id, request).id)
                assertEquals(FailureKind.CONFLICT, failure { owner.sendMessage(direct.id, request.copy(text = "Changed")) }.kind)
                memberSocket.await("message.created", direct.id)
                assertEquals(FailureKind.FORBIDDEN, failure { outsider.messages(direct.id) }.kind)
                val reply = member.sendMessage(direct.id, ChatMessageInput(UUID.randomUUID().toString(), "Actual reply", first.id))
                assertEquals(first.id, reply.replyPreview!!.id)
                val edited = owner.editMessage(direct.id, first.id, "Actual edited message")
                assertTrue(edited.version > first.version)
                assertEquals(FailureKind.FORBIDDEN, failure { member.editMessage(direct.id, first.id, "Forbidden") }.kind)
                assertEquals(0, member.markConversationRead(direct.id, reply.id).unreadCount)
                assertEquals(reply.id, member.markConversationRead(direct.id, first.id).lastReadMessageId)
                val deleted = owner.deleteMessage(direct.id, first.id)
                assertNotNull(deleted.deletedAt); assertEquals("", deleted.text)
                memberSocket.close()
                val missed = owner.sendMessage(direct.id, ChatMessageInput(UUID.randomUUID().toString(), "While WSS closed"))
                val reconnected = member.openUserEvents()
                try {
                    reconnected.await("connected")
                    assertTrue(member.messages(direct.id).items.any { it.id == missed.id })
                    owner.sendMessage(direct.id, ChatMessageInput(UUID.randomUUID().toString(), "After WSS reconnect"))
                    reconnected.await("message.created", direct.id)
                    assertTrue(owner.peerPresence(direct.id))
                } finally { reconnected.close() }
                val group = owner.createGroup("Android real group", "Disposable test", listOf(member.state.value.session!!.user.id), UUID.randomUUID().toString())
                try {
                    assertEquals("owner", group.myRole)
                    assertEquals(2, owner.groupMembers(group.id).size)
                    val groupMessage = owner.sendMessage(group.id, ChatMessageInput(UUID.randomUUID().toString(), "Private membership"))
                    assertTrue(member.messages(group.id).items.any { it.id == groupMessage.id })
                    owner.removeGroupMember(group.id, member.state.value.session!!.user.id)
                    assertEquals(FailureKind.FORBIDDEN, failure { member.messages(group.id) }.kind)
                } finally { owner.deleteGroup(group.id) }
                owner.logout()
                withTimeout(5_000) { while (ownerSocket.events.receiveCatching().isSuccess) { /* drain bounded invalidations */ } }
                assertNull(owner.state.value.session)
            } finally { ownerSocket.close(); memberSocket.close() }
        }
    }

    @Test fun `actual private MinIO direct and group attachments preserve bytes and access scope`() = runBlocking {
        withFixture { fixture ->
            val owner = fixture.actor(0); val member = fixture.actor(1); val outsider = fixture.actor(2)
            val direct = owner.createDirect(member.state.value.session!!.user.id)
            val group = owner.createGroup("Android attachment verification", "", listOf(member.state.value.session!!.user.id), UUID.randomUUID().toString())
            try {
                for (conversation in listOf(direct, group)) {
                    val bytes = "Android native repository private file ${UUID.randomUUID()}\n".toByteArray()
                    val uploaded = owner.uploadAttachment(conversation.id, "verification.txt", "text/plain", bytes, UUID.randomUUID().toString())
                    assertEquals("ready", uploaded.status)
                    val sent = owner.sendMessage(conversation.id, ChatMessageInput(UUID.randomUUID().toString(), "Real file", attachmentIds = listOf(uploaded.id)))
                    val attachment = sent.attachments.single()
                    assertEquals("attached", attachment.status)
                    assertArrayEquals(bytes, member.downloadAttachment(conversation.id, attachment))
                    assertEquals(FailureKind.FORBIDDEN, failure { outsider.downloadAttachment(conversation.id, attachment) }.kind)
                    if (conversation.type == "group") {
                        owner.removeGroupMember(group.id, member.state.value.session!!.user.id)
                        assertEquals(FailureKind.FORBIDDEN, failure { member.downloadAttachment(group.id, attachment) }.kind)
                    }
                    bytes.fill(0)
                }
            } finally { owner.deleteGroup(group.id) }
        }
    }

    @Test fun `actual personal folders mixed items notification preferences and noop push contracts`() = runBlocking {
        withFixture { fixture ->
            val owner = fixture.actor(0); val member = fixture.actor(1)
            val direct = owner.createDirect(member.state.value.session!!.user.id)
            val folder = owner.createFolder("Android ${UUID.randomUUID().toString().take(8)}")
            val meeting = owner.createMeeting(MeetingInput("Android folder meeting"))
            try {
                owner.setFolderItem(folder.id, "conversation", direct.id, true)
                val mixed = owner.setFolderItem(folder.id, "conference", meeting.id, true)
                assertEquals(2, mixed.itemCount)
                val items = owner.folderItems(folder.id).items
                assertTrue(items.any { it.conversation?.id == direct.id })
                assertTrue(items.any { it.meeting?.id == meeting.id })
                assertEquals(FailureKind.NOT_FOUND, failure { member.folderItems(folder.id) }.kind)
                assertEquals("Renamed", owner.renameFolder(folder.id, "Renamed").name)
                assertTrue(owner.reorderFolders(owner.folders().map { it.id }).any { it.id == folder.id })
                val prefs = owner.notificationPreferences()
                assertEquals(prefs, owner.saveNotificationPreferences(prefs))
                assertEquals("noop", owner.integrationCapabilities().push)
                assertEquals(FailureKind.UNAVAILABLE, failure { owner.registerPushDevice("disposable-unregistered-test-token") }.kind)
                owner.sendMessage(direct.id, ChatMessageInput(UUID.randomUUID().toString(), "Notification proof"))
                val page = member.notifications()
                assertTrue(page.items.isNotEmpty())
                assertNotNull(member.readNotification(page.items.first().id).readAt)
                assertEquals(FailureKind.NOT_FOUND, failure { owner.readNotification(page.items.first().id) }.kind)
                assertEquals(1, owner.setFolderItem(folder.id, "conference", meeting.id, false).itemCount)
            } finally { owner.deleteFolder(folder.id); owner.transitionMeeting(meeting.id, MeetingTransition.CANCEL) }
        }
    }

    private suspend fun UserEvents.await(type: String, conversationId: String? = null) = withTimeout(10_000) {
        var event = events.receive()
        while (event.type != type || (conversationId != null && event.conversationId != conversationId)) event = events.receive()
        event
    }
    private suspend fun failure(block: suspend () -> Any?): AppFailure = try { block(); error("Expected curated failure") } catch (failure: AppFailure) { failure }
    private suspend fun withFixture(block: suspend (Fixture) -> Unit) {
        assumeTrue("Stage07 disposable fixture is opt-in", System.getenv("MEETSPACE_STAGE07_LIVE") == "1")
        val properties = Properties().apply { File(requireNotNull(System.getenv("MEETSPACE_STAGE07_FIXTURE"))).inputStream().use(::load) }
        val fixture = Fixture(properties)
        try { block(fixture) } finally { fixture.close(); properties.clear() }
    }
    private class Fixture(private val properties: Properties) {
        private val repositories = mutableListOf<DefaultFoundationRepository>()
        private val certificate = File(properties.getProperty("caPath")).inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate }
        private val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate).build()
        private val client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).callTimeout(20, TimeUnit.SECONDS).build()
        suspend fun actor(index: Int): DefaultFoundationRepository {
            val origin = properties.getProperty("origin")
            check(origin.startsWith("https://127.0.0.1:"))
            return DefaultFoundationRepository(MessagingLiveProfiles(), MessagingLiveCredentials(), MeetSpaceApi(client)).also {
                repositories += it; it.addServer(origin); it.login(properties.getProperty("email$index"), properties.getProperty("password$index"))
            }
        }
        suspend fun close() { repositories.forEach { if (it.state.value.session != null) runCatching { it.logout() } }; client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }
}
private class MessagingLiveProfiles : ProfileStore {
    private var settings = PersistedSettings()
    override suspend fun read() = settings
    override suspend fun write(settings: PersistedSettings) { this.settings = settings }
}
private class MessagingLiveCredentials : CredentialStore {
    private val values = mutableMapOf<SessionScope, StoredCredential>()
    override suspend fun read(scope: SessionScope) = values[scope]
    override suspend fun write(scope: SessionScope, credential: StoredCredential) { values[scope] = credential }
    override suspend fun delete(scope: SessionScope) { values.remove(scope) }
}
