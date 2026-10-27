package com.meetspace.app

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.meetspace.app.ui.WorkspaceTab
import com.meetspace.app.ui.RecordingsUiState
import com.meetspace.app.ui.RecordingsWorkspace

@Composable
fun RecordingsHost(model: RecordingsViewModel, state: RecordingsUiState, playback: RecordingPlayback?, onWorkspaceTab: ((WorkspaceTab) -> Unit)? = null, profileLabel: String = "") {
    val resolver = LocalContext.current.applicationContext.contentResolver
    val previewIdentity = model.previewIdentity
    var previewGeneration by remember(model) { mutableIntStateOf(0) }
    val previews = remember(model, previewIdentity, previewGeneration) {
        RecordingPreviewLoader(model::previewAccess, isCurrent = { previewIdentity != null && previewIdentity == model.previewIdentity })
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(previews, lifecycle) {
        var stopped = false
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { stopped = true; previews.close() }
            if (event == Lifecycle.Event.ON_START && stopped) { stopped = false; previewGeneration++ }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); previews.close() }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        val cleanupFailed = model.cleanupFailureHandler()
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            if (!model.exportTo(cleanup = { RecordingFiles.removePartial(resolver, uri, cleanupFailed) }) { access, progress -> RecordingFiles.write(resolver, uri, access, progress) })
                RecordingFiles.removePartial(resolver, uri, cleanupFailed)
        } else model.discardExport()
    }
    CompositionLocalProvider(LocalRecordingPreviews provides previews) {
        RecordingsWorkspace(state, model.actions.copy(download = { recording, file ->
            model.queueExport(recording, file)?.let { request ->
                try { export.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(request.file.mimeType).putExtra(Intent.EXTRA_TITLE, MessagingFiles.safeName(request.file.filename))) }
                catch (_: Exception) { model.discardExport(); model.exportError() }
            }
        }), onWorkspaceTab = onWorkspaceTab, profileLabel = profileLabel,
            playingFileId = playback?.access?.file?.id, inlinePlayer = playback?.let { current ->
                { RecordingPlayer(current, model::closePlayback, retry = {
                    val recording = state.detail.data
                    val file = recording?.files?.firstOrNull { it.id == current.access.file.id }
                    if (recording != null && file != null) model.play(recording, file) else model.closePlayback()
                }, inline = true) }
            })
    }
}
