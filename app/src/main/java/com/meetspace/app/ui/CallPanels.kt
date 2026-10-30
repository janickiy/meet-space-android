package com.meetspace.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
    val activeSpeakerIds: Set<String> = emptySet(),
    val chat: CallTextPanelState = CallTextPanelState(),
    val comments: CallTextPanelState = CallTextPanelState(),
    val commentCount: Int? = null,
    val room: MeetingRoomUiState = MeetingRoomUiState(),
    val recordingState: CallRecordingUiState = CallRecordingUiState(),
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

private val PanelBackground = Color(0xFF222C3B)
private val PanelBorder = Color(0xFF354155)
private val PanelText = Color(0xFFF1F5FC)
private val PanelMuted = Color(0xFF9EACC2)

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
) {
    // COMMENTS remains a compatibility route while callers migrate to the single chat entry.
    val legacyComments = panel == CallPanel.COMMENTS
    val slug = if (legacyComments) "comments" else "chat"
    val draft = if (legacyComments) actions.commentDraft else actions.chatDraft
    val send = if (legacyComments) actions.sendComment else actions.sendChat
    val retry = if (legacyComments) actions.retryComments else actions.retryChat
    val more = if (legacyComments) actions.moreComments else actions.moreChat
    val latest = if (legacyComments) actions.latestComments else actions.latestChat
    val phone = LocalConfiguration.current.screenWidthDp < 600
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
    BoxWithConstraints(modifier.clip(RoundedCornerShape(20.dp)).background(PanelBackground)
        .border(1.dp, PanelBorder, RoundedCornerShape(20.dp)).testTag("call-$slug-panel")) {
        val short = maxHeight < (260.dp * LocalDensity.current.fontScale).coerceAtLeast(380.dp)
        val scroll = rememberScrollState()
        // Preserve the editor's composition slot and focus when the IME resizes this panel.
        Column(Modifier.fillMaxSize().then(if (short) Modifier.verticalScroll(scroll) else Modifier)) {
            CallTextPanelHeader(state.participantCount, phone, close)
            LazyColumn((if (short) Modifier.height(96.dp) else Modifier.weight(1f)).fillMaxWidth().testTag("call-$slug-scroll"),
                state = feedState, contentPadding = PaddingValues(if (phone) 14.dp else 16.dp)) {
                callTextFeed(state, slug, retry, more, latest, actions.downloadChatAttachment, actions.retryMessage, actions.discardMessage)
            }
            CallTextComposer(state, slug, draft, send, actions, phone, compact = short)
        }
    }
}

@Composable
private fun CallTextPanelHeader(participantCount: Int?, phone: Boolean, close: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = if (phone) 15.dp else 18.dp, end = if (phone) 8.dp else 11.dp,
        top = if (phone) 11.dp else 16.dp, bottom = if (phone) 11.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Чат встречи", color = PanelText, fontSize = if (phone) 16.sp else 17.sp,
                lineHeight = if (phone) 24.sp else 25.5.sp, letterSpacing = (-0.4).sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() })
            Text("Сообщения и файлы" + (participantCount?.takeIf { it >= 0 }?.let { " · ${participantLabel(it)}" } ?: ""),
                color = PanelMuted, fontSize = 10.sp, lineHeight = 15.sp, letterSpacing = 0.sp)
        }
        IconButton(close, Modifier.size(48.dp).testTag("call-panel-close")) {
            Icon(CallIcons.X, "Закрыть чат", Modifier.size(20.dp), tint = Color(0xFFAAB9CF))
        }
    }
    HorizontalDivider(color = PanelBorder)
}

private fun participantLabel(count: Int): String {
    val word = when {
        count % 100 in 11..14 -> "участников"
        count % 10 == 1 -> "участник"
        count % 10 in 2..4 -> "участника"
        else -> "участников"
    }
    return "$count $word"
}

