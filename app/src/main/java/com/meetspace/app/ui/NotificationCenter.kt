package com.meetspace.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.meetspace.core.model.InboxNotification
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The count is server-wide, independent of the loaded page and selected inbox filter. */
@Composable
internal fun NotificationBell(
    unreadCount: Long,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val unread = unreadCount.coerceAtLeast(0)
    val colors = MaterialTheme.colorScheme
    Box(modifier.size(48.dp)) {
        IconButton(onClick, Modifier.fillMaxSize().testTag("notification-bell")
            .semantics {
                contentDescription = if (unread == 0L) "Уведомления, всё прочитано" else "Уведомления, непрочитанных: $unread"
                stateDescription = if (expanded) "Открыты" else "Закрыты"
            }, colors = IconButtonDefaults.iconButtonColors(
                containerColor = if (expanded) colors.primaryContainer else Color.Transparent,
                contentColor = if (expanded) colors.primary else colors.onSurfaceVariant,
            )) {
            Icon(Icons.Outlined.NotificationsNone, null, Modifier.size(24.dp))
        }
        if (unread > 0) Surface(
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 1.dp, end = 1.dp)
                .defaultMinSize(minWidth = 19.dp, minHeight = 19.dp).testTag("notification-bell-count")
                .clearAndSetSemantics { },
            shape = CircleShape, color = colors.primary,
            border = BorderStroke(2.dp, colors.background),
        ) {
            Box(Modifier.padding(horizontal = 4.dp, vertical = 2.dp), contentAlignment = Alignment.Center) {
                Text(if (unread > 99) "99+" else unread.toString(), fontSize = 10.sp, lineHeight = 12.sp,
                    color = colors.onPrimary, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** [anchorBounds] uses the bell's boundsInWindow in pixels. Short windows allow every control to scroll into view. */
@Composable
internal fun NotificationCenter(
    state: MessagingUiState,
    actions: MessagingActions,
    onDismiss: () -> Unit,
    onSettings: () -> Unit,
    anchorBounds: IntRect? = null,
    meetingTitles: Map<String, String> = emptyMap(),
) {
    val contentDensity = LocalDensity.current
    val windowSize = LocalWindowInfo.current.containerSize
    val now by produceState(Instant.now()) {
        while (true) { delay(60_000); value = Instant.now() }
    }
    val position = remember {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize) = IntOffset.Zero
        }
    }
    Popup(popupPositionProvider = position, onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, dismissOnBackPress = true,
            dismissOnClickOutside = true, usePlatformDefaultWidth = false)) {
        // Popups own a window. Preserve the caller's text/density policy and use that same
        // density for measurements and pixel offsets (including multi-window/preview overrides).
        CompositionLocalProvider(LocalDensity provides contentDensity) {
        val density = LocalDensity.current
        val safeTop = WindowInsets.safeDrawing.getTop(density)
        val safeBottom = WindowInsets.safeDrawing.getBottom(density)
        BoxWithConstraints(Modifier.sizeIn(maxWidth = with(density) { windowSize.width.toDp() },
            maxHeight = with(density) { windowSize.height.toDp() }).fillMaxSize().testTag("notification-overlay")) {
            val colors = MaterialTheme.colorScheme
            val dark = colors.surface.luminance() < .5f
            val margin = 12.dp
            val panelWidth = minOf(400.dp, (maxWidth - margin * 2).coerceAtLeast(0.dp))
            val marginPx = with(density) { margin.roundToPx() }
            val minTop = maxOf(marginPx, safeTop + marginPx)
            val lastBottom = constraints.maxHeight - maxOf(safeBottom, marginPx)
            val maxTop = maxOf(minTop, lastBottom - with(density) { 240.dp.roundToPx() })
            val top = ((anchorBounds?.bottom?.plus(with(density) { 8.dp.roundToPx() }))
                ?: (safeTop + with(density) { 76.dp.roundToPx() })).coerceIn(minTop, maxTop)
            val widthPx = with(density) { panelWidth.roundToPx() }
            val left = ((anchorBounds?.right ?: (constraints.maxWidth - marginPx)) - widthPx)
                .coerceIn(marginPx, maxOf(marginPx, constraints.maxWidth - marginPx - widthPx))
            val panelHeight = minOf(640.dp, with(density) { (lastBottom - top).coerceAtLeast(1).toDp() })
            Box(Modifier.fillMaxSize().background(if (dark) Color.Black.copy(alpha = .21f) else Color(0xFF152139).copy(alpha = .10f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
                .clearAndSetSemantics { })
            Surface(Modifier.offset { IntOffset(left, top) }.width(panelWidth).heightIn(max = panelHeight)
                .pointerInput(Unit) { detectTapGestures { } }
                .testTag("notification-center").semantics { paneTitle = "Уведомления" },
                shape = RoundedCornerShape(if (maxWidth < 600.dp) 18.dp else 20.dp),
                border = BorderStroke(1.dp, colors.outlineVariant), shadowElevation = 12.dp,
                color = colors.surface) {
                val scrollControls = panelHeight.value / density.fontScale < 340f
                if (scrollControls) {
                    // Landscape, split-screen and large text can leave no room between pinned
                    // controls. One scroll container keeps both events and actions reachable.
                    LazyColumn(Modifier.fillMaxWidth().testTag("notification-events")) {
                        item("heading") { NotificationHeading(state.notificationUnread, onDismiss) }
                        item("toolbar") { NotificationToolbar(state, actions); HorizontalDivider(color = colors.outlineVariant) }
                        notificationEntries(state, actions, meetingTitles, now, colors, onDismiss)
                        item("settings") { HorizontalDivider(color = colors.outlineVariant); NotificationFooter(onDismiss, onSettings) }
                    }
                } else Column {
                    NotificationHeading(state.notificationUnread, onDismiss)
                    NotificationToolbar(state, actions)
                    HorizontalDivider(color = colors.outlineVariant)
                    key(state.notificationFilter) {
                        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).testTag("notification-events"),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)) {
                            notificationEntries(state, actions, meetingTitles, now, colors, onDismiss)
                        }
                    }
                    HorizontalDivider(color = colors.outlineVariant)
                    NotificationFooter(onDismiss, onSettings)
                }
            }
        }
        }
    }
}

