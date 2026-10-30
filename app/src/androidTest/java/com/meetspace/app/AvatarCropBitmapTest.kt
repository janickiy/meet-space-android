package com.meetspace.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Native codec/pixel tests use generated fixtures only; no personal image or server access. */
class AvatarCropBitmapTest {
    @Test fun editorKeepsTheWholeAspectAndExportMatchesHorizontalAndVerticalOffsets() {
        val landscape = Bitmap.createBitmap(900, 300, Bitmap.Config.ARGB_8888)
        try {
            for (y in 0 until landscape.height) for (x in 0 until landscape.width) landscape.setPixel(x, y,
                when { x < 300 -> Color.RED; x < 600 -> Color.GREEN; else -> Color.BLUE })
            val source = decodeAvatarCropSource(png(landscape))
            try {
                assertEquals(3f, source.width.toFloat() / source.height, .001f)
                assertExportColor(source, AvatarCrop(x = 1f), Color.RED)
                assertExportColor(source, AvatarCrop(), Color.GREEN)
                assertExportColor(source, AvatarCrop(x = -1f), Color.BLUE)
                assertFalse(source.isRecycled)
            } finally { source.recycle() }
        } finally { landscape.recycle() }
        val portrait = Bitmap.createBitmap(300, 900, Bitmap.Config.ARGB_8888)
        try {
            for (y in 0 until portrait.height) for (x in 0 until portrait.width) portrait.setPixel(x, y,
                when { y < 300 -> Color.RED; y < 600 -> Color.GREEN; else -> Color.BLUE })
            assertExportColor(portrait, AvatarCrop(y = 1f), Color.RED)
            assertExportColor(portrait, AvatarCrop(y = -1f), Color.BLUE)
        } finally { portrait.recycle() }
    }

    @Test fun exportDoesNotPaintEmptyCornersAndLeavesSourceOwnedByTheCaller() {
        val bitmap = Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        try {
            for (crop in listOf(AvatarCrop(), AvatarCrop(3f, -1f, -1f), AvatarCrop(3f, 1f, 1f))) {
                val encoded = exportAvatarCrop(bitmap, crop)
                assertTrue(encoded.size in 1..2 * 1024 * 1024)
                val decoded = BitmapFactory.decodeByteArray(encoded, 0, encoded.size)
                try {
                    assertEquals(512, decoded.width); assertEquals(512, decoded.height)
                    for ((x, y) in listOf(0 to 0, 511 to 0, 0 to 511, 511 to 511)) {
                        val pixel = decoded.getPixel(x, y)
                        assertTrue(Color.red(pixel) > 220 && Color.blue(pixel) > 220 && Color.green(pixel) < 30)
                    }
                } finally { decoded.recycle() }
            }
            assertFalse(bitmap.isRecycled)
        } finally { bitmap.recycle() }
    }

    @Test fun decodeAppliesExifBeforeEditingAndExportStripsLocationMetadata() {
        val bitmap = Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888)
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) bitmap.setPixel(x, y, if (y < 400) Color.RED else Color.BLUE)
        val temp = File.createTempFile("avatar-crop-", ".jpg", InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        try {
            temp.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
            ExifInterface(temp.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                setAttribute(ExifInterface.TAG_GPS_LATITUDE, "55/1,45/1,0/1")
                saveAttributes()
            }
            val source = decodeAvatarCropSource(temp.readBytes())
            try {
                assertTrue(source.width < source.height)
                assertTrue(Color.blue(source.getPixel(source.width / 4, source.height / 2)) > 180)
                assertTrue(Color.red(source.getPixel(source.width * 3 / 4, source.height / 2)) > 180)
                val encoded = exportAvatarCrop(source, AvatarCrop())
                assertNull(ExifInterface(encoded.inputStream()).getAttribute(ExifInterface.TAG_GPS_LATITUDE))
            } finally { source.recycle() }
        } finally { bitmap.recycle(); temp.delete() }
    }

    @Test fun invalidAndOversizedSourcesAreRejectedBeforeAnEditableBitmapIsReturned() {
        for (bytes in listOf(byteArrayOf(), byteArrayOf(1, 2, 3), ByteArray(MAX_AVATAR_CROP_INPUT_BYTES + 1))) {
            try { decodeAvatarCropSource(bytes); fail("Expected invalid photo") }
            catch (_: IllegalArgumentException) { }
        }
        val large = Bitmap.createBitmap(2400, 600, Bitmap.Config.ARGB_8888)
        try {
            val source = decodeAvatarCropSource(png(large))
            try { assertTrue(maxOf(source.width, source.height) <= 1200); assertEquals(4f, source.width.toFloat() / source.height, .01f) }
            finally { source.recycle() }
        } finally { large.recycle() }
    }

    private fun assertExportColor(source: Bitmap, crop: AvatarCrop, expected: Int) {
        val encoded = exportAvatarCrop(source, crop)
        val result = BitmapFactory.decodeByteArray(encoded, 0, encoded.size)
        try {
            val pixel = result.getPixel(256, 256)
            assertTrue(kotlin.math.abs(Color.red(expected) - Color.red(pixel)) <= 25)
            assertTrue(kotlin.math.abs(Color.green(expected) - Color.green(pixel)) <= 25)
            assertTrue(kotlin.math.abs(Color.blue(expected) - Color.blue(pixel)) <= 25)
        } finally { result.recycle() }
    }

    private fun png(bitmap: Bitmap) = ByteArrayOutputStream().use {
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)); it.toByteArray()
    }
}
