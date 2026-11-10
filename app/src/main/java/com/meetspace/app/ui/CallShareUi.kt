package com.meetspace.app.ui

import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.floor

private data class CallShareColors(val paper: Color, val card: Color, val line: Color, val text: Color,
    val muted: Color, val accent: Color, val soft: Color, val buttonText: Color)

private val lightCallShare = CallShareColors(Color.White, Color(0xFFF3EFF5), Color(0xFFE6DFE9), Color(0xFF27222D),
    Color(0xFF685D70), Color(0xFF693D78), Color(0xFFEEE5F2), Color.White)
private val darkCallShare = CallShareColors(Color(0xFF25202B), Color(0xFF302936), Color(0xFF493D50), Color(0xFFF6F0F8),
    Color(0xFFC4B8CB), Color(0xFFD8B3E6), Color(0xFF46334F), Color(0xFF271A2D))

@Composable
internal fun CallShareDialog(title: String, invitationLink: String?, onInviteParticipants: (() -> Unit)?, close: () -> Unit) {
    val colors = if (MaterialTheme.colorScheme.background.luminance() < .5f) darkCallShare else lightCallShare
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var feedback by remember(invitationLink) { mutableStateOf<String?>(null) }

    Dialog(close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 680.dp).fillMaxWidth().testTag("call-share-dialog"),
                shape = RoundedCornerShape(20.dp), color = colors.paper, contentColor = colors.text) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Поделиться встречей", Modifier.weight(1f).semantics { heading() },
                            fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                        IconButton(close, Modifier.testTag("call-share-close")) {
                            Icon(CallIcons.X, "Закрыть окно приглашения", Modifier.size(20.dp), tint = colors.muted)
                        }
                    }
                    Text(title, color = colors.muted, fontSize = 14.sp)
                    if (invitationLink == null) Text("Ссылка на встречу недоступна.", color = colors.muted, fontSize = 14.sp)
                    else CallInvitationCard(invitationLink, colors) {
                        scope.launch {
                            feedback = try {
                                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Приглашение MeetSpace", invitationLink)))
                                "Ссылка скопирована"
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                "Не удалось скопировать ссылку. Её можно выделить и скопировать вручную."
                            }
                        }
                    }
                    feedback?.let { Text(it, color = colors.muted, fontSize = 13.sp,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                    CallShareActions(colors, onInviteParticipants, invitationLink?.let { link -> {
                        try {
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, title)
                                putExtra(Intent.EXTRA_TEXT, link)
                            }, null))
                        } catch (_: android.content.ActivityNotFoundException) {
                            feedback = "Не удалось открыть меню отправки. Скопируйте ссылку."
                        }
                    } })
                }
            }
        }
    }
}

@Composable
private fun CallInvitationCard(link: String, colors: CallShareColors, copy: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    BoxWithConstraints(Modifier.fillMaxWidth().background(colors.card, shape).border(1.dp, colors.line, shape).padding(16.dp)) {
        if (maxWidth / LocalDensity.current.fontScale >= 480.dp) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                CallInvitationLink(link, colors, copy, Modifier.weight(1f))
                CallInvitationQr(link, colors)
            }
        } else {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                CallInvitationLink(link, colors, copy, Modifier.fillMaxWidth())
                CallInvitationQr(link, colors)
            }
        }
    }
}

@Composable
private fun CallInvitationLink(link: String, colors: CallShareColors, copy: () -> Unit, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Ссылка на встречу", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text("Поделитесь ссылкой или отсканируйте QR-код, чтобы присоединиться с другого устройства.",
            color = colors.muted, fontSize = 13.sp, lineHeight = 19.sp)
        val shape = RoundedCornerShape(10.dp)
        Row(Modifier.fillMaxWidth().background(colors.paper, shape).border(1.dp, colors.line, shape).padding(4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SelectionContainer(Modifier.weight(1f).padding(horizontal = 6.dp, vertical = 4.dp)) {
                Text(link, fontSize = 13.sp, lineHeight = 17.sp, modifier = Modifier.testTag("call-share-link"))
            }
            TextButton(copy, Modifier.heightIn(min = 48.dp).background(colors.soft, RoundedCornerShape(7.dp))
                .testTag("call-share-copy"), colors = ButtonDefaults.textButtonColors(contentColor = colors.accent),
                contentPadding = PaddingValues(horizontal = 8.dp)) {
                Icon(Icons.Outlined.ContentCopy, null, Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text("Копировать", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun CallInvitationQr(link: String, colors: CallShareColors) {
    val qr by key(link) {
        produceState<BitMatrix?>(null) {
            value = withContext(Dispatchers.Default) {
                runCatching {
                    QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, 0, 0,
                        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 4))
                }.getOrNull()
            }
        }
    }
    qr?.let { matrix ->
        Column(Modifier.width(162.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val shape = RoundedCornerShape(12.dp)
            Canvas(Modifier.fillMaxWidth().aspectRatio(1f).background(Color.White, shape).border(1.dp, colors.line, shape)
                .padding(4.dp).testTag("call-share-qr").semantics { contentDescription = "QR-код ссылки на встречу" }) {
                val module = floor(minOf(size.width, size.height) / matrix.width).coerceAtLeast(1f)
                val xOffset = floor((size.width - matrix.width * module) / 2f)
                val yOffset = floor((size.height - matrix.height * module) / 2f)
                for (row in 0 until matrix.height) for (column in 0 until matrix.width) {
                    if (matrix[column, row]) drawRect(Color(0xFF211927),
                        topLeft = Offset(xOffset + column * module, yOffset + row * module), size = Size(module, module))
                }
            }
            Text("Подключиться с телефона", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Text("Наведите камеру на QR-код", color = colors.muted, fontSize = 11.sp, textAlign = TextAlign.Center)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CallShareActions(colors: CallShareColors, invite: (() -> Unit)?, share: (() -> Unit)?) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        share?.let { send -> TextButton(send, Modifier.heightIn(min = 48.dp).testTag("call-share-native"),
            colors = ButtonDefaults.textButtonColors(contentColor = colors.accent)) {
            Icon(Icons.Outlined.Share, null, Modifier.size(18.dp))
            Spacer(Modifier.width(7.dp))
            Text("Поделиться")
        } }
        invite?.let { open -> Button(open, Modifier.heightIn(min = 48.dp).testTag("call-share-invite"),
            shape = RoundedCornerShape(10.dp), colors = ButtonDefaults.buttonColors(containerColor = colors.accent,
                contentColor = colors.buttonText)) {
            Icon(Icons.Outlined.MailOutline, null, Modifier.size(18.dp))
            Spacer(Modifier.width(7.dp))
            Text("Пригласить участников")
        } }
    }
}
