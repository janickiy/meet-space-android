package com.meetspace.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.NumberFormat
import java.util.Locale

enum class CallPanel { PARTICIPANTS, CHAT, COMMENTS }

data class CallAttachmentUi(
    val id: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val canDownload: Boolean = true,
    val downloading: Boolean = false,
    val error: String? = null,
)

enum class CallAttachmentStatus { PREPARING, UPLOADING, READY, FAILED, UNKNOWN }

data class CallPendingAttachmentUi(
    val id: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val status: CallAttachmentStatus = CallAttachmentStatus.READY,
    val error: String? = null,
)

data class CallTextEntry(
    val id: String,
    val author: String,
    val text: String,
    val timeLabel: String,
    val own: Boolean = false,
    val dateLabel: String? = null,
    val attachmentLabel: String? = null,
    val deleted: Boolean = false,
    val attachments: List<CallAttachmentUi> = emptyList(),
    val localRequestId: String? = null,
    val deliveryFailed: Boolean = false,
    val sending: Boolean = false,
)

data class CallTextPanelState(
    val entries: List<CallTextEntry> = emptyList(),
    val draft: String = "",
    val loading: Boolean = false,
    val sending: Boolean = false,
    val canSend: Boolean = false,
    val error: String? = null,
    val unavailableReason: String? = null,
    val hasMore: Boolean = false,
    val viewingOlder: Boolean = false,
    val pendingFiles: List<CallPendingAttachmentUi> = emptyList(),
    val canAttach: Boolean = false,
    val canEdit: Boolean = true,
    val participantCount: Int? = null,
    val status: String? = null,
)

data class CallUiDetails(
    val elapsedSeconds: Long? = null,
    val recording: Boolean = false,
    val chat: CallTextPanelState = CallTextPanelState(),
    val comments: CallTextPanelState = CallTextPanelState(),
    val commentCount: Int? = null,
    val room: MeetingRoomUiState = MeetingRoomUiState(),
    val recordingState: CallRecordingUiState = CallRecordingUiState(),
    val invitationLink: String? = null,
)

data class CallPanelActions(
    val open: (CallPanel?) -> Unit = {},
    val room: MeetingRoomActions = MeetingRoomActions(),
    val chatDraft: (String) -> Unit = {},
    val sendChat: () -> Unit = {},
    val retryChat: () -> Unit = {},
    val moreChat: () -> Unit = {},
    val commentDraft: (String) -> Unit = {},
    val sendComment: () -> Unit = {},
    val retryComments: () -> Unit = {},
    val moreComments: () -> Unit = {},
    val latestChat: () -> Unit = {},
    val latestComments: () -> Unit = {},
    val attachChat: () -> Unit = {},
    val removeChatAttachment: (String) -> Unit = {},
    val downloadChatAttachment: (entryId: String, attachmentId: String) -> Unit = { _, _ -> },
    val retryMessage: (String) -> Unit = {},
    val discardMessage: (String) -> Unit = {},
)

private data class CallPanelColors(
    val surface: Color,
    val feed: Color,
    val line: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val tint: Color,
    val error: Color,
)

private val LightCallPanel = CallPanelColors(
    Color.White, Color(0xFFF4EFF6), Color(0xFFE7E8F0), Color(0xFF292B3B),
    Color(0xFF6F7385), Color(0xFF6E477C), Color(0xFFF0E6F4), Color(0xFFB22242),
)
private val DarkCallPanel = CallPanelColors(
    Color(0xFF21232F), Color(0xFF24202C), Color(0xFF36394C), Color(0xFFF3F3FA),
    Color(0xFFB0B3C7), Color(0xFFD5B2E4), Color(0xFF45344E), Color(0xFFFFC1C7),
)

@Composable
private fun callPanelColors() = if (MaterialTheme.colorScheme.background.luminance() < .5f) DarkCallPanel else LightCallPanel

