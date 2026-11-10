package com.meetspace.app.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.meetspace.app.AvatarImage
import com.meetspace.app.CallPermissionRequest
import com.meetspace.app.ScreenShareService
import com.meetspace.app.CallJoinDevices
import com.meetspace.app.preferredAudioRoute
import com.meetspace.app.preferredJoinDevices
import com.meetspace.app.rememberAccountDevicePreferences
import com.meetspace.core.media.*
import com.meetspace.core.model.User
import kotlinx.coroutines.flow.StateFlow
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

@Composable
fun CallHost(engine: CallEngine, state: CallState, onWorkspaceTab: ((WorkspaceTab) -> Unit)? = null, profileLabel: String = "", details: CallUiDetails = CallUiDetails(), panelActions: CallPanelActions = CallPanelActions(), recordingActions: CallRecordingActions = CallRecordingActions(), authScope: String = "", profileUser: User? = null) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentAuthScope by rememberUpdatedState(authScope)
    val joinRequest = remember(engine) { CallPermissionRequest() }
    val microphoneRequest = remember(engine) { CallPermissionRequest() }
    val cameraRequest = remember(engine) { CallPermissionRequest() }
    val notificationRequest = remember(engine) { CallPermissionRequest() }
    val projectionRequest = remember(engine) { CallPermissionRequest() }
    var hostActive by remember { mutableStateOf(true) }
    val (preferences, updatePreferences) = rememberAccountDevicePreferences()
    var microphoneEdited by rememberSaveable(state.conferenceId) { mutableStateOf(false) }
    var cameraEdited by rememberSaveable(state.conferenceId) { mutableStateOf(false) }
    var pendingJoin by rememberSaveable(state.conferenceId) { mutableStateOf(false) }
    var joinMicrophone by rememberSaveable(state.conferenceId) { mutableStateOf(false) }
    var joinCamera by rememberSaveable(state.conferenceId) { mutableStateOf(false) }
    var routeChosen by rememberSaveable(state.conferenceId, preferences.speaker, state.phase == CallPhase.RECONNECTING) { mutableStateOf(false) }
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
    val leaveCall = {
        if (state.phase == CallPhase.PREJOIN || state.phase == CallPhase.FAILED) engine.leave()
        else confirmExit = true
    }
    BackHandler(onBack = leaveCall)
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
    CallScreen(state, onWorkspaceTab = onWorkspaceTab, profileLabel = profileLabel, profileUser = profileUser, devicePreferences = preferences, updateDevicePreferences = updatePreferences, joinPending = pendingJoin, details = details, panelActions = panelActions, recordingActions = recordingActions,
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
        }, leave = leaveCall, switchCamera = engine::switchCamera, reconnect = engine::reconnectMeeting,
        localMicrophoneLevel = engine.localMicrophoneLevel,
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

