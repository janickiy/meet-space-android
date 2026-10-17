package com.meetspace.app.ui

import android.content.ClipData
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.meetspace.core.model.ChatMessage
import kotlinx.coroutines.launch

@Composable
internal fun MessageActionsDialog(message: ChatMessage, own: Boolean, actions: MessagingActions, delete: () -> Unit, close: () -> Unit) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = close) {
        Column(Modifier.widthIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Сообщение", Modifier.weight(1f).padding(start = 12.dp), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                IconButton(close) { Icon(Icons.Outlined.Close, "Закрыть действия", tint = Color.White) }
            }
            Surface(color = if (own) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(17.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(if (own) "Вы" else message.senderName, color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Text(message.text.ifBlank { "Вложение" }, fontSize = 12.sp, lineHeight = 19.sp, maxLines = 6, overflow = TextOverflow.Ellipsis)
                    message.attachments.forEach { Text(it.filename, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            Surface(shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.fillMaxWidth().padding(5.dp)) {
                    MessageAction("Ответить", Icons.AutoMirrored.Outlined.Reply, "message-reply-${message.id}") { close(); actions.reply(message) }
                    HorizontalDivider()
                    if (message.text.isNotBlank()) {
                        MessageAction("Скопировать", Icons.Outlined.ContentCopy, "message-copy-${message.id}") { scope.launch {
                            runCatching { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Сообщение", message.text))) }.onSuccess { close() }.onFailure { error = true }
                        } }
                        HorizontalDivider()
                    }
                    MessageAction(if (message.pinned) "Открепить" else "Закрепить", Icons.Outlined.PushPin, "message-pin-${message.id}") { close(); actions.pin(message, !message.pinned) }
                    if (own) {
                        HorizontalDivider()
                        MessageAction("Редактировать", Icons.Outlined.Edit, "message-edit-${message.id}") { close(); actions.edit(message) }
                        HorizontalDivider()
                        MessageAction("Удалить", Icons.Outlined.DeleteOutline, "message-delete-${message.id}", true) { close(); delete() }
                    }
                    if (error) Text("Не удалось скопировать сообщение.", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun MessageAction(text: String, icon: ImageVector, tag: String, danger: Boolean = false, action: () -> Unit) {
    TextButton(action, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag), shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)) {
        Icon(icon, null, Modifier.size(19.dp)); Spacer(Modifier.width(12.dp)); Text(text, Modifier.weight(1f), fontSize = 13.sp)
    }
}
