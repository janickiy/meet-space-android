package com.meetspace.app.ui

import android.view.Gravity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.selection.selectable
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
    CallRecordingStatus.STOPPED -> "Запись"
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
internal fun CallRecordingControl(state: CallRecordingUiState, compact: Boolean, open: () -> Unit) {
    val active = state.status != CallRecordingStatus.UNKNOWN && state.status != CallRecordingStatus.STOPPED
    val tint = when (state.status) {
        CallRecordingStatus.PAUSED -> Color(0xFFEBC37D)
        CallRecordingStatus.RECORDING, CallRecordingStatus.STOPPING -> Color(0xFFFB8998)
        else -> CallForeground
    }
    val shape = RoundedCornerShape(10.dp)
    Row(Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).clip(shape).background(CallHeaderSurface).border(1.dp, CallHeaderBorder, shape)
        .testTag("call-recording-control")
        .clickable(role = Role.Button, onClickLabel = "Управление записью", onClick = open)
        .semantics { contentDescription = "Управление записью: ${state.status.label}" }.padding(horizontal = if (compact) 0.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
        Icon(state.status.icon, null, Modifier.size(19.dp), tint = tint)
        if (!compact) Text(if (active) state.status.label else "Запись", color = tint, fontSize = 12.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = if (active) Modifier.testTag("call-recording-status") else Modifier)
    }
}

