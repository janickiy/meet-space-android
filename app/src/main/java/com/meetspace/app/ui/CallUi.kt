package com.meetspace.app.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.meetspace.app.CallPermissionRequest
import com.meetspace.app.ScreenShareService
import com.meetspace.app.CallJoinDevices
import com.meetspace.app.preferredAudioRoute
import com.meetspace.app.preferredJoinDevices
import com.meetspace.app.rememberAccountDevicePreferences
import com.meetspace.core.media.*
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

@Composable
fun CallHost(engine: CallEngine, state: CallState, onWorkspaceTab: ((WorkspaceTab) -> Unit)? = null, profileLabel: String = "", details: CallUiDetails = CallUiDetails(), panelActions: CallPanelActions = CallPanelActions(), recordingActions: CallRecordingActions = CallRecordingActions(), authScope: String = "") {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentAuthScope by rememberUpdatedState(authScope)
    val joinRequest = remember(engine) { CallPermissionRequest() }
    val microphoneRequest = remember(engine) { CallPermissionRequest() }
    val cameraRequest = remember(engine) { CallPermissionRequest() }
    val notificationRequest = remember(engine) { CallPermissionRequest() }
    val projectionRequest = remember(engine) { CallPermissionRequest() }
    var hostActive by remember { mutableStateOf(true) }
    val (preferences, _) = rememberAccountDevicePreferences()
    var microphoneEdited by rememberSaveable(state.conferenceId) { mutableStateOf(false) }
    var cameraEdited by rememberSaveable(state.conferenceId) { mutableStateOf(false) }
    var pendingJoin by rememberSaveable(state.conferenceId) { mutableStateOf(false) }
    var joinMicrophone by rememberSaveable(state.conferenceId) { mutableStateOf(false) }
    var joinCamera by rememberSaveable(state.conferenceId) { mutableStateOf(false) }
    var routeChosen by rememberSaveable(state.conferenceId) { mutableStateOf(false) }
    var requestingProjection by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }
    fun foreground() = hostActive && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    DisposableEffect(engine, lifecycle, authScope, state.conferenceId, state.directCallId, state.participantId, state.phase) {
        hostActive = true
        fun invalidateDevices() {
            joinRequest.invalidate(); microphoneRequest.invalidate(); cameraRequest.invalidate(); notificationRequest.invalidate()
            pendingJoin = false
        }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) {
            invalidateDevices()
            // A full-screen Android projection picker may stop the caller. Keep its
            // explicit request, but never accept a result while the caller is backgrounded.
            if (!projectionRequest.awaitingResult) requestingProjection = false
        } }
        lifecycle.addObserver(observer)
        onDispose {
            hostActive = false
            invalidateDevices(); projectionRequest.invalidate(); requestingProjection = false
            lifecycle.removeObserver(observer)
        }
    }
    BackHandler { confirmExit = true }
    fun join(devices: CallJoinDevices) {
        if (state.microphone != devices.microphone) engine.microphone(devices.microphone)
        if (state.camera != devices.camera) engine.camera(devices.camera)
        engine.connect()
    }
    val joinPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val wasPending = pendingJoin
        pendingJoin = false
        val current = engine.state.value
        val accepted = joinRequest.consume(currentAuthScope, current, foreground())
        if (wasPending && accepted && current.phase == CallPhase.PREJOIN) {
            join(CallJoinDevices(
                joinMicrophone && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
                joinCamera && ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
            ))
        }
    }
    LaunchedEffect(state.phase, state.routes, preferences.speaker) {
        if (state.phase == CallPhase.CONNECTED && !routeChosen) {
            preferredAudioRoute(preferences.speaker, state.routes)?.let { preferred ->
                routeChosen = true
                if (!preferred.selected) engine.audioRoute(preferred.id)
            }
        }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (!microphoneRequest.consume(currentAuthScope, engine.state.value, foreground())) return@rememberLauncherForActivityResult
        if (it) engine.microphone(true) else engine.message("Микрофон выключен. Доступ можно разрешить в настройках Android.")
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (!cameraRequest.consume(currentAuthScope, engine.state.value, foreground())) return@rememberLauncherForActivityResult
        if (it) engine.camera(true) else engine.message("Камера выключена. Доступ можно разрешить в настройках Android.")
    }
    val bluetooth = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { engine.refreshAudioRoutes() }
    val projection = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        requestingProjection = false
        val current = engine.state.value
        if (!projectionRequest.consume(currentAuthScope, current, foreground()) || current.directCallId != null || current.phase != CallPhase.CONNECTED) return@rememberLauncherForActivityResult
        if (it.resultCode == Activity.RESULT_OK && it.data != null) {
            try { ContextCompat.startForegroundService(context, Intent(context, ScreenShareService::class.java).putExtra(ScreenShareService.CONSENT, it.data).putExtra(ScreenShareService.CONFERENCE, current.conferenceId)) }
            catch (_: Exception) { engine.message("Не удалось запустить демонстрацию экрана. Повторите из приложения.") }
        } else engine.message("Демонстрация экрана отменена.")
    }
    val startProjection: (CallState) -> Unit = { target ->
        if (foreground() && target.phase == CallPhase.CONNECTED && target.directCallId == null && projectionRequest.begin(currentAuthScope, target)) {
            requestingProjection = true
            try { projection.launch(context.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent()) }
            catch (_: Exception) {
                projectionRequest.consume(currentAuthScope, engine.state.value, false); requestingProjection = false
                engine.message("Не удалось запросить демонстрацию экрана. Повторите из приложения.")
            }
        } else requestingProjection = false
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        val current = engine.state.value
        if (notificationRequest.consume(currentAuthScope, current, foreground())) startProjection(current)
        else requestingProjection = false
    }
    CallScreen(state, onWorkspaceTab = onWorkspaceTab, profileLabel = profileLabel, devicePreferences = preferences, joinPending = pendingJoin, details = details, panelActions = panelActions, recordingActions = recordingActions,
        microphone = {
            microphoneEdited = true
            if (state.microphone) engine.microphone(false)
            else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) engine.microphone(true)
            else if (foreground() && microphoneRequest.begin(currentAuthScope, state)) micPermission.launch(Manifest.permission.RECORD_AUDIO)
        },
        camera = {
            cameraEdited = true
            if (state.camera) engine.camera(false)
            else if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) engine.camera(true)
            else if (foreground() && cameraRequest.begin(currentAuthScope, state)) cameraPermission.launch(Manifest.permission.CAMERA)
        },
        share = {
            if (state.sharing) engine.stopScreen(state.conferenceId, state.directCallId)
            else if (!requestingProjection) {
                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    if (foreground() && notificationRequest.begin(currentAuthScope, state)) {
                        requestingProjection = true
                        notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                } else startProjection(state)
            }
        }, connect = connect@{
            if (pendingJoin) return@connect
            if (state.phase != CallPhase.PREJOIN) { engine.connect(); return@connect }
            val devices = preferredJoinDevices(state, preferences, microphoneEdited, cameraEdited)
            val permissions = buildList {
                if (devices.microphone && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.RECORD_AUDIO)
                if (devices.camera && ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.CAMERA)
            }
            if (permissions.isEmpty()) join(devices)
            else if (foreground() && joinRequest.begin(currentAuthScope, state)) {
                pendingJoin = true; joinMicrophone = devices.microphone; joinCamera = devices.camera
                joinPermissions.launch(permissions.toTypedArray())
            }
        }, leave = { confirmExit = true }, switchCamera = engine::switchCamera,
        route = { routeChosen = true; engine.audioRoute(it) },
        refreshRoutes = {
            if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) bluetooth.launch(Manifest.permission.BLUETOOTH_CONNECT)
            else engine.refreshAudioRoutes()
        },
        openSystemSettings = {
            try { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
            catch (_: Exception) { engine.message("Не удалось открыть настройки приложения Android.") }
        },
    )
    if (confirmExit) AlertDialog(onDismissRequest = { confirmExit = false }, title = { Text("Выйти из звонка?") },
        text = { Text("Камера, микрофон и демонстрация экрана будут остановлены.") },
        confirmButton = { TextButton(onClick = { confirmExit = false; engine.leave() }) { Text("Выйти") } },
        dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("Остаться") } })
}

