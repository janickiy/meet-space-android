package com.meetspace.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.RingtoneManager
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.roundToInt

private val inputChoices = listOf(MicrophonePreference.DEFAULT to "По умолчанию", MicrophonePreference.DEVICE to "Микрофон устройства", MicrophonePreference.HEADSET to "Микрофон гарнитуры")
private val outputChoices = listOf(SpeakerPreference.DEFAULT to "По умолчанию", SpeakerPreference.SPEAKER to "Динамик устройства",
    SpeakerPreference.HEADSET to "Наушники", SpeakerPreference.BLUETOOTH to "Bluetooth-гарнитура")
private val cameraChoices = listOf(CameraPreference.DEFAULT to "По умолчанию", CameraPreference.FRONT to "Фронтальная камера", CameraPreference.BACK to "Основная камера")

@Composable
fun AccountAudioPanel(settings: AppSettingsUiState, actions: AppSettingsActions, wide: Boolean = false) {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val device = settings.devicePreferences
    val current by rememberUpdatedState(device)
    val probe = remember(context) { AccountAudioProbe(context) }
    val manager = remember(context) { context.getSystemService(AudioManager::class.java) }
    var microphonePermissionPending by remember { mutableStateOf(false) }
    var permissionNotice by remember { mutableStateOf<String?>(null) }
    fun testMic() {
        val input = manager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { when (current.microphone) {
            MicrophonePreference.DEFAULT -> false
            MicrophonePreference.DEVICE -> it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC
            MicrophonePreference.HEADSET -> it.type in setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET)
        } }
        if (current.microphone != MicrophonePreference.DEFAULT && input == null) { permissionNotice = "Выбранный микрофон не подключён."; return }
        permissionNotice = null; probe.microphone(input?.id, current.microphoneVolume, current.noiseSuppression)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val pending = microphonePermissionPending; microphonePermissionPending = false
        if (pending && granted && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) testMic()
        else if (!granted) permissionNotice = "Разрешите доступ к микрофону в настройках Android."
    }
    DisposableEffect(probe, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) { microphonePermissionPending = false; probe.stop() } }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); probe.close() }
    }
    fun update(value: AccountDevicePreferences) { microphonePermissionPending = false; probe.stop(""); permissionNotice = null; actions.updateDevicePreferences(value) }
    Column(Modifier.fillMaxWidth().testTag("account-audio-panel")) {
        MediaHeading("Аудио", "Подготовьте звук к следующей встрече.", wide)
        AudioCard("Микрофон", "mic", probe) {
            AudioSelectRow(wide, {
                MediaSelect("Микрофон", device.microphone, inputChoices, "account-mic") { update(device.copy(microphone = it)) }
            }) {
                AudioTestButton("mic", probe) {
                    if (probe.kind == AudioProbeKind.MICROPHONE) probe.stop()
                    else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) testMic()
                    else { microphonePermissionPending = true; permission.launch(Manifest.permission.RECORD_AUDIO) }
                }
            }
            ProbeStatus("mic", probe, permissionNotice ?: "Проверьте микрофон, чтобы увидеть уровень звука и названия устройств.")
            MediaVolume("Громкость микрофона", device.microphoneVolume, "account-microphone-volume") { update(device.copy(microphoneVolume = it)) }
            NotificationSwitch("Подключаться с выключенным микрофоном", device.autoMute, true, "account-auto-mute", verticalPadding = 13.dp) { update(device.copy(autoMute = it)) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            NotificationSwitch("Шумоподавление", device.noiseSuppression, true, "account-noise", verticalPadding = 13.dp) { update(device.copy(noiseSuppression = it)) }
        }
        AudioCard("Динамик", "speaker", probe) {
            AudioSelectRow(wide, { MediaSelect("Динамик", device.speaker, outputChoices, "account-speaker") { update(device.copy(speaker = it)) } }) {
                AudioTestButton("speaker", probe) { if (probe.kind == AudioProbeKind.SPEAKER) probe.stop() else probe.speaker(device.speaker, device.speakerVolume) }
            }
            ProbeStatus("speaker", probe)
            MediaVolume("Громкость динамика", device.speakerVolume, "account-speaker-volume") { update(device.copy(speakerVolume = it)) }
            OutputPicker(device.speaker, "speaker") { update(device.copy(speaker = it)) }
        }
        AudioCard("Звонки и уведомления", null, probe) {
            MediaHint("Громкость сигнала вызова и уведомлений о сообщениях, приглашениях и скором начале встречи.")
            Text("Источник звука", fontSize = 12.6.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 16.dp, bottom = 9.dp))
            MediaSelect("Динамик для сигналов", device.signalOutput, outputChoices, "account-signal-output") { update(device.copy(signalOutput = it)) }
            OutputPicker(device.signalOutput, "signals") { update(device.copy(signalOutput = it)) }
            MediaVolume("Громкость сигналов", device.signalVolume, "account-signal-volume") { update(device.copy(signalVolume = it)) }
            Spacer(Modifier.height(12.dp))
            AudioTestButton("ringtone", probe) {
                if (probe.kind == AudioProbeKind.RINGTONE) probe.stop()
                else probe.ringtone(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), device.signalOutput, device.signalVolume)
            }
            ProbeStatus("ringtone", probe)
            Spacer(Modifier.height(10.dp))
            MediaHint("Для сигналов в приложении. В фоне громкостью и выходом управляет Android. Беззвучный режим сохраняется.")
            if (android.os.Build.VERSION.SDK_INT < 28) MediaHint("На Android 8 аудиовыход для сигналов выбирает система.")
        }
        MediaHint("Настройки применяются автоматически и сохраняются на этом устройстве.")
    }
}