private val CallBackground = Color(0xFF17131C)
private val CallTileColor = Color(0xFF28232E)
internal val CallForeground = Color(0xFFF5F0F7)
private val CallMuted = Color(0xFFC5B8CC)
private val CallBorder = Color(0xFF605266)
internal val CallHeaderSurface = Color(0xFF282230)
internal val CallHeaderBorder = Color(0xFF494052)

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
    profileUser: User? = null,
    updateDevicePreferences: ((AccountDevicePreferences) -> Unit)? = null,
    reconnect: () -> Unit = {},
    localMicrophoneLevel: StateFlow<Float>? = null,
) {
    var selectedPanel by rememberSaveable(state.conferenceId) { mutableStateOf<CallPanel?>(null) }
    var settings by rememberSaveable(state.conferenceId) { mutableStateOf(false) }
    var invite by rememberSaveable(state.conferenceId) { mutableStateOf(false) }
    var shareMeeting by rememberSaveable(state.conferenceId) { mutableStateOf(false) }
    var devices by remember { mutableStateOf(false) }
    var recordingDialog by rememberSaveable(state.conferenceId) { mutableStateOf(false) }
    val prejoin = state.phase in setOf(CallPhase.PREJOIN, CallPhase.FAILED)
    val canInvite = details.room.self?.let { it.canParticipate && (it.role == "owner" || it.role == "co_host") } == true
    fun openPanel(panel: CallPanel?) {
        selectedPanel = if (selectedPanel == panel) null else panel
        panelActions.open(selectedPanel)
    }
    BackHandler(enabled = selectedPanel != null && !prejoin) { selectedPanel = null; panelActions.open(null) }
    Surface(
        color = if (prejoin) MaterialTheme.colorScheme.surface else CallBackground,
        contentColor = if (prejoin) MaterialTheme.colorScheme.onBackground else CallForeground,
        modifier = Modifier.fillMaxSize().testTag("call-screen"),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(if (!prejoin || onWorkspaceTab != null) WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal) else WindowInsets.safeDrawing).then(if (!prejoin) Modifier.imePadding() else Modifier)) {
            val phone = maxWidth < 600.dp
            val shortHeight = maxHeight < 400.dp
            val viewportWidth = maxWidth
            if (prejoin) Prejoin(state, viewportWidth, microphone, camera, connect, leave, switchCamera, onWorkspaceTab, profileLabel, joinPending, { devices = true }, profileUser)
            else Column(Modifier.fillMaxSize()) {
                CallHeader(state, details, phone, shortHeight, recording = { recordingDialog = true },
                    invite = if (canInvite) ({ shareMeeting = true }) else null, reconnect = reconnect,
                    options = { settings = true },
                    onWorkspaceTab = onWorkspaceTab, compactActions = viewportWidth / LocalDensity.current.fontScale.coerceAtLeast(1f) < 600.dp)
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(horizontal = if (phone) 12.dp else 20.dp)
                    .padding(top = if (shortHeight) 4.dp else if (phone) 8.dp else 6.dp, bottom = if (shortHeight) 4.dp else if (phone) 8.dp else 12.dp).testTag("call-body")) {
                    val panel = selectedPanel
                    val close = { selectedPanel = null; panelActions.open(null) }
                    if (viewportWidth >= 780.dp) {
                        val panelWidth = (maxWidth * .25f).coerceIn(260.dp, 350.dp)
                        val columns = if (panel != null && viewportWidth <= 1050.dp) 2 else 4
                        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            CallStage(state, phone, columns, Modifier.weight(1f), devicePreferences, stageContentOverride, sharePreviewName,
                                selfRole = details.room.self?.role, localMicrophoneLevel = localMicrophoneLevel)
                            if (panel != null) CallPanelContent(panel, state, details, panelActions, close, ::openPanel,
                                compact = false, modifier = Modifier.width(panelWidth).fillMaxHeight())
                        }
                    } else {
                        // Keep the same chat instance as the keyboard resizes it so the draft retains focus.
                        val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
                        val showStage = panel == null || maxHeight >= 230.dp
                        val stageWeight = if (keyboardVisible && panel != null) .22f else .5f
                        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (showStage) CallStage(state, phone, if (phone || panel != null) 2 else 4, Modifier.weight(stageWeight).fillMaxWidth(),
                                devicePreferences, stageContentOverride, sharePreviewName, selfRole = details.room.self?.role,
                                localMicrophoneLevel = localMicrophoneLevel)
                            if (panel != null) CallPanelContent(panel, state, details, panelActions, close, ::openPanel,
                                compact = true, modifier = Modifier.weight(1f - stageWeight).fillMaxWidth())
                        }
                    }
                }
                CallToolbar(state, viewportWidth, shortHeight, microphone, camera, leave, selectedPanel, ::openPanel,
                    waitingCount = if (details.room.self?.role == "owner") details.room.waiting.size else 0, localMicrophoneLevel = localMicrophoneLevel)
            }
        }
    }
    if (recordingDialog && !prejoin) CallRecordingDialog(state.title, details.recordingState, recordingActions, LocalConfiguration.current.screenWidthDp < 600) { recordingDialog = false }
    if (devices && prejoin) PrejoinDevices(state, joinPending, microphone, camera, route, openSystemSettings, { devices = false })
    if (settings && !prejoin) CallDeviceSettings(state, devicePreferences, updateDevicePreferences, route, refreshRoutes,
        switchCamera, openSystemSettings, onWorkspaceTab, share = { settings = false; share() }) { settings = false }
    if (shareMeeting && canInvite && !prejoin) CallShareDialog(state.title, details.invitationLink,
        onInviteParticipants = { shareMeeting = false; invite = true }, close = { shareMeeting = false })
    if (invite && canInvite && !prejoin) MeetingInvitationDialog(details.room, panelActions.room) { invite = false }
}

