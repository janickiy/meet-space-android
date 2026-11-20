package com.meetspace.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import com.meetspace.core.model.AppFailure
import com.meetspace.core.model.ChatAttachment
import com.meetspace.core.model.FailureKind
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

internal val LocalChatMedia = staticCompositionLocalOf<ChatMediaLoader?> { null }

internal sealed interface ChatMediaPreview : Closeable {
    class Photo(val bitmap: Bitmap) : ChatMediaPreview {
        // The final Compose frame can still be drawing this bitmap when the dialog closes.
        override fun close() = Unit
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    class Video(bytes: ByteArray, mimeType: String, private val onClose: (Video) -> Unit) : ChatMediaPreview {
        private val lock = Any()
        private var data: ByteArray? = bytes
        val mediaSource: MediaSource = ProgressiveMediaSource.Factory(DataSource.Factory {
            ByteArrayDataSource { synchronized(lock) { data ?: throw IOException("Chat media preview closed") } }
        }).setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(0)).createMediaSource(
            MediaItem.Builder().setUri(Uri.parse("meetspace-chat-media://preview")).setMimeType(mimeType).build(),
        )

        override fun close() {
            synchronized(lock) {
                data?.fill(0)
                data = null
            }
            onClose(this)
        }
    }
}

/** One selected conversation and account, with image thumbnails in RAM and a single open video. */
internal class ChatMediaLoader(
    private val download: suspend (ChatAttachment) -> ByteArray,
    private val isCurrent: () -> Boolean = { true },
) : Closeable {
    private val lock = Any()
    private var closed = false
    private var activeVideo: ChatMediaPreview.Video? = null
    private val permits = Semaphore(2)
    private val thumbnails = object : LruCache<ChatAttachment, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: ChatAttachment, value: Bitmap) = value.allocationByteCount
    }

    suspend fun thumbnail(file: ChatAttachment): Bitmap? = withContext(Dispatchers.IO) {
        permits.withPermit {
            var unpublished: Bitmap? = null
            try {
                ensureCurrent()
                synchronized(lock) { thumbnails.get(file) }?.let { return@withPermit it }
                val bytes = downloadMedia(file)
                try {
                    unpublished = if (file.isVideo) videoThumbnail(bytes) else decodePhoto(bytes, THUMBNAIL_EDGE)
                } finally { bytes.fill(0) }
                ensureCurrent()
                val bitmap = unpublished ?: return@withPermit null
                synchronized(lock) {
                    checkScope()
                    thumbnails.put(file, bitmap)
                    unpublished = null
                }
                bitmap
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            finally { unpublished?.recycle() }
        }
    }

    suspend fun open(file: ChatAttachment): ChatMediaPreview {
        var unpublished: ChatMediaPreview? = null
        try {
            val result = withContext(Dispatchers.IO) {
                permits.withPermit {
                    ensureCurrent()
                    val bytes = downloadMedia(file)
                    var videoOwnsBytes = false
                    try {
                        if (file.isVideo) {
                            val video = ChatMediaPreview.Video(bytes, file.mimeType, ::forgetVideo)
                            unpublished = video
                            videoOwnsBytes = true
                            val displaced = synchronized(lock) {
                                checkScope()
                                activeVideo.also { activeVideo = video }
                            }
                            displaced?.close()
                            ensureCurrent()
                            video
                        } else {
                            val bitmap = decodePhoto(bytes, PHOTO_EDGE)
                                ?: throw AppFailure(FailureKind.UNAVAILABLE, "Не удалось открыть изображение.")
                            val photo = ChatMediaPreview.Photo(bitmap)
                            unpublished = photo
                            ensureCurrent()
                            photo
                        }
                    } finally { if (!videoOwnsBytes) bytes.fill(0) }
                }
            }
            unpublished = null
            return result
        } finally {
            val discarded = unpublished
            if (discarded is ChatMediaPreview.Photo) discarded.bitmap.recycle() else discarded?.close()
        }
    }

    private suspend fun downloadMedia(file: ChatAttachment): ByteArray {
        if ((!file.isVideo && !file.mimeType.startsWith("image/")) || file.status !in setOf("attached", "ready") ||
            file.size !in 1L..MAX_BYTES) throw AppFailure(FailureKind.VALIDATION, "Этот файл недоступен для просмотра.")
        val bytes = download(file)
        try {
            ensureCurrent()
            if (bytes.size.toLong() != file.size) throw AppFailure(FailureKind.INVALID_RESPONSE, "Файл получен не полностью.")
            return bytes
        } catch (failure: Exception) {
            bytes.fill(0)
            throw failure
        }
    }

    private suspend fun ensureCurrent() {
        currentCoroutineContext().ensureActive()
        synchronized(lock) { checkScope() }
    }

    private fun checkScope() {
        if (closed || !isCurrent()) throw CancellationException("Chat media scope changed")
    }

    private fun forgetVideo(video: ChatMediaPreview.Video) = synchronized(lock) {
        if (activeVideo === video) activeVideo = null
    }

    override fun close() {
        val video = synchronized(lock) {
            closed = true
            thumbnails.evictAll()
            activeVideo.also { activeVideo = null }
        }
        video?.close()
    }
}

private val ChatAttachment.isVideo get() = mimeType.startsWith("video/")
private const val MAX_BYTES = 10L * 1024 * 1024
private const val THUMBNAIL_EDGE = 320
private const val PHOTO_EDGE = 1600

private fun decodePhoto(bytes: ByteArray, edge: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outMimeType?.startsWith("image/") != true || !validDimensions(bounds.outWidth, bounds.outHeight)) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > edge) sample *= 2
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }) ?: return null
    val orientation = try {
        ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (_: IOException) { ExifInterface.ORIENTATION_NORMAL }
    val matrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
        }
    }
    if (matrix.isIdentity) return bitmap
    return try { Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true) }
    finally { bitmap.recycle() }
}

private fun videoThumbnail(bytes: ByteArray): Bitmap? {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(object : MediaDataSource() {
            override fun getSize() = bytes.size.toLong()
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                if (position < 0 || position >= bytes.size.toLong()) return -1
                val start = position.toInt()
                val count = minOf(size, bytes.size - start)
                bytes.copyInto(buffer, offset, start, start + count)
                return count
            }
            override fun close() = Unit
        })
        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return null
        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return null
        if (!validDimensions(width, height) || width.toLong() * height > 16_000_000) return null
        if (Build.VERSION.SDK_INT >= 27) {
            val (targetWidth, targetHeight) = scaledDimensions(width, height)
            return retriever.getScaledFrameAtTime(-1L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, targetWidth, targetHeight)
        }
        val bitmap = retriever.frameAtTime ?: return null
        val (targetWidth, targetHeight) = scaledDimensions(bitmap.width, bitmap.height)
        if (bitmap.width == targetWidth && bitmap.height == targetHeight) return bitmap
        return try { Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true) }
        finally { bitmap.recycle() }
    } finally { retriever.release() }
}

private fun scaledDimensions(width: Int, height: Int): Pair<Int, Int> {
    val scale = maxOf(1f, maxOf(width, height).toFloat() / THUMBNAIL_EDGE)
    return (width / scale).toInt().coerceAtLeast(1) to (height / scale).toInt().coerceAtLeast(1)
}

private fun validDimensions(width: Int, height: Int) =
    width in 1..32_768 && height in 1..32_768 && width.toLong() * height <= 64_000_000
