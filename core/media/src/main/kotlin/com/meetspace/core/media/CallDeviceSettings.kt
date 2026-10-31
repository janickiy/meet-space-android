package com.meetspace.core.media

/** Device-local defaults. Applying these must never activate a camera or microphone. */
data class CallDeviceSettings(
    val microphone: String = "DEFAULT",
    val microphoneVolume: Int = 80,
    val speakerVolume: Int = 100,
    val speaker: String = "DEFAULT",
    val noiseSuppression: Boolean = true,
    val camera: String = "DEFAULT",
    val hideRemoteVideo: Boolean = false,
)