private fun LazyListScope.callTextFeed(
    state: CallTextPanelState, slug: String, retry: () -> Unit, more: () -> Unit, latest: () -> Unit,
    download: (String, String) -> Unit,
    retryMessage: (String) -> Unit,
    discardMessage: (String) -> Unit,
) {
    if (state.loading) item("loading") {
        LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 12.dp).testTag("call-$slug-loading"), color = Color(0xFF88A5FF), trackColor = PanelBorder)
    }
    state.error?.let { error -> item("error") {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(error, color = Color(0xFFFFC1C7), fontSize = 12.sp, modifier = Modifier.testTag("call-$slug-error").semantics { liveRegion = LiveRegionMode.Polite })
            TextButton(retry, Modifier.heightIn(min = 48.dp).testTag("call-$slug-retry"), enabled = !state.loading && !state.sending) { Text("Повторить", color = Color(0xFF88A5FF)) }
        }
    } }
    state.unavailableReason?.let { reason -> item("unavailable") {
        Text(reason, Modifier.padding(vertical = 8.dp).testTag("call-$slug-unavailable"), color = PanelMuted, fontSize = 12.sp, lineHeight = 19.sp)
    } }
    if (state.hasMore) item("more") { TextButton(more, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("call-$slug-more"), enabled = !state.loading) { Text("Предыдущие сообщения", color = Color(0xFF88A5FF)) } }
    if (state.viewingOlder) item("latest") { TextButton(latest, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("call-$slug-latest"), enabled = !state.loading) { Text("К новым сообщениям", color = Color(0xFF88A5FF)) } }
    if (state.entries.isEmpty() && !state.loading && state.error == null && state.unavailableReason == null) item("empty") {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 30.dp).testTag("call-$slug-empty"),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(CallIcons.MessageCircle, null, Modifier.size(36.dp), tint = Color(0xFF8EACFF))
            Text("Пока нет сообщений", color = PanelText, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, textAlign = TextAlign.Center)
            Text("Сообщения и файлы участников появятся здесь.", color = PanelMuted, fontSize = 12.sp, lineHeight = 20.sp, textAlign = TextAlign.Center)
        }
    }
    items(state.entries, key = { "entry-${it.id}" }) { entry ->
        Column(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
            entry.dateLabel?.takeIf(String::isNotBlank)?.let {
                Text(it, Modifier.fillMaxWidth().padding(bottom = 18.dp), textAlign = TextAlign.Center,
                    color = Color(0xFF93A4BF), fontSize = 10.sp, lineHeight = 15.sp, letterSpacing = 0.sp)
            }
            CallTextEntryView(entry, slug, download, retryMessage, discardMessage)
        }
    }
}

@Composable
private fun CallTextEntryView(entry: CallTextEntry, slug: String, download: (String, String) -> Unit,
    retryMessage: (String) -> Unit, discardMessage: (String) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("call-$slug-entry-${entry.id}"),
        contentAlignment = if (entry.own) Alignment.TopEnd else Alignment.TopStart) {
        val shape = if (entry.own) RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp) else RoundedCornerShape(14.dp, 14.dp, 14.dp, 4.dp)
        Column(Modifier.widthIn(max = maxWidth * .96f).clip(shape)
            .background(if (entry.own) Color(0xFF344A73) else Color(0xFF2D394D)).padding(12.dp)) {
            Text(entry.author, color = Color(0xFFB9CBFF), fontSize = 10.sp, lineHeight = 15.sp, letterSpacing = 0.sp, fontWeight = FontWeight.SemiBold)
            if (entry.deleted || entry.text.isNotEmpty()) {
                Spacer(Modifier.height(5.dp))
                Text(if (entry.deleted) "Сообщение удалено" else entry.text, color = if (entry.deleted) PanelMuted else Color(0xFFE0E7F3),
                    fontSize = 12.sp, lineHeight = 19.2.sp, letterSpacing = 0.sp)
            }
            if (!entry.deleted) {
                entry.attachments.forEach { file ->
                    Spacer(Modifier.height(10.dp))
                    CallFileCard(file, slug, entry.id) { download(entry.id, file.id) }
                }
                if (entry.attachments.isEmpty()) entry.attachmentLabel?.let { label ->
                    Spacer(Modifier.height(10.dp))
                    Text(label, color = Color(0xFFB8CDFF), fontSize = 11.sp, lineHeight = 17.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(entry.timeLabel + if (entry.own) " · Вы" else "",
                    color = Color(0xFFA8B7CC), fontSize = 9.sp, lineHeight = 13.5.sp, letterSpacing = 0.sp)
                entry.localRequestId?.let { requestId -> MessageDeliveryStatus(requestId, entry.deliveryFailed,
                    entry.sending, { retryMessage(requestId) }, { discardMessage(requestId) }) }
            }
        }
    }
}