private fun LazyListScope.notificationEntries(state: MessagingUiState, actions: MessagingActions,
    meetingTitles: Map<String, String>, now: Instant, colors: ColorScheme, onDismiss: () -> Unit) {
    val notifications = state.notifications.data.orEmpty()
    if (state.notifications.error != null) item("error") {
        Column(Modifier.fillMaxWidth().padding(12.dp).testTag("notification-error"),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(state.notifications.error, color = colors.error, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            TextButton(actions.loadNotifications, enabled = !state.notifications.loading && !state.notificationBusy,
                modifier = Modifier.heightIn(min = 48.dp).testTag("notification-retry")) {
                Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Повторить")
            }
        }
    }
    itemsIndexed(notifications, key = { _, item -> item.id }) { index, item ->
        val group = notificationGroup(item, now)
        if (index == 0 || notificationGroup(notifications[index - 1], now) != group) {
            Text(group, Modifier.padding(start = 12.dp, top = 12.dp, bottom = 7.dp)
                .semantics { heading() }, fontSize = 10.sp, lineHeight = 14.sp,
                fontWeight = FontWeight.SemiBold, color = colors.onSurfaceVariant)
        }
        NotificationEvent(item, state, meetingTitles, now) {
            actions.openNotification(item)
            onDismiss()
        }
    }
    if (state.notifications.loading || state.notificationBusy) item("progress") {
        Row(Modifier.fillMaxWidth().padding(16.dp).testTag("notification-loading"),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(if (state.notificationBusy) "Отмечаем прочитанными…" else "Загружаем уведомления…",
                style = MaterialTheme.typography.bodySmall)
        }
    }
    if (notifications.isEmpty() && !state.notifications.loading && !state.notificationBusy && state.notifications.error == null) item("empty") {
        NotificationEmpty(state.notificationFilter == NotificationFilter.UNREAD, state.notificationCursor != null)
    }
    if (state.notificationCursor != null) item("more") {
        TextButton(actions.moreNotifications, enabled = !state.notifications.loading && !state.notificationBusy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("notification-more")) {
            Text(if (notifications.isEmpty() && state.notificationFilter == NotificationFilter.UNREAD) "Проверить более ранние" else "Ещё уведомления", fontSize = 12.sp)
        }
    }
}

@Composable
private fun NotificationFooter(onDismiss: () -> Unit, onSettings: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    TextButton({ onDismiss(); onSettings() }, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        .fillMaxWidth().heightIn(min = 48.dp).testTag("notification-settings"),
        colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurfaceVariant),
        shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)) {
        Icon(Icons.Outlined.Settings, null, Modifier.size(17.dp)); Spacer(Modifier.width(8.dp))
        Text("Настроить уведомления", fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(8.dp)); Icon(Icons.Outlined.ChevronRight, null, Modifier.size(17.dp))
    }
}

