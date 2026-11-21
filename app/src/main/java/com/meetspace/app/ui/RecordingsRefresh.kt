package com.meetspace.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meetspace.core.model.*
import com.meetspace.app.LocalRecordingPreviews
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal val finishedRecordingStates = setOf("ready", "partial_ready", "failed", "cancelled")
private const val RecordingDeletedNotice = "Запись удалена."
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
private fun <T> RecordingSelect(
    label: String, value: T, choices: List<Pair<T, String>>, select: (T) -> Unit, tag: String,
    modifier: Modifier = Modifier, minimumHeight: Dp = 44.dp,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        Surface(shape = shape, color = colors.surface, border = BorderStroke(1.dp, colors.outlineVariant)) {
            Row(Modifier.fillMaxWidth().heightIn(min = minimumHeight).clickable(role = Role.DropdownList) { expanded = true }
                .testTag(tag).padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(choices.firstOrNull { it.first == value }?.second ?: label, Modifier.weight(1f), color = colors.onSurface,
                    fontSize = 12.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Icon(CallIcons.ChevronDown, label, Modifier.size(14.dp))
            }
        }
        DropdownMenu(expanded, { expanded = false }) {
            choices.forEach { (id, title) -> DropdownMenuItem(text = { Text(title) }, onClick = { expanded = false; select(id) }) }
        }
    }
}

@Composable
private fun RecordingLibraryTabs(live: Boolean, roomy: Boolean, choose: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth()) {
        HorizontalDivider(Modifier.align(Alignment.BottomCenter), color = colors.outlineVariant)
        Row(Modifier.fillMaxWidth().selectableGroup().padding(bottom = 1.dp),
            horizontalArrangement = Arrangement.spacedBy(if (roomy) 22.dp else 25.dp)) {
            listOf(false to "Завершённые", true to "В эфире").forEach { (value, title) ->
                val selected = live == value
                Column(Modifier.width(IntrinsicSize.Max).selectable(selected, role = Role.Tab) { choose(value) }
                    .testTag(if (value) "recordings-live" else "recordings-finished")) {
                    Row(Modifier.heightIn(min = 42.dp).padding(start = 2.dp, end = 2.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(title, fontSize = if (roomy) 14.sp else 13.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold,
                            color = if (selected) colors.primary else colors.onSurfaceVariant)
                        if (value) Box(Modifier.size(6.dp).background(colors.tertiary, CircleShape))
                    }
                    HorizontalDivider(thickness = 2.dp, color = if (selected) colors.primary else Color.Transparent)
                }
            }
        }
    }
}

