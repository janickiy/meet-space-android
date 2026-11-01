package com.meetspace.core.data

import com.meetspace.core.model.*
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in read-only audit through the production repository. Never changes user content,
 * read markers, meeting membership or call state. Report contains counts/errors only. */
class LiveApiCompatibilityTest {
    @Test fun publicServerReadContracts(): Unit = runBlocking {
        assumeTrue(System.getenv("MEETSPACE_API_LIVE") == "1")
        val origin = requireNotNull(System.getenv("MEETSPACE_API_ORIGIN"))
        val profiles = Profiles(); val credentials = Credentials()
        val client = System.getenv("MEETSPACE_API_CA")?.let { path ->
            require(origin.startsWith("https://127.0.0.1:"))
            val certificate = File(path).inputStream().use {
                java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(it) as java.security.cert.X509Certificate
            }
            val trust = okhttp3.tls.HandshakeCertificates.Builder().addTrustedCertificate(certificate).build()
            okhttp3.OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
        } ?: okhttp3.OkHttpClient()
        val repository = DefaultFoundationRepository(profiles, credentials, MeetSpaceApi(client))
        val results = mutableListOf<JsonObject>()
        suspend fun <T> check(label: String, operation: suspend () -> T): T? = try {
            operation().also { value -> results += buildJsonObject {
                put("check", label); put("result", "PASS")
                when (value) {
                    is Collection<*> -> put("count", value.size)
                    is MeetingPage -> put("count", value.items.size)
                    is ChatPage<*> -> put("count", value.items.size)
                    is MessagePage -> put("count", value.items.size)
                    is RecordingLibraryPage -> put("count", value.items.size)
                    is RecordingPage -> put("count", value.items.size)
                }
            } }
        } catch (error: AppFailure) {
            results += buildJsonObject { put("check", label); put("result", "FAIL"); put("kind", error.kind.name) }; null
        }
        fun unavailable(label: String) { results += buildJsonObject { put("check", label); put("result", "NO_SAMPLE") } }
        try {
            repository.addServer(origin)
            repository.login(requireNotNull(System.getenv("MEETSPACE_API_EMAIL")), requireNotNull(System.getenv("MEETSPACE_API_PASSWORD")))
            check("auth.me-capabilities") { checkNotNull(repository.state.value.session) }
            val user = checkNotNull(repository.state.value.session).user
            if (user.avatarVersion != null) check("profile.avatar") { repository.userAvatar(user.id).also { assertTrue(it.isNotEmpty()); it.fill(0) } }
            else unavailable("profile.avatar")
            check("users.search") { repository.searchPeers("тест") }
            check("notifications.preferences") { repository.notificationPreferences() }
            val notifications = check("notifications.list") { repository.notifications() }
            notifications?.nextCursor?.let { cursor -> check("notifications.cursor") { repository.notifications(cursor) } }
            check("integrations.capabilities") { repository.integrationCapabilities() }
            val active = check("calls.active") { repository.activeDirectCall() }
            if (active != null) check("calls.detail") { repository.directCall(active.id) } else unavailable("calls.detail")
            val meetings = linkedMapOf<String, Meeting>()
            for (view in MeetingsView.entries) for (scope in MeetingsScope.entries) {
                val page = check("meetings.${view.name}.${scope.name}") { repository.timeline(MeetingsQuery(view, scope, limit = 10)) }
                page?.items?.forEach { meetings[it.id] = it }
                if (scope == MeetingsScope.ALL) page?.nextCursor?.let { cursor ->
                    check("meetings.${view.name}.cursor") { repository.timeline(MeetingsQuery(view, cursor = cursor, limit = 10)) }
                }
            }
            if (meetings.isEmpty()) unavailable("meetings.detail-and-materials")
            for ((index, meeting) in meetings.values.take(4).withIndex()) {
                check("meeting.$index.detail") { repository.conference(meeting.id) }
                val membership = check("meeting.$index.membership") { repository.membership(meeting.id) }
                check("meeting.$index.participants") { repository.participants(meeting.id) }
                if (meeting.isClosed) check("meeting.$index.history") { repository.history(meeting.id) }
                if (membership?.isAdmitted == true) {
                    check("meeting.$index.chat.read-state") { repository.meetingChatReadState(meeting.id) }
                    val messages = check("meeting.$index.chat.messages") { repository.meetingMessages(meeting.id) }
                    messages?.nextCursor?.let { check("meeting.$index.chat.cursor") { repository.meetingMessages(meeting.id, it) } }
                }
                check("meeting.$index.recordings") { repository.recordings(meeting.id) }
            }
            val chats = check("chats.list") { repository.conversations() }
            for (type in listOf("direct", "group")) check("chats.filter.$type") { repository.conversations(ChatQuery(type = type)) }
            check("chats.unread") { repository.conversations(ChatQuery(unreadOnly = true)) }
            chats?.nextCursor?.let { check("chats.cursor") { repository.conversations(ChatQuery(cursor = it)) } }
            if (chats?.items.isNullOrEmpty()) unavailable("chats.detail-and-messages")
            for ((index, chat) in chats?.items.orEmpty().take(4).withIndex()) {
                check("chat.$index.detail") { repository.conversation(chat.id) }
                if (chat.type == "group") {
                    check("chat.$index.members") { repository.groupMembers(chat.id) }
                    if (chat.avatarVersion != null) check("chat.$index.avatar") { repository.groupAvatar(chat.id).also { it.fill(0) } }
                } else check("chat.$index.presence") { repository.peerPresence(chat.id) }
                val messages = check("chat.$index.messages") { repository.messages(chat.id) }
                messages?.nextCursor?.let { check("chat.$index.cursor") { repository.messages(chat.id, it) } }
                messages?.items?.firstOrNull()?.let { message -> check("chat.$index.context") { repository.messageContext(chat.id, message.id) } }
                check("chat.$index.search") { repository.searchMessages(chat.id, "тест") }
                check("chat.$index.pins") { repository.messagePins(chat.id) }
                messages?.items?.flatMap { it.attachments }?.firstOrNull { it.size < 1_000_000 && it.status == "attached" }?.let {
                    check("chat.$index.file-download") { repository.downloadAttachment(chat.id, it).also { bytes -> bytes.fill(0) } }
                }
            }
            val folders = check("folders.list") { repository.folders() }
            check("folders.candidates") { repository.folderItems() }
            for ((index, folder) in folders.orEmpty().take(3).withIndex()) {
                check("folder.$index.items") { repository.folderItems(folder.id) }
                check("folder.$index.candidates") { repository.folderItems(folder.id, FolderQuery(candidates = true)) }
            }
            val library = check("recordings.library") { repository.recordingLibrary(limit = 10) }
            library?.nextCursor?.let { check("recordings.cursor") { repository.recordingLibrary(it, 10) } }
            if (library?.items.isNullOrEmpty()) unavailable("recordings.detail-and-files")
            for ((index, item) in library?.items.orEmpty().take(3).withIndex()) {
                val recording = check("recording.$index.detail") { repository.recording(item.meeting.id, item.recording.id) } ?: continue
                val capabilities = repository.state.value.session!!.capabilities
                if (capabilities.transcription) check("recording.$index.transcript") { repository.recordingTranscript(item.meeting.id, recording.id) }
                if (capabilities.aiSummary) check("recording.$index.summary") { repository.recordingSummary(item.meeting.id, recording.id) }
                recording.files.firstOrNull { it.available && it.sizeBytes?.let { bytes -> bytes > 128 } == true }?.let { file ->
                    check("recording.$index.file-range") {
                        repository.recordingAccess(item.meeting.id, recording.id, file.id).use { access ->
                            access.open(0, 128).use { stream -> assertEquals(128, stream.input.readBytes().size) }
                        }
                    }
                }
            }
            check("events.websocket") { repository.openUserEvents().let { events ->
                try { withTimeout(5000) { events.events.receive() } } finally { events.close() }
            } }
            check("auth.refresh-restore") {
                val restored = DefaultFoundationRepository(profiles, credentials, MeetSpaceApi(client))
                try { restored.restore(); assertEquals(user.id, restored.state.value.session?.user?.id) }
                finally { restored.logout() }
            }
        } finally {
            repository.logout()
            System.getenv("MEETSPACE_API_EVIDENCE")?.let { File(it).writeText(buildJsonObject { put("origin", origin); put("checks", JsonArray(results)) }.toString()) }
        }
        assertTrue("API checks failed; inspect sanitized evidence", results.none { it["result"] == JsonPrimitive("FAIL") })
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
}
