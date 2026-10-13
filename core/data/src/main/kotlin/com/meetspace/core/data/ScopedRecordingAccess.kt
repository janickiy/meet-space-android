package com.meetspace.core.data

import com.meetspace.core.model.FailureKind
import com.meetspace.core.model.RecordingFile
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.security.MessageDigest
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*

/** A capability belongs to exactly one identity; closing it cancels pending requests and existing readers. */
internal class ScopedRecordingAccess(
    override val file: RecordingFile,
    private val url: HttpUrl,
    private val client: OkHttpClient,
    private val onClosed: (ScopedRecordingAccess) -> Unit,
) : RecordingAccess {
    private val gate = Any()
    private var closed = false
    private val calls = mutableSetOf<Call>()
    private val streams = mutableSetOf<InputStream>()

    override fun toString() = "RecordingAccess([private])"

    override fun open(position: Long, length: Long?): RecordingStream {
        val call = newCall(position, length)
        return try { responseStream(call, call.execute(), position, length) }
        catch (error: Exception) { call.cancel(); synchronized(gate) { calls.remove(call) }; throw safeFailure(error) }
    }

    override suspend fun copyTo(destination: OutputStream, onProgress: (Long) -> Unit): Long = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { close() }
        val call = try { newCall(0, null) } catch (error: Exception) {
            if (continuation.isActive) continuation.resumeWithException(safeFailure(error))
            return@suspendCancellableCoroutine
        }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                close()
                if (continuation.isActive) continuation.resumeWithException(safeFailure(e))
            }
            override fun onResponse(call: Call, response: Response) {
                val buffer = ByteArray(64 * 1024)
                try {
                    val digest = MessageDigest.getInstance("SHA-256")
                    var total = 0L
                    responseStream(call, response, 0, null).use { stream ->
                        while (true) {
                            val count = stream.input.read(buffer)
                            if (count == -1) break
                            if (!continuation.isActive) throw unavailable()
                            try { destination.write(buffer, 0, count) }
                            catch (_: IOException) { throw RecordingReadException(FailureKind.STORAGE, "Не удалось сохранить файл в выбранном месте.") }
                            digest.update(buffer, 0, count)
                            total += count
                            onProgress(total)
                        }
                    }
                    val checksum = digest.digest().joinToString("") { "%02x".format(it) }
                    if (file.checksumSha256 != null && checksum != file.checksumSha256) throw invalid()
                    if (continuation.isActive) continuation.resume(total)
                } catch (error: Exception) {
                    response.close()
                    if (continuation.isActive) continuation.resumeWithException(safeFailure(error))
                } finally { buffer.fill(0); close() }
            }
        })
    }

    private fun newCall(position: Long, length: Long?): Call = synchronized(gate) {
        if (closed) throw unavailable()
        if (position < 0 || length?.let { it <= 0 || position > Long.MAX_VALUE - it } == true ||
            file.sizeBytes?.let { position >= it } == true) throw invalid()
        val range = position != 0L || length != null
        val end = length?.let { position + it - 1 }?.let { requested -> file.sizeBytes?.let { minOf(requested, it - 1) } ?: requested }
        val request = Request.Builder().url(url).header("Accept-Encoding", "identity")
            .apply { if (range) header("Range", "bytes=$position-${end ?: ""}") }.build()
        client.newCall(request).also { calls.add(it) }
    }

    private fun responseStream(call: Call, response: Response, position: Long, requestedLength: Long?): RecordingStream {
        try {
            when (response.code) {
                401, 403, 404, 410 -> throw RecordingReadException(FailureKind.FORBIDDEN, "Ссылка на файл недоступна. Обновите запись и попробуйте снова.")
                429 -> throw RecordingReadException(FailureKind.RATE_LIMITED, "Слишком много запросов. Попробуйте позже.")
                in 300..399, 416 -> throw invalid()
            }
            if (!response.isSuccessful) throw RecordingReadException(FailureKind.UNAVAILABLE, "Хранилище записи временно недоступно.")
            val body = response.body ?: throw invalid()
            if (body.contentType()?.let { "${it.type}/${it.subtype}" } != file.mimeType ||
                response.header("Content-Encoding")?.let { it != "identity" } == true) throw invalid()
            val contentLength = body.contentLength().takeIf { it >= 0 }
            val ranged = position != 0L || requestedLength != null
            val expected: Long?
            if (ranged) {
                if (response.code != 206) throw invalid()
                val range = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matchEntire(response.header("Content-Range").orEmpty()) ?: throw invalid()
                val (start, end, total) = range.destructured.toList().map { it.toLongOrNull() ?: throw invalid() }
                val requestedEnd = requestedLength?.let { position + it - 1 }
                if (start != position || end < start || total <= end ||
                    file.sizeBytes?.let { it != total } == true ||
                    end != minOf(requestedEnd ?: (total - 1), total - 1)) throw invalid()
                expected = end - start + 1
                if (contentLength != null && contentLength != expected) throw invalid()
            } else {
                if (response.code != 200 || response.header("Content-Range") != null) throw invalid()
                expected = file.sizeBytes ?: contentLength
                if (file.sizeBytes != null && contentLength != null && contentLength != file.sizeBytes) throw invalid()
            }
            val raw = body.byteStream()
            val input = object : InputStream() {
                private var count = 0L
                private var done = false
                override fun read(): Int {
                    val one = ByteArray(1)
                    return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 255
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    synchronized(gate) { if (closed || done) throw unavailable() }
                    if (offset < 0 || length < 0 || offset > buffer.size - length) throw IndexOutOfBoundsException()
                    if (length == 0) return 0
                    try {
                        val read = raw.read(buffer, offset, length)
                        if (read == -1) {
                            if (expected != null && count != expected) throw invalid()
                        } else {
                            if (count > Long.MAX_VALUE - read) throw invalid()
                            count += read
                            if (expected != null && count > expected) throw invalid()
                        }
                        synchronized(gate) { if (closed) throw unavailable() }
                        return read
                    } catch (error: Exception) { close(); throw safeFailure(error) }
                }
                override fun close() {
                    synchronized(gate) {
                        if (done) return
                        done = true
                        streams.remove(this); calls.remove(call)
                    }
                    call.cancel(); response.close()
                }
            }
            synchronized(gate) {
                if (closed) { response.close(); throw unavailable() }
                streams.add(input)
            }
            return RecordingStream(input, expected)
        } catch (error: Exception) { response.close(); synchronized(gate) { calls.remove(call) }; throw safeFailure(error) }
    }

    override fun close() {
        val pending: Pair<List<Call>, List<InputStream>> = synchronized(gate) {
            if (closed) return
            closed = true
            (calls.toList() to streams.toList()).also { calls.clear(); streams.clear() }
        }
        pending.first.forEach { it.cancel() }
        pending.second.forEach { runCatching { it.close() } }
        onClosed(this)
    }

    private fun unavailable() = RecordingReadException(FailureKind.SESSION_EXPIRED, "Доступ к записи закрыт. Откройте её заново.")
    private fun invalid() = RecordingReadException(FailureKind.INVALID_RESPONSE, "Файл записи не прошёл проверку. Обновите запись и попробуйте снова.")
    private fun safeFailure(error: Exception): RecordingReadException = when (error) {
        is RecordingReadException -> error
        is SSLException -> RecordingReadException(FailureKind.TLS, "Не удалось подтвердить защищённое соединение с хранилищем записи.")
        is SocketTimeoutException -> RecordingReadException(FailureKind.TIMEOUT, "Хранилище записи не ответило вовремя.")
        is IOException -> RecordingReadException(FailureKind.NETWORK, "Не удалось загрузить файл записи. Проверьте соединение.")
        else -> invalid()
    }
}