@Composable
private fun RecordingViewSwitch(grid: Boolean, choose: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(10.dp), color = colors.surface, border = BorderStroke(1.dp, colors.outlineVariant)) {
        Row(Modifier.padding(4.dp).selectableGroup()) {
            listOf(false to RecordingIcons.List, true to RecordingIcons.Grid).forEach { (isGrid, icon) ->
                val selected = isGrid == grid
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(7.dp))
                    .background(if (selected) colors.primaryContainer else colors.surface)
                    .selectable(selected, role = Role.Tab) { choose(isGrid) }
                    .testTag(if (isGrid) "recordings-view-grid" else "recordings-view-list"), contentAlignment = Alignment.Center) {
                    Icon(icon, if (isGrid) "Плитка" else "Список", Modifier.size(18.dp),
                        tint = if (selected) colors.primary else colors.onSurfaceVariant)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecordingLibraryContent(
    state: RecordingsUiState, actions: RecordingsActions, modifier: Modifier, tablet: Boolean,
    selfName: String,
) {
    val initialMeetingId = state.meetingId.orEmpty()
    val libraryTag = if (initialMeetingId.isEmpty()) "recordings-library" else "recordings-list"
    var query by remember(initialMeetingId) { mutableStateOf("") }
    var live by rememberSaveable(initialMeetingId) { mutableStateOf(false) }
    var meeting by remember(initialMeetingId) { mutableStateOf(initialMeetingId) }
    var period by rememberSaveable(initialMeetingId) { mutableIntStateOf(0) }
    var oldest by rememberSaveable(initialMeetingId) { mutableStateOf(false) }
    var tabletGrid by rememberSaveable(initialMeetingId) { mutableStateOf(false) }
    val grid = tablet && tabletGrid
    var deleting by remember(initialMeetingId) { mutableStateOf<Recording?>(null) }
    val scoped = initialMeetingId.isNotEmpty()
    val library = state.library
    val currentMeeting = state.meeting.data?.takeIf { it.id == initialMeetingId }
    val all: List<Pair<Meeting?, Recording>> = if (scoped) {
        state.recordings.data?.items.orEmpty().filter { record ->
            record.conferenceId == initialMeetingId && (record.status !in setOf("ready", "partial_ready") ||
                record.playable || record.files.any { it.type == "tracks_archive" && it.available })
        }.map { currentMeeting to it }
    } else library.meetings.flatMap { row -> row.recordings.data?.items.orEmpty().map { row.meeting to it } }
    val inTab = all.filter { (_, record) -> (record.status !in finishedRecordingStates) == live }
    val cutoff = if (period == 0) null else Instant.now().minusSeconds(period * 86400L)
    val text = query.trim()
    val matching = inTab.filter { (info, record) ->
        val date = record.startedAt ?: record.createdAt
        (meeting.isEmpty() || meeting == record.conferenceId) && (cutoff == null || date >= cutoff) &&
            (text.isEmpty() || info?.title?.contains(text, true) == true || recordingKind(record).contains(text, true) ||
                recordingTime(date).contains(text, true))
    }.sortedWith(compareBy<Pair<Meeting?, Recording>> { it.second.startedAt ?: it.second.createdAt }.let { if (oldest) it else it.reversed() })
    val meetingChoices = listOf("" to "Все встречи") + if (scoped) {
        listOf(initialMeetingId to (currentMeeting?.title ?: "Текущая встреча"))
    } else {
        library.meetings.filter { row -> row.meeting.id == meeting ||
            row.recordings.data?.items.orEmpty().any { (it.status !in finishedRecordingStates) == live } }
            .map { it.meeting.id to it.meeting.title }
    }
    val loading = if (scoped) state.recordings.loading else library.loading
    val refresh = if (scoped) actions.refreshRecordings else actions.refreshLibrary
    val showEmpty = matching.isEmpty() && !loading && if (scoped) state.recordings.data != null && state.recordings.error == null
        else library.loaded && library.error == null && library.meetings.none { it.recordings.error != null || it.recordings.loading }
    fun chooseMeeting(id: String) {
        if (scoped && id.isEmpty()) actions.allRecordings() else meeting = id
    }

    BoxWithConstraints(modifier.fillMaxHeight()) {
        val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
        val readableWidth = maxWidth / scale
        val roomy = tablet && readableWidth >= 540.dp
        val meetingFieldWidth = minOf(350.dp, maxWidth * .38f)
        val large = roomy && (maxWidth + 96.dp) / scale > 1000.dp
        val columns = if (!grid) 1 else if (large) 3 else if (roomy) 2 else 1
        val gap = if (grid) 16.dp else 12.dp
        val colors = MaterialTheme.colorScheme
        PullToRefreshBox(isRefreshing = loading, onRefresh = { if (!loading) refresh() }, modifier = Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize().testTag(libraryTag).semantics {
                customActions = listOf(CustomAccessibilityAction("Обновить записи") { if (!loading) refresh(); !loading })
            }, contentPadding = PaddingValues(top = if (roomy) 22.dp else 16.dp, bottom = if (roomy) 30.dp else 24.dp)) {
                if (state.error != null || state.notice != null || state.download != null) item {
                    Box(Modifier.padding(bottom = 12.dp)) { RecordingMessages(state, actions) }
                }
                item {
                    Column(Modifier.padding(bottom = if (roomy) 24.dp else 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.heightIn(min = 21.dp), contentAlignment = Alignment.CenterStart) {
                            Text("БИБЛИОТЕКА ВСТРЕЧ", color = colors.primary, fontSize = if (roomy) 10.sp else 9.sp,
                                lineHeight = 15.sp, letterSpacing = if (roomy) 1.2.sp else 1.08.sp, fontWeight = FontWeight.Bold)
                        }
                        Text("Важное остаётся с вами", Modifier.widthIn(max = if (roomy) Dp.Unspecified else 310.dp)
                            .heightIn(min = if (roomy) 31.dp else 29.dp).semantics { heading() },
                            fontSize = if (roomy) 26.sp else 24.sp, lineHeight = if (roomy) 31.sp else 29.sp,
                            letterSpacing = if (roomy) (-1.17).sp else (-1.08).sp, fontWeight = FontWeight.SemiBold)
                        Text("Видео, аудио и материалы ваших встреч.", Modifier.heightIn(min = 18.dp),
                            color = colors.onSurfaceVariant, fontSize = 12.sp, lineHeight = 18.sp)
                    }
                }
                item { Box(Modifier.padding(bottom = if (roomy) 20.dp else 16.dp)) {
                    RecordingLibraryTabs(live, roomy) { selected -> live = selected; if (!scoped) meeting = "" }
                } }
                item {
                    val search: @Composable (Modifier) -> Unit = { mod ->
                        WorkspaceSearchField(query, { query = it.take(200) }, "Найти запись встречи", "$libraryTag-search",
                            mod, minimumHeight = if (roomy) 48.dp else 46.dp)
                    }
                    val choice: @Composable (Modifier) -> Unit = { mod ->
                        Column(mod, verticalArrangement = Arrangement.spacedBy(if (roomy) 6.dp else 4.dp)) {
                            Text("Встреча", Modifier.heightIn(min = 17.dp), fontSize = 11.sp, lineHeight = 17.sp, color = colors.onSurfaceVariant)
                            RecordingSelect("Встреча", meeting, meetingChoices, ::chooseMeeting, "recordings-meeting-filter",
                                minimumHeight = if (roomy) 48.dp else 44.dp)
                        }
                    }
                    if (roomy) Row(Modifier.fillMaxWidth().padding(bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.Bottom) {
                        search(Modifier.weight(1f)); choice(Modifier.width(meetingFieldWidth))
                    } else Column(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        search(Modifier.fillMaxWidth()); choice(Modifier.fillMaxWidth())
                    }
                }
                item {
                    val filters: @Composable (Modifier) -> Unit = { mod ->
                        Row(mod, horizontalArrangement = Arrangement.spacedBy(if (roomy) 9.dp else 8.dp)) {
                            RecordingSelect("Период записей", period, listOf(0 to "Всё время", 7 to "За 7 дней", 30 to "За 30 дней", 90 to "За 90 дней"),
                                { period = it }, "recordings-period-filter", if (roomy) Modifier.width(125.dp) else Modifier.weight(1f),
                                minimumHeight = if (roomy) 44.dp else 48.dp)
                            RecordingSelect("Сортировка записей", oldest, listOf(false to "Сначала новые", true to "Сначала старые"),
                                { oldest = it }, "recordings-sort-filter", if (roomy) Modifier.width(156.dp) else Modifier.weight(1f),
                                minimumHeight = if (roomy) 44.dp else 48.dp)
                        }
                    }
                    if (!tablet) {
                        filters(Modifier.fillMaxWidth())
                    } else if (roomy || readableWidth >= 285.dp) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = if (roomy) Alignment.CenterVertically else Alignment.Bottom) {
                            filters(Modifier.weight(1f))
                            RecordingViewSwitch(grid) { tabletGrid = it }
                        }
                    } else Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        filters(Modifier.fillMaxWidth())
                        RecordingViewSwitch(grid) { tabletGrid = it }
                    }
                }
                item {
                    Text("Найдено: ${matching.size}", Modifier.padding(start = 2.dp, top = 14.dp, bottom = if (roomy) 10.dp else 9.dp)
                        .heightIn(min = if (roomy) 15.dp else 14.dp), fontSize = if (roomy) 10.sp else 9.sp,
                        lineHeight = if (roomy) 15.sp else 14.sp, color = colors.onSurfaceVariant)
                }
                if (scoped) {
                    if (state.meeting.error != null || state.meeting.loading) item { RecordingLoad(state.meeting, refresh) }
                    if (state.recordings.error != null) item { RecordingLoad(state.recordings, refresh) }
                } else if (library.error != null) item { RecordingLoad(RecordingsLoad<Unit>(error = library.error), refresh) }

                items(matching.chunked(columns), key = { row -> row.joinToString { it.second.id } }) { row ->
                    val rowTag = if (scoped) "recordings-card" else "recordings-library"
                    val equalHeights = columns > 1
                    Row(Modifier.fillMaxWidth().padding(bottom = gap).testTag(if (equalHeights) "$rowTag-pair" else "$rowTag-single")
                        .then(if (equalHeights) Modifier.height(IntrinsicSize.Min) else Modifier),
                        horizontalArrangement = Arrangement.spacedBy(gap)) {
                        row.forEach { (info, record) ->
                            RecordingLibraryCard(info, record, grid, roomy,
                                Modifier.weight(1f).then(if (equalHeights) Modifier.fillMaxHeight() else Modifier),
                                open = { if (scoped) actions.open(record.id) else actions.openLibrary(record.conferenceId, record.id) },
                                selfId = state.selfId, delete = if (canDeleteRecording(record, info, state.selfId)) ({
                                    if (state.notice == RecordingDeletedNotice) actions.clearMessage()
                                    deleting = record
                                }) else null,
                                selfName = selfName, large = large)
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                if (showEmpty) item {
                    Surface(color = colors.surface, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, colors.outlineVariant)) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = if (roomy) 42.dp else 34.dp),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(WorkspaceHeaderIcons.Recordings, null, Modifier.padding(bottom = 7.dp).size(40.dp), tint = colors.primary)
                            Text(if (query.isNotBlank()) "На загруженных страницах совпадений нет" else "Записи не найдены",
                                Modifier.testTag(if (scoped) "recordings-empty" else "recordings-library-empty"),
                                fontSize = 19.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                            Text("Попробуйте другую встречу или измените условия поиска.", fontSize = 12.sp, color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
                            OutlinedButton({ query = ""; period = 0; chooseMeeting("") }, modifier = Modifier.padding(top = 12.dp)) { Text("Сбросить фильтры") }
                        }
                    }
                }
                if (scoped) {
                    if (state.recordings.data?.nextOffset != null) item {
                        OutlinedButton(actions.moreRecordings, enabled = !loading, modifier = Modifier.fillMaxWidth().testTag("recordings-more")) { Text("Проверить следующие записи") }
                    }
                } else {
                    items(library.meetings.filter { it.recordings.error != null || it.recordings.data?.nextOffset != null || it.recordings.loading },
                        key = { "continuation-${it.meeting.id}" }) { row ->
                        Column(Modifier.padding(bottom = 12.dp)) {
                            Text(row.meeting.title, style = MaterialTheme.typography.titleSmall)
                            if (row.recordings.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                            row.recordings.error?.let { Text(it, color = colors.error)
                                TextButton({ actions.retryLibraryMeeting(row.meeting.id) }, modifier = Modifier.testTag("recordings-library-retry-${row.meeting.id}")) { Text("Повторить") } }
                            if (row.recordings.data?.nextOffset != null) TextButton({ actions.moreLibraryMeeting(row.meeting.id) },
                                enabled = !row.recordings.loading, modifier = Modifier.testTag("recordings-library-more-${row.meeting.id}")) { Text("Ещё записи этой встречи") }
                        }
                    }
                    if (library.hasMore) item { OutlinedButton(actions.moreLibrary, enabled = !loading,
                        modifier = Modifier.fillMaxWidth().testTag("recordings-library-more")) { Text("Показать ещё записи") } }
                }
                item { RecordingRetention(compact = !roomy) }
            }
        }
    }
    deleting?.let { RecordingDeleteDialog(it, state, actions) { deleting = null } }
}

@Composable
internal fun RecordingLibraryCard(
    meeting: Meeting?, record: Recording, grid: Boolean, wide: Boolean,
    modifier: Modifier, open: () -> Unit, selfId: String, delete: (() -> Unit)? = null,
    large: Boolean = wide, selfName: String = "",
) {
    val colors = MaterialTheme.colorScheme
    val compact = !wide
    val title = meeting?.title?.takeIf { it.isNotBlank() } ?: "Запись встречи"
    val owner = if (meeting?.ownerId == selfId && selfId.isNotBlank()) {
        if (selfName.isBlank()) "вы" else "$selfName (вы)"
    } else null
    val ready = record.status == "ready" || record.status == "partial_ready"
    val file = if (ready && record.mode == "individual_tracks") {
        record.files.firstOrNull { it.type == "tracks_archive" && it.available } ?: record.media
    } else record.media.takeIf { ready }
    val live = record.status == "recording" || record.status == "degraded"
    val compactRow = compact && !grid

    val body: @Composable (Modifier) -> Unit = { bodyModifier ->
        Column(bodyModifier, verticalArrangement = Arrangement.spacedBy(if (compactRow) 4.dp else 5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (compactRow) 4.dp else 5.dp)) {
                Icon(recordingModeIcon(record), null, Modifier.size(if (compactRow) 11.dp else 13.dp), tint = colors.primary)
                Text(if (live) "${recordingKind(record)} · Идёт запись" else recordingKind(record),
                    fontSize = if (compactRow) 9.sp else 10.sp, lineHeight = if (compactRow) 14.sp else 15.sp,
                    fontWeight = FontWeight.SemiBold, color = colors.primary)
            }
            Text(title, Modifier.heightIn(min = if (grid && wide) 40.dp else 0.dp),
                fontSize = when { compactRow -> 12.sp; compact -> 15.sp; else -> 14.sp },
                lineHeight = if (compactRow) 17.sp else 20.sp, fontWeight = FontWeight.SemiBold)
            Text(listOfNotNull(recordingCardDate.format(record.startedAt ?: record.createdAt),
                meeting?.participantCount?.let(::recordingParticipants)).joinToString(" · "),
                fontSize = if (compactRow) 9.sp else 11.sp, lineHeight = if (compactRow) 14.sp else 16.sp,
                color = colors.onSurfaceVariant)
            if (owner != null) Text("Организатор: $owner", fontSize = if (compactRow) 9.sp else 10.sp,
                lineHeight = if (compactRow) 14.sp else 15.sp, color = colors.onSurfaceVariant)
            if (file == null && !live) Text(recordingStatus(record), fontSize = 10.sp, lineHeight = 15.sp, color = colors.onSurfaceVariant)
        }
    }
    val deleteAction: @Composable () -> Unit = {
        if (delete != null) {
            IconButton(delete, Modifier.size(44.dp).testTag("recording-delete-${record.id}")) {
                Icon(ChatMessageIcons.Delete, "Удалить запись: $title", Modifier.size(18.dp), tint = colors.error)
            }
        } else {
            Spacer(Modifier.size(44.dp))
        }
    }
    val primaryAction: @Composable (Modifier) -> Unit = { actionModifier ->
        if (file != null) {
            val label = when (record.mode) {
                "individual_tracks" -> "Материалы"
                "audio_only" -> "Слушать"
                else -> "Смотреть"
            }
            val labelled = !grid || large
            val shape = RoundedCornerShape(9.dp)
            Row(actionModifier.heightIn(min = 44.dp).clip(shape).border(1.dp, colors.outlineVariant, shape)
                .clickable(role = Role.Button, onClick = open).testTag("recording-open-${record.id}")
                .padding(horizontal = if (labelled) 12.dp else 9.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp, if (grid) Alignment.CenterHorizontally else Alignment.Start),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(if (record.mode == "individual_tracks") RecordingIcons.FolderOpen else CallIcons.Play,
                    if (labelled) null else label, Modifier.size(if (grid) 15.dp else 18.dp))
                if (labelled) Text(label, fontSize = 11.sp, lineHeight = 16.sp)
            }
        } else if (live) Text("В эфире", actionModifier, fontSize = 11.sp, color = colors.error)
        else Spacer(actionModifier)
    }

    Surface(onClick = open, shape = RoundedCornerShape(if (compactRow) 13.dp else 16.dp), color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineVariant), modifier = modifier.testTag("recording-row-${record.id}")) {
        if (grid) {
            Column(Modifier.padding(11.dp)) {
                RecordingModeCover(record, Modifier.fillMaxWidth().aspectRatio(16f / 9f), compact = compact, grid = true)
                body(Modifier.fillMaxWidth().padding(start = 3.dp, end = 3.dp, top = if (compact) 14.dp else 13.dp,
                    bottom = if (compact) 14.dp else 10.dp))
                Spacer(Modifier.weight(1f))
                HorizontalDivider(color = colors.outlineVariant)
                Row(Modifier.fillMaxWidth().padding(top = 7.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Box { primaryAction(Modifier) }
                    deleteAction()
                }
            }
        } else {
            val gap = when { compact -> 8.dp; large -> 18.dp; else -> 12.dp }
            Row(Modifier.fillMaxWidth().padding(if (compact) 11.dp else 13.dp), horizontalArrangement = Arrangement.spacedBy(gap),
                verticalAlignment = Alignment.CenterVertically) {
                val coverWidth = when { compact -> 84.dp; large -> 150.dp; else -> 128.dp }
                RecordingModeCover(record, Modifier.width(coverWidth).aspectRatio(if (compact) 1f else 1.6f), compact = compact)
                body(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (!compact) primaryAction(Modifier.width(112.dp))
                    if (compact && live) {
                        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                            Text("В эфире", Modifier.wrapContentSize(unbounded = true).rotate(90f),
                                fontSize = 8.sp, lineHeight = 10.sp, color = colors.error, maxLines = 1, softWrap = false)
                        }
                    } else deleteAction()
                }
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
internal fun RecordingModeCover(record: Recording, modifier: Modifier, player: Boolean = false, compact: Boolean = false, grid: Boolean = false) {
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
        else RecordingPosterIllustration(record.mode, Modifier.fillMaxSize().testTag("recording-cover-placeholder-${record.id}"),
            compact = compact, grid = grid, player = player)
        if (!player) {
            val live = record.status == "recording" || record.status == "degraded"
            val badgeSize = when { grid && compact -> 44.dp; compact -> 30.dp; else -> 38.dp }
            Box(Modifier.fillMaxSize().background(Color(0x30141A29)))
            Surface(shape = CircleShape, color = Color(0xC91E263D), border = BorderStroke(1.dp, Color(0x36FFFFFF))) {
                Box(Modifier.size(badgeSize), contentAlignment = Alignment.Center) {
                    if (live) Box(Modifier.size(badgeSize / 2).border(1.5.dp, Color.White, CircleShape))
                    else Icon(recordingModeIcon(record), null, Modifier.size(badgeSize / 2), tint = Color.White)
                }
            }
            val duration = record.durationSec ?: record.media?.durationSec
            val badge = if (live || duration == null) recordingStatus(record)
                else recordingDuration(duration).padStart(5, '0')
            val inset = when { grid && compact -> 10.dp; compact -> 4.dp; else -> 7.dp }
            Surface(color = if (live) Color(0xFFA5344A) else Color(0xE0151B2C), shape = RoundedCornerShape(4.dp),
                modifier = Modifier.align(Alignment.BottomEnd).padding(inset)) {
                Text(badge, Modifier.padding(horizontal = if (compact) 4.dp else 5.dp, vertical = if (compact) 1.dp else 2.dp),
                    fontSize = when { grid && compact -> 11.sp; compact -> 8.sp; else -> 10.sp },
                    lineHeight = if (compact && !grid) 10.sp else 13.sp, letterSpacing = .3.sp, color = Color.White)
            }
        }
    }
}

@Composable
private fun RecordingPosterIllustration(mode: String, modifier: Modifier, compact: Boolean, grid: Boolean, player: Boolean) {
    Canvas(modifier) {
        val width = size.width
        val height = size.height
        when (mode) {
            "audio_only", "individual_tracks" -> {
                val tracks = mode == "individual_tracks"
                if (!tracks) drawRect(Brush.linearGradient(listOf(Color(0xFF254952), Color(0xFF19323F))))
                val rows = if (tracks) 3 else 1
                val waveColor = if (tracks) Color(0xBFA8A1CC) else Color(0xBF79D4C6)
                val inset = if (tracks) minOf(10.dp.toPx(), height * .12f) else 0f
                val rowHeight = (height - 2 * inset) / rows
                val naturalStep = (if (tracks) 4.dp else 6.dp).toPx()
                val naturalHeight = (if (tracks) 19.dp else 56.dp).toPx()
                val step = minOf(if (player) naturalStep * 1.7f else naturalStep, (width - 2 * inset) / 25)
                val maxBarHeight = minOf(rowHeight * .8f, if (player) naturalHeight * 2f else naturalHeight)
                val barWidth = step * .5f
                repeat(rows) { row ->
                    val middle = inset + rowHeight * (row + .5f)
                    if (tracks) drawRoundRect(Color(0x08FFFFFF), Offset(inset, middle - rowHeight * .42f),
                        Size(width - 2 * inset, rowHeight * .84f), CornerRadius(4.dp.toPx()))
                    repeat(25) { bar ->
                        val length = (18 + bar * 17 % 38) / 56f * maxBarHeight
                        val x = width / 2 + (bar - 12) * step - barWidth / 2
                        drawRoundRect(waveColor, Offset(x, middle - length / 2), Size(barWidth, length), CornerRadius(barWidth / 2))
                    }
                }
            }
            else -> {
                val gap = when { player -> 10.dp; grid -> 7.dp; compact -> 3.dp; else -> 4.dp }.toPx()
                val insetX = when { player -> 23.dp; grid -> 25.dp; compact -> 7.dp; else -> 12.dp }.toPx()
                val insetY = when { player -> 23.dp; grid -> 16.dp; compact -> 7.dp; else -> 12.dp }.toPx()
                val tile = Size((width - 2 * insetX - gap).coerceAtLeast(0f) / 2, (height - 2 * insetY - gap).coerceAtLeast(0f) / 2)
                val avatar = when { player -> 42.dp; grid -> 30.dp; compact -> 19.dp; else -> 23.dp }.toPx()
                repeat(4) { index ->
                    val start = Offset(insetX + index % 2 * (tile.width + gap), insetY + index / 2 * (tile.height + gap))
                    drawRoundRect(posterPanels[index], start, tile, CornerRadius(5.dp.toPx()))
                    drawCircle(posterAvatars[index], minOf(avatar, tile.height, tile.width) / 2, start + Offset(tile.width / 2, tile.height / 2))
                }
            }
        }
    }
}

@Composable
private fun RecordingMenu(delete: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    Box {
        IconButton({ menu = true }, Modifier.size(44.dp)) {
            Icon(CallIcons.Ellipsis, "Действия с записью", Modifier.size(20.dp), tint = colors.onSurfaceVariant)
        }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text("Удалить запись", color = colors.error) },
                leadingIcon = { Icon(ChatMessageIcons.Delete, null, Modifier.size(18.dp), tint = colors.error) },
                onClick = { menu = false; delete() }, modifier = Modifier.testTag("recording-delete"))
        }
    }
}