@Composable
internal fun CallPanelFrame(
    panel: CallPanel,
    participantCount: Int?,
    compact: Boolean,
    close: () -> Unit,
    selectPanel: ((CallPanel) -> Unit)?,
    modifier: Modifier = Modifier,
    refreshParticipants: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val colors = callPanelColors()
    val participantsSelected = panel == CallPanel.PARTICIPANTS
    val shape = RoundedCornerShape(14.dp)
    Column(modifier.clip(shape).background(colors.surface).border(1.dp, colors.line, shape)) {
        Row(
            Modifier.fillMaxWidth().padding(start = if (compact) 14.dp else 16.dp, end = if (compact) 8.dp else 12.dp,
                top = if (compact) 3.dp else 10.dp, bottom = if (compact) 0.dp else 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (participantsSelected) "Участники встречи" else "Чат встречи",
                color = colors.text, fontSize = 17.sp, lineHeight = 23.sp, letterSpacing = (-.3).sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            if (refreshParticipants != null) {
                IconButton(refreshParticipants, Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.Refresh, "Обновить участников", Modifier.size(18.dp), tint = colors.muted)
                }
            }
            IconButton(close, Modifier.size(48.dp).testTag("call-panel-close")) {
                Icon(CallIcons.X, "Закрыть панель встречи", Modifier.size(18.dp), tint = colors.muted)
            }
        }
        if (selectPanel != null) {
            Row(
                Modifier.fillMaxWidth().selectableGroup().padding(horizontal = if (compact) 13.dp else 16.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                CallPanelTab("Чат", "call-panel-tab-chat", !participantsSelected, colors) { selectPanel(CallPanel.CHAT) }
                val participants = participantCount?.let { "Участники ($it)" } ?: "Участники"
                CallPanelTab(participants, "call-panel-tab-participants", participantsSelected, colors) {
                    selectPanel(CallPanel.PARTICIPANTS)
                }
            }
        }
        HorizontalDivider(color = colors.line)
        Box(Modifier.weight(1f).fillMaxWidth().background(colors.feed)) { content() }
    }
}

@Composable
private fun CallPanelTab(label: String, tag: String, selected: Boolean, colors: CallPanelColors, select: () -> Unit) {
    Box(
        Modifier.heightIn(min = 48.dp).selectable(selected, role = Role.Tab, onClick = select).testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) colors.accent else colors.muted, fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 1.dp))
        if (selected) Box(Modifier.matchParentSize()) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp)
                .background(colors.accent, RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp)))
        }
    }
}

/** One server-backed meeting history. Draft and file ownership stay with the caller. */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun CallTextPanel(
    panel: CallPanel,
    title: String,
    state: CallTextPanelState,
    count: Int?,
    actions: CallPanelActions,
    close: () -> Unit,
    modifier: Modifier = Modifier,
    selectPanel: ((CallPanel) -> Unit)? = null,
    compact: Boolean = LocalConfiguration.current.screenWidthDp < 780,
    participantCount: Int? = state.participantCount,
) {
    // COMMENTS remains a compatibility route while callers migrate to the single chat entry.
    val legacyComments = panel == CallPanel.COMMENTS
    val slug = if (legacyComments) "comments" else "chat"
    val draft = if (legacyComments) actions.commentDraft else actions.chatDraft
    val send = if (legacyComments) actions.sendComment else actions.sendChat
    val retry = if (legacyComments) actions.retryComments else actions.retryChat
    val more = if (legacyComments) actions.moreComments else actions.moreChat
    val latest = if (legacyComments) actions.latestComments else actions.latestChat
    val colors = callPanelColors()
    val feedState = androidx.compose.foundation.lazy.rememberLazyListState()
    var previousLast by remember(panel) { mutableStateOf(state.entries.lastOrNull()?.id) }
    var previousOlder by remember(panel) { mutableStateOf(state.viewingOlder) }
    LaunchedEffect(state.entries.lastOrNull()?.id, state.viewingOlder) {
        val last = state.entries.lastOrNull()
        if (!state.viewingOlder && (previousOlder || (last?.own == true && last.id != previousLast))) {
            withFrameNanos { }
            feedState.scrollToItem((feedState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
        }
        previousLast = last?.id
        previousOlder = state.viewingOlder
    }
    CallPanelFrame(panel, participantCount, compact, close, selectPanel, modifier.testTag("call-$slug-panel")) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val short = maxHeight < (170.dp * LocalDensity.current.fontScale).coerceAtLeast(195.dp)
            val scroll = rememberScrollState()
            Column(Modifier.fillMaxSize().then(if (short) Modifier.verticalScroll(scroll) else Modifier)) {
                LazyColumn(
                    (if (short) Modifier.height(96.dp) else Modifier.weight(1f)).fillMaxWidth().testTag("call-$slug-scroll"),
                    state = feedState,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = if (compact) 10.dp else 14.dp),
                ) {
                    callTextFeed(state, slug, colors, retry, more, latest, actions.downloadChatAttachment,
                        actions.retryMessage, actions.discardMessage)
                }
                CallTextComposer(state, slug, draft, send, actions, colors, compact)
            }
        }
    }
}

