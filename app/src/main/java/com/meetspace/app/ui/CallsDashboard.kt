package com.meetspace.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.meetspace.app.R
import com.meetspace.core.model.*
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

/** The October 12 reference merges Home and Calls into the authenticated meeting list. */
@Composable
internal fun MeetingsHeader(wide: Boolean, calendar: () -> Unit, recordings: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 75.dp).background(MaterialTheme.colorScheme.surface)
        .padding(start = if (wide) 30.dp else 20.dp, end = if (wide) 24.dp else 14.dp, bottom = 0.dp)
        .testTag("meetings-header"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (wide) Image(painterResource(R.drawable.ic_meetspace), null, Modifier.size(32.dp))
        Text("Встречи", Modifier.weight(1f).semantics { heading() }, fontSize = if (wide) 25.sp else 23.sp, lineHeight = 31.sp, fontWeight = FontWeight.SemiBold)
        IconButton(calendar, Modifier.testTag("calls-calendar")) { Icon(Icons.Outlined.CalendarMonth, "Открыть календарь", Modifier.size(27.dp)) }
        IconButton(recordings, Modifier.testTag("meeting-recordings")) { Icon(Icons.Outlined.PlayCircleOutline, "Записи встреч", Modifier.size(27.dp)) }
    }
}

@Composable
internal fun CallsDashboard(state: MeetingsUiState, repository: RepositoryState, actions: MeetingsActions,
    onCreate: () -> Unit, onSchedule: () -> Unit, onJoin: () -> Unit, onZone: () -> Unit, onFolders: () -> Unit = {}, modifier: Modifier = Modifier) {
    var query by remember { mutableStateOf("") }
    var filters by remember { mutableStateOf(false) }
    val reset = { query = ""; actions.listPeriod(null, null); actions.listFilter(state.listView, MeetingsScope.ALL) }
    BoxWithConstraints(modifier.fillMaxSize().testTag("calls")) {
        val readableWidth = maxWidth / LocalDensity.current.fontScale.coerceAtLeast(1f)
        val wide = readableWidth >= 780.dp
        val expanded = readableWidth >= 1080.dp
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()).padding(horizontal = if (wide) 54.dp else 16.dp, vertical = if (wide) 28.dp else 20.dp)
            .testTag(if (wide) "calls-wide" else "calls-compact"), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 1160.dp).fillMaxWidth()) {
                MeetingQuickActions(wide, expanded, state, onCreate, onSchedule, onJoin)
                if (wide) HorizontalDivider(Modifier.padding(top = 24.dp, bottom = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                else Spacer(Modifier.height(0.dp))
                Row(Modifier.widthIn(max = 650.dp).fillMaxWidth().height(IntrinsicSize.Min).padding(bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(if (wide) 10.dp else 8.dp)) {
                    WorkspaceShortcut("Записи и материалы", Icons.Outlined.PlayCircleOutline, { actions.recordings(null) }, Modifier.weight(1f).testTag("meetings-library-shortcut"))
                    WorkspaceShortcut("Мои папки", Icons.Outlined.FolderOpen, onFolders, Modifier.weight(1f).testTag("meetings-folders-shortcut"))
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Мои встречи", Modifier.weight(1f).semantics { heading() }, fontSize = if (wide) 22.sp else 17.sp, lineHeight = if (wide) 33.sp else 26.sp, fontWeight = FontWeight.SemiBold)
                    Text(meetingZoneLabel(state), Modifier.clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button, onClick = onZone).padding(start = 6.dp, top = 7.dp, bottom = 7.dp).testTag("meeting-zone"),
                        fontSize = if (wide) 11.sp else 10.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (wide && expanded) Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Box(Modifier.weight(1f)) { Box(Modifier.widthIn(max = 430.dp)) { MeetingViewTabs(state, actions, true) } }
                    Box(Modifier.width(320.dp)) { MeetingSearch(query, { query = it }, !state.list.loading, actions.refreshList) }
                } else {
                    Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f)) { MeetingSearch(query, { query = it }, !state.list.loading, actions.refreshList) }
                        if (!wide) OutlinedIconButton(onClick = { filters = true }, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            colors = IconButtonDefaults.outlinedIconButtonColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.size(52.dp).testTag("calls-filters")) { Icon(Icons.Outlined.Tune, "Фильтры встреч", Modifier.size(20.dp)) }
                    }
                    Spacer(Modifier.height(12.dp))
                    MeetingViewTabs(state, actions, false)
                    if (wide) Spacer(Modifier.height(16.dp))
                }
                if (wide) MeetingDesktopFilters(state, actions, reset)
                else FlowRow(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ScopeDropdown(state.listScope, { actions.listFilter(state.listView, it) }, Modifier, compact = true)
                    MeetingFilterButton(if (state.listFrom != null || state.listTo != null) "Период выбран" else "Период",
                        { filters = true }, Modifier.testTag("meeting-period"), compact = true, leadingIcon = Icons.Outlined.CalendarMonth)
                    if (state.listScope != MeetingsScope.ALL || state.listFrom != null || state.listTo != null) IconButton(reset, Modifier.testTag("meeting-reset")) { Icon(Icons.Outlined.Close, "Сбросить фильтры") }
                }
                val rows = state.list.data?.items.orEmpty().filter { it.title.contains(query.trim(), ignoreCase = true) }
                Row(Modifier.fillMaxWidth().padding(top = if (wide) 21.dp else 16.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    val caption = when (state.listView) { MeetingsView.UPCOMING -> "Предстоящие и без даты"; MeetingsView.ACTIVE -> "Встречи в эфире"; MeetingsView.PAST -> "Завершённые и отменённые" }
                    Text(caption + (state.list.data?.let { " · ${rows.size}${if (it.nextCursor != null) "+" else ""}" } ?: ""),
                        Modifier.weight(1f).testTag("calls-loaded-count").semantics { contentDescription = "$caption. Загружено: ${rows.size}." }, fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                MeetingLoadStatus(state.list, actions.refreshList)
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    rows.forEach { MeetingListEntry(it, state, repository.session?.user?.id, actions.open, wide) }
                }
                if (rows.isEmpty() && state.list.data != null && !state.list.loading && state.list.error == null) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 28.dp).testTag("calls-empty"), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Outlined.CalendarMonth, null, Modifier.size(42.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(if (query.isNotBlank() || state.listFrom != null || state.listTo != null) "Встречи не найдены" else when (state.listView) { MeetingsView.ACTIVE -> "Сейчас нет активных встреч"; MeetingsView.PAST -> "Здесь будет история встреч"; MeetingsView.UPCOMING -> "Самое время для первой встречи" }, style = MaterialTheme.typography.titleMedium)
                        Text(if (query.isNotBlank()) "Поиск по загруженным страницам. Можно загрузить следующие." else "Создайте встречу и пригласите коллег по ссылке.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(if (query.isNotBlank() || state.listFrom != null || state.listTo != null) reset else onCreate, enabled = !state.actionBusy && !state.creationUncertain) { Text(if (query.isNotBlank() || state.listFrom != null || state.listTo != null) "Сбросить фильтры" else "Создать встречу") }
                    }
                }
                if (state.list.data?.nextCursor != null) TextButton(actions.moreList, enabled = !state.list.loading, modifier = Modifier.fillMaxWidth().testTag("meeting-more")) { Text("Загрузить ещё") }
                Spacer(Modifier.height(14.dp))
            }
        }
    }
    if (filters) MeetingFilterDialog(state, actions, reset) { filters = false }
}

