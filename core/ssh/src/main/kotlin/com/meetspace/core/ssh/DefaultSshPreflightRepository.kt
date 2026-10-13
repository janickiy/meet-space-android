package com.meetspace.core.ssh

import android.content.Context
import com.meetspace.core.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

object SshPreflightRepositoryFactory {
    fun create(context: Context): SshPreflightRepository = DefaultSshPreflightRepository(
        AndroidHostPinStore(context.applicationContext), JschSshTransport(),
        { domain -> NetworkReadinessProbe.inspect(domain) },
    )
}

internal class DefaultSshPreflightRepository(
    private val store: HostPinStore,
    private val transport: SshTransport,
    private val networkProbe: suspend (String) -> NetworkReadiness,
    private val operationTimeoutMillis: Long = 45_000,
) : SshPreflightRepository {
    private val mutex = Mutex()

    override suspend fun inspectHost(endpoint: SshEndpoint): SshHostIdentity = operation {
        val canonical = SshInputValidator.validateEndpoint(endpoint)
        val previous = store.read(canonical)
        val actual = transport.inspect(canonical)
        HostKeyCodec.validate(actual)
        SshHostIdentity(canonical, actual, when {
            previous == null -> SshTrust.NEW
            previous == actual -> SshTrust.TRUSTED
            else -> SshTrust.CHANGED
        }, previous)
    }

    override suspend fun trustHost(identity: SshHostIdentity) = operation {
        val endpoint = SshInputValidator.validateEndpoint(identity.endpoint)
        if (identity.trust != SshTrust.NEW || identity.previous != null) {
            if (identity.trust == SshTrust.CHANGED) throw changedHostFailure()
            throw SshFailure(SshFailureKind.INPUT, "Подтверждение доступно только для нового SSH-сервера.")
        }
        HostKeyCodec.validate(identity.key)
        // DataStore edit is atomic: a concurrently saved different key cannot be overwritten.
        store.trustNew(endpoint, identity.key)
    }

    override suspend fun preflight(request: SshRequest, credentials: SshCredentials): PreflightReport = try {
        operation {
            val canonical = SshInputValidator.validateRequest(request)
            val pin = store.read(canonical.endpoint)
                ?: throw SshFailure(SshFailureKind.UNTRUSTED_HOST, "Сначала проверьте и подтвердите отпечаток SSH-сервера.")
            HostKeyCodec.validate(pin)
            val stdout = transport.executeProbe(canonical, credentials, pin)
            val network = networkProbe(canonical.domain)
            PreflightAnalyzer.analyze(canonical, pin, stdout, network)
        }
    } finally { credentials.destroy() }

    override suspend fun forgetHost(endpoint: SshEndpoint) = operation {
        store.delete(SshInputValidator.validateEndpoint(endpoint))
    }

    private suspend fun <T : Any> operation(block: suspend () -> T): T {
        return try {
            // withTimeoutOrNull only consumes this deadline; caller cancellation propagates unchanged.
            withTimeoutOrNull(operationTimeoutMillis) { mutex.withLock { block() } }
                ?: throw SshFailure(SshFailureKind.TIMEOUT, "SSH-проверка не завершилась за отведённое время. Проверьте сеть и повторите подключение.")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: SshFailure) { throw failure }
        catch (_: Exception) {
            throw SshFailure(SshFailureKind.NETWORK, "Не удалось завершить SSH-проверку. Проверьте соединение и повторите попытку.")
        }
    }
}
