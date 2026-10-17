package com.meetspace.core.data

import android.content.Context
import com.meetspace.core.model.LogoutResult
import com.meetspace.core.model.RepositoryState
import com.meetspace.core.model.ServerProfile
import com.meetspace.core.model.ThemeMode
import com.meetspace.core.model.RegistrationResult
import com.meetspace.core.model.AppFailure
import com.meetspace.core.model.FailureKind
import kotlinx.coroutines.flow.StateFlow

interface FoundationRepository {
    val state: StateFlow<RepositoryState>
    suspend fun addServer(rawUrl: String): ServerProfile
    suspend fun addAccount(rawUrl: String, email: String, password: String, displayName: String? = null): RegistrationResult =
        throw AppFailure(FailureKind.INCOMPATIBLE_SERVER, "Добавление аккаунта недоступно для этого подключения.")
    suspend fun selectServer(profileId: String)
    suspend fun removeServer(profileId: String)
    suspend fun deleteAccount(password: String) {
        throw AppFailure(FailureKind.INCOMPATIBLE_SERVER, "Удаление аккаунта недоступно для этого подключения.")
    }
    suspend fun joinGuest(invitation: String, displayName: String): Unit =
        throw AppFailure(FailureKind.INCOMPATIBLE_SERVER, "Гостевой вход недоступен на этом сервере.")
    suspend fun refreshGuestMeeting(): Unit = Unit
    suspend fun login(email: String, password: String)
    suspend fun register(displayName: String, email: String, password: String): RegistrationResult =
        throw AppFailure(FailureKind.INCOMPATIBLE_SERVER, "Регистрация недоступна для этого подключения.")
    suspend fun restore()
    suspend fun logout(): LogoutResult
    suspend fun setTheme(mode: ThemeMode)
}

object FoundationRepositoryFactory {
    fun create(context: Context): FoundationRepository {
        val application = context.applicationContext
        return DefaultFoundationRepository(
            preferences = AndroidProfileStore(application),
            credentials = KeystoreCredentialStore(application),
            api = MeetSpaceApi(),
        )
    }
}
