package com.meetspace.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meetspace.core.model.*
import com.meetspace.app.LocalRecordingPreviews
import java.time.Instant

internal val finishedRecordingStates = setOf("ready", "partial_ready", "failed", "cancelled")
internal fun canDeleteRecording(record: Recording, meeting: Meeting?, selfId: String) =
    selfId.isNotBlank() && record.status in finishedRecordingStates && (meeting?.id == record.conferenceId && selfId == meeting.ownerId || selfId == record.requestedBy)
internal fun recordingKind(record: Recording) = when (record.mode) { "audio_only" -> "Аудиозапись"; "individual_tracks" -> "Дорожки участников"; "screen_focus" -> "Запись экрана"; else -> "Видеозапись" }
internal fun recordingStatus(record: Recording) = when (record.status) {
    "starting" -> "Запускается"; "recording", "degraded" -> "В эфире"; "stopping", "processing", "finalizing", "uploading" -> "Обработка";
    "ready" -> "Готова"; "partial_ready" -> "Частично готова"; "failed" -> "Ошибка записи"; "cancelled" -> "Отменена"; else -> "Статус уточняется"
}

@Composable
internal fun <T> RefreshSelect(label: String, value: T, choices: List<Pair<T, String>>, select: (T) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton({ expanded = true }, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface, containerColor = MaterialTheme.colorScheme.surface),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text(choices.find { it.first == value }?.second ?: label, Modifier.weight(1f), fontSize = 13.sp, lineHeight = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Outlined.ExpandMore, label, Modifier.size(18.dp))
        }
        DropdownMenu(expanded, { expanded = false }) { choices.forEach { (id, title) -> DropdownMenuItem(text = { Text(title) }, onClick = { select(id); expanded = false }) } }
    }
}

