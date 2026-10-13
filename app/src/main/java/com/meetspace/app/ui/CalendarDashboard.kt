package com.meetspace.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meetspace.core.model.*
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The week strip controls the authenticated month feeds; pagination and partial failures stay visible. */
@Composable
internal fun CalendarDashboard(state: MeetingsUiState, actions: MeetingsActions, schedule: () -> Unit, onZone: () -> Unit, modifier: Modifier = Modifier) {
    var filters by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var viewFilter by remember { mutableStateOf<MeetingsView?>(null) }
    val feeds = MeetingsView.entries.map { it to (state.calendarFeeds[it] ?: MeetingsLoad()) }
    val visibleFeeds = feeds.filter { viewFilter == null || it.first == viewFilter }
    val items = visibleFeeds.flatMap { it.second.data?.items.orEmpty() }.distinctBy { it.id }.sortedBy { it.timelineAt }
    val selectedItems = items.filter { it.timelineAt.atZone(state.zone).toLocalDate() == state.calendarDay }
    val weekStart = state.calendarDay.minusDays((state.calendarDay.dayOfWeek.value - 1).toLong())
    BoxWithConstraints(modifier.fillMaxSize().testTag("calendar")) {
        val fontScale = LocalDensity.current.fontScale
        val wide = maxWidth >= 780.dp
        val phone = maxWidth < 600.dp
        // The reference keeps the 780 dp stage but only adds the sidebar from 840 dp.
        // Larger text needs enough room for both the month controls and seven dates.
        val showSide = maxWidth >= 840.dp && maxWidth - 408.dp >= 340.dp * fontScale.coerceAtLeast(1f)
        Column(Modifier.fillMaxSize().padding(horizontal = if (wide) 24.dp else 0.dp)
            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState()).padding(horizontal = if (wide) 28.dp else 18.dp, vertical = if (wide) 28.dp else 22.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 22.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text("Календарь", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                    Text("Время для важных разговоров.", Modifier.padding(top = 8.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.testTag("calendar-menu")) { Icon(Icons.Outlined.MoreVert, "Действия календаря", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(if (filters) "Скрыть фильтры" else "Фильтры") }, onClick = { filters = !filters; menu = false }, leadingIcon = { Icon(Icons.Outlined.Tune, null) }, modifier = Modifier.testTag("calendar-filters"))
                        DropdownMenuItem(text = { Text("Обновить") }, onClick = { menu = false; actions.refreshCalendar() }, enabled = feeds.none { it.second.loading }, leadingIcon = { Icon(Icons.Outlined.Refresh, null) }, modifier = Modifier.testTag("calendar-refresh"))
                    }
                }
                FilledTonalIconButton(onClick = schedule, enabled = !state.actionBusy && !state.creationUncertain, modifier = Modifier.testTag("calendar-schedule")) { Icon(Icons.Outlined.Add, "Запланировать встречу") }
            }
            if (filters) {
                ScopeFilter(state.calendarScope, actions.calendarFilter)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = viewFilter == null, onClick = { viewFilter = null }, label = { Text("Все виды") })
                    MeetingsView.entries.forEach { view -> FilterChip(selected = viewFilter == view, onClick = { viewFilter = view }, label = { Text(viewLabel(view)) }) }
                }
            }
            val agenda: @Composable (Modifier) -> Unit = { pane ->
                Column(pane.testTag("calendar-agenda")) {
                    CalendarWeekStrip(state.calendarDay, state.zone, items, actions.day, phone)
                    Spacer(Modifier.height(if (phone) 20.dp else 24.dp))
                    val daySummary = when {
                        visibleFeeds.any { it.second.loading || it.second.data == null && it.second.error == null } -> "Загрузка…"
                        visibleFeeds.any { it.second.error != null } -> "Не все данные загружены"
                        selectedItems.isEmpty() -> "Нет встреч"
                        else -> "Встреч: ${selectedItems.size}"
                    }
                    Text(state.calendarDay.format(DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("ru"))) + " · " + daySummary,
                        modifier = Modifier.semantics { heading() }, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(16.dp))
                    visibleFeeds.forEach { (view, load) -> if (load.loading || load.error != null) {
                        Text(viewLabel(view), style = MaterialTheme.typography.labelMedium)
                        MeetingLoadStatus(load, actions.refreshCalendar)
                    } }
                    if (selectedItems.isEmpty() && visibleFeeds.all { it.second.data != null && !it.second.loading && it.second.error == null }) {
                        Text("На загруженных страницах встреч в этот день нет.", Modifier.padding(bottom = 16.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val hours = selectedItems.map { it.timelineAt.atZone(state.zone).hour }
                    val firstHour = minOf(9, hours.minOrNull() ?: 9)
                    val lastHour = maxOf(18, hours.maxOrNull() ?: 18)
                    for (hour in firstHour..lastHour) {
                        Row(Modifier.fillMaxWidth()) {
                            Text("%02d:00".format(hour), Modifier.width(43.dp * fontScale.coerceAtLeast(1f)).padding(top = 4.dp),
                                fontSize = 10.sp, lineHeight = 14.sp, maxLines = 1, softWrap = false, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Column(Modifier.weight(1f).heightIn(min = if (phone) 82.dp else 78.dp)) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                selectedItems.filter { it.timelineAt.atZone(state.zone).hour == hour }.forEach { meeting ->
                                    CalendarEvent(meeting, state.zone, actions.open, Modifier.padding(vertical = 6.dp))
                                }
                            }
                        }
                    }
                    if (feeds.any { it.second.data?.nextCursor != null }) {
                        TextButton(onClick = actions.moreCalendar, enabled = feeds.none { it.second.loading }, modifier = Modifier.fillMaxWidth().testTag("calendar-more")) { Text("Загрузить следующие страницы месяца") }
                        Text("Показаны загруженные встречи. В следующих страницах могут быть другие встречи этого дня.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (showSide) Row(Modifier.fillMaxWidth().testTag("calendar-wide"), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
                agenda(Modifier.weight(1f))
                CalendarSide(items.filter { val date = it.timelineAt.atZone(state.zone).toLocalDate(); date >= weekStart && date < weekStart.plusDays(7) }, state, actions, onZone, Modifier.width(280.dp))
            } else {
                agenda(Modifier.fillMaxWidth().testTag("calendar-compact"))
                TextButton(onClick = onZone, modifier = Modifier.testTag("meeting-zone")) { Icon(Icons.Outlined.Schedule, null, Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)); Text(state.zone.id, fontSize = 12.sp) }
            }
        }
    }
}

@Composable
private fun CalendarWeekStrip(day: LocalDate, zone: ZoneId, items: List<Meeting>, choose: (LocalDate) -> Unit, phone: Boolean) {
    val start = day.minusDays((day.dayOfWeek.value - 1).toLong())
    val eventDays = items.map { it.timelineAt.atZone(zone).toLocalDate() }.toSet()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val separateControls = maxWidth < 410.dp && LocalDensity.current.fontScale > 1.2f
        val month: @Composable (Modifier) -> Unit = { modifier ->
            Text(day.format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale.forLanguageTag("ru"))).replaceFirstChar { it.uppercase() }, modifier,
                fontSize = if (phone) 18.sp else 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
        }
        val controls: @Composable () -> Unit = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { choose(LocalDate.now(zone)) }, contentPadding = PaddingValues(4.dp), modifier = Modifier.testTag("calendar-today")) { Text("Сегодня", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                IconButton(onClick = { choose(day.minusWeeks(1)) }, modifier = Modifier.size(48.dp).testTag("calendar-previous")) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, "Предыдущая неделя", Modifier.size(20.dp)) }
                IconButton(onClick = { choose(day.plusWeeks(1)) }, modifier = Modifier.size(48.dp).testTag("calendar-next")) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "Следующая неделя", Modifier.size(20.dp)) }
            }
        }
        if (separateControls) Column { month(Modifier.fillMaxWidth()); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { controls() } }
        else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { month(Modifier.weight(1f)); controls() }
    }
    Spacer(Modifier.height(if (phone) 14.dp else 18.dp))
    Surface(shape = RoundedCornerShape(if (phone) 17.dp else 20.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val padding = if (phone) 7.dp else 12.dp
            val gap = if (phone) 2.dp else 7.dp
            val stripWidth = maxOf(maxWidth, maxOf(42.dp, 26.dp * LocalDensity.current.fontScale) * 7 + padding * 2 + gap * 6)
            Row(Modifier.horizontalScroll(rememberScrollState()).width(stripWidth).padding(padding), horizontalArrangement = Arrangement.spacedBy(gap)) {
                repeat(7) { offset ->
                    val date = start.plusDays(offset.toLong())
                    val active = date == day
                    Column(Modifier.weight(1f).heightIn(min = if (phone) 70.dp else 74.dp).clip(RoundedCornerShape(if (phone) 12.dp else 13.dp))
                        .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
                        .clickable { choose(date) }.padding(vertical = 8.dp).testTag("calendar-day-$date")
                        .semantics { selected = active; contentDescription = date.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("ru"))) + if (date in eventDays) ", есть встречи" else "" },
                        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
                        val color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                        Text(listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")[offset], fontSize = if (phone) 9.sp else 10.sp, lineHeight = 13.sp, maxLines = 1, softWrap = false, color = if (active) color.copy(alpha = .8f) else MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(date.dayOfMonth.toString(), fontSize = if (phone) 16.sp else 17.sp, lineHeight = 20.sp, maxLines = 1, softWrap = false, fontWeight = FontWeight.SemiBold, color = color)
                        Box(Modifier.size(4.dp).background(if (date in eventDays) color else androidx.compose.ui.graphics.Color.Transparent, CircleShape))
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarEvent(meeting: Meeting, zone: ZoneId, open: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().heightIn(min = 65.dp).clip(RoundedCornerShape(topStart = 5.dp, bottomStart = 5.dp, topEnd = 13.dp, bottomEnd = 13.dp))
        .background(MaterialTheme.colorScheme.primaryContainer).clickable { open(meeting.id) }.testTag("meeting-row-${meeting.id}")) {
        Box(Modifier.width(3.dp).heightIn(min = 65.dp).background(MaterialTheme.colorScheme.primary))
        Column(Modifier.padding(horizontal = 15.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(meeting.title, fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold)
            val at = meeting.timelineAt.atZone(zone)
            val time = at.format(DateTimeFormatter.ofPattern("HH:mm"))
            val end = meeting.plannedDurationMin?.takeIf { meeting.scheduledAt != null && meeting.finishedAt == null }?.let { " — " + at.plusMinutes(it.toLong()).format(DateTimeFormatter.ofPattern("HH:mm")) }.orEmpty()
            Text(time + end + " · " + meetingStatusLabel(meeting.status), fontSize = 10.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CalendarSide(items: List<Meeting>, state: MeetingsUiState, actions: MeetingsActions, onZone: () -> Unit, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("НА ЭТОЙ НЕДЕЛЕ", fontSize = 10.sp, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Хороший ритм", fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
            Text("Все встречи команды в вашем часовом поясе.", fontSize = 12.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            items.take(5).forEach { meeting ->
                Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { actions.open(meeting.id) }.padding(vertical = 4.dp)) {
                    Text(meeting.title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text(meeting.timelineAt.atZone(state.zone).format(DateTimeFormatter.ofPattern("EEE, HH:mm", Locale.forLanguageTag("ru"))), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (items.isEmpty()) Text("На загруженных страницах нет встреч этой недели.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onZone, modifier = Modifier.testTag("meeting-zone"), contentPadding = PaddingValues(0.dp)) { Icon(Icons.Outlined.Schedule, null, Modifier.size(15.dp)); Spacer(Modifier.width(6.dp)); Text(state.zone.id, fontSize = 11.sp) }
        }
    }
}
