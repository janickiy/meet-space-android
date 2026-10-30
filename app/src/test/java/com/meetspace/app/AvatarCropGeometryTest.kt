package com.meetspace.app

import org.junit.Assert.*
import org.junit.Test

class AvatarCropGeometryTest {
    @Test fun coverPreservesAspectAndNoSliderEndpointExposesAnEmptyMargin() {
        for ((width, height) in listOf(1200 to 600, 600 to 1200, 1200 to 1200, 31 to 1200, 1200 to 31)) {
            for (zoom in listOf(1f, 1.75f, 3f)) for (x in listOf(-1f, 0f, 1f)) for (y in listOf(-1f, 0f, 1f)) {
                val m = avatarCropMetrics(width, height, 320f, AvatarCrop(zoom, x, y))
                assertEquals(width.toFloat() / height, m.scaledWidth / m.scaledHeight, .0001f)
                assertTrue(m.left <= .001f); assertTrue(m.top <= .001f)
                assertTrue(m.left + m.scaledWidth >= 319.999f)
                assertTrue(m.top + m.scaledHeight >= 319.999f)
            }
        }
    }

    @Test fun horizontalAndVerticalControlsMatchThePrototypeCoverGeometry() {
        val landscape = avatarCropMetrics(1200, 600, 320f, AvatarCrop())
        assertEquals(640f, landscape.scaledWidth, .001f)
        assertEquals(320f, landscape.scaledHeight, .001f)
        assertEquals(160f, landscape.maxOffsetX, .001f)
        assertEquals(0f, landscape.maxOffsetY, 0f)
        assertEquals(0f, avatarCropMetrics(1200, 600, 320f, AvatarCrop(x = 1f)).left, .001f)
        assertEquals(-320f, avatarCropMetrics(1200, 600, 320f, AvatarCrop(x = -1f)).left, .001f)
        val portrait = avatarCropMetrics(600, 1200, 320f, AvatarCrop())
        assertEquals(0f, portrait.maxOffsetX, 0f)
        assertEquals(160f, portrait.maxOffsetY, .001f)
    }

    @Test fun draggingConvertsPixelsToNormalizedControlsAndClampsAtTheImageEdges() {
        val first = AvatarCrop().pan(80f, 40f, 1200, 600, 320f)
        assertEquals(.5f, first.x, .0001f)
        assertEquals(0f, first.y, .0001f)
        assertEquals(1f, first.pan(10_000f, 10_000f, 1200, 600, 320f).x, .0001f)
        assertEquals(-1f, first.pan(-10_000f, 0f, 1200, 600, 320f).x, .0001f)
        val zoomed = AvatarCrop(zoom = 2f).pan(160f, 160f, 1200, 1200, 320f)
        assertEquals(1f, zoomed.x, .0001f); assertEquals(1f, zoomed.y, .0001f)
    }

    @Test fun theSameControlsProduceTheSameCropAcrossPhoneTabletAndExportSizes() {
        val crop = AvatarCrop(2.3f, -.6f, .4f)
        val a = avatarCropMetrics(900, 1200, 240f, crop)
        val b = avatarCropMetrics(900, 1200, 512f, crop)
        assertEquals(a.left / 240f, b.left / 512f, .0001f)
        assertEquals(a.top / 240f, b.top / 512f, .0001f)
        assertEquals(a.scaledWidth / 240f, b.scaledWidth / 512f, .0001f)
    }

    @Test fun malformedControlsNormalizeAndInvalidDimensionsAreRejected() {
        assertEquals(AvatarCrop(3f, -1f, 1f), AvatarCrop(Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 4f).normalized())
        assertEquals(AvatarCrop(), AvatarCrop(Float.NaN, Float.NaN, Float.NaN).normalized())
        for (width in listOf(0, -1)) {
            try { avatarCropMetrics(width, 100, 320f, AvatarCrop()); fail("Expected invalid source bounds") }
            catch (_: IllegalArgumentException) { }
        }
        for (viewport in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            try { avatarCropMetrics(100, 100, viewport, AvatarCrop()); fail("Expected invalid viewport") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
