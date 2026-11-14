package com.meetspace.app.ui

import android.content.ClipData
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.meetspace.core.model.ChatMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@Composable
internal fun MessageActionsDialog(
    message: ChatMessage,
    own: Boolean,
    actions: MessagingActions,
    delete: () -> Unit,
    forward: () -> Unit,
    select: () -> Unit,
    anchor: Rect?,
    threadStart: Float,
    close: () -> Unit,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val hostView = LocalView.current
    val hostWindowOrigin = remember(hostView, anchor) {
        val screen = IntArray(2).also(hostView::getLocationOnScreen)
        val window = IntArray(2).also(hostView::getLocationInWindow)
        Offset((screen[0] - window[0]).toFloat(), (screen[1] - window[1]).toFloat())
    }
    var copyError by remember(message.id) { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val scrim = if (colors.surface.luminance() < .5f) Color(0xA3050A12) else Color(0x6B132039)

    Dialog(
        onDismissRequest = close,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false, decorFitsSystemWindows = false),
    ) {
        SetChatDialogWindowAppearance(colors.surface.luminance() >= .5f, blurRadius = 3.dp)
        val density = LocalDensity.current
        val safeDrawing = WindowInsets.safeDrawing
        val safeTop = safeDrawing.getTop(density)
        val safeBottom = safeDrawing.getBottom(density)
        val safeLeft = safeDrawing.getLeft(density, LayoutDirection.Ltr)
        val safeRight = safeDrawing.getRight(density, LayoutDirection.Ltr)
        var screenOrigin by remember { mutableStateOf(Offset.Zero) }
        Layout(
            content = {
                val dismiss by rememberUpdatedState(close)
                Box(
                    Modifier.background(scrim).pointerInput(Unit) {
                        detectTapGestures { dismiss() }
                    },
                )
                Column(
                    Modifier.testTag("message-actions-dialog")
                        .verticalScroll(rememberScrollState()).pointerInput(Unit) {
                            detectTapGestures { }
                        },
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 32.dp).padding(start = 12.dp, end = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Действия с сообщением",
                            Modifier.weight(1f).semantics { heading() },
                            color = Color.White,
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Box(Modifier.size(44.dp), contentAlignment = Alignment.CenterEnd) {
                            Box(
                                Modifier.size(32.dp).clip(CircleShape)
                                    .background(Color.White.copy(alpha = .11f))
                                    .clickable(role = Role.Button, onClick = close),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(CallIcons.X, "Закрыть действия с сообщением", Modifier.size(17.dp), tint = Color.White)
                            }
                        }
                    }
                    ActionMessagePreview(message, own)
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = colors.surface,
                        border = BorderStroke(1.dp, colors.outlineVariant),
                        shadowElevation = 4.dp,
                    ) {
                        Column(Modifier.fillMaxWidth().padding(6.dp)) {
                            MessageAction("Ответить", ChatMessageIcons.Reply, "message-reply-${message.id}", first = true) {
                                close()
                                actions.reply(message)
                            }
                            MessageAction("Копировать", ChatMessageIcons.Copy, "message-copy-${message.id}") {
                                scope.launch {
                                    try {
                                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Сообщение", messagePreviewText(message))))
                                        close()
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        copyError = true
                                    }
                                }
                            }
                            MessageAction("Переслать", ChatMessageIcons.Forward, "message-forward-${message.id}") {
                                close()
                                forward()
                            }
                            MessageAction("Выбрать", ChatMessageIcons.Select, "message-select-${message.id}") {
                                close()
                                select()
                            }
                            MessageAction(
                                if (message.pinned) "Открепить" else "Закрепить",
                                ChatMessageIcons.Pin,
                                "message-pin-${message.id}",
                            ) {
                                close()
                                actions.pin(message, !message.pinned)
                            }
                            if (own && message.forwardedSenderName == null) {
                                MessageAction("Изменить", ChatMessageIcons.Edit, "message-edit-${message.id}") {
                                    close()
                                    actions.edit(message)
                                }
                            }
                            if (own) {
                                MessageAction("Удалить", ChatMessageIcons.Delete, "message-delete-${message.id}", danger = true) {
                                    close()
                                    delete()
                                }
                            }
                            if (copyError) {
                                Text(
                                    "Не удалось скопировать сообщение.",
                                    Modifier.padding(12.dp),
                                    color = colors.error,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxSize().onGloballyPositioned { coordinates ->
                val position = coordinates.localToScreen(Offset.Zero)
                if (position != Offset.Unspecified) screenOrigin = position
            },
        ) { measurables, constraints ->
            val viewportWidth = constraints.maxWidth
            val viewportHeight = constraints.maxHeight
            val margin = 16.dp.roundToPx()
            val backdrop = measurables[0].measure(Constraints.fixed(viewportWidth, viewportHeight))
            val panelWidth = minOf(320.dp.roundToPx(), (viewportWidth - safeLeft - safeRight - margin * 2).coerceAtLeast(0))
            val panel = measurables[1].measure(
                Constraints(
                    minWidth = panelWidth,
                    maxWidth = panelWidth,
                    maxHeight = (viewportHeight - safeTop - safeBottom - margin * 2).coerceAtLeast(0),
                ),
            )
            val windowToDialog = hostWindowOrigin - screenOrigin
            val safeMinLeft = safeLeft + margin
            val maxLeft = (viewportWidth - panel.width - safeRight - margin).coerceAtLeast(safeMinLeft)
            val minLeft = (threadStart + windowToDialog.x + 12.dp.toPx()).roundToInt().coerceIn(safeMinLeft, maxLeft)
            val left = when {
                anchor == null -> (minLeft + maxLeft) / 2
                own -> (anchor.right + windowToDialog.x - panel.width).roundToInt().coerceIn(minLeft, maxLeft)
                else -> (anchor.left + windowToDialog.x).roundToInt().coerceIn(minLeft, maxLeft)
            }
            val minTop = safeTop + margin
            val maxTop = (viewportHeight - safeBottom - panel.height - margin).coerceAtLeast(minTop)
            val top = anchor?.let { (it.top + windowToDialog.y - 68.dp.toPx()).roundToInt().coerceIn(minTop, maxTop) }
                ?: ((viewportHeight - panel.height) / 2).coerceIn(minTop, maxTop)
            layout(viewportWidth, viewportHeight) {
                backdrop.place(0, 0)
                panel.place(left, top)
            }
        }
    }
}

@Composable
private fun ActionMessagePreview(message: ChatMessage, own: Boolean) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(17.dp, 17.dp, if (own) 5.dp else 17.dp, if (own) 17.dp else 5.dp)
    Surface(
        color = if (own) colors.primaryContainer else colors.surface,
        contentColor = colors.onSurface,
        shape = shape,
        border = BorderStroke(1.dp, if (own) colors.primaryContainer else colors.outlineVariant),
        shadowElevation = 3.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 11.dp)) {
            Text(
                messageAuthor(message, own),
                color = colors.primary,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (message.text.isNotBlank()) {
                Text(message.text, Modifier.padding(top = 5.dp), fontSize = 12.sp, lineHeight = 19.sp, maxLines = 5, overflow = TextOverflow.Ellipsis)
            }
            message.attachments.firstOrNull()?.let { file ->
                Row(
                    Modifier.padding(top = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(CallIcons.Paperclip, null, Modifier.size(15.dp), tint = colors.primary)
                    val additional = message.attachments.size - 1
                    Text(
                        if (additional > 0) "${file.filename} · ещё $additional" else file.filename,
                        color = colors.onSurfaceVariant,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(
                Modifier.align(Alignment.End).padding(top = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                val time = message.createdAt.atZone(ZoneId.systemDefault()).format(MessageTimeFormat)
                Text(if (message.version > 1) "изменено · $time" else time, color = colors.onSurfaceVariant, fontSize = 10.sp, lineHeight = 14.sp)
                if (own) Icon(ChatMessageIcons.Delivered, "Отправлено", Modifier.size(13.dp), tint = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun MessageAction(
    text: String,
    icon: ImageVector,
    tag: String,
    first: Boolean = false,
    danger: Boolean = false,
    action: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val contentColor = if (danger) colors.error else colors.onSurface
    val shape = RoundedCornerShape(10.dp)
    Box(Modifier.fillMaxWidth().clip(shape)) {
        if (!first) HorizontalDivider(color = colors.outlineVariant.copy(alpha = .7f))
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(role = Role.Button, onClick = action)
                .testTag(tag).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, null, Modifier.size(19.dp), tint = contentColor)
            Text(text, Modifier.weight(1f), color = contentColor, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

internal fun messageAuthor(message: ChatMessage, own: Boolean): String =
    if (own) "Вы" else message.senderName.ifBlank { "Участник" }

internal fun messagePreviewText(message: ChatMessage): String =
    message.text.ifBlank { message.attachments.firstOrNull()?.filename ?: "Вложение" }

private val MessageTimeFormat = DateTimeFormatter.ofPattern("HH:mm")
