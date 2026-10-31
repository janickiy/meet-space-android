package com.meetspace.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as ComposeSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Device tests stay on this device. No samples, files or streams are sent to the server. */
@Composable
fun AccountAudioPanel(settings: AppSettingsUiState, actions: AppSettingsActions) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val appContext = context.applicationContext
    val audioManager = remember(appContext) { appContext.getSystemService(AudioManager::class.java) }
    var inputs by remember { mutableStateOf(audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()) }
    var inputId by remember { mutableStateOf<Int?>(null) }
    val probe = remember(appContext) { AccountAudioProbe(appContext) }
    var permissionNotice by remember { mutableStateOf<String?>(null) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            permissionNotice = null
            probe.microphone(inputId)
        } else if (!granted) permissionNotice = "Доступ к микрофону не разрешён. Его можно включить в настройках Android."
    }
    DisposableEffect(audioManager, probe, lifecycle) {
        val handler = Handler(Looper.getMainLooper())
        val devices = object : AudioDeviceCallback() {
            private fun refresh() { inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList() }
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
        }
        audioManager.registerAudioDeviceCallback(devices, handler)
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) probe.stop() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); audioManager.unregisterAudioDeviceCallback(devices); probe.close() }
    }
    val device = settings.devicePreferences
    val inputChoices = buildList {
        add(null to "По умолчанию")
        inputs.filter { it.type in inputDeviceTypes }.forEach { add(it.id to audioDeviceName(it)) }
    }
    LaunchedEffect(inputChoices) { if (inputChoices.none { it.first == inputId }) inputId = null }
    Column(Modifier.fillMaxWidth().testTag("account-audio-panel")) {
        MediaPanelHeading("Аудио", "Подготовьте звук к следующей встрече.")
        MediaChoice("Микрофон", inputChoices.firstOrNull { it.first == inputId }?.second ?: "По умолчанию", inputChoices, "account-mic", enabled = probe.kind == null) { inputId = it }
        DeviceTestRow("mic", Icons.Outlined.Mic, "Проверить микрофон", probe, permissionNotice) {
            if (probe.kind == AudioProbeKind.MICROPHONE) probe.stop()
            else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                permissionNotice = null; probe.microphone(inputId)
            } else micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
        MediaHint("Выбор микрофона относится к этой проверке. Во время встречи устройство выбирает Android.")
        MediaToggle("Подключаться с выключенным микрофоном", device.autoMute, { actions.updateDevicePreferences(device.copy(autoMute = it)) }, "account-auto-mute")
        MediaSectionDivider()
        val routes = listOf(SpeakerPreference.DEFAULT to "По умолчанию", SpeakerPreference.SPEAKER to "Динамик устройства", SpeakerPreference.HEADSET to "Наушники")
        MediaChoice("Динамик", routes.first { it.first == device.speaker }.second, routes, "account-speaker", enabled = probe.kind == null) { actions.updateDevicePreferences(device.copy(speaker = it)) }
        DeviceTestRow("speaker", Icons.Outlined.VolumeUp, "Проверить динамик", probe) {
            if (probe.kind == AudioProbeKind.SPEAKER) probe.stop() else probe.speaker(device.speaker)
        }
        MediaHint("Выбранный выход используется, если он доступен. Громкость регулируется кнопками Android.")
        MediaSectionDivider()
        var notificationRevision by remember { mutableIntStateOf(0) }
        DisposableEffect(lifecycle) {
            val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) notificationRevision++ }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }
        val notificationSound = remember(notificationRevision) { accountNotificationSound(appContext) }
        Text("Звук приглашения", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 10.dp))
        OutlinedButton(onClick = { openAccountSoundSettings(context, actions) }, shape = RoundedCornerShape(12.dp), border = mediaFieldBorder(), modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).testTag("account-ringtone")) {
            Text(if (notificationSound == null) "Без звука · настройки Android" else "Сигнал уведомлений Android", Modifier.weight(1f), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
            Icon(Icons.Outlined.Settings, null, Modifier.size(18.dp))
        }
        DeviceTestRow("ringtone", Icons.Outlined.VolumeUp, "Проверить звук приглашения", probe) {
            if (probe.kind == AudioProbeKind.RINGTONE) probe.stop() else probe.ringtone(notificationSound)
        }
        MediaNote("Проверка микрофона длится до 8 секунд; звук не сохраняется. Тесты останавливаются при уходе с экрана. Настройки устройств сохраняются на этом устройстве.")
        TextButton(actions.openSystemSettings, contentPadding = PaddingValues(0.dp), modifier = Modifier.heightIn(min = 48.dp).testTag("settings-system-permissions")) {
            Icon(Icons.Outlined.Settings, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Разрешения микрофона и звука", fontSize = 12.6.sp)
        }
    }
}

