package com.meetspace.core.media

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MicrophoneGainProcessorTest {
    @Test fun microphoneRmsDropsByTwentyPercentWithoutChangingTheWaveformAtEveryCaptureRate() {
        val processor = MicrophoneGainProcessor()
        for (rate in listOf(8_000, 16_000, 32_000, 48_000)) {
            processor.initialize(rate, 1)
            processor.reset(rate)
            val frames = rate / 100
            val bands = maxOf(1, rate / 16_000)
            val buffer = ByteBuffer.allocateDirect(160 * bands * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
            val original = FloatArray(frames) { frame ->
                (12_000 * sin(2 * PI * 440 * frame / rate) + 3_000 * sin(2 * PI * 997 * frame / rate)).toFloat()
            }
            original.forEachIndexed { index, sample -> buffer.putFloat(index * Float.SIZE_BYTES, sample) }
            // WebRTC exposes capacity for 160 floats at 8 kHz, but only 80 are audio.
            if (buffer.capacity() > frames * Float.SIZE_BYTES) buffer.putFloat(frames * Float.SIZE_BYTES, 12_345f)
            buffer.position(Float.SIZE_BYTES)
            buffer.order(ByteOrder.BIG_ENDIAN) // JNI buffers do not arrive with native byte order set.

            processor.process(bands, frames, buffer)

            val processed = FloatArray(frames) { buffer.getFloat(it * Float.SIZE_BYTES) }
            val beforeRms = sqrt(original.sumOf { it.toDouble() * it } / frames)
            val afterRms = sqrt(processed.sumOf { it.toDouble() * it } / frames)
            assertEquals("RMS at $rate Hz", 0.8, afterRms / beforeRms, 0.000001)
            assertTrue(processed.all { it.isFinite() })
            original.indices.forEach { index ->
                assertEquals("No distortion at $rate Hz, frame $index", original[index].toDouble(), processed[index] / 0.8, 0.003)
            }
            assertEquals(Float.SIZE_BYTES, buffer.position())
            if (buffer.capacity() > frames * Float.SIZE_BYTES) assertEquals(12_345f, buffer.getFloat(frames * Float.SIZE_BYTES), 0f)
        }
    }

    @Test fun muteAndRestartDoNotAccumulateAttenuationOrRememberBorrowedAudio() {
        val processor = MicrophoneGainProcessor()
        processor.initialize(48_000, 1)
        val muted = ByteBuffer.allocateDirect(480 * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
        processor.process(3, 480, muted)
        repeat(480) { assertEquals(0f, muted.getFloat(it * Float.SIZE_BYTES), 0f) }

        processor.reset(16_000)
        val restarted = ByteBuffer.allocateDirect(160 * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
        repeat(160) { restarted.putFloat(it * Float.SIZE_BYTES, if (it % 2 == 0) 20_000f else -20_000f) }
        processor.process(1, 160, restarted)
        repeat(160) { assertEquals(if (it % 2 == 0) 16_000f else -16_000f, restarted.getFloat(it * Float.SIZE_BYTES), 0f) }
        repeat(480) { assertEquals(0f, muted.getFloat(it * Float.SIZE_BYTES), 0f) }
    }
}
