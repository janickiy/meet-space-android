package com.meetspace.core.media

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.webrtc.AudioProcessingFactory
import org.webrtc.ExternalAudioProcessingFactory

/** Attenuates the mono microphone after WebRTC's AGC and before encoding. */
internal class MicrophoneGainProcessor : ExternalAudioProcessingFactory.AudioProcessing {
    override fun initialize(sampleRateHz: Int, numChannels: Int) = Unit
    override fun reset(newRate: Int) = Unit

    override fun process(numBands: Int, numFrames: Int, buffer: ByteBuffer) {
        // SDK 144.7559.15 passes channels()[0] as native-endian float PCM, not PCM16
        // or interleaved bands. At 8 kHz its buffer capacity exceeds numFrames.
        // The pointer belongs to WebRTC and is only valid during this callback.
        if (numFrames <= 0 || numFrames > buffer.limit() / Float.SIZE_BYTES || buffer.isReadOnly) return
        buffer.order(ByteOrder.nativeOrder())
        var offset = 0
        repeat(numFrames) {
            buffer.putFloat(offset, buffer.getFloat(offset) * 0.8f)
            offset += Float.SIZE_BYTES
        }
    }
}

/** The SDK's external APM is process-global, even across PeerConnection factories. */
internal object MicrophoneAudioProcessing {
    // Kotlin initializes this object on first use, after WebRTC initialization.
    // Keep Java SDK types out of generic lazy/apply inference for Android lint's K2 UAST.
    val factory: AudioProcessingFactory = createFactory()

    private fun createFactory(): AudioProcessingFactory {
        val processing = ExternalAudioProcessingFactory()
        processing.setCapturePostProcessing(MicrophoneGainProcessor())
        processing.setBypassFlagForCapturePost(false)
        return processing
    }

    // Do not destroy this process-wide owner on call teardown: nativeDestroy in
    // 144.7559.15 dereferences a null processor and leaves its global pointer
    // dangling. Each PeerConnectionFactory owns/releases its own APM reference;
    // this bounded singleton keeps no Context, tracks or borrowed audio buffers.
}
