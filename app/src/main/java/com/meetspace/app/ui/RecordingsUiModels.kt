package com.meetspace.app.ui

import com.meetspace.core.model.Meeting
import com.meetspace.core.model.MeetingPage
import com.meetspace.core.model.MeetingsView
import com.meetspace.core.model.Recording
import com.meetspace.core.model.RecordingFile
import com.meetspace.core.model.RecordingPage
import com.meetspace.core.model.RecordingSegmentPage
import com.meetspace.core.model.RecordingSummaryState
import com.meetspace.core.model.RecordingTranscriptState

/** Private recording and material metadata stays in memory, outside saved instance state. */
data class RecordingsLoad<T>(val data: T? = null, val loading: Boolean = false, val error: String? = null)

data class RecordingDownloadUi(val filename: String, val bytes: Long = 0, val totalBytes: Long? = null)

data class RecordingLibraryMeeting(val meeting: Meeting, val recordings: RecordingsLoad<RecordingPage>)

/** Each meeting keeps its own error and continuation; a failed request is never an empty result. */
data class RecordingLibrary(
    val meetings: List<RecordingLibraryMeeting> = emptyList(),
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val hasMore: Boolean = true,
)

data class RecordingsUiState(
    val visible: Boolean = false,
    val library: RecordingLibrary = RecordingLibrary(),
    val meetingPicker: Boolean = false,
    val detailFromLibrary: Boolean = false,
    val meetings: RecordingsLoad<MeetingPage> = RecordingsLoad(),
    val meetingView: MeetingsView = MeetingsView.PAST,
    val meetingId: String? = null,
    val meeting: RecordingsLoad<Meeting> = RecordingsLoad(),
    val recordings: RecordingsLoad<RecordingPage> = RecordingsLoad(),
    val detailId: String? = null,
    val detail: RecordingsLoad<Recording> = RecordingsLoad(),
    val transcript: RecordingsLoad<RecordingTranscriptState> = RecordingsLoad(),
    val segments: RecordingsLoad<RecordingSegmentPage> = RecordingsLoad(),
    val summary: RecordingsLoad<RecordingSummaryState> = RecordingsLoad(),
    val download: RecordingDownloadUi? = null,
    val mediaBusy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
)

data class RecordingsActions(
    val close: () -> Unit = {},
    val back: () -> Unit = {},
    val refreshLibrary: () -> Unit = {},
    val moreLibrary: () -> Unit = {},
    val retryLibraryMeeting: (String) -> Unit = {},
    val moreLibraryMeeting: (String) -> Unit = {},
    val openLibrary: (String, String) -> Unit = { _, _ -> },
    val chooseMeeting: () -> Unit = {},
    val allRecordings: () -> Unit = {},
    val refreshMeetings: () -> Unit = {},
    val moreMeetings: () -> Unit = {},
    val filterMeetings: (MeetingsView) -> Unit = {},
    val openMeeting: (String) -> Unit = {},
    val refreshRecordings: () -> Unit = {},
    val moreRecordings: () -> Unit = {},
    val open: (String) -> Unit = {},
    val closeDetail: () -> Unit = {},
    val refreshDetail: () -> Unit = {},
    val play: (Recording, RecordingFile) -> Unit = { _, _ -> },
    val download: (Recording, RecordingFile) -> Unit = { _, _ -> },
    val cancelDownload: () -> Unit = {},
    val loadTranscript: () -> Unit = {},
    val moreSegments: () -> Unit = {},
    val loadSummary: () -> Unit = {},
    val clearMessage: () -> Unit = {},
)
