package com.meetspace.app.ui

import android.content.ClipData
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun MeetingLinkInvitationDialog(
    meetingTitle: String,
    invitationLink: String?,
    onInviteParticipants: (() -> Unit)?,
    close: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Dialog(close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp).widthIn(max = 680.dp).fillMaxWidth()
                .testTag("meeting-link-invite-dialog"),
            shape = RoundedCornerShape(24.dp),
            color = colors.surface,
            border = BorderStroke(1.dp, colors.outlineVariant),
        ) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Пригласить на встречу", Modifier.weight(1f).semantics { heading() },
                        fontSize = 22.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold,
                    )
                    IconButton(close, Modifier.testTag("meeting-invite-close")) {
                        Icon(CallIcons.X, "Закрыть окно приглашения", Modifier.size(20.dp))
                    }
                }
                Column(
                    Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState())
                        .padding(top = 12.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Text(meetingTitle, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
                    if (invitationLink == null) {
                        MeetingInvitationActions(null, onInviteParticipants, Modifier.fillMaxWidth())
                    } else {
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val wide = maxWidth / LocalDensity.current.fontScale >= 520.dp
                            if (wide) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                                    verticalAlignment = Alignment.Top,
                                ) {
                                    MeetingInvitationActions(invitationLink, onInviteParticipants, Modifier.weight(1f))
                                    MeetingInvitationQrFigure(invitationLink, Modifier.width(180.dp))
                                }
                            } else {
                                Column(
                                    Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(20.dp),
                                ) {
                                    MeetingInvitationActions(invitationLink, onInviteParticipants, Modifier.fillMaxWidth())
                                    MeetingInvitationQrFigure(invitationLink, Modifier.width(160.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MeetingInvitationActions(link: String?, inviteParticipants: (() -> Unit)?, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember(link) { mutableStateOf(false) }
    var copyFailed by remember(link) { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (link == null) {
            Text("Ссылка на встречу недоступна.", color = colors.onSurfaceVariant, fontSize = 14.sp)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Ссылка-приглашение", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 50.dp).border(1.dp, colors.outlineVariant, shape)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    SelectionContainer {
                        Text(link, Modifier.testTag("meeting-invite-link"), fontSize = 16.sp, lineHeight = 22.sp)
                    }
                }
            }
            Button(
                onClick = {
                    scope.launch {
                        try {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Приглашение MeetSpace", link)))
                            copied = true
                            copyFailed = false
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            copied = false
                            copyFailed = true
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("meeting-invite-copy"),
                shape = shape,
            ) {
                Icon(if (copied) CallIcons.Check else ChatMessageIcons.Copy, null, Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    if (copied) "Ссылка скопирована" else "Скопировать ссылку",
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold,
                )
            }
            if (copyFailed) {
                Text(
                    "Не удалось скопировать ссылку. Её можно выделить вручную.",
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    color = colors.error, fontSize = 13.sp, lineHeight = 18.sp,
                )
            }
        }
        if (inviteParticipants != null) {
            OutlinedButton(
                inviteParticipants, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("meeting-invite-by-name"),
                shape = shape, border = BorderStroke(1.dp, colors.outlineVariant),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.onSurface),
            ) {
                Icon(ChatMessageIcons.UserPlus, null, Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text("Пригласить по имени или email", fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            }
        }
        if (link != null) {
            Text("По ссылке можно присоединиться без аккаунта.", color = colors.onSurfaceVariant, fontSize = 14.sp, lineHeight = 20.sp)
        }
    }
}

@Composable
private fun MeetingInvitationQrFigure(link: String, modifier: Modifier) {
    val matrix = rememberInvitationQr(link) ?: return
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        InvitationQrCode(
            matrix,
            Modifier.fillMaxWidth().aspectRatio(1f).background(Color.White, shape).border(1.dp, colors.outlineVariant, shape)
                .padding(6.dp).testTag("meeting-invite-qr"),
        )
        Text(
            "Подключиться с телефона", Modifier.padding(top = 7.dp),
            fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
        )
        Text("Наведите камеру на QR-код", color = colors.onSurfaceVariant, fontSize = 11.sp, lineHeight = 16.sp, textAlign = TextAlign.Center)
    }
}
