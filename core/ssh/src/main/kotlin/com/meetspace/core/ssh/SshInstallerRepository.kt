package com.meetspace.core.ssh

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.meetspace.core.model.*
import java.util.UUID
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*

interface SshInstallerRepository {
    suspend fun plan(endpoint: SshEndpoint, credentials: SshCredentials, options: InstallOptions, expectedHostKey: SshHostKey? = null): InstallPlan
    suspend fun apply(ticket: InstallTicket, credentials: SshCredentials, dockerConsent: Boolean, acmeConsent: Boolean, expectedHostKey: SshHostKey? = null): InstallStatus
    suspend fun control(action: String, ticket: InstallTicket, credentials: SshCredentials, expectedHostKey: SshHostKey? = null): InstallStatus
    suspend fun pending(): InstallTicket?
    suspend fun remember(ticket: InstallTicket)
    suspend fun forget()
}
private val Context.installPreferences by preferencesDataStore(name = "meetspace_install_job")

object SshInstallerRepositoryFactory {
    fun create(context: Context): SshInstallerRepository = AndroidSshInstallerRepository(context.applicationContext)
}

internal class AndroidSshInstallerRepository(private val context: Context, private val testBundle: InstallerBundle? = null) : SshInstallerRepository {
    private val pins = AndroidHostPinStore(context)
    private val transport = JschSshTransport()
    private val bundle by lazy { testBundle ?: InstallerBundle(InstallerBundle.names.associateWith { name -> context.assets.open(name).use { it.readBytes() } }) }
    private val storageKey = stringPreferencesKey("public_ticket_v1")

    private suspend fun request(endpoint: SshEndpoint, credentials: SshCredentials, action: String, data: JsonObject, expectedHostKey: SshHostKey?): JsonObject = try {
        boundedInstallerRequest(timeoutMillis = if (action == "plan") 180_000 else 60_000) {
            val valid = SshInputValidator.validate(endpoint.host, endpoint.port.toString(), endpoint.user, "example.com").endpoint
            require(valid == endpoint)
            val pin = pins.read(endpoint) ?: throw SshFailure(SshFailureKind.UNTRUSTED_HOST, "Сначала подтвердите ключ SSH-сервера.")
            requireInstallerHostKey(pin, expectedHostKey)
            val payload = withContext(Dispatchers.IO) { bundle.payload(action, data) }
            parseInstallerReply(transport.executeInstaller(endpoint, credentials, pin, payload))
        }
    } finally { credentials.destroy() }

    override suspend fun plan(endpoint: SshEndpoint, credentials: SshCredentials, options: InstallOptions, expectedHostKey: SshHostKey?): InstallPlan {
        val result = request(endpoint, credentials, "plan", buildJsonObject { put("options", buildJsonObject {
            put("domain", options.domain.trim().lowercase()); put("publicIpv4", options.publicIpv4.trim()); put("acmeEmail", options.acmeEmail.trim())
        }) }, expectedHostKey)
        return bundle.parsePlan(result)
    }
    override suspend fun apply(ticket: InstallTicket, credentials: SshCredentials, dockerConsent: Boolean, acmeConsent: Boolean, expectedHostKey: SshHostKey?): InstallStatus =
        parseInstallStatus(request(ticket.endpoint, credentials, "apply", buildJsonObject {
            put("plan", jsonObject(ticket.plan.json)); put("jobId", ticket.jobId); put("confirmedPlanHash", ticket.plan.hash)
            put("confirmDockerInstall", dockerConsent); put("acceptAcmeTerms", acmeConsent)
        }, expectedHostKey), ticket)
    override suspend fun control(action: String, ticket: InstallTicket, credentials: SshCredentials, expectedHostKey: SshHostKey?): InstallStatus {
        require(action in setOf("status", "cancel", "resume"))
        return parseInstallStatus(request(ticket.endpoint, credentials, action, buildJsonObject {
            put("jobId", ticket.jobId); put("planHash", ticket.planHash)
            if (action == "resume") put("confirmedPlanHash", ticket.planHash)
        }, expectedHostKey), ticket)
    }
    override suspend fun remember(ticket: InstallTicket) {
        require(UUID.fromString(ticket.jobId).toString() == ticket.jobId)
        val raw = buildJsonObject {
            put("host", ticket.endpoint.host); put("port", ticket.endpoint.port); put("user", ticket.endpoint.user)
            put("plan", jsonObject(ticket.plan.json)); put("jobId", ticket.jobId)
        }.toString()
        context.installPreferences.edit { it[storageKey] = raw }
    }
    override suspend fun pending(): InstallTicket? {
        val raw = context.installPreferences.data.first()[storageKey] ?: return null
        val data = jsonObject(raw)
        val endpoint = SshInputValidator.validate(data.string("host"), data.string("port"), data.string("user"), "example.com").endpoint
        return InstallTicket(endpoint, withContext(Dispatchers.IO) { bundle.parsePlan(data.getValue("plan").jsonObject) }, data.string("jobId"))
    }
    override suspend fun forget() { context.installPreferences.edit { it.remove(storageKey) } }
}

/** Compare before opening an authenticated connection; JSch also pins the actual handshake. */
internal fun requireInstallerHostKey(pin: SshHostKey, expected: SshHostKey?) {
    if (expected != null && pin != expected) {
        throw SshFailure(SshFailureKind.HOST_KEY_CHANGED, "Ключ SSH-сервера изменился после проверки. Повторите проверку сервера.")
    }
}

/** An operation deadline is a recoverable network failure; caller cancellation remains cancellation. */
internal suspend fun <T> boundedInstallerRequest(timeoutMillis: Long = 60_000, block: suspend () -> T): T = try {
    withTimeout(timeoutMillis) { block() }
} catch (_: TimeoutCancellationException) {
    currentCoroutineContext().ensureActive()
    throw SshFailure(SshFailureKind.TIMEOUT, "SSH-сервер не ответил вовремя. Состояние задания неизвестно; подключитесь снова.")
}