@Composable
internal fun RecordingLibraryContent(state: RecordingsUiState, actions: RecordingsActions, modifier: Modifier, tablet: Boolean) {
    var query by remember { mutableStateOf("") }
    var live by remember { mutableStateOf(false) }
    var meeting by remember { mutableStateOf("") }
    var period by remember { mutableIntStateOf(0) }
    var oldest by remember { mutableStateOf(false) }
    var grid by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Recording?>(null) }
    val library = state.library
    val all = library.meetings.flatMap { row -> row.recordings.data?.items.orEmpty().map { row.meeting to it } }
    val matching = all.filter { (m, r) ->
        (if (live) r.status !in finishedRecordingStates else r.status in finishedRecordingStates) &&
            (meeting.isBlank() || meeting == m.id) && (period == 0 || (r.startedAt ?: r.createdAt) >= Instant.now().minusSeconds(period * 86400L)) &&
            (query.isBlank() || "${m.title} ${recordingKind(r)}".contains(query.trim(), true))
    }.sortedWith(compareBy<Pair<Meeting, Recording>> { it.second.startedAt ?: it.second.createdAt }.let { if (oldest) it else it.reversed() })
    BoxWithConstraints(modifier.fillMaxHeight()) {
        val readableWidth = maxWidth / LocalDensity.current.fontScale.coerceAtLeast(1f)
        val roomy = tablet && readableWidth >= 660.dp
        val columns = if (grid && readableWidth >= 900.dp) 3 else if (grid && readableWidth >= 560.dp) 2 else 1
        LazyColumn(Modifier.fillMaxSize().testTag("recordings-library"), contentPadding = PaddingValues(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.error != null || state.notice != null || state.download != null) item { RecordingMessages(state, actions) }
            item { Text("БИБЛИОТЕКА ВСТРЕЧ", color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, letterSpacing = 1.5.sp)
                Spacer(Modifier.height(10.dp)); Text("Важное остаётся с вами", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp)); Text("Видео, аудио и материалы ваших встреч.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) }
            item { Row(horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(false to "Завершённые", true to "В эфире").forEach { (value, title) ->
                    Column(Modifier.selectable(live == value, role = Role.Tab) { live = value; meeting = "" }.testTag(if (value) "recordings-live" else "recordings-finished")) {
                        Box(Modifier.heightIn(min = 48.dp).padding(horizontal = 2.dp, vertical = 12.dp), contentAlignment = Alignment.Center) { Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (live == value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                        HorizontalDivider(Modifier.width(if (value) 80.dp else 118.dp), thickness = 2.dp, color = if (live == value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
                    }
                }
                Spacer(Modifier.weight(1f)); IconButton(actions.refreshLibrary, enabled = !library.loading, modifier = Modifier.testTag("recordings-library-refresh")) { Icon(Icons.Outlined.Refresh, "Обновить библиотеку записей") }
            } }
            item {
                val search: @Composable (Modifier) -> Unit = { mod -> WorkspaceSearchField(query, { query = it.take(200) }, "Найти запись встречи", "recordings-library-search", mod) }
                val choice: @Composable (Modifier) -> Unit = { mod -> RefreshSelect("Встреча", meeting, listOf("" to "Все встречи") + library.meetings.map { it.meeting.id to it.meeting.title }, { meeting = it }, mod) }
                if (roomy) Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) { search(Modifier.weight(1f)); choice(Modifier.weight(.65f)) }
                else Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { search(Modifier.fillMaxWidth()); choice(Modifier.fillMaxWidth()) }
            }
            item { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val periodPicker: @Composable (Modifier) -> Unit = { mod ->
                    RefreshSelect("Период записей", period, listOf(0 to "Всё время", 7 to "За 7 дней", 30 to "За 30 дней", 90 to "За 90 дней"), { period = it }, mod)
                }
                val sortPicker: @Composable (Modifier) -> Unit = { mod ->
                    RefreshSelect("Сортировка записей", oldest, listOf(false to "Сначала новые", true to "Сначала старые"), { oldest = it }, mod)
                }
                val views: @Composable () -> Unit = {
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        Row(Modifier.padding(2.dp)) {
                            IconToggleButton(!grid, { grid = false }, Modifier.size(48.dp).testTag("recordings-view-list"),
                                colors = IconButtonDefaults.iconToggleButtonColors(checkedContainerColor = MaterialTheme.colorScheme.primaryContainer)) { Icon(Icons.Outlined.ViewList, "Список") }
                            IconToggleButton(grid, { grid = true }, Modifier.size(48.dp).testTag("recordings-view-grid"),
                                colors = IconButtonDefaults.iconToggleButtonColors(checkedContainerColor = MaterialTheme.colorScheme.primaryContainer)) { Icon(Icons.Outlined.GridView, "Плитка") }
                        }
                    }
                }
                if (roomy) Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    periodPicker(Modifier.weight(1f)); sortPicker(Modifier.weight(1.2f)); views()
                } else {
                    if (readableWidth >= 360.dp) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        periodPicker(Modifier.weight(1f)); sortPicker(Modifier.weight(1.2f))
                    } else {
                        periodPicker(Modifier.fillMaxWidth()); sortPicker(Modifier.fillMaxWidth())
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { views() }
                }
            } }
            item { Text("Найдено: ${matching.size} · в загруженных записях", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (library.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (library.error != null) item { RecordingLoad(RecordingsLoad<Unit>(error = library.error), actions.refreshLibrary) }
            items(matching.chunked(columns), key = { row -> row.joinToString { it.second.id } }) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    row.forEach { (m, r) -> RecordingLibraryCard(m, r, grid, roomy && !grid, Modifier.weight(1f), { actions.openLibrary(m.id, r.id) },
                        canDeleteRecording(r, m, state.selfId), { deleting = r }) }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            if (matching.isEmpty() && library.loaded && !library.loading && library.error == null && library.meetings.none { it.recordings.error != null || it.recordings.loading }) item {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.PlayCircleOutline, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(if (query.isNotBlank()) "На загруженных страницах совпадений нет" else "Записи не найдены", style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("recordings-library-empty"))
                    Text("Выберите другую встречу или измените условия поиска.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton({ query = ""; meeting = ""; period = 0; live = false }) { Text("Сбросить фильтры") }
                }
            }
            items(library.meetings.filter { it.recordings.error != null || it.recordings.data?.nextOffset != null || it.recordings.loading }, key = { "continuation-${it.meeting.id}" }) { row ->
                Column { Text(row.meeting.title, style = MaterialTheme.typography.titleSmall)
                    if (row.recordings.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    row.recordings.error?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton({ actions.retryLibraryMeeting(row.meeting.id) }, modifier = Modifier.testTag("recordings-library-retry-${row.meeting.id}")) { Text("Повторить") } }
                    if (row.recordings.data?.nextOffset != null) TextButton({ actions.moreLibraryMeeting(row.meeting.id) }, enabled = !row.recordings.loading, modifier = Modifier.testTag("recordings-library-more-${row.meeting.id}")) { Text("Ещё записи этой встречи") } }
            }
            if (library.hasMore) item { OutlinedButton(actions.moreLibrary, enabled = !library.loading, modifier = Modifier.fillMaxWidth().testTag("recordings-library-more")) { Text("Показать ещё записи") } }
            item { RecordingRetention() }
        }
    }
    deleting?.let { RecordingDeleteDialog(it, state, actions) { deleting = null } }
}

@Composable
private fun RecordingLibraryCard(meeting: Meeting, record: Recording, grid: Boolean, wide: Boolean, modifier: Modifier, open: () -> Unit, deletable: Boolean, delete: () -> Unit) {
    Surface(onClick = open, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = modifier.testTag("recording-row-${record.id}")) {
        BoxWithConstraints {
            val stacked = grid || maxWidth / LocalDensity.current.fontScale.coerceAtLeast(1f) < 300.dp
            val body: @Composable (Modifier) -> Unit = { mod ->
                Column(mod, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(recordingKind(record), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(meeting.title, fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(recordingTime(record.startedAt ?: record.createdAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(listOfNotNull(recordingStatus(record), meeting.participantCount?.let { "Участников: $it" }).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val buttons: @Composable () -> Unit = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(open, modifier = Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) {
                        Icon(if (record.mode == "individual_tracks") Icons.Outlined.FolderOpen else Icons.Outlined.PlayArrow, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (record.mode == "individual_tracks") "Материалы" else if (record.mode == "audio_only") "Слушать" else "Смотреть")
                    }
                    if (deletable) RecordingMenu(delete)
                }
            }
            if (stacked) Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                RecordingModeCover(record, Modifier.fillMaxWidth().height(160.dp))
                body(Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { buttons() }
            } else Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                RecordingModeCover(record, Modifier.width(if (wide) 140.dp else 80.dp).height(if (wide) 100.dp else 88.dp))
                body(Modifier.weight(1f))
                if (wide) buttons() else if (deletable) RecordingMenu(delete)
            }
        }
    }
}

/** Uses the backend's authorised JPEG poster; audio and unavailable posters retain a mode fallback. */
@Composable
internal fun RecordingModeCover(record: Recording, modifier: Modifier) {
    val previews = LocalRecordingPreviews.current
    val preview = record.files.firstOrNull { record.status in setOf("ready", "partial_ready") && it.type == "preview_jpg" && it.available }
    val image = remember(previews, record.conferenceId, record.id, preview) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(previews, record.conferenceId, record.id, preview) {
        image.value = if (previews != null && preview != null) previews.load(record, preview) else null
    }
    val bitmap = image.value
    val icon = when(record.mode) { "audio_only" -> Icons.Outlined.GraphicEq; "individual_tracks" -> Icons.Outlined.PeopleOutline; else -> Icons.Outlined.Videocam }
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap.asImageBitmap(), "Обложка записи", Modifier.fillMaxSize().testTag("recording-cover-${record.id}"), contentScale = ContentScale.Crop)
        else Icon(icon, null, Modifier.size(52.dp).testTag("recording-cover-placeholder-${record.id}"), tint = MaterialTheme.colorScheme.primary)
        Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(6.dp), modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp)) {
            Text(record.durationSec?.let(::recordingDuration) ?: recordingStatus(record), Modifier.padding(6.dp), fontSize = 10.sp)
        }
    }
}

@Composable
private fun RecordingMenu(delete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box { IconButton({ menu = true }) { Icon(Icons.Outlined.MoreHoriz, "Действия с записью") }
        DropdownMenu(menu, { menu = false }) { DropdownMenuItem(text = { Text("Удалить запись", color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) }, onClick = { menu = false; delete() }, modifier = Modifier.testTag("recording-delete")) } }
}
@Composable
private fun RecordingDeleteDialog(record: Recording, state: RecordingsUiState, actions: RecordingsActions, close: () -> Unit) {
    LaunchedEffect(state.deleting, state.notice) { if (!state.deleting && state.notice == "Запись удалена.") close() }
    AlertDialog(onDismissRequest = { if (!state.deleting) close() }, title = { Text("Удалить запись?") },
        text = { Column { Text("Запись и её файлы будут удалены для всех участников. Это действие нельзя отменить."); state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) } } },
        confirmButton = { TextButton({ actions.delete(record) }, enabled = !state.deleting, modifier = Modifier.testTag("recording-delete-confirm")) { Text("Удалить") } }, dismissButton = { TextButton(close, enabled = !state.deleting) { Text("Отмена") } })
}
@Composable
private fun RecordingRetention() { Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Outlined.Info, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Text("Материалы доступны участникам встречи. Срок хранения определяет сервер; скачайте нужные файлы заранее.", style = MaterialTheme.typography.bodySmall)
    }
} }

