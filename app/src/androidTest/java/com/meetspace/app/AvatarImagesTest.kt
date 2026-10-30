package com.meetspace.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Real Android codec checks: no network, account, picker permissions or production image. */
class AvatarImagesTest {
    @Test fun cacheSeparatesVersionsAndUserGroupResources() = runBlocking {
        val userCalls = AtomicInteger(); val groupCalls = AtomicInteger()
        val loader = AvatarImageLoader(
            user = { userCalls.incrementAndGet(); jpeg(Color.RED) },
            group = { groupCalls.incrementAndGet(); jpeg(Color.BLUE) },
            isCurrent = { true },
        )
        try {
            val first = loader.load("same-id", "v1", false)
            assertNotNull(first)
            assertSame(first, loader.load("same-id", "v1", false))
            assertEquals(1, userCalls.get())
            assertNotSame(first, loader.load("same-id", "v2", false))
            assertEquals(2, userCalls.get())
            val group = loader.load("same-id", "v1", true)!!
            assertEquals(1, groupCalls.get())
            assertTrue(Color.red(first!!.getPixel(10, 10)) > 220)
            assertTrue(Color.blue(group.getPixel(10, 10)) > 220)
            assertTrue(loader.cachedBytes in 1..4 * 1024 * 1024)
        } finally { loader.close() }
        assertEquals(0, loader.cachedBytes)
        assertNull(loader.load("same-id", "v1", false))
    }

    @Test fun aLateImageFromAnOldAccountIsDiscardedAndItsEncodedBufferCleared() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val complete = CompletableDeferred<Unit>()
        val current = AtomicBoolean(true)
        val encoded = jpeg(Color.RED)
        val loader = AvatarImageLoader(user = { entered.complete(Unit); complete.await(); encoded }, group = { error("Unexpected group fetch") }, isCurrent = current::get)
        val pending = async { loader.load("private-user", "v1", false) }
        entered.await()
        current.set(false); loader.close(); complete.complete(Unit)
        assertNull(pending.await())
        assertTrue(encoded.all { it == 0.toByte() })
        assertEquals(0, loader.cachedBytes)
    }

    @Test fun selectedPhotoIsSubsampledRotatedAndStrippedBeforeUpload() {
        val bitmap = Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888)
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) bitmap.setPixel(x, y, if (y < 400) Color.RED else Color.BLUE)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File.createTempFile("avatar-codec-", ".jpg", context.cacheDir)
        try {
            source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
            ExifInterface(source.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                setAttribute(ExifInterface.TAG_GPS_LATITUDE, "55/1,45/1,0/1")
                saveAttributes()
            }
            val encoded = normalizeAvatarImage(source.readBytes())
            assertTrue(encoded.size in 1..2 * 1024 * 1024)
            val result = BitmapFactory.decodeByteArray(encoded, 0, encoded.size)
            try {
                assertTrue(result.width <= 512)
                assertEquals(result.width, result.height)
                assertTrue(Color.blue(result.getPixel(result.width / 4, result.height / 2)) > 180)
                assertTrue(Color.red(result.getPixel(result.width * 3 / 4, result.height / 2)) > 180)
            } finally { result.recycle() }
            val exif = ExifInterface(encoded.inputStream())
            assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
            // Re-encoded JPEGs may expose an undefined orientation (0) after EXIF removal.
            // Both 0 and normal (1) mean no further transform; the pixel assertions above
            // prove that the original 90-degree rotation was already applied.
            assertTrue(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
                in setOf(ExifInterface.ORIENTATION_UNDEFINED, ExifInterface.ORIENTATION_NORMAL))
        } finally { bitmap.recycle(); source.delete() }
    }

    @Test fun malformedAndOversizedInputsCannotBeUploaded() {
        listOf(byteArrayOf(), byteArrayOf(1, 2, 3), ByteArray(20 * 1024 * 1024 + 1)).forEach { input ->
            try { normalizeAvatarImage(input); fail("Expected a rejected image") }
            catch (_: IllegalArgumentException) { }
        }
    }

    private fun jpeg(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(color)
            ByteArrayOutputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)); it.toByteArray() }
        } finally { bitmap.recycle() }
    }
}
