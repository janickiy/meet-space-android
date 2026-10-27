package com.meetspace.app.ui

import android.content.ClipData
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.meetspace.app.R
import com.meetspace.core.model.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

/** A bounded workspace: each pane scrolls independently, with a persistent message composer. */
@Composable
fun MessagingWorkspace(state: MessagingUiState, actions: MessagingActions, modifier: Modifier = Modifier,
    inboxRequested: Boolean = false, consumeInbox: () -> Unit = {},
    newChatRequested: Boolean = false, foldersRequested: Boolean = false,
    consumeWorkspaceRequest: () -> Unit = {}, serverOrigin: String? = null, onStartCall: (() -> Unit)? = null,
    personalCallLabel: String? = null, onResumeCall: () -> Unit = {},
    notificationBell: (@Composable () -> Unit)? = null, onNotifications: (() -> Unit)? = null,
    conversationNavigationRevision: Int = 0) {
    var newChat by remember { mutableStateOf(false) }
    var folders by remember { mutableStateOf(false) }
    var candidates by remember { mutableStateOf(false) }
    var members by remember { mutableStateOf(false) }
    var notifications by remember { mutableStateOf(false) }
    var deleteMessage by remember { mutableStateOf<ChatMessage?>(null) }
    LaunchedEffect(Unit) { actions.activate() }
    LaunchedEffect(inboxRequested) { if (inboxRequested) { actions.loadNotifications(); notifications = true; consumeInbox() } }
    LaunchedEffect(state.selectedId) { newChat = false; members = false; deleteMessage = null }
    LaunchedEffect(state.mutationRevision) { if (state.mutationRevision > 0) newChat = false }
    LaunchedEffect(state.selectedFolderId) { candidates = false }
    // Handle Home shortcuts after initial state effects so a restored selection cannot dismiss them.
    LaunchedEffect(newChatRequested, foldersRequested) {
        if (newChatRequested) { actions.searchUsers(""); newChat = true; consumeWorkspaceRequest() }
        else if (foldersRequested) { folders = true; consumeWorkspaceRequest() }
    }
    // A notification may target the already-selected chat behind folders or another local overlay.
    LaunchedEffect(conversationNavigationRevision) {
        if (conversationNavigationRevision > 0) {
            folders = false; candidates = false; members = false; newChat = false
            notifications = false; deleteMessage = null
        }
    }
    if (folders) { FoldersWorkspace(state, actions) { folders = false }; return }
    BackHandler(enabled = state.selectedId != null, onBack = actions.back)
    var searchOpen by remember { mutableStateOf(state.query.isNotBlank()) }
    var inviteDismissed by remember { mutableStateOf(false) }
    BoxWithConstraints(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("messaging-workspace")) {
        val wide = maxWidth >= 780.dp
        val compactHeight = maxHeight < 420.dp
        val sidebarWidth = 340.dp
        val smallPhone = maxWidth < 360.dp
        val listOnly = !wide && state.selectedId == null
        Column(Modifier.fillMaxSize()) {
            if (wide || listOnly) MessagingHeader(wide, searchOpen,
                onSearch = { searchOpen = !searchOpen; if (!searchOpen) actions.query("") }, notificationBell = notificationBell)
            if ((state.error != null || state.notice != null) && state.selectedId == null) {
                Box(Modifier.fillMaxWidth().heightIn(max = 120.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp)) {
                    MessagingNotice(state.error, state.notice, actions.clearMessage)
                }
            }
            val stage = Modifier.weight(1f).fillMaxWidth()
            val list: @Composable () -> Unit = {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                    ConversationList(state, actions, searchOpen, serverOrigin, inviteDismissed, { inviteDismissed = true }, smallPhone,
                        manageFolders = { folders = true }, addItems = { actions.searchFolderCandidates(""); candidates = true },
                        onNotifications = onNotifications ?: { actions.loadNotifications(); notifications = true })
                    if (!wide) Surface(onClick = { actions.searchUsers(""); newChat = true }, enabled = !state.busy,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 17.dp).heightIn(min = 53.dp).testTag("messaging-new"),
                        shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary, shadowElevation = 6.dp) {
                        Column(Modifier.padding(horizontal = 17.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
                            Icon(Icons.Outlined.Add, null, Modifier.size(23.dp))
                            Text("Новый чат", fontSize = 13.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            val detail: @Composable () -> Unit = {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    // The tablet title and any workspace notice also consume viewport height.
                    // Size the composer/history from the remaining thread, not the outer screen.
                    val compactThread = compactHeight || maxHeight.value / LocalDensity.current.fontScale < 420f
                    ConversationDetail(state, actions, showBack = !wide, compactHeight = compactThread,
                        showMembers = { actions.loadMembers(); members = true }, delete = { deleteMessage = it }, onStartCall = onStartCall,
                        onNewChat = if (wide) ({ actions.searchUsers(""); newChat = true }) else null,
                        personalCallLabel = personalCallLabel, onResumeCall = onResumeCall)
                }
            }
            if (wide) {
                Row(stage.padding(horizontal = 24.dp).testTag("messaging-two-pane")) {
                    Box(Modifier.width(sidebarWidth).fillMaxHeight()) { list() }
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Box(Modifier.weight(1f).fillMaxHeight()) { detail() }
                }
            } else Box(stage.testTag("messaging-single-pane")) {
                if (state.selectedId != null) detail() else list()
            }
        }
    }
    if (newChat) NewChatDialog(state, actions) { newChat = false }

    if (candidates && state.selectedFolderId != null) FolderCandidatesDialog(state, actions) { candidates = false }
    if (members && state.detail.data != null) MembersDialog(state, actions) { members = false }
    if (notifications) NotificationsDialog(state, actions) { notifications = false }
    deleteMessage?.let { message ->
        AlertDialog(onDismissRequest = { if (!state.busy) deleteMessage = null }, title = { Text("Удалить сообщение?") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Сообщение будет удалено для участников чата.")
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { Button(onClick = { actions.delete(message); deleteMessage = null }, enabled = !state.busy, modifier = Modifier.testTag("message-confirm-delete")) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { deleteMessage = null }, enabled = !state.busy) { Text("Отмена") } })
    }
}

@Composable
private fun MessagingHeader(wide: Boolean, searchOpen: Boolean, onSearch: () -> Unit,
    notificationBell: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 75.dp).background(MaterialTheme.colorScheme.surface)
        .padding(start = if (wide) 30.dp else 20.dp, end = if (wide) 24.dp else 12.dp, bottom = 0.dp)
        .testTag("messaging-header"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (wide) Image(painterResource(R.drawable.ic_meetspace), null, Modifier.size(32.dp))
        Text("Чаты", Modifier.weight(1f).semantics { heading() }, fontSize = if (wide) 25.sp else 23.sp,
            lineHeight = 31.sp, letterSpacing = (-.5).sp, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (wide) 8.dp else 0.dp)) {
            IconButton(onClick = onSearch, modifier = Modifier.testTag("messaging-search-toggle").semantics { stateDescription = if (searchOpen) "Поиск открыт" else "Поиск скрыт" }) {
                Icon(if (searchOpen) Icons.Outlined.Close else Icons.Outlined.Search, if (searchOpen) "Закрыть поиск чатов" else "Поиск чатов", Modifier.size(27.dp))
            }
            notificationBell?.invoke()
        }
    }
}

@Composable
private fun ConversationList(state: MessagingUiState, actions: MessagingActions, searchOpen: Boolean,
    serverOrigin: String?, inviteDismissed: Boolean, dismissInvite: () -> Unit, smallPhone: Boolean,
    manageFolders: () -> Unit, addItems: () -> Unit, onNotifications: () -> Unit) {
    var folderMenu by remember { mutableStateOf(false) }
    val listScroll = rememberLazyListState()
    LaunchedEffect(searchOpen) { if (searchOpen) listScroll.scrollToItem(0) }
    val selectedFolder = state.folders.data.orEmpty().find { it.id == state.selectedFolderId }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 17.dp), horizontalArrangement = Arrangement.spacedBy(23.dp)) {
            ChatFilter.entries.forEach { filter ->
                MessagingFilter(when (filter) { ChatFilter.ALL -> "Все"; ChatFilter.DIRECT -> "Личные"; ChatFilter.GROUP -> "Группы"; ChatFilter.UNREAD -> "Новые" },
                    state.selectedFolderId == null && state.filter == filter, "chat-filter-${filter.name.lowercase()}",
                    count = if (filter == ChatFilter.UNREAD) state.conversations.data?.count { it.unreadCount > 0 }?.takeIf { it > 0 } else null,
                    onClick = { actions.filter(filter) })
            }
            Box {
                MessagingFilter(selectedFolder?.name ?: "Папки", state.selectedFolderId != null, "chat-folder-filter", dropdown = true) { folderMenu = true }
                DropdownMenu(expanded = folderMenu, onDismissRequest = { folderMenu = false }) {
                    DropdownMenuItem(text = { Text("Все чаты") }, onClick = { folderMenu = false; actions.filter(ChatFilter.ALL) })
                    state.folders.data.orEmpty().forEach { folder ->
                        DropdownMenuItem(text = { Text(folder.name) }, onClick = { folderMenu = false; actions.selectFolder(folder.id) }, trailingIcon = { Text(folder.itemCount.toString()) })
                    }
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Управление папками") }, onClick = { folderMenu = false; manageFolders() },
                        leadingIcon = { Icon(Icons.Outlined.FolderOpen, null) }, modifier = Modifier.testTag("folders-manage"))
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Уведомления${if (state.notificationUnread > 0) " · ${countLabel(state.notificationUnread)}" else ""}") },
                        leadingIcon = { Icon(Icons.Outlined.Notifications, null) }, onClick = { folderMenu = false; onNotifications() }, modifier = Modifier.testTag("messaging-notifications"))
                    DropdownMenuItem(text = { Text("Обновить чаты") }, leadingIcon = { Icon(Icons.Outlined.Refresh, null) },
                        onClick = { folderMenu = false; actions.refresh() }, modifier = Modifier.testTag("conversations-refresh"))
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        val loading = if (selectedFolder != null) state.folderEntries.loading else state.conversations.loading
        val error = if (selectedFolder != null) state.folderEntries.error else state.conversations.error
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("conversation-list"), state = listScroll,
            contentPadding = PaddingValues(start = if (smallPhone) 12.dp else 15.dp, end = if (smallPhone) 12.dp else 15.dp, top = 15.dp, bottom = 80.dp)) {
            if (searchOpen) item("search") {
                WorkspaceSearchField(state.query, actions.query, if (selectedFolder != null) "Найти в папке" else "Найти чат",
                    "conversation-search", Modifier.padding(bottom = 12.dp), placeholder = "Найти человека или чат")
            }
            if (!inviteDismissed && !serverOrigin.isNullOrBlank()) item("invite") { ChatInviteBanner(serverOrigin, dismissInvite) }
            if (selectedFolder != null) item("folder-heading") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(selectedFolder.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    IconButton(onClick = addItems, enabled = !state.busy, modifier = Modifier.testTag("folder-add-items")) { Icon(Icons.Outlined.Add, "Добавить чаты и встречи") }
                }
            }
            if (state.realtimeLabel != "Обновления подключены") item("connection-status") {
                Text(state.realtimeLabel, Modifier.padding(vertical = 8.dp).testTag("messaging-realtime"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.folders.error != null) item("folders-error") { Text(state.folders.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (loading || error != null) item("list-status") { LoadingStatus(loading, error, actions.refresh) }
            if (selectedFolder != null) {
                val entries = state.folderEntries.data.orEmpty()
                if (entries.isEmpty() && state.folderEntries.data != null && !loading && error == null) item { EmptyChat("В папке пока нет чатов и встреч") }
                items(entries, key = { "${it.type}:${it.id}" }) { entry -> FolderEntryRow(entry, state, actions, selectedFolder.id) }
                if (state.folderCursor != null) item { MoreButton("Ещё в папке", "folder-more", loading, actions.moreFolderItems) }
            } else {
                val conversations = state.conversations.data.orEmpty()
                if (conversations.isEmpty() && state.conversations.data != null && !loading && error == null) item { EmptyChat(if (state.query.isBlank()) "Пока нет чатов в этом разделе" else "Чаты не найдены") }
                items(conversations, key = { it.id }) { conversation -> ConversationRow(conversation, state.selectedId == conversation.id, smallPhone, state.drafts[conversation.id]) { actions.open(conversation.id) } }
                if (state.conversationCursor != null) item { MoreButton("Загрузить ещё чаты", "conversations-more", loading, actions.moreConversations) }
            }
        }
    }
}

@Composable
private fun MessagingFilter(label: String, active: Boolean, tag: String, count: Int? = null, dropdown: Boolean = false, onClick: () -> Unit) {
    Column(Modifier.width(IntrinsicSize.Min).heightIn(min = 52.dp).clickable(role = Role.Tab, onClick = onClick).testTag(tag).semantics { selected = active }, verticalArrangement = Arrangement.Bottom) {
        Row(Modifier.heightIn(min = 49.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(label, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            count?.let { Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) { Text(it.toString(), Modifier.padding(horizontal = 5.dp, vertical = 1.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            if (dropdown) Icon(Icons.Outlined.ExpandMore, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(Modifier.fillMaxWidth().height(3.dp).background(if (active) MaterialTheme.colorScheme.primary else Color.Transparent))
    }
}

@Composable
private fun ChatInviteBanner(origin: String, dismiss: () -> Unit) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember(origin) { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 14.dp).testTag("chat-invite")) {
        Surface(onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("MeetSpace", origin))); copied = true } },
            color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 84.dp).testTag("chat-invite-copy")) {
            Row(Modifier.padding(start = 15.dp, end = 22.dp, top = 19.dp, bottom = 19.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(if (copied) "Ссылка скопирована" else "Пригласите свою команду", fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(5.dp))
                    Text("Общайтесь и встречайтесь в MeetSpace", fontSize = 11.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Outlined.PersonAddAlt, null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }
        IconButton(onClick = dismiss, modifier = Modifier.align(Alignment.TopEnd).offset(x = 12.dp, y = (-12).dp).testTag("chat-invite-dismiss")) {
            Icon(Icons.Outlined.Close, "Скрыть приглашение", Modifier.size(24.dp).background(MaterialTheme.colorScheme.surface, CircleShape).padding(3.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ConversationRow(conversation: ChatConversation, selected: Boolean, smallPhone: Boolean = false, draft: String? = null, open: () -> Unit) {
    Surface(onClick = open, modifier = Modifier.fillMaxWidth().heightIn(min = if (conversation.type == "group") 98.dp else 87.dp).testTag("conversation-${conversation.id}"),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
        Row(Modifier.padding(vertical = 15.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            ChatAvatar(conversation.title, conversation.type == "group", if (smallPhone) 43.dp else 51.dp, if (smallPhone) 46.dp else 53.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(conversation.title.ifBlank { "Чат" }, fontSize = if (smallPhone) 13.sp else 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (conversation.type == "group") Text("Группа · ${memberCountLabel(conversation.memberCount)}", fontSize = 10.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(draft?.let { "Черновик: $it" } ?: conversation.preview.ifBlank { "Сообщений пока нет" }, fontSize = if (smallPhone) 12.sp else 13.sp, lineHeight = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                conversation.lastMessageAt?.let { Text(timeLabel(it), fontSize = 10.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (conversation.unreadCount > 0) Badge(containerColor = MaterialTheme.colorScheme.primary) { Text(countLabel(conversation.unreadCount), fontSize = 10.sp) }
                if (!conversation.notificationsEnabled) Icon(Icons.Outlined.NotificationsOff, "Уведомления чата выключены", Modifier.size(16.dp))
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun FolderEntryRow(entry: FolderEntry, state: MessagingUiState, actions: MessagingActions, folderId: String) {
    Column {
        entry.conversation?.let { ConversationRow(it, state.selectedId == it.id) { actions.open(it.id) } }
        entry.meeting?.let { meeting ->
            Card(onClick = { actions.openMeeting(meeting.id) }, modifier = Modifier.fillMaxWidth().testTag("folder-meeting-${meeting.id}"), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.VideoCall, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text(meeting.title, style = MaterialTheme.typography.titleSmall)
                        Text("Встреча · ${dateLabel(meeting.scheduledAt ?: meeting.createdAt)}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        TextButton(onClick = { actions.folderMembership(folderId, entry.type, entry.id, false) }, enabled = !state.busy, modifier = Modifier.align(Alignment.End).testTag("folder-remove-${entry.id}")) { Text("Убрать из папки") }
    }
}

@Composable
private fun ConversationDetail(state: MessagingUiState, actions: MessagingActions, showBack: Boolean, compactHeight: Boolean,
    showMembers: () -> Unit, delete: (ChatMessage) -> Unit, onStartCall: (() -> Unit)?, onNewChat: (() -> Unit)?,
    personalCallLabel: String?, onResumeCall: () -> Unit) {
    if (state.selectedId == null) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.Forum, null, Modifier.size(52.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("Выберите разговор", style = MaterialTheme.typography.titleLarge)
                    Text("Личные сообщения и обсуждения команды — рядом с вашими встречами.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (onNewChat != null) ThreadCreateAction(state.busy, onNewChat)
        }
        return
    }
    val conversation = state.detail.data
    var historyDialog by remember(state.selectedId) { mutableStateOf<String?>(null) }
    var clearHistory by remember(state.selectedId) { mutableStateOf<Boolean?>(null) }
    historyDialog?.let { value -> ChatHistoryDialog(state, actions, value == "pins") { historyDialog = null } }
    clearHistory?.let { hide -> AlertDialog(onDismissRequest = { clearHistory = null }, title = { Text(if (hide) "Удалить чат?" else "Очистить историю?") },
        text = { Text("История будет скрыта только для вас. У собеседника сообщения сохранятся.") },
        confirmButton = { TextButton({ actions.clearConversation(hide); clearHistory = null }, enabled = !state.busy) { Text("Подтвердить") } },
        dismissButton = { TextButton({ clearHistory = null }) { Text("Отмена") } }) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("conversation-detail")) {
        var options by remember(state.selectedId) { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = if (compactHeight) 4.dp else if (showBack) 12.dp else 18.dp, vertical = if (compactHeight) 0.dp else if (showBack) 12.dp else 14.dp)
            .testTag("thread-header"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (compactHeight) 4.dp else if (showBack) 6.dp else 11.dp)) {
            if (showBack) IconButton(onClick = actions.back, modifier = Modifier.testTag("conversation-back")) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "К списку чатов") }
            if (!compactHeight) ChatAvatar(conversation?.title.orEmpty(), conversation?.type == "group", if (showBack) 32.dp else 40.dp)
            Column(Modifier.weight(1f)) {
                Text(conversation?.title?.ifBlank { "Чат" } ?: "Загружаем чат…", fontSize = if (showBack) 13.sp else 14.sp,
                    lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = if (compactHeight) 1 else 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics {
                        if (compactHeight && conversation != null) stateDescription = if (conversation.type == "group") "Группа, ${memberCountLabel(conversation.memberCount)}"
                            else when (state.peerOnline) { true -> "В сети"; false -> "Не в сети"; null -> "Личный чат" }
                    })
                if (!compactHeight) conversation?.let {
                    Text(if (it.type == "group") "Группа · ${memberCountLabel(it.memberCount)}" else when (state.peerOnline) { true -> "В сети"; false -> "Не в сети"; null -> "Личный чат" },
                        fontSize = 11.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (onStartCall != null && conversation?.type == "direct") IconButton(onClick = onStartCall, enabled = !state.busy,
                modifier = Modifier.size(48.dp).testTag("conversation-call")) {
                Icon(Icons.Outlined.Phone, "Позвонить", Modifier.size(if (showBack) 38.dp else 42.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(if (showBack) 12.dp else 13.dp)).padding(10.dp), tint = MaterialTheme.colorScheme.primary)
            }
            // Keep creation reachable on short tablet windows without consuming history height.
            if (onNewChat != null && compactHeight) IconButton(onClick = onNewChat, enabled = !state.busy, modifier = Modifier.testTag("messaging-new")) { Icon(Icons.Outlined.Add, "Новый чат") }
            IconButton({ historyDialog = "search" }, Modifier.testTag("chat-search-messages")) { Icon(Icons.Outlined.Search, "Поиск по сообщениям", Modifier.size(20.dp)) }
            Box {
                IconButton(onClick = { options = true }, modifier = Modifier.testTag("conversation-options")) { Icon(Icons.Outlined.MoreHoriz, "Информация и действия с чатом") }
                DropdownMenu(expanded = options, onDismissRequest = { options = false }) {
                    DropdownMenuItem(text = { Text("Закреплённые сообщения") }, leadingIcon = { Icon(Icons.Outlined.PushPin, null) }, onClick = { options = false; actions.loadPins(); historyDialog = "pins" })
                    if (conversation?.type == "direct") {
                        DropdownMenuItem(text = { Text("Очистить историю") }, onClick = { options = false; clearHistory = false })
                        DropdownMenuItem(text = { Text("Удалить чат") }, onClick = { options = false; clearHistory = true })
                    }
                    DropdownMenuItem(text = { Text("Обновить сообщения") }, leadingIcon = { Icon(Icons.Outlined.Refresh, null) }, enabled = !state.messages.loading,
                        onClick = { options = false; actions.refreshMessages() }, modifier = Modifier.testTag("messages-refresh"))
                    if (conversation?.type == "group") DropdownMenuItem(text = { Text("Участники и настройки группы") }, leadingIcon = { Icon(Icons.Outlined.Group, null) },
                        onClick = { options = false; showMembers() }, modifier = Modifier.testTag("group-members"))
                    if (conversation?.type == "direct") DropdownMenuItem(text = { Text(if (conversation.notificationsEnabled) "Отключить уведомления" else "Включить уведомления") }, enabled = !state.busy,
                        leadingIcon = { Icon(if (conversation.notificationsEnabled) Icons.Outlined.NotificationsOff else Icons.Outlined.NotificationsActive, null) },
                        onClick = { options = false; actions.conversationNotifications(!conversation.notificationsEnabled) }, modifier = Modifier.testTag("conversation-notifications"))
                    if (conversation != null && conversation.unreadCount > 0) DropdownMenuItem(text = { Text("Отметить чат прочитанным") }, leadingIcon = { Icon(Icons.Outlined.DoneAll, null) },
                        enabled = !state.busy && !state.messages.loading, onClick = { options = false; actions.markRead() }, modifier = Modifier.testTag("conversation-mark-read"))
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ChatPinBar(state, actions) { historyDialog = "pins"; actions.loadPins() }
        if (personalCallLabel != null && conversation?.type == "direct") Surface(onClick = onResumeCall,
            color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth().testTag("personal-call-chat-banner")) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.Phone, null, Modifier.size(22.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Личный звонок", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text(personalCallLabel, fontSize = 10.sp)
                }
                Text("Вернуться ↗", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        if (!compactHeight) {
            Column(Modifier.fillMaxWidth().heightIn(max = 120.dp).verticalScroll(rememberScrollState())) {
                if (state.error != null || state.notice != null) Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    MessagingNotice(state.error, state.notice, actions.clearMessage)
                }
                LoadingStatus(state.detail.loading, state.detail.error, actions.refreshMessages, Modifier.padding(horizontal = 12.dp))
                LoadingStatus(state.messages.loading, state.messages.error, actions.refreshMessages, Modifier.padding(horizontal = 12.dp))
            }
        }
        val messages = remember(state.messages.data) { state.messages.data.orEmpty().sortedByDescending { it.sequence } }
        val scroll = rememberLazyListState()
        LaunchedEffect(state.focusedMessageId, state.messages.data) {
            state.focusedMessageId?.let { id -> messages.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { scroll.scrollToItem(it) } }
        }
        LaunchedEffect(state.selectedId, messages.firstOrNull()?.id) {
            if (!state.viewingContext && messages.isNotEmpty() && (scroll.firstVisibleItemIndex <= 1 || messages.first().senderId == state.selfId)) scroll.scrollToItem(0)
        }
        LazyColumn(state = scroll, reverseLayout = true, modifier = Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.background).testTag("message-history"),
            contentPadding = PaddingValues(horizontal = if (showBack) 16.dp else 24.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            if (compactHeight && (state.error != null || state.notice != null)) item("workspace-notice") { MessagingNotice(state.error, state.notice, actions.clearMessage) }
            if (compactHeight && (state.detail.loading || state.detail.error != null || state.messages.loading || state.messages.error != null)) item("history-status") {
                Column {
                    LoadingStatus(state.detail.loading, state.detail.error, actions.refreshMessages)
                    LoadingStatus(state.messages.loading, state.messages.error, actions.refreshMessages)
                }
            }
            if (messages.isEmpty() && state.messages.data != null && !state.messages.loading && state.messages.error == null) item { EmptyChat("Начните разговор") }
            itemsIndexed(messages, key = { _, message -> message.id }) { index, message ->
                Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    val day = message.createdAt.atZone(ZoneId.systemDefault()).toLocalDate()
                    val previousDay = messages.getOrNull(index + 1)?.createdAt?.atZone(ZoneId.systemDefault())?.toLocalDate()
                    if (day != previousDay) Text(day.format(DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("ru"))), Modifier.fillMaxWidth(),
                        fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    MessageBubble(message, state.selfId, state.busy || state.sending || state.pendingSend || state.attachmentBusy, actions, delete, showBack)
                }
            }
            if (state.messageCursor != null) item { MoreButton("Предыдущие сообщения", "messages-more", state.messages.loading, actions.moreMessages) }
        }
        if (state.viewingContext) TextButton(actions.latestMessages, Modifier.fillMaxWidth().testTag("messages-latest")) { Text("Вернуться к новым сообщениям") }
        if (onNewChat != null && !compactHeight) ThreadCreateAction(state.busy, onNewChat)
        HorizontalDivider()
        if (conversation != null) MessageComposer(state, actions, compactHeight, showBack)
    }
}

@Composable
private fun ThreadCreateAction(busy: Boolean, onNewChat: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 12.dp).testTag("thread-create-actions"), horizontalArrangement = Arrangement.End) {
        Button(onClick = onNewChat, enabled = !busy, shape = RoundedCornerShape(15.dp),
            contentPadding = PaddingValues(horizontal = 17.dp, vertical = 10.dp), modifier = Modifier.heightIn(min = 48.dp).testTag("messaging-new")) {
            Icon(Icons.Outlined.Add, null, Modifier.size(19.dp))
            Spacer(Modifier.width(8.dp))
            Text("Новый чат", fontSize = 13.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage, selfId: String, busy: Boolean, actions: MessagingActions, delete: (ChatMessage) -> Unit, compactWidth: Boolean) {
    if (message.kind == "call") {
        message.call?.let { CallHistoryBubble(it, selfId, message.createdAt, message.id) }
        return
    }
    val own = message.senderId == selfId
    val deleted = message.deletedAt != null
    var menu by remember(message.id) { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val bubbleWidth = maxWidth * if (compactWidth) .93f else .9f
        val avatarSize = if (compactWidth) 26.dp else 30.dp
        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (own) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Top) {
            if (!own && !deleted) { ChatAvatar(message.senderName, false, avatarSize); Spacer(Modifier.width(10.dp)) }
            Box(Modifier.widthIn(max = if (own) bubbleWidth else bubbleWidth - avatarSize - 10.dp).testTag("message-${message.id}")) {
                Surface(modifier = if (deleted) Modifier else Modifier.testTag("message-menu-${message.id}").clickable(enabled = !busy, onClickLabel = "Действия с сообщением") { menu = true },
                    shape = RoundedCornerShape(topStart = 17.dp, topEnd = 17.dp, bottomStart = if (own) 17.dp else 4.dp, bottomEnd = if (own) 4.dp else 17.dp),
                    color = if (own) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    border = if (own) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Column(Modifier.padding(horizontal = if (compactWidth) 13.dp else 15.dp, vertical = if (compactWidth) 11.dp else 13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        if (!own && !deleted) Text(message.senderName.ifBlank { "Участник" }, fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                        if (!deleted) message.replyPreview?.let { reply ->
                            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().testTag("message-reply-preview-${message.id}")) {
                                Column(Modifier.padding(10.dp)) {
                                    Text(reply.senderName.ifBlank { "Ответ на сообщение" }, style = MaterialTheme.typography.labelSmall)
                                    Text(if (reply.deleted) "Сообщение удалено" else reply.text.ifBlank { "Вложение" }, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                        if (deleted || message.text.isNotBlank()) Text(if (deleted) "Сообщение удалено" else message.text, fontSize = 12.sp, lineHeight = 20.sp,
                            color = if (deleted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                        if (!deleted) message.attachments.forEach { attachment ->
                            Row(Modifier.widthIn(min = 180.dp).heightIn(min = 48.dp).clickable(enabled = !busy, role = Role.Button) { actions.download(attachment) }.testTag("attachment-${attachment.id}")
                                .padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Description, null, Modifier.size(40.dp, 45.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(8.dp)).padding(8.dp), tint = MaterialTheme.colorScheme.primary)
                                Column(Modifier.weight(1f, fill = false)) {
                                    Text(attachment.filename, fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(sizeLabel(attachment.size), fontSize = 10.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Icon(Icons.Outlined.Download, "Сохранить файл", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Text("${timeLabel(message.createdAt)}${if (!deleted && message.version > 1) " · изменено" else ""}", fontSize = 10.sp, lineHeight = 15.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.End))
                    }
                }
                if (menu && !deleted) MessageActionsDialog(message, own, actions, { delete(message) }) { menu = false }
            }
        }
    }
}

@Composable
private fun CallHistoryBubble(call: DirectCallHistory, selfId: String, createdAt: Instant, messageId: String) {
    val outgoing = call.callerUserId == selfId
    val missed = !outgoing && call.status in setOf("missed", "cancelled")
    val result = when (call.status) {
        "ringing" -> if (outgoing) "Вы звоните…" else "Входящий вызов"
        "accepted" -> "Разговор идёт"
        "declined" -> if (outgoing) "Вызов отклонён" else "Вы отклонили вызов"
        "cancelled" -> if (outgoing) "Вы отменили вызов" else "Пропущенный вызов"
        "missed" -> if (outgoing) "Нет ответа" else "Пропущенный вызов"
        else -> "Завершён · ${call.durationSec / 60}:${(call.durationSec % 60).toString().padStart(2, '0')}"
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start) {
        Surface(color = if (outgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(17.dp), modifier = Modifier.testTag("call-history-$messageId")) {
            Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(if (outgoing) Icons.Outlined.CallMade else Icons.Outlined.CallReceived, null, Modifier.size(23.dp),
                    tint = if (missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (outgoing) "Исходящий звонок" else "Входящий звонок", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text(result, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(timeLabel(createdAt), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun MessageComposer(state: MessagingUiState, actions: MessagingActions, compactHeight: Boolean, compactWidth: Boolean) {
    val busy = state.sending || state.attachmentBusy
    val hasContext = state.editing != null || state.replyTo != null || state.attachments.isNotEmpty() || state.attachmentBusy || state.pendingSend
    var contextOpen by remember(state.selectedId) { mutableStateOf(false) }
    val fieldLabel = if (state.editing != null) "Изменить сообщение" else "Сообщение"
    val fieldFontSize = if (compactWidth) 13.sp else 14.sp
    val fieldLineHeight = if (compactWidth) 20.sp else 21.sp
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(
        start = if (compactHeight) 4.dp else if (compactWidth) 10.dp else 18.dp,
        end = if (compactHeight) 4.dp else if (compactWidth) 10.dp else 18.dp,
        top = if (compactHeight) 4.dp else if (compactWidth) 10.dp else 14.dp,
        bottom = if (compactHeight) 4.dp else if (compactWidth) 19.dp else 14.dp), verticalArrangement = Arrangement.spacedBy(if (compactHeight) 0.dp else 6.dp)) {
        if (hasContext && !compactHeight) {
            Column(Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MessageComposeContext(state, actions)
            }
        }
        if (compactHeight && state.attachmentBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(onClick = actions.pickAttachment, enabled = !busy && !state.pendingSend && state.editing == null, modifier = Modifier.testTag("message-attach")) { Icon(Icons.Outlined.AttachFile, "Прикрепить файл") }
            if (compactHeight && hasContext) IconButton(onClick = { contextOpen = true }, modifier = Modifier.testTag("message-compose-context")) {
                BadgedBox(badge = { if (state.attachments.isNotEmpty()) Badge { Text(state.attachments.size.toString()) } }) {
                    Icon(if (state.replyTo != null) Icons.AutoMirrored.Outlined.Reply else if (state.editing != null) Icons.Outlined.Edit else Icons.Outlined.MoreHoriz, "Ответ, вложения и состояние отправки")
                }
            }
            // Keep vertical scrolling and focus when the window or keyboard changes height.
            // Compact mode limits visible lines without changing the internal scroll orientation.
            BasicTextField(value = state.draft, onValueChange = { actions.draft(it.take(8000)) },
                enabled = !state.sending && !state.pendingSend, singleLine = false, maxLines = if (compactHeight) 1 else 4,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = fieldFontSize, lineHeight = fieldLineHeight, color = MaterialTheme.colorScheme.onSurface), cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("message-draft").semantics { contentDescription = fieldLabel },
                decorationBox = { field -> Box(Modifier.padding(horizontal = if (compactWidth) 8.dp else 10.dp, vertical = if (compactWidth) 10.dp else 12.dp), contentAlignment = Alignment.CenterStart) {
                    if (state.draft.isEmpty()) Text(if (state.editing != null) "Изменить сообщение…" else "Написать сообщение…", fontSize = fieldFontSize, lineHeight = fieldLineHeight,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    field()
                } })
            FilledIconButton(onClick = actions.send, enabled = !busy && (state.draft.isNotBlank() || state.attachments.isNotEmpty() || state.pendingSend),
                shape = RoundedCornerShape(14.dp), modifier = Modifier.size(48.dp).testTag("message-send")) {
                if (state.sending) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                else Icon(if (state.editing != null) Icons.Outlined.Check else Icons.Outlined.ArrowUpward, if (state.pendingSend) "Повторить отправку" else if (state.editing != null) "Сохранить сообщение" else "Отправить сообщение")
            }
        }
    }
    if (compactHeight && contextOpen) AlertDialog(onDismissRequest = { contextOpen = false }, title = { Text("Перед отправкой") },
        text = { Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MessageComposeContext(state, actions)
        } }, confirmButton = { TextButton(onClick = { contextOpen = false }) { Text("Готово") } })
}

@Composable
private fun MessageComposeContext(state: MessagingUiState, actions: MessagingActions) {
    val busy = state.sending || state.attachmentBusy
    (state.editing ?: state.replyTo)?.let { original ->
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp)).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (state.editing != null) "Редактирование" else "Ответ: ${original.senderName}", style = MaterialTheme.typography.labelMedium)
                Text(if (original.deletedAt != null) "Сообщение удалено" else original.text.ifBlank { "Вложение" }, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = actions.cancelCompose, enabled = !busy && !state.pendingSend, modifier = Modifier.testTag("message-cancel-compose")) { Icon(Icons.Outlined.Close, "Отменить ответ или редактирование") }
        }
    }
    state.attachments.forEach { attachment ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("pending-attachment-${attachment.id}")) {
            Icon(Icons.Outlined.AttachFile, null)
            Text("${attachment.filename} · ${sizeLabel(attachment.size)}", Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            IconButton(onClick = { actions.removeAttachment(attachment.id) }, enabled = !busy && !state.pendingSend) { Icon(Icons.Outlined.Close, "Убрать вложение ${attachment.filename}") }
        }
    }
    if (state.attachmentBusy) Text("Загружаем файл…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    if (state.pendingSend) Text("Ответ сервера не получен. Повтор использует тот же запрос и не создаёт копию сообщения.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("message-pending-send"))
}

@Composable
private fun NewChatDialog(state: MessagingUiState, actions: MessagingActions, dismiss: () -> Unit) {
    var group by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    AlertDialog(onDismissRequest = { if (!state.busy) dismiss() }, title = { Text(if (group) "Новая группа" else "Новый личный чат") },
        shape = RoundedCornerShape(24.dp), containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp,
        properties = DialogProperties(usePlatformDefaultWidth = false), modifier = Modifier.imePadding().padding(horizontal = 16.dp).widthIn(max = 560.dp).fillMaxWidth(),
        text = { Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !group, onClick = { group = false; selected = emptyMap() }, label = { Text("Личный") }, enabled = !state.busy, modifier = Modifier.testTag("create-chat-direct"))
                FilterChip(selected = group, onClick = { group = true; selected = emptyMap() }, label = { Text("Группа") }, enabled = !state.busy, modifier = Modifier.testTag("create-chat-group"))
            }
            if (group) {
                OutlinedTextField(value = name, onValueChange = { name = it.take(200) }, label = { Text("Название группы") }, shape = RoundedCornerShape(12.dp), enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("create-group-name"))
                OutlinedTextField(value = description, onValueChange = { description = it.take(2000) }, label = { Text("Описание · необязательно") }, shape = RoundedCornerShape(12.dp), minLines = 2, maxLines = 4, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("create-group-description"))
                if (selected.isNotEmpty()) Text("Выбраны: ${selected.values.joinToString()}", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(value = state.userQuery, onValueChange = actions.searchUsers, label = { Text("Найти человека") }, supportingText = { Text("Имя или точный email") }, shape = RoundedCornerShape(12.dp), singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("chat-user-search"))
            if (state.users.loading || state.users.error != null) LoadingStatus(state.users.loading, state.users.error, { actions.searchUsers(state.userQuery) })
            state.users.data.orEmpty().filter { it.id != state.selfId }.forEach { peer ->
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background, RoundedCornerShape(12.dp)).padding(8.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (group) Checkbox(checked = peer.id in selected, onCheckedChange = { checked -> selected = if (checked) selected + (peer.id to peer.displayName) else selected - peer.id }, enabled = !state.busy, modifier = Modifier.testTag("group-select-${peer.id}"))
                    Text(peer.displayName, Modifier.weight(1f))
                    if (!group) TextButton(onClick = { actions.createDirect(peer.id) }, enabled = !state.busy, modifier = Modifier.testTag("direct-create-${peer.id}")) { Text("Написать") }
                }
            }
            if (state.users.data?.isEmpty() == true && !state.users.loading && state.users.error == null) Text(if (state.userQuery.isBlank()) "Введите имя собеседника" else "Пользователи не найдены")
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { if (group) Button(onClick = { actions.createGroup(name.trim(), description.trim(), selected.keys.toList()) }, enabled = !state.busy && name.isNotBlank() && selected.isNotEmpty(),
            shape = RoundedCornerShape(12.dp), modifier = Modifier.heightIn(min = 48.dp).testTag("create-group-submit")) { Text(if (state.busy) "Создаём…" else "Создать группу") } },
        dismissButton = { TextButton(onClick = dismiss, enabled = !state.busy) { Text("Закрыть") } })
}

@Composable
internal fun FoldersDialog(state: MessagingUiState, actions: MessagingActions, dismiss: () -> Unit) {
    var editing by remember { mutableStateOf<ChatFolder?>(null) }
    var name by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<ChatFolder?>(null) }
    LaunchedEffect(state.mutationRevision) { if (state.mutationRevision > 0) { editing = null; name = ""; deleting = null } }
    val folders = state.folders.data.orEmpty()
    Dialog(onDismissRequest = { if (!state.busy) dismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxWidth().imePadding().padding(horizontal = 16.dp, vertical = 24.dp), contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth().heightIn(max = maxHeight.coerceAtMost(640.dp)).testTag("folders-dialog"),
                shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(Modifier.padding(20.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Outlined.FolderOpen, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                        Text("Папки", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
                        IconButton(onClick = dismiss, enabled = !state.busy, modifier = Modifier.testTag("folders-close")) {
                            Icon(Icons.Outlined.Close, "Закрыть управление папками", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Column(
                        Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).testTag("folders-dialog-content"),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text("Собирайте чаты и встречи по темам. Папки видны только вам.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (state.folders.loading || state.folders.error != null) LoadingStatus(state.folders.loading, state.folders.error, actions.refresh)
                        if (folders.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                folders.forEachIndexed { index, folder ->
                                    FolderManagementRow(folder, index, folders, state.busy, editing?.id == folder.id,
                                        rename = { editing = folder; name = folder.name }, delete = { deleting = folder },
                                        reorder = actions.reorderFolders)
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (editing != null) "Переименовать папку" else "Новая папка", style = MaterialTheme.typography.titleSmall)
                            OutlinedTextField(
                                value = name, onValueChange = { name = it.take(100) }, singleLine = true,
                                label = { Text(if (editing != null) "Новое название" else "Название папки") },
                                supportingText = { Text("До 50 символов") }, enabled = !state.busy,
                                shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().testTag("folder-name"),
                            )
                            Button(
                                onClick = { val folder = editing; if (folder == null) actions.createFolder(name.trim()) else actions.renameFolder(folder.id, name.trim()) },
                                enabled = !state.busy && name.trim().codePointCount(0, name.trim().length) in 1..50,
                                shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("folder-save"),
                            ) {
                                Icon(if (editing != null) Icons.Outlined.Check else Icons.Outlined.Add, null, Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(if (editing != null) "Сохранить название" else "Создать папку")
                            }
                            if (editing != null) TextButton(onClick = { editing = null; name = "" }, enabled = !state.busy,
                                modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Отменить переименование") }
                        }
                        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                    }
                    TextButton(onClick = dismiss, enabled = !state.busy, modifier = Modifier.align(Alignment.End).padding(top = 8.dp)) { Text("Готово") }
                }
            }
        }
    }
    deleting?.let { folder -> AlertDialog(onDismissRequest = { if (!state.busy) deleting = null }, title = { Text("Удалить папку «${folder.name}»?") },
        text = { Text("Чаты и встречи сохранятся. Удалится только папка.") },
        confirmButton = { Button(onClick = { actions.deleteFolder(folder.id) }, enabled = !state.busy, modifier = Modifier.testTag("folder-confirm-delete")) { Text("Удалить папку") } },
        dismissButton = { TextButton(onClick = { deleting = null }, enabled = !state.busy) { Text("Отмена") } }) }
}

@Composable
private fun FolderManagementRow(
    folder: ChatFolder, index: Int, folders: List<ChatFolder>, busy: Boolean, editing: Boolean,
    rename: () -> Unit, delete: () -> Unit, reorder: (List<String>) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("folder-management-${folder.id}"),
        color = if (editing) colors.primaryContainer else colors.surfaceContainerLow,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, if (editing) colors.primary else colors.outlineVariant),
    ) {
        BoxWithConstraints(Modifier.padding(12.dp)) {
            val details: @Composable RowScope.() -> Unit = {
                Icon(Icons.Outlined.FolderOpen, null, Modifier.size(32.dp), tint = colors.primary)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(folder.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("Чаты: ${folder.conversationCount} · встречи: ${folder.conferenceCount}",
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
            val controls: @Composable () -> Unit = {
                FlowRow(modifier = Modifier.widthIn(max = 198.dp), horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.End)) {
                    IconButton(onClick = rename, enabled = !busy, modifier = Modifier.size(48.dp).testTag("folder-rename-${folder.id}")) {
                        Icon(Icons.Outlined.Edit, "Переименовать ${folder.name}", Modifier.size(20.dp))
                    }
                    IconButton(onClick = delete, enabled = !busy, modifier = Modifier.size(48.dp).testTag("folder-delete-${folder.id}"),
                        colors = IconButtonDefaults.iconButtonColors(contentColor = colors.error)) {
                        Icon(Icons.Outlined.DeleteOutline, "Удалить папку ${folder.name}", Modifier.size(20.dp))
                    }
                    IconButton(onClick = { reorder(folders.map { it.id }.toMutableList().apply { add(index - 1, removeAt(index)) }) },
                        enabled = index > 0 && !busy, modifier = Modifier.size(48.dp).testTag("folder-up-${folder.id}")) {
                        Icon(Icons.Outlined.KeyboardArrowUp, "Поднять папку ${folder.name}", Modifier.size(22.dp))
                    }
                    IconButton(onClick = { reorder(folders.map { it.id }.toMutableList().apply { add(index + 1, removeAt(index)) }) },
                        enabled = index < folders.lastIndex && !busy, modifier = Modifier.size(48.dp).testTag("folder-down-${folder.id}")) {
                        Icon(Icons.Outlined.KeyboardArrowDown, "Опустить папку ${folder.name}", Modifier.size(22.dp))
                    }
                }
            }
            if (maxWidth >= 420.dp * fontScale) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), content = details)
                    controls()
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), content = details)
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { controls() }
                }
            }
        }
    }
}

@Composable
internal fun FolderCandidatesDialog(state: MessagingUiState, actions: MessagingActions, dismiss: () -> Unit) {
    val folderId = state.selectedFolderId ?: return
    AlertDialog(onDismissRequest = dismiss, title = { Text("Чаты и встречи в папке") },
        text = { Column(Modifier.heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(value = state.candidateQuery, onValueChange = actions.searchFolderCandidates, label = { Text("Найти чат или встречу") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("folder-candidate-search"))
            LoadingStatus(state.folderCandidates.loading, state.folderCandidates.error, { actions.searchFolderCandidates(state.candidateQuery) })
            LazyColumn(Modifier.weight(1f, fill = false).testTag("folder-candidate-list")) {
                items(state.folderCandidates.data.orEmpty(), key = { "${it.type}:${it.id}" }) { entry ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = entry.inFolder, onCheckedChange = { actions.folderMembership(folderId, entry.type, entry.id, it) }, enabled = !state.busy, modifier = Modifier.testTag("folder-candidate-${entry.id}"))
                        Column(Modifier.weight(1f)) {
                            Text(entry.title)
                            Text(if (entry.type == "conference") "Встреча" else "Чат", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (state.candidateCursor != null) item { MoreButton("Загрузить ещё", "folder-candidates-more", state.folderCandidates.loading, actions.moreFolderCandidates) }
                if (state.folderCandidates.data?.isEmpty() == true && !state.folderCandidates.loading && state.folderCandidates.error == null) item { EmptyChat("Доступные чаты и встречи не найдены") }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton(onClick = dismiss) { Text("Готово") } })
}

@Composable
private fun MembersDialog(state: MessagingUiState, actions: MessagingActions, dismiss: () -> Unit) {
    val group = state.detail.data ?: return
    var name by remember(group.id, group.name) { mutableStateOf(group.name) }
    var description by remember(group.id, group.description) { mutableStateOf(group.description) }
    var removal by remember { mutableStateOf<ChatMember?>(null) }
    var leave by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = { if (!state.busy) dismiss() }, title = { Text("Группа") },
        text = { Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (group.canManage) {
                OutlinedTextField(value = name, onValueChange = { name = it.take(200) }, label = { Text("Название") }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("group-name"))
                OutlinedTextField(value = description, onValueChange = { description = it.take(2000) }, label = { Text("Описание") }, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
                Button(onClick = { actions.editGroup(name.trim(), description.trim()) }, enabled = !state.busy && name.isNotBlank() && (name.trim() != group.name || description.trim() != group.description), modifier = Modifier.testTag("group-save")) { Text("Сохранить") }
            } else { Text(group.title, style = MaterialTheme.typography.titleMedium); if (group.description.isNotBlank()) Text(group.description) }
            LoadingStatus(state.members.loading, state.members.error, actions.loadMembers)
            state.members.data.orEmpty().forEach { member ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(member.displayName)
                        Text("${roleLabel(member.role)}${when (member.online) { true -> " · в сети"; false -> " · не в сети"; null -> "" }}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (group.canManage && member.id != state.selfId && member.role != "owner" && (group.myRole == "owner" || member.role == "member")) IconButton(onClick = { removal = member }, enabled = !state.busy, modifier = Modifier.testTag("group-remove-${member.id}")) { Icon(Icons.Outlined.PersonRemove, "Удалить ${member.displayName} из группы") }
                }
            }
            if (state.memberCursor != null) MoreButton("Ещё участники", "group-members-more", state.members.loading, actions.moreMembers)
            if (group.canManage) {
                HorizontalDivider()
                OutlinedTextField(value = state.userQuery, onValueChange = actions.searchUsers, label = { Text("Добавить участника") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("group-member-search"))
                LoadingStatus(state.users.loading, state.users.error, { actions.searchUsers(state.userQuery) })
                state.users.data.orEmpty().filter { peer -> peer.id != state.selfId && state.members.data.orEmpty().none { it.id == peer.id } }.forEach { peer ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(peer.displayName, Modifier.weight(1f))
                        TextButton(onClick = { actions.addMember(peer.id) }, enabled = !state.busy, modifier = Modifier.testTag("group-add-${peer.id}")) { Text("Добавить") }
                    }
                }
            }
            if (group.myRole != "owner") OutlinedButton(onClick = { leave = true }, enabled = !state.busy, modifier = Modifier.testTag("group-leave")) { Text("Выйти из группы") }
            if (group.myRole == "owner") TextButton(onClick = { delete = true }, enabled = !state.busy, modifier = Modifier.testTag("group-delete")) { Text("Удалить группу", color = MaterialTheme.colorScheme.error) }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton(onClick = dismiss, enabled = !state.busy) { Text("Готово") } })
    removal?.let { member -> ConfirmationDialog("Удалить участника?", "${member.displayName} потеряет доступ к группе.", state.busy, state.error, { removal = null }) { actions.removeMember(member.id); removal = null } }
    if (leave) ConfirmationDialog("Выйти из группы?", "Вы потеряете доступ к сообщениям и файлам группы.", state.busy, state.error, { leave = false }) { actions.leaveGroup(); leave = false }
    if (delete) ConfirmationDialog("Удалить группу?", "Группа станет недоступна всем участникам.", state.busy, state.error, { delete = false }) { actions.deleteGroup(); delete = false }
}

@Composable
private fun NotificationsDialog(state: MessagingUiState, actions: MessagingActions, dismiss: () -> Unit) {
    var settings by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 440.dp).fillMaxWidth().heightIn(max = maxHeight).testTag("notifications-dialog"),
                shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.padding(24.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (settings) "Настройки уведомлений" else "Уведомления", Modifier.weight(1f).semantics { heading() }, fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold)
                        IconButton(onClick = { settings = !settings }, modifier = Modifier.testTag("notifications-settings")) {
                            Icon(if (settings) Icons.AutoMirrored.Outlined.ArrowBack else Icons.Outlined.Settings,
                                if (settings) "К уведомлениям" else "Настройки уведомлений", Modifier.size(21.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = dismiss, modifier = Modifier.testTag("notifications-close")) { Icon(Icons.Outlined.Close, "Закрыть", Modifier.size(21.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    Spacer(Modifier.height(18.dp))
                    if (settings) Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (!state.pushEnabled) Text("Системные уведомления приходят, пока приложение открыто.")
                        Text(when {
                            !state.pushConfigured -> "Фоновые push ещё не настроены."
                            state.pushEnabled -> "Фоновые push включены на этом устройстве."
                            else -> "Фоновые push доступны. Включите их, чтобы получать уведомления, когда приложение закрыто."
                        }, modifier = Modifier.testTag("notifications-push-status"))
                        if (state.pushConfigured) OutlinedButton(
                            onClick = if (state.pushEnabled) actions.disablePush else actions.enablePush,
                            enabled = !state.pushBusy,
                            modifier = Modifier.testTag(if (state.pushEnabled) "notifications-push-disable" else "notifications-push-enable"),
                        ) {
                            if (state.pushBusy) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("Сохраняем…")
                            } else Text(if (state.pushEnabled) "Отключить фоновые push" else "Включить фоновые push")
                        }
                        state.pushMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("notifications-push-message").semantics { liveRegion = LiveRegionMode.Polite }) }
                        if (!state.notificationPermissionEnabled) OutlinedButton(onClick = actions.requestSystemNotifications, modifier = Modifier.testTag("notifications-permission")) { Text("Разрешить уведомления") }
                        LoadingStatus(state.notificationPreferences.loading, state.notificationPreferences.error, actions.loadNotifications)
                        state.notificationPreferences.data?.let { preferences ->
                            PreferenceToggle("Приглашения на встречи", preferences.invitation, !state.busy, "notification-pref-invitation") { actions.saveNotificationPreferences(preferences.copy(invitation = it)) }
                            PreferenceToggle("Напоминания о встречах", preferences.reminder, !state.busy, "notification-pref-reminder") { actions.saveNotificationPreferences(preferences.copy(reminder = it)) }
                            PreferenceToggle("Готовность записи", preferences.recording, !state.busy, "notification-pref-recording") { actions.saveNotificationPreferences(preferences.copy(recording = it)) }
                            PreferenceToggle("Итоги встречи", preferences.summary, !state.busy, "notification-pref-summary") { actions.saveNotificationPreferences(preferences.copy(summary = it)) }
                        }
                        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Новых: ${state.notificationUnread}", Modifier.weight(1f), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            IconButton(onClick = actions.loadNotifications, enabled = !state.notifications.loading, modifier = Modifier.testTag("notifications-refresh")) { Icon(Icons.Outlined.Refresh, "Обновить уведомления", Modifier.size(21.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        if (state.notificationUnread > 0) TextButton(onClick = actions.readAllNotifications, enabled = !state.notificationBusy,
                            contentPadding = PaddingValues(horizontal = 0.dp), modifier = Modifier.testTag("notifications-read-loaded")) { Text("Прочитать все", fontSize = 12.sp) }
                        LoadingStatus(state.notifications.loading, state.notifications.error, actions.loadNotifications)
                        LazyColumn(Modifier.weight(1f, fill = false).testTag("notifications-list")) {
                            items(state.notifications.data.orEmpty(), key = { it.id }) { notification ->
                                NotificationRow(notification, state.notificationBusy, actions, dismiss)
                            }
                            if (state.notificationCursor != null) item { MoreButton("Ещё уведомления", "notifications-more", state.notifications.loading, actions.moreNotifications) }
                            if (state.notifications.data?.isEmpty() == true && !state.notifications.loading && state.notifications.error == null) item { EmptyChat("Уведомлений пока нет") }
                            state.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
                        }
                        TextButton(onClick = { actions.back(); dismiss() }, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).padding(top = 8.dp).testTag("notifications-open-chats")) {
                            Text("Открыть чаты", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationRow(notification: InboxNotification, busy: Boolean, actions: MessagingActions, dismiss: () -> Unit) {
    val message = notification.type.contains("message")
    val icon = when {
        message -> Icons.Outlined.ChatBubbleOutline
        notification.type.contains("recording") -> Icons.Outlined.PlayCircleOutline
        notification.type.contains("summary") -> Icons.Outlined.Description
        notification.type.contains("cancel") -> Icons.Outlined.EventBusy
        notification.type.contains("admission") -> Icons.Outlined.PersonAdd
        notification.conferenceId != null || notification.type.startsWith("conference.") -> Icons.Outlined.Videocam
        else -> Icons.Outlined.NotificationsNone
    }
    Column(Modifier.fillMaxWidth().testTag("notification-row-${notification.id}")) {
        Row(Modifier.fillMaxWidth().padding(vertical = 15.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Surface(Modifier.size(42.dp), shape = RoundedCornerShape(15.dp), color = if (message) Color(0xFFDEF0E9) else Color(0xFFDFE8FF)) {
                Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(21.dp), tint = if (message) Color(0xFF3D8576) else Color(0xFF4D71C5)) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(notificationLabel(notification.type), Modifier.weight(1f), fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold)
                    if (notification.readAt == null) Box(Modifier.size(6.dp).background(MaterialTheme.colorScheme.primary, CircleShape).semantics { contentDescription = "Не прочитано" })
                }
                notification.scheduledAt?.let { Text("Встреча: ${dateLabel(it)}", fontSize = 12.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text("Получено: ${dateLabel(notification.createdAt)}", Modifier.testTag("notification-date-${notification.id}"), fontSize = 10.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (notification.conversationId != null || notification.conferenceId != null) TextButton(onClick = { actions.openNotification(notification); dismiss() }, contentPadding = PaddingValues(0.dp), modifier = Modifier.testTag("notification-open-${notification.id}")) { Text(if (notification.conversationId != null) "Открыть чат" else "Открыть встречу", fontSize = 12.sp) }
                    if (notification.readAt == null) TextButton(onClick = { actions.readNotification(notification.id) }, enabled = !busy, contentPadding = PaddingValues(0.dp), modifier = Modifier.testTag("notification-read-${notification.id}")) { Text("Прочитано", fontSize = 12.sp) }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun PreferenceToggle(label: String, checked: Boolean, enabled: Boolean, tag: String, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = change).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f)); Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun ConfirmationDialog(title: String, message: String, busy: Boolean, error: String?, dismiss: () -> Unit, confirm: () -> Unit) {
    AlertDialog(onDismissRequest = { if (!busy) dismiss() }, title = { Text(title) }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(message); error?.let { Text(it, color = MaterialTheme.colorScheme.error) } } },
        confirmButton = { Button(onClick = confirm, enabled = !busy, modifier = Modifier.testTag("messaging-confirm")) { Text("Подтвердить") } }, dismissButton = { TextButton(onClick = dismiss, enabled = !busy) { Text("Отмена") } })
}

@Composable
private fun ChatAvatar(title: String, group: Boolean, size: androidx.compose.ui.unit.Dp = 44.dp, height: androidx.compose.ui.unit.Dp = size) {
    // The mockup uses pastel initials, including in dark mode; no synthetic profile photo.
    val tone = title.hashCode().ushr(1) % 4
    val background = when (tone) { 1 -> Color(0xFFDEF0E9); 2 -> Color(0xFFF6E7DB); 3 -> Color(0xFFDFE8FF); else -> Color(0xFFE6DEF6) }
    val foreground = when (tone) { 1 -> Color(0xFF3D8576); 2 -> Color(0xFFAC7451); 3 -> Color(0xFF4D71C5); else -> Color(0xFF7253A2) }
    Box {
        Surface(Modifier.size(size, height), shape = RoundedCornerShape(size * .33f), color = background) {
            Box(contentAlignment = Alignment.Center) { Text(title.trim().split(Regex("\\s+")).take(2).mapNotNull { it.firstOrNull()?.uppercaseChar() }.joinToString("").ifBlank { "Ч" }, fontSize = (size.value * .35f).sp, fontWeight = FontWeight.SemiBold, color = foreground) }
        }
        if (group) Surface(Modifier.align(Alignment.BottomEnd).offset(x = 3.dp, y = 3.dp).size(if (size >= 43.dp) 22.dp else 18.dp),
            shape = RoundedCornerShape(if (size >= 44.dp) 8.dp else 6.dp), color = MaterialTheme.colorScheme.primaryContainer,
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.surface)) { Icon(Icons.Outlined.Group, null, Modifier.padding(4.dp), tint = MaterialTheme.colorScheme.primary) }
    }
}

@Composable
private fun LoadingStatus(loading: Boolean, error: String?, retry: () -> Unit, modifier: Modifier = Modifier) {
    if (!loading && error == null) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("messaging-loading"))
        if (error != null) {
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            TextButton(onClick = retry, enabled = !loading) { Text("Повторить загрузку") }
        }
    }
}

@Composable
private fun MessagingNotice(error: String?, notice: String?, dismiss: () -> Unit) {
    val value = error ?: notice ?: return
    Surface(color = if (error != null) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().testTag("messaging-notice").semantics { liveRegion = LiveRegionMode.Polite }) {
        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            IconButton(onClick = dismiss) { Icon(Icons.Outlined.Close, "Закрыть сообщение") }
        }
    }
}

@Composable
private fun MoreButton(label: String, tag: String, loading: Boolean, more: () -> Unit) {
    OutlinedButton(onClick = more, enabled = !loading, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)) { Text(label) }
}

@Composable
private fun EmptyChat(label: String) { Text(label, Modifier.fillMaxWidth().padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
private fun countLabel(value: Long): String = if (value > 99) "99+" else value.toString()
private fun memberCountLabel(count: Int): String = "$count " + when {
    count % 100 in 11..14 -> "участников"
    count % 10 == 1 -> "участник"
    count % 10 in 2..4 -> "участника"
    else -> "участников"
}
private fun timeLabel(at: Instant): String = at.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm", Locale.forLanguageTag("ru")))
private fun dateLabel(at: Instant): String = at.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.forLanguageTag("ru")))
private fun sizeLabel(bytes: Long): String = when { bytes >= 1024 * 1024 -> String.format(Locale.forLanguageTag("ru"), "%.1f МБ", bytes / (1024.0 * 1024)); bytes >= 1024 -> String.format(Locale.forLanguageTag("ru"), "%.1f КБ", bytes / 1024.0); else -> "$bytes Б" }
private fun roleLabel(role: String): String = when (role) { "owner" -> "Владелец"; "admin" -> "Администратор"; else -> "Участник" }
private fun notificationLabel(type: String): String = when {
    type == "conference.soon" || type.contains("reminder") -> "Скоро встреча"
    type == "conference.invited" || type.contains("invitation") || type.contains("invite") -> "Приглашение на встречу"
    type.contains("recording") -> "Запись встречи готова"
    type.contains("summary") -> "Итоги встречи готовы"
    type.contains("message") -> "Новое сообщение"
    type.contains("admission") -> "Изменился допуск к встрече"
    type.contains("cancel") -> "Встреча отменена"
    type.contains("schedule") -> "Изменилось расписание встречи"
    else -> "Обновление MeetSpace"
}