@Composable
private fun CallHeader(state: CallState, details: CallUiDetails, phone: Boolean, shortHeight: Boolean,
    recording: () -> Unit, invite: (() -> Unit)?, reconnect: () -> Unit, options: () -> Unit,
    onWorkspaceTab: ((WorkspaceTab) -> Unit)?, compactActions: Boolean) {
    val connected = state.phase == CallPhase.CONNECTED
    val reconnecting = state.phase == CallPhase.RECONNECTING
    val reconnectRotation = remember { Animatable(0f) }
    LaunchedEffect(reconnecting) {
        reconnectRotation.snapTo(0f)
        if (reconnecting) reconnectRotation.animateTo(360f, tween(900))
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = if (phone) 10.dp else 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = if (shortHeight) 4.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (phone) 4.dp else 10.dp)) {
            if (onWorkspaceTab != null) IconButton({ onWorkspaceTab(WorkspaceTab.CALLS) }, Modifier.size(48.dp).testTag("call-back-to-meetings")) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "К списку встреч", Modifier.size(20.dp), tint = CallMuted)
            }
            Column(Modifier.weight(1f).heightIn(min = 48.dp)
                .clickable(onClickLabel = "Открыть настройки встречи", role = Role.Button, onClick = options)
                .testTag("call-meeting-options"), verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
                Text(state.title, fontSize = if (phone) 14.sp else 18.sp, lineHeight = if (phone) 19.sp else 24.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = if (phone) 2 else 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
                Text(if (connected) "Встреча в эфире" else state.status, fontSize = if (phone) 11.sp else 12.sp,
                    lineHeight = 16.sp, color = CallMuted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("call-status"))
            }
            if (connected && !phone) details.elapsedSeconds?.let { seconds ->
                val elapsed = seconds.coerceAtLeast(0)
                Text("%02d:%02d:%02d".format(java.util.Locale.ROOT, elapsed / 3600, elapsed / 60 % 60, elapsed % 60),
                    color = CallMuted, fontSize = 13.sp, style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"), maxLines = 1, modifier = Modifier.testTag("call-elapsed"))
            }
            CallRecordingControl(details.recordingState, compactActions, recording)
            if (invite != null) CallHeaderButton(CallIcons.Link, "Пригласить", "call-invite-header", invite, showLabel = !compactActions)
            CallHeaderButton(CallIcons.RefreshCw, "Переподключиться", "call-reconnect", reconnect, enabled = connected,
                iconModifier = Modifier.rotate(reconnectRotation.value))
        }
        HorizontalDivider(color = CallHeaderBorder)
        CallError(state)
    }
}

@Composable
private fun CallHeaderButton(icon: ImageVector, label: String, tag: String, onClick: () -> Unit,
    showLabel: Boolean = false, enabled: Boolean = true, iconModifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(10.dp)
    val tint = if (enabled) CallForeground else CallMuted.copy(alpha = .65f)
    Row(Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).clip(shape).background(CallHeaderSurface)
        .border(1.dp, CallHeaderBorder, shape).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .testTag(tag).padding(horizontal = if (showLabel) 12.dp else 0.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
        Icon(icon, if (showLabel) null else label, iconModifier.size(19.dp), tint = tint)
        if (showLabel) Text(label, color = tint, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun CallPanelContent(panel: CallPanel, state: CallState, details: CallUiDetails, actions: CallPanelActions,
    close: () -> Unit, select: (CallPanel) -> Unit, compact: Boolean, modifier: Modifier) {
    val participantCount = details.chat.participantCount ?: state.participants.count { it.online }
    val selectTab: (CallPanel) -> Unit = { if (it != panel) select(it) }
    if (panel == CallPanel.PARTICIPANTS) CallPanelFrame(panel, participantCount, compact, close, selectTab, modifier,
        refreshParticipants = actions.room.refresh.takeUnless { details.room.loading || details.room.busy }) {
        MeetingRoomPanel(state, details.room, actions.room, Modifier.fillMaxSize(), close, embedded = true)
    } else CallTextPanel(panel, state.title, if (panel == CallPanel.COMMENTS) details.comments else details.chat,
        if (panel == CallPanel.COMMENTS) details.commentCount else null, actions, close, modifier,
        selectPanel = selectTab, compact = compact, participantCount = participantCount)
}

@Composable
private fun Prejoin(
    state: CallState, viewportWidth: Dp,
    microphone: () -> Unit, camera: () -> Unit, connect: () -> Unit, leave: () -> Unit,
    switchCamera: () -> Unit, onWorkspaceTab: ((WorkspaceTab) -> Unit)?, profileLabel: String,
    joinPending: Boolean, openDevices: () -> Unit, profileUser: User?,
) {
    val colors = MaterialTheme.colorScheme
    val name = selfName(state, profileUser?.displayLabel ?: profileLabel)
    val guest = onWorkspaceTab == null && profileUser != null
    val tablet = viewportWidth >= 600.dp
    val spacious = viewportWidth >= 700.dp
    val tabletShell = viewportWidth >= 780.dp
    val wide = viewportWidth >= 840.dp && LocalDensity.current.fontScale <= 1.3f
    val contentPadding = if (spacious) 24.dp else 18.dp
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 74.dp).padding(horizontal = if (spacious) 28.dp else 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (tabletShell) Brand(compact = true)
            Text("Перед встречей", modifier = Modifier.weight(1f).semantics { heading() },
                color = colors.onSurface, fontSize = if (tabletShell) 25.sp else 23.sp,
                lineHeight = if (tabletShell) 30.sp else 28.sp, letterSpacing = (-.5).sp, fontWeight = FontWeight.SemiBold)
            IconButton(leave, modifier = Modifier.size(48.dp).testTag("call-leave")) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Назад к встречам", Modifier.size(27.dp), tint = colors.onSurface)
            }
        }
        HorizontalDivider(color = colors.outlineVariant)
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).background(colors.background)
            .padding(horizontal = contentPadding + (if (tabletShell) 24.dp else 0.dp))) {
            val viewportHeight = maxHeight
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("call-prejoin-scroll"),
                horizontalAlignment = Alignment.CenterHorizontally) {
                if (wide) Row(
                    Modifier.fillMaxWidth().heightIn(min = viewportHeight).padding(vertical = 24.dp).testTag("call-prejoin-wide"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(32.dp),
                ) {
                    PrejoinPreview(state, microphone, camera, switchCamera, Modifier.weight(1.25f), name, profileUser, wide, tablet)
                    PrejoinInfo(state, connect, leave, Modifier.weight(1f), name, guest, tablet, joinPending, openDevices)
                } else Column(
                    Modifier.widthIn(max = 520.dp).fillMaxWidth().padding(vertical = if (spacious) 24.dp else 22.dp)
                        .testTag("call-prejoin-compact"),
                    verticalArrangement = Arrangement.spacedBy(25.dp),
                ) {
                    PrejoinPreview(state, microphone, camera, switchCamera, Modifier.fillMaxWidth(), name, profileUser, wide, tablet)
                    PrejoinInfo(state, connect, leave, Modifier.fillMaxWidth(), name, guest, tablet, joinPending, openDevices)
                }
            }
        }
        onWorkspaceTab?.let { WorkspaceNavigation(WorkspaceTab.CALLS, it, name, tabletShell, profileUser) }
    }
}

