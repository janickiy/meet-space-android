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
                    Row(Modifier.fillMaxWidth().heightIn(min = if (tablet) 84.dp else 77.dp).padding(start = if (tablet) 30.dp else 20.dp, end = if (tablet) 30.dp else 20.dp, bottom = if (tablet) 15.dp else 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (tablet) Brand(compact = true)
                        Text(if (state.detailId != null) "Просмотр записи" else "Записи", style = MaterialTheme.typography.headlineSmall.copy(fontSize = if (tablet) 25.sp else 23.sp, letterSpacing = (-.5).sp), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).semantics { heading() })
                        IconButton(onClick = actions.close, modifier = Modifier.size(48.dp).testTag("recordings-back")) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Назад к встречам", Modifier.size(27.dp))
                        }
                    }
                    Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = if (tablet) 24.dp else 0.dp).clip(RoundedCornerShape(topStart = 25.dp, topEnd = 25.dp)).background(MaterialTheme.colorScheme.surface).padding(horizontal = if (tablet) 28.dp else 18.dp).testTag("recordings-single-pane"), contentAlignment = Alignment.TopCenter) {
                        when {
                            state.detailId != null -> RecordingDetails(state, actions, Modifier.widthIn(max = 840.dp).fillMaxWidth(), compact = compactDetail, playingFileId, inlinePlayer)
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
private fun RecordingLibraryContent(state: RecordingsUiState, actions: RecordingsActions, modifier: Modifier, tablet: Boolean) {
    var query by remember { mutableStateOf("") }
    val library = state.library
    val ready = library.meetings.flatMap { row -> row.recordings.data?.items.orEmpty().filter { it.playable }.map { row.meeting to it } }
        .sortedByDescending { (_, recording) -> recording.startedAt ?: recording.createdAt }
    val matching = ready.filter { (meeting, recording) ->
        listOf(meeting.title, recordingLabel(recording), recordingTime(recording.startedAt ?: recording.createdAt))
            .any { it.contains(query.trim(), ignoreCase = true) }
    }
    BoxWithConstraints(modifier.fillMaxHeight()) {
        val columns = if (tablet && maxWidth / LocalDensity.current.fontScale >= 560.dp) 2 else 1
        LazyColumn(Modifier.fillMaxSize().testTag("recordings-library"), verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(top = 18.dp, bottom = 24.dp)) {
            if (state.error != null || state.notice != null || state.download != null) item { RecordingMessages(state, actions) }
            item { Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Записи", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
                    Text("К важному разговору можно вернуться.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
                IconButton(actions.refreshLibrary, enabled = !library.loading, modifier = Modifier.size(48.dp).testTag("recordings-library-refresh")) { Icon(Icons.Outlined.Refresh, "Обновить библиотеку записей") }
            } }
            item { BasicTextField(query, { query = it.take(200) }, singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(13.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp).testTag("recordings-library-search").semantics { contentDescription = "Поиск среди загруженных записей" },
                decorationBox = { input -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Icon(Icons.Outlined.Search, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) Text("Найти запись встречи", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        input()
                    }
                } }) }
            item { FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                FilterChip(selected = true, onClick = { query = "" }, label = { Text("Все записи · ${ready.size}") }, modifier = Modifier.heightIn(min = 48.dp))
                FilterChip(selected = false, onClick = actions.chooseMeeting, label = { Text("Выбрать встречу") }, modifier = Modifier.heightIn(min = 48.dp).testTag("recordings-choose-meeting"))
            } }
            if (library.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth().testTag("recordings-library-loading")) }
            if (library.error != null) item { RecordingLoad(RecordingsLoad<Unit>(error = library.error), actions.refreshLibrary) }
            items(matching.chunked(columns), key = { row -> row.joinToString { "${it.first.id}/${it.second.id}" } }) { row ->
                Row(Modifier.fillMaxWidth().testTag(if (columns == 2) "recordings-library-pair" else "recordings-library-single"), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    row.forEach { (meeting, recording) ->
                        Card(onClick = { actions.openLibrary(meeting.id, recording.id) }, shape = RoundedCornerShape(21.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.weight(1f).testTag("recording-row-${recording.id}")) {
                            RecordingCover(recording, meeting.title, Modifier.fillMaxWidth().height(if (tablet) 174.dp else 168.dp))
                            Column(Modifier.padding(if (tablet) 17.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                Text(meeting.title, style = MaterialTheme.typography.titleMedium.copy(fontSize = if (tablet) 14.sp else 13.sp, lineHeight = 20.sp), fontWeight = FontWeight.SemiBold)
                                Text("${recordingTime(recording.startedAt ?: recording.createdAt)} · ${if (recording.media?.type == "final_audio") "Аудио" else "Видео"}",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 16.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (columns == 2 && row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            // Partial failures stay visible even when other meetings returned usable recordings.
            items(library.meetings.filter { it.recordings.error != null || it.recordings.loading || it.recordings.data?.nextOffset != null }, key = { "continuation-${it.meeting.id}" }) { row ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().testTag("recordings-library-meeting-${row.meeting.id}")) {
                    Text(row.meeting.title, style = MaterialTheme.typography.titleSmall)
                    if (row.recordings.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    row.recordings.error?.let { error ->
                        Text(error, color = MaterialTheme.colorScheme.error)
                        OutlinedButton({ actions.retryLibraryMeeting(row.meeting.id) }, enabled = !library.loading && !row.recordings.loading,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("recordings-library-retry-${row.meeting.id}")) { Text("Повторить загрузку записей") }
                    }
                    if (row.recordings.data?.nextOffset != null) OutlinedButton({ actions.moreLibraryMeeting(row.meeting.id) }, enabled = !library.loading && !row.recordings.loading,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("recordings-library-more-${row.meeting.id}")) { Text("Ещё записи этой встречи") }
                }
            }
            if (matching.isEmpty() && library.loaded && !library.loading && library.error == null && library.meetings.none { it.recordings.error != null || it.recordings.loading }) item {
                Text(if (query.isNotBlank()) "На загруженных страницах совпадений нет" else if (library.hasMore) "На загруженных страницах готовых записей нет." else "Готовых записей пока нет. Обновите список позже.",
                    modifier = Modifier.testTag("recordings-library-empty"))
            }
            if (library.hasMore) item {
                OutlinedButton(actions.moreLibrary, enabled = !library.loading && library.error == null, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("recordings-library-more")) { Text("Загрузить ещё записи") }
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
                            RecordingCover(recording, state.meeting.data?.title ?: recordingLabel(recording), modifier = Modifier.fillMaxWidth().height(if (compact) 168.dp else 174.dp))
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

/** Decorative metadata artwork. Its title comes from the meeting, never from an invented frame. */
@Composable
private fun RecordingCover(record: Recording, title: String, modifier: Modifier, file: RecordingFile? = record.media, play: (() -> Unit)? = null, enabled: Boolean = true, compact: Boolean = false) {
    val coverColors = listOf(Color(0xFFDFE6F5), Color(0xFFE9E2F3), Color(0xFFDEEDE6), Color(0xFF293548))
    val background = if (play != null) Color(0xFF252F40) else coverColors[(record.id.hashCode() and Int.MAX_VALUE) % coverColors.size]
    BoxWithConstraints(modifier.clip(RoundedCornerShape(0.dp)).background(background), contentAlignment = Alignment.Center) {
        val artworkWidth = maxWidth * .65f
        val artworkHeight = maxHeight * .7f
        val density = LocalDensity.current
        // The same readable title is outside this artwork. Treat its small printed copy as
        // decoration so font scaling never clips the actual card title or playback control.
        CompositionLocalProvider(LocalDensity provides Density(density.density, 1f)) {
            Column(Modifier.size(artworkWidth, artworkHeight).rotate(-3f)
                .shadow(12.dp, RoundedCornerShape(5.dp), ambientColor = Color(0x20293C62), spotColor = Color(0x20293C62))
                .background(Color(0xFFFAF9F5), RoundedCornerShape(5.dp)).padding(horizontal = 20.dp, vertical = 18.dp).clearAndSetSemantics {},
                verticalArrangement = Arrangement.Center) {
                Text("MEETSPACE / RECORDING", color = Color(0xFF343B4C), fontSize = 8.sp, lineHeight = 10.sp, letterSpacing = 1.sp, maxLines = 1)
                Spacer(Modifier.height(8.dp))
                Text(title, color = Color(0xFF343B4C), fontSize = 22.sp, lineHeight = 23.sp, letterSpacing = (-1).sp,
                    fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    repeat(3) { Box(Modifier.width(27.dp).height(5.dp).background(Color(0xFF9EADE0), RoundedCornerShape(5.dp))) }
                }
            }
        }
        if (play == null) Surface(shape = CircleShape, color = Color(0xDEFFFFFF), modifier = Modifier.size(42.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.PlayArrow, null, tint = Color(0xFF3668ED)) }
        }
        else FilledIconButton(onClick = play, enabled = enabled, shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xDEFFFFFF), contentColor = Color(0xFF3668ED)),
            modifier = Modifier.size(if (compact) 48.dp else 62.dp).testTag("recording-play-${file?.id}")) {
            Icon(Icons.Outlined.PlayArrow, "Воспроизвести", Modifier.size(32.dp))
        }
        (file?.durationSec ?: record.durationSec)?.let { duration ->
            Surface(shape = RoundedCornerShape(5.dp), color = Color(0xC9263043), modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)) {
                Text(recordingDuration(duration), color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecordingDetails(state: RecordingsUiState, actions: RecordingsActions, modifier: Modifier, compact: Boolean,
    playingFileId: String? = null, inlinePlayer: (@Composable () -> Unit)? = null) {
    val recording = state.detail.data
    var showSummary by remember(state.detailId) { mutableStateOf(false) }
    LazyColumn(modifier.fillMaxHeight().testTag("recording-detail"), verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(top = 22.dp, bottom = 24.dp)) {
        if (state.error != null || state.notice != null || state.download != null) item { RecordingMessages(state, actions) }
        if (recording == null) item { RecordingRefresh("Просмотр записи", state.detail.loading, actions.refreshDetail) }
        if (state.detail.loading || state.detail.error != null) item { RecordingLoad(state.detail, actions.refreshDetail) }
        item { TextButton(actions.allRecordings, modifier = Modifier.heightIn(min = 48.dp).testTag("recordings-all")) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Все записи")
        } }
        recording?.let { record ->
            item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(state.meeting.data?.title ?: recordingLabel(record), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).semantics { heading() })
                    IconButton(actions.refreshDetail, enabled = !state.detail.loading, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.Refresh, "Обновить: Просмотр записи") }
                }
                Text(recordingTime(record.startedAt ?: record.createdAt), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                if (!record.playable) Text("Запись пока недоступна. Обновите страницу позже.", modifier = Modifier.testTag("recording-unavailable"))
            } }
            items(record.files.filter { record.playable && it.available && it.type in setOf("final_mp4", "final_audio") }, key = { "file-${it.id}" }) { file ->
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (file.id == playingFileId && inlinePlayer != null) inlinePlayer()
                    else RecordingCover(record, state.meeting.data?.title ?: recordingLabel(record), Modifier.fillMaxWidth().aspectRatio(16f / 9f).heightIn(min = 180.dp).clip(RoundedCornerShape(if (compact) 16.dp else 22.dp)), file,
                        play = { actions.play(record, file) }, enabled = !state.mediaBusy, compact = compact)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (file.type == "final_audio") "Аудиозапись" else "Видеозапись", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(listOfNotNull(file.sizeBytes?.let(::recordingSize), if (file.type == "final_mp4") "MP4" else null).joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
                    }
                    (file.durationSec ?: record.durationSec)?.let { duration -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Длительность", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(recordingDuration(duration))
                    } }
                    Button(onClick = { actions.download(record, file) }, enabled = !state.mediaBusy && state.download == null, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("recording-download-${file.id}")) {
                        Icon(Icons.Outlined.Download, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("Скачать")
                    }
                }
            }
            item { Text("Материалы встречи", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() }) }
            stickyHeader(key = "material-tabs") {
                Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !showSummary, onClick = { showSummary = false }, label = { Text("Расшифровка") }, modifier = Modifier.heightIn(min = 48.dp).testTag("recording-tab-transcript"))
                        FilterChip(selected = showSummary, onClick = { showSummary = true }, label = { Text("Итоги") }, modifier = Modifier.heightIn(min = 48.dp).testTag("recording-tab-summary"))
                    }
                }
            }
            if (!showSummary) {
            item { RecordingCard {
                RecordingRefresh("Расшифровка", state.transcript.loading || state.segments.loading, actions.loadTranscript)
                RecordingLoad(state.transcript, actions.loadTranscript)
                if (state.transcript.data == null && !state.transcript.loading && state.transcript.error == null) OutlinedButton(actions.loadTranscript, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("recording-load-transcript")) { Text("Показать расшифровку") }
                state.transcript.data?.let { transcript ->
                    when {
                        !transcript.enabled && transcript.item?.status != "ready" -> Text("Расшифровки отключены на сервере.")
                        transcript.item?.status != "ready" -> Text("Готовой расшифровки пока нет.")
                        else -> {
                            transcript.item?.language?.takeIf { it.isNotBlank() }?.let { Text("Язык: $it", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            RecordingLoad(state.segments, actions.loadTranscript)
                            if (state.segments.data?.items?.isEmpty() == true && !state.segments.loading) Text("Расшифровка не содержит текста.")
                        }
                    }
                }
            } }
            if (state.transcript.data?.item?.status == "ready") {
                items(state.segments.data?.items.orEmpty(), key = { "segment-${it.id}" }) { segment -> RecordingCard {
                    Text(listOfNotNull(recordingDuration(segment.startMs / 1000), segment.speakerLabel?.takeIf(String::isNotBlank)).joinToString(" · "), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    SelectionContainer { Text(segment.text, modifier = Modifier.testTag("recording-segment-${segment.id}")) }
                } }
                if (state.segments.data?.nextOffset != null) item { OutlinedButton(actions.moreSegments, enabled = !state.segments.loading, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("recording-segments-more")) { Text("Продолжить расшифровку") } }
            }
            } else {
            item { RecordingCard {
                RecordingRefresh("Итоги встречи", state.summary.loading, actions.loadSummary)
                RecordingLoad(state.summary, actions.loadSummary)
                if (state.summary.data == null && !state.summary.loading && state.summary.error == null) OutlinedButton(actions.loadSummary, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("recording-load-summary")) { Text("Показать итоги") }
                state.summary.data?.let { summaryState ->
                    val summary = summaryState.item
                    when {
                        !summaryState.enabled && summary?.status != "ready" -> Text("Итоги встреч отключены на сервере.")
                        summary?.status != "ready" -> Text("Готовых итогов пока нет.")
                        else -> {
                            if (summary.text.isNotBlank()) SelectionContainer { Text(summary.text, modifier = Modifier.testTag("recording-summary")) }
                            if (summary.keyPoints.isNotEmpty()) {
                                Text("Главное", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                                summary.keyPoints.forEach { Text("• $it") }
                            }
                            if (summary.actionItems.isNotEmpty()) {
                                Text("Задачи", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                                summary.actionItems.forEach { item ->
                                    Text(item.text)
                                    listOfNotNull(item.assignee?.takeIf(String::isNotBlank), item.dueDate?.takeIf(String::isNotBlank)).takeIf { it.isNotEmpty() }?.let { Text(it.joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                }
                            }
                            if (summary.topics.isNotEmpty()) Text("Темы: ${summary.topics.joinToString(", ")}")
                        }
                    }
                }
            } }
            }
        }
    }
}

@Composable
private fun RecordingMessages(state: RecordingsUiState, actions: RecordingsActions) {
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
private fun RecordingRefresh(title: String, loading: Boolean, refresh: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).semantics { heading() })
        IconButton(refresh, enabled = !loading, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.Refresh, "Обновить: $title") }
    }
}

@Composable
private fun <T> RecordingLoad(load: RecordingsLoad<T>, retry: () -> Unit) {
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
private fun RecordingCard(content: @Composable ColumnScope.() -> Unit) {
    Card(shape = MeetSpaceTokens.CardShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

private fun recordingLabel(recording: Recording) = if (recording.media?.type == "final_audio") "Аудиозапись встречи" else "Запись встречи"
private fun recordingTime(value: Instant): String = DateTimeFormatter.ofPattern("d MMMM yyyy · HH:mm", Locale.forLanguageTag("ru")).withZone(ZoneId.systemDefault()).format(value)
private fun recordingDuration(seconds: Long): String = if (seconds >= 3600) "%d:%02d:%02d".format(Locale.ROOT, seconds / 3600, (seconds / 60) % 60, seconds % 60) else "%d:%02d".format(Locale.ROOT, seconds / 60, seconds % 60)
private fun recordingSize(bytes: Long): String = if (bytes >= 1024L * 1024) String.format(Locale.forLanguageTag("ru"), "%.1f МБ", bytes.toDouble() / (1024 * 1024)) else if (bytes >= 1024) String.format(Locale.forLanguageTag("ru"), "%.1f КБ", bytes.toDouble() / 1024) else "$bytes Б"
