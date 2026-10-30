package com.meetspace.app

import kotlin.math.max

/** Normalized controls match the prototype and stay independent of the editor's pixel size. */
internal data class AvatarCrop(val zoom: Float = 1f, val x: Float = 0f, val y: Float = 0f) {
    fun normalized() = AvatarCrop(
        zoom = if (zoom.isNaN()) 1f else zoom.coerceIn(1f, 3f),
        x = if (x.isNaN()) 0f else x.coerceIn(-1f, 1f),
        y = if (y.isNaN()) 0f else y.coerceIn(-1f, 1f),
    )

    /** Pointer deltas are measured in the same pixel coordinate system as viewport. */
    fun pan(deltaX: Float, deltaY: Float, sourceWidth: Int, sourceHeight: Int, viewport: Float): AvatarCrop {
        val crop = normalized()
        val metrics = avatarCropMetrics(sourceWidth, sourceHeight, viewport, crop)
        return crop.copy(
            x = if (metrics.maxOffsetX > 0f && deltaX.isFinite()) crop.x + deltaX / metrics.maxOffsetX else 0f,
            y = if (metrics.maxOffsetY > 0f && deltaY.isFinite()) crop.y + deltaY / metrics.maxOffsetY else 0f,
        ).normalized()
    }
}

internal data class AvatarCropMetrics(
    val scale: Float,
    val scaledWidth: Float,
    val scaledHeight: Float,
    val maxOffsetX: Float,
    val maxOffsetY: Float,
    val left: Float,
    val top: Float,
)

/** Cover a square without stretching; normalized offsets can never expose an empty margin. */
internal fun avatarCropMetrics(sourceWidth: Int, sourceHeight: Int, viewport: Float, crop: AvatarCrop): AvatarCropMetrics {
    require(sourceWidth > 0 && sourceHeight > 0 && viewport.isFinite() && viewport > 0f)
    val normalized = crop.normalized()
    val scale = max(viewport / sourceWidth, viewport / sourceHeight) * normalized.zoom
    val width = sourceWidth * scale
    val height = sourceHeight * scale
    require(scale.isFinite() && width.isFinite() && height.isFinite())
    // E.g. 600 * (320 / 600f) can be 320.00003: a fitted axis must still be
    // disabled rather than turning a pointer delta into an enormous normalized pan.
    fun maxOffset(edge: Float): Float {
        val overflow = (edge - viewport).coerceAtLeast(0f)
        return if (overflow <= viewport * .000001f) 0f else overflow / 2f
    }
    val maxX = maxOffset(width)
    val maxY = maxOffset(height)
    return AvatarCropMetrics(scale, width, height, maxX, maxY,
        left = -maxX + normalized.x * maxX,
        top = -maxY + normalized.y * maxY)
}