/** A local Camera2 surface, independent of WebRTC and never attached to a meeting. */
@Composable
fun AccountVideoPanel(name: String, settings: AppSettingsUiState, actions: AppSettingsActions) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val manager = remember(context.applicationContext) { context.applicationContext.getSystemService(CameraManager::class.java) }
    val cameras = remember(manager) { availablePreviewCameras(manager) }
    var selectedId by remember { mutableStateOf(cameras.firstOrNull { it.front }?.id ?: cameras.firstOrNull()?.id) }
    var previewRequested by remember { mutableStateOf(false) }
    var previewFrame by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val rotation = LocalView.current.display?.rotation ?: Surface.ROTATION_0
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) { notice = null; previewRequested = true }
        else if (!granted) notice = "Доступ к камере не разрешён. Его можно включить в настройках Android."
    }
    val camera = remember(manager) { AccountCameraPreview(manager) }
    DisposableEffect(camera, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) {
            previewRequested = false; previewFrame = false; camera.close()
        } }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); camera.close() }
    }
    val device = settings.devicePreferences
    Column(Modifier.fillMaxWidth().testTag("account-video-panel")) {
        MediaPanelHeading("Видео", "Предпросмотр доступен только вам.")
        Box(Modifier.fillMaxWidth().heightIn(min = 250.dp).clip(RoundedCornerShape(18.dp)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.background).testTag("account-camera-preview")) {
            Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 48.dp, bottom = 22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (previewRequested && selectedId != null) {
                    val previewId = selectedId!!
                    key(previewId) {
                        AndroidView(factory = { viewContext -> TextureView(viewContext).apply {
                            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                                    if (!previewRequested || selectedId != previewId || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
                                    camera.open(previewId, this@apply, texture, width, height, rotation) { message ->
                                        notice = message; previewRequested = false; previewFrame = false
                                    }
                                }
                                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) { camera.transform(this@apply, width, height, rotation) }
                                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean { camera.close(); return true }
                                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
                                    if (previewRequested && selectedId == previewId) previewFrame = true
                                }
                            }
                        } }, onRelease = { it.surfaceTextureListener = null; camera.close() },
                            modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(12.dp)).testTag(if (previewFrame) "account-camera-live" else "account-camera-starting"))
                    }
                } else {
                    val initials = name.trim().split(Regex("\\s+")).filter(String::isNotBlank).take(2).mapNotNull { it.firstOrNull()?.uppercaseChar() }.joinToString("")
                    Box(Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                        Text(initials, fontSize = 23.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Text(if (previewFrame && previewRequested) "Предпросмотр камеры" else if (previewRequested) "Включаем камеру…" else "Камера выключена", fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                    modifier = Modifier.testTag("account-camera-status").semantics { liveRegion = LiveRegionMode.Polite })
                Text("Изображение остаётся на этом устройстве", fontSize = 11.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = {
                    if (previewRequested) { previewRequested = false; previewFrame = false; camera.close() }
                    else if (selectedId == null) notice = "Камера не найдена на этом устройстве."
                    else if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) { notice = null; previewRequested = true }
                    else cameraPermission.launch(Manifest.permission.CAMERA)
                }, shape = RoundedCornerShape(12.dp), modifier = Modifier.heightIn(min = 48.dp).testTag("account-camera-toggle")) {
                    Icon(if (previewRequested) Icons.Outlined.VideocamOff else Icons.Outlined.Videocam, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text(if (previewRequested) "Выключить предпросмотр" else "Показать предпросмотр", fontSize = 12.sp, lineHeight = 18.sp)
                }
            }
            Row(Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 14.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Lock, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Только вы", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        notice?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp).testTag("account-camera-error").semantics { liveRegion = LiveRegionMode.Polite }) }
        Spacer(Modifier.height(24.dp))
        val cameraChoices = cameras.map { it.id to if (it.front) "Фронтальная камера" else "Основная камера" }
        MediaChoice("Камера", cameraChoices.firstOrNull { it.first == selectedId }?.second ?: "Камера недоступна", cameraChoices, "account-camera", enabled = cameraChoices.isNotEmpty()) {
            previewRequested = false; previewFrame = false; camera.close(); selectedId = it; notice = null
        }
        MediaHint("Выбор относится к этому предпросмотру. Во время встречи камеру можно переключить в панели звонка.")
        MediaSectionDivider()
        MediaToggle("Подключаться с выключенной камерой", device.cameraOff, { actions.updateDevicePreferences(device.copy(cameraOff = it)) }, "account-camera-off")
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        MediaToggle("Видеть себя на встрече", device.showSelf, { actions.updateDevicePreferences(device.copy(showSelf = it)) }, "account-self-video", "Ваше видео или аватар среди участников")
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        MediaToggle("Скрыть видео участников", device.hideParticipantVideo, { actions.updateDevicePreferences(device.copy(hideParticipantVideo = it)) }, "account-hide-video", "Показывать аватары вместо видео. Звук остаётся включённым.")
        MediaNote("Настройки сохраняются на этом устройстве. Предпросмотр выключается при уходе с экрана или в фон; повторное включение — только по нажатию.")
    }
}

@Composable
private fun MediaPanelHeading(title: String, subtitle: String) {
    val wide = LocalConfiguration.current.screenWidthDp >= 780
    Column(Modifier.padding(bottom = if (wide) 32.dp else 30.dp)) {
        Text(title, fontSize = if (wide) 23.sp else 21.sp, lineHeight = 29.sp, letterSpacing = (-.5).sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
        Text(subtitle, fontSize = 13.2.sp, lineHeight = 21.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable private fun mediaFieldBorder() = BorderStroke(1.dp, lerp(MaterialTheme.colorScheme.outlineVariant, MaterialTheme.colorScheme.onSurfaceVariant, .65f))

@Composable
private fun <T> MediaChoice(label: String, value: String, options: List<Pair<T, String>>, tag: String, enabled: Boolean = true, change: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 10.dp))
    Box {
        OutlinedButton({ open = true }, enabled = enabled, shape = RoundedCornerShape(12.dp), border = mediaFieldBorder(), contentPadding = PaddingValues(horizontal = 14.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).testTag(tag)) {
            Text(value, Modifier.weight(1f), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
            Icon(Icons.Outlined.KeyboardArrowDown, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(open, { open = false }) {
            options.forEach { (key, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { open = false; change(key) }) }
        }
    }
}

@Composable
private fun MediaToggle(label: String, value: Boolean, change: (Boolean) -> Unit, tag: String, hint: String? = null, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 13.4.sp, lineHeight = 21.sp)
            hint?.let { Text(it, fontSize = 11.5.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
        }
        val track = (if (value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant).copy(alpha = if (enabled) 1f else .45f)
        Box(Modifier.size(48.dp).toggleable(value = value, enabled = enabled, role = Role.Switch, onValueChange = change).testTag(tag).semantics { contentDescription = label }
            .drawBehind {
                val top = (size.height - 26.dp.toPx()) / 2f
                drawRoundRect(track, Offset(2.dp.toPx(), top), ComposeSize(44.dp.toPx(), 26.dp.toPx()), CornerRadius(13.dp.toPx()))
                drawCircle(Color.White.copy(alpha = if (enabled) 1f else .6f), 10.dp.toPx(), Offset((if (value) 33 else 15).dp.toPx(), size.height / 2))
            })
    }
}

@Composable private fun MediaSectionDivider() { HorizontalDivider(Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.outlineVariant) }
@Composable private fun MediaHint(text: String) { Text(text, fontSize = 11.6.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp)) }
@Composable private fun MediaNote(text: String) {
    Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Icon(Icons.Outlined.Info, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, fontSize = 11.2.sp, lineHeight = 18.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DeviceTestRow(id: String, icon: ImageVector, label: String, probe: AccountAudioProbe, permissionNotice: String? = null, click: () -> Unit) {
    val kind = when (id) { "mic" -> AudioProbeKind.MICROPHONE; "speaker" -> AudioProbeKind.SPEAKER; else -> AudioProbeKind.RINGTONE }
    val active = probe.kind == kind
    val level = if (active) probe.level else 0f
    Column(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().height(6.dp).testTag("account-$id-meter").semantics {
            contentDescription = if (id == "mic") "Уровень микрофона" else "Ход проверки звука"
            progressBarRangeInfo = ProgressBarRangeInfo(level, 0f..1f)
        }, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(20) { index -> Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(if (level > index / 20f) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) }
        }
        TextButton(click, contentPadding = PaddingValues(0.dp), modifier = Modifier.heightIn(min = 48.dp).testTag("account-test-$id")) {
            Icon(if (active) Icons.Outlined.Stop else icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
            Text(if (active) "Остановить проверку" else label, fontSize = 12.6.sp, textAlign = TextAlign.Start)
        }
        val status = if (permissionNotice != null) permissionNotice else probe.status.takeIf { probe.lastKind == kind }
        status?.takeIf(String::isNotBlank)?.let { Text(it, fontSize = 11.2.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("account-$id-test-status").semantics { liveRegion = LiveRegionMode.Polite }) }
    }
}

private const val MESSAGING_CHANNEL = "meetspace-messaging"
private fun openAccountSoundSettings(context: Context, actions: AppSettingsActions) {
    val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(MESSAGING_CHANNEL)
    val intent = if (channel != null) Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, MESSAGING_CHANNEL)
    else Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    runCatching { context.startActivity(intent) }.onFailure { actions.openSystemSettings() }
}
private fun accountNotificationSound(context: Context): Uri? {
    val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(MESSAGING_CHANNEL)
    return if (channel != null) channel.sound else RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
}

private val inputDeviceTypes = setOf(AudioDeviceInfo.TYPE_BUILTIN_MIC, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET)
private fun audioDeviceName(device: AudioDeviceInfo): String = when (device.type) {
    AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Микрофон устройства"
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth"
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Динамик устройства"
    else -> "Гарнитура"
}

internal enum class AudioProbeKind { MICROPHONE, SPEAKER, RINGTONE }

/** Main-thread non-blocking polling makes stop/release ordered with every read and callback. */
internal class AccountAudioProbe(private val context: Context) : AutoCloseable {
    var kind: AudioProbeKind? by mutableStateOf(null); private set
    var lastKind: AudioProbeKind? by mutableStateOf(null); private set
    var level by mutableFloatStateOf(0f); private set
    var status by mutableStateOf(""); private set
    private val handler = Handler(Looper.getMainLooper())
    private var recorder: AudioRecord? = null
    private var track: AudioTrack? = null
    private var tone: Ringtone? = null
    private var startedAt = 0L
    private var durationMs = 0L
    private val samples = ShortArray(1600)
    private val poll = object : Runnable {
        override fun run() {
            if (kind == null) return
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            try {
                if (kind == AudioProbeKind.MICROPHONE) {
                    val count = recorder?.read(samples, 0, samples.size, AudioRecord.READ_NON_BLOCKING) ?: 0
                    if (count < 0) { stop("Микрофон недоступен. Повторите проверку."); return }
                    if (count > 0) {
                        var power = 0.0
                        repeat(count) { power += samples[it].toDouble() * samples[it] }
                        val rms = sqrt(power / count) / Short.MAX_VALUE
                        level = if (rms <= 0.00001) 0f else ((20 * log10(rms) + 60) / 60).toFloat().coerceIn(0f, 1f)
                    }
                } else level = (elapsed.toFloat() / durationMs).coerceIn(0f, 1f)
                if (elapsed >= durationMs) { stop(if (kind == AudioProbeKind.MICROPHONE) "Проверка завершена. Звук не сохранён." else "Проверка завершена. Если звука не было, проверьте громкость Android."); return }
                handler.postDelayed(this, 50L)
            } catch (_: RuntimeException) { stop("Не удалось завершить проверку. Устройство освобождено.") }
        }
    }

    @SuppressLint("MissingPermission")
    fun microphone(inputId: Int?) {
        stop("")
        lastKind = AudioProbeKind.MICROPHONE
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { status = "Разрешите доступ к микрофону."; return }
        try {
            val minimum = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0)
            val input = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(16_000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(max(minimum * 2, 6400)).build()
            recorder = input
            check(input.state == AudioRecord.STATE_INITIALIZED)
            if (inputId != null) {
                val device = context.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == inputId }
                check(device != null && input.setPreferredDevice(device))
            }
            input.startRecording()
            check(input.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            begin(AudioProbeKind.MICROPHONE, 8_000, "Говорите: индикатор показывает уровень микрофона.")
        } catch (_: Exception) { stop("Микрофон недоступен. Проверьте разрешение и подключение устройства.") }
    }

    fun speaker(preference: SpeakerPreference) {
        stop(""); lastKind = AudioProbeKind.SPEAKER
        try {
            val output = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(16_000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(24_000).setTransferMode(AudioTrack.MODE_STATIC).build()
            track = output
            check(output.state != AudioTrack.STATE_UNINITIALIZED)
            if (preference != SpeakerPreference.DEFAULT) {
                val outputs = context.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                val route = outputs.firstOrNull { device -> when (preference) {
                    SpeakerPreference.SPEAKER -> device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    SpeakerPreference.HEADSET -> device.type in setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET)
                    else -> false
                } }
                check(route != null && output.setPreferredDevice(route))
            }
            val pcm = ShortArray(12_000) { index ->
                val fade = minOf(index / 800.0, (11_999 - index) / 800.0, 1.0).coerceAtLeast(0.0)
                (sin(2.0 * Math.PI * 440 * index / 16_000) * Short.MAX_VALUE * .18 * fade).toInt().toShort()
            }
            check(output.write(pcm, 0, pcm.size) == pcm.size)
            check(output.state == AudioTrack.STATE_INITIALIZED)
            output.play()
            begin(AudioProbeKind.SPEAKER, 1_000, "Воспроизводим короткий тестовый сигнал.")
        } catch (_: Exception) { stop("Выбранный аудиовыход недоступен. Подключите устройство или выберите другой выход.") }
    }

    fun ringtone(uri: Uri?) {
        stop(""); lastKind = AudioProbeKind.RINGTONE
        if (uri == null) { status = "Звук уведомлений выключен в Android."; return }
        try {
            val sound = RingtoneManager.getRingtone(context, uri) ?: error("No system ringtone")
            tone = sound
            sound.audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            sound.play()
            begin(AudioProbeKind.RINGTONE, 3_000, "Проверяем текущий сигнал уведомлений Android.")
        } catch (_: Exception) { stop("Сигнал недоступен. Проверьте настройки уведомлений Android.") }
    }

    private fun begin(value: AudioProbeKind, duration: Long, text: String) {
        kind = value; lastKind = value; startedAt = SystemClock.elapsedRealtime(); durationMs = duration; status = text; level = 0f
        handler.post(poll)
    }
    fun stop(message: String = "Проверка остановлена.") {
        handler.removeCallbacks(poll)
        recorder?.let { runCatching { it.stop() }; runCatching { it.release() } }; recorder = null
        track?.let { runCatching { it.stop() }; runCatching { it.release() } }; track = null
        tone?.let { runCatching { it.stop() } }; tone = null
        kind = null; level = 0f; status = message
        samples.fill(0)
    }
    override fun close() { stop("") }
}

private data class PreviewCamera(val id: String, val front: Boolean)
private fun availablePreviewCameras(manager: CameraManager): List<PreviewCamera> = runCatching {
    manager.cameraIdList.mapNotNull { id ->
        val facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
        if (facing == CameraCharacteristics.LENS_FACING_FRONT || facing == CameraCharacteristics.LENS_FACING_BACK) PreviewCamera(id, facing == CameraCharacteristics.LENS_FACING_FRONT) else null
    }.distinctBy { it.front }.sortedByDescending { it.front }
}.getOrDefault(emptyList())

/** All callbacks use the main looper. An epoch rejects late camera-open/session callbacks. */
private class AccountCameraPreview(private val manager: CameraManager) : AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private var epoch = 0
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var surface: Surface? = null
    private var buffer = Size(640, 480)
    private var sensor = 90
    private var front = true

    @SuppressLint("MissingPermission")
    fun open(id: String, view: TextureView, texture: SurfaceTexture, width: Int, height: Int, rotation: Int, failure: (String) -> Unit) {
        close()
        val ticket = epoch
        fun fail() { if (ticket == epoch) { close(); failure("Камера недоступна. Закройте другое приложение с камерой и повторите.") } }
        try {
            val characteristics = manager.getCameraCharacteristics(id)
            sensor = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            front = characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            val choices = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(SurfaceTexture::class.java).orEmpty()
            val candidates = choices.filter { max(it.width, it.height) <= 1280 }.ifEmpty { choices.toList() }
            val turns = relativeRotation(rotation) % 180 != 0
            val ratio = width.toDouble() / height.coerceAtLeast(1)
            buffer = candidates.minByOrNull { abs((if (turns) it.height.toDouble() / it.width else it.width.toDouble() / it.height) - ratio) } ?: Size(640, 480)
            texture.setDefaultBufferSize(buffer.width, buffer.height)
            transform(view, width, height, rotation)
            val output = Surface(texture); surface = output
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (ticket != epoch) { camera.close(); return }
                    device = camera
                    try {
                        @Suppress("DEPRECATION")
                        camera.createCaptureSession(listOf(output), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(value: CameraCaptureSession) {
                                if (ticket != epoch) { value.close(); return }
                                session = value
                                try {
                                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                        addTarget(output)
                                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                    }.build()
                                    value.setRepeatingRequest(request, null, handler)
                                } catch (_: Exception) { fail() }
                            }
                            override fun onConfigureFailed(value: CameraCaptureSession) { value.close(); fail() }
                        }, handler)
                    } catch (_: Exception) { fail() }
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close(); fail() }
                override fun onError(camera: CameraDevice, error: Int) { camera.close(); fail() }
            }, handler)
        } catch (_: Exception) { fail() }
    }

    private fun relativeRotation(rotation: Int): Int {
        val degrees = when (rotation) { Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0 }
        return if (front) (360 - (sensor + degrees) % 360) % 360 else (sensor - degrees + 360) % 360
    }

    fun transform(view: TextureView, width: Int, height: Int, rotation: Int) {
        if (width <= 0 || height <= 0) return
        val degrees = relativeRotation(rotation)
        val turns = degrees % 180 != 0
        val rotatedWidth = if (turns) buffer.height else buffer.width
        val rotatedHeight = if (turns) buffer.width else buffer.height
        val scale = max(width.toFloat() / rotatedWidth, height.toFloat() / rotatedHeight)
        val matrix = Matrix().apply {
            setScale(buffer.width.toFloat() / width, buffer.height.toFloat() / height)
            postTranslate(-buffer.width / 2f, -buffer.height / 2f)
            postRotate(degrees.toFloat())
            postScale(scale, scale)
            postTranslate(width / 2f, height / 2f)
            if (front) postScale(-1f, 1f, width / 2f, height / 2f)
        }
        view.setTransform(matrix)
    }

    override fun close() {
        epoch++
        session?.let { runCatching { it.stopRepeating() }; runCatching { it.close() } }; session = null
        device?.let { runCatching { it.close() } }; device = null
        surface?.let { runCatching { it.release() } }; surface = null
    }
}
