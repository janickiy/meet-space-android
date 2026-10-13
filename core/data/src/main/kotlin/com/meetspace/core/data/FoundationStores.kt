package com.meetspace.core.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.meetspace.core.model.AppFailure
import com.meetspace.core.model.FailureKind
import com.meetspace.core.model.ServerProfile
import com.meetspace.core.model.ThemeMode
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

@Serializable
internal data class PersistedSettings(
    val profiles: List<ServerProfile> = emptyList(),
    val selectedProfileId: String? = null,
    val theme: ThemeMode = ThemeMode.SYSTEM,
)

internal interface ProfileStore {
    suspend fun read(): PersistedSettings
    suspend fun write(settings: PersistedSettings)
}

internal interface CredentialStore {
    suspend fun read(scope: SessionScope): StoredCredential?
    suspend fun write(scope: SessionScope, credential: StoredCredential)
    suspend fun delete(scope: SessionScope)
}

private val Context.foundationPreferences by preferencesDataStore(name = "meetspace_profiles")

/** Profiles and theme contain no access token, session cookie or password. */
internal class AndroidProfileStore(private val context: Context) : ProfileStore {
    private val settingsKey = stringPreferencesKey("settings_v1")

    override suspend fun read(): PersistedSettings {
        return try {
            val json = context.foundationPreferences.data.first()[settingsKey] ?: return PersistedSettings()
            wireJson.decodeFromString<PersistedSettings>(json)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw storageFailure() }
    }

    override suspend fun write(settings: PersistedSettings) {
        try {
            context.foundationPreferences.edit { it[settingsKey] = wireJson.encodeToString(settings) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw storageFailure() }
    }
}

/** AES-GCM ciphertext in app-private no-backup storage. The key never leaves Android Keystore. */
internal class KeystoreCredentialStore(context: Context) : CredentialStore {
    private val directory = File(context.noBackupFilesDir, "meetspace_sessions_v1")

    override suspend fun read(scope: SessionScope): StoredCredential? = withContext(Dispatchers.IO) {
        synchronized(keyLock) {
            val file = file(scope)
            if (!file.baseFile.exists()) return@synchronized null
            try {
                val bytes = file.readFully()
                if (bytes.size !in 30..MAX_BYTES || bytes[0] != FORMAT_VERSION || bytes[1].toInt() != IV_BYTES) {
                    throw IllegalStateException()
                }
                val iv = bytes.copyOfRange(2, 2 + IV_BYTES)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
                cipher.updateAAD(scopeBytes(scope))
                val clear = cipher.doFinal(bytes.copyOfRange(2 + IV_BYTES, bytes.size))
                val credential = try { wireJson.decodeFromString<StoredCredential>(clear.decodeToString()) }
                    finally { clear.fill(0) }
                if (credential.expiresAt <= System.currentTimeMillis()) {
                    file.delete()
                    null
                } else {
                    require(credential.value.isNotBlank() && credential.value.length <= 8192)
                    credential
                }
            } catch (_: Exception) {
                file.delete()
                throw AppFailure(FailureKind.STORAGE, "Сохранённая сессия недоступна. Войдите снова.")
            }
        }
    }

    override suspend fun write(scope: SessionScope, credential: StoredCredential) = withContext(Dispatchers.IO) {
        synchronized(keyLock) {
            try {
                check(directory.isDirectory || directory.mkdirs())
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, key())
                cipher.updateAAD(scopeBytes(scope))
                val clear = wireJson.encodeToString(credential).encodeToByteArray()
                val encrypted = try { cipher.doFinal(clear) } finally { clear.fill(0) }
                check(cipher.iv.size == IV_BYTES)
                val bytes = byteArrayOf(FORMAT_VERSION, IV_BYTES.toByte()) + cipher.iv + encrypted
                val file = file(scope)
                val stream = file.startWrite()
                try { stream.write(bytes); file.finishWrite(stream) }
                catch (error: Exception) { file.failWrite(stream); throw error }
            } catch (_: Exception) { throw storageFailure() }
        }
    }

    override suspend fun delete(scope: SessionScope) = withContext(Dispatchers.IO) {
        synchronized(keyLock) {
            val target = file(scope)
            target.delete()
            if (target.baseFile.exists()) throw storageFailure()
        }
    }

    private fun file(scope: SessionScope): AtomicFile {
        val digest = MessageDigest.getInstance("SHA-256").digest(scopeBytes(scope))
        val name = digest.joinToString("") { "%02x".format(it) }
        return AtomicFile(File(directory, "$name.enc"))
    }

    private fun scopeBytes(scope: SessionScope): ByteArray =
        ("meetspace-session-v1\n${scope.origin}\n${scope.accountId}").encodeToByteArray()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build())
        return generator.generateKey()
    }

    private companion object {
        val keyLock = Any()
        const val KEY_ALIAS = "meetspace_session_aes_gcm_v1"
        const val FORMAT_VERSION: Byte = 1
        const val IV_BYTES = 12
        const val MAX_BYTES = 32 * 1024
    }
}

internal fun storageFailure() = AppFailure(FailureKind.STORAGE, "Не удалось сохранить настройки на устройстве.")
