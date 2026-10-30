package com.meetspace.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meetspace.core.media.CallState
import kotlin.math.max

/** UI phases are mapped from authoritative signaling and media state by the call host. */
enum class PersonalCallUiPhase { OUTGOING, INCOMING, CONNECTING, CONNECTED, RECONNECTING, ENDED, FAILED }

/** No account, call, video or elapsed time is invented by the presentation layer. */
data class PersonalCallUiState(
    val callId: String,
    val peerName: String,
    val selfName: String,
    val phase: PersonalCallUiPhase,
    val media: CallState = CallState(),
    val elapsedSeconds: Long? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val controlsEnabled: Boolean = true,
    val statusText: String? = null,
)

/** Permissions, signaling, media effects and navigation remain owned by the host. */
data class PersonalCallActions(
    val minimize: () -> Unit = {},
    val microphone: () -> Unit = {},
    val camera: () -> Unit = {},
    val share: () -> Unit = {},
    val end: () -> Unit = {},
    val accept: () -> Unit = {},
    val decline: () -> Unit = {},
    val switchCamera: () -> Unit = {},
)

private object PersonalCallColors {
    val Background = Color(0xFF141E30)
    val Foreground = Color(0xFFF1F5FF)
    val Muted = Color(0xFF9EAFC8)
    val Line = Color(0xFF334157)
    val StageCenter = Color(0xFF2D3A58)
    val StageMiddle = Color(0xFF212D44)
    val StageEdge = Color(0xFF1B263C)
    val Control = Color(0xFF2B3951)
    val ControlLine = Color(0xFF40516C)
    val Selected = Color(0xFFD9E5FA)
    val SelectedForeground = Color(0xFF213451)
    val Danger = Color(0xFFDB4F65)
    val DangerLine = Color(0xFFE5697C)
    val Connected = Color(0xFF93D8C2)
}

/** Native counterpart of personal-call.js/css. Minimizing opens the existing personal thread. */
@Composable
fun PersonalCallUi(state: PersonalCallUiState, actions: PersonalCallActions, modifier: Modifier = Modifier) {
    BackHandler(onBack = actions.minimize)
    Surface(modifier.fillMaxSize().testTag("personal-call-screen"), color = PersonalCallColors.Background,
        contentColor = PersonalCallColors.Foreground) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            val phone = maxWidth < 600.dp
            val short = maxHeight < 400.dp
            val veryShort = maxHeight < 250.dp || maxHeight.value / LocalDensity.current.fontScale < 180f
            Column(Modifier.fillMaxSize().then(if (veryShort) Modifier.verticalScroll(rememberScrollState()) else Modifier)) {
                PersonalCallHeader(state, actions.minimize, phone, short)
                state.error?.takeIf(String::isNotBlank)?.let { error ->
                    Text(error, color = Color(0xFFFFC5CE), fontSize = 12.sp, lineHeight = 18.sp,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = if (phone) 16.dp else 25.dp, vertical = 6.dp)
                            .testTag("personal-call-error").semantics { liveRegion = LiveRegionMode.Assertive })
                }
                PersonalCallStage(state, actions, phone,
                    Modifier.fillMaxWidth().then(if (veryShort) Modifier.height(180.dp) else Modifier.weight(1f))
                        .padding(horizontal = if (phone) 12.dp else 24.dp))
                PersonalCallFooter(state, actions, phone, short)
            }
        }
    }
}