private val CallBackground = Color(0xFF171F2D)
private val CallTileColor = Color(0xFF2B3546)
private val CallMuted = Color(0xFF9EACBF)

@Composable
fun CallScreen(
    state: CallState, microphone: () -> Unit = {}, camera: () -> Unit = {}, share: () -> Unit = {},
    connect: () -> Unit = {}, leave: () -> Unit = {}, switchCamera: () -> Unit = {},
    route: (Int) -> Unit = {}, refreshRoutes: () -> Unit = {},
    onWorkspaceTab: ((WorkspaceTab) -> Unit)? = null, profileLabel: String = "",
    devicePreferences: AccountDevicePreferences = AccountDevicePreferences(), joinPending: Boolean = false,
    openSystemSettings: (() -> Unit)? = null,
    details: CallUiDetails = CallUiDetails(), panelActions: CallPanelActions = CallPanelActions(), recordingActions: CallRecordingActions = CallRecordingActions(),
    // Deterministic screenshot content is supplied only by androidTest; production uses VideoFeed.
    stageContentOverride: (@Composable () -> Unit)? = null, sharePreviewName: String? = null,
) {
    var selectedPanel by rememberSaveable(state.conferenceId) { mutableStateOf<CallPanel?>(null) }
    var audio by remember { mutableStateOf(false) }
    var devices by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var recordingDialog by rememberSaveable(state.conferenceId) { mutableStateOf(false) }
    val prejoin = state.phase in setOf(CallPhase.PREJOIN, CallPhase.FAILED)
    fun openPanel(panel: CallPanel?) {
        selectedPanel = if (selectedPanel == panel) null else panel
        panelActions.open(selectedPanel)
    }
    BackHandler(enabled = selectedPanel != null && !prejoin) { selectedPanel = null; panelActions.open(null) }
    Surface(
        color = if (prejoin) MaterialTheme.colorScheme.background else CallBackground,
        contentColor = if (prejoin) MaterialTheme.colorScheme.onBackground else Color.White,
        modifier = Modifier.fillMaxSize().testTag("call-screen"),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(if (!prejoin || onWorkspaceTab != null) WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal) else WindowInsets.safeDrawing).then(if (!prejoin) Modifier.imePadding() else Modifier)) {
            val widePrejoin = maxWidth >= 840.dp && LocalDensity.current.fontScale <= 1.3f
            val phone = maxWidth < 600.dp
            // A short landscape window uses the compact controls, with sharing in the menu,
            // so enlarged labels cannot push a second toolbar row beyond the screen.
            val shortHeight = maxHeight < 400.dp
            val compactControls = phone || shortHeight
            val viewportWidth = maxWidth
            val viewportHeight = maxHeight
            if (prejoin) Prejoin(state, widePrejoin, !phone, maxWidth >= 780.dp, microphone, camera, connect, leave, switchCamera, onWorkspaceTab, profileLabel, joinPending, { devices = true })
            else Column(Modifier.fillMaxSize()) {
                CallHeader(state, details, compactControls, menu, { menu = it }, share, switchCamera, { refreshRoutes(); audio = true }, openSystemSettings, { recordingDialog = true }, shortHeight)
                if (!shortHeight && details.room.self != null) Row(Modifier.fillMaxWidth().padding(horizontal = if (phone) 14.dp else 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Вы — ${meetingRole(details.room.self?.role).lowercase()}", Modifier.weight(1f), color = CallMuted, fontSize = 11.sp)
                    TextButton({ openPanel(CallPanel.PARTICIPANTS) }) { Text(if (details.room.self?.role == "owner" && details.room.waiting.isNotEmpty()) "Зал ожидания · ${details.room.waiting.size}" else "Участники", color = Color(0xFFDFE6FF), fontSize = 11.sp) }
                }
                Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = if (phone) 12.dp else 20.dp)
                    .padding(top = if (shortHeight) 4.dp else if (phone) 8.dp else 6.dp, bottom = if (shortHeight) 4.dp else if (phone) 8.dp else 12.dp).testTag("call-body")) {
                    val panel = selectedPanel
                    val inlinePanel = panel != null && panel != CallPanel.PARTICIPANTS && viewportWidth >= 780.dp
                    val panelWidth = if (viewportWidth <= 1050.dp) 310.dp else 350.dp
                    val columns = if (phone || (inlinePanel && viewportWidth <= 1050.dp)) 2 else 4
                    val close = { selectedPanel = null; panelActions.open(null) }
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        CallStage(state, phone, columns, Modifier.weight(1f), devicePreferences, stageContentOverride, sharePreviewName)
                        if (inlinePanel && panel != null) CallPanelContent(panel, state, details, panelActions, close, Modifier.width(panelWidth).fillMaxHeight())
                    }
                    if (panel != null && panel != CallPanel.PARTICIPANTS && !inlinePanel) CallPanelContent(panel, state, details, panelActions, close, Modifier.fillMaxSize())
                    if (panel == CallPanel.PARTICIPANTS) Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
                        SideEffect { window?.setGravity(if (phone) Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL else Gravity.CENTER) }
                        Box(Modifier.fillMaxWidth().padding(if (phone) 14.dp else 20.dp), contentAlignment = Alignment.Center) {
                            MeetingRoomPanel(state, details.room, panelActions.room, Modifier.widthIn(max = 520.dp).fillMaxWidth(), close)
                        }
                    }
                }
                CallToolbar(state, compactControls, microphone, camera, share, leave, selectedPanel, ::openPanel, shortHeight)
                CallNavigation(onWorkspaceTab, selfName(state, profileLabel), phone, viewportHeight < 400.dp)
            }
        }
    }
    if (recordingDialog && !prejoin) CallRecordingDialog(state.title, details.recordingState, recordingActions, LocalConfiguration.current.screenWidthDp < 600) { recordingDialog = false }
    if (devices && prejoin) PrejoinDevices(state, joinPending, microphone, camera, route, openSystemSettings, { devices = false })
    if (audio) AlertDialog(onDismissRequest = { audio = false }, title = { Text("Аудиоустройство") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (state.routes.isEmpty()) Text("Используется системное аудиоустройство. Подключите гарнитуру и обновите список.")
            state.routes.forEach { item -> TextButton(onClick = { route(item.id); audio = false }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text((if (item.selected) "✓ " else "") + item.name) } }
            TextButton(onClick = refreshRoutes, modifier = Modifier.heightIn(min = 48.dp)) { Text("Обновить устройства") }
        }
    }, confirmButton = { TextButton(onClick = { audio = false }) { Text("Закрыть") } })
}

