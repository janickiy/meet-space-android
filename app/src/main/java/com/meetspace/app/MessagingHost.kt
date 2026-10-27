package com.meetspace.app

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import com.meetspace.app.ui.MessagingWorkspace
import com.meetspace.app.ui.MessagingUiState
import com.meetspace.core.model.ChatAttachment
import kotlinx.coroutines.launch

@Composable
fun MessagingHost(model: MessagingViewModel, state: MessagingUiState, pushController: PushController, pushState: PushState,
    authScope: String, openMeeting: (String) -> Unit, modifier: Modifier = Modifier,
    inboxRequested: Boolean = false, consumeInbox: () -> Unit = {},
    newChatRequested: Boolean = false, foldersRequested: Boolean = false,
    consumeWorkspaceRequest: () -> Unit = {}, serverOrigin: String? = null, onStartCall: (() -> Unit)? = null,
    personalCall: DirectCallSession? = null, onResumeCall: () -> Unit = {},
    notificationBell: (@Composable () -> Unit)? = null, onNotifications: (() -> Unit)? = null,
    openNotification: ((com.meetspace.core.model.InboxNotification) -> Unit)? = null,
    conversationNavigationRevision: Int = 0) {
    val context = LocalContext.current
    val resolver = context.applicationContext.contentResolver
    val effects = rememberCoroutineScope()
    val selected by rememberUpdatedState(state.selectedId)
    val identity by rememberUpdatedState(authScope)
    var importTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var exportTarget by remember { mutableStateOf<Pair<String, ChatAttachment>?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = importTarget; importTarget = null
        if (uri != null && target != null && target.first == identity && target.second == selected) {
            effects.launch {
                try {
                    val metadata = MessagingFiles.metadata(resolver, uri)
                    if (target.first == identity && target.second == selected)
                        model.upload(metadata.name, metadata.mimeType) { MessagingFiles.read(resolver, uri) }
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (error: Exception) { model.message((error as? com.meetspace.core.model.AppFailure)?.message ?: "Не удалось открыть файл.") }
            }
        }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val target = exportTarget; exportTarget = null
        if (uri != null) {
            val accepted = target != null && target.first == identity && target.second.conversationId == selected &&
                model.export(target.second, onFailure = { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { resolver.delete(uri, null, null) }; Unit } }) { bytes -> MessagingFiles.write(resolver, uri, bytes) }
            if (!accepted) effects.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { resolver.delete(uri, null, null) } }
        }
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        model.notificationPermission(NotificationManagerCompat.from(context).areNotificationsEnabled())
    }
    LaunchedEffect(authScope) { model.notificationPermission(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    MessagingWorkspace(state.copy(pushConfigured = pushState.configured, pushEnabled = pushState.enabled,
        pushBusy = pushState.busy, pushMessage = pushState.message), model.actions.copy(
        enablePush = { effects.launch { pushController.enable() } },
        disablePush = { effects.launch { pushController.disable() } },
        pickAttachment = { selected?.let { importTarget = identity to it; picker.launch(arrayOf("*/*")) } },
        download = { attachment -> exportTarget = identity to attachment; save.launch(MessagingFiles.safeName(attachment.filename)) },
        openMeeting = openMeeting,
        openNotification = openNotification ?: { item ->
            model.readNotification(item.id)
            val conversation = item.conversationId
            if (conversation != null) model.open(conversation)
            else item.conferenceId?.let(openMeeting)
            Unit
        },
        requestSystemNotifications = {
            if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            else model.notificationPermission(NotificationManagerCompat.from(context).areNotificationsEnabled())
        },
    ), modifier, inboxRequested, consumeInbox, newChatRequested, foldersRequested, consumeWorkspaceRequest, serverOrigin, onStartCall,
        personalCallLabel = personalCall?.let { if (it.call?.status == "accepted") "${it.elapsedSeconds / 60}:${(it.elapsedSeconds % 60).toString().padStart(2, '0')}" else if (it.incoming) "Входящий вызов" else "Вы звоните…" },
        onResumeCall = onResumeCall, notificationBell = notificationBell, onNotifications = onNotifications,
        conversationNavigationRevision = conversationNavigationRevision)
}
