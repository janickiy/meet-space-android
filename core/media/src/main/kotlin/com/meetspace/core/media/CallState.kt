package com.meetspace.core.media

import org.webrtc.EglBase
import org.webrtc.VideoSink
import org.webrtc.VideoTrack

enum class CallPhase { IDLE, PREJOIN, CONNECTING, CONNECTED, RECONNECTING, FAILED, LEAVING }
data class CallParticipant(val id: String, val name: String, val online: Boolean, val microphone: Boolean, val camera: Boolean)
data class CallVideo(val id: String, val participantId: String, val screen: Boolean, val feed: VideoFeed)
data class CallDiagnostics(val relay: Boolean = false, val receivedBytes: Long = 0, val decodedFrames: Long = 0, val sentBytes: Long = 0, val receivedAudioBytes: Long = 0, val receivedVideoBytes: Long = 0,
    val screenFramesEncoded: Long = 0, val screenEncodedFps: Double? = null)
data class AudioRoute(val id: Int, val name: String, val selected: Boolean)
data class CallState(
    val phase: CallPhase = CallPhase.IDLE,
    val conferenceId: String = "", val title: String = "", val participantId: String = "",
    val status: String = "", val error: String? = null,
    val microphone: Boolean = false, val camera: Boolean = false, val sharing: Boolean = false,
    val microphoneBlocked: Boolean = false, val cameraBlocked: Boolean = false, val screenBlocked: Boolean = false,
    val localVideo: VideoFeed? = null, val localScreen: VideoFeed? = null,
    val participants: List<CallParticipant> = emptyList(), val videos: List<CallVideo> = emptyList(),
    val routes: List<AudioRoute> = emptyList(), val diagnostics: CallDiagnostics = CallDiagnostics(),
    // Events invalidate REST projections; untrusted socket message bodies never enter the UI.
    val discussionRevision: Long = 0, val recordingRevision: Long = 0,
    val directCallId: String? = null,
) { val visible: Boolean get() = phase != CallPhase.IDLE }

/** Rendering never races a disposed native track during rotation, reconnect or leave. */
class VideoFeed internal constructor(private val track: VideoTrack, val eglContext: EglBase.Context) {
    private val sinks = mutableSetOf<VideoSink>()
    private var closed = false
    @Synchronized fun attach(sink: VideoSink) { if (!closed && sinks.add(sink)) track.addSink(sink) }
    @Synchronized fun detach(sink: VideoSink) { if (sinks.remove(sink) && !closed) track.removeSink(sink) }
    @Synchronized internal fun close() {
        if (closed) return
        sinks.forEach(track::removeSink); sinks.clear(); closed = true
    }
}
