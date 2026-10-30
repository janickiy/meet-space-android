package com.meetspace.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

internal const val MAX_AVATAR_CROP_INPUT_BYTES = 8 * 1024 * 1024
private const val MAX_SOURCE_EDGE = 1200
private const val OUTPUT_EDGE = 512
private const val MAX_OUTPUT_BYTES = 2 * 1024 * 1024

/** The caller owns the returned bitmap and must recycle it after all previews are detached. */
internal fun decodeAvatarCropSource(bytes: ByteArray): Bitmap {
    require(bytes.isNotEmpty() && bytes.size <= MAX_AVATAR_CROP_INPUT_BYTES) { "Выберите изображение размером до 8 МБ." }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val width = bounds.outWidth
    val height = bounds.outHeight
    require(bounds.outMimeType in setOf("image/jpeg", "image/png", "image/webp")) { "Выберите изображение JPG, PNG или WebP." }
    require(width in 1..32_768 && height in 1..32_768 && width.toLong() * height <= 40_000_000L) {
        "Не удалось прочитать фото или его разрешение слишком велико."
    }
    // A power-of-two decode bounds the temporary allocation before rotation or scaling.
    var sample = 1
    while ((maxOf(width, height) + sample - 1) / sample > MAX_SOURCE_EDGE) sample *= 2
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }) ?: throw IllegalArgumentException("Не удалось прочитать фото.")
    val owned = linkedSetOf(decoded)
    var result: Bitmap? = null
    try {
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
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
        val oriented = if (matrix.isIdentity) decoded else {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also(owned::add)
        }
        result = oriented
        return oriented
    } finally { owned.filterNot { it === result }.forEach(Bitmap::recycle) }
}

/** Encode exactly the rectangle shown by the editor; the circular guide is a preview mask only. */
internal fun exportAvatarCrop(source: Bitmap, crop: AvatarCrop): ByteArray {
    require(!source.isRecycled && source.width in 1..MAX_SOURCE_EDGE && source.height in 1..MAX_SOURCE_EDGE) {
        "Не удалось подготовить изображение. Выберите фото снова."
    }
    val output = Bitmap.createBitmap(OUTPUT_EDGE, OUTPUT_EDGE, Bitmap.Config.ARGB_8888)
    return try {
        val metrics = avatarCropMetrics(source.width, source.height, OUTPUT_EDGE.toFloat(), crop)
        Canvas(output).apply {
            // JPEG has no alpha. Transparent PNG/WebP regions use a predictable neutral background.
            drawColor(Color.WHITE)
            drawBitmap(source, null, RectF(metrics.left, metrics.top, metrics.left + metrics.scaledWidth,
                metrics.top + metrics.scaledHeight), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        }
        ByteArrayOutputStream().use { encoded ->
            require(output.compress(Bitmap.CompressFormat.JPEG, 90, encoded)) { "Не удалось сохранить фото." }
            require(encoded.size() in 1..MAX_OUTPUT_BYTES) { "Не удалось уменьшить изображение до 2 МБ." }
            encoded.toByteArray()
        }
    } finally { output.recycle() }
}