/** Displays only server-confirmed recording state; actions never change it optimistically. */
@Composable
internal fun CallRecordingDialog(title: String, state: CallRecordingUiState, actions: CallRecordingActions, phone: Boolean, close: () -> Unit) {
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setGravity(if (phone) Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL else Gravity.CENTER) }
        val colors = MaterialTheme.colorScheme
        val padding = if (phone) 22.dp else 25.dp
        val shape = RoundedCornerShape(if (phone) 23.dp else 24.dp)
        val showFormats = state.status == CallRecordingStatus.STOPPED && state.canStart
        Box(Modifier.fillMaxWidth().padding(if (phone) 14.dp else 20.dp), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 440.dp).fillMaxWidth().clip(shape)
                .background(colors.surface).border(1.dp, colors.outlineVariant, shape)
                .testTag("call-recording-dialog").verticalScroll(rememberScrollState())
                .padding(start = padding, top = padding - 8.dp, end = padding, bottom = padding)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Запись встречи", Modifier.weight(1f).semantics { heading() }, color = colors.onSurface,
                        fontSize = if (phone) 20.sp else 21.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold)
                    IconButton(close, Modifier.offset(x = 10.dp).size(48.dp).testTag("call-recording-close")) {
                        Icon(CallIcons.X, "Закрыть управление записью", Modifier.size(20.dp), tint = colors.onSurface)
                    }
                }
                Spacer(Modifier.height(9.dp))
                Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    RecordingStatusSummary(title, state.status)
                    if (state.busy || state.status in setOf(CallRecordingStatus.STARTING, CallRecordingStatus.STOPPING)) {
                        LinearProgressIndicator(Modifier.fillMaxWidth().testTag("call-recording-busy"))
                    }
                    state.description?.let { RecordingHint(it) }
                    state.error?.let { error ->
                        Column {
                            Text(error, color = colors.error, fontSize = 13.sp, lineHeight = 22.sp,
                                modifier = Modifier.testTag("call-recording-error").semantics { liveRegion = LiveRegionMode.Polite })
                            TextButton(actions.retry, Modifier.heightIn(min = 48.dp).testTag("call-recording-retry"),
                                enabled = !state.busy) { Text("Обновить состояние") }
                        }
                    }
                    if (showFormats) {
                        RecordingFormats(state, phone, actions.selectMode)
                    }
                    val enabled = !state.busy
                    when (state.status) {
                        CallRecordingStatus.STOPPED -> RecordingAction("Начать запись", CallIcons.Circle, enabled && state.canStart, "start", actions.start)
                        CallRecordingStatus.RECORDING -> if (state.canPause) RecordingAction("Приостановить запись", CallIcons.Pause, enabled, "pause", actions.pause)
                        CallRecordingStatus.PAUSED -> RecordingAction("Продолжить запись", CallIcons.Play, enabled && state.canResume, "resume", actions.resume)
                        else -> Unit
                    }
                    if (state.status in setOf(CallRecordingStatus.STARTING, CallRecordingStatus.RECORDING, CallRecordingStatus.PAUSED)) {
                        OutlinedButton(actions.stop, enabled = enabled && state.canStop,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("call-recording-stop"),
                            shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.error)) {
                            Icon(CallIcons.Square, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Остановить запись")
                        }
                    }
                    if (showFormats) {
                        val permission = state.unavailableReason ?: "Остановить запись может её инициатор или организатор встречи."
                        RecordingHint("По умолчанию записи хранятся 7 дней. $permission")
                    } else {
                        state.unavailableReason?.let { RecordingHint(it, Modifier.testTag("call-recording-unavailable")) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordingStatusSummary(title: String, status: CallRecordingStatus) {
    val colors = MaterialTheme.colorScheme
    val stopped = status == CallRecordingStatus.STOPPED
    val tint = when (status) {
        CallRecordingStatus.RECORDING, CallRecordingStatus.STOPPING -> colors.error
        CallRecordingStatus.PAUSED -> Color(0xFFB27C14)
        else -> colors.primary
    }
    val background = when (status) {
        CallRecordingStatus.RECORDING, CallRecordingStatus.STOPPING -> colors.errorContainer
        CallRecordingStatus.PAUSED -> tint.copy(alpha = .1f)
        else -> colors.primaryContainer
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(46.dp).background(background, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
            Icon(if (stopped) CallIcons.Video else status.icon, null, Modifier.size(20.dp), tint = tint)
        }
        Column(Modifier.weight(1f)) {
            Text(if (status == CallRecordingStatus.UNKNOWN) "Состояние записи неизвестно" else status.label,
                color = colors.onSurface, fontSize = 15.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold)
            Text(title, color = colors.onSurfaceVariant, fontSize = 12.sp, lineHeight = 19.sp)
        }
    }
}

@Composable
private fun RecordingFormats(state: CallRecordingUiState, phone: Boolean, select: (String) -> Unit) {
    val columns = if (phone) 1 else 2
    Column(Modifier.fillMaxWidth().selectableGroup().padding(bottom = if (phone) 8.dp else 0.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Формат записи", Modifier.semantics { heading() }.padding(bottom = 2.dp),
            color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
        state.modes.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { mode ->
                    RecordingFormatCard(mode, mode == state.selectedMode, !state.busy, phone,
                        Modifier.weight(1f).fillMaxHeight()) { select(mode) }
                }
                if (row.size < columns) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun RecordingFormatCard(mode: String, selected: Boolean, enabled: Boolean, phone: Boolean, modifier: Modifier, select: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val icon = when (mode) {
        "audio_only" -> CallIcons.AudioLines
        "individual_tracks" -> CallIcons.Users
        else -> CallIcons.Video
    }
    val mark = if (selected) colors.primary else colors.outline
    Surface(modifier.selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = select)
        .testTag("recording-mode-$mode"), shape = RoundedCornerShape(12.dp),
        color = if (selected) colors.primaryContainer else colors.surface,
        border = BorderStroke(1.dp, if (selected) colors.primary else colors.outlineVariant)) {
        Row(Modifier.fillMaxSize().heightIn(min = if (phone) 66.dp else 74.dp)
            .padding(start = if (phone) 16.dp else 18.dp, end = 12.dp,
                top = if (phone) 11.dp else 13.dp, bottom = if (phone) 11.dp else 13.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(17.dp).background(colors.surface, CircleShape).border(1.dp, mark, CircleShape),
                contentAlignment = Alignment.Center) {
                if (selected) Box(Modifier.size(9.dp).background(mark, CircleShape))
            }
            Spacer(Modifier.width(15.dp))
            Icon(icon, null, Modifier.size(20.dp), tint = colors.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(recordingModeName(mode), color = colors.onSurface, fontSize = 13.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold)
                Text(recordingModeDescription(mode), color = colors.onSurfaceVariant, fontSize = 11.sp, lineHeight = 17.sp)
            }
        }
    }
}

@Composable
private fun RecordingHint(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, lineHeight = 22.sp)
}

@Composable
private fun RecordingAction(label: String, icon: ImageVector, enabled: Boolean, tag: String, click: () -> Unit) {
    Button(click, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("call-recording-$tag"),
        shape = RoundedCornerShape(12.dp)) {
        Icon(icon, null, Modifier.size(20.dp))
        Spacer(Modifier.width(9.dp))
        Text(label, fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold)
    }
}

internal fun recordingModeName(mode: String) = when (mode) { "audio_only" -> "Только аудио"; "individual_tracks" -> "Отдельные дорожки"; else -> "Общая запись" }
internal fun recordingModeDescription(mode: String) = when (mode) {
    "audio_only" -> "Разговор без видеодорожки"
    "individual_tracks" -> "Дорожки участников и общий микс"
    else -> "Видео участников, звук и демонстрация экрана"
}
