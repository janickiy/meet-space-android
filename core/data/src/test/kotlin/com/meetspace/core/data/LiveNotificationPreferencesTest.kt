package com.meetspace.core.data

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit opt-in against an authorized account. Saves the existing flags unchanged.
 * Credentials are supplied by environment and are never logged or persisted by this test. */
class LiveNotificationPreferencesTest {
    @Test fun currentServerLoadsSavesAndReloadsThroughTheAndroidRepository(): Unit = runBlocking {
        assumeTrue(System.getenv("MEETSPACE_NOTIFICATION_LIVE") == "1")
        val origin = requireNotNull(System.getenv("MEETSPACE_NOTIFICATION_ORIGIN"))
        require(origin.startsWith("https://") && ServerOrigin.normalize(origin) == origin)
        val repository = DefaultFoundationRepository(Profiles(), Credentials(), MeetSpaceApi())
        try {
            repository.addServer(origin)
            repository.login(requireNotNull(System.getenv("MEETSPACE_NOTIFICATION_EMAIL")),
                requireNotNull(System.getenv("MEETSPACE_NOTIFICATION_PASSWORD")))
            val before = repository.notificationPreferences()
            assertNull("Current API no longer exposes summary", before.summary)
            assertEquals("Saving unchanged preferences must work on the strict API", before, repository.saveNotificationPreferences(before))
            assertEquals("A fresh GET retains every category and delivery channel", before, repository.notificationPreferences())
            System.getenv("MEETSPACE_NOTIFICATION_EVIDENCE")?.let { path ->
                File(path).writeText("""{"origin":"$origin","get":true,"putUnchanged":true,"reload":true,"summaryAbsent":true,"preferencesUnchanged":true}""" + "\n")
            }
        } finally { repository.logout() }
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
