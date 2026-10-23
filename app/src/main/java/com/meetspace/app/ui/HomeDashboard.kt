package com.meetspace.app.ui

import android.content.ClipData
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meetspace.core.model.ChatConversation
import com.meetspace.core.model.RepositoryState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * The approved home composition has a conversation pane, never a tablet navigation rail.
 * Its width is the current application viewport, so split screen also becomes compact.
 * Private conversation content stays in the supplied transient ViewModel state.
 */
@Composable
fun HomeDashboard(
    repository: RepositoryState,
    messaging: MessagingUiState,
    messagingActions: MessagingActions,
    busy: Boolean,
    disableCreate: Boolean,
    onCreate: () -> Unit,
    onNewChat: () -> Unit,
    onJoin: () -> Unit,
    onSchedule: () -> Unit,
    onOpenChat: (String) -> Unit,
    onFolders: () -> Unit,
    onProfile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize().testTag("home-dashboard")) {
        val viewportWidth = maxWidth
        val wide = viewportWidth / LocalDensity.current.fontScale.coerceAtLeast(1f) >= 780.dp
        val stage = Modifier.fillMaxSize().padding(horizontal = if (wide) 24.dp else 0.dp)
            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(MaterialTheme.colorScheme.surface)
        val chats: @Composable (Modifier) -> Unit = { paneModifier ->
            HomeConversations(
                repository, messaging, messagingActions, wide,
                onNewChat, onOpenChat, onFolders, onProfile, paneModifier,
            )
        }
        if (wide) {
            Row(stage.testTag("home-wide")) {
                chats(Modifier.width(if (viewportWidth >= 1000.dp) 282.dp else 226.dp).fillMaxHeight().verticalScroll(rememberScrollState()))
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                    val viewportHeight = maxHeight
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Column(
                            Modifier.heightIn(min = viewportHeight).padding(horizontal = 30.dp, vertical = 20.dp).widthIn(max = 680.dp).fillMaxWidth(),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            HomeWelcome(true)
                            HomeActions(true, busy, disableCreate, messaging.busy, onCreate, onNewChat, onJoin, onSchedule)
                            Spacer(Modifier.height(24.dp))
                            HomeInvite(repository.selectedProfile?.origin, true)
                        }
                    }
                }
            }
        } else {
            Column(stage.verticalScroll(rememberScrollState()).testTag("home-compact")) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = if (viewportWidth < 360.dp) 16.dp else 20.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Column(Modifier.widthIn(max = 680.dp).fillMaxWidth()) {
                        HomeWelcome(false)
                        HomeActions(viewportWidth >= 600.dp, busy, disableCreate, messaging.busy, onCreate, onNewChat, onJoin, onSchedule)
                        Spacer(Modifier.height(26.dp))
                        HomeInvite(repository.selectedProfile?.origin, viewportWidth >= 600.dp)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                chats(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun HomeWelcome(wide: Boolean) {
    Text(
        "Чаты и видеовстречи", modifier = Modifier.fillMaxWidth().semantics { heading() },
        fontSize = if (wide) 27.sp else 25.sp, lineHeight = if (wide) 34.sp else 32.sp,
        fontWeight = FontWeight.SemiBold, letterSpacing = (-0.7).sp, textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(8.dp))
    Text("Будьте рядом, где бы вы ни находились.", Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 14.sp, lineHeight = 22.sp, textAlign = TextAlign.Center)
    Spacer(Modifier.height(24.dp))
}

private data class HomeAction(val title: String, val note: String, val icon: ImageVector, val tag: String, val enabled: Boolean, val click: () -> Unit)

private val HomeChatIcon: ImageVector by lazy {
    ImageVector.Builder("HomeChat", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(7.8f, 19.9f)
            curveTo(9.1f, 20.6f, 10.5f, 21f, 12f, 21f)
            curveTo(17f, 21f, 21f, 17f, 21f, 12f)
            curveTo(21f, 7f, 17f, 3f, 12f, 3f)
            curveTo(7f, 3f, 3f, 7f, 3f, 12f)
            curveTo(3f, 13.5f, 3.4f, 14.9f, 4.1f, 16.2f)
            lineTo(2f, 22f)
            close()
        }
    }.build()
}

@Composable
private fun HomeActions(
    showNotes: Boolean, busy: Boolean, disableCreate: Boolean, chatBusy: Boolean,
    onCreate: () -> Unit, onNewChat: () -> Unit, onJoin: () -> Unit, onSchedule: () -> Unit,
) {
    val actions = listOf(
        HomeAction("Новый звонок", "Начните разговор сейчас", Icons.Outlined.Videocam, "home-new-call", !busy && !disableCreate, onCreate),
        HomeAction("Новый чат", "Напишите коллеге или команде", HomeChatIcon, "home-new-chat", !chatBusy, onNewChat),
        HomeAction("Подключиться к звонку", "По ссылке-приглашению", Icons.Outlined.Link, "home-join-call", !busy, onJoin),
        HomeAction("Запланировать встречу", "Выберите удобное время", Icons.Outlined.CalendarMonth, "home-schedule", !busy && !disableCreate, onSchedule),
    )
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    BoxWithConstraints(Modifier.fillMaxWidth().drawWithCache {
        val blueCenter = Offset(size.width * .3f, size.height * .58f)
        val greenCenter = Offset(size.width * .78f, size.height * .58f)
        val blueRadius = minOf(size.width * .6f, size.height * .78f).coerceAtLeast(1f)
        val greenRadius = minOf(size.width * .5f, size.height * .7f).coerceAtLeast(1f)
        val blueGlow = Brush.radialGradient(listOf(if (dark) Color(0xFF5C82CC).copy(alpha = .27f) else Color(0xAAB9D3F9), Color.Transparent), blueCenter, blueRadius)
        val greenGlow = Brush.radialGradient(listOf(if (dark) Color(0xFF45836A).copy(alpha = .27f) else Color(0x88CCEAE5), Color.Transparent), greenCenter, greenRadius)
        onDrawBehind {
            // Each drawn shape ends exactly where its gradient becomes transparent.
            // A rectangle clipped inside the radius would produce a visible hard edge.
            drawCircle(blueGlow, radius = blueRadius, center = blueCenter)
            drawCircle(greenGlow, radius = greenRadius, center = greenCenter)
        }
    }) {
        val columns = if (maxWidth.value / LocalDensity.current.fontScale < 300f) 1 else 2
        Column(verticalArrangement = Arrangement.spacedBy(if (showNotes) 16.dp else 12.dp)) {
            if (columns == 1) {
                // Intrinsic row heights can round scaled text metrics down by a pixel.
                // Stacked cards have no equal-height peer and should measure naturally.
                actions.forEach { action -> HomeActionCard(action, showNotes, Modifier.fillMaxWidth()) }
            } else {
                actions.chunked(columns).forEach { row ->
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(if (showNotes) 16.dp else 12.dp)) {
                        row.forEach { action -> HomeActionCard(action, showNotes, Modifier.weight(1f).fillMaxHeight()) }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeActionCard(action: HomeAction, showNotes: Boolean, modifier: Modifier) {
    Surface(
        onClick = action.click, enabled = action.enabled, modifier = modifier.heightIn(min = if (showNotes) 124.dp else 120.dp).testTag(action.tag),
        shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f)), shadowElevation = 2.dp,
    ) {
        Column(Modifier.padding(horizontal = if (showNotes) 22.dp else 18.dp, vertical = 18.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Icon(action.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary.copy(alpha = if (action.enabled) 1f else .38f), modifier = Modifier.size(28.dp))
                if (showNotes) Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(17.dp).rotate(-45f), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .5f))
            }
            Spacer(Modifier.height(if (showNotes) 12.dp else 15.dp))
            Text(action.title, modifier = Modifier.fillMaxWidth(), fontWeight = FontWeight.SemiBold, fontSize = if (showNotes) 16.sp else 15.sp, lineHeight = 21.sp,
                letterSpacing = (-.3).sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (action.enabled) 1f else .38f))
            if (showNotes) {
                Spacer(Modifier.height(6.dp))
                Text(action.note, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 16.sp)
            }
        }
    }
}

@Composable
private fun HomeInvite(origin: String?, showArt: Boolean) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember(origin) { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(21.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), color = MaterialTheme.colorScheme.surface) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = if (showArt) 25.dp else 20.dp, vertical = if (showArt) 18.dp else 20.dp)) {
            val availableWidth = maxWidth
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                if (showArt && availableWidth.value / LocalDensity.current.fontScale >= 400f) HomeInviteArt()
                Column(Modifier.weight(1f)) {
                    Text("Встречаться проще вместе", fontSize = if (showArt) 16.sp else 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-.35).sp)
                    Spacer(Modifier.height(4.dp))
                    Text("Пригласите коллег в MeetSpace по ссылке.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 18.sp)
                    Spacer(Modifier.height(15.dp))
                    Button(
                        onClick = { origin?.let { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("MeetSpace", it))); copied = true } } },
                        enabled = !origin.isNullOrBlank(), modifier = Modifier.heightIn(min = 48.dp).testTag("home-invite-copy"), shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(Icons.Outlined.ContentCopy, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (copied) "Ссылка скопирована" else "Скопировать ссылку", fontSize = 12.sp, lineHeight = 18.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeInviteArt() {
    Box(Modifier.size(105.dp, 90.dp)) {
        Surface(Modifier.size(62.dp, 65.dp).align(Alignment.TopEnd).rotate(10f), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Icon(Icons.Outlined.PersonOutline, null, Modifier.padding(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Surface(Modifier.size(68.dp, 72.dp).align(Alignment.BottomStart).rotate(-9f), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shadowElevation = 3.dp) {
            Icon(Icons.Outlined.PersonOutline, null, Modifier.padding(14.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Surface(Modifier.size(35.dp).align(Alignment.BottomEnd), shape = CircleShape, color = MaterialTheme.colorScheme.primary,
            border = BorderStroke(3.dp, MaterialTheme.colorScheme.surface)) {
            Icon(Icons.Outlined.Add, null, Modifier.padding(7.dp), tint = MaterialTheme.colorScheme.onPrimary)
        }
    }
}

@Composable
private fun HomeConversations(
    repository: RepositoryState, state: MessagingUiState, actions: MessagingActions, wide: Boolean,
    onNewChat: () -> Unit, onOpenChat: (String) -> Unit, onFolders: () -> Unit, onProfile: () -> Unit, modifier: Modifier,
) {
    Column(modifier.background(MaterialTheme.colorScheme.surface).padding(horizontal = if (wide) 18.dp else 20.dp, vertical = 24.dp).testTag("home-chats")) {
        if (wide) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onProfile).testTag("home-profile"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HomeAvatar(repository.session?.user?.displayLabel.orEmpty(), false, state.realtimeLabel == "Обновления подключены")
                    Column(Modifier.weight(1f)) {
                        Text(repository.session?.user?.displayLabel.orEmpty(), fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(if (state.realtimeLabel == "Обновления подключены") "В сети" else "MeetSpace", fontSize = 11.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                IconButton(onClick = onNewChat, enabled = !state.busy, modifier = Modifier.background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp))) {
                    Icon(Icons.Outlined.Add, "Новый чат", tint = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.height(26.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Ваши чаты", fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
            state.conversations.data?.let { Text("${it.size}${if (state.conversationCursor != null) "+" else ""}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp) }
        }
        Spacer(Modifier.height(15.dp))
        HomeChatSearch(state.query, actions.query)
        Spacer(Modifier.height(6.dp))
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ChatFilter.entries.forEach { filter ->
                val active = state.filter == filter
                Surface(
                    onClick = { actions.filter(filter) }, shape = RoundedCornerShape(9.dp),
                    color = if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("home-filter-${filter.name.lowercase()}").semantics { selected = active },
                ) {
                    Box(Modifier.padding(horizontal = 10.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                        Text(when (filter) { ChatFilter.ALL -> "Все"; ChatFilter.DIRECT -> "Личные"; ChatFilter.GROUP -> "Группы"; ChatFilter.UNREAD -> "Новые" }, fontSize = 12.sp, lineHeight = 20.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        if (state.conversations.loading) Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.Center) {
            CircularProgressIndicator(Modifier.size(22.dp).semantics { contentDescription = "Загрузка чатов" }, strokeWidth = 2.dp)
        }
        state.conversations.error?.let { error ->
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = actions.refresh) { Text("Повторить") }
        }
        val conversations = state.conversations.data.orEmpty()
        if (conversations.isEmpty() && !state.conversations.loading && state.conversations.error == null) {
            Text(if (state.query.isNotBlank() || state.filter != ChatFilter.ALL) "Чаты не найдены" else "Здесь появятся ваши чаты. Начните разговор с коллегой или командой.",
                Modifier.fillMaxWidth().padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 19.sp, textAlign = TextAlign.Center)
        }
        conversations.forEach { conversation ->
            HomeConversationRow(conversation, { onOpenChat(conversation.id) })
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        if (state.conversationCursor != null) TextButton(onClick = actions.moreConversations, enabled = !state.conversations.loading, modifier = Modifier.fillMaxWidth()) { Text("Показать ещё") }
        Row(Modifier.fillMaxWidth().heightIn(min = 58.dp).clickable(role = Role.Button, onClick = onFolders).testTag("home-folders"),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Icon(Icons.Outlined.FolderOpen, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Папки команды", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 18.sp)
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HomeChatSearch(query: String, onQuery: (String) -> Unit) {
    WorkspaceSearchField(query, onQuery, "Найти человека или чат", "home-chat-search")
}

@Composable
private fun HomeConversationRow(conversation: ChatConversation, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 89.dp).clickable(role = Role.Button, onClick = onClick).padding(vertical = 15.dp).testTag("home-chat-${conversation.id}"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HomeAvatar(conversation.title, conversation.type == "group")
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(conversation.title, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (conversation.type == "group") Text("Группа · ${conversation.memberCount} ${memberWord(conversation.memberCount)}", fontSize = 10.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (conversation.preview.isNotBlank()) Text(conversation.preview, fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            conversation.lastMessageAt?.takeIf { it != Instant.EPOCH }?.let { last -> Text(homeMessageTime(last), fontSize = 10.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (conversation.unreadCount > 0) Surface(color = MaterialTheme.colorScheme.primary, shape = CircleShape) {
                Text(if (conversation.unreadCount > 99) "99+" else conversation.unreadCount.toString(), Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}

@Composable
private fun HomeAvatar(title: String, group: Boolean, online: Boolean = false) {
    Box(Modifier.size(42.dp, 44.dp)) {
        Surface(Modifier.fillMaxSize(), shape = RoundedCornerShape(13.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Box(contentAlignment = Alignment.Center) { Text(homeInitials(title), color = MaterialTheme.colorScheme.primary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
        }
        if (group) Surface(Modifier.size(20.dp).align(Alignment.BottomEnd), shape = RoundedCornerShape(7.dp), color = MaterialTheme.colorScheme.primaryContainer,
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.surface)) {
            Icon(Icons.Outlined.Groups, "Групповой чат", Modifier.padding(3.dp), tint = MaterialTheme.colorScheme.primary)
        }
        else if (online) Surface(Modifier.size(10.dp).align(Alignment.BottomEnd), shape = CircleShape, color = Color(0xFF28895D),
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.surface)) { }
    }
}

private fun homeInitials(name: String): String = name.trim().split(Regex("\\s+")).filter(String::isNotBlank).take(2).joinToString("") { it.take(1).uppercase(Locale.ROOT) }

private fun memberWord(count: Int): String = when {
    count % 100 in 11..14 -> "участников"
    count % 10 == 1 -> "участник"
    count % 10 in 2..4 -> "участника"
    else -> "участников"
}

private fun homeMessageTime(time: Instant): String {
    val local = time.atZone(ZoneId.systemDefault())
    val pattern = if (local.toLocalDate() == java.time.LocalDate.now()) "HH:mm" else "dd.MM"
    return DateTimeFormatter.ofPattern(pattern, Locale.forLanguageTag("ru")).format(local)
}
