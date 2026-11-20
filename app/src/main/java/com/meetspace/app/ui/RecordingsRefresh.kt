package com.meetspace.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meetspace.core.model.*
import com.meetspace.app.LocalRecordingPreviews
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal val finishedRecordingStates = setOf("ready", "partial_ready", "failed", "cancelled")
internal fun canDeleteRecording(record: Recording, meeting: Meeting?, selfId: String) =
    selfId.isNotBlank() && record.status in finishedRecordingStates && (meeting?.id == record.conferenceId && selfId == meeting.ownerId || selfId == record.requestedBy)
internal fun recordingKind(record: Recording) = when (record.mode) { "audio_only" -> "Аудиозапись"; "individual_tracks" -> "Дорожки участников"; "screen_focus" -> "Запись экрана"; else -> "Видеозапись" }
private fun recordingModeIcon(record: Recording) = when (record.mode) {
    "audio_only" -> CallIcons.Mic
    "individual_tracks" -> CallIcons.Users
    "screen_focus" -> CallIcons.Monitor
    else -> CallIcons.Video
}
internal fun recordingStatus(record: Recording) = when (record.status) {
    "starting" -> "Запускается"; "recording", "degraded" -> "В эфире"; "stopping", "processing", "finalizing", "uploading" -> "Обработка";
    "ready" -> "Готова"; "partial_ready" -> "Частично готова"; "failed" -> "Ошибка записи"; "cancelled" -> "Отменена"; else -> "Статус уточняется"
}