@Composable
private fun AudioCard(title: String, meter: String?, probe: AccountAudioProbe, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f).semantics { heading() }, fontSize = 15.12.sp, lineHeight = 22.68.sp, fontWeight = FontWeight.SemiBold)
            if (meter != null) {
                val active = probe.kind == if (meter == "mic") AudioProbeKind.MICROPHONE else AudioProbeKind.SPEAKER
                val level = if (active) probe.level else 0f
                Row(Modifier.testTag("account-$meter-meter").semantics { progressBarRangeInfo = ProgressBarRangeInfo(level, 0f..1f) }, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    repeat(8) { index -> Box(Modifier.size(5.dp, 15.dp).clip(RoundedCornerShape(3.dp)).background(
                        if (level > index / 8f) Color(0xFF32B78A) else MaterialTheme.colorScheme.outlineVariant)) }
                }
            }
        }
        content()
        HorizontalDivider(Modifier.padding(top = 22.dp, bottom = 24.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun AudioSelectRow(wide: Boolean, select: @Composable () -> Unit, test: @Composable () -> Unit) {
    if (wide) Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { select() }; test()
    } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        select(); Box(Modifier.fillMaxWidth()) { test() }
    }
}

@Composable
private fun AudioTestButton(id: String, probe: AccountAudioProbe, action: () -> Unit) {
    val kind = when (id) { "mic" -> AudioProbeKind.MICROPHONE; "speaker" -> AudioProbeKind.SPEAKER; else -> AudioProbeKind.RINGTONE }
    val active = probe.kind == kind
    val label = when (id) { "mic" -> "микрофон"; "speaker" -> "динамик"; else -> "сигналы" }
    OutlinedButton(action, shape = RoundedCornerShape(11.dp), border = BorderStroke(1.dp, if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            containerColor = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp), modifier = Modifier.heightIn(min = 46.dp).testTag("account-test-$id")
            .semantics { contentDescription = if (active) "Остановить проверку" else "Проверить $label"; stateDescription = if (active) "Проверка идёт" else "Остановлено" }) {
        Icon(if (active) Icons.Outlined.Stop else if (id == "mic") CallIcons.Mic else Icons.Outlined.PlayArrow, null, Modifier.size(17.dp))
        Spacer(Modifier.width(7.dp)); Text(if (active) "Остановить" else "Проверить", fontSize = 12.6.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ProbeStatus(id: String, probe: AccountAudioProbe, fallback: String = "") {
    val kind = when (id) { "mic" -> AudioProbeKind.MICROPHONE; "speaker" -> AudioProbeKind.SPEAKER; else -> AudioProbeKind.RINGTONE }
    val text = if (probe.lastKind == kind && probe.status.isNotEmpty()) probe.status else fallback
    if (text.isNotBlank()) Text(text, Modifier.padding(top = 10.dp).testTag("account-$id-test-status").semantics { liveRegion = LiveRegionMode.Polite },
        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.48.sp, lineHeight = 18.37.sp)
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun MediaVolume(label: String, value: Int, tag: String, change: (Int) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val rail = MaterialTheme.colorScheme.outlineVariant
    Column(Modifier.fillMaxWidth().padding(top = 20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(label, Modifier.weight(1f), fontSize = 12.6.sp, lineHeight = 18.9.sp)
            Surface(shape = RoundedCornerShape(9.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Text("$value%", Modifier.padding(horizontal = 8.dp, vertical = 5.dp), color = MaterialTheme.colorScheme.primary, fontSize = 10.71.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Slider(value.toFloat(), { change(it.roundToInt().coerceIn(0, 100)) }, valueRange = 0f..100f,
            thumb = { Box(Modifier.width(14.dp).height(44.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(14.dp).background(accent, CircleShape))
            } },
            track = { slider -> Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                drawLine(rail, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), size.height, StrokeCap.Round)
                drawLine(accent, Offset(0f, size.height / 2), Offset(size.width * slider.value / 100f, size.height / 2), size.height, StrokeCap.Round)
            } },
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag(tag).semantics { contentDescription = label; stateDescription = "$value%" })
    }
}

@Composable
private fun <T> MediaSelect(label: String, value: T, options: List<Pair<T, String>>, tag: String, change: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton({ open = true }, shape = RoundedCornerShape(11.dp),
            border = BorderStroke(1.dp, lerp(MaterialTheme.colorScheme.outlineVariant, MaterialTheme.colorScheme.onSurfaceVariant, .65f)),
            colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface),
            contentPadding = PaddingValues(horizontal = 13.dp, vertical = 10.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp).testTag(tag).semantics { contentDescription = label }) {
            Text(options.firstOrNull { it.first == value }?.second ?: "По умолчанию", Modifier.weight(1f), fontSize = 14.sp, lineHeight = 21.sp)
            Icon(Icons.Outlined.KeyboardArrowDown, null, Modifier.size(18.dp))
        }
        DropdownMenu(open, { open = false }) { options.forEach { (key, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { open = false; change(key) }) } }
    }
}

@Composable
private fun OutputPicker(value: SpeakerPreference, tag: String, change: (SpeakerPreference) -> Unit) {
    var open by remember { mutableStateOf(false) }
    TextButton({ open = true }, contentPadding = PaddingValues(0.dp), modifier = Modifier.heightIn(min = 44.dp).testTag("account-$tag-other-output")) {
        Text("Выбрать другое устройство", fontSize = 12.6.sp)
    }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text("Выберите устройство") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) { outputChoices.forEach { (key, label) ->
            OutlinedButton({ change(key); open = false }, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) {
                Icon(when (key) { SpeakerPreference.HEADSET -> Icons.Outlined.Headphones; SpeakerPreference.BLUETOOTH -> Icons.Outlined.Bluetooth; else -> CallIcons.Volume2 }, null, Modifier.size(19.dp))
                Spacer(Modifier.width(11.dp)); Text(label, Modifier.weight(1f), fontSize = 13.sp)
                if (key == value) Icon(Icons.Outlined.Check, null, Modifier.size(18.dp))
            }
        } }
    }, confirmButton = { TextButton({ open = false }) { Text("Закрыть") } })
}

@Composable
private fun MediaHeading(title: String, subtitle: String, wide: Boolean) {
    Column(Modifier.padding(bottom = if (wide) 32.dp else 30.dp)) {
        Text(title, fontSize = if (wide) 23.1.sp else 21.sp, lineHeight = if (wide) 30.03.sp else 27.3.sp,
            letterSpacing = (-.5).sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
        Text(subtitle, fontSize = 13.16.sp, lineHeight = 21.056.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun MediaHint(text: String) { Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.48.sp, lineHeight = 18.37.sp) }

@Composable
fun AccountVideoPanel(name: String, settings: AppSettingsUiState, actions: AppSettingsActions, wide: Boolean = false, twoColumns: Boolean = false) {
    val device = settings.devicePreferences
    Column(Modifier.fillMaxWidth().testTag("account-video-panel")) {
        MediaHeading("Видео", "Предпросмотр доступен только вам.", wide)
        val preview: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                CameraPreview(device.camera)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Камера", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    MediaSelect("Камера", device.camera, cameraChoices, "account-camera") { actions.updateDevicePreferences(device.copy(camera = it)) }
                }
            }
        }
        val options: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(if (twoColumns) 24.dp else 20.dp)) {
                NotificationSwitch("Подключаться с выключенной камерой", device.cameraOff, true, "account-camera-off", verticalPadding = 0.dp) { actions.updateDevicePreferences(device.copy(cameraOff = it)) }
                NotificationSwitch("Видеть себя на звонке", device.showSelf, true, "account-self-video", hint = "Ваше видео или аватар", verticalPadding = 0.dp) { actions.updateDevicePreferences(device.copy(showSelf = it)) }
                NotificationSwitch("Скрыть видео участников", device.hideParticipantVideo, true, "account-hide-video", hint = "Звук сохраняется; видео и экраны скрываются.", verticalPadding = 0.dp) { actions.updateDevicePreferences(device.copy(hideParticipantVideo = it)) }
            }
        }
        if (twoColumns) Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Box(Modifier.weight(1f)) { preview() }; Box(Modifier.weight(1f)) { options() }
        } else Column(verticalArrangement = Arrangement.spacedBy(24.dp)) { preview(); options() }
        HorizontalDivider(Modifier.padding(top = 24.dp, bottom = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
        MediaHint("Настройки применяются автоматически и сохраняются на этом устройстве.")
    }
}

