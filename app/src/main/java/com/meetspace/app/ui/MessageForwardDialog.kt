package com.meetspace.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meetspace.core.model.ChatConversation

@Composable
internal fun MessageForwardDialog(state: MessagingUiState, actions: MessagingActions) {
    val forwarding = state.forwarding ?: return
    val messages = forwarding.messages
    val first = messages.firstOrNull() ?: return
    val colors = MaterialTheme.colorScheme
    val conversations = forwarding.conversations.data.orEmpty()
    val directRecipients = conversations.mapNotNull { it.peer?.id }.toSet()
    val users = forwarding.users.data.orEmpty().filter { it.id != state.selfId && it.id !in directRecipients }
    val pendingSearch = forwarding.conversations.loading || forwarding.users.loading

    ChatMessageModal(
        if (messages.size == 1) "Переслать сообщение" else "Переслать сообщения",
        "message-forward-dialog",
        forwarding.busy,
        actions.closeForwarding,
    ) { padding, heading ->
        val error = forwarding.error ?: forwarding.conversations.error ?: forwarding.users.error
        LazyColumn(Modifier.testTag("message-forward-content"), contentPadding = PaddingValues(padding)) {
            item("heading") {
                heading()
                Spacer(Modifier.height(18.dp))
            }
            item("search") {
                ForwardRecipientSearch(forwarding.query, forwarding.busy, actions.searchForwardRecipients)
                Spacer(Modifier.height(18.dp))
            }
            item("preview") {
                Column(
                    Modifier.fillMaxWidth().background(colors.background, RoundedCornerShape(13.dp))
                        .padding(horizontal = 15.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(
                        if (messages.size == 1) messageAuthor(first, first.senderId == state.selfId) else "Сообщений: ${messages.size}",
                        fontSize = 11.sp,
                        lineHeight = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurfaceVariant,
                    )
                    Text(
                        messagePreviewText(first),
                        fontSize = 13.sp,
                        lineHeight = 22.sp,
                        color = colors.onSurfaceVariant,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(18.dp))
            }
            if (forwarding.busy || pendingSearch) item("progress") {
                LinearProgressIndicator(Modifier.fillMaxWidth().testTag("message-forward-progress"))
            }
            if (error != null) item("error") {
                Text(
                    error,
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    color = colors.error,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                )
                if (forwarding.error == null) {
                    TextButton({ actions.searchForwardRecipients(forwarding.query) }, enabled = !forwarding.busy && !pendingSearch) {
                        Text("Повторить поиск")
                    }
                }
            }
            if (conversations.isNotEmpty() || users.isNotEmpty()) item("target-top") {
                Spacer(Modifier.height(15.dp))
            }
            items(conversations, key = { "chat:${it.id}" }) { conversation ->
                ForwardTarget(
                    conversation = conversation,
                    tag = "message-forward-target-${conversation.id}",
                    enabled = !forwarding.busy,
                    onClick = { actions.forwardToConversation(conversation.id) },
                )
                if (conversation.id != conversations.last().id || users.isNotEmpty()) Spacer(Modifier.height(5.dp))
            }
            items(users, key = { "user:${it.id}" }) { user ->
                ForwardTarget(
                    conversation = ChatConversation(id = "user:${user.id}", type = "direct", peer = user),
                    tag = "message-forward-user-${user.id}",
                    enabled = !forwarding.busy,
                    onClick = { actions.forwardToUser(user.id) },
                )
                if (user.id != users.last().id) Spacer(Modifier.height(5.dp))
            }
            if (forwarding.cursor != null) item("more") {
                TextButton(
                    actions.moreForwardRecipients,
                    Modifier.fillMaxWidth().testTag("message-forward-more"),
                    enabled = !forwarding.busy && !pendingSearch,
                ) {
                    Text("Загрузить ещё")
                }
            } else if (!pendingSearch && error == null && conversations.isEmpty() && users.isEmpty()) item("empty") {
                Text(
                    "Чат не найден",
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    color = colors.onSurfaceVariant,
                    fontSize = 13.sp,
                    lineHeight = 22.sp,
                )
            }
        }
    }
}

@Composable
private fun ForwardRecipientSearch(query: String, busy: Boolean, change: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp)
    val focus = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Получатель", color = colors.onSurface, fontSize = 13.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
        BasicTextField(
            value = query,
            onValueChange = change,
            enabled = !busy,
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("message-forward-search").semantics {
                contentDescription = "Получатель"
            }.onFocusChanged { focused = it.isFocused },
            textStyle = style.copy(color = colors.onSurface),
            cursorBrush = SolidColor(colors.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
            decorationBox = { input ->
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 50.dp)
                        .border(1.dp, if (focused) colors.primary else colors.outlineVariant, RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (query.isBlank()) Text(
                        "Найти чат или группу",
                        style = style,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    input()
                }
            },
        )
    }
}

@Composable
private fun ForwardTarget(conversation: ChatConversation, tag: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().heightIn(min = 62.dp).clip(RoundedCornerShape(13.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .testTag(tag).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        ConversationAvatar(conversation, 40.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                conversation.title.ifBlank { "Чат" },
                color = colors.onSurface,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (conversation.type == "group") "Групповой чат" else "Личный чат",
                color = colors.onSurfaceVariant,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
        }
        Icon(ChatMessageIcons.ChevronRight, null, Modifier.size(17.dp), tint = colors.onSurfaceVariant)
    }
}