@Composable
private fun PrejoinPreview(
    state: CallState, microphone: () -> Unit, camera: () -> Unit, switchCamera: () -> Unit,
    modifier: Modifier, name: String, profileUser: User?, wide: Boolean, medium: Boolean,
) {
    val enabled = state.phase == CallPhase.PREJOIN
    val localVideo = state.localVideo
    BoxWithConstraints(modifier.heightIn(min = 220.dp).aspectRatio(if (medium && !wide) 16f / 10f else 4f / 3f)
        .clip(RoundedCornerShape(if (medium) 25.dp else 20.dp)).background(Color(0xFF222D40)).testTag("call-preview")) {
        if (localVideo != null) VideoContent(localVideo, false)
        else Column(
            Modifier.align(Alignment.Center).padding(bottom = if (maxHeight < 250.dp) 22.dp else 0.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (medium) 18.dp else 12.dp),
        ) {
            val avatarSize = if (medium) 96.dp else 76.dp
            if (profileUser?.avatarVersion != null) AvatarImage(name, profileUser.id, profileUser.avatarVersion, size = avatarSize)
            else CallAvatar(name, Modifier.size(avatarSize), if (medium) 30.sp else 25.sp)
            Text(if (state.camera) "Камера включается…" else "Камера выключена", color = Color(0xFFBDC8DC),
                fontSize = 12.sp, lineHeight = 18.sp, textAlign = TextAlign.Center)
        }
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = if (medium) 20.dp else 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrejoinControl(if (state.microphone) "Выключить микрофон" else "Включить микрофон", if (state.microphone) CallIcons.Mic else CallIcons.MicOff,
                enabled && !state.microphoneBlocked, state.microphone, "call-mic", microphone, highlightOff = true)
            PrejoinControl(if (state.camera) "Выключить камеру" else "Включить камеру", if (state.camera) CallIcons.Video else CallIcons.VideoOff,
                enabled && !state.cameraBlocked, state.camera, "call-camera", camera, highlightOff = true)
            if (state.camera) PrejoinControl("Повернуть камеру", Icons.Outlined.Cameraswitch, enabled, false, "call-switch-camera", switchCamera)
        }
    }
}

