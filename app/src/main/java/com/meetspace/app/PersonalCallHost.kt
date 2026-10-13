package com.meetspace.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.meetspace.app.ui.*
import com.meetspace.core.media.*

private data class PersonalPermissionTarget(val callId: String, val selfId: String, val clientId: String, val action: String)

@Composable
fun PersonalCallHost(model: DirectCallViewModel, session: DirectCallSession, engine: CallEngine,
    media: CallState, selfName: String, minimize: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val callId = session.call?.id
    val (preferences, _) = rememberAccountDevicePreferences()
    var permissionTarget by remember { mutableStateOf<PersonalPermissionTarget?>(null) }
    var projectionTarget by remember { mutableStateOf<String?>(null) }
    var hostActive by remember { mutableStateOf(true) }
    var routeChosen by remember(callId) { mutableStateOf(false) }
    DisposableEffect(lifecycle) {
        hostActive = true
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                // A grant returned after backgrounding must not undo onStop's device pause.
                permissionTarget = null
                projectionTarget = null
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            hostActive = false
            permissionTarget = null
            projectionTarget = null
            lifecycle.removeObserver(observer)
        }
    }
    fun allowed(name: String) = ContextCompat.checkSelfPermission(context, name) == PackageManager.PERMISSION_GRANTED
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val target = permissionTarget; permissionTarget = null
        val latest = model.state.value
        if (target == null || !hostActive || !latest.visible || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) ||
            target.callId != latest.call?.id || target.selfId != latest.selfId || target.clientId != latest.clientId || !latest.canControl) return@rememberLauncherForActivityResult
        when (target.action) {
            "accept" -> model.accept(latest.microphoneWanted && allowed(Manifest.permission.RECORD_AUDIO), latest.cameraWanted && allowed(Manifest.permission.CAMERA))
            "microphone", "camera" -> {
                val microphone = target.action == "microphone"
                if (!allowed(if (microphone) Manifest.permission.RECORD_AUDIO else Manifest.permission.CAMERA)) {
                    model.message(if (microphone) "Микрофон выключен. Доступ можно разрешить в настройках Android."
                        else "Камера выключена. Доступ можно разрешить в настройках Android.")
                    return@rememberLauncherForActivityResult
                }
                val actual = engine.state.value
                if (actual.directCallId != target.callId) return@rememberLauncherForActivityResult
                if (actual.phase in setOf(CallPhase.CONNECTING, CallPhase.RECONNECTING) ||
                    latest.call?.status == "accepted" && actual.phase == CallPhase.PREJOIN) {
                    // Granting permission is not a deferred instruction to capture hardware.
                    model.message(if (microphone) "Доступ разрешён. Включите микрофон после подключения."
                        else "Доступ разрешён. Включите камеру после подключения.")
                } else if (actual.phase == CallPhase.PREJOIN && latest.call?.status == "ringing" ||
                    actual.phase == CallPhase.CONNECTED && latest.ownsMedia) {
                    if (microphone) model.microphone(true) else model.camera(true)
                }
            }
        }
    }
    fun request(action: String, requested: List<String>) {
        val current = model.state.value
        val target = current.call?.id ?: return
        if (permissionTarget != null || !hostActive || !current.visible || target != callId || !current.canControl ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        permissionTarget = PersonalPermissionTarget(target, current.selfId, current.clientId, action)
        permissions.launch(requested.toTypedArray())
    }
    val projection = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val target = projectionTarget; projectionTarget = null
        val latest = model.state.value
        val currentMedia = engine.state.value
        if (target == null || target != latest.call?.id || currentMedia.directCallId != target || !latest.ownsMedia || latest.ending || currentMedia.phase != CallPhase.CONNECTED) return@rememberLauncherForActivityResult
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, ScreenShareService::class.java)
                    .putExtra(ScreenShareService.CONSENT, result.data)
                    .putExtra(ScreenShareService.CONFERENCE, currentMedia.conferenceId)
                    .putExtra(ScreenShareService.DIRECT_CALL, target))
            } catch (_: Exception) { model.message("Не удалось запустить демонстрацию экрана.") }
        } else model.message("Демонстрация экрана отменена.")
    }
    LaunchedEffect(callId, session.ownsMedia, session.call?.status) {
        // A ringing incoming call never opens Android device permission dialogs by itself.
        if (session.ownsMedia && session.call?.status == "ringing" && session.microphoneWanted) {
            if (allowed(Manifest.permission.RECORD_AUDIO)) model.microphone(true)
            else request("microphone", listOf(Manifest.permission.RECORD_AUDIO))
        }
    }
    LaunchedEffect(media.phase, media.routes, preferences.speaker) {
        if (media.phase != CallPhase.CONNECTED) routeChosen = false
        if (media.phase == CallPhase.CONNECTED && !routeChosen) {
            preferredAudioRoute(preferences.speaker, media.routes)?.let {
                routeChosen = true
                if (!it.selected) engine.audioRoute(it.id)
            }
        }
    }
    BackHandler(onBack = minimize)
    val mediaForCall = if (media.directCallId == callId) media else CallState()
    val phase = when {
        session.otherDevice -> PersonalCallUiPhase.ENDED
        session.call?.active == false -> PersonalCallUiPhase.ENDED
        session.ending -> PersonalCallUiPhase.ENDED
        session.incoming -> PersonalCallUiPhase.INCOMING
        session.call?.status == "ringing" || session.pendingPeer != null -> PersonalCallUiPhase.OUTGOING
        mediaForCall.phase == CallPhase.CONNECTED -> PersonalCallUiPhase.CONNECTED
        mediaForCall.phase == CallPhase.RECONNECTING -> PersonalCallUiPhase.RECONNECTING
        mediaForCall.phase == CallPhase.FAILED -> PersonalCallUiPhase.FAILED
        else -> PersonalCallUiPhase.CONNECTING
    }
    val statusText = when {
        session.otherDevice -> "Звонок открыт на другом устройстве"
        session.ending -> "Завершаем звонок…"
        session.uncertain -> "Проверяем результат вызова"
        else -> when (session.call?.status) {
            "declined" -> "Вызов отклонён"
            "cancelled" -> "Вызов отменён"
            "missed" -> "Нет ответа"
            "ended" -> "Звонок завершён"
            else -> null
        }
    }
    val waiting = session.call?.status == "ringing"
    val otherMedia = media.visible && media.directCallId != callId
    PersonalCallUi(PersonalCallUiState(callId = callId.orEmpty(), peerName = session.peer?.displayName.orEmpty(), selfName = selfName,
        phase = phase, media = mediaForCall.copy(
            microphone = if (waiting) session.microphoneWanted && allowed(Manifest.permission.RECORD_AUDIO) else mediaForCall.microphone,
            camera = mediaForCall.camera),
        elapsedSeconds = session.elapsedSeconds.takeIf { session.call?.acceptedAt != null },
        busy = session.busy || permissionTarget != null,
        error = session.error ?: if (otherMedia) "Сначала завершите текущую встречу, чтобы ответить на звонок." else mediaForCall.error,
        controlsEnabled = session.canControl && !otherMedia,
        statusText = statusText),
        PersonalCallActions(minimize = minimize,
            microphone = {
                val enabled = if (waiting) session.microphoneWanted && allowed(Manifest.permission.RECORD_AUDIO) else mediaForCall.microphone
                if (enabled) model.microphone(false)
                else if (allowed(Manifest.permission.RECORD_AUDIO)) model.microphone(true)
                else request("microphone", listOf(Manifest.permission.RECORD_AUDIO))
            }, camera = {
                if (mediaForCall.camera) model.camera(false)
                else if (allowed(Manifest.permission.CAMERA)) model.camera(true)
                else request("camera", listOf(Manifest.permission.CAMERA))
            }, share = {
                if (mediaForCall.sharing) engine.stopScreen()
                else if (projectionTarget == null && mediaForCall.phase == CallPhase.CONNECTED && session.ownsMedia) {
                    projectionTarget = callId
                    projection.launch(context.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
                }
            }, end = model::end, accept = {
                val needed = buildList {
                    if (session.microphoneWanted && !allowed(Manifest.permission.RECORD_AUDIO)) add(Manifest.permission.RECORD_AUDIO)
                    if (session.cameraWanted && !allowed(Manifest.permission.CAMERA)) add(Manifest.permission.CAMERA)
                }
                if (needed.isEmpty()) model.accept(session.microphoneWanted, session.cameraWanted)
                else request("accept", needed)
            }, decline = model::decline, switchCamera = engine::switchCamera))
    if (session.uncertain && !session.busy) AlertDialog(onDismissRequest = model::minimize,
        title = { Text("Результат вызова не подтверждён") },
        text = { Text("Сервер мог уже создать звонок. Повторная проверка использует тот же запрос и не создаёт второй вызов.") },
        confirmButton = { TextButton(onClick = model::retryStart) { Text("Проверить") } },
        dismissButton = { TextButton(onClick = minimize) { Text("К переписке") } })
}