@Composable
internal fun RecordingDetails(state: RecordingsUiState, actions: RecordingsActions, modifier: Modifier, compact: Boolean, playingFileId: String?, inlinePlayer: (@Composable () -> Unit)?) {
    val record = state.detail.data
    var summary by remember(state.detailId, state.initialSummary) { mutableStateOf(state.initialSummary) }
    var deleting by remember { mutableStateOf(false) }
    BoxWithConstraints(modifier.fillMaxHeight()) {
        val wide = maxWidth / LocalDensity.current.fontScale >= 850.dp
        val materials: @Composable () -> Unit = { RecordingMaterials(state, actions, summary, { summary = it }) }
        val info: @Composable () -> Unit = { if (record != null) RecordingInfo(record, state, actions) }
        LazyColumn(Modifier.fillMaxSize().testTag("recording-detail"), contentPadding = PaddingValues(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.error != null || state.notice != null || state.download != null || state.detail.loading || state.detail.error != null) item { RecordingMessages(state, actions); RecordingLoad(state.detail, actions.refreshDetail) }
            item { TextButton(actions.allRecordings, modifier = Modifier.testTag("recordings-all")) { Text("← Все записи") } }
            if (record != null) {
                item { Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(recordingKind(record), fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                        Text(state.meeting.data?.title ?: "Запись встречи", style = MaterialTheme.typography.headlineSmall)
                        Text(recordingTime(record.startedAt ?: record.createdAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(actions.refreshDetail, enabled = !state.detail.loading) { Icon(Icons.Outlined.Refresh, "Обновить запись") }
                    if (canDeleteRecording(record, state.meeting.data, state.selfId)) RecordingMenu { deleting = true }
                } }
                item { val main: @Composable () -> Unit = { Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (playingFileId != null && inlinePlayer != null) inlinePlayer()
                    else Box(Modifier.fillMaxWidth().aspectRatio(16f/9f), contentAlignment = Alignment.Center) {
                        RecordingModeCover(record, Modifier.fillMaxSize())
                        record.media?.takeIf { record.playable }?.let { file -> FilledIconButton({ actions.play(record, file) }, enabled = !state.mediaBusy, modifier = Modifier.size(56.dp).testTag("recording-play-${file.id}")) { Icon(Icons.Outlined.PlayArrow, "Воспроизвести") } }
                    }
                    if (!record.playable) Text(recordingStatus(record), modifier = Modifier.testTag("recording-unavailable"))
                    RecordingRetention(); materials()
                } }
                    if (wide) Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) { Column(Modifier.weight(1f)) { main() }; Column(Modifier.width(300.dp)) { info() } }
                    else { main() }
                }
                if (!wide) item { info() }
            }
        }
    }
    if (deleting && record != null) RecordingDeleteDialog(record, state, actions) { deleting = false }
}