@Composable
private fun CallFileCard(file: CallAttachmentUi, slug: String, entryId: String, download: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0x08FFFFFF))
            .border(1.dp, Color(0xFF526078), RoundedCornerShape(10.dp))
            .clickable(enabled = file.canDownload && !file.downloading, onClick = download)
            .testTag("call-$slug-attachment-$entryId-${file.id}")
            .semantics { contentDescription = "Скачать ${file.name}" }
            .padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            CallFileIcon(file.mimeType)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(file.name, color = Color(0xFFE6EDF9), fontSize = 11.sp, lineHeight = 16.5.sp, letterSpacing = 0.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (file.downloading) "Скачивание…" else callFileSize(file.sizeBytes), color = Color(0xFFA6B5CB), fontSize = 9.sp, lineHeight = 13.5.sp, letterSpacing = 0.sp)
            }
            if (file.downloading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 1.5.dp, color = Color(0xFFB3C4DD))
            else Icon(CallIcons.Download, null, Modifier.size(16.dp), tint = Color(0xFFB3C4DD))
        }
        file.error?.let { Text(it, color = Color(0xFFFFC1C7), fontSize = 10.sp, lineHeight = 15.sp,
            modifier = Modifier.testTag("call-$slug-attachment-error-${file.id}").semantics { liveRegion = LiveRegionMode.Polite }) }
    }
}

