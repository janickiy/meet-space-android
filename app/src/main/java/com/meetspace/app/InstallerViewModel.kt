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
    private var expectedHostKey: SshHostKey? = null
    private var accessExpiry: Job? = null
    private var importedKey: ByteArray? = null
    private var operation: Job? = null
    private var poll: Job? = null
    private var generation = 0
    private var observationGeneration = 0
    private var foreground = false
    @Volatile private var input: InputStream? = null
    private val pendingLoad = viewModelScope.launch {
        try { mutableState.update { it.copy(pending = repository.pending()) } }
        catch (_: Exception) { mutableState.update { it.copy(error = "Не удалось прочитать сохранённую установку.") } }
    }

    fun start(endpoint: SshEndpoint, domain: String, access: VerifiedInstallationAccess? = null) {
        // Ignore a second tap from the outgoing wizard while its composition is replaced.
        // It must not detach the installation or erase the access just transferred to it.
        if (state.value.active) { access?.destroy(); return }
        val verified = access?.take(SshRequest(endpoint, domain))
        if (state.value.pending != null) {
            reconnect()
            val pending = state.value.pending
            if (verified != null && pending?.endpoint == endpoint && pending.plan.options.domain == domain) {
                acceptVerifiedAccess(verified)
                prepare(state.value.options)
            } else verified?.credentials?.destroy()
            return
        }
        detach()
        mutableState.value = InstallerUiState(active = true, endpoint = endpoint,
            options = InstallOptions(domain, endpoint.host.takeIf { it.matches(Regex("[0-9.]+")) } ?: "", ""),
            pending = state.value.pending, credentialReset = state.value.credentialReset)
        if (verified != null) {
            acceptVerifiedAccess(verified)
            // Host names do not prove a public IPv4. Ask only for that installation
            // option while retaining the already verified SSH access in memory.
            if (state.value.options.publicIpv4.isNotEmpty()) prepare(state.value.options)
            else mutableState.update { it.copy(screen = InstallScreen.ACCESS) }
        }
    }

    private fun acceptVerifiedAccess(access: VerifiedInstallationAccess.Access) {
        credentials = access.credentials
        expectedHostKey = access.hostKey
        mutableState.update { it.copy(needsAccess = false, screen = if (it.ticket == null) InstallScreen.PLAN else InstallScreen.PROGRESS,
            credentialReset = it.credentialReset + 1) }
        accessExpiry = viewModelScope.launch {
            delay(access.remainingMillis)
            if (credentials === access.credentials && state.value.screen == InstallScreen.ACCESS && !state.value.busy) {
                clearAccess()
                mutableState.update { it.copy(needsAccess = true, credentialReset = it.credentialReset + 1,
                    error = "Срок хранения SSH-доступа истёк. Повторите проверку сервера или введите данные доступа снова.") }
            }
        }
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
        clearAccess()
        mutableState.update { it.copy(needsAccess = true, keyLoaded = false) }
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
                    } } finally { if (input === stream) input = null }
                }
                currentCoroutineContext().ensureActive()
                importedKey = result; result = null
                mutableState.update { it.copy(keyLoaded = true) }
            } finally { result?.fill(0) }
        }
    }
    fun authenticate(options: InstallOptions, password: String, useKey: Boolean) {
        if (state.value.busy) return
        if (state.value.endpoint == null) return
        if (!state.value.needsAccess && credentials != null && state.value.ticket == null) {
            if (options.domain != state.value.options.domain) {
                failure(SshFailure(SshFailureKind.INPUT, "Домен изменился. Повторите проверку сервера для нового домена."))
                return
            }
            mutableState.update { it.copy(options = options, screen = InstallScreen.PLAN) }
            prepare(options)
            return
        }
        val access = if (useKey) {
            val key = importedKey ?: run { mutableState.update { it.copy(error = "Выберите приватный ключ.") }; return }
            importedKey = null; SshCredentials.PrivateKey(key, password.toByteArray())
        } else {
            if (password.isEmpty()) { mutableState.update { it.copy(error = "Введите пароль SSH.") }; return }
            SshCredentials.Password(password.toByteArray())
        }
        clearAccess(); credentials = access
        mutableState.update { it.copy(options = if (it.ticket == null) options else it.options, keyLoaded = false,
            credentialReset = it.credentialReset + 1, needsAccess = false) }
        prepare(options)
    }
    private fun prepare(options: InstallOptions) {
        val endpoint = state.value.endpoint ?: return
        execute {
            // pending() can still be loading when the user finishes a fast preflight.
            // Never use newly transferred access for a saved job on another server/account.
            pendingLoad.join()
            currentCoroutineContext().ensureActive()
            val pending = state.value.pending
            if (state.value.ticket == null && pending != null) {
                if (pending.endpoint != endpoint || pending.plan.options.domain != options.domain) {
                    clearAccess()
                    mutableState.update { it.copy(endpoint = pending.endpoint, options = pending.plan.options,
                        ticket = pending, plan = pending.plan, needsAccess = true, screen = InstallScreen.ACCESS) }
                    return@execute
                }
                mutableState.update { it.copy(ticket = pending, plan = pending.plan, options = pending.plan.options) }
            }
            val ticket = state.value.ticket
            if (ticket == null) {
                val plan = withAccess { repository.plan(endpoint, it, options, expectedHostKey) }
                currentCoroutineContext().ensureActive()
                if (plan.existingJobId != null) {
                    val existing = InstallTicket(endpoint, plan, requireNotNull(plan.existingJobId))
                    repository.remember(existing)
                    currentCoroutineContext().ensureActive()
                    mutableState.update { it.copy(plan = plan, ticket = existing, pending = existing, screen = InstallScreen.PROGRESS) }
                    updateStatus(controlRequest("status", existing))
                } else mutableState.update { it.copy(plan = plan, screen = InstallScreen.PLAN) }
            } else {
                mutableState.update { it.copy(screen = InstallScreen.PROGRESS) }
                try { updateStatus(controlRequest("status", ticket)) }
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
            currentCoroutineContext().ensureActive()
            mutableState.update { it.copy(ticket = ticket, pending = ticket, screen = InstallScreen.PROGRESS) }
            val status = withAccess { repository.apply(ticket, it, dockerConsent, acmeConsent, expectedHostKey) }
            currentCoroutineContext().ensureActive()
            updateStatus(status)
            observe()
        }
    }
    /** Read-only reconciliation after a saved apply ticket was never acknowledged by the server. */
    fun refreshMissingPlan() {
        if (state.value.busy || state.value.needsAccess || state.value.errorCode != "STATE_UNAVAILABLE") return
        val previous = state.value.ticket ?: return
        execute {
            val plan = withAccess { repository.plan(previous.endpoint, it, previous.plan.options, expectedHostKey) }
            currentCoroutineContext().ensureActive()
            val existingId = plan.existingJobId
            if (existingId == null) {
                // A fresh server plan has ruled out a managed or unknown installation.
                repository.forget()
                currentCoroutineContext().ensureActive()
                mutableState.update { it.copy(plan = plan, ticket = null, pending = null, status = null, screen = InstallScreen.PLAN) }
            } else {
                val ticket = InstallTicket(previous.endpoint, plan, existingId)
                repository.remember(ticket)
                currentCoroutineContext().ensureActive()
                mutableState.update { it.copy(plan = plan, ticket = ticket, pending = ticket) }
                updateStatus(controlRequest("status", ticket))
                observe()
            }
        }
    }
    fun control(action: String) {
        if (state.value.busy || state.value.needsAccess || action !in setOf("cancel", "resume")) return
        val ticket = state.value.ticket ?: return
        execute {
            updateStatus(controlRequest(action, ticket))
            if (action == "cancel") { clearAccess(); mutableState.update { it.copy(needsAccess = true) } }
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
                    val status = controlRequest("status", ticket)
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
        if (status.state in setOf("completed", "cancelled", "failed")) { clearAccess(); mutableState.update { it.copy(needsAccess = true) } }
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
            screen = if (it.plan == null && it.ticket == null) InstallScreen.ACCESS else it.screen,
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
        execute { repository.forget(); currentCoroutineContext().ensureActive(); mutableState.update { it.copy(pending = null) }; detach() }
    }
    private suspend fun controlRequest(action: String, ticket: InstallTicket): InstallStatus {
        val result = withAccess { repository.control(action, ticket, it, expectedHostKey) }
        currentCoroutineContext().ensureActive()
        return result
    }
    private suspend fun <T> withAccess(block: suspend (SshCredentials) -> T): T {
        val copy = (credentials ?: throw InstallFailure("SSH_ACCESS_REQUIRED")).copyOwned()
        return try { block(copy) } finally { copy.destroy() }
    }
    private fun clearAccess() {
        accessExpiry?.cancel(); accessExpiry = null
        credentials?.destroy(); credentials = null; expectedHostKey = null; importedKey?.fill(0); importedKey = null
    }
    override fun onCleared() { detach() }
    class Factory(private val repository: SshInstallerRepository) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(InstallerViewModel::class.java))
            @Suppress("UNCHECKED_CAST") return InstallerViewModel(repository) as T
        }
    }
}

