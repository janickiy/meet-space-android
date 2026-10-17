package com.meetspace.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meetspace.core.model.*
import com.meetspace.core.media.CallState

data class MeetingRoomUiState(val participants: List<MeetingParticipant> = emptyList(), val selfId: String = "",
    val loading: Boolean = false, val busy: Boolean = false, val error: String? = null, val notice: String? = null,
    val query: String = "", val users: List<MeetingInviteUser> = emptyList()) {
    val self get() = participants.find { it.id == selfId }
    val waiting get() = participants.filter { it.admissionState == "waiting" || it.status == "waiting" }
}
data class MeetingRoomActions(val refresh: () -> Unit = {}, val admit: (String, Boolean) -> Unit = { _, _ -> },
    val moderate: (String, MeetingModeration) -> Unit = { _, _ -> }, val searchUsers: (String) -> Unit = {},
    val invite: (List<String>, List<String>) -> Unit = { _, _ -> })
internal fun meetingRole(role: String?) = when (role) { "owner" -> "Организатор"; "co_host" -> "Соорганизатор"; "guest" -> "Гость"; else -> "Участник" }

@Composable
internal fun MeetingRoomPanel(call: CallState, room: MeetingRoomUiState, actions: MeetingRoomActions, modifier: Modifier, close: () -> Unit) {
    var target by remember { mutableStateOf<MeetingParticipant?>(null) }
    var invite by remember { mutableStateOf(false) }
    val participants = room.participants.ifEmpty { call.participants.map { MeetingParticipant(it.id, "", null, it.name, "participant", "joined") } }
    val roster = participants.filter { it.isAdmitted }.sortedBy { if (it.id == room.selfId) 1 else 0 }
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = modifier.testTag("call-participants-panel")) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Участники · ${roster.size}", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(actions.refresh, enabled = !room.loading && !room.busy) { Icon(Icons.Outlined.Refresh, "Обновить участников") }
                IconButton(close, Modifier.testTag("call-panel-close")) { Icon(Icons.Outlined.Close, "Закрыть участников") }
            }
            if (room.loading || room.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            room.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (room.self?.role == "owner" && room.waiting.isNotEmpty()) item {
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth().testTag("call-waiting-room")) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Зал ожидания · ${room.waiting.size}", fontWeight = FontWeight.SemiBold)
                            room.waiting.forEach { person ->
                                Text(person.displayName)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button({ actions.admit(person.id, true) }, enabled = !room.busy, modifier = Modifier.testTag("admit-${person.id}")) { Text("Принять") }
                                    OutlinedButton({ actions.admit(person.id, false) }, enabled = !room.busy) { Text("Отклонить") }
                                }
                            }
                        }
                    }
                }
                items(roster, key = { it.id }) { person ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(42.dp)) {
                            Box(contentAlignment = Alignment.Center) { Text(person.displayName.split(' ').take(2).mapNotNull { it.firstOrNull() }.joinToString(""), color = MaterialTheme.colorScheme.primary, fontSize = 12.sp) }
                        }
                        Column(Modifier.weight(1f)) {
                            Text(if (person.id == room.selfId) "${person.displayName} (вы)" else person.displayName, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(meetingRole(person.role) + if (call.participants.none { it.id == person.id && it.online }) " · Не в сети" else "", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            call.participants.find { it.id == person.id }?.let { peer -> Text(if (peer.microphone) "Микрофон включён" else "Микрофон выключен", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            val restrictions = listOfNotNull("Микрофон".takeIf { person.microphoneBlocked }, "Камера".takeIf { person.cameraBlocked }, "Экран".takeIf { person.screenBlocked })
                            if (restrictions.isNotEmpty()) Text("Ограничено: ${restrictions.joinToString()}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (canModerateMeeting(room.self, person, "mute")) IconButton({ target = person }, enabled = !room.busy, modifier = Modifier.testTag("moderate-${person.id}")) { Icon(Icons.Outlined.MoreHoriz, "Управление ${person.displayName}") }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                if (room.self?.canParticipate == true && room.self?.role in setOf("owner", "co_host")) item { OutlinedButton({ invite = true }, Modifier.fillMaxWidth().testTag("call-invite")) { Icon(Icons.Outlined.PersonAdd, null); Spacer(Modifier.width(8.dp)); Text("Пригласить участников") } }
                if (roster.isEmpty() && !room.loading) item { Text("Список участников пока недоступен.") }
            }
        }
    }
    target?.let { old ->
        val person = room.participants.find { it.id == old.id }
        if (person != null) ParticipantManagement(person, room, actions) { target = null }
    }
    if (invite) MeetingInvitationDialog(room, actions) { invite = false }
}

@Composable
private fun ParticipantManagement(person: MeetingParticipant, room: MeetingRoomUiState, actions: MeetingRoomActions, close: () -> Unit) {
    var kick by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = close, title = { Text(person.displayName) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(meetingRole(person.role), color = MaterialTheme.colorScheme.onSurfaceVariant)
            listOf(Triple("mute", "микрофон", person.microphoneBlocked), Triple("camera", "камеру", person.cameraBlocked), Triple("screen", "экран", person.screenBlocked)).forEach { (key, label, blocked) ->
                if (canModerateMeeting(room.self, person, key)) OutlinedButton({ actions.moderate(person.id, MeetingModeration(key, !blocked)) }, enabled = !room.busy, modifier = Modifier.fillMaxWidth()) { Text(if (blocked) "Разрешить $label" else "Отключить $label") }
            }
            if (person.role != "guest" && canModerateMeeting(room.self, person, "role")) OutlinedButton({ actions.moderate(person.id, MeetingModeration("role", role = if (person.role == "co_host") "participant" else "co_host")) }, enabled = !room.busy) { Text(if (person.role == "co_host") "Снять роль соорганизатора" else "Сделать соорганизатором") }
            if (canModerateMeeting(room.self, person, "kick")) TextButton({ kick = true }, enabled = !room.busy) { Text("Удалить из встречи", color = MaterialTheme.colorScheme.error) }
            room.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton(close) { Text("Готово") } })
    if (kick) AlertDialog(onDismissRequest = { kick = false }, title = { Text("Удалить участника?") }, text = { Text("${person.displayName} потеряет доступ к встрече и не сможет войти снова по этой ссылке.") },
        confirmButton = { TextButton({ actions.moderate(person.id, MeetingModeration("kick")); kick = false; close() }) { Text("Удалить") } }, dismissButton = { TextButton({ kick = false }) { Text("Отмена") } })
}

@Composable
internal fun MeetingInvitationDialog(room: MeetingRoomUiState, actions: MeetingRoomActions, close: () -> Unit) {
    var selected by remember { mutableStateOf<List<MeetingInviteUser>>(emptyList()) }
    var emails by remember { mutableStateOf("") }
    val entered = emails.split(Regex("[\\s,;]+")).filter { it.isNotBlank() }.distinct()
    val valid = selected.size + entered.size in 1..20 && entered.all { it.length <= 254 && it.matches(Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) }
    LaunchedEffect(room.notice) { if (room.notice != null) { selected = emptyList(); emails = "" } }
    AlertDialog(onDismissRequest = { if (!room.busy) close() }, title = { Text("Пригласить участников") },
        text = { LazyColumn(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { OutlinedTextField(room.query, actions.searchUsers, label = { Text("Имя или email") }, singleLine = true, enabled = !room.busy, modifier = Modifier.fillMaxWidth().testTag("invitation-search")) }
            items(room.users, key = { it.id }) { user ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(selected.any { it.id == user.id }, { checked -> if (checked && selected.size < 20) selected = selected + user else if (!checked) selected = selected.filterNot { it.id == user.id } }, enabled = !room.busy)
                    Column { Text(user.displayName, style = MaterialTheme.typography.bodySmall); Text(user.email, style = MaterialTheme.typography.labelSmall) }
                }
            }
            item { FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { selected.forEach { user -> InputChip(true, { selected = selected - user }, label = { Text(user.displayName) }, trailingIcon = { Icon(Icons.Outlined.Close, null, Modifier.size(14.dp)) }) } } }
            item { OutlinedTextField(emails, { emails = it.take(5100) }, label = { Text("Email приглашённых") }, supportingText = { Text("До 20 получателей, через запятую") }, enabled = !room.busy, modifier = Modifier.fillMaxWidth().testTag("invitation-emails")) }
            room.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            room.notice?.let { item { Text(it, color = MaterialTheme.colorScheme.primary) } }
        } }, confirmButton = { Button({ actions.invite(selected.map { it.id }, entered.filterNot { email -> selected.any { it.email.equals(email, true) } }) }, enabled = !room.busy && valid, modifier = Modifier.testTag("invitation-send")) { Text("Пригласить") } }, dismissButton = { TextButton(close, enabled = !room.busy) { Text("Закрыть") } })
}