@Composable
private fun PrejoinInfo(
    state: CallState, connect: () -> Unit, leave: () -> Unit, modifier: Modifier,
    name: String, guest: Boolean, tablet: Boolean, joinPending: Boolean, openDevices: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val failed = state.phase == CallPhase.FAILED
    val nameLabel = if (guest) "Как вас представить?" else "Ваше имя"
    val titleSpacing = if (tablet) 14.dp else 11.dp
    val titleHeight = if (tablet) 36 else 31
    Column(modifier.testTag("call-prejoin-info")) {
        Surface(shape = RoundedCornerShape(7.dp), color = colors.primaryContainer, contentColor = colors.primary) {
            Row(Modifier.heightIn(min = 30.dp).padding(horizontal = 9.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(CallIcons.Video, null, Modifier.size(20.dp))
                Text("Ваша следующая встреча", fontSize = 11.sp, lineHeight = 16.5.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(titleSpacing))
        Text(state.title, fontSize = if (tablet) 30.sp else 26.sp, lineHeight = titleHeight.sp,
            letterSpacing = (-1.1).sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.heightIn(min = titleHeight.dp).semantics { heading() })
        Spacer(Modifier.height(titleSpacing))
        Text(if (failed) state.status else "Проверьте звук и камеру перед встречей", fontSize = 13.sp, lineHeight = 22.sp,
            color = colors.onSurfaceVariant,
            modifier = Modifier.heightIn(min = 22.dp).testTag("call-status").semantics { liveRegion = LiveRegionMode.Polite })
        CallError(state)
        Spacer(Modifier.height(10.dp))
        Surface(shape = RoundedCornerShape(10.dp), color = colors.primaryContainer, contentColor = colors.primary) {
            Row(Modifier.heightIn(min = 44.dp).padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.PersonOutline, null, Modifier.size(20.dp))
                Text(if (guest) "Вход без аккаунта · гость" else "Вход с аккаунтом", fontSize = 12.sp, lineHeight = 18.sp)
            }
        }
        Spacer(Modifier.height(if (tablet) 24.dp else 20.dp))
        Text(nameLabel, fontSize = 13.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.heightIn(min = 20.dp).testTag("call-prejoin-name-label"))
        Spacer(Modifier.height(8.dp))
        Surface(shape = RoundedCornerShape(12.dp), color = colors.surface, contentColor = colors.onSurface,
            border = BorderStroke(1.dp, colors.outlineVariant), modifier = Modifier.fillMaxWidth().testTag("call-prejoin-name")
                .semantics(mergeDescendants = true) { contentDescription = nameLabel }) {
            Box(Modifier.heightIn(min = 50.dp).padding(horizontal = 14.dp, vertical = 10.dp), contentAlignment = Alignment.CenterStart) {
                Text(name, fontSize = 16.sp, lineHeight = 24.sp)
            }
        }
        Spacer(Modifier.height(if (tablet) 16.dp else 14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(CallIcons.Mic, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
            Text(state.routes.firstOrNull { it.selected }?.name ?: "Системный микрофон", modifier = Modifier.weight(1f),
                color = colors.onSurfaceVariant, fontSize = 11.sp, lineHeight = 17.sp)
            IconButton(openDevices, modifier = Modifier.size(48.dp).testTag("call-devices")) {
                Icon(Icons.Outlined.Settings, "Настройки устройств", Modifier.size(20.dp), tint = colors.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(24.dp))
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 44.dp) {
            val actionPadding = PaddingValues(horizontal = 19.dp, vertical = 10.dp)
            Column(Modifier.fillMaxWidth()) {
                Button(connect, enabled = !joinPending, shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag("call-connect"),
                    contentPadding = actionPadding) {
                    Text(if (failed) "Повторить подключение" else "Присоединиться", fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(9.dp))
                    Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(18.dp))
                }
                TextButton(leave, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                    contentPadding = actionPadding) {
                    Text("Вернуться к встречам", fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("В фоне камера и микрофон выключаются.", Modifier.fillMaxWidth().heightIn(min = 17.dp), color = colors.onSurfaceVariant,
            fontSize = 10.sp, lineHeight = 17.sp, textAlign = TextAlign.Center)
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
            if (state.routes.isEmpty()) Text("Аудиоустройство выбирает Android при подключении. Во время встречи его можно сменить в настройках устройств.", style = MaterialTheme.typography.bodySmall)
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
private fun CallDeviceSettings(state: CallState, preferences: AccountDevicePreferences, update: ((AccountDevicePreferences) -> Unit)?,
    route: (Int) -> Unit, refreshRoutes: () -> Unit, switchCamera: () -> Unit, openSystemSettings: (() -> Unit)?,
    onWorkspaceTab: ((WorkspaceTab) -> Unit)?, share: () -> Unit, dismiss: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text("Настройки устройств") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()).testTag("call-settings-dialog")) {
            Text("Вывод звука", style = MaterialTheme.typography.titleSmall)
            if (state.routes.isEmpty()) Text("Используется системное аудиоустройство.")
            state.routes.forEach { item ->
                TextButton(onClick = { route(item.id) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text((if (item.selected) "✓ " else "") + item.name)
                }
            }
            TextButton(refreshRoutes, Modifier.heightIn(min = 48.dp)) { Text("Обновить устройства") }
            if (update != null) {
                MediaVolume("Громкость микрофона", preferences.microphoneVolume, "call-settings-microphone-volume") { update(preferences.copy(microphoneVolume = it)) }
                MediaVolume("Громкость динамика", preferences.speakerVolume, "call-settings-speaker-volume") { update(preferences.copy(speakerVolume = it)) }
            }
            if (state.phase == CallPhase.CONNECTED && state.camera && !state.cameraBlocked) {
                TextButton(switchCamera, Modifier.heightIn(min = 48.dp)) { Text("Повернуть камеру") }
            }
            TextButton(share, Modifier.heightIn(min = 48.dp).testTag("call-screen-share-action"),
                enabled = state.phase == CallPhase.CONNECTED && !state.screenBlocked && !state.cameraBlocked) {
                Icon(CallIcons.MonitorUp, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (state.sharing) "Остановить демонстрацию" else "Демонстрация экрана")
            }
            if (openSystemSettings != null) TextButton({ dismiss(); openSystemSettings() }, Modifier.heightIn(min = 48.dp)) {
                Text("Разрешения приложения в Android")
            }
            if (onWorkspaceTab != null) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("Рабочее пространство", style = MaterialTheme.typography.titleSmall)
                TextButton({ dismiss(); onWorkspaceTab(WorkspaceTab.MESSAGES) }, Modifier.heightIn(min = 48.dp).testTag("call-workspace-messages")) { Text("Чаты") }
                TextButton({ dismiss(); onWorkspaceTab(WorkspaceTab.PROFILE) }, Modifier.heightIn(min = 48.dp).testTag("call-workspace-profile")) { Text("Профиль") }
            }
        }
    }, confirmButton = { TextButton(dismiss) { Text("Закрыть") } })
}

@Composable
private fun CallError(state: CallState) {
    state.error?.let { Text(it, color = if (state.phase in setOf(CallPhase.PREJOIN, CallPhase.FAILED)) MaterialTheme.colorScheme.error else Color(0xFFFFC1C7),
        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("call-error").semantics { liveRegion = LiveRegionMode.Polite }) }
}

@Composable
private fun CallStage(state: CallState, phone: Boolean, columns: Int, modifier: Modifier, preferences: AccountDevicePreferences,
    stageContentOverride: (@Composable () -> Unit)?, sharePreviewName: String?, selfRole: String?, localMicrophoneLevel: StateFlow<Float>?) {
    val screen = if (preferences.hideParticipantVideo) null else state.videos.firstOrNull { it.screen }
    val sharedFeed = screen?.feed ?: state.localScreen
    val hasScreen = sharedFeed != null || stageContentOverride != null
    val remoteTiles = state.participants.filter { it.id != state.participantId && it.online }.mapIndexed { index, participant ->
        VideoTileItem(participant.id, participant.id, participant.name,
            if (preferences.hideParticipantVideo) null else state.videos.firstOrNull { it.participantId == participant.id && !it.screen }?.feed,
            participant.microphone, index % 3)
    }
    val self = if (preferences.showSelf) {
        val name = selfName(state)
        VideoTileItem("self", state.participantId, if (name == "Вы") name else "$name · вы", state.localVideo,
            state.microphone, 3, name, selfRole)
    } else null
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
                        VideoTile(tile, state, phone,
                            Modifier.fillMaxWidth().height(tileHeight.coerceAtMost(stageHeight)).testTag("call-participant-${tile.id}"), compact = true,
                            liveLevel = localMicrophoneLevel.takeIf { tile.id == "self" })
                    }
                }
            }
        } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(if (phone) 11.dp else 12.dp)) {
            if (hasScreen) CallSharedScene(sharedFeed, stageContentOverride, sharingLabel, phone, compact = false, Modifier.fillMaxWidth().weight(1f))
            if (tiles.isEmpty() && !hasScreen) Column(Modifier.fillMaxWidth().weight(1f).testTag("call-no-visible-participants"), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(CallIcons.Users, null, Modifier.size(44.dp), tint = CallMuted)
                Text("Ждём других участников", color = CallMuted, modifier = Modifier.padding(16.dp), textAlign = TextAlign.Center)
            }
            if (tiles.isNotEmpty()) {
                val fillStage = !hasScreen && tiles.size == 1
                val tileBounds = when {
                    hasScreen -> Modifier.height(tileHeight)
                    fillStage -> Modifier.height(stageHeight)
                    else -> Modifier.aspectRatio(1.35f)
                }
                LazyVerticalGrid(columns = GridCells.Fixed(minOf(columns, tiles.size)),
                    modifier = (if (hasScreen) Modifier.height(stripHeight) else Modifier.weight(1f)).fillMaxWidth().testTag("call-grid"),
                    horizontalArrangement = Arrangement.spacedBy(gap), verticalArrangement = Arrangement.spacedBy(gap)) {
                    items(tiles, key = { it.id }) { tile ->
                        VideoTile(tile, state, phone, tileBounds.fillMaxWidth().testTag("call-participant-${tile.id}"),
                            compact = hasScreen, fillStage = fillStage, liveLevel = localMicrophoneLevel.takeIf { tile.id == "self" })
                    }
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CallToolbar(state: CallState, viewportWidth: Dp, shortHeight: Boolean, microphone: () -> Unit, camera: () -> Unit,
    leave: () -> Unit, panel: CallPanel?, openPanel: (CallPanel) -> Unit, waitingCount: Int,
    localMicrophoneLevel: StateFlow<Float>?) {
    val connected = state.phase == CallPhase.CONNECTED
    val fontScale = LocalDensity.current.fontScale
    val compact = viewportWidth < 780.dp
    val gutter = if (compact) 12.dp else 22.dp
    val spacing = if (compact) 4.dp else 6.dp
    val inset = if (compact) 4.dp else 5.dp
    val labelSize = if (compact || shortHeight) 10.sp else 11.sp
    val buttonHeight = if (compact || shortHeight) 48.dp else 55.dp
    val shape = RoundedCornerShape(13.dp)
    val buttons: @Composable (Modifier) -> Unit = { width ->
        val size = width.heightIn(min = buttonHeight)
        CallToolbarButton("Микрофон", if (state.microphone) CallIcons.Mic else CallIcons.MicOff, "call-mic", size, labelSize, microphone,
            enabled = connected && !state.microphoneBlocked, selected = state.microphone,
            speaking = state.isSpeaking(state.participantId), microphoneLevel = state.microphoneLevel(state.participantId), liveLevel = localMicrophoneLevel)
        CallToolbarButton("Камера", if (state.camera) CallIcons.Video else CallIcons.VideoOff, "call-camera", size, labelSize, camera,
            enabled = connected && !state.cameraBlocked, selected = state.camera)
        CallToolbarButton("Участники", CallIcons.Users, "call-people", size, labelSize, { openPanel(CallPanel.PARTICIPANTS) },
            selected = panel == CallPanel.PARTICIPANTS, badge = waitingCount)
        CallToolbarButton("Чат", CallIcons.MessageCircle, "call-chat", size, labelSize, { openPanel(CallPanel.CHAT) }, selected = panel == CallPanel.CHAT)
        CallToolbarButton("Выйти", CallIcons.LogOut, "call-leave", size, labelSize, leave, enabled = state.phase != CallPhase.LEAVING, danger = true)
    }
    Column(Modifier.fillMaxWidth().navigationBarsPadding().testTag("call-toolbar"), horizontalAlignment = Alignment.CenterHorizontally) {
        HorizontalDivider(color = CallHeaderBorder, modifier = Modifier.padding(horizontal = gutter))
        val container = Modifier.padding(horizontal = gutter, vertical = if (shortHeight) 4.dp else if (compact) 8.dp else 11.dp)
            .clip(shape).background(Color(0xFF241E2C)).border(1.dp, Color(0xFF40364B), shape).padding(inset)
        if (compact && fontScale <= 1.3f && viewportWidth >= 288.dp) {
            Row(Modifier.fillMaxWidth().then(container), horizontalArrangement = Arrangement.spacedBy(spacing)) {
                buttons(Modifier.weight(1f))
            }
        } else {
            val buttonWidth = 76.dp * (fontScale / 1.3f).coerceIn(1f, 1.6f)
            FlowRow(container, horizontalArrangement = Arrangement.spacedBy(spacing, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(spacing), maxItemsInEachRow = 5) {
                buttons(Modifier.width(buttonWidth))
            }
        }
    }
}

@Composable
private fun CallToolbarButton(label: String, icon: ImageVector, tag: String, modifier: Modifier, labelSize: TextUnit, onClick: () -> Unit,
    enabled: Boolean = true, selected: Boolean? = null, danger: Boolean = false, speaking: Boolean = false, microphoneLevel: Float = 0f,
    badge: Int = 0, liveLevel: StateFlow<Float>? = null) {
    val shape = RoundedCornerShape(9.dp)
    val highlighted = enabled && selected == true
    val background = when {
        danger && enabled -> Color(0xFFBC213A)
        highlighted -> Color(0xFF4C375B)
        else -> CallHeaderSurface
    }
    val border = if (highlighted) Color(0xFFA98BBD) else if (danger && enabled) background else CallHeaderBorder
    val foreground = if (enabled) CallForeground else CallMuted.copy(alpha = .6f)
    val action = if (selected == null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        else Modifier.toggleable(selected, enabled = enabled, role = Role.Checkbox, onValueChange = { onClick() })
    Column(modifier.clip(shape).background(background).border(1.dp, border, shape).then(action).testTag(tag)
        .then(if (tag == "call-mic") Modifier.speakingStatus(speaking && enabled) else Modifier)
        .padding(horizontal = if (labelSize < 10.sp) 2.dp else 4.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
        Box {
            if (tag == "call-mic" && selected == true) MicrophoneLevelIcon(microphoneLevel, enabled, foreground, null,
                Modifier.size(21.dp).testTag("$tag-icon"), liveLevel = liveLevel)
            else Icon(icon, null, Modifier.size(21.dp), tint = foreground)
            if (badge > 0) Badge(Modifier.align(Alignment.TopEnd).offset(x = 7.dp, y = (-4).dp)) {
                Text(if (badge > 9) "9+" else badge.toString())
            }
        }
        Text(label, color = foreground, fontSize = labelSize, lineHeight = 14.sp, fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center, modifier = Modifier.testTag("$tag-label"))
    }
}

private fun selfName(state: CallState, profileLabel: String = ""): String = state.participants.find { it.id == state.participantId }?.name?.takeIf { it.isNotBlank() } ?: profileLabel.takeIf { it.isNotBlank() } ?: "Вы"
private data class VideoTileItem(val id: String, val participantId: String, val name: String, val feed: VideoFeed?,
    val microphone: Boolean, val tone: Int, val avatarName: String = name, val role: String? = null)

@Composable
private fun VideoTile(tile: VideoTileItem, state: CallState, phone: Boolean, modifier: Modifier, compact: Boolean = false, fillStage: Boolean = false,
    liveLevel: StateFlow<Float>? = null) {
    val speaking = state.isSpeaking(tile.participantId)
    val level = state.microphoneLevel(tile.participantId)
    val shape = RoundedCornerShape(if (compact) 15.dp else 10.dp)
    Box(modifier.speakingStatus(speaking).clip(shape).background(if (speaking) SpeakingBackground else CallTileColor)
        .border(if (speaking) 2.dp else 1.dp, if (speaking) SpeakingGreen else Color(0xFF605266), shape)) {
        if (tile.feed == null) {
            val avatarSize = if (fillStage) { if (phone) 64.dp else 88.dp } else { if (phone) 36.dp else 46.dp }
            val avatarFont = if (fillStage) { if (phone) 24.sp else 29.sp } else { if (phone) 12.sp else 14.sp }
            CallAvatar(tile.avatarName, Modifier.align(Alignment.Center).size(avatarSize), avatarFont, tile.tone)
        } else VideoContent(tile.feed, false)
        if (speaking && tile.feed != null) Box(Modifier.fillMaxSize().background(SpeakingGreen.copy(alpha = .09f)))
        val badgeTag = "call-participant-${tile.id}-badge"
        val caption = if (compact) Modifier.align(Alignment.BottomStart).fillMaxWidth()
            .then(if (tile.feed != null) Modifier.background(Color(0xD117131C)) else Modifier)
            .testTag(badgeTag).padding(start = 9.dp, end = 8.dp, top = 4.dp, bottom = 7.dp)
        else Modifier.align(Alignment.BottomCenter).padding(horizontal = 8.dp).padding(bottom = if (phone) 5.dp else 10.dp)
            .widthIn(max = 320.dp).clip(RoundedCornerShape(7.dp)).background(Color(0xD117131C)).testTag(badgeTag)
            .padding(horizontal = 9.dp, vertical = 6.dp)
        Row(caption, verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (phone || compact) 4.dp else 8.dp)) {
            Text(tile.name, color = if (speaking) SpeakingForeground else Color(0xFFF5F0F7),
                fontSize = if (compact) 9.sp else if (phone) 10.sp else 12.sp, lineHeight = 16.sp, letterSpacing = 0.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = compact))
            val badgeSize = if (compact) 13.dp else 16.dp
            if (!compact && (tile.role == "owner" || tile.role == "co_host")) {
                Icon(Icons.Outlined.VerifiedUser, meetingRole(tile.role), Modifier.size(badgeSize), tint = Color(0xFFF5F0F7))
            }
            val micModifier = Modifier.size(badgeSize).testTag("call-participant-${tile.id}-mic")
            val micTint = Color(0xFFC5B8CC)
            if (tile.microphone) MicrophoneLevelIcon(level, state.phase == CallPhase.CONNECTED, micTint,
                if (speaking) "Говорит" else "Микрофон включён", micModifier, liveLevel = liveLevel)
            else Icon(CallIcons.MicOff, "Без звука", micModifier, tint = micTint)
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
private fun PrejoinControl(label: String, icon: ImageVector, enabled: Boolean, selected: Boolean, tag: String, click: () -> Unit, highlightOff: Boolean = false) {
    val background = if (enabled && highlightOff && !selected) Color.White else Color.White.copy(alpha = .125f)
    val foreground = when {
        !enabled -> Color(0xFF8290A5)
        highlightOff && !selected -> Color(0xFF28374D)
        else -> Color.White
    }
    val action = if (highlightOff) Modifier.toggleable(value = selected, enabled = enabled, role = Role.Checkbox, onValueChange = { click() })
    else Modifier.clickable(enabled = enabled, role = Role.Button, onClick = click)
    Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(background).testTag(tag).then(action),
        contentAlignment = Alignment.Center) {
        Icon(icon, label, Modifier.size(20.dp), tint = foreground)
    }
}
