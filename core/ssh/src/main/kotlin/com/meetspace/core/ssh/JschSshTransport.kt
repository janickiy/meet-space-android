package com.meetspace.core.ssh

import com.jcraft.jsch.*
import com.meetspace.core.model.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

internal interface SshTransport {
    suspend fun inspect(endpoint: SshEndpoint): SshHostKey
    suspend fun executeProbe(request: SshRequest, credentials: SshCredentials, pin: SshHostKey): String
}

/** One fresh session and JSch identity repository per operation. No agent, forwarding, PTY or interactive prompts. */
internal class JschSshTransport : SshTransport {
    override suspend fun inspect(endpoint: SshEndpoint): SshHostKey = blocking { operation ->
        val jsch = newJsch()
        val guard = PinnedHostKeyRepository(endpoint, expected = null, inspectionOnly = true)
        jsch.setHostKeyRepository(guard)
        val session = configuredSession(jsch, endpoint, operation)
        try {
            session.connect(CONNECT_TIMEOUT_MS)
            // Inspection is intentionally terminated by the verified host-key callback before USERAUTH.
            throw SshFailure(SshFailureKind.INVALID_RESPONSE, "Не удалось безопасно проверить ключ SSH-сервера.")
        } catch (_: InspectionComplete) {
            guard.observed ?: throw invalidHostKeyFailure()
        } catch (error: Exception) {
            throw safeFailure(error)
        }
    }

    override suspend fun executeProbe(request: SshRequest, credentials: SshCredentials, pin: SshHostKey): String =
        executeCommand(request.endpoint, credentials, pin, PreflightProbe.command(request.domain, request.endpoint.host), PreflightProbe.script.toByteArray(Charsets.UTF_8))

    internal suspend fun executeInstaller(endpoint: SshEndpoint, credentials: SshCredentials, pin: SshHostKey, payload: ByteArray): String {
        require(payload.size <= 512 * 1024)
        return executeCommand(endpoint, credentials, pin, "if [ \"$(id -u)\" = 0 ]; then exec /usr/bin/python3 -; else exec sudo -n /usr/bin/python3 -; fi", payload, 256 * 1024)
    }

    private suspend fun executeCommand(endpoint: SshEndpoint, credentials: SshCredentials, pin: SshHostKey, command: String, input: ByteArray, maximumOutput: Int = MAX_STDOUT_BYTES): String {
        // Transport owns a separate copy, including when cancellation races queued/blocking work.
        val owned = when (credentials) {
            is SshCredentials.Password -> SshCredentials.Password(credentials.password.copyOf())
            is SshCredentials.PrivateKey -> SshCredentials.PrivateKey(credentials.key.copyOf(), credentials.passphrase.copyOf())
        }
        return try {
            blocking { operation ->
                val jsch = newJsch()
                val guard = PinnedHostKeyRepository(endpoint, pin, inspectionOnly = false)
                jsch.setHostKeyRepository(guard)
                val session = configuredSession(jsch, endpoint, operation)
                var identity: InMemoryIdentity? = null
                try {
                    operation.checkActive()
                    when (owned) {
                        is SshCredentials.Password -> {
                            if (owned.password.isEmpty() || owned.password.size > 4096) throw credentialsFailure()
                            session.setConfig("PreferredAuthentications", "password")
                            session.setPassword(owned.password)
                        }
                        is SshCredentials.PrivateKey -> {
                            if (owned.key.size !in 32..MAX_PRIVATE_KEY_BYTES || owned.passphrase.size > 4096) throw keyFailure()
                            PrivateKeyPolicy.validate(owned.key)
                            identity = try { InMemoryIdentity(KeyPair.load(jsch, owned.key, null)) }
                            catch (_: Exception) { throw keyFailure() }
                            if (identity.algName !in SUPPORTED_KEY_FORMATS) throw keyFailure()
                            try { jsch.addIdentity(identity, owned.passphrase) }
                            catch (_: Exception) { throw keyFailure() }
                            if (!identity.hasSupportedSize()) throw keyFailure()
                            session.setConfig("PreferredAuthentications", "publickey")
                        }
                    }
                    session.connect(CONNECT_TIMEOUT_MS)
                    if (!guard.verified) throw SshFailure(SshFailureKind.UNTRUSTED_HOST, "Сначала подтвердите отпечаток SSH-сервера.")
                    operation.checkActive()
                    val channel = session.openChannel("exec") as ChannelExec
                    operation.attachChannel(channel)
                    channel.setPty(false)
                    channel.setAgentForwarding(false)
                    channel.setCommand(command)
                    channel.setInputStream(ByteArrayInputStream(input))
                    val output = BoundedProbeOutput(maximumOutput, operation)
                    val errors = BoundedProbeOutput(MAX_STDERR_BYTES, operation, discard = true)
                    channel.setOutputStream(output, true)
                    channel.setExtOutputStream(errors, true)
                    channel.connect(CONNECT_TIMEOUT_MS)
                    while (!channel.isClosed) {
                        operation.checkActive()
                        if (output.exceeded || errors.exceeded) throw responseLimitFailure()
                        if (!session.isConnected) throw networkFailure()
                        Thread.sleep(20)
                    }
                    if (output.exceeded || errors.exceeded) throw responseLimitFailure()
                    if (channel.exitStatus != 0) throw SshFailure(SshFailureKind.REMOTE_COMMAND, "SSH-подключение установлено, но сервер не выполнил команду. Для установки нужны Python 3 и sudo без запроса пароля.")
                    output.text()
                } catch (error: Exception) {
                    if (guard.changed) throw changedHostFailure()
                    throw safeFailure(error)
                } finally {
                    // Clear even a key that failed to decrypt before registration with JSch.
                    try { jsch.removeAllIdentity() } finally { identity?.clear(); owned.destroy() }
                }
            }
        } finally { owned.destroy() }
    }