@Composable
private fun RecordingDeleteDialog(record: Recording, state: RecordingsUiState, actions: RecordingsActions, close: () -> Unit) {
    var confirmed by remember(record.id) { mutableStateOf(false) }
    LaunchedEffect(state.deleting, state.notice) {
        if (confirmed && !state.deleting && state.notice == RecordingDeletedNotice) close()
    }
    AlertDialog(onDismissRequest = { if (!state.deleting) close() }, title = { Text("Удалить запись?") },
        text = { Column { Text("Запись и её файлы будут удалены для всех участников. Это действие нельзя отменить."); state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) } } },
        confirmButton = {
            TextButton({ confirmed = true; actions.delete(record) }, enabled = !state.deleting,
                modifier = Modifier.testTag("recording-delete-confirm")) { Text("Удалить") }
        }, dismissButton = { TextButton(close, enabled = !state.deleting) { Text("Отмена") } })
}
@Composable
private fun RecordingRetention(compact: Boolean = false) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().padding(vertical = if (compact) 12.dp else 14.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Icon(ChatMessageIcons.Info, null, Modifier.padding(top = 2.dp).size(16.dp), tint = muted)
        Text("Материалы доступны участникам встречи. Срок хранения определяет сервер.",
            fontSize = if (compact) 10.sp else 11.sp, lineHeight = if (compact) 17.sp else 18.sp, color = muted)
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
                        RecordingInfo(record, state, actions, onDelete = if (canDeleteRecording(record, meeting, state.selfId)) ({
                            if (state.notice == RecordingDeletedNotice) actions.clearMessage()
                            deleting = true
                        }) else null)
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
