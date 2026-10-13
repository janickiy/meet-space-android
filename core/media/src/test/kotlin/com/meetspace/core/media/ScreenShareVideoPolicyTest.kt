package com.meetspace.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenShareVideoPolicyTest {
    @Test fun landscapeCaptureKeepsAspectWithinExistingBounds() {
        assertEquals(1280 to 720, ScreenShareVideoPolicy.captureSize(1920, 1080))
        assertEquals(1280 to 720, ScreenShareVideoPolicy.captureSize(3840, 2160))
        assertEquals(640 to 360, ScreenShareVideoPolicy.captureSize(640, 360))
    }

    @Test fun portraitAndUnusualDisplaysUseUniformScaleAndEvenDimensions() {
        for ((width, height) in listOf(1080 to 1920, 2400 to 1080, 1080 to 2400, 1194 to 834, 834 to 1194, 1921 to 1081)) {
            val (capturedWidth, capturedHeight) = ScreenShareVideoPolicy.captureSize(width, height)
            assertTrue(capturedWidth in 2..1280 && capturedHeight in 2..720)
            assertEquals(0, capturedWidth % 2); assertEquals(0, capturedHeight % 2)
            val scale = minOf(1.0, 1280.0 / width, 720.0 / height)
            assertTrue("Only the existing even-pixel rounding may alter width", width * scale - capturedWidth in 0.0..2.0)
            assertTrue("Only the existing even-pixel rounding may alter height", height * scale - capturedHeight in 0.0..2.0)
        }
        assertEquals(404 to 720, ScreenShareVideoPolicy.captureSize(1080, 1920))
    }

    @Test(expected = IllegalArgumentException::class)
    fun missingCaptureDimensionsAreRejected() { ScreenShareVideoPolicy.captureSize(0, 1080) }
}