    private fun newJsch() = JSch().apply {
        AndroidCryptoAdapters.initialize()
        setInstanceLogger(SilentLogger)
    }

    private fun configuredSession(jsch: JSch, endpoint: SshEndpoint, operation: SshOperation): Session {
        val session = jsch.getSession(endpoint.user, endpoint.host, endpoint.port)
        operation.attachSession(session)
        session.setLogger(SilentLogger)
        session.setDaemonThread(true)
        session.setSocketFactory(object : SocketFactory {
            override fun createSocket(host: String, port: Int): Socket {
                val socket = Socket()
                operation.attachSocket(socket)
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                operation.checkActive()
                socket.soTimeout = CONNECT_TIMEOUT_MS
                socket.tcpNoDelay = true
                return socket
            }
            override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()
            override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()
        })
        session.setConfig("StrictHostKeyChecking", "yes")
        session.setConfig("HashKnownHosts", "no")
        session.setConfig("MaxAuthTries", "1")
        session.setConfig("PreferredAuthentications", "none")
        session.setConfig("kex", "curve25519-sha256,curve25519-sha256@libssh.org,ecdh-sha2-nistp256,ecdh-sha2-nistp384,ecdh-sha2-nistp521,diffie-hellman-group14-sha256,diffie-hellman-group16-sha512,diffie-hellman-group18-sha512")
        session.setConfig("server_host_key", "ssh-ed25519,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521,rsa-sha2-512,rsa-sha2-256")
        session.setConfig("PubkeyAcceptedAlgorithms", "ssh-ed25519,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521,rsa-sha2-512,rsa-sha2-256")
        session.setConfig("cipher.c2s", "aes128-gcm@openssh.com,aes256-gcm@openssh.com,aes128-ctr,aes192-ctr,aes256-ctr")
        session.setConfig("cipher.s2c", "aes128-gcm@openssh.com,aes256-gcm@openssh.com,aes128-ctr,aes192-ctr,aes256-ctr")
        session.setConfig("mac.c2s", "hmac-sha2-256-etm@openssh.com,hmac-sha2-512-etm@openssh.com,hmac-sha2-256,hmac-sha2-512")
        session.setConfig("mac.s2c", "hmac-sha2-256-etm@openssh.com,hmac-sha2-512-etm@openssh.com,hmac-sha2-256,hmac-sha2-512")
        session.setConfig("compression.c2s", "none")
        session.setConfig("compression.s2c", "none")
        session.setConfig("enable_strict_kex", "yes")
        session.setConfig("require_strict_kex", "yes")
        session.setConfig("CheckCiphers", "aes128-gcm@openssh.com,aes256-gcm@openssh.com,aes128-ctr,aes192-ctr,aes256-ctr")
        // Explicit lightweight BC implementations do not replace Android's platform crypto provider.
        session.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519")
        session.setConfig("xdh", "com.jcraft.jsch.bc.XDH")
        session.timeout = CONNECT_TIMEOUT_MS
        return session
    }

