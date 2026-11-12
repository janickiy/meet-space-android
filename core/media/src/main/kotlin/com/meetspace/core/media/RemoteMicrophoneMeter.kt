package com.meetspace.core.media

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt
import org.webrtc.AudioTrackSink

/** Decoded microphone level before AudioTrack's playback volume or the device mixer.
 * WebRTC owns the PCM buffer. Only a scalar RMS peak survives this callback; audio is
 * never copied, retained, modified, recorded or posted onto another thread. */
internal class RemoteMicrophoneMeter : AudioTrackSink {
    private val active = AtomicBoolean(true)
    private val meter = LocalMicrophoneMeter()

    override fun onData(buffer: ByteBuffer, bitsPerSample: Int, sampleRate: Int,
        channels: Int, frames: Int, timestamp: Long) {
        if (!active.get() || bitsPerSample != 16 || sampleRate <= 0 || channels !in 1..8 || frames <= 0) return
        val samples = frames.toLong() * channels
        if (samples > buffer.limit() / Short.SIZE_BYTES) return
        buffer.order(ByteOrder.nativeOrder())
        var energy = 0.0
        for (index in 0 until samples.toInt()) {
            val sample = buffer.getShort(index * Short.SIZE_BYTES).toDouble()
            energy += sample * sample
        }
        meter.record((sqrt(energy / samples) / 32768.0).toFloat())
    }

    fun takeLevel(): Float = meter.takeLevel().let { if (active.get()) it else 0f }
    fun close() { active.set(false); meter.takeLevel() }
}
