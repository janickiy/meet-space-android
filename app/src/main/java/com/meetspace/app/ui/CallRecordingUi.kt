package com.meetspace.app.ui

import android.view.Gravity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.selection.selectable
import com.meetspace.core.model.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

enum class CallRecordingStatus { UNKNOWN, STOPPED, STARTING, RECORDING, PAUSED, STOPPING }

data class CallRecordingUiState(
    val modes: List<String> = emptyList(),
    val selectedMode: String = "composite",
    val status: CallRecordingStatus = CallRecordingStatus.UNKNOWN,
    val busy: Boolean = false,
    val canStart: Boolean = false,
    val canPause: Boolean = false,
    val canResume: Boolean = false,
    val canStop: Boolean = false,
    val error: String? = null,
    val unavailableReason: String? = null,
    val description: String? = null,
)

data class CallRecordingActions(
    val selectMode: (String) -> Unit = {},
    val start: () -> Unit = {},
    val pause: () -> Unit = {},
    val resume: () -> Unit = {},
    val stop: () -> Unit = {},
    val retry: () -> Unit = {},
)

private val CallRecordingStatus.label: String get() = when (this) {
    CallRecordingStatus.UNKNOWN -> "Запись"
    CallRecordingStatus.STOPPED -> "Записать"
    CallRecordingStatus.STARTING -> "Запуск записи…"
    CallRecordingStatus.RECORDING -> "Идёт запись"
    CallRecordingStatus.PAUSED -> "На паузе"
    CallRecordingStatus.STOPPING -> "Завершение записи…"
}

private val CallRecordingStatus.icon: ImageVector get() = when (this) {
    CallRecordingStatus.RECORDING, CallRecordingStatus.STOPPING -> CallIcons.CircleDot
    CallRecordingStatus.PAUSED -> CallIcons.Pause
    else -> CallIcons.Circle
}

@Composable
internal fun CallRecordingControl(state: CallRecordingUiState, phone: Boolean, open: () -> Unit) {
    val tint = when (state.status) {
        CallRecordingStatus.PAUSED -> Color(0xFFEBC37D)
        CallRecordingStatus.RECORDING, CallRecordingStatus.STOPPING -> Color(0xFFFB8998)
        else -> Color(0xFFADC1FF)
    }
    Box(Modifier.heightIn(min = 48.dp).testTag("call-recording-control")
        .clickable(role = Role.Button, onClickLabel = "Управление записью", onClick = open)
        .semantics { contentDescription = "Управление записью: ${state.status.label}" }, contentAlignment = Alignment.Center) {
        Row(Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFF293346)).border(1.dp, Color(0xFF3A455A), RoundedCornerShape(12.dp))
            .heightIn(min = if (phone) 36.dp else 40.dp).padding(horizontal = if (phone) 8.dp else 12.dp, vertical = if (phone) 8.dp else 9.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (phone) 6.dp else 8.dp)) {
            Icon(state.status.icon, null, Modifier.size(if (phone) 14.dp else 17.dp), tint = tint)
            Text(state.status.label, color = Color(0xFFDBE4F4), fontSize = if (phone) 9.sp else 11.sp, lineHeight = if (phone) 14.sp else 17.sp,
                fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("call-recording-status"))
            Icon(CallIcons.ChevronDown, null, Modifier.size(if (phone) 11.dp else 13.dp), tint = Color(0xFF8EA0BB))
        }
    }
}