@Composable
private fun RecordingInfo(record: Recording, state: RecordingsUiState, actions: RecordingsActions) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        RecordingCard {
            Text("О записи", style = MaterialTheme.typography.titleMedium)
            listOf("Дата записи" to recordingTime(record.startedAt ?: record.createdAt), "Длительность" to (record.durationSec?.let(::recordingDuration) ?: "—"),
                "Статус" to recordingStatus(record), "Доступ" to "Участники встречи").forEach { (key, value) ->
                Text(key, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(value, style = MaterialTheme.typography.bodySmall)
            }
        }
        RecordingCard {
            Text("Материалы", style = MaterialTheme.typography.titleMedium)
            record.files.filter { record.status in setOf("ready", "partial_ready") && it.available && it.type != "preview_jpg" }.forEach { file ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(if (file.type == "tracks_archive") Icons.Outlined.FolderZip else Icons.Outlined.Description, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) { Text(file.filename, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall); file.sizeBytes?.let { Text(recordingSize(it), style = MaterialTheme.typography.labelSmall) } }
                    IconButton({ actions.download(record, file) }, enabled = !state.mediaBusy && state.download == null, modifier = Modifier.size(48.dp).testTag("recording-download-${file.id}")) { Icon(Icons.Outlined.Download, "Скачать ${file.filename}") }
                }
            }
            if (record.files.none { record.status in setOf("ready", "partial_ready") && it.available && it.type != "preview_jpg" }) Text("Доступных файлов пока нет.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun RecordingMaterials(state: RecordingsUiState, actions: RecordingsActions, summary: Boolean, choose: (Boolean) -> Unit) {
    RecordingCard {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!summary, { choose(false); if (state.transcript.data == null) actions.loadTranscript() }, label = { Text("Расшифровка") }, modifier = Modifier.testTag("recording-tab-transcript"))
            FilterChip(summary, { choose(true); if (state.summary.data == null) actions.loadSummary() }, label = { Text("Итоги ИИ") }, modifier = Modifier.testTag("recording-tab-summary"))
        }
        if (!summary) {
            RecordingLoad(state.transcript, actions.loadTranscript); RecordingLoad(state.segments, actions.loadTranscript)
            if (state.transcript.data == null && !state.transcript.loading) TextButton(actions.loadTranscript, Modifier.testTag("recording-load-transcript")) { Text("Показать расшифровку") }
            state.transcript.data?.let { value ->
                if (value.item?.status != "ready") Text(if (!value.enabled) "Расшифровки отключены на сервере." else "Готовой расшифровки пока нет.")
                else state.segments.data?.items.orEmpty().forEach { segment ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                        TextButton({ actions.seek(segment.startMs) }, enabled = state.detail.data?.playable == true, modifier = Modifier.testTag("recording-seek-${segment.id}")) { Text(recordingDuration(segment.startMs / 1000), fontSize = 11.sp) }
                        Column(Modifier.weight(1f)) { segment.speakerLabel?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }; SelectionContainer { Text(segment.text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("recording-segment-${segment.id}")) } }
                    }
                }
            }
            if (state.segments.data?.nextOffset != null) TextButton(actions.moreSegments, enabled = !state.segments.loading, modifier = Modifier.testTag("recording-segments-more")) { Text("Продолжить расшифровку") }
        } else {
            RecordingLoad(state.summary, actions.loadSummary)
            if (state.summary.data == null && !state.summary.loading) TextButton(actions.loadSummary, Modifier.testTag("recording-load-summary")) { Text("Показать итоги") }
            state.summary.data?.let { value -> val result = value.item
                if (result?.status != "ready") Text(if (!value.enabled) "Итоги встреч отключены на сервере." else "Готовых итогов пока нет.")
                else {
                    SelectionContainer { Text(result.text, modifier = Modifier.testTag("recording-summary")) }
                    if (result.keyPoints.isNotEmpty()) { Text("Главное", fontWeight = FontWeight.SemiBold); result.keyPoints.forEach { Text("• $it") } }
                    if (result.actionItems.isNotEmpty()) { Text("Задачи", fontWeight = FontWeight.SemiBold); result.actionItems.forEach { Text(listOfNotNull(it.text, it.assignee, it.dueDate).joinToString(" · ")) } }
                    if (result.topics.isNotEmpty()) Text(result.topics.joinToString(" · "))
                }
            }
        }
    }
}
