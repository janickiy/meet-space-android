package com.meetspace.app

import com.meetspace.app.ui.microphoneFillFraction
import org.junit.Assert.*
import org.junit.Test

class MicrophoneIntensityTest {
    @Test fun silenceAndInvalidLevelsLeaveTheMicrophoneEmpty() {
        for (level in listOf(0f, .002f, .011f, -.5f, Float.NaN, Float.POSITIVE_INFINITY))
            assertEquals(0f, microphoneFillFraction(level), 0f)
    }

    @Test fun microphoneFillGrowsWithActualVoiceVolumeAndIsBounded() {
        val levels = listOf(.012f, .025f, .05f, .1f, .25f)
        assertTrue(levels.map(::microphoneFillFraction).zipWithNext().all { (a, b) -> a < b })
        assertEquals(0f, microphoneFillFraction(.012f), .00001f)
        assertEquals(1f, microphoneFillFraction(.25f), .00001f)
        assertEquals(1f, microphoneFillFraction(1f), .00001f)
        assertEquals(1f, microphoneFillFraction(Float.MAX_VALUE), .00001f)
    }
}