@Composable
private fun CallHeader(state: CallState, details: CallUiDetails, phone: Boolean, menu: Boolean, setMenu: (Boolean) -> Unit,
    share: () -> Unit, switchCamera: () -> Unit, audio: () -> Unit, openSystemSettings: (() -> Unit)?, recording: () -> Unit, shortHeight: Boolean) {
    val canCapture = state.phase == CallPhase.CONNECTED
    // Short windows reserve height for the shared picture while retaining every 48dp action target.
    Column(Modifier.fillMaxWidth().padding(horizontal = if (phone) 14.dp else 24.dp, vertical = if (shortHeight) 4.dp else if (phone) 10.dp else 13.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (phone) 9.dp else 12.dp)) {
            Column(Modifier.weight(1f).heightIn(min = 48.dp).then(if (phone) Modifier.clickable(role = Role.Button, onClickLabel = "Информация о встрече", onClick = { setMenu(true) }).testTag("call-info").semantics { contentDescription = "Информация о встрече" } else Modifier), verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically)) {
                Text(state.title, fontSize = if (phone) 13.sp else 17.sp, lineHeight = if (phone) 20.sp else 24.sp, letterSpacing = (-.3).sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
                val status = if (details.elapsedSeconds != null && state.phase == CallPhase.CONNECTED) callElapsed(details.elapsedSeconds) else state.status
                Text("$status / ${state.participants.count { it.online }} участников", fontSize = if (phone) 10.sp else 11.sp, lineHeight = 16.sp, color = CallMuted,
                    modifier = Modifier.testTag(if (details.elapsedSeconds != null && state.phase == CallPhase.CONNECTED) "call-elapsed" else "call-status"))
            }
            CallRecordingControl(details.recordingState, phone, recording)
            Box {
                if (!phone) IconButton({ setMenu(true) }, Modifier.size(48.dp).testTag("call-info")) { Icon(CallIcons.Ellipsis, "Информация о встрече", Modifier.size(20.dp), tint = Color(0xFFCAD4E4)) }
                DropdownMenu(menu, { setMenu(false) }, containerColor = Color(0xFF222C3B)) {
                    if (phone) DropdownMenuItem(text = { Text(if (state.sharing) "Остановить демонстрацию" else "Демонстрация экрана", color = Color.White) },
                        onClick = { setMenu(false); share() }, enabled = canCapture && !state.screenBlocked && !state.cameraBlocked,
                        leadingIcon = { Icon(CallIcons.MonitorUp, null, tint = CallMuted) }, modifier = Modifier.testTag("call-share"))
                    DropdownMenuItem(text = { Text("Аудиоустройство", color = Color.White) }, onClick = { setMenu(false); audio() },
                        leadingIcon = { Icon(Icons.Outlined.Headphones, null, tint = CallMuted) }, modifier = Modifier.testTag("call-audio"))
                    if (state.camera) DropdownMenuItem(text = { Text("Повернуть камеру", color = Color.White) }, onClick = { setMenu(false); switchCamera() },
                        enabled = canCapture, leadingIcon = { Icon(Icons.Outlined.Cameraswitch, null, tint = CallMuted) }, modifier = Modifier.testTag("call-switch-camera"))
                    openSystemSettings?.let { open -> DropdownMenuItem(text = { Text("Разрешения приложения", color = Color.White) }, onClick = { setMenu(false); open() }) }
                }
            }
        }
        CallError(state)
    }
}

