package com.meetspace.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.staticCompositionLocalOf
import com.meetspace.core.data.RecordingAccess
import com.meetspace.core.model.Recording
import com.meetspace.core.model.RecordingFile
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

internal val LocalRecordingPreviews = staticCompositionLocalOf<RecordingPreviewLoader?> { null }

/** Private posters live only in this visible account's bounded memory cache. */
internal class RecordingPreviewLoader(
    private val open: suspend (Recording, RecordingFile) -> RecordingAccess,
    private val isCurrent: () -> Boolean,
    maxCacheBytes: Int = 8 * 1024 * 1024,
) : Closeable {
    private val lock = Any()
    private var closed = false
    private val accesses = mutableSetOf<RecordingAccess>()
    // Thumbnail requests cannot exhaust the repository's eight leases or evict playback.
    private val permits = Semaphore(2)
    private val cache = object : LruCache<String, Bitmap>(maxCacheBytes) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    internal val cachedBytes get() = cache.size()

    suspend fun load(recording: Recording, preview: RecordingFile): Bitmap? = withContext(Dispatchers.IO) {
        if (recording.status !in setOf("ready", "partial_ready") || preview.type != "preview_jpg" ||
            preview.mimeType != "image/jpeg" || !preview.available || preview.sizeBytes?.let { it > MAX_BYTES } == true) return@withContext null
        val key = "${recording.conferenceId}|${recording.id}|${preview.id}|${preview.checksumSha256}|${preview.sizeBytes}"
        permits.withPermit {
            currentCoroutineContext().ensureActive()
            synchronized(lock) {
                if (closed || !isCurrent()) return@withPermit null
                cache.get(key)?.let { return@withPermit it }
            }
            var access: RecordingAccess? = null
            try {
                val lease = open(recording, preview)
                access = lease
                synchronized(lock) {
                    if (closed || !isCurrent()) throw CancellationException("Recording account changed")
                    accesses.add(lease)
                }
                val encoded = LimitedPreviewBuffer()
                try {
                    lease.copyTo(encoded)
                    currentCoroutineContext().ensureActive()
                    val bytes = encoded.toByteArray()
                    val bitmap = try { decodePreview(bytes) } finally { bytes.fill(0) }
                    currentCoroutineContext().ensureActive()
                    if (bitmap != null) synchronized(lock) {
                        if (closed || !isCurrent()) { bitmap.recycle(); return@withPermit null }
                        cache.put(key, bitmap)
                    }
                    bitmap
                } finally { encoded.clear() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            finally {
                access?.let { lease -> synchronized(lock) { accesses.remove(lease) }; lease.close() }
            }
        }
    }

    override fun close() {
        val active = synchronized(lock) {
            if (closed) return
            closed = true
            accesses.toList().also { accesses.clear(); cache.evictAll() }
        }
        active.forEach { it.close() }
        // Do not recycle cached bitmaps: a leaving Compose frame may still reference them.
    }

    private class LimitedPreviewBuffer : ByteArrayOutputStream() {
        private var closed = false
        @Synchronized
        override fun write(value: Int) { checkSize(1); super.write(value) }
        @Synchronized
        override fun write(bytes: ByteArray, offset: Int, length: Int) { checkSize(length); super.write(bytes, offset, length) }
        private fun checkSize(extra: Int) {
            if (closed || extra < 0 || count > MAX_BYTES - extra) throw IOException("Recording preview is unavailable or too large")
        }
        @Synchronized
        fun clear() { closed = true; buf.fill(0); reset() }
    }

    private companion object {
        const val MAX_BYTES = 4 * 1024 * 1024
        fun decodePreview(bytes: ByteArray): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val width = bounds.outWidth; val height = bounds.outHeight
            if (bounds.outMimeType != "image/jpeg" || width !in 1..16_384 || height !in 1..16_384 || width.toLong() * height > 64_000_000) return null
            var sample = 1
            while (maxOf(width, height) / sample > 1024) sample *= 2
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        }
    }
}
