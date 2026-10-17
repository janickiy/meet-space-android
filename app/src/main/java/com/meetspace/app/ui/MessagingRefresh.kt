package com.meetspace.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meetspace.core.model.*

@Composable
internal fun ChatHistoryDialog(state: MessagingUiState, actions: MessagingActions, pins: Boolean, dismiss: () -> Unit) {
    val load = if (pins) state.pins else state.searchResults
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (pins) "Закреплённые сообщения" else "Поиск по сообщениям") },
        text = { Column(Modifier.heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!pins) OutlinedTextField(state.searchQuery, actions.searchMessages, singleLine = true,
                label = { Text("Найти сообщение") }, modifier = Modifier.fillMaxWidth().testTag("message-search-input"))
            if (load.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            load.error?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(if (pins) actions.loadPins else ({ actions.searchMessages(state.searchQuery) })) { Text("Повторить") } }
            if (!pins && state.searchQuery.trim().length < 2) Text("Введите хотя бы два символа.", style = MaterialTheme.typography.bodySmall)
            if (load.data?.isEmpty() == true && !load.loading) Text(if (pins) "Нет закреплённых сообщений" else "Сообщения не найдены")
            LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(load.data.orEmpty(), key = { it.id }) { message ->
                    Surface(onClick = { actions.focusMessage(message.id); dismiss() }, shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth().testTag("message-result-${message.id}")) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(message.senderName, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                                Text(message.text.ifBlank { "Вложение" }, maxLines = 4, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                            }
                            if (pins) IconButton({ actions.pin(message, false) }, enabled = !state.busy) { Icon(Icons.Outlined.PushPin, "Открепить сообщение") }
                        }
                    }
                }
                if ((if (pins) state.pinsCursor else state.searchCursor) != null) item {
                    TextButton(if (pins) actions.morePins else actions.moreSearch, enabled = !load.loading) { Text("Загрузить ещё") }
                }
            }
        } }, confirmButton = { TextButton(dismiss) { Text("Закрыть") } })
}

@Composable
internal fun ChatPinBar(state: MessagingUiState, actions: MessagingActions, open: () -> Unit) {
    val pin = state.pins.data?.firstOrNull() ?: return
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 12.dp, vertical = 6.dp).testTag("chat-pinned-bar"),
        verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).clickable { actions.focusMessage(pin.id) }.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(11.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.PushPin, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Закреплённое сообщение", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                Text(pin.text.ifBlank { "Вложение" }, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.primary)
            }
        }
        TextButton(open, Modifier.testTag("chat-pins")) { Text("${state.pins.data?.size ?: 0} ›") }
    }
}