private fun callElapsed(seconds: Long): String {
    val elapsed = seconds.coerceAtLeast(0)
    return if (elapsed >= 3600) "%d:%02d:%02d".format(java.util.Locale.ROOT, elapsed / 3600, elapsed / 60 % 60, elapsed % 60)
    else "%02d:%02d".format(java.util.Locale.ROOT, elapsed / 60, elapsed % 60)
}

@Composable
private fun CallPanelContent(panel: CallPanel, state: CallState, details: CallUiDetails, actions: CallPanelActions, close: () -> Unit, modifier: Modifier) {
    if (panel == CallPanel.PARTICIPANTS) MeetingRoomPanel(state, details.room, actions.room, modifier, close)
    else CallTextPanel(panel, state.title, if (panel == CallPanel.COMMENTS) details.comments else details.chat,
        if (panel == CallPanel.COMMENTS) details.commentCount else null, actions, close, modifier)
}

@Composable
private fun Prejoin(state: CallState, wide: Boolean, medium: Boolean, tabletShell: Boolean, microphone: () -> Unit, camera: () -> Unit, connect: () -> Unit, leave: () -> Unit, switchCamera: () -> Unit, onWorkspaceTab: ((WorkspaceTab) -> Unit)?, profileLabel: String, joinPending: Boolean, openDevices: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().heightIn(min = if (tabletShell) 84.dp else 77.dp).padding(horizontal = if (tabletShell) 30.dp else 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (tabletShell) { Brand(compact = true); Spacer(Modifier.width(24.dp)) }
            Text("Перед встречей", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            IconButton(leave, modifier = Modifier.size(48.dp).testTag("call-leave")) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Назад") }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(horizontal = if (tabletShell) 24.dp else 0.dp).clip(RoundedCornerShape(topStart = 25.dp, topEnd = 25.dp)).background(MaterialTheme.colorScheme.surface).padding(horizontal = if (tabletShell) 28.dp else 18.dp)) {
            val viewportHeight = maxHeight
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("call-prejoin-scroll"), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                if (wide) Row(Modifier.fillMaxWidth().heightIn(min = viewportHeight).testTag("call-prejoin-wide"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    PrejoinPreview(state, microphone, camera, switchCamera, Modifier.weight(1.25f), profileLabel, wide = true, medium = medium)
                    PrejoinInfo(state, connect, leave, Modifier.weight(1f), profileLabel, joinPending, openDevices)
                } else Column(Modifier.widthIn(max = 520.dp).fillMaxWidth().padding(vertical = 22.dp).testTag("call-prejoin-compact"), verticalArrangement = Arrangement.spacedBy(25.dp)) {
                    PrejoinPreview(state, microphone, camera, switchCamera, Modifier.fillMaxWidth(), profileLabel, wide = false, medium = medium)
                    PrejoinInfo(state, connect, leave, Modifier.fillMaxWidth(), profileLabel, joinPending, openDevices)
                }
            }
        }
        onWorkspaceTab?.let { WorkspaceNavigation(WorkspaceTab.CALLS, it, selfName(state, profileLabel), tabletShell) }
    }
}

