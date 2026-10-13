package com.meetspace.app

import com.meetspace.app.ui.FoundationScreen
import com.meetspace.core.data.FoundationRepository
import com.meetspace.core.model.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FoundationViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    @Test fun registrationRequiresVerifiedServerAndBlocksMismatchedPasswords() = runTest(dispatcher) {
        val repository = FakeRepository()
        val model = FoundationViewModel(repository)
        runCurrent()
        model.navigate(FoundationScreen.REGISTER)
        assertEquals(FoundationScreen.WELCOME, model.state.value.screen)
        repository.state.value = RepositoryState(listOf(profile), profile.id, ServerVersion("test", "unknown", "unknown"))
        runCurrent()
        model.navigate(FoundationScreen.REGISTER)
        model.register("Test", "test@example.test", "synthetic-password", "different")
        runCurrent()
        assertEquals(0, repository.registerCalls)
        assertEquals(FoundationScreen.REGISTER, model.state.value.screen)
        assertTrue(model.state.value.error!!.contains("не совпадают"))
    }

    @Test fun confirmedRegistrationEntersWorkspaceOnlyAfterRealSessionResult() = runTest(dispatcher) {
        val repository = FakeRepository().apply {
            state.value = RepositoryState(listOf(profile), profile.id, ServerVersion("test", "unknown", "unknown"))
        }
        val model = FoundationViewModel(repository)
        runCurrent()
        model.navigate(FoundationScreen.REGISTER)
        model.register(" Test ", " test@example.test ", "synthetic-password", "synthetic-password")
        runCurrent()
        assertEquals(1, repository.registerCalls)
        assertEquals(FoundationScreen.WORKSPACE, model.state.value.screen)
        assertNotNull(model.state.value.repository.session)
        assertTrue(model.state.value.notice!!.contains("Аккаунт создан"))
    }

    @Test fun createdAccountWithFailedLoginRoutesToLoginWithoutRepeatingRegistration() = runTest(dispatcher) {
        val repository = FakeRepository().apply {
            state.value = RepositoryState(listOf(profile), profile.id, ServerVersion("test", "unknown", "unknown"))
            registrationResult = RegistrationResult(User("new-user", "new@example.test"), false, "Сервер временно недоступен.")
        }
        val model = FoundationViewModel(repository)
        runCurrent()
        model.navigate(FoundationScreen.REGISTER)
        model.register("Test", "new@example.test", "synthetic-password", "synthetic-password")
        runCurrent()
        assertEquals(FoundationScreen.LOGIN, model.state.value.screen)
        assertNull(model.state.value.repository.session)
        assertEquals("new@example.test", model.state.value.loginEmail)
        assertEquals(profile.id, model.state.value.loginEmailProfileId)
        assertTrue(model.state.value.notice!!.contains("Аккаунт создан"))
        assertEquals(1, repository.registerCalls)
    }

    @Test fun duplicateRegistrationKeepsRegistrationScreenWithSafeServerError() = runTest(dispatcher) {
        val repository = FakeRepository().apply {
            state.value = RepositoryState(listOf(profile), profile.id, ServerVersion("test", "unknown", "unknown"))
            registrationFailure = AppFailure(FailureKind.CONFLICT, "Аккаунт уже существует.")
        }
        val model = FoundationViewModel(repository)
        runCurrent()
        model.navigate(FoundationScreen.REGISTER)
        model.register("Test", "test@example.test", "synthetic-password", "synthetic-password")
        runCurrent()
        assertEquals(FoundationScreen.REGISTER, model.state.value.screen)
        assertEquals("Аккаунт уже существует.", model.state.value.error)
        assertNull(model.state.value.repository.session)
    }

    @Test fun expiredRestoreOpensLoginWithSafeError() = runTest(dispatcher) {
        val repository = FakeRepository().apply {
            state.value = RepositoryState(listOf(profile), profile.id, ServerVersion("test", "unknown", "unknown"))
            restoreFailure = AppFailure(FailureKind.SESSION_EXPIRED, "Войдите снова.")
        }
        val model = FoundationViewModel(repository)
        runCurrent()
        assertEquals(FoundationScreen.LOGIN, model.state.value.screen)
        assertEquals("Войдите снова.", model.state.value.error)
        assertFalse(model.state.value.busy)
    }

    @Test fun completedInstallationOpensRegistrationOnlyAfterSuccessfulServerProbe() = runTest(dispatcher) {
        val repository = FakeRepository()
        val model = FoundationViewModel(repository)
        runCurrent()
        model.navigate(FoundationScreen.CONNECT)
        model.connectForRegistration("https://example.test")
        runCurrent()
        assertEquals(1, repository.connectCalls)
        assertNotNull(model.state.value.repository.version)
        assertEquals(FoundationScreen.REGISTER, model.state.value.screen)
        assertNull(model.state.value.repository.session)
        assertEquals(0, repository.registerCalls)
    }

    @Test fun addingAccountStaysInWorkspaceAndReturnsToAccounts() = runTest(dispatcher) {
        val repository = FakeRepository().apply {
            state.value = RepositoryState(listOf(profile), profile.id, ServerVersion("test", "unknown", "unknown"), session)
        }
        val model = FoundationViewModel(repository)
        runCurrent()
        model.addAccount("https://example.test", "other@example.test", "synthetic-password", null, "")
        runCurrent()
        assertEquals(FoundationScreen.WORKSPACE, model.state.value.screen)
        assertEquals("accounts", model.state.value.accountSection)
        assertEquals(1, repository.accountAdds)
        assertNotNull(model.state.value.repository.session)
    }

    @Test fun failedAccountAdditionPreservesCurrentWorkspace() = runTest(dispatcher) {
        val repository = FakeRepository().apply {
            state.value = RepositoryState(listOf(profile), profile.id, ServerVersion("test", "unknown", "unknown"), session)
            connectFailure = AppFailure(FailureKind.INVALID_CREDENTIALS, "Неверный пароль.")
        }
        val model = FoundationViewModel(repository)
        runCurrent()
        model.addAccount("https://example.test", "other@example.test", "wrong", null, "")
        runCurrent()
        assertEquals(FoundationScreen.WORKSPACE, model.state.value.screen)
        assertEquals(session, model.state.value.repository.session)
        assertEquals(profile, model.state.value.repository.selectedProfile)
        assertEquals("Неверный пароль.", model.state.value.error)
    }

    @Test fun newlyCreatedAccountWithFailedSignInLeavesCurrentWorkspaceAndOffersLogin() = runTest(dispatcher) {
        val repository = FakeRepository().apply {
            state.value = RepositoryState(listOf(profile), profile.id, ServerVersion("test", "unknown", "unknown"), session)
            registrationResult = RegistrationResult(User("new-user", "new@example.test"), false, "Попробуйте войти.")
        }
        val model = FoundationViewModel(repository)
        runCurrent()
        model.addAccount("https://example.test", "new@example.test", "synthetic-password", "Новое имя", "synthetic-password")
        runCurrent()
        assertEquals(FoundationScreen.WORKSPACE, model.state.value.screen)
        assertEquals(session, model.state.value.repository.session)
        assertEquals("new@example.test", model.state.value.accountCreatedEmail)
    }

    @Test fun failedInstallationServerProbeNeverOpensRegistration() = runTest(dispatcher) {
        val repository = FakeRepository().apply {
            connectFailure = AppFailure(FailureKind.NETWORK, "Сервер недоступен.")
        }
        val model = FoundationViewModel(repository)
        runCurrent()
        model.navigate(FoundationScreen.CONNECT)
        model.connectForRegistration("https://example.test")
        runCurrent()
        assertEquals(FoundationScreen.CONNECT, model.state.value.screen)
        assertNull(model.state.value.repository.version)
        assertEquals("Сервер недоступен.", model.state.value.error)
        assertEquals(0, repository.registerCalls)
    }

    @Test fun installationCreateAccountPreservesExistingAuthenticatedAccount() = runTest(dispatcher) {
        val repository = FakeRepository().apply {
            state.value = RepositoryState(listOf(profile), profile.id, ServerVersion("test", "unknown", "unknown"), session = session)
        }
        val model = FoundationViewModel(repository)
        runCurrent()
        model.connectForRegistration("https://example.test")
        runCurrent()
        assertEquals(FoundationScreen.WORKSPACE, model.state.value.screen)
        assertEquals(session, model.state.value.repository.session)
        assertEquals(0, repository.registerCalls)
    }

    @Test fun repeatedTapDoesNotStartSecondLoginWhileFirstPending() = runTest(dispatcher) {
        val repository = FakeRepository().apply { loginGate = CompletableDeferred() }
        val model = FoundationViewModel(repository)
        runCurrent()
        model.login(" test@example.test ", "test-only")
        model.login("test@example.test", "test-only")
        runCurrent()
        assertEquals(1, repository.loginCalls)
        assertTrue(model.state.value.busy)
        repository.loginGate!!.complete(Unit)
        runCurrent()
        assertEquals("test@example.test", repository.loginEmail)
        assertEquals(FoundationScreen.WORKSPACE, model.state.value.screen)
    }

    @Test fun offlineLogoutClearsWorkspaceAndExplainsUnconfirmedRevocation() = runTest(dispatcher) {
        val repository = FakeRepository().apply {
            state.value = RepositoryState(listOf(profile), profile.id, ServerVersion("test", "unknown", "unknown"), session = session)
        }
        val model = FoundationViewModel(repository)
        runCurrent()
        assertEquals(FoundationScreen.WORKSPACE, model.state.value.screen)
        model.logout()
        runCurrent()
        assertNull(model.state.value.repository.session)
        assertEquals(FoundationScreen.LOGIN, model.state.value.screen)
        assertTrue(model.state.value.notice!!.contains("Сервер не подтвердил"))
    }

    @Test fun unauthenticatedNavigationCannotEnterWorkspace() = runTest(dispatcher) {
        val model = FoundationViewModel(FakeRepository())
        runCurrent()
        model.navigate(FoundationScreen.WORKSPACE)
        assertEquals(FoundationScreen.WELCOME, model.state.value.screen)
    }

    @Test fun failedThemeWriteKeepsAuthenticatedWorkspace() = runTest(dispatcher) {
        val repository = FakeRepository().apply {
            state.value = RepositoryState(listOf(profile), profile.id, ServerVersion("test", "unknown", "unknown"), session = session)
            themeFailure = AppFailure(FailureKind.STORAGE, "Не удалось сохранить настройки.")
        }
        val model = FoundationViewModel(repository)
        runCurrent()
        model.theme(ThemeMode.DARK)
        runCurrent()
        assertEquals(FoundationScreen.WORKSPACE, model.state.value.screen)
        assertNotNull(model.state.value.repository.session)
        assertNotNull(model.state.value.error)
    }

    @Test fun expiredSelectedProfileOpensLoginDirectlyAfterSuccessfulProbe() = runTest(dispatcher) {
        val repository = FakeRepository().apply { connectExpired = true }
        val model = FoundationViewModel(repository)
        runCurrent()
        model.navigate(FoundationScreen.CONNECT)
        model.connect("https://example.test")
        runCurrent()
        assertEquals(FoundationScreen.LOGIN, model.state.value.screen)
        assertNotNull(model.state.value.error)
    }

    @Test fun downstreamApiSessionExpiryLeavesWorkspaceAndShowsLogin() = runTest(dispatcher) {
        val repository = FakeRepository().apply {
            state.value = RepositoryState(listOf(profile), profile.id, ServerVersion("test", "unknown", "unknown"), session = session)
        }
        val model = FoundationViewModel(repository)
        runCurrent()
        assertEquals(FoundationScreen.WORKSPACE, model.state.value.screen)
        repository.state.value = repository.state.value.copy(session = null)
        runCurrent()
        assertEquals(FoundationScreen.LOGIN, model.state.value.screen)
        assertNull(model.state.value.repository.session)
        assertEquals("Сессия истекла. Войдите снова.", model.state.value.error)
    }

    @Test fun offlineStartupDoesNotClaimServerWasVerified() = runTest(dispatcher) {
        val repository = FakeRepository().apply {
            state.value = RepositoryState(listOf(profile), profile.id)
            restoreFailure = AppFailure(FailureKind.NETWORK, "Сервер недоступен.")
        }
        val model = FoundationViewModel(repository)
        runCurrent()
        assertEquals(FoundationScreen.CONNECT, model.state.value.screen)
        model.navigate(FoundationScreen.LOGIN)
        assertEquals(FoundationScreen.CONNECT, model.state.value.screen)
    }

    private class FakeRepository : FoundationRepository {
        override val state = MutableStateFlow(RepositoryState())
        var restoreFailure: AppFailure? = null
        var loginGate: CompletableDeferred<Unit>? = null
        var loginCalls = 0
        var themeFailure: AppFailure? = null
        var connectExpired = false
        var accountAdds = 0
        var connectFailure: AppFailure? = null
        var connectCalls = 0
        var loginEmail: String? = null
        var registerCalls = 0
        var registrationResult = RegistrationResult(session.user, true)
        var registrationFailure: AppFailure? = null
        override suspend fun restore() { restoreFailure?.let { throw it } }
        override suspend fun addServer(rawUrl: String): ServerProfile {
            connectCalls++
            connectFailure?.let { throw it }
            state.value = state.value.copy(profiles = listOf(profile), selectedProfileId = profile.id,
                version = ServerVersion("test", "unknown", "unknown"))
            if (connectExpired) {
                state.value = state.value.copy(version = ServerVersion("test", "unknown", "unknown"))
                throw AppFailure(FailureKind.SESSION_EXPIRED, "Войдите снова.")
            }
            return profile
        }
        override suspend fun addAccount(rawUrl: String, email: String, password: String, displayName: String?): RegistrationResult {
            accountAdds++
            connectFailure?.let { throw it }
            return registrationResult
        }
        override suspend fun selectServer(profileId: String) { state.value = state.value.copy(selectedProfileId = profileId) }
        override suspend fun removeServer(profileId: String) { state.value = RepositoryState() }
        override suspend fun login(email: String, password: String) {
            loginCalls++
            loginEmail = email
            loginGate?.await()
            state.value = state.value.copy(profiles = listOf(profile), selectedProfileId = profile.id, version = ServerVersion("test", "unknown", "unknown"), session = session)
        }
        override suspend fun register(displayName: String, email: String, password: String): RegistrationResult {
            registerCalls++
            registrationFailure?.let { throw it }
            if (registrationResult.signedIn) state.value = state.value.copy(session = session)
            return registrationResult
        }
        override suspend fun logout(): LogoutResult {
            state.value = state.value.copy(session = null)
            return LogoutResult(false)
        }
        override suspend fun setTheme(mode: ThemeMode) { themeFailure?.let { throw it }; state.value = state.value.copy(theme = mode) }
    }
    companion object {
        private val profile = ServerProfile("test-profile", "https://example.test", "test-user", "test@example.test")
        private val session = AccountSession(User("test-user", "test@example.test"), Capabilities(), true)
    }
}