    private suspend fun <T> blocking(block: (SshOperation) -> T): T = suspendCancellableCoroutine { continuation ->
        val operation = SshOperation()
        val future = executor.submit {
            try {
                operation.checkActive()
                continuation.resume(block(operation))
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            } finally { operation.close() }
        }
        continuation.invokeOnCancellation { operation.close(); future.cancel(true) }
    }

    private companion object {
        val executor = Executors.newFixedThreadPool(2) { task -> Thread(task, "MeetSpace-SSH").apply { isDaemon = true } }
        val SUPPORTED_KEY_FORMATS = setOf("ssh-ed25519", "ecdsa-sha2-nistp256", "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521", "ssh-rsa")
        const val CONNECT_TIMEOUT_MS = 15_000
        const val MAX_PRIVATE_KEY_BYTES = 256 * 1024
        const val MAX_STDOUT_BYTES = 64 * 1024
        const val MAX_STDERR_BYTES = 16 * 1024
    }
}

/** Inspection must throw from check even for a known key: returning OK would permit USERAUTH. */
internal class PinnedHostKeyRepository(
    private val endpoint: SshEndpoint,
    private val expected: SshHostKey?,
    private val inspectionOnly: Boolean,
) : HostKeyRepository {
    @Volatile var observed: SshHostKey? = null; private set
    @Volatile var verified = false; private set
    @Volatile var changed = false; private set
    private val expectedBytes = expected?.let(HostKeyCodec::validate)

    override fun check(host: String, key: ByteArray): Int {
        val expectedHost = if (endpoint.port == 22) endpoint.host else endpoint.pinId
        if (host != expectedHost) return HostKeyRepository.NOT_INCLUDED
        observed = HostKeyCodec.fromBytes(key)
        if (inspectionOnly) throw InspectionComplete()
        if (expectedBytes == null) return HostKeyRepository.NOT_INCLUDED
        if (!MessageDigest.isEqual(expectedBytes, key)) {
            changed = true
            return HostKeyRepository.CHANGED
        }
        verified = true
        return HostKeyRepository.OK
    }
    override fun add(hostkey: HostKey?, ui: UserInfo?) { throw IllegalStateException("Host pin is read-only") }
    override fun remove(host: String?, type: String?) { throw IllegalStateException("Host pin is read-only") }
    override fun remove(host: String?, type: String?, key: ByteArray?) { throw IllegalStateException("Host pin is read-only") }
    override fun getKnownHostsRepositoryID() = "MeetSpace exact host and port pin"
    override fun getHostKey(): Array<HostKey> = expectedBytes?.let { arrayOf(HostKey(endpoint.pinId, it)) } ?: emptyArray()
    override fun getHostKey(host: String?, type: String?): Array<HostKey> {
        val expectedHost = if (endpoint.port == 22) endpoint.host else endpoint.pinId
        if (host != null && host != expectedHost) return emptyArray()
        return getHostKey().filter { type == null || it.type == type }.toTypedArray()
    }
}

internal class InspectionComplete : RuntimeException("Host key inspection complete")

private class InMemoryIdentity(private var pair: KeyPair?) : Identity {
    override fun setPassphrase(passphrase: ByteArray?): Boolean = pair?.decrypt(passphrase) == true
    override fun getPublicKeyBlob(): ByteArray? = pair?.publicKeyBlob
    override fun getSignature(data: ByteArray): ByteArray? = pair?.getSignature(data)
    override fun getSignature(data: ByteArray, alg: String): ByteArray? = pair?.getSignature(data, alg)
    override fun getAlgName(): String = pair?.keyTypeString ?: ""
    override fun getName() = "MeetSpace in-memory key"
    override fun isEncrypted(): Boolean = pair?.isEncrypted ?: false
    override fun clear() { pair?.dispose(); pair = null }
    fun hasSupportedSize(): Boolean = pair?.let { key -> when (key.keyTypeString) {
        "ssh-rsa" -> key.keySize in 2048..8192
        "ssh-ed25519" -> key.keySize == 32 // JSch reports EdDSA bytes, RSA/ECDSA bits.
        "ecdsa-sha2-nistp256" -> key.keySize == 256
        "ecdsa-sha2-nistp384" -> key.keySize == 384
        "ecdsa-sha2-nistp521" -> key.keySize == 521
        else -> false
    } } == true
}

