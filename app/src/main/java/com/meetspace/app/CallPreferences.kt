package com.meetspace.app

import com.meetspace.app.ui.AccountDevicePreferences
import com.meetspace.app.ui.SpeakerPreference
import com.meetspace.core.media.AudioRoute
import com.meetspace.core.media.CallState

internal data class CallJoinDevices(val microphone: Boolean, val camera: Boolean)

/** A manual prejoin choice wins over the stored default, including explicitly switching off. */
internal fun preferredJoinDevices(state: CallState, preferences: AccountDevicePreferences, microphoneEdited: Boolean, cameraEdited: Boolean) = CallJoinDevices(
    microphone = !state.microphoneBlocked && if (microphoneEdited) state.microphone else !preferences.autoMute,
    camera = !state.cameraBlocked && if (cameraEdited) state.camera else !preferences.cameraOff,
)

/** Route ids are transient. Resolve the preference against the engine's current device list. */
internal fun preferredAudioRoute(preference: SpeakerPreference, routes: List<AudioRoute>): AudioRoute? {
    val matches = when (preference) {
        SpeakerPreference.DEFAULT -> return null
        SpeakerPreference.SPEAKER -> routes.filter { it.name == "Динамик" }
        SpeakerPreference.HEADSET -> routes.filter { it.name == "Гарнитура" || it.name == "Bluetooth" }
    }
    return matches.firstOrNull { it.selected } ?: matches.firstOrNull()
}