@Composable
private fun <T> RecordingSelect(label: String, value: T, choices: List<Pair<T, String>>, select: (T) -> Unit, modifier: Modifier = Modifier, fontSize: Int = 12) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton({ expanded = true }, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface, containerColor = MaterialTheme.colorScheme.surface),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(choices.find { it.first == value }?.second ?: label, Modifier.weight(1f), fontSize = fontSize.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(4.dp))
            Icon(CallIcons.ChevronDown, label, Modifier.size(14.dp))
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
        val roomy = tablet && readableWidth >= 650.dp
        val columns = if (grid && readableWidth >= 1000.dp) 3 else if (grid && readableWidth >= 560.dp) 2 else 1
        LazyColumn(Modifier.fillMaxSize().testTag("recordings-library"), contentPadding = PaddingValues(top = if (roomy) 22.dp else 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.error != null || state.notice != null || state.download != null) item { RecordingMessages(state, actions) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("БИБЛИОТЕКА ВСТРЕЧ", color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold)
                    Text("Важное остаётся с вами", Modifier.semantics { heading() }, fontSize = if (roomy) 26.sp else 24.sp,
                        lineHeight = 30.sp, letterSpacing = (-0.8).sp, fontWeight = FontWeight.SemiBold)
                    Text("Видео, аудио и материалы ваших встреч.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            }
            item { Box(Modifier.fillMaxWidth()) {
                HorizontalDivider(Modifier.align(Alignment.BottomCenter), color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf(false to "Завершённые", true to "В эфире").forEach { (value, title) ->
                        val selected = live == value
                        Column(Modifier.widthIn(max = readableWidth / 2).width(IntrinsicSize.Max)
                            .selectable(selected, role = Role.Tab) { live = value; meeting = "" }
                            .testTag(if (value) "recordings-live" else "recordings-finished")) {
                            Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 2.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                if (value) Box(Modifier.size(6.dp).background(Color(0xFF278A78), CircleShape))
                            }
                            HorizontalDivider(thickness = 2.dp, color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(actions.refreshLibrary, enabled = !library.loading, modifier = Modifier.testTag("recordings-library-refresh")) {
                        Icon(CallIcons.RefreshCw, "Обновить библиотеку записей", Modifier.size(18.dp))
                    }
                }
            } }
            item {
                val search: @Composable (Modifier) -> Unit = { mod -> WorkspaceSearchField(query, { query = it.take(200) }, "Найти запись встречи", "recordings-library-search", mod, minimumHeight = 48.dp) }
                val choice: @Composable (Modifier) -> Unit = { mod ->
                    Column(mod, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Встреча", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        RecordingSelect("Встреча", meeting, listOf("" to "Все встречи") + library.meetings.map { it.meeting.id to it.meeting.title }, { meeting = it })
                    }
                }
                if (roomy) Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Bottom) { search(Modifier.weight(1f)); choice(Modifier.weight(.6f).widthIn(max = 350.dp)) }
                else Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { search(Modifier.fillMaxWidth()); choice(Modifier.fillMaxWidth()) }
            }
            item { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val periodPicker: @Composable (Modifier) -> Unit = { mod ->
                    RecordingSelect("Период записей", period, listOf(0 to "Всё время", 7 to "За 7 дней", 30 to "За 30 дней", 90 to "За 90 дней"), { period = it }, mod, if (roomy) 12 else 11)
                }
                val sortPicker: @Composable (Modifier) -> Unit = { mod ->
                    RecordingSelect("Сортировка записей", oldest, listOf(false to "Сначала новые", true to "Сначала старые"), { oldest = it }, mod, if (roomy) 12 else 11)
                }
                val views: @Composable () -> Unit = {
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        Row(Modifier.padding(2.dp).selectableGroup()) {
                            listOf(false to RecordingIcons.List, true to RecordingIcons.Grid).forEach { (isGrid, icon) ->
                                val selected = isGrid == grid
                                Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp))
                                    .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                                    .selectable(selected, role = Role.Tab) { grid = isGrid }
                                    .testTag(if (isGrid) "recordings-view-grid" else "recordings-view-list"), contentAlignment = Alignment.Center) {
                                    Icon(icon, if (isGrid) "Плитка" else "Список", Modifier.size(18.dp),
                                        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
                if (readableWidth >= 346.dp) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (roomy) {
                        periodPicker(Modifier.width(120.dp)); sortPicker(Modifier.width(146.dp)); Spacer(Modifier.weight(1f))
                    } else {
                        periodPicker(Modifier.weight(1f)); sortPicker(Modifier.weight(1.25f))
                    }
                    views()
                } else {
                    if (readableWidth >= 360.dp) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        periodPicker(Modifier.weight(1f)); sortPicker(Modifier.weight(1.2f))
                    } else {
                        periodPicker(Modifier.fillMaxWidth()); sortPicker(Modifier.fillMaxWidth())
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { views() }
                }
            } }
            item { Text("Найдено: ${matching.size}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (library.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (library.error != null) item { RecordingLoad(RecordingsLoad<Unit>(error = library.error), actions.refreshLibrary) }
            items(matching.chunked(columns), key = { row -> row.joinToString { it.second.id } }) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    row.forEach { (m, r) -> RecordingLibraryCard(m, r, grid, roomy && !grid, Modifier.weight(1f), { actions.openLibrary(m.id, r.id) },
                        selfId = state.selfId, delete = if (canDeleteRecording(r, m, state.selfId)) ({ deleting = r }) else null) }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            if (matching.isEmpty() && library.loaded && !library.loading && library.error == null && library.meetings.none { it.recordings.error != null || it.recordings.loading }) item {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(WorkspaceHeaderIcons.Recordings, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
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
internal fun RecordingLibraryCard(meeting: Meeting?, record: Recording, grid: Boolean, wide: Boolean,
    modifier: Modifier, open: () -> Unit, selfId: String, delete: (() -> Unit)? = null) {
    Surface(onClick = open, shape = RoundedCornerShape(if (wide || grid) 16.dp else 13.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = modifier.testTag("recording-row-${record.id}")) {
        BoxWithConstraints {
            val readableWidth = maxWidth / LocalDensity.current.fontScale.coerceAtLeast(1f)
            val stacked = grid || readableWidth < 270.dp
            val colors = MaterialTheme.colorScheme
            val body: @Composable (Modifier) -> Unit = { mod ->
                Column(mod, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(recordingModeIcon(record), null, Modifier.size(12.dp), tint = colors.primary)
                        Text(recordingKind(record), fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.primary)
                    }
                    Text(meeting?.title ?: "Запись встречи", fontSize = if (grid) 15.sp else if (wide) 14.sp else 12.sp,
                        lineHeight = if (grid || wide) 20.sp else 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(recordingCardDate.format(record.startedAt ?: record.createdAt),
                        meeting?.participantCount?.let(::recordingParticipants)).joinToString(" · "), fontSize = if (wide || grid) 11.sp else 10.sp,
                        lineHeight = 15.sp, color = colors.onSurfaceVariant)
                    if (meeting != null && selfId.isNotBlank() && meeting.ownerId == selfId) {
                        Text("Организатор: вы", fontSize = 10.sp, lineHeight = 14.sp, color = colors.onSurfaceVariant)
                    }
                    if (record.status !in finishedRecordingStates || !record.playable) {
                        Text(recordingStatus(record), fontSize = 10.sp, lineHeight = 14.sp, color = colors.onSurfaceVariant)
                    }
                }
            }
            val action: @Composable (Boolean) -> Unit = { labelled ->
                val label = when (record.mode) {
                    "individual_tracks" -> "Материалы"
                    "audio_only" -> "Слушать"
                    else -> "Смотреть"
                }
                val icon = if (record.mode == "individual_tracks") RecordingIcons.FolderOpen else CallIcons.Play
                OutlinedButton(open, modifier = Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(9.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.onSurface),
                    border = BorderStroke(1.dp, colors.outlineVariant), contentPadding = PaddingValues(horizontal = 11.dp)) {
                    Icon(icon, if (labelled) null else label, Modifier.size(15.dp))
                    if (labelled) {
                        Spacer(Modifier.width(6.dp))
                        Text(label, fontSize = 11.sp)
                    }
                }
            }
            if (stacked) Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RecordingModeCover(record, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                body(Modifier.fillMaxWidth().padding(horizontal = 3.dp, vertical = 3.dp))
                HorizontalDivider(color = colors.outlineVariant)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (record.playable) action(true)
                    Spacer(Modifier.weight(1f))
                    RecordingMenu(open, delete)
                }
            } else Row(Modifier.fillMaxWidth().padding(if (wide) 12.dp else 10.dp),
                horizontalArrangement = Arrangement.spacedBy(if (wide) 12.dp else 8.dp), verticalAlignment = if (wide) Alignment.CenterVertically else Alignment.Top) {
                val coverWidth = when {
                    !wide -> 84.dp
                    readableWidth < 965.dp -> 128.dp
                    else -> 150.dp
                }
                RecordingModeCover(record, Modifier.width(coverWidth).aspectRatio(if (wide) 1.6f else 1f))
                body(Modifier.weight(1f))
                if (wide && record.playable) action(readableWidth >= 550.dp)
                RecordingMenu(open, delete)
            }
        }
    }
}

private val recordingCardDate = DateTimeFormatter.ofPattern("d MMMM · HH:mm", Locale.forLanguageTag("ru"))
    .withZone(ZoneId.systemDefault())

private fun recordingParticipants(count: Int): String {
    val noun = when {
        count % 100 in 11..14 -> "участников"
        count % 10 == 1 -> "участник"
        count % 10 in 2..4 -> "участника"
        else -> "участников"
    }
    return "$count $noun"
}

private val posterPanels = listOf(Color(0xFF39435E), Color(0xFF423953), Color(0xFF254C4E), Color(0xFF4C4043))
private val posterAvatars = listOf(Color(0xFF596B98), Color(0xFF79638C), Color(0xFF3D807F), Color(0xFF866B6C))

/** Uses the server's authorised preview when available and a neutral illustration for each format. */
@Composable
internal fun RecordingModeCover(record: Recording, modifier: Modifier, player: Boolean = false) {
    val previews = LocalRecordingPreviews.current
    val preview = record.files.firstOrNull { record.status in setOf("ready", "partial_ready") && it.type == "preview_jpg" && it.available }
    val image = remember(previews, record.conferenceId, record.id, preview) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(previews, record.conferenceId, record.id, preview) {
        image.value = if (previews != null && preview != null) previews.load(record, preview) else null
    }
    val poster = image.value
    val background = when (record.mode) {
        "audio_only" -> Color(0xFF254952)
        "individual_tracks" -> Color(0xFF28243C)
        else -> Color(0xFF242B42)
    }
    Box(modifier.clip(RoundedCornerShape(if (player) 0.dp else 10.dp)).background(background), contentAlignment = Alignment.Center) {
        if (poster != null) Image(poster.asImageBitmap(), "Обложка записи", Modifier.fillMaxSize().testTag("recording-cover-${record.id}"),
            contentScale = if (player) ContentScale.Fit else ContentScale.Crop)
        else RecordingPosterIllustration(record.mode, Modifier.fillMaxSize().testTag("recording-cover-placeholder-${record.id}"))
        if (!player) {
            Box(Modifier.fillMaxSize().background(Color(0x24141A29)))
            Surface(shape = CircleShape, color = Color(0xDB1E263D), border = BorderStroke(1.dp, Color(0x55FFFFFF))) {
                Box(Modifier.size(if (record.mode == "audio_only") 32.dp else 34.dp), contentAlignment = Alignment.Center) {
                    Icon(recordingModeIcon(record), null, Modifier.size(17.dp), tint = Color.White)
                }
            }
            val live = record.status in setOf("recording", "degraded")
            Surface(color = if (live) Color(0xFFA5344A) else Color(0xE0151B2C), shape = RoundedCornerShape(4.dp),
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)) {
                Text(record.durationSec?.let(::recordingDuration) ?: recordingStatus(record), Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    fontSize = 9.sp, lineHeight = 12.sp, color = Color.White)
            }
        }
    }
}

@Composable
private fun RecordingPosterIllustration(mode: String, modifier: Modifier) {
    Canvas(modifier) {
        val width = size.width
        val height = size.height
        if (mode == "audio_only" || mode == "individual_tracks") {
            val rows = if (mode == "audio_only") 1 else 3
            val waveColor = if (rows == 1) Color(0xFF79D4C6) else Color(0xFFA8A1CC)
            repeat(rows) { row ->
                val middle = height * (row + 1) / (rows + 1)
                if (rows > 1) drawRoundRect(Color(0x12FFFFFF), Offset(width * .07f, middle - height * .10f),
                    Size(width * .86f, height * .20f), CornerRadius(3.dp.toPx()))
                repeat(21) { bar ->
                    val length = (7 + (bar * 17 + row * 3) % 19) / 25f * height * if (rows == 1) .55f else .16f
                    val barWidth = width / 54f
                    drawRoundRect(waveColor.copy(alpha = .8f), Offset(width * .10f + bar * width * .038f, middle - length / 2),
                        Size(barWidth, length), CornerRadius(barWidth / 2))
                }
            }
        } else {
            val gap = height * .03f
            val inset = height * .08f
            val tile = Size((width - 2 * inset - gap) / 2, (height - 2 * inset - gap) / 2)
            repeat(4) { index ->
                val start = Offset(inset + index % 2 * (tile.width + gap), inset + index / 2 * (tile.height + gap))
                drawRoundRect(posterPanels[index], start, tile, CornerRadius(4.dp.toPx()))
                drawCircle(posterAvatars[index], minOf(tile.height, tile.width) * .22f, start + Offset(tile.width / 2, tile.height / 2))
            }
        }
    }
}

@Composable
private fun RecordingMenu(open: (() -> Unit)? = null, delete: (() -> Unit)? = null) {
    var menu by remember { mutableStateOf(false) }
    Box {
        IconButton({ menu = true }) { Icon(CallIcons.Ellipsis, "Действия с записью", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        DropdownMenu(menu, { menu = false }) {
            if (open != null) DropdownMenuItem(text = { Text("О записи") }, leadingIcon = { Icon(ChatMessageIcons.Info, null, Modifier.size(18.dp)) },
                onClick = { menu = false; open() })
            if (delete != null) DropdownMenuItem(text = { Text("Удалить запись", color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(ChatMessageIcons.Delete, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error) },
                onClick = { menu = false; delete() }, modifier = Modifier.testTag("recording-delete"))
        }
    }
}
@Composable
private fun RecordingDeleteDialog(record: Recording, state: RecordingsUiState, actions: RecordingsActions, close: () -> Unit) {
    LaunchedEffect(state.deleting, state.notice) { if (!state.deleting && state.notice == "Запись удалена.") close() }
    AlertDialog(onDismissRequest = { if (!state.deleting) close() }, title = { Text("Удалить запись?") },
        text = { Column { Text("Запись и её файлы будут удалены для всех участников. Это действие нельзя отменить."); state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) } } },
        confirmButton = { TextButton({ actions.delete(record) }, enabled = !state.deleting, modifier = Modifier.testTag("recording-delete-confirm")) { Text("Удалить") } }, dismissButton = { TextButton(close, enabled = !state.deleting) { Text("Отмена") } })
}
@Composable
private fun RecordingRetention() {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(ChatMessageIcons.Info, null, Modifier.padding(top = 1.dp).size(16.dp), tint = muted)
        Text("Материалы доступны участникам встречи. Срок хранения определяет сервер.", fontSize = 11.sp, lineHeight = 17.sp, color = muted)
    }
}

@Composable
internal fun RecordingDetails(state: RecordingsUiState, actions: RecordingsActions, modifier: Modifier, compact: Boolean, playingFileId: String?, inlinePlayer: (@Composable () -> Unit)?) {
    val record = state.detail.data
    val meeting = state.meeting.data
    var summary by remember(state.detailId, state.initialSummary, state.capabilities) {
        mutableStateOf(state.capabilities.aiSummary && (state.initialSummary || !state.capabilities.transcription))
    }
    var deleting by remember { mutableStateOf(false) }
    BoxWithConstraints(modifier.fillMaxHeight()) {
        val wide = maxWidth / LocalDensity.current.fontScale >= 690.dp
        LazyColumn(Modifier.fillMaxSize().testTag("recording-detail"),
            contentPadding = PaddingValues(top = if (wide) 22.dp else 12.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.error != null || state.notice != null || state.download != null || state.detail.loading || state.detail.error != null) item {
                RecordingMessages(state, actions)
                RecordingLoad(state.detail, actions.refreshDetail)
            }
            item {
                TextButton(actions.allRecordings, modifier = Modifier.heightIn(min = 48.dp).testTag("recordings-all"),
                    contentPadding = PaddingValues(end = 10.dp)) {
                    Icon(WorkspaceHeaderIcons.Back, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("Все записи", fontSize = 12.sp)
                }
            }
            if (record != null) {
                item {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(recordingKind(record), fontSize = 10.sp, letterSpacing = 1.1.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text(meeting?.title ?: "Запись встречи", Modifier.semantics { heading() },
                            fontSize = if (compact) 23.sp else 25.sp, lineHeight = 30.sp, letterSpacing = (-0.7).sp, fontWeight = FontWeight.SemiBold)
                        Text(listOfNotNull(recordingDate.format(record.startedAt ?: record.createdAt), meeting?.participantCount?.let(::recordingParticipants)).joinToString(" · "),
                            fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item {
                    val playback: @Composable () -> Unit = {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (playingFileId != null && inlinePlayer != null) inlinePlayer()
                            else RecordingPlaybackPreview(record, state, actions, compact)
                            RecordingRetention()
                            RecordingMaterials(state, actions, summary, { summary = it })
                        }
                    }
                    val information: @Composable () -> Unit = {
                        RecordingInfo(record, state, actions, onDelete = if (canDeleteRecording(record, meeting, state.selfId)) ({ deleting = true }) else null)
                    }
                    if (wide) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                        Column(Modifier.weight(1.65f)) { playback() }
                        Column(Modifier.weight(1f)) { information() }
                    } else Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        playback()
                        information()
                    }
                }
            }
        }
    }
    if (deleting && record != null) RecordingDeleteDialog(record, state, actions) { deleting = false }
}

private val recordingDate = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("ru"))
    .withZone(ZoneId.systemDefault())
internal val RecordingStageColor = Color(0xFF202838)

@Composable
private fun RecordingPlaybackPreview(record: Recording, state: RecordingsUiState, actions: RecordingsActions, compact: Boolean) {
    val available = record.media?.takeIf { record.playable }
    val trackArchive = record.files.any { it.type == "tracks_archive" && it.available } && record.status in setOf("ready", "partial_ready")
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(RecordingStageColor).testTag("recording-playback-preview")) {
        Box(Modifier.fillMaxWidth().aspectRatio(if (compact) 16f / 11f else 16f / 10f), contentAlignment = Alignment.Center) {
            RecordingModeCover(record, Modifier.fillMaxSize(), player = true)
            if (available != null) {
                val label = if (available.type == "final_audio") "Слушать" else "Воспроизвести"
                OutlinedIconButton({ actions.play(record, available) }, enabled = !state.mediaBusy,
                    border = BorderStroke(1.dp, Color(0x70FFFFFF)),
                    colors = IconButtonDefaults.outlinedIconButtonColors(containerColor = Color(0xCC18233A), contentColor = Color.White),
                    modifier = Modifier.size(64.dp).testTag("recording-play-${available.id}")) {
                    Icon(CallIcons.Play, label, Modifier.size(26.dp))
                }
            } else {
                Column(Modifier.fillMaxSize().background(Color(0xB31A2035)).padding(16.dp).testTag("recording-unavailable"),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)) {
                    Text(if (trackArchive) "Дорожки участников" else recordingStatus(record), fontWeight = FontWeight.SemiBold,
                        fontSize = 17.sp, color = Color.White)
                    if (trackArchive) Text("Архив доступен в разделе «Материалы».", color = Color(0xFFC1CCE1), fontSize = 12.sp)
                }
            }
        }
        if (available != null) RecordingPlaybackControls(0, available.durationSec?.times(1000) ?: record.durationSec?.times(1000),
            playing = false, enabled = !state.mediaBusy, onToggle = { actions.play(record, available) })
    }
}

@Composable
internal fun RecordingPlaybackControls(positionMs: Long, durationMs: Long?, playing: Boolean, enabled: Boolean,
    onToggle: () -> Unit, onSeek: ((Long) -> Unit)? = null) {
    val tint = Color(0xFFE6EBF6)
    val accent = Color(0xFFA8BCFF)
    Row(Modifier.fillMaxWidth().background(RecordingStageColor).padding(start = 8.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        IconButton(onToggle, enabled = enabled, modifier = Modifier.size(48.dp)) {
            Icon(if (playing) CallIcons.Pause else CallIcons.Play, if (playing) "Приостановить запись" else "Воспроизвести запись",
                Modifier.size(20.dp), tint = if (enabled) tint else tint.copy(alpha = .5f))
        }
        Text(recordingDuration(positionMs / 1000), fontSize = 10.sp, color = tint)
        if (onSeek != null && durationMs != null && durationMs > 0) {
            var preview by remember(durationMs, enabled) { mutableStateOf<Float?>(null) }
            Slider(value = preview ?: (positionMs.toDouble() / durationMs).toFloat().coerceIn(0f, 1f),
                onValueChange = { preview = it },
                onValueChangeFinished = { preview?.let { onSeek((it.toDouble() * durationMs).toLong()) }; preview = null },
                enabled = enabled,
                modifier = Modifier.weight(1f).semantics { contentDescription = "Позиция в записи" },
                colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = Color(0xFF657084)))
        } else {
            Box(Modifier.weight(1f).height(5.dp).background(Color(0xFF657084), CircleShape))
        }
        Text(durationMs?.let { recordingDuration(it / 1000) } ?: "—", fontSize = 10.sp, color = tint)
    }
}

@Composable
private fun RecordingInfo(record: Recording, state: RecordingsUiState, actions: RecordingsActions, onDelete: (() -> Unit)?) {
    val meeting = state.meeting.data
    val colors = MaterialTheme.colorScheme
    val files = record.files.filter { record.status in setOf("ready", "partial_ready") && it.available && it.type != "preview_jpg" }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        RecordingCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("О записи", Modifier.weight(1f).semantics { heading() }, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                IconButton(actions.refreshDetail, enabled = !state.detail.loading, modifier = Modifier.size(48.dp)) {
                    Icon(CallIcons.RefreshCw, "Обновить запись", Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                }
                if (onDelete != null) RecordingMenu(delete = onDelete)
            }
            val details = listOfNotNull(
                "Дата записи" to recordingTime(record.startedAt ?: record.createdAt),
                "Длительность" to (record.durationSec?.let(::recordingDuration) ?: "—"),
                record.media?.sizeBytes?.let { "Размер" to recordingSize(it) },
                meeting?.takeIf { state.selfId.isNotBlank() && it.ownerId == state.selfId }?.let { "Организатор" to "Вы" },
                meeting?.participantCount?.let { "Участники" to it.toString() },
                "Доступ" to "Участники встречи",
            )
            Column {
                details.forEachIndexed { index, (label, value) ->
                    if (index > 0) HorizontalDivider(color = colors.outlineVariant)
                    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(label, Modifier.weight(1f), fontSize = 12.sp, lineHeight = 16.sp, color = colors.onSurfaceVariant)
                        Text(value, Modifier.weight(1.45f), fontSize = 12.sp, lineHeight = 16.sp, textAlign = TextAlign.End)
                    }
                }
            }
        }
        RecordingCard {
            Text("Материалы", Modifier.semantics { heading() }, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (files.isEmpty()) Text("Доступных файлов пока нет.", fontSize = 12.sp, color = colors.onSurfaceVariant)
            files.forEach { file ->
                val (title, icon) = when (file.type) {
                    "tracks_archive" -> "Архив дорожек" to RecordingIcons.FolderOpen
                    "final_mp4" -> "Видеозапись" to CallIcons.Video
                    "final_audio" -> "Аудиозапись" to CallIcons.Mic
                    else -> file.filename to CallIcons.FileText
                }
                HorizontalDivider(color = colors.outlineVariant)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(36.dp, 40.dp).background(colors.primaryContainer, RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
                        Icon(icon, null, Modifier.size(18.dp), tint = colors.primary)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(listOfNotNull(file.filename, file.sizeBytes?.let(::recordingSize)).joinToString(" · "), maxLines = 2,
                            overflow = TextOverflow.Ellipsis, fontSize = 10.sp, lineHeight = 14.sp, color = colors.onSurfaceVariant)
                    }
                    IconButton({ actions.download(record, file) }, enabled = !state.mediaBusy && state.download == null, modifier = Modifier.size(48.dp).testTag("recording-download-${file.id}")) {
                        Icon(ChatMessageIcons.Download, "Скачать ${file.filename}", Modifier.size(19.dp), tint = if (state.mediaBusy || state.download != null) colors.onSurfaceVariant else colors.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordingMaterials(state: RecordingsUiState, actions: RecordingsActions, summary: Boolean, choose: (Boolean) -> Unit) {
    if (!state.capabilities.transcription && !state.capabilities.aiSummary) return
    RecordingCard {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.capabilities.transcription) FilterChip(!summary, { choose(false); if (state.transcript.data == null) actions.loadTranscript() }, label = { Text("Расшифровка") }, modifier = Modifier.testTag("recording-tab-transcript"))
            if (state.capabilities.aiSummary) FilterChip(summary, { choose(true); if (state.summary.data == null) actions.loadSummary() }, label = { Text("Итоги ИИ") }, modifier = Modifier.testTag("recording-tab-summary"))
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
