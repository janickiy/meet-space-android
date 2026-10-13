package com.meetspace.app

import android.os.Build
import android.system.Os
import android.system.OsConstants
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.webrtc.Logging
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.JavaAudioDeviceModule

/** Loader/runtime smoke only: no peer, network session, camera, track or microphone capture. */
class NativeLibrariesSmokeTest {
    @Test fun bundledNativeLibrariesLoadAndWebRtcFactoryDisposes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val pageSize = Os.sysconf(OsConstants._SC_PAGESIZE)
        assertTrue("Kernel page size must be readable", pageSize > 0)
        InstrumentationRegistry.getArguments().getString("expectedPageSize")?.let {
            assertEquals("Runtime must use the explicitly requested kernel page size", it.toLong(), pageSize)
        }
        val libraries = listOf("androidx.graphics.path", "datastore_shared_counter", "jingle_peerconnection_so")
        libraries.forEach(System::loadLibrary)
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .setInjectableLogger({ _, _, _ -> }, Logging.Severity.LS_NONE)
            .createInitializationOptions())
        val audio = JavaAudioDeviceModule.builder(context).createAudioDeviceModule()
        try {
            audio.setAudioRecordEnabled(false)
            val factory = PeerConnectionFactory.builder().setAudioDeviceModule(audio).createPeerConnectionFactory()
            factory.dispose()
        } finally {
            audio.release()
        }
        val evidence = JSONObject()
            .put("pageSize", pageSize)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            .put("versionName", BuildConfig.VERSION_NAME)
            .put("librariesLoaded", JSONArray(libraries))
            .put("webRtcFactoryCreatedAndDisposed", true)
            .put("peerOrCaptureStarted", false)
        File(context.cacheDir, "stage08-native-runtime.json").writeText(evidence.toString(2))
    }
}