/** Uses the same server-backed folder membership as Chats; no second local collection. */
@Composable
internal fun FoldersWorkspace(state: MessagingUiState, actions: MessagingActions, close: () -> Unit) {
    var manage by remember { mutableStateOf(false) }
    var candidates by remember { mutableStateOf(false) }
    val folder = state.folders.data.orEmpty().find { it.id == state.selectedFolderId }
    val back = { if (folder != null) actions.selectFolder(null) else close() }
    BackHandler(onBack = back)
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("folders-workspace")) {
        val wide = maxWidth >= 780.dp
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).heightIn(min = 75.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                if (wide) { Brand(compact = true); Spacer(Modifier.width(14.dp)) }
                Text("Папки", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                IconButton(back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Назад") }
            }
            LazyColumn(Modifier.weight(1f).widthIn(max = 1050.dp).fillMaxWidth().align(Alignment.CenterHorizontally), contentPadding = PaddingValues(horizontal = if (wide) 0.dp else 16.dp, vertical = if (wide) 36.dp else 24.dp), verticalArrangement = Arrangement.spacedBy(if (wide) 24.dp else 20.dp)) {
                item {
                    if (folder != null) TextButton({ actions.selectFolder(null) }) { Text("← Все папки") }
                    val heading: @Composable () -> Unit = {
                    Text(if (folder == null) "ЛИЧНОЕ ПРОСТРАНСТВО" else "ВАША ПАПКА", color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, letterSpacing = 1.5.sp)
                    Spacer(Modifier.height(5.dp))
                    Text(folder?.name ?: "Всё нужное — вместе", fontSize = if (wide) 27.sp else 22.sp, lineHeight = if (wide) 33.sp else 27.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
                    Spacer(Modifier.height(8.dp))
                    Text(folder?.let { "${it.itemCount} элементов · ${it.conversationCount} чатов · ${it.conferenceCount} встреч" } ?: "Соберите встречи и чаты по проектам.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    }
                    val buttons: @Composable () -> Unit = { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button({ if (folder == null) manage = true else { actions.searchFolderCandidates(""); candidates = true } }, shape = RoundedCornerShape(12.dp), modifier = (if (wide) Modifier else Modifier.weight(1f)).heightIn(min = 44.dp).testTag("folder-primary")) {
                            Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(6.dp)); Text(if (folder == null) "Новая папка" else "Добавить")
                        }
                        if (folder != null) IconButton({ manage = true }) { Icon(Icons.Outlined.MoreHoriz, "Действия с папкой") }
                    } }
                    if (wide) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) { Column(Modifier.weight(1f)) { heading() }; buttons() }
                    else Column { heading(); Spacer(Modifier.height(16.dp)); buttons() }
                }
                if (state.busy || state.folders.loading || state.folderEntries.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                (state.error ?: state.folders.error ?: state.folderEntries.error)?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error); TextButton(actions.refresh) { Text("Повторить") } } }
                if (folder == null) {
                    items(state.folders.data.orEmpty().chunked(if (wide) 3 else 1)) { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            row.forEach { value ->
                                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.weight(1f).heightIn(min = if (wide) 172.dp else 118.dp)) {
                                    if (!wide) Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Icon(Icons.Outlined.FolderOpen, null, Modifier.size(44.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)).padding(9.dp), tint = MaterialTheme.colorScheme.primary)
                                        Column(Modifier.weight(1f).clickable { actions.selectFolder(value.id) }.padding(vertical = 16.dp).testTag("folder-card-${value.id}"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text(value.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                            Text("${value.itemCount} элементов · ${value.conversationCount} чатов · ${value.conferenceCount} встреч", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        IconButton({ actions.selectFolder(value.id); manage = true }) { Icon(Icons.Outlined.MoreHoriz, "Действия с папкой ${value.name}") }
                                    } else
                                    Column(Modifier.fillMaxWidth().padding(18.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Outlined.FolderOpen, null, Modifier.size(44.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(13.dp)).padding(10.dp), tint = MaterialTheme.colorScheme.primary)
                                            Spacer(Modifier.weight(1f))
                                            IconButton({ actions.selectFolder(value.id); manage = true }) { Icon(Icons.Outlined.MoreHoriz, "Действия с папкой ${value.name}") }
                                        }
                                        Column(Modifier.fillMaxWidth().clickable { actions.selectFolder(value.id) }.padding(top = 14.dp, bottom = 10.dp).testTag("folder-card-${value.id}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text(value.name, fontWeight = FontWeight.SemiBold)
                                            Text("${value.itemCount} элементов · ${value.conversationCount} чатов · ${value.conferenceCount} встреч", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                            if (wide) repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                    item { Text("Папки видны только вам. Чаты и встречи могут находиться сразу в нескольких папках.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("all" to "Все", "conference" to "Встречи", "conversation" to "Чаты").forEach { (key, label) ->
                            FilterChip(state.folderFilter == key, { actions.folderFilter(key) }, label = { Text(label) }, modifier = Modifier.testTag("folder-filter-$key"))
                        }
                    }
                    OutlinedTextField(state.query, actions.query, singleLine = true, label = { Text("Найти в папке") }, modifier = Modifier.fillMaxWidth()) }
                    items(state.folderEntries.data.orEmpty(), key = { it.type + it.id }) { entry ->
                        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                            Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Row(Modifier.weight(1f).clickable { if (entry.type == "conference") actions.openMeeting(entry.id) else { actions.open(entry.id); close() } }.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(if (entry.type == "conference") Icons.Outlined.Videocam else Icons.Outlined.ChatBubbleOutline, null, tint = MaterialTheme.colorScheme.primary)
                                    Column { Text(entry.title, style = MaterialTheme.typography.titleSmall); Text(if (entry.type == "conference") "Встреча" else "Чат", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                }
                                IconButton({ actions.folderMembership(folder.id, entry.type, entry.id, false) }, enabled = !state.busy) { Icon(Icons.Outlined.FolderOff, "Убрать из папки") }
                            }
                        }
                    }
                    if (state.folderEntries.data?.isEmpty() == true) item { Text("В папке нет подходящих элементов.") }
                    if (state.folderCursor != null) item { TextButton(actions.moreFolderItems, enabled = !state.folderEntries.loading) { Text("Загрузить ещё") } }
                }
            }
        }
    }
    if (manage) FoldersDialog(state, actions) { manage = false }
    if (candidates) FolderCandidatesDialog(state, actions) { candidates = false }
}