@Composable
private fun MeetingQuickActions(wide: Boolean, expanded: Boolean, state: MeetingsUiState, create: () -> Unit, schedule: () -> Unit, join: () -> Unit) {
    val enabled = !state.actionBusy && !state.creationUncertain
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!wide && LocalDensity.current.fontScale > 1.3f) {
            // Keep full action names readable when system text is large, without shrinking it.
            MeetingQuick("Новая встреча", "Начать разговор сейчас", Icons.Outlined.Videocam, true, false, enabled, create, Modifier.fillMaxWidth().testTag("meeting-create"))
            MeetingQuick("Запланировать", "Выбрать дату и время", Icons.Outlined.CalendarMonth, false, false, enabled, schedule, Modifier.fillMaxWidth().testTag("meeting-schedule"))
        } else Row(horizontalArrangement = Arrangement.spacedBy(if (wide) 14.dp else 10.dp)) {
            MeetingQuick("Новая встреча", "Начать разговор сейчас", Icons.Outlined.Videocam, true, wide, enabled, create, Modifier.weight(1f).testTag("meeting-create"))
            MeetingQuick(if (expanded) "Запланировать встречу" else "Запланировать", "Выбрать дату и время", Icons.Outlined.CalendarMonth, false, wide, enabled, schedule, Modifier.weight(1f).testTag("meeting-schedule"))
            if (wide) Surface(onClick = join, enabled = !state.actionBusy, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.background, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.weight(.9f).heightIn(min = 110.dp).testTag("meeting-join")) {
                Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.Link, null, Modifier.size(25.dp), tint = MaterialTheme.colorScheme.primary); Text("Подключиться по ссылке", fontSize = 13.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.primary) }
            }
        }
        if (!wide) TextButton(join, enabled = !state.actionBusy, modifier = Modifier.fillMaxWidth().testTag("meeting-join"), contentPadding = PaddingValues(0.dp)) {
            Icon(Icons.Outlined.Link, null, Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)); Text("Подключиться по ссылке", fontSize = 12.sp); Spacer(Modifier.width(8.dp)); Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(14.dp))
        }
    }
}

