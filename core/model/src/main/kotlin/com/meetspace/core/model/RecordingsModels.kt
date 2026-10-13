package com.meetspace.core.model

import java.time.Instant

/** Public metadata only: storage keys, diagnostics and signed URLs stay in the data layer. */
data class Recording(
    val id: String, val conferenceId: String, val mode: String, val status: String,
    val createdAt: Instant, val startedAt: Instant? = null, val durationSec: Long? = null,
    val files: List<RecordingFile> = emptyList(),
    /** Account that requested capture; only it or the conference owner may stop it. */
    val requestedBy: String? = null,
) {
    val media: RecordingFile? get() = files.firstOrNull { it.type == "final_mp4" && it.available }
        ?: files.firstOrNull { it.type == "final_audio" && it.available }
    val playable: Boolean get() = status == "ready" && media != null
}

data class RecordingFile(
    val id: String, val type: String, val filename: String, val mimeType: String,
    val sizeBytes: Long? = null, val checksumSha256: String? = null,
    val durationSec: Long? = null, val available: Boolean = true,
)
data class RecordingPage(val items: List<Recording>, val nextOffset: Int?)
data class RecordingTranscriptState(val enabled: Boolean, val item: RecordingTranscript?)
data class RecordingTranscript(val id: String, val recordingId: String, val language: String, val status: String)
data class RecordingSegment(
    val id: String, val transcriptId: String, val startMs: Long, val endMs: Long,
    val speakerLabel: String?, val text: String,
)
data class RecordingSegmentPage(val items: List<RecordingSegment>, val total: Long, val offset: Int, val limit: Int) {
    val nextOffset: Int? get() = (offset + items.size).takeIf { items.isNotEmpty() && it < total }
}
data class RecordingSummaryState(val enabled: Boolean, val item: RecordingSummary?)
data class RecordingSummary(
    val id: String, val transcriptId: String, val status: String, val text: String,
    val keyPoints: List<String>, val actionItems: List<RecordingActionItem>, val topics: List<String>,
)
data class RecordingActionItem(val text: String, val assignee: String?, val dueDate: String?)
