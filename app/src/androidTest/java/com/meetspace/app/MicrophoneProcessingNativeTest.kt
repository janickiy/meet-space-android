package com.meetspace.app

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.webrtc.AudioProcessingFactory
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Logging
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * Exercises the real SDK APM owner across call factory teardown/recreation.
 * No peer, network, microphone capture or application-data reset is involved.
 * Signal amplitude and physical microphone behavior are covered separately.
 */
class MicrophoneProcessingNativeTest {
    @Test fun processWideMicrophoneProcessorSurvivesThreeNativeFactoryLifetimes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .setInjectableLogger({ _, _, _ -> }, Logging.Severity.LS_NONE)
            .createInitializationOptions())
        val processing = productionAudioProcessing()
        val apm = nativeApm(processing)
        assertNotEquals("The production processor must have a real native APM owner", 0L, apm)

        repeat(3) { cycle ->
            val adm = JavaAudioDeviceModule.builder(context)
                .setUseHardwareAcousticEchoCanceler(true)
                .setUseHardwareNoiseSuppressor(true)
                .createAudioDeviceModule()
            var factory: PeerConnectionFactory? = null
            var source: AudioSource? = null
            var track: AudioTrack? = null
            try {
                adm.setAudioRecordEnabled(false)
                adm.setMicrophoneMute(true)
                factory = PeerConnectionFactory.builder()
                    .setAudioDeviceModule(adm)
                    .setAudioProcessingFactory(productionAudioProcessing())
                    .createPeerConnectionFactory()
                source = factory.createAudioSource(MediaConstraints())
                track = factory.createAudioTrack("microphone-owner-$cycle", source)
                assertEquals("microphone-owner-$cycle", track.id())
                assertSame("Recreating a call must reuse the SDK's process-wide owner",
                    processing, productionAudioProcessing())
                assertEquals(apm, nativeApm(processing))
            } finally {
                // Preserve native ownership order even when an assertion fails.
                try { track?.dispose() }
                finally {
                    try { source?.dispose() }
                    finally {
                        try { factory?.dispose() }
                        finally { adm.release() }
                    }
                }
            }
            assertSame(processing, productionAudioProcessing())
            assertEquals("The shared APM must remain valid after factory disposal", apm, nativeApm(processing))
        }
    }

    private fun productionAudioProcessing(): AudioProcessingFactory {
        // The core module keeps this SDK owner internal; reflection avoids exposing
        // a production test API solely for the app's native instrumentation test.
        val type = Class.forName("com.meetspace.core.media.MicrophoneAudioProcessing")
        val owner = type.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
        return type.getDeclaredMethod("getFactory").apply { isAccessible = true }.invoke(owner) as AudioProcessingFactory
    }

    private fun nativeApm(factory: AudioProcessingFactory): Long =
        factory.javaClass.getDeclaredField("apmPtr").apply { isAccessible = true }.getLong(factory)
}