@Composable
private fun MeetingQuick(title: String, note: String, icon: ImageVector, primary: Boolean, wide: Boolean, enabled: Boolean, click: () -> Unit, modifier: Modifier) {
    Surface(onClick = click, enabled = enabled, color = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        contentColor = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(16.dp), border = if (primary) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = modifier.heightIn(min = if (wide) 110.dp else 98.dp)) {
        val tint = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
        if (wide) Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(25.dp), tint = tint)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { Text(title, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold); Text(note, fontSize = 10.sp, lineHeight = 15.sp, color = if (primary) tint.copy(alpha = .8f) else MaterialTheme.colorScheme.onSurfaceVariant) }
        } else Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Icon(icon, null, Modifier.size(25.dp), tint = tint); Icon(Icons.Outlined.ArrowOutward, null, Modifier.size(16.dp), tint = tint.copy(alpha = .5f)) }
            Text(title, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun MeetingViewTabs(state: MeetingsUiState, actions: MeetingsActions, expanded: Boolean) {
    Row(Modifier.fillMaxWidth().selectableGroup()) {
        MeetingsView.entries.forEach { view ->
            val selected = state.listView == view
            Column(Modifier.weight(1f).selectable(selected, role = Role.Tab) { actions.listFilter(view, state.listScope) }.testTag("meeting-view-${view.name.lowercase()}"), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.heightIn(min = 46.dp).padding(horizontal = 3.dp, vertical = 12.dp), contentAlignment = Alignment.Center) { Text(viewLabel(view), fontSize = if (expanded) 14.sp else 12.sp, lineHeight = 17.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                HorizontalDivider(thickness = if (selected) 2.dp else 1.dp, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun MeetingSearch(query: String, change: (String) -> Unit, refreshEnabled: Boolean, refresh: () -> Unit) {
    WorkspaceSearchField(query, { change(it.take(200)) }, "Найти встречу", "calls-search", clearTag = "meeting-search-clear") {
        IconButton(refresh, enabled = refreshEnabled, modifier = Modifier.size(48.dp).testTag("calls-refresh")) {
            Icon(Icons.Outlined.Refresh, "Обновить встречи", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ScopeDropdown(scope: MeetingsScope, choose: (MeetingsScope) -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    var menu by remember { mutableStateOf(false) }
    Box(modifier) {
        MeetingFilterButton(scopeLabel(scope), { menu = true },
            (if (compact) Modifier.wrapContentWidth() else Modifier.fillMaxWidth()).testTag("meeting-scope-picker"), compact,
            leadingIcon = if (compact) Icons.Outlined.PeopleOutline else null, trailingIcon = Icons.Outlined.ExpandMore)
        DropdownMenu(menu, { menu = false }) { MeetingsScope.entries.forEach { item -> DropdownMenuItem(text = { Text(scopeLabel(item)) }, onClick = { menu = false; choose(item) }, modifier = Modifier.testTag("meeting-scope-${item.name.lowercase()}")) } }
    }
}

/** Matching visible bounds and touch targets for both compact filters, including large text. */
@Composable
private fun MeetingFilterButton(label: String, click: () -> Unit, modifier: Modifier, compact: Boolean,
    leadingIcon: ImageVector? = null, trailingIcon: ImageVector? = null) {
    OutlinedButton(click, modifier.heightIn(min = 52.dp),
        shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) {
        leadingIcon?.let { Icon(it, null, Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)) }
        Text(label, if (compact) Modifier else Modifier.weight(1f), fontSize = 13.sp, lineHeight = 20.sp,
            maxLines = if (compact) 1 else Int.MAX_VALUE, softWrap = !compact)
        trailingIcon?.let { Spacer(Modifier.width(8.dp)); Icon(it, null, Modifier.size(16.dp)) }
    }
}

@Composable
private fun MeetingDesktopFilters(state: MeetingsUiState, actions: MeetingsActions, reset: () -> Unit) {
    var from by remember(state.listFrom) { mutableStateOf(state.listFrom?.toString().orEmpty()) }
    var to by remember(state.listTo) { mutableStateOf(state.listTo?.toString().orEmpty()) }
    val apply = { left: String, right: String -> parsePeriod(left, right)?.let { actions.listPeriod(it.first, it.second) } }
    Surface(shape = RoundedCornerShape(13.dp), color = MaterialTheme.colorScheme.background, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.padding(15.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1.15f), verticalArrangement = Arrangement.spacedBy(7.dp)) { Text("Моё участие", fontSize = 11.sp, lineHeight = 16.sp); ScopeDropdown(state.listScope, { actions.listFilter(state.listView, it) }) }
            PeriodField("С даты", from, { from = it; apply(it, to) }, "meeting-filter-from", Modifier.weight(1f))
            PeriodField("По дату", to, { to = it; apply(from, it) }, "meeting-filter-to", Modifier.weight(1f))
            IconButton({ from = ""; to = ""; reset() }, Modifier.testTag("meeting-reset")) { Icon(Icons.Outlined.RestartAlt, "Сбросить фильтры") }
        }
    }
    if (from.length == 10 && to.length == 10 && parsePeriod(from, to) == null) Text("Проверьте даты: конец периода не может быть раньше начала.", color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun PeriodField(label: String, value: String, change: (String) -> Unit, tag: String, modifier: Modifier = Modifier) {
    var calendar by remember { mutableStateOf(false) }
    val date = runCatching { LocalDate.parse(value) }.getOrNull()
    val displayed = date?.format(periodDisplayFormat) ?: value
    Column(modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(label, fontSize = 11.sp, lineHeight = 16.sp)
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp)).padding(start = 10.dp, end = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(displayed, { input ->
                val trimmed = input.take(10)
                // Presentation is Russian; complete dates still flow through the ISO period parser.
                change(runCatching { LocalDate.parse(trimmed, periodDisplayFormat).toString() }.getOrDefault(trimmed))
            }, singleLine = true, textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface), cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.weight(1f).padding(vertical = 10.dp).testTag(tag).semantics { contentDescription = "$label, ДД.ММ.ГГГГ" },
                decorationBox = { input -> Box { if (displayed.isEmpty()) Text("ДД.ММ.ГГГГ", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); input() } })
            IconButton({ calendar = true }, Modifier.size(48.dp).testTag("$tag-calendar")) { Icon(Icons.Outlined.CalendarToday, "Выбрать дату: $label", Modifier.size(18.dp)) }
        }
    }
    if (calendar) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = date?.takeIf { it.year in 1900..2100 }?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli())
        DatePickerDialog(onDismissRequest = { calendar = false }, confirmButton = {
            TextButton(onClick = { picker.selectedDateMillis?.let { change(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) }; calendar = false },
                enabled = picker.selectedDateMillis != null, modifier = Modifier.testTag("$tag-calendar-confirm")) { Text("Выбрать") }
        }, dismissButton = { TextButton({ calendar = false }) { Text("Отмена") } }) { DatePicker(picker, title = { Text(label, Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) }) }
    }
}

private val periodDisplayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.uuuu", Locale.forLanguageTag("ru")).withResolverStyle(ResolverStyle.STRICT)

@Composable
private fun MeetingFilterDialog(state: MeetingsUiState, actions: MeetingsActions, reset: () -> Unit, dismiss: () -> Unit) {
    var from by remember { mutableStateOf(state.listFrom?.toString().orEmpty()) }
    var to by remember { mutableStateOf(state.listTo?.toString().orEmpty()) }
    var scope by remember { mutableStateOf(state.listScope) }
    val period = parsePeriod(from, to)
    AlertDialog(onDismissRequest = dismiss, containerColor = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(24.dp), tonalElevation = 0.dp,
        modifier = Modifier.padding(horizontal = 20.dp).widthIn(max = 480.dp).fillMaxWidth(), properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text("Фильтры встреч") }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Моё участие", fontSize = 12.sp); ScopeDropdown(scope, { scope = it })
            PeriodField("С даты", from, { from = it }, "meeting-filter-from")
            PeriodField("По дату", to, { to = it }, "meeting-filter-to")
            Text("Дата включается в период. Часовой пояс: ${state.zone.id}. Период применяется к дате завершения, расписания или создания встречи на сервере.", fontSize = 11.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (period == null) Text("Введите даты ДД.ММ.ГГГГ и проверьте порядок периода.", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        } }, confirmButton = { Button({ period?.let { actions.listFilter(state.listView, scope); actions.listPeriod(it.first, it.second); dismiss() } }, enabled = period != null, modifier = Modifier.testTag("meeting-filter-apply")) { Text("Применить") } },
        dismissButton = { TextButton({ reset(); dismiss() }, modifier = Modifier.testTag("meeting-filter-reset")) { Text("Сбросить") } })
}

private fun parsePeriod(from: String, to: String): Pair<LocalDate?, LocalDate?>? = runCatching {
    val left = from.takeIf(String::isNotBlank)?.let(LocalDate::parse)
    val right = to.takeIf(String::isNotBlank)?.let(LocalDate::parse)
    require(left == null || right == null || left <= right)
    left to right
}.getOrNull()

private fun scopeLabel(scope: MeetingsScope): String = when (scope) { MeetingsScope.ALL -> "Все мои встречи"; MeetingsScope.OWNED -> "Я организатор"; MeetingsScope.PARTICIPATING -> "Я участник" }
private fun meetingZoneLabel(state: MeetingsUiState): String {
    val city = if (state.zone.id == "Europe/Moscow") "Москва" else state.zone.id.substringAfterLast('/').replace('_', ' ')
    val seconds = state.zone.rules.getOffset(Instant.now()).totalSeconds
    val offset = if (seconds % 3600 == 0) (if (seconds >= 0) "+" else "") + seconds / 3600 else state.zone.rules.getOffset(Instant.now()).id
    return "$city · UTC$offset"
}

@Composable
private fun MeetingListEntry(meeting: Meeting, state: MeetingsUiState, selfId: String?, open: (String) -> Unit, wide: Boolean) {
    val at = meeting.scheduledAt ?: meeting.startedAt ?: meeting.finishedAt
    val duration = if (meeting.isClosed) {
        if (meeting.startedAt != null && meeting.finishedAt != null) Duration.between(meeting.startedAt, meeting.finishedAt).seconds.takeIf { it >= 0 }?.div(60) else null
    } else meeting.plannedDurationMin?.toLong()
    val owner = selfId != null && meeting.ownerId == selfId
    val showDetails = { open(meeting.id) }
    Surface(onClick = showDetails, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (meeting.status == "active") MaterialTheme.colorScheme.primary.copy(alpha = .4f) else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().testTag("meeting-row-${meeting.id}")) {
        BoxWithConstraints(Modifier.padding(if (wide) 18.dp else 14.dp)) {
            // Give long titles the full card width on narrow screens with enlarged text.
            val stacked = maxWidth / LocalDensity.current.fontScale < 300.dp
            val date: @Composable () -> Unit = { MeetingDateTile(at, state, wide) }
            val options: @Composable () -> Unit = {
                IconButton(showDetails, Modifier.size(48.dp).testTag("meeting-options-${meeting.id}")) {
                    Icon(Icons.Outlined.MoreHoriz, "Подробнее о встрече", Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val title: @Composable () -> Unit = {
                Text(meeting.title, Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Открыть встречу", onClick = showDetails)
                    .testTag("meeting-title-${meeting.id}"), fontSize = if (wide) 16.sp else 15.sp, lineHeight = if (wide) 24.sp else 22.sp, fontWeight = FontWeight.SemiBold)
            }
            val metadata: @Composable () -> Unit = {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(listOfNotNull(duration?.let { "$it мин" }, if (owner) "Вы организатор" else "Вы участник").joinToString(" · "),
                        fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    meeting.participantCount?.let { count ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Outlined.PeopleOutline, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Участников: $count", fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (stacked) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    date()
                    Box(Modifier.weight(1f)) { MeetingStatusBadge(meeting) }
                    options()
                }
                title()
                metadata()
            } else Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                date()
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    MeetingStatusBadge(meeting)
                    title()
                    metadata()
                }
                options()
            }
        }
    }
}

@Composable
private fun MeetingDateTile(at: Instant?, state: MeetingsUiState, wide: Boolean) {
    Column(Modifier.width((if (wide) 88.dp else 76.dp) * LocalDensity.current.fontScale.coerceAtLeast(1f))
        .heightIn(min = 76.dp).background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = .55f), RoundedCornerShape(12.dp))
        .padding(horizontal = 8.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
        if (at != null) {
            Text(at.atZone(state.zone).format(DateTimeFormatter.ofPattern("HH:mm")), fontSize = 20.sp, lineHeight = 26.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false, color = MaterialTheme.colorScheme.primary)
            Text(at.atZone(state.zone).format(DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("ru"))), fontSize = 11.sp, lineHeight = 16.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Icon(Icons.Outlined.Videocam, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
            Text("Без даты", fontSize = 11.sp, lineHeight = 16.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MeetingStatusBadge(meeting: Meeting) {
    val active = meeting.status == "active"
    Surface(shape = RoundedCornerShape(6.dp), color = if (active) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
        Text(if (meeting.status == "created" && meeting.scheduledAt == null) "Без даты" else meetingStatusLabel(meeting.status),
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp), fontSize = 11.sp, lineHeight = 16.sp,
            color = if (active) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun WorkspaceShortcut(title: String, icon: ImageVector, click: () -> Unit, modifier: Modifier) {
    Surface(onClick = click, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = modifier.heightIn(min = 48.dp).fillMaxHeight()) {
        Row(Modifier.padding(horizontal = 9.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.primary)
            Text(title, Modifier.weight(1f), fontSize = 12.sp, lineHeight = 18.sp)
            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