private fun LazyListScope.callTextFeed(
    state: CallTextPanelState,
    slug: String,
    colors: CallPanelColors,
    retry: () -> Unit,
    more: () -> Unit,
    latest: () -> Unit,
    download: (String, String) -> Unit,
    retryMessage: (String) -> Unit,
    discardMessage: (String) -> Unit,
) {
    if (state.loading) item("loading") {
        LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 12.dp).testTag("call-$slug-loading"),
            color = colors.accent, trackColor = colors.tint)
    }
    state.error?.let { error -> item("error") {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(error, color = colors.error, fontSize = 12.sp,
                modifier = Modifier.testTag("call-$slug-error").semantics { liveRegion = LiveRegionMode.Polite })
            TextButton(retry, Modifier.heightIn(min = 48.dp).testTag("call-$slug-retry"), enabled = !state.loading && !state.sending) {
                Text("Повторить", color = colors.accent)
            }
        }
    } }
    state.unavailableReason?.let { reason -> item("unavailable") {
        Text(reason, Modifier.padding(vertical = 8.dp).testTag("call-$slug-unavailable"), color = colors.muted, fontSize = 12.sp, lineHeight = 19.sp)
    } }
    if (state.hasMore) item("more") {
        TextButton(more, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("call-$slug-more"), enabled = !state.loading) {
            Text("Предыдущие сообщения", color = colors.accent)
        }
    }
    if (state.viewingOlder) item("latest") {
        TextButton(latest, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("call-$slug-latest"), enabled = !state.loading) {
            Text("К новым сообщениям", color = colors.accent)
        }
    }
    if (state.entries.isEmpty() && !state.loading && state.error == null && state.unavailableReason == null) item("empty") {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 20.dp).testTag("call-$slug-empty"),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(CallIcons.MessageCircle, null, Modifier.size(30.dp), tint = colors.accent)
            Text("Пока нет сообщений", color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, textAlign = TextAlign.Center)
            Text("Сообщения и файлы участников появятся здесь.", color = colors.muted, fontSize = 12.sp, lineHeight = 18.sp, textAlign = TextAlign.Center)
        }
    }
    items(state.entries, key = { "entry-${it.id}" }) { entry ->
        Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
            entry.dateLabel?.takeIf(String::isNotBlank)?.let { date ->
                Box(Modifier.fillMaxWidth().padding(bottom = 16.dp), contentAlignment = Alignment.Center) {
                    Text(date, Modifier.background(colors.surface, RoundedCornerShape(20.dp)).padding(horizontal = 11.dp, vertical = 3.dp),
                        color = colors.muted, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.sp)
                }
            }
            CallTextEntryView(entry, slug, colors, download, retryMessage, discardMessage)
        }
    }
}