@Composable
private fun PrejoinPreview(state: CallState, microphone: () -> Unit, camera: () -> Unit, switchCamera: () -> Unit, modifier: Modifier, profileLabel: String, wide: Boolean, medium: Boolean) {
    val enabled = state.phase == CallPhase.PREJOIN
    val localVideo = state.localVideo
    Box(modifier.aspectRatio(if (medium && !wide) 16f / 10f else 4f / 3f).heightIn(min = 220.dp).clip(RoundedCornerShape(if (medium) 25.dp else 20.dp)).background(Color(0xFF222D40)).testTag("call-preview")) {
        if (localVideo != null) VideoContent(localVideo, false)
        else Column(Modifier.align(Alignment.Center).padding(bottom = 52.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            CallAvatar(selfName(state, profileLabel), Modifier.size(if (medium) 96.dp else 76.dp), if (medium) 30.sp else 25.sp)
            Text(if (state.camera) "Камера включается…" else "Камера выключена", color = Color(0xFFBDC8DC), style = MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.align(Alignment.BottomCenter).padding(18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CallControl(if (state.microphone) "Выключить микрофон" else "Включить микрофон", if (state.microphone) Icons.Outlined.Mic else Icons.Outlined.MicOff,
                enabled && !state.microphoneBlocked, state.microphone, "call-mic", microphone, showLabel = false, highlightOff = true)
            CallControl(if (state.camera) "Выключить камеру" else "Включить камеру", if (state.camera) Icons.Outlined.Videocam else Icons.Outlined.VideocamOff,
                enabled && !state.cameraBlocked, state.camera, "call-camera", camera, showLabel = false, highlightOff = true)
            if (state.camera) CallControl("Повернуть камеру", Icons.Outlined.Cameraswitch, enabled, false, "call-switch-camera", switchCamera, showLabel = false)
        }
    }
}

@Composable
private fun PrejoinInfo(state: CallState, connect: () -> Unit, leave: () -> Unit, modifier: Modifier, profileLabel: String, joinPending: Boolean, openDevices: () -> Unit) {
    Column(modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primaryContainer) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Videocam, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Text("Ваша следующая встреча", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
            }
        }
        Text(state.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
        Text(state.status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("call-status").semantics { liveRegion = LiveRegionMode.Polite })
        CallError(state)
        (state.participants.find { it.id == state.participantId }?.name?.takeIf { it.isNotBlank() } ?: profileLabel.takeIf { it.isNotBlank() })?.let { name ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Ваше имя", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("call-prejoin-name-label"))
                OutlinedTextField(value = name, onValueChange = {}, readOnly = true,
                    modifier = Modifier.fillMaxWidth().testTag("call-prejoin-name").semantics { contentDescription = "Ваше имя" }, shape = RoundedCornerShape(12.dp))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Mic, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(state.routes.firstOrNull { it.selected }?.name ?: "Устройства Android", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            IconButton(openDevices, modifier = Modifier.size(48.dp).testTag("call-devices")) { Icon(Icons.Outlined.Settings, "Настройки устройств", Modifier.size(20.dp)) }
        }
        if (state.phase == CallPhase.FAILED) Text("Не удалось подключиться. Устройства остановлены.", style = MaterialTheme.typography.bodySmall)
        Button(connect, enabled = !joinPending, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("call-connect")) {
            Text(if (state.phase == CallPhase.FAILED) "Повторить подключение" else "Присоединиться")
            Spacer(Modifier.width(10.dp))
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(18.dp))
        }
        TextButton(leave, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Вернуться к встречам") }
        Text("В фоне камера и микрофон выключаются. При возврате включите их снова. Демонстрация экрана продолжается до остановки.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PrejoinDevices(state: CallState, joinPending: Boolean, microphone: () -> Unit, camera: () -> Unit, route: (Int) -> Unit, openSystemSettings: (() -> Unit)?, dismiss: () -> Unit) {
    val enabled = state.phase == CallPhase.PREJOIN && !joinPending
    AlertDialog(onDismissRequest = dismiss, title = { Text("Устройства перед встречей") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Микрофон", Modifier.weight(1f))
                Switch(state.microphone, onCheckedChange = { microphone() }, enabled = enabled && !state.microphoneBlocked,
                    modifier = Modifier.testTag("call-device-mic").semantics { contentDescription = "Микрофон перед встречей" })
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Камера", Modifier.weight(1f))
                Switch(state.camera, onCheckedChange = { camera() }, enabled = enabled && !state.cameraBlocked,
                    modifier = Modifier.testTag("call-device-camera").semantics { contentDescription = "Камера перед встречей" })
            }
            if (state.microphoneBlocked || state.cameraBlocked) Text("Часть устройств отключена организатором.", style = MaterialTheme.typography.bodySmall)
            if (state.routes.isEmpty()) Text("Аудиоустройство выбирает Android при подключении. Во время встречи его можно сменить через меню встречи.", style = MaterialTheme.typography.bodySmall)
            else state.routes.forEach { item ->
                TextButton(onClick = { route(item.id) }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text((if (item.selected) "✓ " else "") + item.name) }
            }
            CallError(state)
            openSystemSettings?.let { open ->
                TextButton(onClick = { dismiss(); open() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("call-device-permissions")) { Text("Разрешения приложения в Android") }
            }
        }
    }, confirmButton = { TextButton(dismiss) { Text("Готово") } })
}

@Composable
private fun CallError(state: CallState) {
    state.error?.let { Text(it, color = if (state.phase in setOf(CallPhase.PREJOIN, CallPhase.FAILED)) MaterialTheme.colorScheme.error else Color(0xFFFFC1C7),
        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("call-error").semantics { liveRegion = LiveRegionMode.Polite }) }
}

@Composable
private fun CallStage(state: CallState, phone: Boolean, columns: Int, modifier: Modifier, preferences: AccountDevicePreferences,
    stageContentOverride: (@Composable () -> Unit)?, sharePreviewName: String?) {
    val screen = state.videos.firstOrNull { it.screen }
    val sharedFeed = screen?.feed ?: state.localScreen
    val hasScreen = sharedFeed != null || stageContentOverride != null
    val remoteTiles = state.participants.filter { it.id != state.participantId && it.online }.mapIndexed { index, participant ->
        VideoTileItem(participant.id, participant.id, participant.name,
            if (preferences.hideParticipantVideo) null else state.videos.firstOrNull { it.participantId == participant.id && !it.screen }?.feed,
            participant.microphone, index % 3)
    }
    val self = if (preferences.showSelf) VideoTileItem("self", state.participantId, "Вы", state.localVideo, state.microphone, 3, selfName(state)) else null
    // Keep the local participant in the first four visible tiles; additional participants remain scrollable.
    val tiles = if (self == null) remoteTiles else remoteTiles.take(3) + self + remoteTiles.drop(3)
    BoxWithConstraints(modifier.fillMaxHeight()) {
        val stageWidth = maxWidth
        val stageHeight = maxHeight
        val gap = if (phone) 9.dp else 10.dp
        val tileHeight = if (phone) 74.dp else 102.dp
        val rows = ((minOf(tiles.size, 4) + columns - 1) / columns).coerceAtLeast(1)
        val stripHeight = (tileHeight * rows + gap * (rows - 1)).coerceAtMost(maxHeight * .55f)
        val name = when {
            stageContentOverride != null -> sharePreviewName
            screen != null -> state.participants.find { it.id == screen.participantId }?.name
            else -> selfName(state)
        }
        val sharingLabel = if (screen == null && stageContentOverride == null) "Вы демонстрируете экран"
            else if (name.isNullOrBlank()) "Демонстрация экрана" else "$name демонстрирует экран"
        if (hasScreen && maxHeight < 280.dp) {
            // In short windows, stacked tiles would consume the shared picture's entire height.
            Row(Modifier.fillMaxSize().testTag("call-short-share-layout"), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CallSharedScene(sharedFeed, stageContentOverride, sharingLabel, phone, compact = true, Modifier.weight(1f).fillMaxHeight())
                if (tiles.isNotEmpty()) LazyVerticalGrid(columns = GridCells.Fixed(1),
                    modifier = Modifier.width((stageWidth * .24f).coerceIn(100.dp, 132.dp)).fillMaxHeight().testTag("call-grid"),
                    verticalArrangement = Arrangement.spacedBy(gap)) {
                    items(tiles, key = { it.id }) { tile ->
                        VideoTile(tile, state.isSpeaking(tile.participantId), phone,
                            Modifier.fillMaxWidth().height(tileHeight.coerceAtMost(stageHeight)).testTag("call-participant-${tile.id}"))
                    }
                }
            }
        } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(if (phone) 11.dp else 12.dp)) {
            if (hasScreen) CallSharedScene(sharedFeed, stageContentOverride, sharingLabel, phone, compact = false, Modifier.fillMaxWidth().weight(1f))
            if (tiles.isEmpty() && !hasScreen) Column(Modifier.fillMaxWidth().weight(1f).testTag("call-no-visible-participants"), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(CallIcons.Users, null, Modifier.size(44.dp), tint = CallMuted)
                Text("Ждём других участников", color = CallMuted, modifier = Modifier.padding(16.dp), textAlign = TextAlign.Center)
            }
            if (tiles.isNotEmpty()) LazyVerticalGrid(columns = GridCells.Fixed(minOf(columns, tiles.size)),
                modifier = (if (hasScreen) Modifier.height(stripHeight) else Modifier.weight(1f)).fillMaxWidth().testTag("call-grid"),
                horizontalArrangement = Arrangement.spacedBy(gap), verticalArrangement = Arrangement.spacedBy(gap)) {
                items(tiles, key = { it.id }) { tile ->
                    VideoTile(tile, state.isSpeaking(tile.participantId), phone,
                        Modifier.fillMaxWidth().then(if (hasScreen) Modifier.height(tileHeight) else Modifier.aspectRatio(1.35f)).testTag("call-participant-${tile.id}"))
                }
            }
        }
    }
}

@Composable
private fun CallSharedScene(feed: VideoFeed?, content: (@Composable () -> Unit)?, label: String, phone: Boolean, compact: Boolean, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(if (phone) 16.dp else 18.dp)).background(Color(0xFF101723)).testTag("call-screen-share")) {
        if (compact) Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().weight(1f).padding(6.dp).testTag("call-share-content"), contentAlignment = Alignment.Center) {
                if (content != null) content() else feed?.let { VideoContent(it, true) }
            }
            CallSharingLabel(label, phone, compact = true)
        } else {
            Box(Modifier.fillMaxSize().padding(if (phone) 10.dp else 18.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxWidth(if (phone) 1f else .9f).fillMaxHeight(if (phone) .9f else .85f).testTag("call-share-content"), contentAlignment = Alignment.Center) {
                    if (content != null) content() else feed?.let { VideoContent(it, true) }
                }
            }
            CallSharingLabel(label, phone, compact = false, Modifier.align(Alignment.BottomStart))
        }
    }
}