@Composable
private fun PersonalCallHeader(state: PersonalCallUiState, minimize: () -> Unit, phone: Boolean, short: Boolean) {
    val connected = state.phase == PersonalCallUiPhase.CONNECTED
    val clock = state.elapsedSeconds?.takeIf { state.phase in setOf(PersonalCallUiPhase.CONNECTED, PersonalCallUiPhase.RECONNECTING) }
    Row(Modifier.fillMaxWidth().padding(horizontal = if (phone) 16.dp else 25.dp,
        vertical = if (short) 6.dp else if (phone) 12.dp else 16.dp),
        horizontalArrangement = Arrangement.spacedBy(if (phone) 10.dp else 13.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(minimize, Modifier.size(48.dp).testTag("personal-call-minimize")) {
            val shape = RoundedCornerShape(if (phone) 11.dp else 13.dp)
            Box(Modifier.size(if (phone) 34.dp else 40.dp, if (phone) 36.dp else 40.dp).clip(shape)
                .background(Color(0xFF263248)).border(1.dp, Color(0xFF3A465B), shape), contentAlignment = Alignment.Center) {
                Icon(CallIcons.ChevronDown, "Свернуть звонок и открыть переписку", Modifier.size(22.dp), tint = Color(0xFFE2E9F7))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("Личный звонок", fontSize = if (phone) 16.sp else 18.sp, lineHeight = if (phone) 22.sp else 26.sp,
                fontWeight = FontWeight.SemiBold, letterSpacing = (-.4).sp, modifier = Modifier.semantics { heading() })
            if (!short) Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (connected) Icon(Icons.Outlined.Lock, null, Modifier.size(12.dp), tint = Color(0xFF8E9FB8))
                Text(when (state.phase) {
                    PersonalCallUiPhase.OUTGOING -> "Соединяем вас с собеседником"
                    PersonalCallUiPhase.INCOMING -> "Входящий звонок"
                    PersonalCallUiPhase.CONNECTING -> "Устанавливаем соединение"
                    PersonalCallUiPhase.CONNECTED -> "Соединение установлено"
                    PersonalCallUiPhase.RECONNECTING -> "Восстанавливаем соединение"
                    PersonalCallUiPhase.ENDED -> "Звонок завершён"
                    PersonalCallUiPhase.FAILED -> "Не удалось соединиться"
                }, color = Color(0xFF8E9FB8), fontSize = 10.sp, lineHeight = 15.sp)
            }
        }
        if (clock != null) Row(Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0xFF21304A))
            .border(1.dp, Color(0xFF35465E), RoundedCornerShape(20.dp)).padding(horizontal = if (phone) 9.dp else 12.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Phone, null, Modifier.size(13.dp), tint = Color(0xFF78CFB6))
            Text(personalCallElapsed(clock), fontSize = if (phone) 11.sp else 12.sp, lineHeight = 17.sp,
                color = Color(0xFFBDD0F7), modifier = Modifier.testTag("personal-call-elapsed").semantics { contentDescription = "Длительность звонка ${personalCallElapsed(clock)}" })
        }
    }
}

