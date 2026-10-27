package com.meetspace.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meetspace.app.ui.*
import com.meetspace.core.data.*
import com.meetspace.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.coroutines.coroutineContext

/** Metadata, streams and asynchronous results belong to one account and one visible route. */
class RecordingsViewModel(
    private val foundation: FoundationRepository,
    private val meetings: MeetingsRepository,
    private val repository: RecordingsRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(RecordingsUiState())
    val state = mutableState.asStateFlow()
    private val mutablePlayback = MutableStateFlow<RecordingPlayback?>(null)
    val playback = mutablePlayback.asStateFlow()
    private val jobs = mutableMapOf<String, Job>()
    private val tickets = mutableMapOf<String, Long>()
    private var generation = 0L
    private var identity = scope()
    private var export: ExportRequest? = null
    private var downloading: RecordingAccess? = null
    private val libraryViews = listOf(MeetingsView.PAST, MeetingsView.ACTIVE, MeetingsView.UPCOMING)
    private var libraryCursor: String? = null
    private var libraryCursors = emptySet<String>()
    private var legacyLibrary = false
    private var legacyCursors = emptyMap<MeetingsView, String?>()
    private var legacyCompleted = emptySet<MeetingsView>()
    private var legacySeen = emptyMap<MeetingsView, Set<String>>()
    private val legacyWarning = "Сервер не поддерживает общий каталог записей. Показаны записи из доступного списка встреч; для полного каталога обновите MeetSpace на сервере."

    init { viewModelScope.launch { foundation.state.collect { if (identity != scope()) { close(); identity = scope() } } } }

    val actions get() = RecordingsActions(
        close = ::close, back = ::back, refreshMeetings = { loadMeetings() }, moreMeetings = { loadMeetings(true) },
        refreshLibrary = { loadLibrary() }, moreLibrary = { loadLibrary(true) },
        retryLibraryMeeting = { loadLibraryMeeting(it) }, moreLibraryMeeting = { loadLibraryMeeting(it, true) },
        openLibrary = ::openLibraryRecording, chooseMeeting = ::chooseMeeting, allRecordings = ::showLibrary,
        filterMeetings = ::filterMeetings, openMeeting = ::openMeeting, refreshRecordings = ::refreshMeeting,
        moreRecordings = { loadRecordings(true) }, open = ::openRecording, closeDetail = ::closeDetail,
        refreshDetail = ::loadDetail, play = ::play, seek = ::seek, delete = ::deleteRecording, cancelDownload = ::cancelDownload,
        loadTranscript = ::loadTranscript, moreSegments = { loadSegments(true) }, loadSummary = ::loadSummary,
        clearMessage = { mutableState.update { it.copy(error = null, notice = null) } },
    )

    fun show(meetingId: String? = null) {
        if (scope() == null) return
        close()
        mutableState.value = RecordingsUiState(visible = true, selfId = foundation.state.value.session?.user?.id.orEmpty())
        if (meetingId == null) loadLibrary() else openMeeting(meetingId)
    }
    /** Notification IDs identify a route; access and metadata still come from the current account's API. */
    fun openNotification(meetingId: String, recordingId: String?, summary: Boolean = false) {
        if (scope() == null || meetingId.isBlank()) return
        if (recordingId.isNullOrBlank()) { show(meetingId); return }
        close()
        mutableState.value = RecordingsUiState(visible = true, selfId = foundation.state.value.session?.user?.id.orEmpty(),
            meetingId = meetingId, meeting = RecordingsLoad(loading = true), detailId = recordingId,
            detail = RecordingsLoad(loading = true), initialSummary = summary)
        request("meeting", { failure -> mutableState.update {
            it.copy(meeting = RecordingsLoad(error = failure.message), detail = RecordingsLoad(error = failure.message))
        } }) {
            val meeting = meetings.conference(meetingId)
            checkCurrent()
            if (meeting.id != meetingId) throw AppFailure(FailureKind.INVALID_RESPONSE, "Сервер вернул другую встречу. Повторите загрузку.")
            mutableState.update { it.copy(meeting = RecordingsLoad(meeting)) }
            // Fetch the indicated recording directly, including one outside the first list page.
            loadDetail()
        }
    }
    fun close() {
        generation++
        jobs.values.toList().forEach(Job::cancel); jobs.clear(); tickets.clear()
        closePlayback(); downloading?.close(); downloading = null; export = null
        mutableState.value = RecordingsUiState()
        libraryCursor = null; libraryCursors = emptySet()
        legacyLibrary = false; legacyCursors = emptyMap(); legacyCompleted = emptySet(); legacySeen = emptyMap()
    }
    override fun onCleared() { close() }
    fun back() { when { playback.value != null -> closePlayback(); state.value.detailId != null -> closeDetail(); state.value.meetingId != null -> {
        cancelDetail(); cancel("recordings"); cancel("meeting")
        mutableState.update { it.copy(meetingId = null, meeting = RecordingsLoad(), recordings = RecordingsLoad()) }
        if (!state.value.meetingPicker) loadLibrary()
    }; state.value.meetingPicker -> {
        mutableState.update { it.copy(meetingPicker = false) }
        if (!state.value.library.loaded) loadLibrary()
    }; else -> close() } }
    private fun chooseMeeting() {
        mutableState.update { it.copy(meetingPicker = true) }
        if (state.value.meetings.data == null) loadMeetings()
    }
    private fun showLibrary() {
        val fromLibrary = state.value.detailFromLibrary
        cancelDetail(); cancel("meeting"); cancel("recordings"); cancel("meetings")
        mutableState.update { it.copy(meetingPicker = false, meetingId = null, meeting = RecordingsLoad(), recordings = RecordingsLoad()) }
        if (!state.value.library.loading && (!fromLibrary || !state.value.library.loaded)) loadLibrary()
    }

    /** The catalogue is paged by recording, independently of meeting timeline membership. */
    private fun loadLibrary(append: Boolean = false) {
        if (scope() == null || !state.value.visible) return
        val previous = state.value.library
        if (previous.loading || (append && !previous.hasMore)) return
        if (!append) jobs.keys.filter { it.startsWith("library-records-") }.toList().forEach(::cancel)
        val old = if (append) previous else previous.copy(meetings = previous.meetings.map { it.copy(recordings = it.recordings.copy(loading = false)) })
        val cursor = if (append) libraryCursor else null
        val seenCursors = if (append) libraryCursors else emptySet()
        mutableState.update { it.copy(library = old.copy(loading = true, error = null)) }
        request("library", { failure -> mutableState.update {
            it.copy(library = it.library.copy(meetings = if (accessDenied(failure)) emptyList() else it.library.meetings,
                loading = false, error = failure.message))
        } }) {
            if (!legacyLibrary || !append) {
                try {
                    val page = repository.recordingLibrary(cursor, 30)
                    checkCurrent()
                    if (page.items.size > 30 || page.items.any { it.recording.conferenceId != it.meeting.id } ||
                        page.nextCursor?.let { it == cursor || it in seenCursors } == true)
                        throw AppFailure(FailureKind.INVALID_RESPONSE, "Сервер вернул неверную страницу записей. Обновите библиотеку.")
                    val rows = (if (append) old.meetings else emptyList()).associateByTo(linkedMapOf()) { it.meeting.id }
                    for (item in page.items) {
                        if (rows.values.any { row -> row.meeting.id != item.meeting.id && row.recordings.data?.items.orEmpty().any { it.id == item.recording.id } })
                            throw AppFailure(FailureKind.INVALID_RESPONSE, "Сервер вернул запись другой встречи. Обновите библиотеку.")
                        val records = rows[item.meeting.id]?.recordings?.data?.items.orEmpty()
                            .filterNot { it.id == item.recording.id } + item.recording
                        rows[item.meeting.id] = RecordingLibraryMeeting(item.meeting, RecordingsLoad(RecordingPage(records, null)))
                    }
                    legacyLibrary = false
                    libraryCursor = page.nextCursor
                    libraryCursors = if (append) seenCursors + listOfNotNull(page.nextCursor) else setOfNotNull(page.nextCursor)
                    mutableState.update { it.copy(library = RecordingLibrary(rows.values.toList(), loaded = true,
                        hasMore = page.nextCursor != null), notice = it.notice.takeUnless { message -> message == legacyWarning }) }
                    return@request
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    checkCurrent()
                    if ((error as? AppFailure)?.kind != FailureKind.INCOMPATIBLE_SERVER) throw error
                    legacyLibrary = true; legacyCursors = emptyMap(); legacyCompleted = emptySet(); legacySeen = emptyMap()
                    mutableState.update { it.copy(notice = legacyWarning) }
                    loadLegacyLibrary(old, append = false)
                    return@request
                }
            }
            loadLegacyLibrary(old, append)
        }
    }

    /** Compatibility mode is explicit and checks all three views in each bounded batch.
     * Active meetings must not wait behind every page of past meetings on older servers. */
    private suspend fun loadLegacyLibrary(old: RecordingLibrary, append: Boolean) {
        var initialized = append
        var pageError: String? = null
        for (view in libraryViews.filterNot { it in legacyCompleted }) {
            val cursor = legacyCursors[view]
            val seen = legacySeen[view].orEmpty()
            try {
                val page = meetings.timeline(MeetingsQuery(view = view, cursor = cursor, limit = 4))
                checkCurrent()
                if (page.items.size > 4 || page.nextCursor?.let { it == cursor || it in seen } == true)
                    throw AppFailure(FailureKind.INVALID_RESPONSE, "Сервер вернул неверную страницу встреч. Обновите библиотеку.")
                if (!initialized) {
                    mutableState.update { it.copy(library = it.library.copy(meetings = emptyList())) }
                    initialized = true
                }
                val existingIds = state.value.library.meetings.map { it.meeting.id }.toSet()
                for (meeting in page.items.distinctBy { it.id }.filterNot { it.id in existingIds }) {
                    val previous = old.meetings.firstOrNull { it.meeting.id == meeting.id }?.recordings ?: RecordingsLoad()
                    updateLibraryMeeting(meeting, previous.copy(loading = true, error = null))
                    val records = try {
                        val result = repository.recordings(meeting.id, 0, 12)
                        checkCurrent(); validateLibraryPage(meeting.id, result, 0)
                        RecordingsLoad(result)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        checkCurrent()
                        val failure = recordingFailure(error)
                        if (failure.kind == FailureKind.SESSION_EXPIRED) throw failure
                        revokeVisibleRecordings(meeting.id, failure)
                        failedLoad(previous, failure)
                    }
                    updateLibraryMeeting(meeting, records)
                }
                legacyCursors = legacyCursors + (view to page.nextCursor)
                legacySeen = legacySeen + (view to (seen + listOfNotNull(page.nextCursor)))
                if (page.nextCursor == null) legacyCompleted = legacyCompleted + view
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                checkCurrent()
                val failure = recordingFailure(error)
                if (failure.kind == FailureKind.SESSION_EXPIRED) throw failure
                pageError = pageError ?: failure.message
            }
        }
        mutableState.update { it.copy(library = it.library.copy(loaded = true, loading = false, error = pageError,
            hasMore = legacyCompleted.size < libraryViews.size)) }
    }

    internal val previewIdentity: String? get() = scope()

    /** Acquire only an authorised preview from currently visible metadata; the loader owns the lease. */
    internal suspend fun previewAccess(recording: Recording, file: RecordingFile): RecordingAccess {
        val expectedScope = scope() ?: throw CancellationException("Account unavailable")
        val epoch = generation
        fun visiblePreview(): Boolean {
            if (!state.value.visible || recording.status !in setOf("ready", "partial_ready") ||
                file.type != "preview_jpg" || file.mimeType != "image/jpeg" || !file.available) return false
            val records = state.value.library.meetings.flatMap { it.recordings.data?.items.orEmpty() } +
                state.value.recordings.data?.items.orEmpty() + listOfNotNull(state.value.detail.data)
            return records.any { it.id == recording.id && it.conferenceId == recording.conferenceId &&
                it.status in setOf("ready", "partial_ready") && file in it.files }
        }
        if (expectedScope != identity || !visiblePreview()) throw CancellationException("Preview no longer visible")
        val access = repository.recordingAccess(recording.conferenceId, recording.id, file.id)
        try {
            coroutineContext.ensureActive()
            if (expectedScope != scope() || epoch != generation || !visiblePreview() || access.file != file) throw CancellationException("Preview scope changed")
            return access
        } catch (error: Exception) { access.close(); throw error }
    }

    private fun loadLibraryMeeting(id: String, append: Boolean = false) {
        if (scope() == null || state.value.library.loading) return
        val row = state.value.library.meetings.firstOrNull { it.meeting.id == id } ?: return
        val old = row.recordings
        if (old.loading) return
        val offset = if (append) old.data?.nextOffset ?: return else 0
        updateLibraryMeeting(row.meeting, old.copy(loading = true, error = null))
        request("library-records-$id", { failure ->
            if (failure.kind != FailureKind.SESSION_EXPIRED) {
                revokeVisibleRecordings(id, failure)
                updateLibraryMeeting(row.meeting, failedLoad(old, failure))
            }
        }) {
            val page = repository.recordings(id, offset, 12)
            checkCurrent(); validateLibraryPage(id, page, offset)
            val rows = ((if (append) old.data?.items.orEmpty() else emptyList()) + page.items).associateBy { it.id }.values.toList()
            updateLibraryMeeting(row.meeting, RecordingsLoad(RecordingPage(rows, page.nextOffset)))
        }
    }

    private fun validateLibraryPage(id: String, page: RecordingPage, offset: Int) {
        if (page.items.size > 12 || page.items.any { it.conferenceId != id } || page.nextOffset?.let { it <= offset } == true)
            throw AppFailure(FailureKind.INVALID_RESPONSE, "Сервер вернул неверную страницу записей. Повторите загрузку.")
    }
    private fun revokeVisibleRecordings(id: String, failure: AppFailure) {
        if (!accessDenied(failure) || state.value.meetingId != id) return
        listOf("meeting", "recordings", "detail", "transcript", "segments", "summary", "media").forEach(::cancel)
        closePlayback(); cancelDownload()
        mutableState.update { it.copy(meeting = RecordingsLoad(), recordings = RecordingsLoad(), detail = RecordingsLoad(error = failure.message),
            transcript = RecordingsLoad(), segments = RecordingsLoad(), summary = RecordingsLoad(), mediaBusy = false, error = failure.message, notice = null) }
    }
    private fun updateLibraryMeeting(meeting: Meeting, recordings: RecordingsLoad<RecordingPage>) {
        mutableState.update { state ->
            val rows = state.library.meetings.toMutableList()
            val index = rows.indexOfFirst { it.meeting.id == meeting.id }
            val value = RecordingLibraryMeeting(meeting, recordings)
            if (index < 0) rows += value else rows[index] = value
            state.copy(library = state.library.copy(meetings = rows))
        }
    }
    private fun openLibraryRecording(meetingId: String, recordingId: String) {
        val row = state.value.library.meetings.firstOrNull { it.meeting.id == meetingId } ?: return
        val record = row.recordings.data?.items?.firstOrNull { it.id == recordingId } ?: return
        val readyMaterials = record.mode == "individual_tracks" && record.status in setOf("ready", "partial_ready") &&
            record.files.any { it.available && it.type != "preview_jpg" }
        if (!record.playable && !readyMaterials) return
        cancelDetail(); cancel("meeting"); cancel("recordings")
        mutableState.update { it.copy(meetingId = meetingId, meeting = RecordingsLoad(row.meeting), recordings = RecordingsLoad(),
            detailId = recordingId, detailFromLibrary = true, meetingPicker = false) }
        loadDetail()
    }
    fun filterMeetings(view: MeetingsView) {
        if (state.value.meetingView == view) return
        cancel("meetings"); mutableState.update { it.copy(meetingView = view, meetings = RecordingsLoad()) }; loadMeetings()
    }
    private fun loadMeetings(append: Boolean = false) {
        val old = state.value.meetings
        if (append && old.loading) return
        val cursor = if (append) old.data?.nextCursor ?: return else null
        val view = state.value.meetingView
        mutableState.update { it.copy(meetings = old.copy(loading = true, error = null)) }
        request("meetings", { failure -> mutableState.update { it.copy(meetings = failedLoad(old, failure)) } }) {
            val page = meetings.timeline(MeetingsQuery(view = view, cursor = cursor))
            checkCurrent()
            if (cursor != null && page.nextCursor == cursor) throw AppFailure(FailureKind.INVALID_RESPONSE, "Сервер повторил страницу. Обновите список встреч.")
            val rows = ((if (append) old.data?.items.orEmpty() else emptyList()) + page.items).associateBy { it.id }.values.toList()
            mutableState.update { it.copy(meetings = RecordingsLoad(MeetingPage(rows, page.nextCursor))) }
        }
    }
    fun openMeeting(id: String) {
        if (scope() == null) return
        cancelDetail(); cancel("recordings"); cancel("meeting")
        mutableState.update { it.copy(meetingId = id, meeting = RecordingsLoad(loading = true), recordings = RecordingsLoad()) }
        request("meeting", { failure -> mutableState.update { it.copy(meeting = RecordingsLoad(error = failure.message)) } }) {
            val result = meetings.conference(id); checkCurrent(); mutableState.update { it.copy(meeting = RecordingsLoad(result)) }
        }
        loadRecordings()
    }
    private fun refreshMeeting() {
        val id = state.value.meetingId ?: return
        val old = state.value.meeting
        mutableState.update { it.copy(meeting = old.copy(loading = true, error = null)) }
        request("meeting", { failure -> mutableState.update { it.copy(meeting = failedLoad(old, failure)) } }) {
            val result = meetings.conference(id); checkCurrent(); mutableState.update { it.copy(meeting = RecordingsLoad(result)) }
        }
        loadRecordings()
    }
    private fun loadRecordings(append: Boolean = false) {
        val id = state.value.meetingId ?: return
        val old = state.value.recordings
        if (append && old.loading) return
        val offset = if (append) old.data?.nextOffset ?: return else 0
        mutableState.update { it.copy(recordings = old.copy(loading = true, error = null)) }
        request("recordings", { failure -> mutableState.update { it.copy(recordings = failedLoad(old, failure)) } }) {
            val page = repository.recordings(id, offset); checkCurrent()
            if (page.nextOffset?.let { it <= offset } == true) throw AppFailure(FailureKind.INVALID_RESPONSE, "Не удалось перейти к следующей странице записей.")
            val rows = ((if (append) old.data?.items.orEmpty() else emptyList()) + page.items).associateBy { it.id }.values.toList()
            mutableState.update { it.copy(recordings = RecordingsLoad(RecordingPage(rows, page.nextOffset))) }
        }
    }
    fun openRecording(id: String) { cancelDetail(); mutableState.update { it.copy(detailId = id) }; loadDetail() }
    fun closeDetail() {
        val fromLibrary = state.value.detailFromLibrary
        cancelDetail()
        if (fromLibrary) mutableState.update { it.copy(meetingId = null, meeting = RecordingsLoad(), recordings = RecordingsLoad()) }
        else if (state.value.visible && state.value.meetingId != null && state.value.recordings.data == null && !state.value.recordings.loading) loadRecordings()
    }
    private fun cancelDetail() {
        listOf("detail", "transcript", "segments", "summary", "media").forEach(::cancel)
        closePlayback()
        mutableState.update { it.copy(detailId = null, detailFromLibrary = false, detail = RecordingsLoad(), transcript = RecordingsLoad(), segments = RecordingsLoad(), summary = RecordingsLoad(), mediaBusy = false, initialSummary = false) }
    }
    private fun loadDetail() {
        val cid = state.value.meetingId ?: return; val rid = state.value.detailId ?: return
        val old = state.value.detail
        mutableState.update { it.copy(detail = old.copy(loading = true, error = null)) }
        request("detail", { failure ->
            closePlayback()
            // A denied/missing recording must not keep its previous private material visible.
            if (failure.kind in setOf(FailureKind.FORBIDDEN, FailureKind.NOT_FOUND, FailureKind.SESSION_EXPIRED)) {
                listOf("transcript", "segments", "summary").forEach(::cancel)
                mutableState.update { it.copy(detail = RecordingsLoad(error = failure.message), transcript = RecordingsLoad(), segments = RecordingsLoad(), summary = RecordingsLoad()) }
            } else mutableState.update { it.copy(detail = failedLoad(old, failure)) }
        }) {
            val result = repository.recording(cid, rid); checkCurrent()
            if (result.id != rid || result.conferenceId != cid) throw AppFailure(FailureKind.INVALID_RESPONSE, "Сервер вернул другую запись. Повторите загрузку.")
            mutableState.update { it.copy(detail = RecordingsLoad(result)) }
            if (state.value.initialSummary) loadSummary()
        }
    }
    private fun loadTranscript() {
        val cid = state.value.meetingId ?: return; val rid = state.value.detailId ?: return
        val old = state.value.transcript
        cancel("segments")
        mutableState.update { it.copy(transcript = old.copy(loading = true, error = null), segments = RecordingsLoad()) }
        request("transcript", { failure -> mutableState.update { it.copy(transcript = RecordingsLoad(error = failure.message)) } }) {
            val result = repository.recordingTranscript(cid, rid); checkCurrent()
            mutableState.update { it.copy(transcript = RecordingsLoad(result)) }
            if (result.item?.status == "ready") loadSegments()
        }
    }
    private fun loadSegments(append: Boolean = false) {
        val cid = state.value.meetingId ?: return; val rid = state.value.detailId ?: return
        val old = state.value.segments
        if (append && old.loading) return
        val offset = if (append) segmentOffset ?: return else 0
        mutableState.update { it.copy(segments = old.copy(loading = true, error = null)) }
        request("segments", { failure -> mutableState.update { it.copy(segments = failedLoad(old, failure)) } }) {
            val page = repository.recordingSegments(cid, rid, offset); checkCurrent()
            val transcriptId = state.value.transcript.data?.item?.id
            if (page.items.any { it.transcriptId != transcriptId }) throw AppFailure(FailureKind.INVALID_RESPONSE, "Расшифровка изменилась. Откройте её заново.")
            if (page.offset != offset) throw AppFailure(FailureKind.INVALID_RESPONSE, "Сервер вернул другую страницу расшифровки.")
            // Keep the server offset separate from the merged list so pagination does not skip text.
            segmentOffset = page.nextOffset
            val rows = ((if (append) old.data?.items.orEmpty() else emptyList()) + page.items).associateBy { it.id }.values.toList()
            mutableState.update { it.copy(segments = RecordingsLoad(page.copy(items = rows, offset = page.offset + page.items.size - rows.size))) }
        }
    }
    private var segmentOffset: Int? = null
    private fun loadSummary() {
        val cid = state.value.meetingId ?: return; val rid = state.value.detailId ?: return
        val old = state.value.summary
        mutableState.update { it.copy(summary = old.copy(loading = true, error = null)) }
        request("summary", { failure -> mutableState.update { it.copy(summary = RecordingsLoad(error = failure.message)) } }) {
            val result = repository.recordingSummary(cid, rid); checkCurrent(); mutableState.update { it.copy(summary = RecordingsLoad(result)) }
        }
    }
    fun play(recording: Recording, file: RecordingFile) = playAt(recording, file, 0)
    private fun playAt(recording: Recording, file: RecordingFile, atMs: Long) {
        if (!recording.playable || recording.conferenceId != state.value.meetingId || state.value.mediaBusy) return
        closePlayback()
        mutableState.update { it.copy(mediaBusy = true, error = null) }
        request("media", { failure -> mutableState.update { it.copy(mediaBusy = false, error = failure.message) } }) {
            val access = repository.recordingAccess(recording.conferenceId, recording.id, file.id)
            try { checkCurrent(); mutablePlayback.value = RecordingPlayback(access, state.value.meeting.data?.title ?: "Запись встречи").apply { positionMs = atMs } }
            catch (e: Exception) { access.close(); throw e }
            mutableState.update { it.copy(mediaBusy = false) }
        }
    }
    fun closePlayback() { mutablePlayback.value?.access?.close(); mutablePlayback.value = null }
    fun seek(positionMs: Long) {
        val record = state.value.detail.data ?: return
        val file = record.media ?: return
        val at = positionMs.coerceAtLeast(0).let { value -> (file.durationSec ?: record.durationSec)?.let { value.coerceAtMost(it * 1000) } ?: value }
        val current = playback.value
        if (current != null && current.access.file.id == file.id) current.seekRequest.value = (current.seekRequest.value?.first ?: 0) + 1 to at
        else playAt(record, file, at)
    }
    fun deleteRecording(record: Recording) {
        val s = state.value
        val meeting = s.library.meetings.find { it.meeting.id == record.conferenceId }?.meeting ?: s.meeting.data
        if (s.deleting || record.status !in setOf("ready", "partial_ready", "failed", "cancelled") ||
            s.selfId.isBlank() || (meeting?.id != record.conferenceId || s.selfId != meeting.ownerId) && s.selfId != record.requestedBy) return
        mutableState.update { it.copy(deleting = true, error = null) }
        request("delete-recording", { failure -> mutableState.update { it.copy(deleting = false, error = failure.message) } }) {
            repository.deleteRecording(record.conferenceId, record.id); checkCurrent()
            closePlayback()
            if (state.value.detailId == record.id) cancelDetail()
            mutableState.update { current -> current.copy(deleting = false, notice = "Запись удалена.",
                recordings = current.recordings.copy(data = current.recordings.data?.let { page -> page.copy(items = page.items.filterNot { it.id == record.id }, nextOffset = page.nextOffset?.let { (it - page.items.count { r -> r.id == record.id }).coerceAtLeast(0) }) }),
                library = current.library.copy(meetings = current.library.meetings.map { row -> row.copy(recordings = row.recordings.copy(data = row.recordings.data?.let { page -> page.copy(items = page.items.filterNot { it.id == record.id }, nextOffset = page.nextOffset?.let { (it - page.items.count { r -> r.id == record.id }).coerceAtLeast(0) }) })) })) }
        }
    }
    fun queueExport(recording: Recording, file: RecordingFile): ExportRequest? {
        if (scope() == null || state.value.download != null || recording.status !in setOf("ready", "partial_ready") || !file.available || recording.files.none { it == file } || recording.conferenceId != state.value.meetingId) return null
        return ExportRequest(recording.conferenceId, recording.id, file, scope()!!).also { export = it }
    }
    fun discardExport() { export = null }
    /** Returns false if a document picker result no longer belongs to this account/route. */
    fun exportTo(cleanup: () -> Unit = {}, writer: suspend (RecordingAccess, (Long) -> Unit) -> Unit): Boolean {
        val selected = export ?: return false
        export = null
        if (selected.identity != scope()) return false
        mutableState.update { it.copy(download = RecordingDownloadUi(selected.file.filename, totalBytes = selected.file.sizeBytes), error = null, notice = null) }
        var completed = false
        val epoch = generation; val downloadTicket = (tickets["download"] ?: 0) + 1
        request("download", { failure -> mutableState.update { it.copy(download = null, error = failure.message) } }, resourceMeetingId = selected.conferenceId) {
            val access = repository.recordingAccess(selected.conferenceId, selected.recordingId, selected.file.id)
            try {
                checkCurrent(); downloading = access
                var lastProgressNanos = 0L
                writer(access) { bytes ->
                    val now = System.nanoTime()
                    if (now - lastProgressNanos >= 100_000_000L) {
                        lastProgressNanos = now
                        viewModelScope.launch {
                            if (generation == epoch && scope() == selected.identity && tickets["download"] == downloadTicket)
                                mutableState.update { it.copy(download = it.download?.copy(bytes = bytes)) }
                        }
                    }
                }
                checkCurrent(); completed = true; mutableState.update { it.copy(download = null, notice = "Запись сохранена в выбранную папку.") }
            } finally { access.close(); if (downloading === access) downloading = null }
        }
        // Even cancellation before the coroutine starts must remove the newly created SAF document.
        jobs["download"]?.invokeOnCompletion { if (!completed) cleanup() }
        return true
    }
    fun cancelDownload() { cancel("download"); downloading?.close(); downloading = null; mutableState.update { it.copy(download = null, notice = "Скачивание отменено.") } }
    fun cleanupFailureHandler(): () -> Unit {
        val epoch = generation; val selectedIdentity = scope()
        return { viewModelScope.launch {
            if (epoch == generation && selectedIdentity == scope()) mutableState.update { it.copy(error = "Не удалось удалить незавершённый файл. Удалите его из выбранной папки через файловый менеджер.") }
        }; Unit }
    }
    fun exportError() { mutableState.update { it.copy(error = "Не удалось открыть место сохранения. Выберите другую папку.") } }

    data class ExportRequest(val conferenceId: String, val recordingId: String, val file: RecordingFile, val identity: String)
    private fun <T> failedLoad(old: RecordingsLoad<T>, failure: AppFailure): RecordingsLoad<T> =
        RecordingsLoad(if (accessDenied(failure)) null else old.data, error = failure.message)
    private fun accessDenied(failure: AppFailure) = failure.kind in setOf(FailureKind.FORBIDDEN, FailureKind.NOT_FOUND, FailureKind.SESSION_EXPIRED)
    private fun recordingFailure(error: Exception) = error as? AppFailure ?: AppFailure(
        (error as? RecordingReadException)?.kind ?: FailureKind.UNAVAILABLE,
        (error as? RecordingReadException)?.message ?: "Не удалось выполнить действие. Попробуйте ещё раз.")
    private fun scope(): String? = foundation.state.value.let { s -> s.selectedProfile?.let { p -> s.session?.user?.let { "${p.id}|${p.origin}|${it.id}" } } }
    private fun cancel(key: String) { tickets[key] = (tickets[key] ?: 0) + 1; jobs.remove(key)?.cancel() }
    private fun request(key: String, failure: (AppFailure) -> Unit, resourceMeetingId: String? = null, operation: suspend () -> Unit) {
        if (scope() == null) return
        cancel(key)
        val ticket = tickets.getValue(key); val epoch = generation; val requestScope = scope()
        val requestMeetingId = resourceMeetingId ?: state.value.meetingId
        jobs[key] = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try { operation() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) {
                coroutineContext.ensureActive()
                if (epoch == generation && requestScope == scope() && tickets[key] == ticket)
                    {
                        val error = recordingFailure(e)
                        if (error.kind == FailureKind.SESSION_EXPIRED && (key == "library" || key.startsWith("library-records-"))) {
                            jobs.keys.filter { it != key }.toList().forEach(::cancel)
                            closePlayback(); cancelDownload()
                            mutableState.update { it.copy(meeting = RecordingsLoad(), recordings = RecordingsLoad(), detail = RecordingsLoad(), transcript = RecordingsLoad(), segments = RecordingsLoad(), summary = RecordingsLoad(),
                                library = RecordingLibrary(error = error.message), error = error.message, mediaBusy = false) }
                        }
                        if (accessDenied(error) && key in setOf("meeting", "recordings", "detail", "transcript", "segments", "summary", "media", "download")) {
                            // Downloads may outlive their detail route. A late denial belongs to
                            // the requested meeting, not whichever recording is currently open.
                            val invalidateVisible = error.kind == FailureKind.SESSION_EXPIRED || state.value.meetingId == requestMeetingId
                            if (invalidateVisible) {
                                listOf("meeting", "recordings", "detail", "transcript", "segments", "summary", "media").filter { it != key }.forEach(::cancel)
                                closePlayback()
                            }
                            jobs.keys.filter { it == "library" || it.startsWith("library-records-") }.toList().forEach(::cancel)
                            if (key == "download" || invalidateVisible) cancelDownload()
                            mutableState.update { current ->
                                val sanitized = if (invalidateVisible) current.copy(meeting = RecordingsLoad(), recordings = RecordingsLoad(), detail = RecordingsLoad(),
                                    transcript = RecordingsLoad(), segments = RecordingsLoad(), summary = RecordingsLoad(), mediaBusy = false) else current
                                sanitized.copy(library = current.library.copy(loading = false, error = "Доступ к записи изменился. Обновите библиотеку.",
                                    meetings = if (error.kind == FailureKind.SESSION_EXPIRED) emptyList() else current.library.meetings.filterNot { row -> row.meeting.id == requestMeetingId }.map { row -> row.copy(recordings = row.recordings.copy(loading = false)) }),
                                    error = error.message, notice = null)
                            }
                        }
                        failure(error)
                    }
            } finally { if (tickets[key] == ticket) jobs.remove(key) }
        }.also(Job::start)
    }
    private suspend fun checkCurrent() { coroutineContext.ensureActive(); if (scope() != identity) throw CancellationException("Identity changed") }
    class Factory(private val foundation: FoundationRepository, private val meetings: MeetingsRepository, private val recordings: RecordingsRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(RecordingsViewModel::class.java)); return RecordingsViewModel(foundation, meetings, recordings) as T
        }
    }
}

class RecordingPlayback(val access: RecordingAccess, val title: String) {
    val seekRequest = MutableStateFlow<Pair<Long, Long>?>(null)
    var positionMs: Long = 0
    var playWhenReady: Boolean = true
}
