package com.meetspace.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meetspace.app.ui.FoundationScreen
import com.meetspace.app.ui.FoundationUiState
import com.meetspace.core.data.FoundationRepository
import com.meetspace.core.model.AppFailure
import com.meetspace.core.model.FailureKind
import com.meetspace.core.model.ThemeMode
import com.meetspace.core.model.RepositoryState
import com.meetspace.core.model.RegistrationValidation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class FoundationViewModel(private val repository: FoundationRepository,
    private val beforeIdentityChange: suspend () -> String? = { null }) : ViewModel() {
    private val mutableState = MutableStateFlow(FoundationUiState(busy = true))
    val state = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.state.collect { snapshot -> mutableState.update {
                val expired = it.screen == FoundationScreen.WORKSPACE && it.repository.session != null && snapshot.session == null
                it.copy(repository = snapshot,
                    screen = if (expired) destination(snapshot) else it.screen,
                    error = if (expired && !it.busy) "Сессия истекла. Войдите снова." else it.error,
                )
            } }
        }
        runOperation(initial = true) {
            repository.restore()
            routeFromSession()
        }
    }

    fun connect(url: String) = connect(url, registration = false)

    fun connectForRegistration(url: String) = connect(url, registration = true)

    private fun connect(url: String, registration: Boolean) = identityOperation {
        repository.addServer(url)
        val snapshot = repository.state.value
        if (registration && snapshot.session == null && snapshot.selectedProfile != null && snapshot.version != null) {
            mutableState.update { it.copy(repository = snapshot, screen = FoundationScreen.REGISTER) }
        } else routeFromSession()
    }

    fun addAccount(url: String, email: String, password: String, name: String?, confirmation: String) = identityOperation {
        name?.let { RegistrationValidation.error(it, email, password, confirmation)?.let { failure ->
            throw AppFailure(FailureKind.VALIDATION, failure.message)
        } }
        mutableState.update { it.copy(accountCreatedEmail = null) }
        val result = repository.addAccount(url, email, password, name)
        if (result.signedIn) {
            routeFromSession()
            mutableState.update { it.copy(accountSection = "accounts", notice = "Аккаунт добавлен и выбран.") }
        } else {
            mutableState.update { it.copy(accountCreatedEmail = result.user.email,
                notice = "Аккаунт создан. Войдите с указанными email и паролем. ${result.signInFailure.orEmpty()}".trim()) }
        }
    }

    fun selectProfile(id: String) = identityOperation {
        repository.selectServer(id)
        routeFromSession()
        mutableState.update { it.copy(accountSection = "accounts") }
    }

    fun removeProfile(id: String) = identityOperation {
        repository.removeServer(id)
        routeFromSession()
    }

    fun deleteAccount(password: String) = identityOperation {
        repository.deleteAccount(password)
        routeFromSession()
        if (repository.state.value.session == null && repository.state.value.selectedProfile != null) {
            mutableState.update { it.copy(notice = "Аккаунт удалён. Войдите в другой сохранённый аккаунт.") }
        }
    }

    fun login(email: String, password: String) = identityOperation {
        repository.login(email.trim(), password)
        routeFromSession()
    }

    fun register(name: String, email: String, password: String, confirmation: String) = identityOperation {
        RegistrationValidation.error(name, email, password, confirmation)?.let {
            throw AppFailure(FailureKind.VALIDATION, it.message)
        }
        val profileId = repository.state.value.selectedProfileId
        val result = repository.register(name.trim(), email.trim(), password)
        if (result.signedIn) {
            routeFromSession()
            mutableState.update { it.copy(notice = "Аккаунт создан. Добро пожаловать в MeetSpace!") }
        } else {
            mutableState.update { it.copy(
                repository = repository.state.value, screen = FoundationScreen.LOGIN,
                loginEmail = result.user.email, loginEmailProfileId = profileId,
                notice = "Аккаунт создан. Войдите с указанными email и паролем. ${result.signInFailure.orEmpty()}".trim(),
            ) }
        }
    }

    fun logout() = identityOperation {
        val result = repository.logout()
        routeFromSession()
        if (!result.remoteConfirmed) {
            mutableState.update { it.copy(notice = "Вы вышли на этом устройстве. Сервер не подтвердил завершение сессии — проверьте соединение.") }
        }
    }

    fun theme(mode: ThemeMode) = runOperation { repository.setTheme(mode) }

    fun navigate(screen: FoundationScreen) {
        if (mutableState.value.busy) return
        if (screen == FoundationScreen.WORKSPACE && repository.state.value.session == null) return
        if (screen in setOf(FoundationScreen.LOGIN, FoundationScreen.REGISTER) &&
            (repository.state.value.selectedProfile == null || repository.state.value.version == null)) return
        if (screen == FoundationScreen.REGISTER && repository.state.value.session != null) return
        mutableState.update { it.copy(screen = screen, error = null, notice = null) }
    }

    fun clearMessage() { mutableState.update { it.copy(error = null, notice = null) } }

    private fun identityOperation(block: suspend () -> Unit) = runOperation {
        val warning = beforeIdentityChange()
        try { block() } finally {
            if (warning != null) mutableState.update { it.copy(notice = listOfNotNull(it.notice, warning).joinToString("\n")) }
        }
    }

    private fun routeFromSession() {
        val snapshot = repository.state.value
        val destination = destination(snapshot)
        mutableState.update { it.copy(repository = snapshot, screen = destination) }
    }

    private fun destination(snapshot: RepositoryState) = when {
        snapshot.session != null -> FoundationScreen.WORKSPACE
        snapshot.selectedProfile == null -> FoundationScreen.WELCOME
        snapshot.version == null -> FoundationScreen.CONNECT
        else -> FoundationScreen.LOGIN
    }

    private fun runOperation(initial: Boolean = false, block: suspend () -> Unit) {
        if (!initial && mutableState.value.busy) return
        val previousProfileId = repository.state.value.selectedProfileId
        mutableState.update { it.copy(busy = true, error = null, notice = null) }
        viewModelScope.launch {
            try {
                block()
            } catch (failure: AppFailure) {
                // Refresh may expire a session or select a different profile before failing.
                val snapshot = repository.state.value
                mutableState.update {
                    it.copy(
                        repository = snapshot,
                        screen = when {
                            snapshot.session != null && (initial || it.screen == FoundationScreen.WORKSPACE) -> FoundationScreen.WORKSPACE
                            snapshot.session == null && (initial || it.screen == FoundationScreen.WORKSPACE ||
                                (snapshot.selectedProfile != null && snapshot.version != null &&
                                    (snapshot.selectedProfileId != previousProfileId || failure.kind in setOf(
                                        FailureKind.SESSION_EXPIRED, FailureKind.STORAGE, FailureKind.INVALID_RESPONSE)))) ->
                                destination(snapshot)
                            else -> it.screen
                        },
                        error = failure.message,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(error = "Не удалось завершить действие. Попробуйте ещё раз.") }
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    class Factory(private val repository: FoundationRepository,
        private val beforeIdentityChange: suspend () -> String? = { null }) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(FoundationViewModel::class.java))
            return FoundationViewModel(repository, beforeIdentityChange) as T
        }
    }
}
