package com.meetspace.app.ui

import android.view.Gravity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.meetspace.core.model.ChatAttachment
import com.meetspace.core.model.ChatConversation
import com.meetspace.core.model.ChatMaterial
import com.meetspace.core.model.ChatMaterialKind
import com.meetspace.core.model.ChatMessage
import com.meetspace.core.model.chatMaterialHttpUri

@Composable
internal fun ConversationInfoDialog(
    conversation: ChatConversation,
    state: MessagingUiState,
    actions: MessagingActions,
    dismiss: () -> Unit,
    onEdit: () -> Unit,
    onMessage: () -> Unit,
    onCall: (() -> Unit)?,
    onPins: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val compact = LocalConfiguration.current.screenWidthDp < 600
    val group = conversation.type == "group"
    val profile = state.contactProfile.data?.takeIf { !group && it.id == conversation.peer?.id }
    val name = profile?.displayName?.takeIf { it.isNotBlank() } ?: conversation.title
    val status = if (group) memberCountLabel(conversation.memberCount) else when (state.peerOnline) {
        true -> "В сети"
        false -> "Не в сети"
        null -> "Личная переписка"
    }
    val details = if (group) listOf("Название" to name, "Описание" to conversation.description.ifBlank { "Описание не добавлено" })
        else listOf("Имя" to name, "О себе" to (profile?.bio?.ifBlank { "Не указано" } ?: "—"),
            "Email" to (profile?.email?.ifBlank { "Не указан" } ?: "—"))
    var tab by remember(conversation.id) { mutableStateOf(ChatMaterialKind.MEDIA) }
    LaunchedEffect(conversation.id, conversation.peer?.id) { if (!group) actions.loadContactProfile() }
    LaunchedEffect(conversation.id, tab) { actions.loadConversationMaterials(tab, false) }
    DisposableEffect(conversation.id) { onDispose(actions.closeConversationInfo) }

    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setGravity(if (compact) Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL else Gravity.CENTER) }
        Surface(
            Modifier.padding(if (compact) 14.dp else 20.dp).widthIn(max = 560.dp).fillMaxWidth().testTag("conversation-info-dialog"),
            shape = RoundedCornerShape(if (compact) 23.dp else 24.dp), color = colors.surface,
        ) {
            Column(Modifier.fillMaxWidth()) {
                val gutter = if (compact) 16.dp else 24.dp
                Row(Modifier.fillMaxWidth().heightIn(min = if (compact) 52.dp else 56.dp)
                    .padding(start = gutter, end = if (compact) 8.dp else 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (group) "О группе" else "О контакте", Modifier.weight(1f).semantics { heading() },
                        color = colors.onSurfaceVariant, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    IconButton(dismiss, Modifier.testTag("conversation-info-close")) {
                        Icon(CallIcons.X, "Закрыть", Modifier.size(20.dp))
                    }
                }
                Column(
                    Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState())
                        .padding(start = gutter, end = gutter, bottom = if (compact) 18.dp else 24.dp).testTag("conversation-info-content"),
                    verticalArrangement = Arrangement.spacedBy(if (compact) 16.dp else 18.dp),
                ) {
                    Column(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        ConversationAvatar(conversation, if (compact) 60.dp else 72.dp, circular = true)
                        Text(name, Modifier.padding(top = 10.dp).semantics { heading() }, fontSize = if (compact) 21.sp else 24.sp,
                            lineHeight = if (compact) 25.sp else 29.sp, fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.4).sp, textAlign = TextAlign.Center)
                        Text(status, Modifier.padding(top = 4.dp), color = colors.onSurfaceVariant, textAlign = TextAlign.Center,
                            fontSize = 13.sp, lineHeight = 18.sp)
                    }
                    val secondaryAction = if (group) onEdit.takeIf { conversation.canManage } else onCall
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                        val actionModifier = if (compact && secondaryAction != null) Modifier.weight(1f) else Modifier
                        ConversationInfoAction(if (group) "Перейти в чат" else "Сообщение", CallIcons.MessageCircle, "conversation-info-message", actionModifier, onClick = onMessage)
                        if (secondaryAction != null) ConversationInfoAction(
                            if (group) "Редактировать группу" else "Позвонить",
                            if (group) ChatMessageIcons.Edit else ChatMessageIcons.Phone,
                            if (group) "conversation-info-edit" else "conversation-info-call", actionModifier,
                            enabled = !state.busy, onClick = secondaryAction,
                        )
                    }
                    Column(Modifier.fillMaxWidth().background(colors.background, RoundedCornerShape(16.dp))
                        .padding(horizontal = if (compact) 15.dp else 20.dp, vertical = if (compact) 15.dp else 18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        details.forEach { (label, value) ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 16.dp)) {
                                Text(label, Modifier.weight(if (compact) .25f else .22f), color = colors.onSurfaceVariant, fontSize = 12.sp, lineHeight = 20.sp)
                                Text(value, Modifier.weight(if (compact) .75f else .78f), color = colors.onSurface, fontSize = 14.sp, lineHeight = 20.sp)
                            }
                        }
                        if (!group) LoadingStatus(state.contactProfile.loading, state.contactProfile.error, actions.loadContactProfile)
                    }
                    if (group) ConversationInfoMembers(conversation, state, actions, compact)
                    ConversationInfoMaterials(conversation, state, actions, tab, { tab = it })
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                        TextButton(onPins, Modifier.testTag("conversation-info-pins")) { Text("Закреплённые сообщения", fontSize = 12.sp) }
                        TextButton({ dismiss(); actions.refreshMessages() }, Modifier.testTag("messages-refresh"), enabled = !state.messages.loading) {
                            Text("Обновить сообщения", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationInfoAction(label: String, icon: ImageVector, tag: String, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    FilledTonalButton(onClick, modifier.heightIn(min = 48.dp).testTag(tag), enabled = enabled,
        shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.filledTonalButtonColors(containerColor = colors.primaryContainer, contentColor = colors.primary),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)) {
        Icon(icon, null, Modifier.size(17.dp))
        Spacer(Modifier.width(7.dp))
        Text(label, fontSize = 13.sp, lineHeight = 17.sp, textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ConversationInfoMembers(conversation: ChatConversation, state: MessagingUiState, actions: MessagingActions, compact: Boolean) {
    val colors = MaterialTheme.colorScheme
    var expanded by remember(conversation.id) { mutableStateOf(false) }
    val inset = if (compact) 15.dp else 20.dp
    val shape = RoundedCornerShape(16.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(colors.background)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { expanded = !expanded }
            .testTag("conversation-info-members").semantics { stateDescription = if (expanded) "Развёрнуты" else "Свёрнуты" }
            .padding(horizontal = inset, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(CallIcons.Users, null, Modifier.size(17.dp))
            Text("Участники", Modifier.weight(1f), fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(conversation.memberCount.toString(), color = colors.onSurfaceVariant, fontSize = 12.sp)
            Icon(CallIcons.ChevronDown, null, Modifier.size(15.dp).rotate(if (expanded) 180f else 0f), tint = colors.onSurfaceVariant)
        }
        if (expanded) Column(Modifier.fillMaxWidth().padding(start = inset, end = inset, bottom = 8.dp)) {
            LoadingStatus(state.members.loading, state.members.error, actions.loadMembers)
            state.members.data.orEmpty().forEach { member ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ChatAvatar(member.displayName, member.id, member.avatarVersion, 30.dp)
                    Column(Modifier.weight(1f)) {
                        Text(member.displayName, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        val labels = listOfNotNull("Вы".takeIf { member.id == state.selfId }, roleLabel(member.role).takeIf { member.role != "member" })
                        if (labels.isNotEmpty()) Text(labels.joinToString(" · "), fontSize = 11.sp, color = colors.onSurfaceVariant)
                    }
                }
            }
            if (state.memberCursor != null) MoreButton("Ещё участники", "conversation-info-members-more", state.members.loading, actions.moreMembers)
        }
    }
}

@Composable
private fun ConversationInfoMaterials(conversation: ChatConversation, state: MessagingUiState, actions: MessagingActions,
    tab: ChatMaterialKind, select: (ChatMaterialKind) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val page = state.chatMaterials?.takeIf { it.kind == tab }?.page
    val fromHistory = page?.data == null && page?.loading != true && page?.error == null
    val items = if (fromHistory) remember(state.messages.data, conversation.id, tab) {
        loadedMaterials(conversation.id, state.messages.data.orEmpty(), tab)
    } else page?.data?.items.orEmpty()
    val uriHandler = LocalUriHandler.current
    var linkFailure by remember(conversation.id, tab) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag("conversation-info-materials"), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.widthIn(max = 332.dp).fillMaxWidth().background(colors.surfaceVariant, RoundedCornerShape(24.dp))
            .padding(4.dp).selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
            ChatMaterialKind.entries.forEach { value ->
                val selected = value == tab
                Box(Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(20.dp))
                    .background(if (selected) colors.surface else colors.surfaceVariant)
                    .selectable(selected = selected, role = Role.Tab) { select(value) }.testTag("conversation-info-tab-${value.wireValue}")
                    .padding(horizontal = 4.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
                    Text(value.label, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold,
                        color = if (selected) colors.primary else colors.onSurfaceVariant, textAlign = TextAlign.Center)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        LoadingStatus(page?.loading == true, page?.error, { actions.loadConversationMaterials(tab, page?.data?.nextCursor != null) })
        if (items.isEmpty() && page?.loading != true && page?.error == null) {
            ConversationInfoEmpty(tab, fromHistory)
        } else if (tab == ChatMaterialKind.MEDIA) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                items.mapNotNull { it.attachment }.chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        row.forEach { file ->
                            Column(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(9.dp)).background(colors.surfaceVariant)
                                .clickable(enabled = !state.attachmentBusy, role = Role.Button) { actions.download(file) }
                                .testTag("conversation-info-material-${file.id}").padding(6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically)) {
                                Icon(if (file.mimeType.startsWith("video/")) CallIcons.Video else ChatMessageIcons.Image, null, Modifier.size(22.dp), tint = colors.primary)
                                Text(file.filename, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items.forEach { item ->
                    item.attachment?.let { file ->
                        ConversationInfoMaterialRow(file.filename, sizeLabel(file.size), CallIcons.FileText, ChatMessageIcons.Download,
                            "conversation-info-material-${file.id}", !state.attachmentBusy) { actions.download(file) }
                    }
                    item.link?.let { link ->
                        ConversationInfoMaterialRow(link.host, link.toString(), CallIcons.Link, ChatMessageIcons.ArrowOutward,
                            "conversation-info-link", true) {
                            linkFailure = runCatching { uriHandler.openUri(link.toASCIIString()) }.isFailure
                        }
                    }
                }
            }
        }
        if (linkFailure) Text("Не удалось открыть ссылку.", color = colors.error, fontSize = 12.sp)
        if (page?.data?.nextCursor != null) MoreButton("Загрузить ещё", "conversation-info-materials-more", page.loading) {
            actions.loadConversationMaterials(tab, true)
        }
    }
}

@Composable
private fun ConversationInfoMaterialRow(title: String, description: String, icon: ImageVector, actionIcon: ImageVector,
    tag: String, enabled: Boolean, open: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(10.dp))
        .clickable(enabled = enabled, role = Role.Button, onClick = open).testTag(tag).padding(horizontal = 6.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).background(colors.primaryContainer, RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(17.dp), tint = colors.primary)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(description, fontSize = 11.sp, lineHeight = 15.sp, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) { Icon(actionIcon, null, Modifier.size(17.dp), tint = colors.primary) }
    }
}

@Composable
private fun ConversationInfoEmpty(tab: ChatMaterialKind, fromHistory: Boolean) {
    val colors = MaterialTheme.colorScheme
    val icon = when (tab) {
        ChatMaterialKind.MEDIA -> ChatMessageIcons.Image
        ChatMaterialKind.FILE -> CallIcons.FileText
        ChatMaterialKind.LINK -> CallIcons.Link
    }
    val kind = when (tab) {
        ChatMaterialKind.MEDIA -> "медиа"
        ChatMaterialKind.FILE -> "файлов"
        ChatMaterialKind.LINK -> "ссылок"
    }
    Column(Modifier.fillMaxWidth().heightIn(min = 100.dp).padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
        Box(Modifier.size(42.dp).background(colors.background, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(22.dp), tint = colors.onSurfaceVariant)
        }
        Text(if (fromHistory) "В загруженных сообщениях нет $kind." else "В этом чате пока нет $kind.",
            color = colors.onSurfaceVariant, fontSize = 13.sp, lineHeight = 19.sp, textAlign = TextAlign.Center)
    }
}

private val chatLinks = Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE)

private fun loadedMaterials(id: String, messages: List<ChatMessage>, kind: ChatMaterialKind): List<ChatMaterial> = messages.asReversed()
    .asSequence().filter { it.conversationId == id && it.deletedAt == null && it.kind == "message" }.flatMap { message ->
        if (kind == ChatMaterialKind.LINK) chatLinks.findAll(message.text).mapNotNull { match ->
            chatMaterialHttpUri(match.value.trimEnd('.', ',', '!', '?', ';', ':', ')'))?.let { ChatMaterial(message.id, link = it) }
        } else message.attachments.asSequence().filter { it.conversationId == id && it.isMedia() == (kind == ChatMaterialKind.MEDIA) }
            .map { ChatMaterial(message.id, attachment = it) }
    }.distinctBy { it.attachment?.id ?: it.link }.take(200).toList()

private fun ChatAttachment.isMedia() = mimeType.startsWith("image/") || mimeType.startsWith("video/")

private val ChatMaterialKind.label: String get() = when (this) {
    ChatMaterialKind.MEDIA -> "Медиа"
    ChatMaterialKind.FILE -> "Файлы"
    ChatMaterialKind.LINK -> "Ссылки"
}
