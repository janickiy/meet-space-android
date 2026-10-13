package com.meetspace.app

import com.meetspace.app.ui.AccountDevicePreferences
import com.meetspace.app.ui.SpeakerPreference
import com.meetspace.core.media.AudioRoute
import com.meetspace.core.media.CallPhase
import com.meetspace.core.media.CallState
import org.junit.Assert.*
import org.junit.Test

class CallPreferencesTest {
    private val prejoin = CallState(CallPhase.PREJOIN)

    @Test fun initialPreferencesJoinWithoutPublishingDevices() {
        assertEquals(CallJoinDevices(false, false), preferredJoinDevices(prejoin, AccountDevicePreferences(), false, false))
    }

    @Test fun manualPrejoinChoicesOverrideStoredDefaultsInBothDirections() {
        val defaultsOn = AccountDevicePreferences(autoMute = false, cameraOff = false)
        assertEquals(CallJoinDevices(true, true), preferredJoinDevices(prejoin, defaultsOn, false, false))
        assertEquals(CallJoinDevices(false, false), preferredJoinDevices(prejoin, defaultsOn, true, true))
        assertEquals(CallJoinDevices(true, true), preferredJoinDevices(prejoin.copy(microphone = true, camera = true), AccountDevicePreferences(), true, true))
    }

    @Test fun blockedDevicesStayOffRegardlessOfStoredOrManualChoices() {
        val blocked = prejoin.copy(microphone = true, camera = true, microphoneBlocked = true, cameraBlocked = true)
        val defaultsOn = AccountDevicePreferences(autoMute = false, cameraOff = false)
        assertEquals(CallJoinDevices(false, false), preferredJoinDevices(blocked, defaultsOn, false, false))
        assertEquals(CallJoinDevices(false, false), preferredJoinDevices(blocked, defaultsOn, true, true))
    }

    @Test fun defaultRoutingAndMissingPreferredDeviceLeaveSystemChoiceAlone() {
        val routes = listOf(AudioRoute(11, "Динамик", true))
        assertNull(preferredAudioRoute(SpeakerPreference.DEFAULT, routes))
        assertNull(preferredAudioRoute(SpeakerPreference.HEADSET, routes))
        assertNull(preferredAudioRoute(SpeakerPreference.SPEAKER, emptyList()))
    }

    @Test fun currentDeviceIdsAreResolvedFreshForEachCall() {
        assertEquals(11, preferredAudioRoute(SpeakerPreference.SPEAKER, listOf(AudioRoute(11, "Динамик", false)))?.id)
        assertEquals(42, preferredAudioRoute(SpeakerPreference.SPEAKER, listOf(AudioRoute(42, "Динамик", false)))?.id)
    }

    @Test fun selectedHeadsetWinsOverAnotherAvailableHeadset() {
        val wired = AudioRoute(2, "Гарнитура", false)
        val bluetooth = AudioRoute(9, "Bluetooth", true)
        assertEquals(bluetooth, preferredAudioRoute(SpeakerPreference.HEADSET, listOf(wired, bluetooth)))
    }
}
