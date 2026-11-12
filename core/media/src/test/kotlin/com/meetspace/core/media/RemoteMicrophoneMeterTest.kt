package com.meetspace.core.media

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class RemoteMicrophoneMeterTest {
    @Test fun measuresBorrowedPcmWithoutChangingItOrRetainingIt() {
        val meter = RemoteMicrophoneMeter()
        val buffer = pcm(4096, -4096, 4096, -4096)
        val bytes = ByteArray(buffer.limit()) { buffer.get(it) }
        buffer.position(2)
        meter.onData(buffer.asReadOnlyBuffer(), 16, 48000, 1, 4, 0)
        assertEquals(.125f, meter.takeLevel(), 0f)
        assertEquals(2, buffer.position())
        assertArrayEquals(bytes, ByteArray(buffer.limit()) { buffer.get(it) })
        for (offset in 0 until buffer.limit() step 2) buffer.putShort(offset, 0)
        assertEquals(0f, meter.takeLevel(), 0f)
        meter.onData(buffer, 16, 48000, 1, 4, 0)
        assertEquals(0f, meter.takeLevel(), 0f)
    }

    @Test fun aggregatesShortBlockPeaksAndResetsForEachUiSample() {
        val meter = RemoteMicrophoneMeter()
        meter.onData(pcm(4096, -4096), 16, 48000, 1, 2, 0)
        meter.onData(pcm(16384, -16384), 16, 48000, 1, 2, 0)
        meter.onData(pcm(0, 0), 16, 48000, 1, 2, 0)
        assertEquals(.5f, meter.takeLevel(), 0f)
        assertEquals(0f, meter.takeLevel(), 0f)
    }

    @Test fun includesAllChannelsAndRejectsMalformedFrameSizes() {
        val meter = RemoteMicrophoneMeter()
        meter.onData(pcm(16384, 0, -16384, 0), 16, 48000, 2, 2, 0)
        assertEquals(.353553f, meter.takeLevel(), .00001f)
        val buffer = pcm(16384, -16384)
        meter.onData(buffer, 32, 48000, 1, 2, 0)
        meter.onData(buffer, 16, 48000, 0, 2, 0)
        meter.onData(buffer, 16, 48000, 2, Int.MAX_VALUE, 0)
        meter.onData(buffer, 16, 48000, 1, 3, 0)
        meter.onData(buffer, 16, 0, 1, 2, 0)
        assertEquals(0f, meter.takeLevel(), 0f)
    }

    @Test fun closeClearsPendingLevelAndIgnoresLateCallbacks() {
        val meter = RemoteMicrophoneMeter()
        val buffer = pcm(16384, -16384)
        meter.onData(buffer, 16, 48000, 1, 2, 0)
        meter.close()
        assertEquals(0f, meter.takeLevel(), 0f)
        meter.onData(buffer, 16, 48000, 1, 2, 0)
        assertEquals(0f, meter.takeLevel(), 0f)
    }

    private fun pcm(vararg samples: Int): ByteBuffer = ByteBuffer.allocateDirect(samples.size * 2)
        .order(ByteOrder.nativeOrder()).apply { samples.forEachIndexed { index, sample -> putShort(index * 2, sample.toShort()) } }
}
