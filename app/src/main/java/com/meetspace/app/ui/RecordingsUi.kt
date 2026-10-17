package com.meetspace.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.meetspace.core.designsystem.MeetSpaceTokens
import com.meetspace.core.model.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Bounded workspace; playback and Storage Access Framework effects belong to the host. */
@Composable
fun RecordingsWorkspace(state: RecordingsUiState, actions: RecordingsActions, onWorkspaceTab: ((WorkspaceTab) -> Unit)? = null, profileLabel: String = "",
    playingFileId: String? = null, inlinePlayer: (@Composable () -> Unit)? = null) {
    BackHandler(onBack = actions.back)
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize().testTag("recordings-workspace")) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val tablet = maxWidth >= 780.dp
            val compactDetail = maxWidth < 600.dp
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).fillMaxWidth().windowInsetsPadding(if (onWorkspaceTab == null) WindowInsets.safeDrawing else WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)).imePadding()) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 75.dp).background(MaterialTheme.colorScheme.surface).padding(start = if (tablet) 30.dp else 20.dp, end = if (tablet) 30.dp else 20.dp, bottom = 0.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (tablet) Brand(compact = true)
                        Text(if (state.detailId != null) "Просмотр записи" else "Записи", style = MaterialTheme.typography.headlineSmall.copy(fontSize = if (tablet) 25.sp else 23.sp, letterSpacing = (-.5).sp), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).semantics { heading() })
                        IconButton(onClick = actions.close, modifier = Modifier.size(48.dp).testTag("recordings-back")) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Назад к встречам", Modifier.size(27.dp))
                        }
                    }
                    Box(Modifier.fillMaxWidth().weight(1f).background(MaterialTheme.colorScheme.background).padding(horizontal = if (tablet) 28.dp else 18.dp).testTag("recordings-single-pane"), contentAlignment = Alignment.TopCenter) {
                        when {
                            state.detailId != null -> RecordingDetails(state, actions, Modifier.widthIn(max = 1140.dp).fillMaxWidth(), compact = compactDetail, playingFileId, inlinePlayer)
                            state.meetingId != null -> RecordingList(state, actions, Modifier.widthIn(max = 1080.dp).fillMaxWidth())
                            state.meetingPicker -> Box(Modifier.widthIn(max = 1080.dp).fillMaxWidth()) { RecordingMeetings(state, actions) }
                            else -> RecordingLibraryContent(state, actions, Modifier.widthIn(max = 1080.dp).fillMaxWidth(), tablet)
                        }
                    }
                }
                onWorkspaceTab?.let { WorkspaceNavigation(WorkspaceTab.CALLS, it, profileLabel, tablet) }
            }
        }
    }
}

