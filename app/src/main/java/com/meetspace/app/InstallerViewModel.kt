package com.meetspace.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meetspace.core.model.*
import com.meetspace.core.ssh.SshInstallerRepository
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class InstallScreen { ACCESS, PLAN, PROGRESS }
data class InstallerUiState(
    val active: Boolean = false, val screen: InstallScreen = InstallScreen.ACCESS,
    val endpoint: SshEndpoint? = null, val options: InstallOptions = InstallOptions("", "", ""),
    val plan: InstallPlan? = null, val ticket: InstallTicket? = null, val pending: InstallTicket? = null,
    val status: InstallStatus? = null, val busy: Boolean = false, val observing: Boolean = false,
    val keyLoaded: Boolean = false, val credentialReset: Int = 0, val error: String? = null,
    val errorCode: String? = null, val needsAccess: Boolean = true,
)

class InstallerViewModel(private val repository: SshInstallerRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(InstallerUiState())
    val state: StateFlow<InstallerUiState> = mutableState.asStateFlow()
    private var credentials: SshCredentials? = null
    private var importedKey: ByteArray? = null
    private var operation: Job? = null
    private var poll: Job? = null
    private var generation = 0
    private var observationGeneration = 0
    private var foreground = false
    @Volatile private var input: InputStream? = null
    init { viewModelScope.launch {
        try { mutableState.update { it.copy(pending = repository.pending()) } }
        catch (_: Exception) { mutableState.update { it.copy(error = "Не удалось прочитать сохранённую установку.") } }
    } }

    fun start(endpoint: SshEndpoint, domain: String) {
        if (state.value.pending != null) { reconnect(); return }
        detach()
        mutableState.value = InstallerUiState(active = true, endpoint = endpoint,
            options = InstallOptions(domain, endpoint.host.takeIf { it.matches(Regex("[0-9.]+")) } ?: "", ""),
            pending = state.value.pending, credentialReset = state.value.credentialReset)
    }
    fun reconnect() {
        val ticket = state.value.pending ?: state.value.ticket
        if (ticket == null) {
            detach()
            mutableState.update { it.copy(active = true, screen = InstallScreen.ACCESS, plan = null, status = null, error = null, errorCode = null) }
            return
        }
        detach()
        mutableState.value = InstallerUiState(active = true, endpoint = ticket.endpoint, options = ticket.plan.options,
            plan = ticket.plan, ticket = ticket, pending = ticket, credentialReset = state.value.credentialReset)
    }
    fun importKey(open: () -> InputStream?) {
        if (state.value.busy) return
        importedKey?.fill(0); importedKey = null
        execute {
            var result: ByteArray? = null
            try {
                withContext(Dispatchers.IO) {
                    val stream = open() ?: throw InstallFailure("KEY_READ")
                    input = stream
                    try { stream.use {
                        val buffer = ByteArray(256 * 1024 + 1)
                        try {
                            var total = 0
                            while (total < buffer.size) { ensureActive(); val n = it.read(buffer, total, buffer.size - total); if (n < 0) break; total += n }
                            if (total !in 32..256 * 1024) throw InstallFailure("KEY_SIZE")
                            result = buffer.copyOf(total)
                        } finally { buffer.fill(0) }
                    } } finally { input = null }
                }
                currentCoroutineContext().ensureActive()
                importedKey = result; result = null
                mutableState.update { it.copy(keyLoaded = true) }
            } finally { result?.fill(0) }
        }
    }
    fun authenticate(options: InstallOptions, password: String, useKey: Boolean) {
        if (state.value.busy) return
        val endpoint = state.value.endpoint ?: return
        val access = if (useKey) {
            val key = importedKey ?: run { mutableState.update { it.copy(error = "Выберите приватный ключ.") }; return }
            importedKey = null; SshCredentials.PrivateKey(key, password.toByteArray())
        } else {
            if (password.isEmpty()) { mutableState.update { it.copy(error = "Введите пароль SSH.") }; return }
            SshCredentials.Password(password.toByteArray())
        }
        credentials?.destroy(); credentials = access
        mutableState.update { it.copy(options = if (it.ticket == null) options else it.options, keyLoaded = false,
            credentialReset = it.credentialReset + 1, needsAccess = false) }
        execute {
            val ticket = state.value.ticket
            if (ticket == null) {
                val plan = repository.plan(endpoint, copyAccess(), options)
                currentCoroutineContext().ensureActive()
                if (plan.existingJobId != null) {
                    val existing = InstallTicket(endpoint, plan, requireNotNull(plan.existingJobId))
                    repository.remember(existing)
                    mutableState.update { it.copy(plan = plan, ticket = existing, pending = existing, screen = InstallScreen.PROGRESS) }
                    updateStatus(repository.control("status", existing, copyAccess()))
                } else mutableState.update { it.copy(plan = plan, screen = InstallScreen.PLAN) }
            } else {
                mutableState.update { it.copy(screen = InstallScreen.PROGRESS) }
                try { updateStatus(repository.control("status", ticket, copyAccess())) }
                catch (failure: InstallFailure) {
                    if (failure.code != "STATE_UNAVAILABLE") throw failure
                    mutableState.update { it.copy(errorCode = failure.code, error = installErrorMessage(failure.code), needsAccess = false) }
                }
            }
            observe()
        }
    }
    /** The same saved job ID is reused after an ambiguous SSH response. Never auto-apply. */
    fun apply(confirmed: Boolean, dockerConsent: Boolean, acmeConsent: Boolean) {
        if (state.value.busy || state.value.needsAccess || !confirmed || !acmeConsent) return
        val plan = state.value.plan ?: return
        if (plan.installDocker && !dockerConsent) return
        if (System.currentTimeMillis() / 1000 > plan.expiresAt) {
            clearAccess(); mutableState.update { it.copy(error = "План истёк. Закройте его и получите новый план.", needsAccess = true) }; return
        }
        val ticket = state.value.ticket ?: InstallTicket(state.value.endpoint ?: return, plan, UUID.randomUUID().toString())
        execute {
            repository.remember(ticket) // Must precede any remote mutation, including a lost apply response.
            mutableState.update { it.copy(ticket = ticket, pending = ticket, screen = InstallScreen.PROGRESS) }
            updateStatus(repository.apply(ticket, copyAccess(), dockerConsent, acmeConsent))
            observe()
        }
    }
    /** Read-only reconciliation after a saved apply ticket was never acknowledged by the server. */
    fun refreshMissingPlan() {
        if (state.value.busy || state.value.needsAccess || state.value.errorCode != "STATE_UNAVAILABLE") return
        val previous = state.value.ticket ?: return
        execute {
            val plan = repository.plan(previous.endpoint, copyAccess(), previous.plan.options)
            val existingId = plan.existingJobId
            if (existingId == null) {
                // A fresh server plan has ruled out a managed or unknown installation.
                repository.forget()
                mutableState.update { it.copy(plan = plan, ticket = null, pending = null, status = null, screen = InstallScreen.PLAN) }
            } else {
                val ticket = InstallTicket(previous.endpoint, plan, existingId)
                repository.remember(ticket)
                mutableState.update { it.copy(plan = plan, ticket = ticket, pending = ticket) }
                updateStatus(repository.control("status", ticket, copyAccess()))
                observe()
            }
        }
    }
    fun control(action: String) {
        if (state.value.busy || state.value.needsAccess || action !in setOf("cancel", "resume")) return
        val ticket = state.value.ticket ?: return
        execute {
            updateStatus(repository.control(action, ticket, copyAccess()))
            observe()
        }
    }
    /** Pause only local observation. The remote job and rotation-retained access are unchanged. */
    fun foreground(active: Boolean) {
        if (foreground == active) return
        foreground = active
        if (active) observe() else stopObservation()
    }
    private fun stopObservation() {
        observationGeneration++
        poll?.cancel(); poll = null
        mutableState.update { it.copy(observing = false) }
    }
    private fun observe() {
        val ticket = state.value.ticket ?: return
        if (!foreground || !state.value.active || state.value.busy || state.value.needsAccess ||
            credentials == null || state.value.status?.terminal != false || poll?.isActive == true) return
        val current = ++observationGeneration
        mutableState.update { it.copy(observing = true) }
        val observer = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                while (isActive && foreground && state.value.ticket == ticket && state.value.status?.terminal == false) {
                    delay(3_000)
                    val status = repository.control("status", ticket, copyAccess())
                    ensureActive()
                    if (current != observationGeneration || !foreground || state.value.ticket != ticket) return@launch
                    updateStatus(status)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (current == observationGeneration) failure(e) }
            finally { if (current == observationGeneration) {
                poll = null
                mutableState.update { it.copy(observing = false) }
            } }
        }
        poll = observer
        observer.start()
    }
    private fun updateStatus(status: InstallStatus) {
        mutableState.update { it.copy(status = status, screen = InstallScreen.PROGRESS, error = null, errorCode = null) }
        if (status.state == "completed") { clearAccess(); mutableState.update { it.copy(needsAccess = true) } }
    }
    private fun execute(block: suspend () -> Unit) {
        if (state.value.busy) return
        stopObservation()
        val current = ++generation
        mutableState.update { it.copy(busy = true, error = null, errorCode = null) }
        operation = viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (current == generation) failure(e) }
            finally { if (current == generation) {
                mutableState.update { it.copy(busy = false) }
                observe()
            } }
        }
    }
    private fun failure(error: Exception) {
        clearAccess()
        mutableState.update { it.copy(needsAccess = true, observing = false, keyLoaded = false,
            credentialReset = it.credentialReset + 1, errorCode = (error as? InstallFailure)?.code,
            error = when (error) {
                is SshFailure -> error.message
                is InstallFailure -> installErrorMessage(error.code)
                else -> "Связь прервана. Состояние сервера неизвестно. Подключитесь снова для проверки."
            }) }
    }
    fun detach() {
        generation++; operation?.cancel(); operation = null
        stopObservation()
        try { input?.close() } catch (_: Exception) { }; input = null
        clearAccess()
        mutableState.update { it.copy(active = false, busy = false, observing = false, needsAccess = true, keyLoaded = false, credentialReset = it.credentialReset + 1) }
    }
    fun forgetCompleted() {
        if (state.value.status?.state != "completed") return
        execute { repository.forget(); mutableState.update { it.copy(pending = null) }; detach() }
    }
    private fun copyAccess(): SshCredentials = when (val current = credentials ?: throw InstallFailure("SSH_ACCESS_REQUIRED")) {
        is SshCredentials.Password -> SshCredentials.Password(current.password.copyOf())
        is SshCredentials.PrivateKey -> SshCredentials.PrivateKey(current.key.copyOf(), current.passphrase.copyOf())
    }
    private fun clearAccess() { credentials?.destroy(); credentials = null; importedKey?.fill(0); importedKey = null }
    override fun onCleared() { detach() }
    class Factory(private val repository: SshInstallerRepository) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(InstallerViewModel::class.java))
            @Suppress("UNCHECKED_CAST") return InstallerViewModel(repository) as T
        }
    }
}