@Composable
private fun CallSharingLabel(label: String, phone: Boolean, compact: Boolean, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().background(Color(0xB0101723)).padding(start = if (compact) 8.dp else 14.dp, end = 10.dp,
        top = if (compact) 2.dp else 5.dp, bottom = if (compact) 5.dp else if (phone) 10.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Icon(CallIcons.Monitor, null, Modifier.size(if (phone || compact) 12.dp else 16.dp), tint = Color(0xFFB1BDD2))
        Text(label, color = Color(0xFFB1BDD2), fontSize = if (phone || compact) 9.sp else 10.sp, lineHeight = 15.sp, modifier = Modifier.testTag("call-sharing-label"))
    }
}

@Composable
private fun CallNavigation(onSelect: ((WorkspaceTab) -> Unit)?, profileLabel: String, phone: Boolean, short: Boolean) {
    val tabs = listOf(WorkspaceTab.CALLS, WorkspaceTab.MESSAGES, WorkspaceTab.PROFILE)
    Column(Modifier.fillMaxWidth().background(Color(0xFF1E2838)).testTag("bottom-navigation").navigationBarsPadding()) {
        HorizontalDivider(color = Color(0xFF344056))
        Row(Modifier.align(Alignment.CenterHorizontally).widthIn(max = 600.dp).fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = if (short) 0.dp else if (phone) 7.dp else 8.dp).selectableGroup()) {
            tabs.forEach { tab ->
                val selected = tab == WorkspaceTab.CALLS
                val color = if (selected) Color(0xFF9DB7FF) else Color(0xFF94A4BC)
                val actionModifier = Modifier.weight(1f).heightIn(min = if (short) 48.dp else 51.dp)
                    .selectable(selected, enabled = onSelect != null, role = Role.Tab, onClick = { onSelect?.invoke(tab) })
                    .testTag("tab-${tab.tag}").semantics { contentDescription = tab.title }.padding(horizontal = 4.dp, vertical = 2.dp)
                val icon: @Composable () -> Unit = {
                    if (tab == WorkspaceTab.PROFILE) {
                        Box(Modifier.size(27.dp).clip(CircleShape).background(Color(0xFF344765)), contentAlignment = Alignment.Center) {
                            val initials = profileLabel.trim().split(Regex("\\s+")).filter(String::isNotBlank).take(2).mapNotNull { it.firstOrNull()?.uppercaseChar() }.joinToString("")
                            Text(initials, color = Color(0xFFB5CAFF), fontSize = 9.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    } else Icon(if (tab == WorkspaceTab.CALLS) CallIcons.Video else CallIcons.MessagesSquare, null, Modifier.size(25.dp), tint = color)
                }
                val label: @Composable () -> Unit = { Text(tab.title, color = color, fontSize = 10.sp, lineHeight = 15.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                if (short) Row(actionModifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally)) { icon(); label() }
                else Column(actionModifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) { icon(); label() }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CallToolbar(state: CallState, phone: Boolean, microphone: () -> Unit, camera: () -> Unit, share: () -> Unit, leave: () -> Unit,
    panel: CallPanel?, openPanel: (CallPanel) -> Unit, shortHeight: Boolean) {
    val enabled = state.phase == CallPhase.CONNECTED
    val fontScale = LocalDensity.current.fontScale
    val regularWidth = if (fontScale > 1.3f) 74.dp else if (phone) 48.dp else 58.dp
    val peopleWidth = if (fontScale > 1.3f) (64.dp * fontScale).coerceAtLeast(90.dp) else if (phone) 56.dp else 58.dp
    val buttonWidth = if (phone) 45.dp else 50.dp
    val buttonHeight = if (phone) 42.dp else 48.dp
    val leaveWidth = if (phone) 51.dp else 68.dp
    val labelSize = if (phone) 8.sp else 9.sp
    FlowRow(Modifier.fillMaxWidth().padding(start = if (phone) 12.dp else 20.dp, end = if (phone) 12.dp else 20.dp,
        top = if (shortHeight) 4.dp else if (phone) 8.dp else 10.dp, bottom = if (shortHeight) 4.dp else if (phone) 13.dp else 15.dp).testTag("call-toolbar"),
        horizontalArrangement = if (phone && fontScale <= 1.3f) Arrangement.SpaceAround else Arrangement.spacedBy(if (phone) 7.dp else 15.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CallControl(if (state.microphone) "Микрофон" else "Без звука", if (state.microphone) CallIcons.Mic else CallIcons.MicOff,
            enabled && !state.microphoneBlocked, state.microphone, "call-mic", microphone, highlightOff = true, controlWidth = regularWidth, buttonWidth = buttonWidth, buttonHeight = buttonHeight, labelFontSize = labelSize,
            speaking = state.isSpeaking(state.participantId), microphoneLevel = state.microphoneLevel(state.participantId))
        CallControl("Камера", if (state.camera) CallIcons.Video else CallIcons.VideoOff,
            enabled && !state.cameraBlocked, state.camera, "call-camera", camera, highlightOff = true, controlWidth = regularWidth, buttonWidth = buttonWidth, buttonHeight = buttonHeight, labelFontSize = labelSize)
        if (!phone) CallControl("Экран", CallIcons.MonitorUp, enabled && !state.screenBlocked && !state.cameraBlocked, state.sharing, "call-share", share,
            controlWidth = regularWidth, buttonWidth = buttonWidth, buttonHeight = buttonHeight, labelFontSize = labelSize)
        CallControl("Участники", CallIcons.Users, true, panel == CallPanel.PARTICIPANTS, "call-people", { openPanel(CallPanel.PARTICIPANTS) }, controlWidth = peopleWidth, buttonWidth = buttonWidth, buttonHeight = buttonHeight, labelFontSize = labelSize)
        CallControl("Чат", CallIcons.MessageCircle, true, panel == CallPanel.CHAT, "call-chat", { openPanel(CallPanel.CHAT) }, controlWidth = regularWidth, buttonWidth = buttonWidth, buttonHeight = buttonHeight, labelFontSize = labelSize)

        Box(Modifier.padding(start = if (phone) 0.dp else 12.dp)) { CallControl("Выйти", CallIcons.LogOut, state.phase != CallPhase.LEAVING, true, "call-leave", leave, danger = true, controlWidth = regularWidth.coerceAtLeast(leaveWidth), buttonWidth = leaveWidth, buttonHeight = buttonHeight, labelFontSize = labelSize) }
    }
}

private fun selfName(state: CallState, profileLabel: String = ""): String = state.participants.find { it.id == state.participantId }?.name?.takeIf { it.isNotBlank() } ?: profileLabel.takeIf { it.isNotBlank() } ?: "Вы"
private data class VideoTileItem(val id: String, val participantId: String, val name: String, val feed: VideoFeed?, val microphone: Boolean, val tone: Int, val avatarName: String = name)

@Composable
private fun VideoTile(tile: VideoTileItem, speaking: Boolean, phone: Boolean, modifier: Modifier) {
    val shape = RoundedCornerShape(15.dp)
    Box(modifier.speakingStatus(speaking).clip(shape).background(if (speaking) SpeakingBackground else CallTileColor)
        .border(if (speaking) 2.dp else 1.dp, if (speaking) SpeakingGreen else Color(0xFF354154), shape)) {
        if (tile.feed == null) CallAvatar(tile.avatarName, Modifier.align(Alignment.Center).size(if (phone) 36.dp else 46.dp), if (phone) 12.sp else 14.sp, tile.tone) else VideoContent(tile.feed, false)
        if (speaking && tile.feed != null) Box(Modifier.fillMaxSize().background(SpeakingGreen.copy(alpha = .09f)))
        Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().then(if (tile.feed != null) Modifier.background(Color(0x80171F2D)) else Modifier).padding(start = 9.dp, end = 8.dp, top = 4.dp, bottom = 7.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tile.name, color = if (speaking) SpeakingForeground else Color(0xFFC5D0DF), fontSize = 9.sp, lineHeight = 14.sp, letterSpacing = 0.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Box(Modifier.clip(RoundedCornerShape(5.dp)).background(if (speaking) SpeakingGreen else Color.Transparent).padding(3.dp)) {
                Icon(if (tile.microphone) CallIcons.Mic else CallIcons.MicOff,
                    if (speaking) "Говорит" else if (tile.microphone) "Микрофон включён" else "Без звука", Modifier.size(13.dp),
                    tint = if (speaking) Color(0xFF103622) else Color(0xFFB4BFD0))
            }
        }
    }
}

/** Keep each renderer tied to its actual native feed across navigation and rotation. */
@Composable
internal fun VideoContent(feed: VideoFeed, screen: Boolean) {
    key(feed) {
        var renderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
        AndroidView(factory = { context -> SurfaceViewRenderer(context).apply {
            init(feed.eglContext, null)
            setScalingType(if (screen) RendererCommon.ScalingType.SCALE_ASPECT_FIT else RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            setEnableHardwareScaler(true)
            renderer = this
            feed.attach(this)
        } }, modifier = Modifier.fillMaxSize())
        DisposableEffect(feed) { onDispose { renderer?.let { feed.detach(it); it.release() }; renderer = null } }
    }
}

@Composable
private fun CallAvatar(name: String, modifier: Modifier, fontSize: TextUnit = 14.sp, tone: Int = 3) {
    val background = when (tone) { 0 -> Color(0xFFF6E7DB); 1 -> Color(0xFFDEF0E9); 2 -> Color(0xFFE6DEF6); else -> Color(0xFFDFE8FF) }
    val foreground = when (tone) { 0 -> Color(0xFFAC7451); 1 -> Color(0xFF3D8576); 2 -> Color(0xFF7253A2); else -> Color(0xFF4D71C5) }
    Box(modifier.clip(CircleShape).background(background), contentAlignment = Alignment.Center) {
        Text(name.trim().split(Regex("\\s+")).filter(String::isNotBlank).take(2).mapNotNull { it.firstOrNull()?.uppercaseChar() }.joinToString(""),
            color = foreground, fontSize = fontSize, lineHeight = fontSize * 1.35f, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ParticipantPanel(state: CallState, modifier: Modifier, close: () -> Unit, phone: Boolean = false) {
    val shape = RoundedCornerShape(if (phone) 23.dp else 24.dp)
    val colors = MaterialTheme.colorScheme
    val others = state.participants.filter { it.id != state.participantId }
    val ordered = others + state.participants.filter { it.id == state.participantId }
    Column(modifier.clip(shape).background(colors.surface).border(1.dp, colors.outlineVariant, shape).testTag("call-participants-panel").padding(if (phone) 21.dp else 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Участники · ${state.participants.size}", Modifier.weight(1f).semantics { heading() }, fontSize = if (phone) 20.sp else 21.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
            IconButton(close, Modifier.size(48.dp).testTag("call-panel-close")) { Icon(CallIcons.X, "Закрыть участников", Modifier.size(20.dp), tint = colors.onSurfaceVariant) }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            if (state.participants.isEmpty()) item { Text("Список участников обновляется", color = colors.onSurfaceVariant, fontSize = 13.sp) }
            items(ordered, key = { it.id }) { person ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val tone = if (person.id == state.participantId) 3 else others.indexOf(person) % 3
                    CallAvatar(person.name, Modifier.size(40.dp), tone = tone)
                    Column(Modifier.weight(1f)) {
                        Text(if (person.id == state.participantId) "Вы" else person.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 21.sp, color = colors.onSurface)
                        Text(if (!person.online) "Не в сети" else if (person.microphone) "Микрофон включён" else "Микрофон выключен", color = colors.onSurfaceVariant, fontSize = 13.sp, lineHeight = 22.sp)
                    }
                    Icon(if (person.microphone && person.online) CallIcons.Mic else CallIcons.MicOff, null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun CallControl(label: String, icon: ImageVector, enabled: Boolean, selected: Boolean, tag: String, click: () -> Unit, danger: Boolean = false, showLabel: Boolean = true, highlightOff: Boolean = false, controlWidth: Dp? = null, buttonWidth: Dp = 48.dp, buttonHeight: Dp = 48.dp, labelFontSize: TextUnit = 10.sp, speaking: Boolean = false, microphoneLevel: Float = 0f) {
    val background = when { !enabled -> Color(0xFF303C4F); danger -> Color(0xFFDD5D6A); highlightOff && !selected -> Color(0xFFE2E8F0); selected && !highlightOff -> Color(0xFF4668AD); else -> Color(0xFF303C4F) }
    val foreground = when { !enabled -> Color(0xFF8290A5); danger || selected && !highlightOff -> Color.White; highlightOff && !selected -> Color(0xFF263449); else -> Color(0xFFCAD4E4) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(if (buttonHeight < 48.dp) 3.dp else 7.dp), modifier = Modifier.width((controlWidth ?: if (LocalDensity.current.fontScale > 1.3f && showLabel) 74.dp else 50.dp).coerceAtLeast(buttonWidth).coerceAtLeast(48.dp))) {
        // Preserve a 48dp action target around the smaller discs from the phone reference.
        Box(Modifier.size(width = buttonWidth.coerceAtLeast(48.dp), height = buttonHeight.coerceAtLeast(48.dp))
            .testTag(tag).toggleable(value = selected, enabled = enabled, role = Role.Checkbox, onValueChange = { click() })
            .then(if (tag == "call-mic") Modifier.speakingStatus(speaking && enabled) else Modifier), contentAlignment = Alignment.Center) {
            Box(Modifier.size(buttonWidth, buttonHeight).clip(RoundedCornerShape(if (buttonHeight < 48.dp) 14.dp else 15.dp)).background(background), contentAlignment = Alignment.Center) {
                if (tag == "call-mic" && selected) MicrophoneLevelIcon(microphoneLevel, enabled, foreground, label, Modifier.size(20.dp).testTag("$tag-icon"))
                else Icon(icon, contentDescription = label, tint = foreground, modifier = Modifier.size(20.dp))
            }
        }
        if (showLabel) Text(label, style = MaterialTheme.typography.labelSmall.copy(fontSize = labelFontSize, lineHeight = 14.sp, letterSpacing = 0.sp), color = Color(0xFFCAD4E4), textAlign = TextAlign.Center, modifier = Modifier.testTag("$tag-label"))
    }
}