@Composable
private fun CallFileIcon(mimeType: String) {
    Box(Modifier.size(32.dp, 36.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x218CAEFF)), contentAlignment = Alignment.Center) {
        Icon(if (mimeType.startsWith("image/")) CallIcons.FileImage else CallIcons.FileText, null, Modifier.size(20.dp), tint = Color(0xFFAFC6FF))
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

@Composable
private fun CallTextComposer(
    state: CallTextPanelState, slug: String, draft: (String) -> Unit, send: () -> Unit,
    actions: CallPanelActions, phone: Boolean, compact: Boolean,
) {
    val editorEnabled = state.canSend && state.canEdit && !state.sending
    val filesReady = state.pendingFiles.size <= 5 && state.pendingFiles.all {
        it.status == CallAttachmentStatus.READY && it.sizeBytes in 0L..10_485_760L
    }
    val canSend = editorEnabled && state.draft.length <= 4000 && filesReady &&
        (state.draft.isNotBlank() || state.pendingFiles.isNotEmpty())
    val requester = remember { BringIntoViewRequester() }
    var editorFocused by remember { mutableStateOf(false) }
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(editorFocused, imeBottom, state.draft, state.pendingFiles.size, compact) {
        if (editorFocused) {
            withFrameNanos { }
            requester.bringIntoView()
        }
    }
    Column(Modifier.fillMaxWidth().bringIntoViewRequester(requester).background(Color(0xFF263142))) {
        HorizontalDivider(color = PanelBorder)
        Column(Modifier.padding(start = if (phone) 12.dp else 14.dp, end = if (phone) 12.dp else 14.dp,
            top = if (phone) 11.dp else 13.dp, bottom = if (phone) 12.dp else 15.dp)) {
            if (state.pendingFiles.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().heightIn(max = 125.dp).verticalScroll(rememberScrollState())
                    .testTag("call-$slug-pending-files"), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    state.pendingFiles.forEach { file ->
                        CallPendingFile(file, slug, editorEnabled) { actions.removeChatAttachment(file.id) }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            Text("Сообщение участникам", color = Color(0xFFB5C3D7), fontSize = 10.sp, lineHeight = 15.sp, letterSpacing = 0.sp)
            Spacer(Modifier.height(9.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF202A39))
                .border(if (editorFocused) 2.dp else 1.dp, if (editorFocused) Color(0xFF85A6FF) else Color(0xFF50607A), RoundedCornerShape(14.dp))
                .padding(5.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                CallComposeButton(CallIcons.Paperclip, "Прикрепить файлы", "call-$slug-attach",
                    enabled = editorEnabled && state.canAttach && state.pendingFiles.size < 5, onClick = actions.attachChat)
                BasicTextField(state.draft, { if (it.length <= 4000) draft(it) }, enabled = editorEnabled, minLines = 2, maxLines = 4,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp).padding(vertical = 7.dp)
                        .onFocusChanged { editorFocused = it.isFocused }.testTag("call-$slug-draft")
                        .semantics { contentDescription = "Сообщение участникам" },
                    textStyle = MaterialTheme.typography.bodySmall.copy(color = if (editorEnabled) Color(0xFFF3F6FC) else PanelMuted,
                        fontSize = 12.sp, lineHeight = 19.2.sp, letterSpacing = 0.sp), cursorBrush = SolidColor(Color(0xFF85A6FF)),
                    decorationBox = { innerTextField ->
                        Box {
                            if (state.draft.isEmpty()) Text("Написать сообщение…", color = Color(0xFF9BAAC0), fontSize = 12.sp, lineHeight = 19.2.sp, letterSpacing = 0.sp)
                            innerTextField()
                        }
                    })
                CallComposeButton(CallIcons.ArrowUp, if (state.sending) "Отправка сообщения" else "Отправить сообщение",
                    "call-$slug-send", enabled = canSend, primary = true, busy = state.sending, onClick = send)
            }
            state.status?.takeIf(String::isNotBlank)?.let {
                Text(it, Modifier.padding(top = 8.dp).testTag("call-$slug-status").semantics { liveRegion = LiveRegionMode.Polite },
                    color = PanelMuted, fontSize = 10.sp, lineHeight = 15.sp)
            }
        }
    }
}

@Composable
private fun CallPendingFile(file: CallPendingAttachmentUi, slug: String, canRemove: Boolean, remove: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFF303E53))
        .border(1.dp, Color(0xFF526078), RoundedCornerShape(10.dp)).padding(7.dp).testTag("call-$slug-pending-${file.id}"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        CallFileIcon(file.mimeType)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(file.name, color = Color(0xFFE6EDF9), fontSize = 11.sp, lineHeight = 16.5.sp, letterSpacing = 0.sp,
                fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val status = when (file.status) {
                CallAttachmentStatus.PREPARING -> "Подготовка…"
                CallAttachmentStatus.UPLOADING -> "Загрузка…"
                CallAttachmentStatus.READY -> callFileSize(file.sizeBytes)
                CallAttachmentStatus.FAILED -> file.error ?: "Не удалось загрузить"
                CallAttachmentStatus.UNKNOWN -> file.error ?: "Статус загрузки уточняется"
            }
            Text(status, color = if (file.status == CallAttachmentStatus.FAILED) Color(0xFFFFC1C7) else Color(0xFFA6B5CB),
                fontSize = 9.sp, lineHeight = 13.5.sp, letterSpacing = 0.sp)
        }
        IconButton(remove, Modifier.size(48.dp).testTag("call-$slug-remove-${file.id}"), enabled = canRemove) {
            Icon(CallIcons.X, "Убрать файл ${file.name}", Modifier.size(16.dp), tint = if (canRemove) Color(0xFFB7C7DC) else PanelMuted)
        }
    }
}

@Composable
private fun CallComposeButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, tag: String,
    enabled: Boolean, primary: Boolean = false, busy: Boolean = false, onClick: () -> Unit,
) {
    IconButton(onClick, Modifier.size(48.dp).testTag(tag), enabled = enabled) {
        Box(Modifier.size(36.dp, 38.dp).clip(RoundedCornerShape(10.dp))
            .background(if (primary) if (enabled) Color(0xFF416DF3) else Color(0xFF35435A) else Color.Transparent), contentAlignment = Alignment.Center) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp).semantics { contentDescription = description }, color = Color(0xFF8192AC), strokeWidth = 2.dp)
            else Icon(icon, description, Modifier.size(20.dp), tint = if (primary) if (enabled) Color.White else Color(0xFF8192AC) else if (enabled) Color(0xFFB8C7DF) else Color(0xFF8192AC))
        }
    }
}
