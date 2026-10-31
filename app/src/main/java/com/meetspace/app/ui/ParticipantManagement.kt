package com.meetspace.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.meetspace.core.model.MeetingModeration
import com.meetspace.core.model.MeetingParticipant
import com.meetspace.core.model.canModerateMeeting

internal fun canManageMeetingParticipant(actor: MeetingParticipant?, person: MeetingParticipant) =
    canModerateMeeting(actor, person, "kick") ||
        (person.role != "guest" && canModerateMeeting(actor, person, "role"))

@Composable
internal fun ParticipantManagement(person: MeetingParticipant, room: MeetingRoomUiState, actions: MeetingRoomActions, close: () -> Unit) {
    var confirmRemoval by remember(person.id) { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val canChangeRole = person.role != "guest" && canModerateMeeting(room.self, person, "role")
    val canRemove = canModerateMeeting(room.self, person, "kick")
    val cohost = person.role == "co_host"
    Dialog(onDismissRequest = { if (!room.busy) close() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp), contentAlignment = Alignment.Center) {
            val stacked = maxWidth < 368.dp && LocalDensity.current.fontScale >= 1.3f
            Surface(Modifier.widthIn(max = 460.dp).fillMaxWidth().heightIn(max = maxHeight).testTag("participant-management"),
                shape = RoundedCornerShape(24.dp), color = colors.surface,
                border = BorderStroke(1.dp, colors.outlineVariant)) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(if (confirmRemoval) "Удалить участника?" else "Участник встречи", Modifier.weight(1f).semantics { heading() },
                            fontSize = 18.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold)
                        IconButton(close, enabled = !room.busy, modifier = Modifier.testTag("participant-close")) {
                            Icon(Icons.Outlined.Close, "Закрыть управление участником", tint = colors.onSurfaceVariant)
                        }
                    }
                    HorizontalDivider(color = colors.outlineVariant)
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(20.dp)
                        .testTag("participant-content"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Surface(shape = RoundedCornerShape(16.dp), color = colors.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                            ParticipantContentLayout(stacked, Modifier.padding(16.dp), leading = {
                                Surface(Modifier.size(48.dp), shape = RoundedCornerShape(16.dp), color = colors.primaryContainer) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(person.displayName.trim().split(Regex("\\s+")).take(2).mapNotNull { it.firstOrNull() }.joinToString("").uppercase(),
                                            color = colors.primary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(person.displayName, fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.testTag("participant-name"))
                                    Text(meetingRole(person.role), color = colors.onSurfaceVariant, fontSize = 12.sp, lineHeight = 18.sp,
                                        modifier = Modifier.testTag("participant-current-role"))
                                }
                            }
                        }
                        if (confirmRemoval) {
                            Text("Участник потеряет доступ к встрече и не сможет войти снова по этой ссылке.",
                                color = colors.onSurfaceVariant, fontSize = 14.sp, lineHeight = 21.sp)
                        } else {
                            if (canChangeRole) ParticipantAction(
                                if (cohost) "Снять роль соорганизатора" else "Сделать соорганизатором",
                                if (cohost) "Вернуть роль участника" else "Разрешить управление участниками встречи",
                                Icons.Outlined.AdminPanelSettings, room.busy, false, stacked, "participant-role",
                            ) { actions.moderate(person.id, MeetingModeration("role", role = if (cohost) "participant" else "co_host")) }
                            if (canRemove) ParticipantAction("Удалить из встречи", "Закрыть доступ к этой встрече",
                                Icons.Outlined.PersonRemove, room.busy, true, stacked, "participant-remove") { confirmRemoval = true }
                        }
                        if (room.busy) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("participant-progress"))
                        room.error?.let { error ->
                            Text(error, color = colors.error, fontSize = 13.sp, lineHeight = 20.sp,
                                modifier = Modifier.testTag("participant-error").semantics { liveRegion = LiveRegionMode.Polite })
                        }
                    }
                    HorizontalDivider(color = colors.outlineVariant)
                    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (confirmRemoval) {
                            OutlinedButton({ confirmRemoval = false }, enabled = !room.busy, shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.heightIn(min = 48.dp).testTag("participant-cancel-remove")) { Text("Отмена") }
                            Button({ actions.moderate(person.id, MeetingModeration("kick")) }, enabled = !room.busy && canRemove,
                                colors = ButtonDefaults.buttonColors(containerColor = colors.error, contentColor = colors.onError),
                                shape = RoundedCornerShape(12.dp), modifier = Modifier.heightIn(min = 48.dp).testTag("participant-confirm-remove")) {
                                Icon(Icons.Outlined.PersonRemove, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Удалить")
                            }
                        } else TextButton(close, enabled = !room.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Готово") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ParticipantAction(title: String, subtitle: String, icon: ImageVector, busy: Boolean, destructive: Boolean, stacked: Boolean, tag: String, action: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val tint = if (destructive) colors.error else colors.primary
    Surface(onClick = action, enabled = !busy, shape = RoundedCornerShape(16.dp), color = colors.surface,
        border = BorderStroke(1.dp, if (destructive) colors.error.copy(alpha = .22f) else colors.outlineVariant),
        modifier = Modifier.fillMaxWidth().testTag(tag)) {
        ParticipantContentLayout(stacked, Modifier.padding(14.dp), leading = {
            Surface(shape = RoundedCornerShape(12.dp), color = tint.copy(alpha = .08f), modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(22.dp), tint = tint.copy(alpha = if (busy) .5f else 1f)) }
            }
        }) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = (if (destructive) colors.error else colors.onSurface).copy(alpha = if (busy) .5f else 1f),
                    fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = colors.onSurfaceVariant.copy(alpha = if (busy) .5f else 1f), fontSize = 12.sp, lineHeight = 18.sp)
            }
        }
    }
}

@Composable
private fun ParticipantContentLayout(stacked: Boolean, modifier: Modifier, leading: @Composable () -> Unit, content: @Composable () -> Unit) {
    if (stacked) Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        leading()
        content()
    } else Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        leading()
        Box(Modifier.weight(1f)) { content() }
    }
}
