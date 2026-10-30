package com.meetspace.app

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.roundToInt

/** The crop is a draft; only Save hands an encoded image to the upload owner. */
@Composable
internal fun AvatarCropDialog(
    title: String, source: Bitmap?, crop: AvatarCrop, loading: Boolean, saving: Boolean, error: String?,
    onCrop: (AvatarCrop) -> Unit, onChoose: () -> Unit, onSave: () -> Unit, onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = { if (!saving) onDismiss() }, properties = DialogProperties(
        usePlatformDefaultWidth = false, dismissOnBackPress = !saving, dismissOnClickOutside = !saving)) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp), contentAlignment = Alignment.Center) {
            val availableHeight = maxHeight
            Surface(Modifier.widthIn(max = 960.dp).fillMaxWidth().heightIn(max = availableHeight).testTag("avatar-editor"),
                color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(24.dp)) {
                BoxWithConstraints {
                    val dialogWidth = maxWidth
                    // Keep controls beside the preview on tablets, including before a photo is chosen.
                    // Large text and narrow windows use the scrollable single-column layout.
                    val wide = maxWidth >= 560.dp && LocalDensity.current.fontScale < 1.5f
                    val inset = if (wide) 24.dp else 20.dp
                    val enabled = !loading && !saving
                    Column {
                        Row(Modifier.fillMaxWidth().padding(start = inset, end = inset, top = 12.dp, bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontSize = if (wide) 23.sp else 19.sp)
                            IconButton(onDismiss, enabled = !saving, modifier = Modifier.size(48.dp).testTag("avatar-editor-close")) {
                                Icon(Icons.Outlined.Close, "Закрыть редактор фото")
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                            .padding(inset).testTag("avatar-editor-content"), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            if (wide) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(13.dp)),
                                        contentAlignment = Alignment.Center) {
                                        Icon(Icons.Outlined.CameraAlt, null, tint = MaterialTheme.colorScheme.primary)
                                    }
                                    Text("Выберите фото и настройте, как оно будет выглядеть.",
                                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                                    AvatarTabletPreview(source, crop, onCrop, enabled,
                                        Modifier.weight(1f).height(380.dp))
                                    AvatarCropControls(source, crop, enabled, onCrop, onChoose,
                                        Modifier.width(if (dialogWidth >= 800.dp) 260.dp else 220.dp), wide = true)
                                }
                            } else if (source == null) {
                                Surface(onClick = onChoose, enabled = enabled, color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = RoundedCornerShape(19.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .5f)),
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp).testTag("avatar-choose")) {
                                    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Icon(Icons.Outlined.FileUpload, null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.primary)
                                        Text("Выбрать изображение", style = MaterialTheme.typography.titleSmall)
                                        Text("JPG, PNG или WebP · до 8 МБ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                Text(if (loading) "Подготавливаем изображение…" else "Выберите фото, затем настройте область и масштаб.",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    AvatarCropStage(source, crop, onCrop, enabled, Modifier.widthIn(max = 240.dp).fillMaxWidth())
                                    Text("Перетащите фото или используйте ползунки.", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                AvatarCropControls(source, crop, enabled, onCrop, onChoose, Modifier.fillMaxWidth(), wide = false)
                            }
                            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("avatar-editor-loading"))
                        }
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 72.dp).verticalScroll(rememberScrollState())
                                .padding(horizontal = inset, vertical = 10.dp).testTag("avatar-editor-error")) }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(Modifier.fillMaxWidth().padding(horizontal = inset, vertical = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                            if (wide && dialogWidth >= 800.dp) Text("Фото увидят участники встреч и чатов.",
                                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedButton(onDismiss, enabled = !saving, shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.heightIn(min = 48.dp).testTag("avatar-cancel")) { Text("Отмена") }
                            if (source != null || wide) Button(onSave, enabled = source != null && enabled, shape = RoundedCornerShape(12.dp),
                                modifier = (if (wide) Modifier else Modifier.weight(1f)).heightIn(min = 48.dp).testTag("avatar-save")) {
                                if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                else Icon(Icons.Outlined.Check, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(7.dp)); Text(if (saving) "Сохраняем…" else "Сохранить фото")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AvatarTabletPreview(
    source: Bitmap?, crop: AvatarCrop, onCrop: (AvatarCrop) -> Unit, enabled: Boolean, modifier: Modifier,
) {
    Surface(modifier.testTag("avatar-editor-preview-panel"), shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.background, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            val preview = Modifier.widthIn(max = 280.dp).fillMaxWidth().aspectRatio(1f)
            if (source == null) {
                Box(preview.clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer)
                    .border(5.dp, MaterialTheme.colorScheme.surface, CircleShape).testTag("avatar-preview-placeholder"),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.PersonOutline, "Превью фото", Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
                }
            } else AvatarCropStage(source, crop, onCrop, enabled,
                preview.border(5.dp, MaterialTheme.colorScheme.surface, CircleShape), round = true)
            Spacer(Modifier.height(14.dp))
            Text("Так вас увидят участники", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AvatarCropControls(
    source: Bitmap?, crop: AvatarCrop, enabled: Boolean, onCrop: (AvatarCrop) -> Unit, onChoose: () -> Unit,
    modifier: Modifier, wide: Boolean,
) {
    Column(modifier.testTag("avatar-editor-controls"), verticalArrangement = Arrangement.spacedBy(if (wide) 12.dp else 5.dp)) {
        val choose: @Composable () -> Unit = {
            OutlinedButton(onChoose, enabled = enabled, shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("avatar-choose")) {
                Icon(Icons.Outlined.FileUpload, null, Modifier.size(18.dp)); Spacer(Modifier.width(7.dp))
                Text(if (source == null) "Выбрать файл" else "Заменить фото")
            }
        }
        if (wide) {
            choose()
            Text("JPG, PNG или WebP\nРазмер файла — до 8 МБ", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val canCrop = enabled && source != null
        val metrics = source?.let { avatarCropMetrics(it.width, it.height, 320f, crop) }
        AvatarCropSlider("Масштаб", crop.zoom, 1f..3f, canCrop, "avatar-zoom") { onCrop(crop.copy(zoom = it).normalized()) }
        AvatarCropSlider("По горизонтали", crop.x, -1f..1f, canCrop && metrics != null && metrics.maxOffsetX > .01f, "avatar-x",
            percentage = ((crop.x + 1f) * 50).roundToInt()) { onCrop(crop.copy(x = it)) }
        AvatarCropSlider("По вертикали", crop.y, -1f..1f, canCrop && metrics != null && metrics.maxOffsetY > .01f, "avatar-y",
            percentage = ((crop.y + 1f) * 50).roundToInt()) { onCrop(crop.copy(y = it)) }
        if (wide) TextButton({ onCrop(AvatarCrop()) }, enabled = canCrop,
            contentPadding = PaddingValues(horizontal = 4.dp), modifier = Modifier.heightIn(min = 48.dp).testTag("avatar-reset")) {
            Icon(Icons.Outlined.RestartAlt, null, Modifier.size(19.dp)); Spacer(Modifier.width(7.dp)); Text("Сбросить кадр")
        } else {
            OutlinedButton({ onCrop(AvatarCrop()) }, enabled = canCrop,
                shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("avatar-reset")) { Text("Сбросить") }
            choose()
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun AvatarCropSlider(
    label: String, value: Float, range: ClosedFloatingPointRange<Float>, enabled: Boolean, tag: String,
    percentage: Int = (value * 100).roundToInt(), change: (Float) -> Unit,
) {
    val active = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    val inactive = MaterialTheme.colorScheme.outlineVariant
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), fontSize = 13.sp)
            Text("$percentage%", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value = value, onValueChange = change, valueRange = range, enabled = enabled,
            thumb = { Box(Modifier.size(16.dp).background(active, CircleShape)) },
            track = { slider -> Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                val fraction = ((slider.value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
                val y = size.height / 2
                drawLine(inactive, Offset(0f, y), Offset(size.width, y), strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
                drawLine(active, Offset(0f, y), Offset(size.width * fraction, y), strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
            } },
            modifier = Modifier.fillMaxWidth().testTag(tag).semantics { contentDescription = label })
    }
}

@Composable
private fun AvatarCropStage(
    source: Bitmap, crop: AvatarCrop, onCrop: (AvatarCrop) -> Unit, enabled: Boolean, modifier: Modifier, round: Boolean = false,
) {
    val latestCrop by rememberUpdatedState(crop)
    val latestChange by rememberUpdatedState(onCrop)
    var gestureCrop by remember(source) { mutableStateOf(crop) }
    AvatarCropPreview(source, crop, modifier.aspectRatio(1f).clip(if (round) CircleShape else RoundedCornerShape(20.dp))
        .background(Color(0xFF182130)).testTag("avatar-crop-stage").semantics { contentDescription = "Область обрезки фотографии" }
        .pointerInput(source, enabled) {
            if (enabled) detectDragGestures(onDragStart = { gestureCrop = latestCrop }) { change, pan ->
                change.consume()
                gestureCrop = gestureCrop.pan(pan.x, pan.y, source.width, source.height, size.width.toFloat())
                latestChange(gestureCrop)
            }
        }, guide = true)
}

@Composable
private fun AvatarCropPreview(source: Bitmap, crop: AvatarCrop, modifier: Modifier, guide: Boolean = false) {
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG) }
    Canvas(modifier) {
        // The server stores JPEG, so transparent pixels must preview the same white background.
        drawRect(Color.White)
        val metrics = avatarCropMetrics(source.width, source.height, size.width, crop)
        drawIntoCanvas { canvas -> canvas.nativeCanvas.drawBitmap(source, null,
            RectF(metrics.left, metrics.top, metrics.left + metrics.scaledWidth, metrics.top + metrics.scaledHeight), paint) }
        if (guide) {
            val shade = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(androidx.compose.ui.geometry.Rect(Offset.Zero, size))
                addOval(androidx.compose.ui.geometry.Rect(Offset.Zero, size))
            }
            drawPath(shade, Color(0x80080D18))
            drawCircle(Color.White.copy(alpha = .93f), radius = size.width / 2 - 1.dp.toPx(), style = Stroke(2.dp.toPx()))
        }
    }
}
