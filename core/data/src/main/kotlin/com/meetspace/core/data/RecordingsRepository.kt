package com.meetspace.core.data

import com.meetspace.core.model.*
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

interface RecordingsRepository {
    /** Server acknowledgement may still be starting. Refresh after an uncertain response before retrying. */
    suspend fun startRecording(conferenceId: String, mode: String = "composite"): Recording
    /** Server acknowledgement may still be stopping/finalizing; there is no pause/resume API. */
    suspend fun stopRecording(conferenceId: String, recordingId: String): Recording
    suspend fun recordings(conferenceId: String, offset: Int = 0, limit: Int = 20): RecordingPage
    suspend fun recording(conferenceId: String, recordingId: String): Recording
    /** Rechecks backend access. This lease is closed on logout/server/account change. */
    suspend fun deleteRecording(conferenceId: String, recordingId: String): Unit = throw AppFailure(FailureKind.UNAVAILABLE, "Удаление записи недоступно.")
    suspend fun recordingAccess(conferenceId: String, recordingId: String, fileId: String): RecordingAccess
    suspend fun recordingTranscript(conferenceId: String, recordingId: String): RecordingTranscriptState
    suspend fun recordingSegments(conferenceId: String, recordingId: String, offset: Int = 0, limit: Int = 100): RecordingSegmentPage
    suspend fun recordingSummary(conferenceId: String, recordingId: String): RecordingSummaryState
}

/** Opaque capability, never serializable or suitable for saved state. Blocking reads run on an IO thread. */
interface RecordingAccess : Closeable {
    val file: RecordingFile
    fun open(position: Long = 0, length: Long? = null): RecordingStream
    /** Streams with bounded memory, checks the complete size/SHA256, and closes on cancellation. */
    suspend fun copyTo(destination: OutputStream, onProgress: (Long) -> Unit = {}): Long
}

class RecordingStream(val input: InputStream, val length: Long?) : Closeable {
    override fun close() = input.close()
}

/** Safe for player errors: neither a signed URL nor an underlying transport exception is attached. */
class RecordingReadException(val kind: FailureKind, override val message: String) : IOException(message)
