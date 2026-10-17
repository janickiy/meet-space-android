package com.meetspace.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meetspace.core.model.*
import com.meetspace.core.ssh.SshInputValidator
import com.meetspace.core.ssh.SshPreflightRepository
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SshWizardStep { DETAILS, VERIFY_HOST, AUTH, RESULTS }

/** Public diagnostics only. Passwords, private keys and passphrases never enter this state. */
data class SshWizardUiState(
    val step: SshWizardStep = SshWizardStep.DETAILS,
    val request: SshRequest? = null,
    val identity: SshHostIdentity? = null,
    val report: PreflightReport? = null,
    val busy: Boolean = false,
    val operation: String? = null,
    val keyLoaded: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val credentialReset: Int = 0,
)

class SshWizardViewModel(
    private val repository: SshPreflightRepository,
    private val clock: () -> Long = System::nanoTime,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SshWizardUiState())
    val state: StateFlow<SshWizardUiState> = mutableState.asStateFlow()
    private var work: Job? = null
    private var generation = 0
    private var importedKey: ByteArray? = null
    private var activeCredentials: SshCredentials? = null
    private var installationAccess: VerifiedInstallationAccess? = null
    private var accessExpiry: Job? = null
    @Volatile private var importStream: InputStream? = null

    fun probe(host: String, port: String, user: String, domain: String) {
        if (state.value.busy) return
        clearInstallationAccess()
        val request = try {
            SshInputValidator.validate(host, port, user, domain)
        } catch (failure: SshFailure) {
            mutableState.value = state.value.copy(error = failure.message, notice = null)
            return
        }
        clearKey()
        mutableState.value = state.value.copy(request = request, identity = null, report = null, keyLoaded = false)
        execute("Получение публичного ключа сервера…") {
            val identity = repository.inspectHost(request.endpoint)
            publish(state.value.copy(identity = identity, step = if (identity.trust == SshTrust.TRUSTED) SshWizardStep.AUTH else SshWizardStep.VERIFY_HOST))
        }
    }

    /** Called only after the independent fingerprint comparison checkbox is selected. */
    fun confirmTrust() {
        val identity = state.value.identity ?: return
        if (identity.trust != SshTrust.NEW || state.value.busy) return
        execute("Сохранение отпечатка…") {
            repository.trustHost(identity)
            publish(state.value.copy(identity = identity.copy(trust = SshTrust.TRUSTED), step = SshWizardStep.AUTH))
        }
    }

    /** An explicit separate danger confirmation must precede this action in the UI. */
    fun forgetTrust() {
        val request = state.value.request ?: return
        if (state.value.busy) return
        clearInstallationAccess()
        clearKey()
        execute("Удаление доверия и повторная проверка…") {
            repository.forgetHost(request.endpoint)
            val identity = repository.inspectHost(request.endpoint)
            publish(state.value.copy(identity = identity, step = SshWizardStep.VERIFY_HOST, keyLoaded = false))
        }
    }

    fun importPrivateKey(openStream: () -> InputStream?) {
        if (state.value.busy || state.value.step != SshWizardStep.AUTH) return
        clearInstallationAccess()
        clearKey()
        mutableState.value = state.value.copy(keyLoaded = false)
        execute("Чтение выбранного ключа…") {
            var loaded: ByteArray? = null
            try { withContext(Dispatchers.IO) {
                val input = openStream() ?: throw SshFailure(SshFailureKind.KEY_FORMAT, "Не удалось открыть выбранный файл ключа.")
                importStream = input
                try {
                    input.use {
                        val output = ByteArray(MAX_PRIVATE_KEY_BYTES)
                        val buffer = ByteArray(4096)
                        var total = 0
                        try {
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = it.read(buffer)
                                if (count < 0) break
                                if (total + count > MAX_PRIVATE_KEY_BYTES) {
                                    throw SshFailure(SshFailureKind.KEY_FORMAT, "Файл ключа слишком большой. Максимум — 256 КиБ.")
                                }
                                buffer.copyInto(output, total, 0, count)
                                total += count
                            }
                            if (total == 0) throw SshFailure(SshFailureKind.KEY_FORMAT, "Файл ключа пуст.")
                            loaded = output.copyOf(total)
                        } finally {
                            buffer.fill(0)
                            output.fill(0)
                        }
                    }
                } finally {
                    if (importStream === input) importStream = null
                }
            }
                currentCoroutineContext().ensureActive()
                importedKey = loaded
                loaded = null
                publish(state.value.copy(keyLoaded = true))
            } finally { loaded?.fill(0) }
        }
    }

    fun clearImportedKey() {
        clearInstallationAccess()
        clearKey()
        mutableState.value = state.value.copy(keyLoaded = false)
    }

    fun preflightPassword(password: String) {
        if (!canPreflight()) return
        if (password.isEmpty()) {
            mutableState.value = state.value.copy(error = "Введите пароль SSH.")
            return
        }
        startPreflight(SshCredentials.Password(password.toByteArray(Charsets.UTF_8)))
    }

    fun preflightKey(passphrase: String) {
        if (!canPreflight()) return
        val bytes = importedKey
        if (bytes == null) {
            mutableState.value = state.value.copy(error = "Выберите файл приватного ключа.")
            return
        }
        importedKey = null
        startPreflight(SshCredentials.PrivateKey(bytes, passphrase.toByteArray(Charsets.UTF_8)))
    }

    private fun canPreflight(): Boolean = !state.value.busy && state.value.step == SshWizardStep.AUTH && state.value.identity?.trust == SshTrust.TRUSTED

    private fun startPreflight(credentials: SshCredentials) {
        val request = state.value.request ?: run { credentials.destroy(); return }
        val identity = state.value.identity ?: run { credentials.destroy(); return }
        clearInstallationAccess()
        clearKey()
        activeCredentials = credentials
        mutableState.value = state.value.copy(keyLoaded = false, report = null, credentialReset = state.value.credentialReset + 1)
        execute("Подключение и проверка сервера без изменений…") {
            // Repository owns/destroys its copy. Only a successful trusted result may retain
            // the original for the next step, so its finally cannot erase transferred access.
            val operationCredentials = credentials.copyOwned()
            var retained = false
            try {
                val report = repository.preflight(request, operationCredentials)
                currentCoroutineContext().ensureActive()
                if (report.endpoint != request.endpoint || report.domain != request.domain || report.hostKey != identity.key) {
                    throw SshFailure(SshFailureKind.INVALID_RESPONSE, "Ответ проверки не соответствует проверенному SSH-серверу.")
                }
                if (!report.hasBlockers) {
                    val access = VerifiedInstallationAccess(request, identity.key, credentials, clock(), clock)
                    installationAccess = access
                    retained = true
                    accessExpiry = viewModelScope.launch {
                        delay(VerifiedInstallationAccess.TTL_MILLIS)
                        if (installationAccess === access) clearInstallationAccess()
                    }
                }
                publish(state.value.copy(report = report, step = SshWizardStep.RESULTS))
            } finally {
                operationCredentials.destroy()
                if (!retained) credentials.destroy()
                if (activeCredentials === credentials) activeCredentials = null
            }
        }
    }

    /** Ownership moves once; a cancelled/changed/expired check never supplies access. */
    fun takeInstallationAccess(): VerifiedInstallationAccess? {
        if (state.value.busy || state.value.step != SshWizardStep.RESULTS || state.value.report?.hasBlockers != false ||
            state.value.identity?.trust != SshTrust.TRUSTED) {
            clearInstallationAccess()
            return null
        }
        val access = installationAccess
        installationAccess = null
        accessExpiry?.cancel(); accessExpiry = null
        return access
    }

    fun cancelOperation() {
        generation++
        work?.cancel()
        work = null
        closeImportStream()
        clearKey()
        clearInstallationAccess()
        activeCredentials?.destroy()
        activeCredentials = null
        mutableState.value = state.value.copy(busy = false, operation = null, keyLoaded = false, error = null,
            notice = "Проверка отменена. SSH-соединение закрывается; данные доступа удалены из памяти операции.",
            credentialReset = state.value.credentialReset + 1)
    }

    fun back() {
        cancelOperation()
        val current = state.value
        mutableState.value = when (current.step) {
            SshWizardStep.RESULTS -> current.copy(step = SshWizardStep.AUTH, report = null, notice = null)
            SshWizardStep.AUTH, SshWizardStep.VERIFY_HOST -> current.copy(step = SshWizardStep.DETAILS, identity = null, report = null, notice = null)
            SshWizardStep.DETAILS -> current.copy(notice = null)
        }
    }

    fun reset() {
        cancelOperation()
        mutableState.value = SshWizardUiState(credentialReset = state.value.credentialReset)
    }

    fun clearMessage() { mutableState.value = state.value.copy(error = null, notice = null) }

    private fun execute(label: String, block: suspend () -> Unit) {
        val operationGeneration = ++generation
        mutableState.value = state.value.copy(busy = true, operation = label, error = null, notice = null)
        work = viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: SshFailure) {
                if (operationGeneration == generation) {
                    mutableState.value = state.value.copy(error = failure.message, keyLoaded = false,
                        credentialReset = state.value.credentialReset + 1)
                    clearKey()
                    if (failure.kind == SshFailureKind.HOST_KEY_CHANGED) {
                        // Invalidate AUTH before the follow-up handshake. Cancelling that handshake
                        // must not re-enable credentials for an identity already known to have changed.
                        mutableState.value = state.value.copy(step = SshWizardStep.DETAILS, identity = null)
                        // A key can change between TOFU and authentication. Re-probe without credentials
                        // so the changed-key screen can show both public fingerprints.
                        val identity = try { state.value.request?.let { repository.inspectHost(it.endpoint) } }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { null }
                        currentCoroutineContext().ensureActive()
                        if (operationGeneration == generation) {
                            mutableState.value = state.value.copy(identity = identity,
                                step = if (identity != null && identity.trust != SshTrust.TRUSTED) SshWizardStep.VERIFY_HOST else SshWizardStep.DETAILS)
                        }
                    }
                }
            } catch (_: Exception) {
                if (operationGeneration == generation) {
                    mutableState.value = state.value.copy(error = "Не удалось выполнить SSH-проверку. Проверьте подключение и повторите попытку.", keyLoaded = false,
                        credentialReset = state.value.credentialReset + 1)
                    clearKey()
                }
            } finally {
                if (operationGeneration == generation) mutableState.value = state.value.copy(busy = false, operation = null)
            }
        }
    }

    private suspend fun publish(next: SshWizardUiState) {
        currentCoroutineContext().ensureActive()
        mutableState.value = next
    }

    private fun clearKey() { importedKey?.fill(0); importedKey = null }
    private fun clearInstallationAccess() {
        accessExpiry?.cancel(); accessExpiry = null
        installationAccess?.destroy(); installationAccess = null
    }
    private fun closeImportStream() { try { importStream?.close() } catch (_: Exception) { }; importStream = null }
    override fun onCleared() { work?.cancel(); closeImportStream(); clearKey(); clearInstallationAccess(); activeCredentials?.destroy(); activeCredentials = null }

    class Factory(private val repository: SshPreflightRepository) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(SshWizardViewModel::class.java))
            @Suppress("UNCHECKED_CAST") return SshWizardViewModel(repository) as T
        }
    }

    companion object { const val MAX_PRIVATE_KEY_BYTES = 256 * 1024 }
}