@Composable
private fun CameraPreview(preference: CameraPreference) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val manager = remember(context) { context.getSystemService(CameraManager::class.java) }
    val cameras = remember(manager) { availablePreviewCameras(manager) }
    val selected = cameras.firstOrNull { if (preference == CameraPreference.BACK) !it.front else it.front } ?: cameras.firstOrNull()
    val camera = remember(manager) { AccountCameraPreview(manager) }
    var cameraPermissionPending by remember { mutableStateOf(false) }
    var requested by remember { mutableStateOf(false) }
    var frame by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val rotation = LocalView.current.display?.rotation ?: Surface.ROTATION_0
    fun stop() { cameraPermissionPending = false; requested = false; frame = false; camera.close() }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val pending = cameraPermissionPending; cameraPermissionPending = false
        if (pending && granted && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) { notice = null; requested = true }
        else if (!granted) notice = "Разрешите доступ к камере в настройках Android."
    }
    DisposableEffect(camera, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) stop() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); camera.close() }
    }
    LaunchedEffect(preference) { stop() }
    Column {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp)).background(Color(0xFF242127))
            .clickable {
                if (requested) stop()
                else if (selected == null) notice = "Камера не найдена."
                else if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) { notice = null; requested = true }
                else { cameraPermissionPending = true; permission.launch(Manifest.permission.CAMERA) }
            }.testTag("account-camera-toggle").semantics { contentDescription = if (requested) "Выключить предпросмотр" else "Показать предпросмотр" }, contentAlignment = Alignment.Center) {
            if (requested && selected != null) key(selected.id) {
                AndroidView(factory = { viewContext -> TextureView(viewContext).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                            if (!requested || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
                            camera.open(selected.id, this@apply, texture, width, height, rotation) { message -> notice = message; stop() }
                        }
                        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) { camera.transform(this@apply, width, height, rotation) }
                        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean { camera.close(); return true }
                        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) { if (requested) frame = true }
                    }
                } }, onRelease = { it.surfaceTextureListener = null; camera.close() },
                    modifier = Modifier.fillMaxSize().testTag(if (frame) "account-camera-live" else "account-camera-starting"))
            }
            if (!frame) Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.VideocamOff, null, Modifier.size(32.dp), tint = Color(0xFFF4F0F6))
                Text(if (requested) "Включаем камеру…" else "Предпросмотр камеры", color = Color(0xFFF4F0F6), fontSize = 12.6.sp,
                    modifier = Modifier.testTag("account-camera-status"))
            } else Text("Предпросмотр камеры", Modifier.align(Alignment.BottomCenter).padding(8.dp).testTag("account-camera-status"), color = Color.White, fontSize = 12.sp)
        }
        notice?.let { Text(it, Modifier.padding(top = 12.dp).testTag("account-camera-error").semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
    }
}
