package com.meetspace.app.ui

import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

@Composable
internal fun MessageSelectionHeader(
    count: Int,
    allSelected: Boolean,
    compactWidth: Boolean,
    compactHeight: Boolean,
    enabled: Boolean,
    close: () -> Unit,
    toggleAll: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(Modifier.fillMaxWidth().background(colors.surface).testTag("message-selection-header")) {
        val narrow = compactWidth && maxWidth < 360.dp
        val padding = if (compactWidth || compactHeight) 12.dp else 24.dp
        Column {
            Row(
                Modifier.fillMaxWidth().heightIn(min = if (compactHeight) 48.dp else if (compactWidth) 72.dp else 75.dp)
                    .padding(horizontal = padding),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (narrow) 5.dp else if (compactWidth) 8.dp else 12.dp),
            ) {
                IconButton(close, Modifier.size(44.dp).testTag("message-selection-close"), enabled = enabled) {
                    Icon(CallIcons.X, "Отменить выбор сообщений", Modifier.size(20.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        "Выбрано: $count",
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        fontSize = (if (narrow) 13 else if (compactWidth) 14 else 15).sp,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Выберите сообщения",
                        fontSize = (if (narrow) 9 else if (compactWidth) 10 else 11).sp,
                        lineHeight = 15.sp,
                        color = colors.onSurfaceVariant,
                    )
                }
                TextButton(
                    toggleAll,
                    Modifier.heightIn(min = 44.dp).testTag("message-selection-all"),
                    enabled = enabled,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = if (narrow) 5.dp else if (compactWidth) 7.dp else 11.dp, vertical = 9.dp),
                ) {
                    Text(
                        if (allSelected) "Снять выбор" else "Выбрать все",
                        fontSize = (if (narrow) 10 else if (compactWidth) 11 else 12).sp,
                        lineHeight = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            HorizontalDivider(color = colors.outlineVariant)
        }
    }
}

@Composable
internal fun MessageSelectionToolbar(
    count: Int,
    compactWidth: Boolean,
    compactHeight: Boolean,
    enabled: Boolean,
    delete: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(Modifier.fillMaxWidth().background(colors.surface).testTag("message-selection-toolbar")) {
        val narrow = compactWidth && maxWidth < 360.dp
        val padding = if (narrow || compactHeight) 12.dp else if (compactWidth) 16.dp else 24.dp
        Column {
            HorizontalDivider(color = colors.outlineVariant)
            Row(
                Modifier.fillMaxWidth().heightIn(min = if (compactHeight) 50.dp else if (compactWidth) 75.dp else 79.dp)
                    .padding(horizontal = padding, vertical = if (compactHeight) 2.dp else 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Выбрано: $count",
                    Modifier.weight(1f),
                    fontSize = (if (narrow) 11 else 12).sp,
                    lineHeight = 17.sp,
                    color = colors.onSurfaceVariant,
                )
                MessageRemovalButton(
                    label = "Удалить ($count)",
                    modifier = Modifier.widthIn(min = if (narrow) 128.dp else if (compactWidth) 138.dp else 146.dp)
                        .testTag("message-selection-delete"),
                    enabled = enabled && count > 0,
                    onClick = delete,
                )
            }
        }
    }
}

@Composable
internal fun MessageSelectionIndicator(selected: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(modifier.size(44.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(24.dp).background(if (selected) colors.primary else colors.surface, CircleShape)
                .border(1.5.dp, if (selected) colors.primary else lerp(colors.outlineVariant, colors.onSurfaceVariant, .55f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(CallIcons.Check, null, Modifier.size(15.dp), tint = colors.onPrimary)
        }
    }
}

@Composable
internal fun SelectedMessagesDeleteDialog(
    count: Int,
    busy: Boolean,
    error: String?,
    dismiss: () -> Unit,
    confirm: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    ChatMessageModal("Удалить выбранные сообщения?", "message-selection-delete-dialog", busy, dismiss) { padding, heading ->
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(padding),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            heading()
            Text(
                buildAnnotatedString {
                    append("Выбрано сообщений: ")
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(count.toString()) }
                    append(".")
                },
                color = colors.onSurfaceVariant,
                fontSize = 13.sp,
                lineHeight = 22.sp,
            )
            Text(
                "Сообщения будут удалены из вашей истории на этом устройстве. У других участников они останутся.",
                color = colors.onSurfaceVariant,
                fontSize = 13.sp,
                lineHeight = 22.sp,
            )
            if (error != null) {
                Text(
                    error,
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    color = colors.error,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                )
            }
            MessageRemovalButton(
                "Удалить у меня ($count)",
                Modifier.fillMaxWidth().testTag("message-selection-confirm-delete"),
                enabled = count > 0 && !busy,
                onClick = confirm,
            )
            OutlinedButton(
                dismiss,
                Modifier.fillMaxWidth().heightIn(min = 46.dp).testTag("message-selection-cancel-delete"),
                enabled = !busy,
                shape = RoundedCornerShape(13.dp),
                border = BorderStroke(1.dp, colors.outlineVariant),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.onSurface),
            ) {
                Text("Отмена", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun MessageRemovalButton(label: String, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Button(
        onClick,
        modifier.heightIn(min = 46.dp),
        enabled = enabled,
        shape = RoundedCornerShape(13.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.error,
            contentColor = Color.White,
            disabledContainerColor = colors.error.copy(alpha = .42f),
            disabledContentColor = Color.White.copy(alpha = .85f),
        ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Icon(ChatMessageIcons.Delete, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun ChatMessageModal(
    title: String,
    tag: String,
    busy: Boolean,
    dismiss: () -> Unit,
    content: @Composable (padding: Dp, heading: @Composable () -> Unit) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val light = colors.surface.luminance() >= .5f
    Dialog(
        onDismissRequest = { if (!busy) dismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false, decorFitsSystemWindows = false),
    ) {
        SetChatDialogWindowAppearance(light, blurRadius = 5.dp)
        val onScrimTap by rememberUpdatedState { if (!busy) dismiss() }
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier.matchParentSize().background(if (light) Color(0x600F1B3C) else Color(0xA3050A12))
                    .pointerInput(Unit) { detectTapGestures { onScrimTap() } },
            )
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                val phone = maxWidth < 780.dp
                val margin = if (phone) 14.dp else 20.dp
                Surface(
                    Modifier.align(if (phone) Alignment.BottomCenter else Alignment.Center)
                        .padding(margin).widthIn(max = 440.dp).fillMaxWidth()
                        .pointerInput(Unit) { detectTapGestures { } }
                        .testTag(tag),
                    shape = RoundedCornerShape(if (phone) 23.dp else 24.dp),
                    color = colors.surface,
                    border = BorderStroke(1.dp, colors.outlineVariant),
                ) {
                    val padding = if (phone) 22.dp else 25.dp
                    content(padding) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                title,
                                Modifier.weight(1f).semantics { heading() },
                                fontSize = (if (phone) 20 else 21).sp,
                                lineHeight = (if (phone) 30 else 32).sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            IconButton(
                                dismiss,
                                Modifier.size(38.dp).testTag("$tag-close"),
                                enabled = !busy,
                            ) {
                                Icon(CallIcons.X, "Закрыть", Modifier.size(20.dp), tint = colors.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SetChatDialogWindowAppearance(lightSurface: Boolean, blurRadius: Dp) {
    val view = LocalView.current
    val window = (view.parent as? DialogWindowProvider)?.window
    val blur = with(androidx.compose.ui.platform.LocalDensity.current) { blurRadius.roundToPx() }
    SideEffect {
        window?.let {
            it.setDimAmount(0f)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && it.attributes.blurBehindRadius != blur) {
                it.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                it.attributes = it.attributes.apply { blurBehindRadius = blur }
            }
            WindowCompat.getInsetsController(it, view).apply {
                isAppearanceLightStatusBars = lightSurface
                isAppearanceLightNavigationBars = lightSurface
            }
        }
    }
}