@Composable
private fun NotificationHeading(unread: Long, close: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 65.dp).padding(start = 16.dp, end = 10.dp, top = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Уведомления", fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() })
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                Text(if (unread > 0) "$unread ${russianCount(unread, "новое", "новых", "новых")}" else "Всё прочитано",
                    Modifier.padding(horizontal = 7.dp, vertical = 4.dp).testTag("notification-total")
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            }
        }
        IconButton(close, Modifier.size(48.dp).testTag("notification-close")) {
            Icon(Icons.Outlined.Close, "Закрыть уведомления", Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NotificationToolbar(state: MessagingUiState, actions: MessagingActions) {
    FlowRow(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 11.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(3.dp).selectableGroup()) {
            for ((filter, label) in listOf(NotificationFilter.ALL to "Все", NotificationFilter.UNREAD to "Непрочитанные")) {
                val selected = state.notificationFilter == filter
                Box(Modifier.widthIn(min = 53.dp).heightIn(min = 48.dp).clip(RoundedCornerShape(9.dp))
                    .background(if (selected) MaterialTheme.colorScheme.surface else Color.Transparent)
                    .selectable(selected, enabled = !state.notificationBusy, role = Role.Tab) { actions.notificationFilter(filter) }
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .testTag(if (filter == NotificationFilter.ALL) "notification-filter-all" else "notification-filter-unread"),
                    contentAlignment = Alignment.Center) {
                    Text(label, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        TextButton(actions.readAllNotifications, enabled = state.notificationUnread > 0 && !state.notificationBusy,
            modifier = Modifier.heightIn(min = 48.dp).testTag("notification-read-all"),
            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp)) {
            Icon(Icons.Outlined.DoneAll, null, Modifier.size(15.dp)); Spacer(Modifier.width(5.dp))
            Text("Прочитать все", fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun NotificationEvent(item: InboxNotification, state: MessagingUiState, meetingTitles: Map<String, String>, now: Instant, open: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val unread = item.readAt == null
    val presentation = notificationPresentation(item)
    val meetingTitle = item.conferenceId?.let(meetingTitles::get)?.takeIf { it.isNotBlank() }
    val chatTitle = state.conversations.data?.find { it.id == item.conversationId }?.title?.takeIf { it.isNotBlank() }
    val scheduledAt = item.scheduledAt
    val body = when {
        scheduledAt != null -> listOfNotNull(meetingTitle, "Начало ${notificationDate(scheduledAt)}").joinToString(" · ")
        chatTitle != null -> chatTitle
        meetingTitle != null -> meetingTitle
        else -> presentation.body
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(14.dp))
        .background(if (unread) lerp(colors.surface, colors.primaryContainer, .55f) else Color.Transparent)
        .clickable(enabled = !state.notificationBusy, role = Role.Button, onClick = open)
        .testTag("notification-event-${item.id}").semantics(mergeDescendants = true) {
            stateDescription = if (unread) "Не прочитано" else "Прочитано"
        }.padding(horizontal = 10.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.Top) {
        Surface(Modifier.size(34.dp), shape = RoundedCornerShape(11.dp), color = if (unread) colors.surface else colors.surfaceVariant) {
            Box(contentAlignment = Alignment.Center) {
                Icon(presentation.icon, null, Modifier.size(20.dp), tint = if (unread) colors.primary else colors.onSurfaceVariant)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(presentation.title, fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(body, Modifier.padding(top = 4.dp), fontSize = 11.sp, lineHeight = 17.sp,
                color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${notificationTime(item.createdAt, now)} · ${presentation.category}", Modifier.padding(top = 7.dp),
                fontSize = 10.sp, lineHeight = 14.sp, color = colors.onSurfaceVariant)
        }
        if (unread) Box(Modifier.padding(top = 6.dp).size(6.dp).background(colors.primary, CircleShape))
        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(14.dp).align(Alignment.CenterVertically),
            tint = colors.onSurfaceVariant.copy(alpha = .65f))
    }
}

@Composable
private fun NotificationEmpty(unreadOnly: Boolean, hasMore: Boolean) {
    Column(Modifier.fillMaxWidth().heightIn(min = 235.dp).padding(horizontal = 22.dp, vertical = 30.dp).testTag("notification-empty"),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)) {
        Surface(shape = RoundedCornerShape(21.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(if (unreadOnly) Icons.Outlined.DoneAll else Icons.Outlined.NotificationsNone, null,
                Modifier.padding(14.dp).size(34.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Text(when { hasMore -> "Здесь всё прочитано"; unreadOnly -> "Всё прочитано"; else -> "Уведомлений пока нет" },
            fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
        Text(when {
            hasMore -> "Можно проверить более ранние события."
            unreadOnly -> "Новых уведомлений пока нет. Все события доступны во вкладке «Все»."
            else -> "Приглашения, сообщения и готовые записи появятся здесь."
        }, fontSize = 12.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

private data class NotificationPresentation(val title: String, val body: String, val category: String, val icon: ImageVector)

private fun notificationPresentation(item: InboxNotification): NotificationPresentation = when {
    item.type == "conference.soon" || item.type.contains("reminder") -> NotificationPresentation("Встреча скоро начнётся", "Откройте встречу, чтобы посмотреть время и участников.", "Напоминание", Icons.Outlined.CalendarMonth)
    item.type == "conference.invited" || item.type.contains("invitation") -> NotificationPresentation("Приглашение на встречу", "Вас пригласили на встречу.", "Приглашение", Icons.Outlined.PersonAdd)
    item.type == "conference.rescheduled" -> NotificationPresentation("Встреча перенесена", "Посмотрите новое время встречи.", "Встреча", Icons.Outlined.CalendarMonth)
    item.type == "summary.failed" -> NotificationPresentation("Не удалось подготовить итоги", "Посмотрите состояние материалов встречи.", "Итоги встречи", Icons.Outlined.Description)
    item.type == "transcript.failed" -> NotificationPresentation("Не удалось подготовить расшифровку", "Посмотрите состояние материалов встречи.", "Расшифровка", Icons.Outlined.Description)
    item.type.contains("recording") -> NotificationPresentation("Запись встречи готова", "Видео встречи уже доступно для просмотра.", "Запись", Icons.Outlined.PlayCircleOutline)
    item.type.contains("summary") -> NotificationPresentation("Итоги встречи готовы", "Откройте материалы встречи, чтобы посмотреть итоги.", "Итоги встречи", Icons.Outlined.Description)
    item.type.contains("transcript") -> NotificationPresentation("Расшифровка встречи готова", "Текст встречи доступен в материалах.", "Расшифровка", Icons.Outlined.Description)
    item.type.contains("message") || item.conversationId != null -> NotificationPresentation("Новое сообщение", "В чате есть новое сообщение.", "Сообщение", Icons.Outlined.Forum)
    item.type.contains("cancel") -> NotificationPresentation("Встреча отменена", "Откройте встречу, чтобы посмотреть подробности.", "Встреча", Icons.Outlined.EventBusy)
    item.type.contains("admission") -> NotificationPresentation("Изменился доступ к встрече", "Посмотрите актуальное состояние встречи.", "Участие", Icons.Outlined.PersonAdd)
    else -> NotificationPresentation("Событие встречи", "Откройте событие, чтобы посмотреть подробности.", "Встреча", Icons.Outlined.Videocam)
}

private fun notificationGroup(item: InboxNotification, now: Instant) =
    if (item.createdAt >= now.minusSeconds(24 * 60 * 60)) "Недавно" else "Ранее"

private fun notificationDate(at: Instant): String = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.forLanguageTag("ru"))
    .format(at.atZone(ZoneId.systemDefault()))

private fun notificationTime(at: Instant, now: Instant): String {
    val minutes = Duration.between(at, now).toMinutes().coerceAtLeast(0)
    return when {
        minutes == 0L -> "Только что"
        minutes < 60 -> "$minutes ${russianCount(minutes, "минуту", "минуты", "минут")} назад"
        minutes < 24 * 60 -> (minutes / 60).let { "$it ${russianCount(it, "час", "часа", "часов")} назад" }
        else -> notificationDate(at)
    }
}

private fun russianCount(value: Long, one: String, few: String, many: String) = when {
    value % 100 in 11..14 -> many
    value % 10 == 1L -> one
    value % 10 in 2..4 -> few
    else -> many
}
