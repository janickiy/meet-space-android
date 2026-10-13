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
    private var libraryViewIndex = 0
    private var libraryCursor: String? = null
    private var libraryCursors = emptySet<String>()

    init { viewModelScope.launch { foundation.state.collect { if (identity != scope()) { close(); identity = scope() } } } }

    val actions get() = RecordingsActions(
        close = ::close, back = ::back, refreshMeetings = { loadMeetings() }, moreMeetings = { loadMeetings(true) },
        refreshLibrary = { loadLibrary() }, moreLibrary = { loadLibrary(true) },
        retryLibraryMeeting = { loadLibraryMeeting(it) }, moreLibraryMeeting = { loadLibraryMeeting(it, true) },
        openLibrary = ::openLibraryRecording, chooseMeeting = ::chooseMeeting, allRecordings = ::showLibrary,
        filterMeetings = ::filterMeetings, openMeeting = ::openMeeting, refreshRecordings = ::refreshMeeting,
        moreRecordings = { loadRecordings(true) }, open = ::openRecording, closeDetail = ::closeDetail,
        refreshDetail = ::loadDetail, play = ::play, cancelDownload = ::cancelDownload,
        loadTranscript = ::loadTranscript, moreSegments = { loadSegments(true) }, loadSummary = ::loadSummary,
        clearMessage = { mutableState.update { it.copy(error = null, notice = null) } },
    )

    fun show(meetingId: String? = null) {
        if (scope() == null) return
        close()
        mutableState.value = RecordingsUiState(visible = true)
        if (meetingId == null) loadLibrary() else openMeeting(meetingId)
    }
    fun close() {
        generation++
        jobs.values.toList().forEach(Job::cancel); jobs.clear(); tickets.clear()
        closePlayback(); downloading?.close(); downloading = null; export = null
        mutableState.value = RecordingsUiState()
        libraryViewIndex = 0; libraryCursor = null; libraryCursors = emptySet()
    }
    override fun onCleared() { close() }
    fun back() { when { playback.value != null -> closePlayback(); state.value.detailId != null -> closeDetail(); state.value.meetingId != null -> {
        cancelDetail(); cancel("recordings"); cancel("meeting")
        mutableState.update { it.copy(meetingId = null, meeting = RecordingsLoad(), recordings = RecordingsLoad()) }
        if (!state.value.meetingPicker && !state.value.library.loaded) loadLibrary()
    }; state.value.meetingPicker -> {
        mutableState.update { it.copy(meetingPicker = false) }
        if (!state.value.library.loaded) loadLibrary()
    }; else -> close() } }
    private fun chooseMeeting() {
        mutableState.update { it.copy(meetingPicker = true) }
        if (state.value.meetings.data == null) loadMeetings()
    }
    private fun showLibrary() {
        cancelDetail(); cancel("meeting"); cancel("recordings"); cancel("meetings")
        mutableState.update { it.copy(meetingPicker = false, meetingId = null, meeting = RecordingsLoad(), recordings = RecordingsLoad()) }
        if (!state.value.library.loaded && !state.value.library.loading) loadLibrary()
    }

    /** One explicit step costs at most one timeline page and four recording-list requests. */
    private fun loadLibrary(append: Boolean = false) {
        if (scope() == null || !state.value.visible) return
        val previous = state.value.library
        if (previous.loading || (append && !previous.hasMore)) return
        if (!append) jobs.keys.filter { it.startsWith("library-records-") }.toList().forEach(::cancel)
        val old = if (append) previous else previous.copy(meetings = previous.meetings.map { it.copy(recordings = it.recordings.copy(loading = false)) })
        val viewIndex = if (append) libraryViewIndex else 0
        val cursor = if (append) libraryCursor else null
        val seenCursors = if (append) libraryCursors else emptySet()
        mutableState.update { it.copy(library = old.copy(loading = true, error = null)) }
        request("library", { failure -> mutableState.update {
            it.copy(library = it.library.copy(meetings = if (accessDenied(failure)) emptyList() else it.library.meetings,
                loading = false, error = failure.message))
        } }) {
            val page = meetings.timeline(MeetingsQuery(view = libraryViews[viewIndex], cursor = cursor, limit = 4))
            checkCurrent()
            if (page.items.size > 4 || page.nextCursor?.let { it == cursor || it in seenCursors } == true)
                throw AppFailure(FailureKind.INVALID_RESPONSE, "Сервер вернул неверную страницу встреч. Обновите библиотеку.")
            val pageMeetings = page.items.distinctBy { it.id }
            val existingIds = if (append) old.meetings.map { it.meeting.id }.toSet() else emptySet()
            if (!append) mutableState.update { it.copy(library = it.library.copy(meetings = old.meetings.filter { row -> pageMeetings.any { it.id == row.meeting.id } })) }
            for (meeting in pageMeetings.filterNot { it.id in existingIds }) {
                val previous = old.meetings.firstOrNull { it.meeting.id == meeting.id }?.recordings ?: RecordingsLoad()
                updateLibraryMeeting(meeting, previous.copy(loading = true, error = null))
                val result = try {
                    val records = repository.recordings(meeting.id, 0, 12)
                    checkCurrent(); validateLibraryPage(meeting.id, records, 0)
                    RecordingsLoad(records)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    checkCurrent()
                    val failure = recordingFailure(error)
                    if (failure.kind == FailureKind.SESSION_EXPIRED) throw failure
                    revokeVisibleRecordings(meeting.id, failure)
                    failedLoad(previous, failure)
                }
                updateLibraryMeeting(meeting, result)
            }
            libraryViewIndex = if (page.nextCursor == null) viewIndex + 1 else viewIndex
            libraryCursor = page.nextCursor
            libraryCursors = if (page.nextCursor == null) emptySet() else seenCursors + listOfNotNull(page.nextCursor)
            mutableState.update { it.copy(library = it.library.copy(loaded = true, loading = false,
                hasMore = libraryViewIndex < libraryViews.size)) }
        }
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
        if (row.recordings.data?.items?.none { it.id == recordingId && it.playable } != false) return
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
    }
    private fun cancelDetail() {
        listOf("detail", "transcript", "segments", "summary", "media").forEach(::cancel)
        closePlayback()
        mutableState.update { it.copy(detailId = null, detailFromLibrary = false, detail = RecordingsLoad(), transcript = RecordingsLoad(), segments = RecordingsLoad(), summary = RecordingsLoad(), mediaBusy = false) }
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
            val result = repository.recording(cid, rid); checkCurrent(); mutableState.update { it.copy(detail = RecordingsLoad(result)) }
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
    fun play(recording: Recording, file: RecordingFile) {
        if (!recording.playable || recording.conferenceId != state.value.meetingId || state.value.mediaBusy) return
        closePlayback()
        mutableState.update { it.copy(mediaBusy = true, error = null) }
        request("media", { failure -> mutableState.update { it.copy(mediaBusy = false, error = failure.message) } }) {
            val access = repository.recordingAccess(recording.conferenceId, recording.id, file.id)
            try { checkCurrent(); mutablePlayback.value = RecordingPlayback(access, state.value.meeting.data?.title ?: "Запись встречи") }
            catch (e: Exception) { access.close(); throw e }
            mutableState.update { it.copy(mediaBusy = false) }
        }
    }
    fun closePlayback() { mutablePlayback.value?.access?.close(); mutablePlayback.value = null }
    fun queueExport(recording: Recording, file: RecordingFile): ExportRequest? {
        if (scope() == null || state.value.download != null || !recording.playable || recording.conferenceId != state.value.meetingId) return null
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
    var positionMs: Long = 0
    var playWhenReady: Boolean = true
}