@Composable
private fun CallTextEntryView(entry: CallTextEntry, slug: String, colors: CallPanelColors,
    download: (String, String) -> Unit, retryMessage: (String) -> Unit, discardMessage: (String) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("call-$slug-entry-${entry.id}"),
        contentAlignment = if (entry.own) Alignment.TopEnd else Alignment.TopStart) {
        val shape = if (entry.own) RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp) else RoundedCornerShape(14.dp, 14.dp, 14.dp, 4.dp)
        Column(Modifier.widthIn(max = maxWidth * .96f).clip(shape)
            .background(if (entry.own) colors.tint else colors.surface).border(1.dp, colors.line, shape)
            .padding(horizontal = 11.dp, vertical = 9.dp)) {
            if (!entry.own) {
                Text(entry.author, color = colors.accent, fontSize = 11.sp, lineHeight = 15.sp,
                    letterSpacing = 0.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(3.dp))
            }
            if (entry.deleted || entry.text.isNotEmpty()) {
                Text(if (entry.deleted) "Сообщение удалено" else entry.text, color = if (entry.deleted) colors.muted else colors.text,
                    fontSize = 12.sp, lineHeight = 18.sp, letterSpacing = 0.sp)
            }
            if (!entry.deleted) {
                entry.attachments.forEach { file ->
                    Spacer(Modifier.height(8.dp))
                    CallFileCard(file, slug, entry.id, colors) { download(entry.id, file.id) }
                }
                if (entry.attachments.isEmpty()) entry.attachmentLabel?.let { label ->
                    Spacer(Modifier.height(8.dp))
                    Text(label, color = colors.accent, fontSize = 11.sp, lineHeight = 16.sp)
                }
            }
            Spacer(Modifier.height(5.dp))
            Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(entry.timeLabel, color = colors.muted, fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 0.sp)
                val requestId = entry.localRequestId
                if (requestId != null) MessageDeliveryStatus(requestId, entry.deliveryFailed,
                    entry.sending, { retryMessage(requestId) }, { discardMessage(requestId) })
                else if (entry.own && !entry.deleted) Icon(CallIcons.Check, "Отправлено", Modifier.size(13.dp), tint = colors.accent)
            }
        }
    }
}

@Composable
private fun CallFileCard(file: CallAttachmentUi, slug: String, entryId: String, colors: CallPanelColors, download: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().clip(shape).background(colors.feed).border(1.dp, colors.line, shape)
            .clickable(enabled = file.canDownload && !file.downloading, onClick = download)
            .testTag("call-$slug-attachment-$entryId-${file.id}")
            .semantics { contentDescription = "Скачать ${file.name}" }.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CallFileIcon(file.mimeType, colors)
            Column(Modifier.weight(1f)) {
                Text(file.name, color = colors.text, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (file.downloading) "Скачивание…" else callFileSize(file.sizeBytes), color = colors.muted,
                    fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 0.sp)
            }
            if (file.downloading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 1.5.dp, color = colors.accent)
            else Icon(CallIcons.Download, null, Modifier.size(16.dp), tint = colors.accent)
        }
        file.error?.let { Text(it, color = colors.error, fontSize = 10.sp, lineHeight = 15.sp,
            modifier = Modifier.testTag("call-$slug-attachment-error-${file.id}").semantics { liveRegion = LiveRegionMode.Polite }) }
    }
}