@Composable
private fun PersonalCallStage(state: PersonalCallUiState, actions: PersonalCallActions, phone: Boolean, modifier: Modifier) {
    val media = state.media
    val remote = media.participants.firstOrNull { it.id != media.participantId }
    val remoteScreen = media.videos.firstOrNull { it.screen && it.participantId != media.participantId }
    val remoteCamera = media.videos.firstOrNull { !it.screen && it.participantId != media.participantId }
    val connected = state.phase in setOf(PersonalCallUiPhase.CONNECTED, PersonalCallUiPhase.RECONNECTING)
    val feed = if (connected) remoteScreen ?: remoteCamera else null
    val shape = RoundedCornerShape(if (phone) 24.dp else 26.dp)
    val speaking = remote?.let { media.isSpeaking(it.id) } == true
    BoxWithConstraints(modifier.speakingStatus(speaking).clip(shape).drawBehind {
        drawRect(Brush.radialGradient(if (speaking) listOf(SpeakingBackground, Color(0xFF204535), Color(0xFF132F24)) else listOf(PersonalCallColors.StageCenter, PersonalCallColors.StageMiddle, PersonalCallColors.StageEdge),
            center = Offset(size.width * .48f, size.height * .42f), radius = max(size.width, size.height) * .75f))
    }.border(if (speaking) 2.dp else 1.dp, if (speaking) SpeakingGreen else PersonalCallColors.Line, shape).testTag("personal-call-stage")) {
        val compact = maxHeight < 420.dp || maxHeight.value / LocalDensity.current.fontScale < 350f
        val short = maxHeight < 190.dp || maxHeight.value / LocalDensity.current.fontScale < 170f
        if (feed != null) {
            Box(Modifier.fillMaxSize().testTag(if (remoteScreen != null) "personal-call-remote-screen" else "personal-call-remote-video")) {
                VideoContent(feed.feed, remoteScreen != null)
                if (speaking) Box(Modifier.fillMaxSize().background(SpeakingGreen.copy(alpha = .09f)))
            }
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color(0xB3141E30))
                .padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(state.peerName.ifBlank { "Собеседник" }, fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(if (remoteScreen != null) "Собеседник демонстрирует экран" else personalCallStatus(state),
                    fontSize = 12.sp, lineHeight = 18.sp, color = PersonalCallColors.Connected,
                    modifier = Modifier.testTag("personal-call-status").semantics { liveRegion = LiveRegionMode.Polite })
            }
        } else {
            val avatarSize = if (short) 52.dp else if (phone && compact) 82.dp else if (phone) 112.dp else 150.dp
            if (short) Row(Modifier.align(Alignment.Center).fillMaxWidth()
                .padding(start = 18.dp, end = if (phone) 94.dp else 160.dp, top = 10.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                PersonalPeerAvatar(state.peerName, avatarSize, false)
                PersonalPeerInfo(state, remote?.camera, remoteCamera != null, short = true, phone = phone, modifier = Modifier.weight(1f))
            } else Column(Modifier.align(Alignment.Center).fillMaxWidth()
                .padding(start = if (phone) 14.dp else 26.dp, end = if (phone) 14.dp else 26.dp,
                    top = if (phone) (if (compact) 52.dp else 80.dp) else 26.dp, bottom = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                PersonalPeerAvatar(state.peerName, avatarSize, true)
                Spacer(Modifier.height(if (compact) 21.dp else if (phone) 27.dp else 28.dp))
                PersonalPeerInfo(state, remote?.camera, remoteCamera != null, short = false, phone = phone)
            }
        }
        if (state.phase !in setOf(PersonalCallUiPhase.ENDED, PersonalCallUiPhase.FAILED)) {
            PersonalSelfPreview(state, actions.switchCamera, phone, compact, short,
                Modifier.align(Alignment.TopEnd).padding(if (phone) 14.dp else 22.dp))
        }
        if (media.sharing) Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(if (phone) 8.dp else 16.dp)
            .clip(RoundedCornerShape(12.dp)).background(Color(0xFF2D4570)).border(1.dp, Color(0xFF647FB5), RoundedCornerShape(12.dp))
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp).testTag("personal-call-sharing"),
            horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(CallIcons.MonitorUp, null, Modifier.size(19.dp))
            Text("Вы демонстрируете экран", fontSize = 11.sp, lineHeight = 17.sp, modifier = Modifier.weight(1f))
            TextButton(actions.share, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp).testTag("personal-call-stop-share"),
                colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFCAD9FF))) { Text("Остановить", fontSize = 11.sp) }
        }
    }
}

@Composable
private fun PersonalPeerAvatar(name: String, size: Dp, rings: Boolean) {
    Box(contentAlignment = Alignment.Center) {
        if (rings) Canvas(Modifier.size(size)) {
            drawCircle(Color(0x05829ECE), radius = this.size.minDimension / 2f + 33.dp.toPx())
            drawCircle(Color(0x08829ECE), radius = this.size.minDimension / 2f + 16.dp.toPx())
        }
        Box(Modifier.size(size).clip(CircleShape).background(Color(0xFF425981))
            .border(1.dp, Color(0x527890B7), CircleShape), contentAlignment = Alignment.Center) {
            Text(personalInitials(name), fontSize = if (size <= 52.dp) 20.sp else if (size <= 82.dp) 28.sp else if (size <= 112.dp) 37.sp else 48.sp,
                lineHeight = if (size <= 52.dp) 27.sp else if (size <= 82.dp) 38.sp else 58.sp, fontWeight = FontWeight.Medium, letterSpacing = (-1).sp,
                color = Color(0xFFE3EEFF), modifier = Modifier.clearAndSetSemantics { })
        }
    }
}