@Composable
private fun RecordingMeetings(state: RecordingsUiState, actions: RecordingsActions) {
    var query by remember { mutableStateOf("") }
    val meetings = state.meetings.data?.items.orEmpty().filter { it.title.contains(query.trim(), ignoreCase = true) }
    LazyColumn(Modifier.fillMaxSize().testTag("recordings-meetings"), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (state.error != null || state.notice != null || state.download != null) item { RecordingMessages(state, actions) }
        item { Text("К важному разговору можно вернуться.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Text("Выберите встречу", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() }) }
        item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(MeetingsView.PAST to "Прошедшие", MeetingsView.ACTIVE to "Активные").forEach { (view, label) ->
                FilterChip(selected = state.meetingView == view, onClick = { query = ""; actions.filterMeetings(view) }, label = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp).testTag("recordings-view-${view.name.lowercase()}"))
            }
        } }
        item { OutlinedTextField(query, { query = it.take(200) }, label = { Text("Найти среди загруженных встреч") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().testTag("recordings-search")) }
        item { RecordingRefresh("Встречи", state.meetings.loading, actions.refreshMeetings) }
        if (state.meetings.loading || state.meetings.error != null) item { RecordingLoad(state.meetings, actions.refreshMeetings) }
        items(meetings, key = { it.id }) { meeting ->
            Card(onClick = { actions.openMeeting(meeting.id) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = MeetSpaceTokens.CardShape, modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).testTag("recordings-meeting-${meeting.id}")) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(meeting.title, style = MaterialTheme.typography.titleMedium)
                    Text(recordingTime(meeting.timelineAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Открыть записи и материалы", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        if (meetings.isEmpty() && state.meetings.data != null && !state.meetings.loading && state.meetings.error == null) item {
            Text(if (query.isBlank()) "Встреч пока нет" else "На загруженных страницах совпадений нет", modifier = Modifier.testTag("recordings-meetings-empty"))
        }
        if (state.meetings.data?.nextCursor != null) item {
            OutlinedButton(actions.moreMeetings, enabled = !state.meetings.loading, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("recordings-meetings-more")) { Text("Загрузить ещё встречи") }
        }
    }
}

@Composable
private fun RecordingList(state: RecordingsUiState, actions: RecordingsActions, modifier: Modifier) {
    var query by remember(state.meetingId) { mutableStateOf("") }
    val ready = state.recordings.data?.items.orEmpty().filter { it.playable }
    val matching = ready.filter { recording ->
        listOf(state.meeting.data?.title.orEmpty(), recordingLabel(recording), recordingTime(recording.startedAt ?: recording.createdAt))
            .any { it.contains(query.trim(), ignoreCase = true) }
    }
    BoxWithConstraints(modifier.fillMaxHeight()) {
        val columns = if (maxWidth / LocalDensity.current.fontScale >= 560.dp) 2 else 1
        val compact = maxWidth < 560.dp
        LazyColumn(Modifier.fillMaxSize().testTag("recordings-list"), verticalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)) {
            if (state.error != null || state.notice != null || state.download != null) item { RecordingMessages(state, actions) }
            item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.meeting.data?.let { Text(it.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() }) }
                Text("К важному разговору можно вернуться.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            } }
            if (state.meeting.loading || state.meeting.error != null) item { RecordingLoad(state.meeting, actions.refreshRecordings) }
            item { OutlinedTextField(query, { query = it.take(200) }, label = { Text("Найти среди загруженных записей") }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
                singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().testTag("recordings-list-search")) }
            item { RecordingRefresh("Загружено записей: ${ready.size}", state.recordings.loading, actions.refreshRecordings) }
            if (state.recordings.loading || state.recordings.error != null) item { RecordingLoad(state.recordings, actions.refreshRecordings) }
            items(matching.chunked(columns), key = { row -> row.joinToString { it.id } }) { row ->
                Row(Modifier.fillMaxWidth().testTag(if (columns == 2) "recordings-card-pair" else "recordings-card-single"), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    row.forEach { recording ->
                        Card(onClick = { actions.open(recording.id) }, shape = RoundedCornerShape(21.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.weight(1f).testTag("recording-row-${recording.id}")) {
                            RecordingModeCover(recording, modifier = Modifier.fillMaxWidth().height(if (compact) 168.dp else 174.dp))
                            Column(Modifier.padding(if (compact) 16.dp else 17.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                Text(state.meeting.data?.title ?: recordingLabel(recording), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(recordingTime(recording.startedAt ?: recording.createdAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(recordingLabel(recording), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    if (columns == 2 && row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            if (matching.isEmpty() && state.recordings.data != null && !state.recordings.loading && state.recordings.error == null) item {
                Text(if (query.isBlank()) "Готовых записей пока нет. Обновите список позже." else "На загруженных страницах совпадений нет", modifier = Modifier.testTag("recordings-empty"))
            }
            if (state.recordings.data?.nextOffset != null) item { OutlinedButton(actions.moreRecordings, enabled = !state.recordings.loading, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("recordings-more")) { Text("Проверить следующие записи") } }
        }
    }
}

@Composable
internal fun RecordingMessages(state: RecordingsUiState, actions: RecordingsActions) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        state.error?.let { RecordingCard { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }); TextButton(actions.clearMessage) { Text("Закрыть сообщение") } } }
        state.notice?.let { Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("recording-notice")) }
        state.download?.let { download -> RecordingCard {
            Text("Скачивание записи", style = MaterialTheme.typography.titleMedium)
            Text(download.filename, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val total = download.totalBytes
            if (total != null && total > 0) {
                LinearProgressIndicator(progress = { (download.bytes.toDouble() / total).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().testTag("recording-download-progress"))
                Text("${recordingSize(download.bytes)} из ${recordingSize(total)}", style = MaterialTheme.typography.bodySmall)
            } else { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(recordingSize(download.bytes), style = MaterialTheme.typography.bodySmall) }
            TextButton(actions.cancelDownload, modifier = Modifier.heightIn(min = 48.dp).testTag("recording-download-cancel")) { Text("Отменить скачивание") }
        } }
    }
}

@Composable
internal fun RecordingRefresh(title: String, loading: Boolean, refresh: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).semantics { heading() })
        IconButton(refresh, enabled = !loading, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.Refresh, "Обновить: $title") }
    }
}

@Composable
internal fun <T> RecordingLoad(load: RecordingsLoad<T>, retry: () -> Unit) {
    if (load.loading) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text("Загружаем…", modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
    load.error?.let { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        OutlinedButton(retry, enabled = !load.loading, modifier = Modifier.heightIn(min = 48.dp)) { Text("Повторить") }
    } }
}

@Composable
internal fun RecordingCard(content: @Composable ColumnScope.() -> Unit) {
    Card(shape = MeetSpaceTokens.CardShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

private fun recordingLabel(recording: Recording) = if (recording.media?.type == "final_audio") "Аудиозапись встречи" else "Запись встречи"
internal fun recordingTime(value: Instant): String = DateTimeFormatter.ofPattern("d MMMM yyyy · HH:mm", Locale.forLanguageTag("ru")).withZone(ZoneId.systemDefault()).format(value)
internal fun recordingDuration(seconds: Long): String = if (seconds >= 3600) "%d:%02d:%02d".format(Locale.ROOT, seconds / 3600, (seconds / 60) % 60, seconds % 60) else "%d:%02d".format(Locale.ROOT, seconds / 60, seconds % 60)
internal fun recordingSize(bytes: Long): String = if (bytes >= 1024L * 1024) String.format(Locale.forLanguageTag("ru"), "%.1f МБ", bytes.toDouble() / (1024 * 1024)) else if (bytes >= 1024) String.format(Locale.forLanguageTag("ru"), "%.1f КБ", bytes.toDouble() / 1024) else "$bytes Б"
