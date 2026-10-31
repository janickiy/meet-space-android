package com.meetspace.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.meetspace.app.NotificationSoundPreview

@Composable
internal fun AccountNotificationHeading(wide: Boolean) {
    Column(Modifier.fillMaxWidth().padding(bottom = if (wide) 32.dp else 30.dp)) {
        Text("Уведомления", fontSize = if (wide) 23.1.sp else 21.sp, lineHeight = if (wide) 30.03.sp else 27.3.sp,
            letterSpacing = (-.5).sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
        Text("Выберите события и настройте звук.", fontSize = 13.16.sp, lineHeight = 21.056.sp, letterSpacing = 0.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
    }
}

/** The prototype's event/sound form, with an explicit server acknowledgement before committing sound. */
@Composable
internal fun AccountNotificationPanel(state: MessagingUiState, actions: MessagingActions, settings: AppSettingsUiState,
    settingsActions: AppSettingsActions, wide: Boolean) {
    val current = state.notificationPreferences.data
    var draft by remember(current) { mutableStateOf(current) }
    var sound by remember(settings.notificationSoundEnabled) { mutableStateOf(settings.notificationSoundEnabled) }
    var saved by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    if (current == null) {
        Text(state.notificationPreferences.error ?: "Загружаем настройки уведомлений…", color = colors.onSurfaceVariant)
        TextButton(actions.loadNotifications) { Text("Обновить") }
        return
    }
    val prefs = draft ?: return
    val dirty = prefs != current || sound != settings.notificationSoundEnabled
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 44.dp) {
        Column(Modifier.fillMaxWidth().testTag("account-notification-form")) {
            NotificationSectionHeading("События", wide)
            NotificationSwitch("Приглашения и изменения встреч", prefs.invitation, !state.busy, "notification-pref-invitation") {
                saved = false; draft = prefs.copy(invitation = it)
            }
            HorizontalDivider(color = colors.outlineVariant)
            NotificationSwitch("Напоминания о встречах", prefs.reminder, !state.busy, "notification-pref-reminder") {
                saved = false; draft = prefs.copy(reminder = it)
            }
            HorizontalDivider(color = colors.outlineVariant)
            NotificationSwitch("Готовность записи", prefs.recording, !state.busy, "notification-pref-recording") {
                saved = false; draft = prefs.copy(recording = it)
            }
            HorizontalDivider(Modifier.padding(top = if (wide) 24.dp else 16.dp, bottom = if (wide) 24.dp else 20.dp), color = colors.outlineVariant)
            NotificationSectionHeading("Звук уведомлений", wide)
            AccountNotificationSound(sound, !state.busy) { saved = false; sound = it }
            FlowRow(Modifier.fillMaxWidth().padding(top = if (wide) 24.dp else 16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = {
                    val soundToSave = sound
                    val completed = { settingsActions.updateNotificationSound(soundToSave); saved = true }
                    if (prefs == current) { actions.clearMessage(); completed() } else actions.saveNotificationPreferences(prefs, completed)
                }, enabled = !state.busy && dirty, shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                    colors = ButtonDefaults.buttonColors(disabledContainerColor = colors.primary.copy(alpha = .45f), disabledContentColor = colors.onPrimary),
                    modifier = Modifier.heightIn(min = 46.dp).testTag("account-notification-save")) {
                    Text("Сохранить настройки", fontSize = 13.16.sp, lineHeight = 19.74.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp)
                }
                if (saved && !dirty) Row(Modifier.heightIn(min = 46.dp).testTag("account-notification-saved")
                    .semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Outlined.Check, null, Modifier.size(16.dp), tint = colors.primary)
                    Text("Настройки сохранены", fontSize = 11.9.sp, color = colors.primary)
                }
            }
            state.error?.let { Text(it, color = colors.error, modifier = Modifier.padding(top = 12.dp).testTag("account-notification-error")) }
        }
    }
}

@Composable
private fun NotificationSectionHeading(label: String, wide: Boolean) {
    Text(label, fontSize = 14.84.sp, lineHeight = 22.26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp,
        modifier = Modifier.padding(bottom = if (wide) 16.dp else 12.dp).semantics { heading() })
}

@Composable
private fun NotificationSwitch(label: String, value: Boolean, enabled: Boolean, tag: String, change: (Boolean) -> Unit) {
    val track = (if (value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant).copy(alpha = if (enabled) 1f else .45f)
    Row(Modifier.fillMaxWidth().toggleable(value, enabled = enabled, role = Role.Switch, onValueChange = change)
        .testTag(tag).padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Text(label, Modifier.weight(1f), fontSize = 13.44.sp, lineHeight = 20.832.sp, letterSpacing = 0.sp)
        Box(Modifier.size(51.dp, 32.dp).drawBehind {
            drawRoundRect(track, Offset(4.dp.toPx(), 3.dp.toPx()), Size(44.dp.toPx(), 26.dp.toPx()), CornerRadius(13.dp.toPx()))
            drawCircle(Color.White.copy(alpha = if (enabled) 1f else .6f), 10.dp.toPx(), Offset((if (value) 35 else 17).dp.toPx(), 16.dp.toPx()))
        })
    }
}

@Composable
internal fun AccountNotificationSound(value: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val preview = remember(context) { NotificationSoundPreview(context) }
    DisposableEffect(preview, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) preview.stop() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); preview.close() }
    }
    LaunchedEffect(value) { if (!value) preview.stop() }
    NotificationSwitch("Звуковой сигнал", value, enabled, "account-sound-enabled", change)
    TextButton(onClick = { if (preview.playing) preview.stop() else preview.play(value) }, enabled = enabled,
        contentPadding = PaddingValues(vertical = 4.dp), modifier = Modifier.heightIn(min = 44.dp).testTag("account-test-notification")
            .semantics { stateDescription = if (preview.playing) "Воспроизводится" else "Остановлен" }) {
        Icon(CallIcons.Volume2, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
        Text("Проверить звук", fontSize = 12.6.sp, lineHeight = 18.9.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.sp)
    }
    if (preview.status.isNotBlank()) Text(preview.status, fontSize = 11.2.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("account-notification-test-status").semantics { liveRegion = LiveRegionMode.Polite })
}
