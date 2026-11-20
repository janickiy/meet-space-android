package com.meetspace.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.meetspace.core.model.ChatFolder
import com.meetspace.core.model.Meeting

data class MeetingFolderAssignmentState(
    val meetingId: String? = null,
    val folders: List<ChatFolder>? = null,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val newFolderId: String? = null,
    val saved: Boolean = false,
)

data class MeetingFolderActions(
    val load: (String) -> Unit = {},
    val create: (String) -> Unit = {},
    val add: (Set<String>) -> Unit = {},
    val close: () -> Unit = {},
)

@Composable
internal fun MeetingFolderDialog(
    meeting: Meeting,
    state: MeetingFolderAssignmentState,
    actions: MeetingFolderActions,
    dismiss: () -> Unit,
) {
    val current = state.takeIf { it.meetingId == meeting.id } ?: MeetingFolderAssignmentState(loading = true)
    val folders = current.folders.orEmpty()
    val colors = MaterialTheme.colorScheme
    var selected by remember(meeting.id) { mutableStateOf(emptySet<String>()) }
    var creating by remember(meeting.id) { mutableStateOf(false) }
    var name by remember(meeting.id) { mutableStateOf("") }
    val pending = folders.filter { it.id in selected && !it.contains }.mapTo(linkedSetOf()) { it.id }

    LaunchedEffect(current.newFolderId) {
        current.newFolderId?.let {
            selected = selected + it
            creating = false
            name = ""
        }
    }
    LaunchedEffect(current.saved) { if (current.saved) dismiss() }

    ChatMessageModal(
        title = if (creating) "Новая папка" else "Добавить в папку",
        tag = "meeting-folder-dialog",
        busy = current.busy,
        dismiss = dismiss,
    ) { padding, heading ->
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(padding),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            heading()
            Text(meeting.title, style = MaterialTheme.typography.titleSmall, color = colors.onSurfaceVariant)
            if (current.loading || current.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (creating) {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth().testTag("meeting-folder-name"),
                    enabled = !current.busy, label = { Text("Название папки") }, singleLine = true)
                Button(onClick = { actions.create(name.trim()) },
                    enabled = !current.busy && name.trim().let { it.codePointCount(0, it.length) in 1..50 },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("meeting-folder-create")) {
                    Text("Создать папку")
                }
                TextButton({ creating = false; name = "" }, enabled = !current.busy, modifier = Modifier.fillMaxWidth()) { Text("Назад") }
            } else if (current.folders != null) {
                if (folders.isEmpty()) Text("У вас пока нет папок.", color = colors.onSurfaceVariant)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    folders.forEach { folder ->
                        val checked = folder.contains || folder.id in pending
                        val enabled = !folder.contains && !current.busy && !current.loading
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .background(colors.background, RoundedCornerShape(10.dp))
                                .toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = {
                                    selected = if (it) selected + folder.id else selected - folder.id
                                })
                                .padding(horizontal = 12.dp).testTag("meeting-folder-option-${folder.id}"),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(MeetingIcons.Folder, null, Modifier.size(19.dp), tint = colors.primary)
                            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                                Text(folder.name, style = MaterialTheme.typography.bodyMedium)
                                if (folder.contains) Text("Встреча уже в папке", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                            }
                            Checkbox(checked, onCheckedChange = null, enabled = enabled)
                        }
                    }
                }
                OutlinedButton({ creating = true }, enabled = !current.busy && !current.loading,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("meeting-folder-new")) {
                    Text("Новая папка")
                }
                Button({ actions.add(pending) }, enabled = pending.isNotEmpty() && !current.busy && !current.loading,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("meeting-folder-save")) {
                    Text("Сохранить")
                }
            }
            current.error?.let { message ->
                Text(message, style = MaterialTheme.typography.bodySmall, color = colors.error,
                    modifier = Modifier.testTag("meeting-folder-error").semantics { liveRegion = LiveRegionMode.Polite })
                if (!creating && current.folders == null) TextButton({ actions.load(meeting.id) }) { Text("Повторить") }
            }
        }
    }
}
