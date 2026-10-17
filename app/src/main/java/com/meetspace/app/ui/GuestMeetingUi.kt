package com.meetspace.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun GuestJoinDialog(state: FoundationUiState, join: (String, String) -> Unit, close: () -> Unit) {
    var invitation by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = { if (!state.busy) close() }, title = { Text("Присоединиться как гость") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Ссылка на встречу сервера ${state.repository.selectedProfile?.origin.orEmpty()}", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(invitation, { invitation = it.take(2048) }, label = { Text("Ссылка или код приглашения") }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("guest-invitation"))
            OutlinedTextField(name, { name = it.take(160) }, label = { Text("Как вас представить?") }, enabled = !state.busy, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("guest-name"))
            Text("Гостевой сеанс действует только в этой встрече и не сохраняется после закрытия приложения.", style = MaterialTheme.typography.bodySmall)
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { Button({ join(invitation.trim(), name.trim()) }, enabled = !state.busy && invitation.isNotBlank() && name.trim().codePointCount(0, name.trim().length) in 1..80,
            modifier = Modifier.testTag("guest-join")) { Text(if (state.busy) "Подключение…" else "Продолжить") } },
        dismissButton = { TextButton(close, enabled = !state.busy) { Text("Отмена") } })
}

/** This route intentionally has no account workspace navigation. */
@Composable
internal fun GuestMeetingLobby(state: FoundationUiState, join: () -> Unit, refresh: () -> Unit, leave: () -> Unit) {
    val session = state.repository.session ?: return
    val meeting = session.guestMeeting ?: return
    BackHandler(enabled = !state.busy, onBack = leave)
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(24.dp), contentAlignment = Alignment.Center) {
            Surface(shape = RoundedCornerShape(24.dp), modifier = Modifier.widthIn(max = 540.dp).fillMaxWidth().testTag("guest-lobby")) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Brand(compact = true)
                    Text(meeting.title, style = MaterialTheme.typography.headlineSmall)
                    Text("${session.user.displayLabel} · Гость", color = MaterialTheme.colorScheme.primary)
                    Text(if (meeting.status == "active") "Встреча началась. Перед входом можно настроить камеру и микрофон." else if (meeting.isClosed) "Встреча завершена." else "Ожидаем начала встречи организатором.")
                    if (meeting.status == "active") Button(join, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("guest-enter-call")) { Text("Войти во встречу") }
                    if (!meeting.isClosed) OutlinedButton(refresh, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Обновить") }
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    TextButton(leave, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("guest-leave")) { Text("Завершить гостевой сеанс") }
                }
            }
        }
    }
}
