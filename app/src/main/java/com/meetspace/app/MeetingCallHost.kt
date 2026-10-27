package com.meetspace.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.withResumed
import com.meetspace.app.ui.*
import com.meetspace.core.media.CallEngine
import com.meetspace.core.media.CallState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Native document pickers belong to the current account and conference; no persisted URI grants. */
@Composable
fun MeetingCallHost(engine: CallEngine, state: CallState, model: CallDiscussionViewModel, details: CallUiDetails,
    authScope: String, profileLabel: String, onWorkspaceTab: ((WorkspaceTab) -> Unit)?) {
    val context = LocalContext.current
    val resolver = context.applicationContext.contentResolver
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val effects = rememberCoroutineScope()
    val identity by rememberUpdatedState(authScope to state.conferenceId)
    var picking by rememberSaveable(stateSaver = listSaver<Pair<String, String>?, String>(
        save = { it?.let { value -> listOf(value.first, value.second) }.orEmpty() },
        restore = { it.takeIf { values -> values.size == 2 }?.let { values -> values[0] to values[1] } },
    )) { mutableStateOf(null) }
    data class Export(val identity: Pair<String, String>, val message: String, val attachment: String)
    var exporting by rememberSaveable(stateSaver = listSaver<Export?, String>(
        save = { it?.let { value -> listOf(value.identity.first, value.identity.second, value.message, value.attachment) }.orEmpty() },
        restore = { it.takeIf { values -> values.size == 4 }?.let { values -> Export(values[0] to values[1], values[2], values[3]) } },
    )) { mutableStateOf(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = picking; picking = null
        if (uri != null && target != null && target == identity) effects.launch {
            try {
                // Activity results can arrive before repeatOnLifecycle restarts the discussion.
                lifecycle.withResumed { if (target == identity) model.foreground(true) }
                val metadata = MessagingFiles.metadata(resolver, uri)
                if (target == identity) model.upload(metadata.name, metadata.mimeType) { MessagingFiles.read(resolver, uri) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { if (target == identity) model.message((error as? com.meetspace.core.model.AppFailure)?.message ?: "Не удалось открыть файл.") }
        }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val target = exporting; exporting = null
        if (uri != null) effects.launch {
            var accepted = false
            try {
                lifecycle.withResumed { if (target?.identity == identity) model.foreground(true) }
                accepted = target != null && target.identity == identity && model.export(target.message, target.attachment,
                    onFailure = { withContext(Dispatchers.IO) { runCatching { resolver.delete(uri, null, null) }; Unit } }) {
                        MessagingFiles.write(resolver, uri, it)
                    }
            } finally {
                if (!accepted) withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { resolver.delete(uri, null, null) }
                }
            }
        }
    }
    val actions = model.actions.copy(
        attachChat = { if (details.chat.canAttach) { picking = identity; picker.launch(arrayOf("image/jpeg", "image/png", "image/webp", "application/pdf", "text/plain", "text/csv")) } },
        downloadChatAttachment = { message, attachment -> model.attachment(message, attachment)?.let {
            exporting = Export(identity, message, attachment); save.launch(MessagingFiles.safeName(it.filename))
        } },
    )
    CallHost(engine, state, onWorkspaceTab, profileLabel, details, actions, recordingActions = model.recordingActions, authScope = authScope)
}
