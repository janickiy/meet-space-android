package com.meetspace.core.media

import org.webrtc.RtpSender
import org.webrtc.VideoSource

/** ScreenCapturerAndroid ignores its framerate argument in the pinned SDK. */
object ScreenShareVideoPolicy {
    const val MAX_FPS = 15

    fun apply(source: VideoSource) {
        // Undefined aspect ratios and absent pixel limits preserve the captured image.
        // The three-int overload would add a crop to the requested aspect ratio.
        source.adaptOutputFormat(VideoSource.AspectRatio.UNDEFINED, null,
            VideoSource.AspectRatio.UNDEFINED, null, MAX_FPS)
    }

    /** A stopped, receive-only slot can have no encodings until its next SDP answer. */
    fun applySenderLimit(sender: RtpSender): Boolean {
        val parameters = sender.parameters
        if (parameters.encodings.isEmpty()) return false
        parameters.encodings.forEach { it.maxFramerate = MAX_FPS }
        check(sender.setParameters(parameters)) { "Screen sender rejected the framerate limit" }
        val applied = sender.parameters.encodings
        check(applied.isNotEmpty() && applied.all { it.maxFramerate == MAX_FPS }) {
            "Screen sender did not retain the framerate limit"
        }
        return true
    }

    fun captureSize(width: Int, height: Int): Pair<Int, Int> {
        require(width > 0 && height > 0)
        val scale = minOf(1.0, 1280.0 / width, 720.0 / height)
        return (width * scale).toInt().coerceAtLeast(2) / 2 * 2 to
            (height * scale).toInt().coerceAtLeast(2) / 2 * 2
    }
}