/** Displays only server-confirmed recording state; actions never change it optimistically. */
@Composable
internal fun CallRecordingDialog(title: String, state: CallRecordingUiState, actions: CallRecordingActions, phone: Boolean, close: () -> Unit) {
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setGravity(if (phone) Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL else Gravity.CENTER) }
        val colors = MaterialTheme.colorScheme
        Box(Modifier.fillMaxWidth().padding(if (phone) 14.dp else 20.dp), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 440.dp).fillMaxWidth().clip(RoundedCornerShape(if (phone) 23.dp else 24.dp))
                .background(colors.surface).border(1.dp, colors.outlineVariant, RoundedCornerShape(if (phone) 23.dp else 24.dp))
                .testTag("call-recording-dialog").verticalScroll(rememberScrollState()).padding(if (phone) 21.dp else 24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Запись встречи", Modifier.weight(1f).semantics { heading() }, color = colors.onSurface, fontSize = if (phone) 20.sp else 21.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold)
                    IconButton(close, Modifier.size(48.dp).testTag("call-recording-close")) { Icon(CallIcons.X, "Закрыть управление записью", Modifier.size(20.dp), tint = colors.onSurfaceVariant) }
                }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.surfaceContainerLow).border(1.dp, colors.outlineVariant, RoundedCornerShape(16.dp)).padding(17.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(15.dp)) {
                    val active = state.status in setOf(CallRecordingStatus.RECORDING, CallRecordingStatus.STOPPING)
                    val paused = state.status == CallRecordingStatus.PAUSED
                    val tint = if (active) Color(0xFFD95265) else if (paused) Color(0xFFB27C14) else colors.primary
                    Box(Modifier.size(45.dp).clip(RoundedCornerShape(13.dp)).background(tint.copy(alpha = .1f)), contentAlignment = Alignment.Center) {
                        Icon(state.status.icon, null, Modifier.size(20.dp), tint = tint)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(if (state.status == CallRecordingStatus.STOPPED) "Запись остановлена" else if (state.status == CallRecordingStatus.UNKNOWN) "Состояние записи неизвестно" else state.status.label,
                            color = colors.onSurface, fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
                        Text(title, color = colors.onSurfaceVariant, fontSize = 13.sp, lineHeight = 22.sp)
                    }
                }
                if (state.busy || state.status in setOf(CallRecordingStatus.STARTING, CallRecordingStatus.STOPPING)) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("call-recording-busy"))
                state.description?.let { Text(it, color = colors.onSurfaceVariant, fontSize = 13.sp, lineHeight = 22.sp) }
                state.unavailableReason?.let { Text(it, color = colors.onSurfaceVariant, fontSize = 13.sp, lineHeight = 22.sp, modifier = Modifier.testTag("call-recording-unavailable")) }
                state.error?.let { error ->
                    Text(error, color = colors.error, fontSize = 13.sp, lineHeight = 22.sp, modifier = Modifier.testTag("call-recording-error").semantics { liveRegion = LiveRegionMode.Polite })
                    TextButton(actions.retry, Modifier.heightIn(min = 48.dp).testTag("call-recording-retry"), enabled = !state.busy) { Text("Обновить состояние") }
                }
                if (state.status == CallRecordingStatus.STOPPED && state.canStart) {
                    Text("Режим записи", fontWeight = FontWeight.SemiBold)
                    state.modes.forEach { mode ->
                        val selected = mode == state.selectedMode
                        Surface(shape = RoundedCornerShape(12.dp), color = if (selected) colors.primaryContainer else colors.surface,
                            border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) colors.primary else colors.outlineVariant),
                            modifier = Modifier.fillMaxWidth().selectable(selected, enabled = !state.busy, role = Role.RadioButton) { actions.selectMode(mode) }.testTag("recording-mode-$mode")) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                RadioButton(selected, null)
                                Column { Text(recordingModeName(mode), fontSize = 13.sp, fontWeight = FontWeight.SemiBold); Text(recordingModeDescription(mode), color = colors.onSurfaceVariant, fontSize = 11.sp) }
                            }
                        }
                    }
                }
                val enabled = !state.busy
                when (state.status) {
                    CallRecordingStatus.STOPPED -> RecordingAction("Начать запись", CallIcons.Circle, enabled && state.canStart, "start", actions.start)
                    CallRecordingStatus.RECORDING -> if (state.canPause) RecordingAction("Приостановить запись", CallIcons.Pause, enabled, "pause", actions.pause)
                    CallRecordingStatus.PAUSED -> RecordingAction("Продолжить запись", CallIcons.Play, enabled && state.canResume, "resume", actions.resume)
                    else -> Unit
                }
                if (state.status in setOf(CallRecordingStatus.STARTING, CallRecordingStatus.RECORDING, CallRecordingStatus.PAUSED)) {
                    OutlinedButton(actions.stop, enabled = enabled && state.canStop, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("call-recording-stop"), shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFC64858))) {
                        Icon(CallIcons.Square, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Остановить запись")
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordingAction(label: String, icon: ImageVector, enabled: Boolean, tag: String, click: () -> Unit) {
    Button(click, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("call-recording-$tag"), shape = RoundedCornerShape(14.dp)) {
        Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(label)
    }
}

internal fun recordingModeName(mode: String) = when (mode) { "audio_only" -> "Только аудио"; "individual_tracks" -> "Отдельные дорожки"; "screen_focus" -> "Фокус на экране"; else -> "Общая запись" }
internal fun recordingModeDescription(mode: String) = when (mode) { "audio_only" -> "Голоса участников без видео"; "individual_tracks" -> "Файлы участников для монтажа"; "screen_focus" -> "Демонстрация экрана и звук встречи"; else -> "Участники и демонстрация экрана" }