fun installErrorMessage(code: String): String = when (code) {
    "INVALID_REQUEST" -> "Сервер не смог обработать запрос установщика. Обновите Android-приложение и повторите получение плана; если ошибка сохраняется, сообщите код INVALID_REQUEST."
    "DISK_SPACE" -> "Недостаточно места: для компактной установки нужно минимум 18 ГиБ свободного диска (около 20 ГБ); рекомендуется 30 ГиБ. Освободите место или увеличьте диск VPS."
    "DNS_FAILED", "DNS_MISMATCH", "PUBLIC_DOMAIN_REQUIRED" -> "Проверьте DNS: домен должен указывать только на публичный IPv4 этого VPS, без CDN и AAAA-записи."
    "PORT_OCCUPIED" -> "Необходимые порты заняты. Выберите новый выделенный VPS."
    "EXISTING_INSTALLATION" -> "Обнаружена другая установка или данные. Автоматическая перезапись запрещена."
    "RESOURCES_INSUFFICIENT" -> "Для установки нужны минимум 2 CPU и VPS с 4 ГБ RAM (от 3 ГиБ доступно системе). Для параллельных встреч и записи рекомендуются 4 CPU и 8 ГБ RAM; доступная нагрузка зависит от участников и качества видео."
    "BUILDX_REQUIRED" -> "Не удалось подготовить Docker Buildx для сборки образов. Проверьте доступ VPS к официальному репозиторию Docker, затем подключитесь снова и продолжите установку."
    "UNSUPPORTED_PLATFORM", "SYSTEMD_REQUIRED" -> "Поддерживаются Ubuntu 22.04 и 24.04 LTS с systemd, amd64 или arm64."
    "PLAN_EXPIRED", "PLAN_CHANGED", "PLAN_INVALID" -> "План устарел или изменился. Получите и проверьте новый план."
    "STATE_UNAVAILABLE" -> "Сервер не нашёл сохранённое задание. Запуск мог не состояться; повтор разрешён только вручную с тем же номером."
    "RELEASE_INVALID", "RELEASE_SIGNATURE", "RELEASE_CHECKSUM" -> "Подпись или контрольная сумма установщика не совпала. Запуск заблокирован."
    "SOURCE_LOOKUP_FAILED", "SOURCE_RATE_LIMIT" -> "Не удалось получить актуальную версию из GitHub. Проверьте доступ сервера к GitHub и повторите получение плана."
    "SOURCE_INVALID" -> "GitHub вернул некорректные данные исходного кода. Повторите получение плана; если ошибка сохраняется, сообщите код SOURCE_INVALID."
    "SOURCE_INCOMPATIBLE", "UNPINNED_BASE_IMAGE" -> "Новая версия бэкенда несовместима с этим установщиком. Обновите Android-приложение; старая версия автоматически не устанавливается."
    "ACME_FAILED", "HTTPS_READINESS" -> "Не удалось подтвердить HTTPS. Проверьте DNS и доступность портов 80/443, затем подключитесь и продолжите установку."
    else -> "Установка требует внимания ($code). Подключитесь снова и проверьте состояние. Данные автоматически не удаляются."
}
