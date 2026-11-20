package com.meetspace.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.meetspace.core.model.ChatPeer

@Composable
internal fun NewChatDialog(state: MessagingUiState, actions: MessagingActions, dismiss: () -> Unit) {
    var group by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(emptyMap<String, String>()) }
    val people = state.users.data.orEmpty().filterNot { it.id == state.selfId }
    val query = state.userQuery.trim()
    val canSearch = query.codePointCount(0, query.length) >= MinChatSearchCharacters
    val enabled = !state.busy
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)

    Dialog(
        onDismissRequest = { if (enabled) dismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            Modifier.imePadding().padding(16.dp).widthIn(max = 500.dp).fillMaxWidth().heightIn(max = 660.dp)
                .testTag("new-chat-dialog"),
            shape = RoundedCornerShape(24.dp), color = colors.surface, border = BorderStroke(1.dp, colors.outlineVariant),
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(40.dp).background(colors.primaryContainer, shape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(if (group) CallIcons.Users else CallIcons.MessageCircle, null,
                            Modifier.size(22.dp), tint = colors.primary)
                    }
                    Text(if (group) "Новая группа" else "Новый личный чат", Modifier.weight(1f).semantics { heading() },
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    IconButton(dismiss, enabled = enabled, modifier = Modifier.testTag("new-chat-close")) {
                        Icon(CallIcons.X, "Закрыть")
                    }
                }
                HorizontalDivider(color = colors.outlineVariant)
                Column(
                    Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.surfaceContainerLow)
                            .selectableGroup().padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        NewChatMode("Личный", CallIcons.UserRound, !group, enabled, "create-chat-direct", Modifier.weight(1f)) {
                            group = false
                            selected = emptyMap()
                        }
                        NewChatMode("Группа", CallIcons.Users, group, enabled, "create-chat-group", Modifier.weight(1f)) {
                            if (!group) selected = emptyMap()
                            group = true
                        }
                    }
                    if (group) {
                        OutlinedTextField(
                            value = name, onValueChange = { name = it.take(200) }, label = { Text("Название группы") },
                            singleLine = true, shape = shape, enabled = enabled,
                            modifier = Modifier.fillMaxWidth().testTag("create-group-name"),
                        )
                        OutlinedTextField(
                            value = description, onValueChange = { description = it.take(2000) },
                            label = { Text("Описание · необязательно") }, shape = shape, minLines = 2, maxLines = 4,
                            enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("create-group-description"),
                        )
                        if (selected.isNotEmpty()) {
                            Text("Выбраны: ${selected.values.joinToString()}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    OutlinedTextField(
                        value = state.userQuery, onValueChange = actions.searchUsers,
                        label = { Text(if (group) "Найти участников" else "Найти человека") },
                        leadingIcon = { Icon(ChatMessageIcons.Search, null, Modifier.size(20.dp)) },
                        trailingIcon = if (state.userQuery.isNotEmpty()) {
                            {
                                IconButton(
                                    { actions.searchUsers("") }, enabled = enabled,
                                    modifier = Modifier.testTag("chat-user-search-clear"),
                                ) { Icon(CallIcons.X, "Очистить поиск", Modifier.size(18.dp)) }
                            }
                        } else null,
                        shape = shape, singleLine = true, enabled = enabled,
                        modifier = Modifier.fillMaxWidth().testTag("chat-user-search"),
                    )
                    when {
                        state.users.loading -> LoadingStatus(true, null, {})
                        state.users.error != null && canSearch -> Surface(
                            Modifier.fillMaxWidth(), shape = shape, color = colors.errorContainer,
                        ) {
                            LoadingStatus(false, state.users.error, { actions.searchUsers(state.userQuery) }, Modifier.padding(12.dp))
                        }
                        people.isNotEmpty() -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            people.forEach { peer ->
                                NewChatPerson(peer, group, peer.id in selected, enabled) {
                                    if (group) {
                                        selected = if (peer.id in selected) selected - peer.id else selected + (peer.id to peer.displayName)
                                    } else actions.createDirect(peer.id)
                                }
                            }
                        }
                        else -> Row(
                            Modifier.fillMaxWidth().background(colors.surfaceContainerLow, shape).padding(14.dp)
                                .testTag("chat-user-search-empty").semantics { liveRegion = LiveRegionMode.Polite },
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(ChatMessageIcons.Search, null, Modifier.size(22.dp), tint = colors.primary)
                            Text(
                                when {
                                    query.isEmpty() -> "Введите имя пользователя"
                                    !canSearch -> "Введите хотя бы $MinChatSearchCharacters символа"
                                    else -> "Пользователи не найдены"
                                },
                                style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                            )
                        }
                    }
                    state.error?.let { Text(it, color = colors.error, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                }
                if (group) {
                    HorizontalDivider(color = colors.outlineVariant)
                    Button(
                        onClick = { actions.createGroup(name.trim(), description.trim(), selected.keys.toList()) },
                        enabled = enabled && name.isNotBlank() && selected.isNotEmpty(), shape = shape,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).heightIn(min = 48.dp)
                            .testTag("create-group-submit"),
                    ) { Text(if (enabled) "Создать группу" else "Создаём…") }
                }
            }
        }
    }
}

@Composable
private fun NewChatMode(
    label: String, icon: ImageVector, selected: Boolean, enabled: Boolean, tag: String, modifier: Modifier, onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val color = if (selected) colors.primary else colors.onSurfaceVariant
    Row(
        modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp))
            .background(if (selected) colors.primaryContainer else colors.surfaceContainerLow)
            .selectable(selected, enabled = enabled, role = Role.Tab, onClick = onClick).testTag(tag)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(17.dp), tint = color)
        Text(label, color = color, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun NewChatPerson(peer: ChatPeer, group: Boolean, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val interaction = if (group) {
        Modifier.toggleable(selected, enabled = enabled, role = Role.Checkbox) { onClick() }
            .testTag("group-select-${peer.id}")
    } else {
        Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick).testTag("direct-create-${peer.id}")
    }
    val shape = RoundedCornerShape(12.dp)
    Surface(
        Modifier.fillMaxWidth(), shape = shape, color = if (selected) colors.primaryContainer else colors.surface,
        border = BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(shape).then(interaction).padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            ChatAvatar(peer.displayName, peer.id, peer.avatarVersion, 38.dp)
            Text(peer.displayName, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (group) {
                Checkbox(selected, onCheckedChange = null, enabled = enabled)
            } else {
                Icon(ChatMessageIcons.ChevronRight, null, Modifier.size(18.dp),
                    tint = if (enabled) colors.primary else colors.onSurfaceVariant)
            }
        }
    }
}