internal class SshOperation {
    private val closed = AtomicBoolean(false)
    private val socket = AtomicReference<Socket?>()
    private val session = AtomicReference<Session?>()
    private val channel = AtomicReference<Channel?>()
    fun attachSocket(value: Socket) { socket.set(value); if (closed.get()) value.close(); checkActive() }
    fun attachSession(value: Session) { session.set(value); if (closed.get()) value.disconnect(); checkActive() }
    fun attachChannel(value: Channel) { channel.set(value); if (closed.get()) value.disconnect(); checkActive() }
    fun checkActive() { if (closed.get() || Thread.currentThread().isInterrupted) throw InterruptedException("SSH operation cancelled") }
    fun closeSocket() { try { socket.getAndSet(null)?.close() } catch (_: Exception) {} }
    fun close() {
        closed.set(true)
        // Closing TCP first unblocks handshake, auth, channel input and pending SSH writes.
        closeSocket()
        try { channel.getAndSet(null)?.disconnect() } catch (_: Exception) {}
        try { session.getAndSet(null)?.disconnect() } catch (_: Exception) {}
    }
}

internal class BoundedProbeOutput(private val maximum: Int, private val operation: SshOperation, private val discard: Boolean = false) : OutputStream() {
    private val data = ByteArrayOutputStream()
    private var total = 0
    @Volatile var exceeded = false; private set
    @Synchronized override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
    @Synchronized override fun write(bytes: ByteArray, offset: Int, length: Int) {
        if (length > maximum - total) {
            exceeded = true
            operation.closeSocket()
            throw IOException("SSH output limit")
        }
        total += length
        if (!discard) data.write(bytes, offset, length)
    }
    @Synchronized fun text(): String = try { data.toByteArray().toString(Charsets.UTF_8) } finally { data.reset() }
}

private object SilentLogger : Logger {
    override fun isEnabled(level: Int) = false
    override fun log(level: Int, message: String?) = Unit
}

/** KeyPair signing reads JSch global adapter mappings, unlike host verification's Session config. */
private object AndroidCryptoAdapters {
    init {
        JSch.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519")
        JSch.setConfig("keypairgen.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")
        JSch.setConfig("keypairgen_fromprivate.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")
    }
    fun initialize() = Unit
}

private fun safeFailure(error: Exception): SshFailure {
    if (error is SshFailure) return error
    if (error is JSchChangedHostKeyException) return changedHostFailure()
    if (error is JSchUnknownHostKeyException) return SshFailure(SshFailureKind.UNTRUSTED_HOST, "Сначала подтвердите отпечаток SSH-сервера.")
    if (generateSequence(error as Throwable) { it.cause }.any { it is SocketTimeoutException }) {
        return SshFailure(SshFailureKind.TIMEOUT, "SSH-сервер не ответил вовремя. Проверьте сеть и доступность SSH-порта.")
    }
    if (error is JSchException && (error.message?.startsWith("Auth fail") == true || error.message?.startsWith("Auth cancel") == true)) return credentialsFailure()
    if (error is JSchAlgoNegoFailException || error is JSchStrictKexException) {
        return SshFailure(SshFailureKind.NETWORK, "SSH-сервер не поддерживает требуемые современные алгоритмы. Обновите конфигурацию OpenSSH.")
    }
    return networkFailure()
}
private fun networkFailure() = SshFailure(SshFailureKind.NETWORK, "Не удалось подключиться к SSH-серверу. Проверьте адрес, порт и сеть.")
private fun credentialsFailure() = SshFailure(SshFailureKind.AUTH, "SSH-сервер отклонил учётные данные. Проверьте пользователя и пароль или доступ по ключу.")
private fun keyFailure() = SshFailure(SshFailureKind.KEY_FORMAT, "Не удалось открыть приватный ключ. Проверьте формат OpenSSH/PEM и парольную фразу.")
private fun responseLimitFailure() = SshFailure(SshFailureKind.INVALID_RESPONSE, "Ответ SSH-проверки превысил допустимый размер. Проверка остановлена.")
