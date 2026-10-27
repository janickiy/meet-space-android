package com.meetspace.app

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.meetspace.app.ui.RecordingModeCover
import com.meetspace.core.data.RecordingAccess
import com.meetspace.core.data.RecordingStream
import com.meetspace.core.designsystem.MeetSpaceTheme
import com.meetspace.core.model.Recording
import com.meetspace.core.model.RecordingFile
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RecordingPreviewTest {
    @get:Rule val compose = createComposeRule()

    @Test fun jpegPosterUsesScopedAccessOffMainAndHasBoundedMemoryCache() = runBlocking {
        val bytes = jpeg()
        val leases = mutableListOf<PreviewAccess>()
        val loader = RecordingPreviewLoader(open = { _, file ->
            assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
            PreviewAccess(file, bytes).also { leases.add(it) }
        }, isCurrent = { true }, maxCacheBytes = 2 * 1024 * 1024)
        try {
            val first = checkNotNull(loader.load(record, preview))
            assertTrue(first.width <= 1024 && first.height <= 1024)
            assertEquals(16f / 9f, first.width.toFloat() / first.height, .01f)
            assertSame(first, loader.load(record, preview))
            assertEquals(1, leases.size)
            assertTrue(leases.single().closed.get())
            repeat(4) { index ->
                val file = preview.copy(id = "other-file-$index")
                assertNotNull(loader.load(record.copy(id = "record-$index", files = listOf(file)), file))
                assertTrue(loader.cachedBytes <= 2 * 1024 * 1024)
            }
            loader.close()
            assertEquals(0, loader.cachedBytes)
            assertFalse("A leaving Compose frame may still hold the bitmap", first.isRecycled)
        } finally { loader.close() }
    }

    @Test fun invalidOrOversizedPostersRetainFallbackAndCloseTheFileLease() = runBlocking {
        var requests = 0
        var bytes = byteArrayOf(1, 2, 3)
        val leases = mutableListOf<PreviewAccess>()
        val loader = RecordingPreviewLoader(open = { _, file ->
            requests++
            PreviewAccess(file, bytes).also { leases.add(it) }
        }, isCurrent = { true })
        try {
            assertNull(loader.load(record, preview.copy(sizeBytes = 4L * 1024 * 1024 + 1)))
            assertNull(loader.load(record, preview.copy(available = false)))
            assertNull(loader.load(record, preview.copy(type = "final_mp4", mimeType = "video/mp4")))
            assertEquals(0, requests)
            assertNull(loader.load(record, preview))
            assertTrue(leases.last().closed.get())
            bytes = ByteArray(4 * 1024 * 1024 + 1)
            assertNull(loader.load(record.copy(id = "oversized"), preview.copy(id = "oversized-file")))
            assertTrue(leases.last().closed.get())
            assertEquals(0, loader.cachedBytes)
        } finally { loader.close() }
    }

    @Test fun cancellationAndAccountChangeCannotKeepOrPublishThePreviousPoster() = runBlocking {
        val ready = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val current = AtomicBoolean(true)
        val lease = PreviewAccess(preview, jpeg(), beforeCopy = { ready.complete(Unit); resume.await() })
        val loader = RecordingPreviewLoader(open = { _, _ -> lease }, isCurrent = current::get)
        val pending = async { loader.load(record, preview) }
        ready.await()
        current.set(false)
        resume.complete(Unit)
        assertNull(pending.await())
        assertTrue(lease.closed.get())
        assertEquals(0, loader.cachedBytes)
        loader.close()

        val copying = CompletableDeferred<Unit>()
        val wait = CompletableDeferred<Unit>()
        val cancelledLease = PreviewAccess(preview, jpeg(), beforeCopy = { copying.complete(Unit); wait.await() })
        val other = RecordingPreviewLoader(open = { _, _ -> cancelledLease }, isCurrent = { true })
        try {
            val cancelled = async { other.load(record, preview) }
            copying.await()
            cancelled.cancelAndJoin()
            assertTrue(cancelledLease.closed.get())
            assertEquals(0, other.cachedBytes)
        } finally { other.close() }
    }

    @Test fun cardDisplaysServerPosterAndAudioRetainsItsModeFallback() {
        val bytes = jpeg()
        val loader = RecordingPreviewLoader(open = { _, file -> PreviewAccess(file, bytes) }, isCurrent = { true })
        var item by mutableStateOf(record)
        try {
            compose.setContent {
                MeetSpaceTheme {
                    CompositionLocalProvider(LocalRecordingPreviews provides loader) {
                        RecordingModeCover(item, Modifier.width(320.dp).height(180.dp))
                    }
                }
            }
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("recording-cover-${record.id}").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("recording-cover-${record.id}").assertIsDisplayed()
            compose.onNodeWithTag("recording-cover-placeholder-${record.id}").assertDoesNotExist()
            compose.onNodeWithText("0:28").assertIsDisplayed()
            compose.runOnIdle { item = record.copy(id = "audio", mode = "audio_only", files = emptyList()) }
            compose.onNodeWithTag("recording-cover-${record.id}").assertDoesNotExist()
            compose.onNodeWithTag("recording-cover-placeholder-audio").assertIsDisplayed()
        } finally { loader.close() }
    }

    private class PreviewAccess(
        override val file: RecordingFile,
        private val bytes: ByteArray,
        private val beforeCopy: suspend () -> Unit = {},
    ) : RecordingAccess {
        val closed = AtomicBoolean()
        override fun open(position: Long, length: Long?) = RecordingStream(bytes.inputStream(), bytes.size.toLong())
        override suspend fun copyTo(destination: OutputStream, onProgress: (Long) -> Unit): Long {
            beforeCopy()
            destination.write(bytes)
            onProgress(bytes.size.toLong())
            return bytes.size.toLong()
        }
        override fun close() { closed.set(true) }
    }

    private companion object {
        val preview = RecordingFile("preview", "preview_jpg", "preview.jpg", "image/jpeg")
        val record = Recording("recording", "meeting", "composite", "ready", Instant.EPOCH,
            durationSec = 28, files = listOf(preview))
        fun jpeg(): ByteArray {
            val bitmap = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.BLUE)
            return try { ByteArrayOutputStream().use { out -> assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)); out.toByteArray() } }
            finally { bitmap.recycle() }
        }
    }
}