@Composable
private fun CallFileIcon(mimeType: String, colors: CallPanelColors) {
    Box(Modifier.size(30.dp, 34.dp).background(colors.tint, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
        Icon(if (mimeType.startsWith("image/")) CallIcons.FileImage else CallIcons.FileText, null, Modifier.size(20.dp), tint = colors.accent)
    }
}

private fun callFileSize(bytes: Long): String {
    if (bytes < 0) return "Размер неизвестен"
    val formatter = NumberFormat.getNumberInstance(Locale.forLanguageTag("ru-RU")).apply { maximumFractionDigits = 1 }
    return when {
        bytes < 1024 -> "$bytes Б"
        bytes < 1_048_576 -> "${formatter.format(bytes / 1024.0)} КБ"
        else -> "${formatter.format(bytes / 1_048_576.0)} МБ"
    }
}

private val meetingEmojis = listOf("😊" to "Улыбка", "👍" to "Палец вверх", "❤️" to "Сердце",
    "👏" to "Аплодисменты", "🎉" to "Праздник")

@Composable
private fun CallTextComposer(
    state: CallTextPanelState, slug: String, draft: (String) -> Unit, send: () -> Unit,
    actions: CallPanelActions, colors: CallPanelColors, compact: Boolean,
) {
    val editorEnabled = state.canSend && state.canEdit && !state.sending
    val filesReady = state.pendingFiles.size <= 5 && state.pendingFiles.all {
        it.status == CallAttachmentStatus.READY && it.sizeBytes in 0L..10_485_760L
    }
    val canSend = editorEnabled && state.draft.length <= 4000 && filesReady &&
        (state.draft.isNotBlank() || state.pendingFiles.isNotEmpty())
    val requester = remember { BringIntoViewRequester() }
    val focusRequester = remember { FocusRequester() }
    var editorFocused by remember { mutableStateOf(false) }
    var emojiMenu by remember { mutableStateOf(false) }
    var editorValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(state.draft, TextRange(state.draft.length)))
    }
    // The server-backed draft may be cleared after a successful send or restored after switching tabs.
    val editor = if (editorValue.text == state.draft) editorValue else TextFieldValue(state.draft, TextRange(state.draft.length))
    fun changeEditor(value: TextFieldValue) {
        if (value.text.length > 4000) return
        editorValue = value
        if (value.text != state.draft) draft(value.text)
    }
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(editorFocused, imeBottom, state.draft, state.pendingFiles.size) {
        if (editorFocused) {
            withFrameNanos { }
            requester.bringIntoView()
        }
    }
    val composerShape = RoundedCornerShape(13.dp)
    Column(Modifier.fillMaxWidth().bringIntoViewRequester(requester).background(colors.surface)) {
        HorizontalDivider(color = colors.line)
        Column(Modifier.padding(start = if (compact) 8.dp else 10.dp, end = if (compact) 8.dp else 10.dp,
            top = if (compact) 6.dp else 9.dp, bottom = if (compact) 8.dp else 10.dp)) {
            if (state.pendingFiles.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().heightIn(max = 90.dp).verticalScroll(rememberScrollState())
                    .testTag("call-$slug-pending-files"), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    state.pendingFiles.forEach { file ->
                        CallPendingFile(file, slug, editorEnabled, colors) { actions.removeChatAttachment(file.id) }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            Column(Modifier.fillMaxWidth().clip(composerShape).background(colors.feed)
                .border(1.dp, if (editorFocused) colors.accent else colors.line, composerShape).padding(4.dp)) {
                BasicTextField(editor, ::changeEditor, enabled = editorEnabled, minLines = 2, maxLines = 4,
                    modifier = Modifier.fillMaxWidth().heightIn(min = if (compact) 37.dp else 42.dp)
                        .focusRequester(focusRequester).onFocusChanged { editorFocused = it.isFocused }
                        .testTag("call-$slug-draft").semantics { contentDescription = "Сообщение участникам" },
                    textStyle = MaterialTheme.typography.bodySmall.copy(color = if (editorEnabled) colors.text else colors.muted,
                        fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.sp), cursorBrush = SolidColor(colors.accent),
                    decorationBox = { innerTextField ->
                        Box(Modifier.padding(start = 8.dp, end = 8.dp, top = 7.dp, bottom = 2.dp)) {
                            if (state.draft.isEmpty()) Text("Напишите сообщение…", color = colors.muted,
                                fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.sp)
                            innerTextField()
                        }
                    })
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    CallComposeButton(CallIcons.Paperclip, "Прикрепить файлы", "call-$slug-attach", colors,
                        enabled = editorEnabled && state.canAttach && state.pendingFiles.size < 5, onClick = actions.attachChat)
                    Box {
                        CallComposeButton(CallIcons.Smile, "Добавить эмодзи", "call-$slug-emoji", colors,
                            enabled = editorEnabled, onClick = { emojiMenu = true })
                        DropdownMenu(emojiMenu && editorEnabled, { emojiMenu = false }, containerColor = colors.surface) {
                            Row(Modifier.padding(horizontal = 4.dp)) {
                                meetingEmojis.forEach { (emoji, description) ->
                                    IconButton({
                                        emojiMenu = false
                                        val start = minOf(editor.selection.start, editor.selection.end)
                                        val end = maxOf(editor.selection.start, editor.selection.end)
                                        val text = editor.text.replaceRange(start, end, emoji)
                                        changeEditor(TextFieldValue(text, TextRange(start + emoji.length)))
                                        focusRequester.requestFocus()
                                    }, Modifier.size(48.dp).semantics { contentDescription = description }) {
                                        Text(emoji, fontSize = 18.sp)
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    CallComposeButton(CallIcons.Send, if (state.sending) "Отправка сообщения" else "Отправить сообщение",
                        "call-$slug-send", colors, enabled = canSend, primary = true, busy = state.sending, onClick = send)
                }
            }
            state.status?.takeIf(String::isNotBlank)?.let {
                Text(it, Modifier.padding(top = 6.dp).testTag("call-$slug-status").semantics { liveRegion = LiveRegionMode.Polite },
                    color = colors.muted, fontSize = 10.sp, lineHeight = 15.sp)
            }
        }
    }
}

@Composable
private fun CallPendingFile(file: CallPendingAttachmentUi, slug: String, canRemove: Boolean, colors: CallPanelColors, remove: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(Modifier.fillMaxWidth().clip(shape).background(colors.feed).border(1.dp, colors.line, shape)
        .padding(horizontal = 8.dp).testTag("call-$slug-pending-${file.id}"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CallFileIcon(file.mimeType, colors)
        Column(Modifier.weight(1f)) {
            Text(file.name, color = colors.text, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val status = when (file.status) {
                CallAttachmentStatus.PREPARING -> "Подготовка…"
                CallAttachmentStatus.UPLOADING -> "Загрузка…"
                CallAttachmentStatus.READY -> callFileSize(file.sizeBytes)
                CallAttachmentStatus.FAILED -> file.error ?: "Не удалось загрузить"
                CallAttachmentStatus.UNKNOWN -> file.error ?: "Статус загрузки уточняется"
            }
            Text(status, color = if (file.status == CallAttachmentStatus.FAILED) colors.error else colors.muted,
                fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 0.sp)
        }
        IconButton(remove, Modifier.size(48.dp).testTag("call-$slug-remove-${file.id}"), enabled = canRemove) {
            Icon(CallIcons.X, "Убрать файл ${file.name}", Modifier.size(16.dp), tint = colors.muted)
        }
    }
}

@Composable
private fun CallComposeButton(
    icon: ImageVector, description: String, tag: String, colors: CallPanelColors,
    enabled: Boolean, primary: Boolean = false, busy: Boolean = false, onClick: () -> Unit,
) {
    val background = Color(0xFF734A83).copy(alpha = if (enabled) 1f else .5f)
    IconButton(onClick, Modifier.size(48.dp).testTag(tag), enabled = enabled) {
        Box(
            Modifier.size(40.dp).background(if (primary) background else Color.Transparent, RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp).semantics { contentDescription = description },
                color = Color.White, strokeWidth = 2.dp)
            else Icon(icon, description, Modifier.size(20.dp),
                tint = if (primary) Color.White else colors.muted.copy(alpha = if (enabled) 1f else .55f))
        }
    }
}
