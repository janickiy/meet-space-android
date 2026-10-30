package com.meetspace.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Serializable
data class ServerProfile(
    val id: String,
    /** Canonical HTTPS origin, without a trailing slash. */
    val origin: String,
    val accountId: String? = null,
    val accountEmail: String? = null,
    val accountName: String? = null,
)

@Serializable
data class User(
    val id: String,
    val email: String,
    val displayName: String? = null,
    val isAdmin: Boolean = false,
    val createdAt: String? = null,
    val avatarVersion: String? = null,
) {
    val displayLabel: String get() = displayName?.takeIf(String::isNotBlank) ?: email
}

data class ServerVersion(val version: String, val commit: String, val buildTime: String)

data class Capabilities(
    val liveCaptions: Boolean = false,
    val transcription: Boolean = false,
    val aiSummary: Boolean = false,
    val semanticSearch: Boolean = false,
    val meetingAnalytics: Boolean = false,
    val recordingModes: List<String> = emptyList(),
    val buildVersion: String = "",
)

data class AccountSession(
    val user: User,
    val capabilities: Capabilities,
    val supportsPersistentSession: Boolean,
    val guestMeeting: Meeting? = null,
)

data class RepositoryState(
    val profiles: List<ServerProfile> = emptyList(),
    val selectedProfileId: String? = null,
    val version: ServerVersion? = null,
    val session: AccountSession? = null,
    val theme: ThemeMode = ThemeMode.SYSTEM,
) {
    val selectedProfile: ServerProfile? get() = profiles.firstOrNull { it.id == selectedProfileId }
}

data class LogoutResult(val remoteConfirmed: Boolean)

enum class FailureKind {
    INVALID_URL, NETWORK, TIMEOUT, TLS, INVALID_RESPONSE, INCOMPATIBLE_SERVER,
    INVALID_CREDENTIALS, SESSION_EXPIRED, FORBIDDEN, RATE_LIMITED, UNAVAILABLE, STORAGE,
    VALIDATION, NOT_FOUND, CONFLICT,
}

/** Only curated, user-safe text reaches the UI; raw HTTP responses are never exposed. */
class AppFailure(val kind: FailureKind, override val message: String) : Exception(message)
