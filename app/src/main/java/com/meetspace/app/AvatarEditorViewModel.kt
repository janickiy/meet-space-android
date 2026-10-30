package com.meetspace.app

import android.graphics.Bitmap
import androidx.annotation.MainThread
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class AvatarEditorState(
    val key: String,
    val title: String,
    val source: Bitmap? = null,
    val crop: AvatarCrop = AvatarCrop(),
    val loading: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val pendingRevision: Long? = null,
    val version: String? = null,
)

/**
 * Retains the original/crop across Activity recreation without putting image data in a Bundle.
 * Every asynchronous result belongs to both a full identity/target key and a particular session.
 */
internal class AvatarEditorViewModel : ViewModel() {
    private val mutable = MutableStateFlow<AvatarEditorState?>(null)
    val state: StateFlow<AvatarEditorState?> = mutable.asStateFlow()
    private var boundKey: String? = null
    private var session = 0L
    private var request = 0L
    private var work: Job? = null
    private var awaitingAcknowledgement: Long? = null
    private data class Saved(val source: Bitmap, val crop: AvatarCrop, val version: String?, val bytes: Int)
    private val saved = LinkedHashMap<String, Saved>(8, .75f, true)
    private var savedBytes = 0
    internal val cachedBytes: Int get() = savedBytes

    @MainThread
    fun bind(key: String) {
        if (boundKey == key) return
        boundKey = key
        dismiss()
    }

    /** Leaving this feature/authentication closes a draft; Activity recreation keeps it retained. */
    @MainThread
    fun deactivate(key: String) {
        if (boundKey != key) return
        boundKey = null
        dismiss()
    }

    @MainThread
    fun open(key: String, title: String, version: String?, load: suspend () -> Bitmap?) {
        if (boundKey == null) bind(key)
        if (boundKey != key || mutable.value?.saving == true) return
        dismiss()
        val cached = saved[key]
        if (cached != null && cached.version == version && !cached.source.isRecycled) {
            mutable.value = AvatarEditorState(key, title, cached.source, cached.crop, version = version)
            return
        }
        if (cached != null) removeSaved(key)
        mutable.value = AvatarEditorState(key, title, loading = true, version = version)
        loadSource(key, replacing = false, load = load)
    }

    /** Cancellation or a bad replacement never destroys the current editable source/crop. */
    @MainThread
    fun replace(key: String, load: suspend () -> Bitmap) {
        val current = mutable.value ?: return
        if (boundKey != key || current.key != key || current.saving) return
        cancelWork()
        mutable.value = current.copy(loading = true, error = null)
        loadSource(key, replacing = true, load = load)
    }

