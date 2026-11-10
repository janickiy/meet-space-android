package com.meetspace.core.media

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sqrt
import org.webrtc.AudioProcessingFactory
import org.webrtc.ExternalAudioProcessingFactory

/** Coalesces the 10 ms audio blocks between UI updates without retaining audio or posting from capture. */
internal class LocalMicrophoneMeter {
    private val peak = AtomicInteger()

    fun record(rms: Float) {
        if (rms.isFinite() && rms > 0f) {
            // Positive IEEE 754 floats sort in the same order as their bit patterns.
            peak.accumulateAndGet(rms.coerceAtMost(1f).toRawBits()) { old, new -> maxOf(old, new) }
        }
    }

    fun takeLevel(): Float = Float.fromBits(peak.getAndSet(0))
}

/** Attenuates the mono microphone after WebRTC's AGC and before encoding. */
internal class MicrophoneGainProcessor(
    private val meter: () -> LocalMicrophoneMeter? = { null },
    private val gain: () -> Float = { .8f },
) : ExternalAudioProcessingFactory.AudioProcessing {
    override fun initialize(sampleRateHz: Int, numChannels: Int) = Unit
    override fun reset(newRate: Int) = Unit

    override fun process(numBands: Int, numFrames: Int, buffer: ByteBuffer) {
        // SDK 144.7559.15 passes channels()[0] as native-endian float PCM, not PCM16
        // or interleaved bands. At 8 kHz its buffer capacity exceeds numFrames.
        // The pointer belongs to WebRTC and is only valid during this callback.
        if (numFrames <= 0 || numFrames > buffer.limit() / Float.SIZE_BYTES || buffer.isReadOnly) return
        buffer.order(ByteOrder.nativeOrder())
        val factor = gain().let { if (it.isFinite()) it.coerceIn(0f, 1f) else 0f }
        val levelMeter = meter()
        var energy = 0.0
        var offset = 0
        repeat(numFrames) {
            val sample = buffer.getFloat(offset) * factor
            buffer.putFloat(offset, sample)
            if (levelMeter != null) energy += sample.toDouble() * sample
            offset += Float.SIZE_BYTES
        }
        // Native float samples use the PCM16 amplitude range.
        levelMeter?.record((sqrt(energy / numFrames) / 32768.0).toFloat())
    }
}

/** The SDK's external APM is process-global, even across PeerConnection factories. */
internal object MicrophoneAudioProcessing {
    @Volatile var microphoneVolume: Int = 80
    private val activeMeter = AtomicReference<LocalMicrophoneMeter?>()

    fun startMeter(meter: LocalMicrophoneMeter) { activeMeter.set(meter) }
    fun stopMeter(meter: LocalMicrophoneMeter) { activeMeter.compareAndSet(meter, null) }
    // Kotlin initializes this object on first use, after WebRTC initialization.
    // Keep Java SDK types out of generic lazy/apply inference for Android lint's K2 UAST.
    val factory: AudioProcessingFactory = createFactory()

    private fun createFactory(): AudioProcessingFactory {
        val processing = ExternalAudioProcessingFactory()
        processing.setCapturePostProcessing(MicrophoneGainProcessor(activeMeter::get) { microphoneVolume.coerceIn(0, 100) / 100f })
        processing.setBypassFlagForCapturePost(false)
        return processing
    }

    // Do not destroy this process-wide owner on call teardown: nativeDestroy in
    // 144.7559.15 dereferences a null processor and leaves its global pointer
    // dangling. Each PeerConnectionFactory owns/releases its own APM reference;
    // this bounded singleton keeps no Context, tracks or borrowed audio buffers.
}
