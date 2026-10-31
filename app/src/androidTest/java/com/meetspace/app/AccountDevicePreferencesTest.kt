package com.meetspace.app

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.meetspace.app.ui.AccountDevicePreferences
import com.meetspace.app.ui.SpeakerPreference
import com.meetspace.app.ui.MicrophonePreference
import com.meetspace.app.ui.CameraPreference
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class AccountDevicePreferencesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "test-device-preferences-${UUID.randomUUID()}"
    private val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    @After fun cleanUp() { context.deleteSharedPreferences(name) }

    @Test fun defaultsKeepDevicesOffAndUseSystemRouting() {
        assertEquals(AccountDevicePreferences(), AccountDevicePreferencesStore(preferences).read())
    }

    @Test fun savedPreferencesSurviveAStoreAndContextRecreation() {
        val expected = AccountDevicePreferences(autoMute = false, cameraOff = false, showSelf = false,
            hideParticipantVideo = true, speaker = SpeakerPreference.BLUETOOTH, textScale = 1.3f,
            microphone = MicrophonePreference.HEADSET, microphoneVolume = 25, speakerVolume = 65, signalVolume = 0,
            signalOutput = SpeakerPreference.SPEAKER, noiseSuppression = false, camera = CameraPreference.BACK)
        AccountDevicePreferencesStore(preferences).write(expected)
        val recreatedContext = context.createPackageContext(context.packageName, 0)
        assertEquals(expected, AccountDevicePreferencesStore(recreatedContext.getSharedPreferences(name, Context.MODE_PRIVATE)).read())
    }

    @Test fun newFieldsRetainLegacyAudioLevelsAndRejectUnknownDevices() {
        preferences.edit().putBoolean("autoMute", false).putString("microphone", "invalid")
            .putString("camera", "disconnected-id").putString("signalOutput", "invalid").commit()
        val loaded = AccountDevicePreferencesStore(preferences).read()
        assertEquals(80, loaded.microphoneVolume)
        assertEquals(100, loaded.speakerVolume)
        assertEquals(100, loaded.signalVolume)
        assertFalse(loaded.autoMute)
        assertTrue(loaded.noiseSuppression)
        assertEquals(MicrophonePreference.DEFAULT, loaded.microphone)
        assertEquals(CameraPreference.DEFAULT, loaded.camera)
        assertEquals(SpeakerPreference.DEFAULT, loaded.signalOutput)
        preferences.edit().putInt("microphoneVolume", -20).putInt("speakerVolume", 300).putInt("signalVolume", -1).commit()
        val bounded = AccountDevicePreferencesStore(preferences).read()
        assertEquals(0, bounded.microphoneVolume)
        assertEquals(100, bounded.speakerVolume)
        assertEquals(0, bounded.signalVolume)
    }

    @Test fun unknownSpeakerValueFallsBackToSystemAndScaleIsBounded() {
        preferences.edit().putString("speaker", "REMOVED_DEVICE_ID_234").putFloat("textScale", 9f).commit()
        val loaded = AccountDevicePreferencesStore(preferences).read()
        assertEquals(SpeakerPreference.DEFAULT, loaded.speaker)
        assertEquals(2f, loaded.textScale)
        AccountDevicePreferencesStore(preferences).write(loaded.copy(textScale = Float.NaN))
        assertEquals(1f, AccountDevicePreferencesStore(preferences).read().textScale)
    }
}
