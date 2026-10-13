package com.meetspace.core.ssh

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.jcraft.jsch.HostKey
import com.meetspace.core.model.*
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

internal interface HostPinStore {
    suspend fun read(endpoint: SshEndpoint): SshHostKey?
    /** Atomic insert only. A different existing key is never replaced. */
    suspend fun trustNew(endpoint: SshEndpoint, key: SshHostKey)
    suspend fun delete(endpoint: SshEndpoint)
}

private val Context.sshHostPreferences by preferencesDataStore(name = "meetspace_ssh_host_pins")

/** Contains only SSH server public keys, scoped to the exact canonical host and port. */
internal class AndroidHostPinStore(private val context: Context) : HostPinStore {
    override suspend fun read(endpoint: SshEndpoint): SshHostKey? = storage {
        context.sshHostPreferences.data.first()[preferenceKey(endpoint)]?.let(::decode)
    }

    override suspend fun trustNew(endpoint: SshEndpoint, key: SshHostKey) = storage {
        HostKeyCodec.validate(key)
        context.sshHostPreferences.edit { preferences ->
            val name = preferenceKey(endpoint)
            val previous = preferences[name]?.let(::decode)
            if (previous != null && previous != key) throw changedHostFailure()
            if (previous == null) preferences[name] = encode(key)
        }
        Unit
    }

    override suspend fun delete(endpoint: SshEndpoint) = storage {
        context.sshHostPreferences.edit { it.remove(preferenceKey(endpoint)) }
        Unit
    }

    private fun preferenceKey(endpoint: SshEndpoint) = stringPreferencesKey(
        "host_v1_" + MessageDigest.getInstance("SHA-256").digest(endpoint.pinId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) },
    )

    private fun encode(key: SshHostKey) = "${key.algorithm}\n${key.keyBase64}\n${key.fingerprint}"
    private fun decode(value: String): SshHostKey {
        if (value.length > 16_384) throw storageFailure()
        val fields = value.split('\n')
        if (fields.size != 3) throw storageFailure()
        return try { SshHostKey(fields[0], fields[1], fields[2]).also(HostKeyCodec::validate) }
        catch (_: Exception) { throw storageFailure() }
    }

    private suspend fun <T> storage(block: suspend () -> T): T = try { block() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: SshFailure) { throw failure }
    catch (_: Exception) { throw storageFailure() }
}

internal object HostKeyCodec {
    private val supported = setOf("ssh-ed25519", "ecdsa-sha2-nistp256", "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521", "ssh-rsa")

    fun fromBytes(bytes: ByteArray): SshHostKey {
        if (bytes.size !in 16..8192) throw invalidHostKeyFailure()
        val hostKey = try { HostKey("inspection", bytes) } catch (_: Exception) { throw invalidHostKeyFailure() }
        if (hostKey.type !in supported) throw invalidHostKeyFailure()
        return SshHostKey(
            algorithm = hostKey.type,
            keyBase64 = Base64.getEncoder().encodeToString(bytes),
            fingerprint = "SHA256:" + Base64.getEncoder().withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes)),
        )
    }

    fun validate(key: SshHostKey): ByteArray {
        if (key.keyBase64.length > 11_000 || key.algorithm !in supported) throw invalidHostKeyFailure()
        val bytes = try { Base64.getDecoder().decode(key.keyBase64) } catch (_: Exception) { throw invalidHostKeyFailure() }
        if (fromBytes(bytes) != key) throw invalidHostKeyFailure()
        return bytes
    }
}

internal fun storageFailure() = SshFailure(SshFailureKind.STORAGE, "Не удалось прочитать или сохранить отпечаток SSH-сервера.")
internal fun changedHostFailure() = SshFailure(SshFailureKind.HOST_KEY_CHANGED, "Ключ SSH-сервера изменился. Подключение заблокировано. Проверьте ключ через панель VPS или у администратора.")
internal fun invalidHostKeyFailure() = SshFailure(SshFailureKind.INVALID_RESPONSE, "SSH-сервер передал неподдерживаемый или повреждённый публичный ключ.")