@Composable
private fun PersonalPeerInfo(state: PersonalCallUiState, remoteCamera: Boolean?, hasRemoteFeed: Boolean,
    short: Boolean, phone: Boolean, modifier: Modifier = Modifier) {
    val connected = state.phase == PersonalCallUiPhase.CONNECTED
    val waiting = state.phase in setOf(PersonalCallUiPhase.OUTGOING, PersonalCallUiPhase.INCOMING, PersonalCallUiPhase.CONNECTING)
    Column(modifier, horizontalAlignment = if (short) Alignment.Start else Alignment.CenterHorizontally) {
        Text(state.peerName.ifBlank { "Собеседник" }, fontSize = if (short) 19.sp else if (phone) 25.sp else 27.sp,
            lineHeight = if (short) 25.sp else if (phone) 31.sp else 33.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-.7).sp,
            textAlign = if (short) TextAlign.Start else TextAlign.Center, maxLines = if (short) 2 else 3, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("personal-call-peer").semantics { heading() })
        Text(personalCallStatus(state), fontSize = if (short) 11.sp else 13.sp, lineHeight = if (short) 16.sp else 19.sp,
            color = if (connected) PersonalCallColors.Connected else Color(0xFFABBCD8), textAlign = if (short) TextAlign.Start else TextAlign.Center,
            modifier = Modifier.padding(top = if (short) 6.dp else 12.dp).testTag("personal-call-status").semantics { liveRegion = LiveRegionMode.Polite })
        if (!short && connected && remoteCamera == false && !hasRemoteFeed) {
            Row(Modifier.padding(top = if (phone) 15.dp else 20.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(CallIcons.VideoOff, null, Modifier.size(13.dp), tint = Color(0xFF90A4C0))
                Text("Камера собеседника выключена", color = Color(0xFF90A4C0), fontSize = 10.sp, lineHeight = 16.sp,
                    modifier = Modifier.testTag("personal-call-remote-camera-off"))
            }
        }
        if (!short && waiting) Row(Modifier.padding(top = 21.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(3) { Box(Modifier.size(5.dp).clip(CircleShape).background(Color(0xFF9EB7EB))) }
        }
    }
}

@Composable
private fun PersonalSelfPreview(state: PersonalCallUiState, switchCamera: () -> Unit, phone: Boolean, compact: Boolean,
    short: Boolean, modifier: Modifier) {
    val width = if (short || (phone && compact)) 62.dp else if (phone) 78.dp else 124.dp
    val height = if (short || (phone && compact)) 78.dp else if (phone) 102.dp else 150.dp
    val shape = RoundedCornerShape(if (phone) 14.dp else 18.dp)
    val feed = state.media.localVideo?.takeIf { state.media.camera }
    val speaking = state.media.isSpeaking(state.media.participantId)
    Box(modifier.size(width, height).speakingStatus(speaking).clip(shape).background(if (speaking) SpeakingBackground else Color(0xFF344158))
        .border(if (speaking) 2.dp else 1.dp, if (speaking) SpeakingGreen else Color(0xFF516079), shape)
        .then(if (feed != null && !state.busy && state.controlsEnabled) Modifier.clickable(role = Role.Button,
            onClickLabel = "Сменить камеру", onClick = switchCamera) else Modifier)
        .testTag("personal-call-self").semantics(mergeDescendants = true) {
            contentDescription = "Ваше видео. " + (if (state.media.camera) "Камера включена. " else "Камера выключена. ") +
                if (state.media.microphone) "Микрофон включён" else "Микрофон выключен"
        }, contentAlignment = Alignment.Center) {
        if (feed != null) VideoContent(feed, false)
        else Box(Modifier.size(if (short || (phone && compact)) 27.dp else if (phone) 34.dp else 47.dp)
            .clip(CircleShape).background(Color(0xFF455574)).border(1.dp, Color(0xFF64779B), CircleShape), contentAlignment = Alignment.Center) {
            Text(personalInitials(state.selfName), color = Color(0xFFD9E5FF), fontSize = if (phone) 11.sp else 15.sp,
                lineHeight = if (phone) 15.sp else 21.sp,
                modifier = Modifier.clearAndSetSemantics { })
        }
        if (speaking && feed != null) Box(Modifier.fillMaxSize().background(SpeakingGreen.copy(alpha = .09f)))
        Icon(if (state.media.camera) CallIcons.Video else CallIcons.VideoOff, null,
            Modifier.align(Alignment.TopEnd).padding(8.dp).size(12.dp), tint = if (state.media.camera) Color(0xFFA4DFCB) else Color(0xFFB0BFD5))
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(if (phone) 6.dp else 9.dp)
            .clip(RoundedCornerShape(if (phone) 7.dp else 9.dp)).background(Color(0xBF152139))
            .padding(horizontal = if (phone) 6.dp else 9.dp, vertical = if (phone) 5.dp else 7.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Вы", color = Color(0xFFDCE6F9), fontSize = if (phone) 9.sp else 10.sp, lineHeight = 14.sp)
            Box(Modifier.clip(RoundedCornerShape(4.dp)).background(if (speaking) SpeakingGreen else Color.Transparent).padding(2.dp)) {
                Icon(if (state.media.microphone) CallIcons.Mic else CallIcons.MicOff, null, Modifier.size(if (phone) 10.dp else 12.dp),
                    tint = if (speaking) Color(0xFF103622) else Color(0xFFDCE6F9))
            }
        }
    }
}

@Composable
private fun PersonalCallFooter(state: PersonalCallUiState, actions: PersonalCallActions, phone: Boolean, short: Boolean) {
    // A failed media connection can still have an accepted server call/lease to end.
    val terminal = state.phase == PersonalCallUiPhase.ENDED
    val incoming = state.phase == PersonalCallUiPhase.INCOMING
    val enabled = state.controlsEnabled && !state.busy
    // The engine accepts device changes in PREJOIN/CONNECTED. During negotiation/recovery
    // an enabled-looking toggle would silently lose its action; ending remains available.
    val devicesEnabled = enabled && state.phase in setOf(PersonalCallUiPhase.OUTGOING, PersonalCallUiPhase.INCOMING, PersonalCallUiPhase.CONNECTED)
    Column(Modifier.fillMaxWidth().padding(horizontal = if (phone) 16.dp else 24.dp,
        vertical = if (short) 10.dp else 17.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (!short) Text(when (state.phase) {
            PersonalCallUiPhase.OUTGOING -> "Собеседник услышит вас после ответа"
            PersonalCallUiPhase.INCOMING -> "Ответьте, чтобы начать разговор"
            PersonalCallUiPhase.CONNECTING -> "Подключаем аудио и видео"
            PersonalCallUiPhase.CONNECTED -> "Разговор один на один"
            PersonalCallUiPhase.RECONNECTING -> "Сохраняем звонок во время восстановления связи"
            PersonalCallUiPhase.ENDED -> "Переписка с собеседником остаётся доступна"
            PersonalCallUiPhase.FAILED -> "Завершите звонок, чтобы освободить соединение"
        }, color = PersonalCallColors.Muted, fontSize = if (phone) 10.sp else 11.sp, lineHeight = 16.sp,
            textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = if (phone) 18.dp else 17.dp))
        if (terminal) Button(actions.minimize, modifier = Modifier.heightIn(min = 48.dp).testTag("personal-call-return-chat"),
            colors = ButtonDefaults.buttonColors(containerColor = PersonalCallColors.Selected, contentColor = PersonalCallColors.SelectedForeground)) {
            Text("Вернуться к переписке")
        } else Row(Modifier.widthIn(max = 520.dp).fillMaxWidth(), horizontalArrangement = if (phone) Arrangement.SpaceAround else Arrangement.spacedBy(25.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.Top) {
            PersonalCallControl(if (state.media.microphone) "Микрофон" else "Без звука", if (state.media.microphone) CallIcons.Mic else CallIcons.MicOff,
                "personal-call-microphone", devicesEnabled && !state.media.microphoneBlocked, actions.microphone, state.media.microphone,
                if (state.media.microphone) "Выключить микрофон" else "Включить микрофон", phone,
                speaking = state.media.isSpeaking(state.media.participantId), microphoneLevel = state.media.microphoneLevel(state.media.participantId))
            PersonalCallControl("Камера", if (state.media.camera) CallIcons.Video else CallIcons.VideoOff,
                "personal-call-camera", devicesEnabled && !state.media.cameraBlocked, actions.camera, state.media.camera,
                if (state.media.camera) "Выключить камеру" else "Включить камеру", phone)
            if (incoming) {
                PersonalCallControl("Отклонить", CallIcons.PhoneOff, "personal-call-decline", !state.busy, actions.decline,
                    null, "Отклонить входящий звонок", phone, danger = true)
                PersonalCallControl("Ответить", Icons.Outlined.Phone, "personal-call-accept", enabled, actions.accept,
                    null, "Принять входящий звонок", phone, accept = true)
            } else {
                PersonalCallControl("Экран", CallIcons.MonitorUp, "personal-call-share",
                    enabled && !state.media.screenBlocked && (state.phase == PersonalCallUiPhase.CONNECTED || state.media.sharing),
                    actions.share, state.media.sharing, if (state.media.sharing) "Остановить демонстрацию экрана" else "Начать демонстрацию экрана", phone)
                PersonalCallControl("Завершить", CallIcons.PhoneOff, "personal-call-end", enabled, actions.end,
                    null, if (state.phase == PersonalCallUiPhase.OUTGOING) "Отменить исходящий звонок" else "Завершить звонок", phone, danger = true)
            }
        }
    }
}

@Composable
private fun PersonalCallControl(label: String, icon: ImageVector, tag: String, enabled: Boolean, action: () -> Unit,
    selected: Boolean?, description: String, phone: Boolean, danger: Boolean = false, accept: Boolean = false, speaking: Boolean = false, microphoneLevel: Float = 0f) {
    val background = when { danger -> PersonalCallColors.Danger; accept -> Color(0xFF83CCB6); selected == true -> PersonalCallColors.Selected; else -> PersonalCallColors.Control }
    val border = when { danger -> PersonalCallColors.DangerLine; accept -> Color(0xFF9DE0CC); selected == true -> PersonalCallColors.Selected; else -> PersonalCallColors.ControlLine }
    val foreground = when { danger -> Color.White; accept || selected == true -> PersonalCallColors.SelectedForeground; else -> Color(0xFFADBDD7) }
    val interaction = if (selected != null) Modifier.toggleable(selected, enabled = enabled, role = Role.Switch, onValueChange = { action() })
        else Modifier.clickable(enabled = enabled, role = Role.Button, onClick = action)
    Column(Modifier.width(if (phone) 65.dp else 75.dp).heightIn(min = 76.dp).then(interaction).testTag(tag)
        .semantics(mergeDescendants = true) { contentDescription = description; if (selected != null) stateDescription = if (speaking && enabled) "Говорит" else if (selected) "Включён" else "Выключен" },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Box(Modifier.size(if (phone) 54.dp else 58.dp, if (phone) 52.dp else 54.dp).clip(RoundedCornerShape(18.dp))
            .background(background.copy(alpha = if (enabled) 1f else .48f)).border(1.dp, border.copy(alpha = if (enabled) 1f else .48f), RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center) {
            if (tag == "personal-call-microphone" && selected == true) MicrophoneLevelIcon(microphoneLevel, enabled,
                foreground.copy(alpha = if (enabled) 1f else .6f), null, Modifier.size(23.dp).testTag("$tag-icon"))
            else Icon(icon, null, Modifier.size(23.dp), tint = foreground.copy(alpha = if (enabled) 1f else .6f))
        }
        Text(label, fontSize = 10.sp, lineHeight = 15.sp, textAlign = TextAlign.Center,
            color = (if (danger) Color(0xFFFFD2D9) else Color(0xFFADBDD7)).copy(alpha = if (enabled) 1f else .6f))
    }
}

private fun personalCallStatus(state: PersonalCallUiState): String = state.statusText?.takeIf(String::isNotBlank) ?: when (state.phase) {
    PersonalCallUiPhase.OUTGOING -> "Вы звоните…"
    PersonalCallUiPhase.INCOMING -> "Вам звонят…"
    PersonalCallUiPhase.CONNECTING -> "Соединяем…"
    PersonalCallUiPhase.CONNECTED -> "На связи"
    PersonalCallUiPhase.RECONNECTING -> "Восстанавливаем связь…"
    PersonalCallUiPhase.ENDED -> "Звонок завершён"
    PersonalCallUiPhase.FAILED -> "Не удалось соединиться"
}

private fun personalInitials(name: String): String = name.trim().split(Regex("\\s+")).filter(String::isNotBlank).take(2)
    .mapNotNull { it.firstOrNull()?.uppercaseChar() }.joinToString("").ifBlank { "?" }

private fun personalCallElapsed(seconds: Long): String {
    val elapsed = seconds.coerceAtLeast(0)
    return "${(elapsed / 60).toString().padStart(2, '0')}:${(elapsed % 60).toString().padStart(2, '0')}"
}
