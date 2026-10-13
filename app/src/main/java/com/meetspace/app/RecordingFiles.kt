package com.meetspace.app

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import com.meetspace.core.data.RecordingAccess
import com.meetspace.core.model.AppFailure
import com.meetspace.core.model.FailureKind
import kotlinx.coroutines.*

/** Streams directly to a newly-created SAF document; no private video cache or broad storage access. */
object RecordingFiles {
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    suspend fun write(resolver: ContentResolver, uri: Uri, access: RecordingAccess, progress: (Long) -> Unit) = withContext(Dispatchers.IO) {
        if (uri.scheme != "content") throw AppFailure(FailureKind.VALIDATION, "Выберите папку через системное приложение.")
        try {
            resolver.openOutputStream(uri, "w")?.use { output -> access.copyTo(output, progress) }
                ?: throw AppFailure(FailureKind.STORAGE, "Не удалось открыть файл для сохранения.")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: AppFailure) { throw error }
        catch (error: com.meetspace.core.data.RecordingReadException) { throw error }
        catch (_: Exception) { throw AppFailure(FailureKind.STORAGE, "Не удалось сохранить запись. Проверьте свободное место и выбранную папку.") }
    }
    fun removePartial(resolver: ContentResolver, uri: Uri, onFailure: () -> Unit = {}) {
        if (uri.scheme == "content") cleanupScope.launch {
            val removed = runCatching { DocumentsContract.deleteDocument(resolver, uri) }.getOrElse {
                runCatching { resolver.delete(uri, null, null) > 0 }.getOrDefault(false)
            }
            if (!removed) onFailure()
        }
    }
}