    private fun loadSource(key: String, replacing: Boolean, load: suspend () -> Bitmap?) {
        val expectedSession = session
        val expectedRequest = ++request
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            // Preserve the owned result even when withContext's prompt cancellation drops its return.
            // load must transfer a newly decoded editor bitmap, not another component's cached image.
            var loaded: Bitmap? = null
            var published = false
            try {
                withContext(Dispatchers.IO) { loaded = load() }
                currentCoroutineContext().ensureActive()
                if (!current(key, expectedSession, expectedRequest)) return@launch
                val current = mutable.value ?: return@launch
                val source = loaded
                if (source != null && source.isRecycled) throw IllegalArgumentException("Не удалось открыть фото. Выберите другое изображение.")
                if (replacing && source == null) throw IllegalArgumentException("Не удалось открыть фото. Выберите другое изображение.")
                mutable.value = current.copy(source = source, crop = AvatarCrop(), loading = false, error = null)
                published = true
            } catch (cancelled: CancellationException) {
                if (current(key, expectedSession, expectedRequest)) mutable.value = mutable.value?.copy(loading = false)
                throw cancelled
            } catch (error: Exception) {
                if (current(key, expectedSession, expectedRequest)) mutable.value = mutable.value?.copy(
                    loading = false, error = safeError(error, "Не удалось открыть фото. Выберите другое изображение."))
            } finally {
                if (!published) loaded?.let { image ->
                    if (!image.isRecycled && mutable.value?.source !== image && saved.values.none { it.source === image }) image.recycle()
                }
                if (current(key, expectedSession, expectedRequest)) work = null
            }
        }
        work = job
        job.start()
    }

    @MainThread
    fun crop(key: String, value: AvatarCrop) {
        val current = mutable.value ?: return
        if (boundKey != key || current.key != key || current.saving || current.loading || current.source == null) return
        mutable.value = current.copy(crop = value.normalized(), error = null)
    }

    @MainThread
    fun close(key: String) {
        val current = mutable.value ?: return
        if (current.key == key && !current.saving) dismiss()
    }

    /** Export first; the upload action receives ownership of bytes only for the active session. */
    @MainThread
    fun submit(key: String, revision: Long, onReady: (ByteArray) -> Unit) {
        val current = mutable.value ?: return
        val source = current.source ?: return
        if (boundKey != key || current.key != key || current.loading || current.saving) return
        cancelWork()
        val expectedSession = session
        val expectedRequest = ++request
        val crop = current.crop
        mutable.value = current.copy(saving = true, error = null, pendingRevision = revision)
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            // Keep ownership outside withContext: prompt cancellation at its return must erase it.
            var encoded: ByteArray? = null
            try {
                withContext(Dispatchers.Default) { encoded = exportAvatarCrop(source, crop) }
                currentCoroutineContext().ensureActive()
                if (!current(key, expectedSession, expectedRequest)) return@launch
                awaitingAcknowledgement = expectedSession
                onReady(checkNotNull(encoded))
                encoded = null
            } catch (cancelled: CancellationException) {
                if (current(key, expectedSession, expectedRequest)) {
                    awaitingAcknowledgement = null
                    mutable.value = mutable.value?.copy(saving = false, pendingRevision = null)
                }
                throw cancelled
            } catch (error: Exception) {
                if (current(key, expectedSession, expectedRequest)) {
                    awaitingAcknowledgement = null
                    mutable.value = mutable.value?.copy(saving = false, pendingRevision = null,
                        error = safeError(error, "Не удалось сохранить фото. Повторите попытку."))
                }
            } finally {
                encoded?.fill(0)
                if (current(key, expectedSession, expectedRequest)) work = null
            }
        }
        work = job
        job.start()
    }

    /** A changed completion revision belongs to the upload started by submit, not recomposition. */
    @MainThread
    fun acknowledge(key: String, revision: Long, error: String?, version: String?) {
        val current = mutable.value ?: return
        if (boundKey != key || current.key != key || !current.saving || current.pendingRevision == null ||
            current.pendingRevision == revision || awaitingAcknowledgement != session) return
        awaitingAcknowledgement = null
        if (error != null) {
            mutable.value = current.copy(saving = false, pendingRevision = null,
                error = error.ifBlank { "Не удалось сохранить фото. Повторите попытку." })
            return
        }
        current.source?.let { rememberSaved(key, it, current.crop, version) }
        dismiss()
    }

    private fun current(key: String, expectedSession: Long, expectedRequest: Long) =
        boundKey == key && mutable.value?.key == key && session == expectedSession && request == expectedRequest

    private fun cancelWork() {
        request++
        work?.cancel()
        work = null
        awaitingAcknowledgement = null
    }

    private fun dismiss() {
        session++
        cancelWork()
        mutable.value = null
    }

    private fun rememberSaved(key: String, source: Bitmap, crop: AvatarCrop, version: String?) {
        removeSaved(key)
        val bytes = source.allocationByteCount
        if (bytes > MAX_SAVED_BYTES) return
        saved[key] = Saved(source, crop.normalized(), version, bytes)
        savedBytes += bytes
        val entries = saved.entries.iterator()
        while ((savedBytes > MAX_SAVED_BYTES || saved.size > MAX_SAVED_ENTRIES) && entries.hasNext()) {
            savedBytes -= entries.next().value.bytes
            entries.remove()
        }
        // Bitmaps already published to Compose must not be recycled during a departing frame.
    }

    private fun removeSaved(key: String) { saved.remove(key)?.let { savedBytes -= it.bytes } }
    private fun safeError(error: Exception, fallback: String): String =
        (error as? IllegalArgumentException)?.message?.takeIf { it.isNotBlank() && it.length <= 200 } ?: fallback

    override fun onCleared() {
        dismiss()
        saved.clear()
        savedBytes = 0
        super.onCleared()
    }

    private companion object { const val MAX_SAVED_BYTES = 8 * 1024 * 1024; const val MAX_SAVED_ENTRIES = 16 }
}