fun installErrorMessage(code: String): String = when (code) {
    "DISK_SPACE" -> "Недостаточно места. Для первой сборки нужно 30 ГиБ свободного диска."
    "DNS_FAILED", "DNS_MISMATCH", "PUBLIC_DOMAIN_REQUIRED" -> "Проверьте DNS: домен должен указывать только на публичный IPv4 этого VPS, без CDN и AAAA-записи."
    "PORT_OCCUPIED" -> "Необходимые порты заняты. Выберите новый выделенный VPS."
    "EXISTING_INSTALLATION" -> "Обнаружена другая установка или данные. Автоматическая перезапись запрещена."
    "RESOURCES_INSUFFICIENT" -> "Для сборки нужны минимум 4 CPU и 8 ГиБ RAM."
    "UNSUPPORTED_PLATFORM", "SYSTEMD_REQUIRED" -> "Поддерживается Ubuntu 24.04 с systemd, amd64 или arm64."
    "PLAN_EXPIRED", "PLAN_CHANGED", "PLAN_INVALID" -> "План устарел или изменился. Получите и проверьте новый план."
    "STATE_UNAVAILABLE" -> "Сервер не нашёл сохранённое задание. Запуск мог не состояться; повтор разрешён только вручную с тем же номером."
    "RELEASE_INVALID", "RELEASE_SIGNATURE", "RELEASE_CHECKSUM" -> "Подпись или контрольная сумма установщика не совпала. Запуск заблокирован."
    "ACME_FAILED", "HTTPS_READINESS" -> "Не удалось подтвердить HTTPS. Проверьте DNS и доступность портов 80/443, затем подключитесь и продолжите установку."
    else -> "Установка требует внимания ($code). Подключитесь снова и проверьте состояние. Данные автоматически не удаляются."
}
