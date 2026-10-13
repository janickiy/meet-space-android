package com.meetspace.core.data

import com.meetspace.core.model.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Properties
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Real stage08 API/private MinIO; seeded artifacts are explicit, never provider-success mocks. */
class LiveRecordingsBackendTest {
    @Test fun `actual recording pages authorized metadata and hidden diagnostic artifacts`() = runBlocking {
        fixture { f ->
            val owner = f.actor(0); val member = f.actor(1); val outsider = f.actor(2); val revoked = f.actor(3)
            val seen = linkedSetOf<String>()
            var offset: Int? = 0
            while (offset != null) {
                val page = owner.recordings(f.cid, offset!!, 1)
                page.items.forEach { assertTrue("Real pages must not overlap", seen.add(it.id)) }
                offset = page.nextOffset
            }
            assertEquals(3, seen.size)
            assertTrue(seen.containsAll(listOf(f.id("recordingId"), f.id("secondRecordingId"), f.id("audioRecordingId"))))
            val record = member.recording(f.cid, f.id("recordingId"))
            assertTrue(record.playable); assertEquals(f.cid, record.conferenceId)
            assertTrue(record.files.any { it.id == f.id("videoFileId") && it.mimeType == "video/mp4" })
            assertTrue(record.files.none { it.type == "debug_log" || it.id == f.id("debugFileId") })
            assertEquals("final_audio", member.recording(f.cid, f.id("audioRecordingId")).media!!.type)
            assertEquals(FailureKind.FORBIDDEN, failure { outsider.recordings(f.cid) }.kind)
            assertEquals(FailureKind.FORBIDDEN, failure { revoked.recording(f.cid, f.id("recordingId")) }.kind)
            assertEquals(FailureKind.NOT_FOUND, failure { owner.recording(f.id("otherConferenceId"), f.id("recordingId")) }.kind)
        }
    }

    @Test fun `actual MP4 verified stream export and signed HTTPS ranged reads`() = runBlocking {
        fixture { f ->
            val member = f.actor(1)
            val output = ByteArrayOutputStream()
            val downloaded = member.recordingAccess(f.cid, f.id("recordingId"), f.id("videoFileId"))
                .copyTo(output)
            val bytes = output.toByteArray()
            assertEquals(f.id("mediaBytes").toLong(), downloaded)
            assertEquals(f.id("mediaSha256"), sha(bytes))
            member.recordingAccess(f.cid, f.id("recordingId"), f.id("videoFileId")).use { access ->
                access.open(8, 32).use { stream ->
                    assertEquals(32L, stream.length)
                    assertTrue("Range bytes match the actual exported MP4", bytes.copyOfRange(8, 40).contentEquals(stream.input.readBytes()))
                }
                access.open(bytes.size.toLong() - 64, 64).use { stream ->
                    assertEquals(64, stream.input.readBytes().size)
                }
            }
            val outsider = f.actor(2)
            assertEquals(FailureKind.FORBIDDEN, failure { outsider.recordingAccess(f.cid, f.id("recordingId"), f.id("videoFileId")) }.kind)
            bytes.fill(0); output.reset()
        }
    }

    @Test fun `actual historical transcript and summary remain readable with provider disabled`() = runBlocking {
        fixture { f ->
            val member = f.actor(1); val outsider = f.actor(2)
            val transcript = member.recordingTranscript(f.cid, f.id("recordingId"))
            assertFalse(transcript.enabled); assertEquals("ready", transcript.item!!.status)
            assertEquals(f.id("transcriptId"), transcript.item!!.id)
            var offset: Int? = 0
            val seen = linkedSetOf<String>()
            var lastTimestamp = -1L
            while (offset != null) {
                val page = member.recordingSegments(f.cid, f.id("recordingId"), offset!!, 100)
                assertEquals(205L, page.total)
                page.items.forEach {
                    assertTrue(seen.add(it.id)); assertTrue(it.startMs >= lastTimestamp)
                    assertTrue(it.endMs >= it.startMs); lastTimestamp = it.startMs
                }
                offset = page.nextOffset
            }
            assertEquals(205, seen.size)
            val summary = member.recordingSummary(f.cid, f.id("recordingId"))
            assertFalse(summary.enabled); assertEquals("ready", summary.item!!.status)
            assertEquals(f.id("summaryId"), summary.item!!.id); assertTrue(summary.item!!.text.isNotBlank())
            assertNull(member.recordingTranscript(f.cid, f.id("secondRecordingId")).item)
            assertEquals(FailureKind.NOT_FOUND, failure { member.recordingSegments(f.cid, f.id("secondRecordingId")) }.kind)
            assertEquals(FailureKind.FORBIDDEN, failure { outsider.recordingSummary(f.cid, f.id("recordingId")) }.kind)
        }
    }

    @Test fun `logout closes an existing actual recording capability`() = runBlocking {
        fixture { f ->
            val member = f.actor(1)
            val access = member.recordingAccess(f.cid, f.id("recordingId"), f.id("videoFileId"))
            member.logout()
            try { access.open().close(); fail("Logged-out capability must not reopen a signed stream") }
            catch (_: RecordingReadException) { /* Curated failure, never prints the signed URL. */ }
            finally { access.close() }
        }
    }
    private suspend fun failure(block: suspend () -> Any?): AppFailure = try { block(); error("Expected curated failure") } catch (e: AppFailure) { e }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private suspend fun fixture(block: suspend (Fixture) -> Unit) {
        assumeTrue("Stage08 actual backend fixture is opt-in", System.getenv("MEETSPACE_STAGE08_LIVE") == "1")
        val properties = Properties().apply { File(requireNotNull(System.getenv("MEETSPACE_STAGE08_FIXTURE"))).inputStream().use(::load) }
        val fixture = Fixture(properties)
        try { block(fixture) } finally { fixture.close(); properties.clear() }
    }
    private class Fixture(private val properties: Properties) {
        private val repositories = mutableListOf<DefaultFoundationRepository>()
        private val certificate = File(properties.getProperty("caPath")).inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate }
        private val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate).build()
        private val client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).callTimeout(20, TimeUnit.SECONDS).build()
        val cid get() = id("conferenceId")
        fun id(name: String): String = requireNotNull(properties.getProperty(name))
        suspend fun actor(index: Int): DefaultFoundationRepository {
            val origin = properties.getProperty("origin"); check(origin == "https://127.0.0.1:18488")
            return DefaultFoundationRepository(RecordingsLiveProfiles(), RecordingsLiveCredentials(), MeetSpaceApi(client)).also {
                repositories += it; it.addServer(origin); it.login(id("email$index"), id("password$index"))
            }
        }
        suspend fun close() { repositories.forEach { if (it.state.value.session != null) runCatching { it.logout() } }; client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }
}
private class RecordingsLiveProfiles : ProfileStore {
    private var settings = PersistedSettings()
    override suspend fun read() = settings
    override suspend fun write(settings: PersistedSettings) { this.settings = settings }
}
private class RecordingsLiveCredentials : CredentialStore {
    private val values = mutableMapOf<SessionScope, StoredCredential>()
    override suspend fun read(scope: SessionScope) = values[scope]
    override suspend fun write(scope: SessionScope, credential: StoredCredential) { values[scope] = credential }
    override suspend fun delete(scope: SessionScope) { values.remove(scope) }
}
