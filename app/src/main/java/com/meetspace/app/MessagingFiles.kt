package com.meetspace.app

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.meetspace.core.model.AppFailure
import com.meetspace.core.model.FailureKind
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** SAF only: no broad storage permission and no persistable access or filesystem path resolution. */
object MessagingFiles {
    const val MAX_BYTES = 10 * 1024 * 1024
    data class Metadata(val name: String, val mimeType: String)
    suspend fun metadata(resolver: ContentResolver, uri: Uri): Metadata = withContext(Dispatchers.IO) {
        if (uri.scheme != "content") throw AppFailure(FailureKind.VALIDATION, "Выберите файл через системное приложение.")
        var name = "Вложение"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn) && cursor.getLong(sizeColumn) > MAX_BYTES)
                    throw AppFailure(FailureKind.VALIDATION, "Файл превышает 10 МБ.")
                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameColumn >= 0) name = cursor.getString(nameColumn) ?: name
            }
        }
        Metadata(safeName(name), resolver.getType(uri)?.takeIf { it.length <= 127 } ?: "application/octet-stream")
    }
    fun safeName(value: String): String = value.substringAfterLast('/').substringAfterLast('\\')
        .filterNot { it.isISOControl() }.trim().take(180).ifBlank { "Вложение" }

    suspend fun read(resolver: ContentResolver, uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        if (uri.scheme != "content") throw AppFailure(FailureKind.VALIDATION, "Недопустимый источник файла.")
        resolver.openInputStream(uri)?.use { input ->
            val result = object : ByteArrayOutputStream() {
                fun wipe() { buf.fill(0); reset() }
            }
            val buffer = ByteArray(8192)
            try {
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buffer); if (n < 0) break
                    if (result.size() + n > MAX_BYTES) throw AppFailure(FailureKind.VALIDATION, "Файл превышает 10 МБ.")
                    result.write(buffer, 0, n)
                }
                result.toByteArray()
            } finally { buffer.fill(0); result.wipe() }
        } ?: throw AppFailure(FailureKind.UNAVAILABLE, "Не удалось открыть выбранный файл.")
    }
    suspend fun write(resolver: ContentResolver, uri: Uri, bytes: ByteArray) = withContext(Dispatchers.IO) {
        if (uri.scheme != "content") throw AppFailure(FailureKind.VALIDATION, "Недопустимое место сохранения.")
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                var offset = 0
                while (offset < bytes.size) {
                    coroutineContext.ensureActive(); val count = minOf(8192, bytes.size - offset)
                    output.write(bytes, offset, count); offset += count
                }
            } ?: throw AppFailure(FailureKind.UNAVAILABLE, "Не удалось записать файл.")
        } catch (error: Exception) {
            // ACTION_CREATE_DOCUMENT supplies a newly created document; remove only that partial export.
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }
}
