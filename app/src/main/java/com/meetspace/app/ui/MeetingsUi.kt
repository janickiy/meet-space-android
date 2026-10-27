package com.meetspace.app.ui

import android.content.ClipData
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.CallEnd
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.EditCalendar
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.VideoCall
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.DialogProperties
import com.meetspace.app.MeetingTime
import com.meetspace.core.designsystem.MeetSpaceTokens
import com.meetspace.core.model.Meeting
import com.meetspace.core.model.MeetingHistory
import com.meetspace.core.model.MeetingInput
import com.meetspace.core.model.MeetingPage
import com.meetspace.core.model.MeetingParticipant
import com.meetspace.core.model.MeetingTransition
import com.meetspace.core.model.MeetingsScope
import com.meetspace.core.model.MeetingsView
import com.meetspace.core.model.RepositoryState
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

/** Transient presentation data. Meetings and invitation codes are never saved in a Bundle. */
data class MeetingsLoad<T>(val data: T? = null, val loading: Boolean = false, val error: String? = null)

data class MeetingsUiState(
    val upcoming: MeetingsLoad<MeetingPage> = MeetingsLoad(),
    val active: MeetingsLoad<MeetingPage> = MeetingsLoad(),
    val list: MeetingsLoad<MeetingPage> = MeetingsLoad(),
    val listView: MeetingsView = MeetingsView.UPCOMING,
    val listScope: MeetingsScope = MeetingsScope.ALL,
    val listFrom: LocalDate? = null,
    val listTo: LocalDate? = null,
    val calendarFeeds: Map<MeetingsView, MeetingsLoad<MeetingPage>> = emptyMap(),
    val calendarMonth: YearMonth = YearMonth.now(),
    val calendarDay: LocalDate = LocalDate.now(),
    val calendarScope: MeetingsScope = MeetingsScope.ALL,
    val zone: ZoneId = ZoneId.systemDefault(),
    val detailId: String? = null,
    val detail: MeetingsLoad<Meeting> = MeetingsLoad(),
    val self: MeetingsLoad<MeetingParticipant> = MeetingsLoad(),
    val history: MeetingsLoad<MeetingHistory> = MeetingsLoad(),
    val invitation: MeetingRoomUiState = MeetingRoomUiState(),
    val actionBusy: Boolean = false,
    val profileBusy: Boolean = false,
    val creationUncertain: Boolean = false,
    val mutationRevision: Long = 0,
    val error: String? = null,
    val notice: String? = null,
)

data class MeetingsActions(
    val activate: (WorkspaceTab) -> Unit = {},
    val refreshHome: () -> Unit = {},
    val listFilter: (MeetingsView, MeetingsScope) -> Unit = { _, _ -> },
    val listPeriod: (LocalDate?, LocalDate?) -> Unit = { _, _ -> },
    val refreshList: () -> Unit = {},
    val moreList: () -> Unit = {},
    val calendarFilter: (MeetingsScope) -> Unit = {},
    val month: (YearMonth) -> Unit = {},
    val day: (LocalDate) -> Unit = {},
    val zone: (ZoneId) -> Unit = {},
    val refreshCalendar: () -> Unit = {},
    val moreCalendar: () -> Unit = {},
    val open: (String) -> Unit = {},
    val closeDetail: () -> Unit = {},
    val refreshDetail: () -> Unit = {},
    val create: (MeetingInput) -> Unit = {},
    val edit: (String, MeetingInput) -> Unit = { _, _ -> },
    val transition: (String, MeetingTransition) -> Unit = { _, _ -> },
    val join: (String) -> Unit = {},
    val joinMeeting: (String) -> Unit = {},
    val call: ((Meeting) -> Unit)? = null,
    val leave: (String) -> Unit = {},
    val updateProfile: (String) -> Unit = {},
    val recordings: (String?) -> Unit = {},
    val acknowledgeUncertainCreate: () -> Unit = {},
    val clearMessage: () -> Unit = {},
    val invitations: MeetingRoomActions = MeetingRoomActions(),
)

private data class MeetingForm(val scheduled: Boolean, val meeting: Meeting? = null)

/** Workspace data and mutations come exclusively from the authenticated repository. */
@Composable
fun MeetingsWorkspace(
    tab: WorkspaceTab,
    state: MeetingsUiState,
    repository: RepositoryState,
    actions: MeetingsActions,
    onTab: (WorkspaceTab) -> Unit,
    homeContent: (@Composable (() -> Unit, () -> Unit, () -> Unit) -> Unit)? = null,
    callsSearchVisible: Boolean = false,
    onFolders: () -> Unit = {},
    notificationBell: (@Composable () -> Unit)? = null,
) {
    var form by remember { mutableStateOf<MeetingForm?>(null) }
    var joinDialog by remember { mutableStateOf(false) }
    var zoneDialog by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<Pair<Meeting, MeetingTransition>?>(null) }
    LaunchedEffect(tab) { actions.activate(tab) }
    LaunchedEffect(state.mutationRevision) {
        if (state.mutationRevision > 0) {
            // A successful join may return the same meeting id that was already open.
            val openCompletedHomeMeeting = tab == WorkspaceTab.HOME && (form != null || joinDialog) && state.detailId != null
            form = null; joinDialog = false; confirmation = null
            if (openCompletedHomeMeeting) onTab(WorkspaceTab.CALLS)
        }
    }
    // Creation and joining open the existing detail route; Home remains a dashboard.
    var observedDetailId by remember { mutableStateOf(state.detailId) }
    LaunchedEffect(state.detailId) {
        if (state.detailId != observedDetailId && tab == WorkspaceTab.HOME && state.detailId != null) onTab(WorkspaceTab.CALLS)
        observedDetailId = state.detailId
    }
    BackHandler(enabled = state.detailId != null && !state.actionBusy, onBack = actions.closeDetail)
    val feedback: @Composable () -> Unit = {
    MeetingsMessage(state.error, state.notice, actions.clearMessage)
    if (state.actionBusy) Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text("Ожидаем ответ сервера…", modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
    if (state.creationUncertain) MeetingCard {
        Text("Результат создания неизвестен", style = MaterialTheme.typography.titleMedium)
        Text("Обновите список и проверьте, появилась ли встреча, прежде чем создавать её повторно.")
        OutlinedButton(onClick = { actions.listPeriod(null, null); if (state.listView == MeetingsView.UPCOMING && state.listScope == MeetingsScope.ALL) actions.refreshList() else actions.listFilter(MeetingsView.UPCOMING, MeetingsScope.ALL); onTab(WorkspaceTab.CALLS) }, enabled = !state.actionBusy) { Text("Проверить список встреч") }
        TextButton(onClick = actions.acknowledgeUncertainCreate, enabled = !state.actionBusy, modifier = Modifier.testTag("meeting-acknowledge-uncertain")) { Text("Я проверил список — разрешить новое создание") }
    }
    }
    if (tab == WorkspaceTab.HOME) {
        Column(Modifier.fillMaxSize()) {
            if (state.error != null || state.notice != null || state.actionBusy || state.creationUncertain) {
                Column(Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()).padding(16.dp)) { feedback() }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val create = { form = MeetingForm(false) }
                val schedule = { form = MeetingForm(true) }
                val join = { joinDialog = true }
                if (homeContent != null) homeContent(create, schedule, join)
                else HomeDashboard(repository, MessagingUiState(), MessagingActions(), state.actionBusy,
                    state.actionBusy || state.creationUncertain, create, { onTab(WorkspaceTab.MESSAGES) }, join, schedule,
                    { onTab(WorkspaceTab.MESSAGES) }, { onTab(WorkspaceTab.MESSAGES) }, { onTab(WorkspaceTab.PROFILE) })
            }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            if (tab == WorkspaceTab.CALLS) BoxWithConstraints(Modifier.fillMaxWidth()) {
                MeetingsHeader(maxWidth >= 780.dp, { onTab(WorkspaceTab.CALENDAR) }, { actions.recordings(null) }, notificationBell)
            }
            if (state.error != null || state.notice != null || state.actionBusy || state.creationUncertain) {
                Column(Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()).padding(16.dp)) { feedback() }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val overview: @Composable (Modifier) -> Unit = { pane ->
                    Box(pane) {
                        when (tab) {
                            WorkspaceTab.CALLS -> CallsDashboard(state, repository, actions, { form = MeetingForm(false) }, { form = MeetingForm(true) }, { joinDialog = true }, { zoneDialog = true }, onFolders)
                            WorkspaceTab.CALENDAR -> CalendarDashboard(state, actions, { form = MeetingForm(true) }, { zoneDialog = true })
                            WorkspaceTab.PROFILE -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) { MeetingsAccount(state, repository, actions) }
                            WorkspaceTab.HOME, WorkspaceTab.MESSAGES -> Unit
                        }
                    }
                }
                overview(Modifier.fillMaxSize())
            }
        }
    }
    if (state.detailId != null && tab in setOf(WorkspaceTab.CALLS, WorkspaceTab.CALENDAR)) {
        AlertDialog(onDismissRequest = { if (!state.actionBusy) actions.closeDetail() },
            modifier = Modifier.padding(horizontal = 20.dp).widthIn(max = 440.dp).fillMaxWidth().testTag("meeting-detail-dialog"),
            properties = DialogProperties(usePlatformDefaultWidth = false), containerColor = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(24.dp), tonalElevation = 0.dp,
            title = { Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (state.detail.data?.isClosed == true) "Материалы встречи" else "О встрече", Modifier.weight(1f), fontSize = 21.sp, lineHeight = 27.sp)
                IconButton(actions.refreshDetail, enabled = !state.actionBusy, modifier = Modifier.testTag("meeting-detail-refresh")) { Icon(Icons.Outlined.Refresh, "Обновить встречу и допуск") }
                IconButton(actions.closeDetail, enabled = !state.actionBusy, modifier = Modifier.testTag("meeting-detail-back")) { Icon(Icons.Outlined.Close, "Закрыть сведения о встрече") }
            } },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                MeetingsMessage(state.error, state.notice, actions.clearMessage)
                MeetingDetails(state, repository, actions, { form = MeetingForm(true, it) }, { meeting, transition -> confirmation = meeting to transition })
            } }, confirmButton = {})
    }
    form?.let { current -> MeetingFormDialog(current, state, actions, onDismiss = { form = null }) }
    if (joinDialog) MeetingJoinDialog(state.actionBusy, state.error, { joinDialog = false }, actions.join)
    if (zoneDialog) ZoneDialog(state.zone, { zoneDialog = false }) { actions.zone(it); zoneDialog = false }
    confirmation?.let { (meeting, transition) ->
        AlertDialog(
        modifier = Modifier.padding(horizontal = 20.dp).widthIn(max = 480.dp).fillMaxWidth(),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 0.dp,
            onDismissRequest = { if (!state.actionBusy) confirmation = null },
            title = { Text(if (transition == MeetingTransition.CANCEL) "Отменить встречу?" else "Завершить встречу?") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Встреча «${meeting.title}» ${if (transition == MeetingTransition.CANCEL) "будет отменена" else "будет завершена"} для всех участников. Это действие нельзя отменить.")
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { Button(onClick = { actions.transition(meeting.id, transition) }, enabled = !state.actionBusy, modifier = Modifier.testTag("meeting-confirm-transition")) {
                MeetingActionLabel(if (transition == MeetingTransition.CANCEL) Icons.Outlined.EventBusy else Icons.Outlined.CallEnd,
                    if (transition == MeetingTransition.CANCEL) "Отменить встречу" else "Завершить", "meeting-confirm-transition-icon")
            } },
            dismissButton = { TextButton(onClick = { confirmation = null }, enabled = !state.actionBusy) { MeetingActionLabel(Icons.Outlined.Close, "Оставить встречу", "meeting-dismiss-transition-icon") } },
        )
    }
}

@Composable
private fun MeetingDetails(state: MeetingsUiState, repository: RepositoryState, actions: MeetingsActions, edit: (Meeting) -> Unit, confirm: (Meeting, MeetingTransition) -> Unit) {
    val meeting = state.detail.data
    val self = state.self.data
    val owner = meeting?.ownerId == repository.session?.user?.id
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()
    var invite by remember(meeting?.id) { mutableStateOf(false) }
    var copied by remember(meeting?.id) { mutableStateOf(false) }
    var copyError by remember(meeting?.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag("meeting-detail"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        MeetingLoadStatus(state.detail, actions.refreshDetail)
        if (meeting != null) {
            MeetingStatus(meeting.status)
            Text(meeting.title, fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
            Text(if (owner) "Вы — организатор" else "Встреча вашего пространства", style = MaterialTheme.typography.labelLarge)
            meeting.scheduledAt?.let { Text("Запланирована: ${formatTime(it, state.zone)}") }
            meeting.plannedDurationMin?.let { Text("Плановая длительность: $it мин") }
            meeting.startedAt?.let { Text("Началась: ${formatTime(it, state.zone)}") }
            meeting.finishedAt?.let { Text("Завершилась: ${formatTime(it, state.zone)}") }
            if (meeting.status == "scheduled") Text("Встреча не начнётся автоматически.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!meeting.isClosed && meeting.isKnownStatus) {
                HorizontalDivider()
                Text(if (meeting.waitingRoomEnabled) "Включён зал ожидания. Действительное приглашение позволяет войти без ожидания." else "Зал ожидания отключён.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                MeetingLoadStatus(state.self, actions.refreshDetail)
                if (self != null) {
                    Text("Ваше членство: ${participantLabel(self)}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("meeting-membership"))
                    if (self.status == "waiting" || self.admissionState == "waiting") Text("Ожидайте решения организатора. Нажмите обновление, чтобы проверить допуск.")
                    if (self.canParticipate) Text("Допуск подтверждён. Можно подключить камеру и микрофон.", style = MaterialTheme.typography.bodyMedium)
                    if (self.status == "left" && self.isAdmitted) Text(if (meeting.status in listOf("created", "scheduled")) "Вы зарегистрированы. Организатор ещё не начал встречу." else "Вы сейчас не находитесь во встрече. Можно войти.", style = MaterialTheme.typography.bodyMedium)
                    if (self.status in listOf("joined", "waiting") && self.admissionState !in listOf("kicked", "rejected")) OutlinedButton(onClick = { actions.leave(meeting.id) }, enabled = !state.actionBusy, modifier = Modifier.fillMaxWidth().testTag("meeting-detail-leave")) { MeetingActionLabel(Icons.AutoMirrored.Outlined.Logout, "Выйти из встречи", "meeting-detail-leave-icon") }
                }
                if (meeting.status == "active" && actions.call != null && self?.admissionState !in listOf("kicked", "rejected", "waiting")) {
                    Button(onClick = { actions.call.invoke(meeting) }, enabled = !state.actionBusy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("meeting-call")) { MeetingActionLabel(Icons.Outlined.VideoCall, "Подключиться к звонку", "meeting-call-icon") }
                }
                val joinAllowed = !state.self.loading && state.self.error == null && (self == null || (meeting.status == "active" && self.status == "left" && self.isAdmitted))
                if (joinAllowed) Button(onClick = { actions.joinMeeting(meeting.id) }, enabled = !state.actionBusy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("meeting-detail-join")) {
                    val registration = meeting.status in listOf("created", "scheduled")
                    MeetingActionLabel(if (registration) Icons.Outlined.EventAvailable else Icons.AutoMirrored.Outlined.Login,
                        if (registration) "Зарегистрироваться на встречу" else "Войти во встречу", "meeting-detail-join-icon")
                }
                OutlinedButton(onClick = {
                    val origin = repository.selectedProfile?.origin ?: return@OutlinedButton
                    coroutineScope.launch {
                        runCatching { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Приглашение MeetSpace", "$origin/i/${meeting.inviteCode}"))) }
                            .onSuccess { copied = true; copyError = false }
                            .onFailure { copyError = true }
                    }
                }, enabled = meeting.inviteCode.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("meeting-copy-invite")) { MeetingActionLabel(if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy, if (copied) "Ссылка скопирована" else "Скопировать приглашение", "meeting-copy-invite-icon") }
                if (copyError) Text("Не удалось скопировать ссылку.", color = MaterialTheme.colorScheme.error)
                if (owner || meeting.status == "active" && self?.canParticipate == true && self.role == "co_host") OutlinedButton({ invite = true; actions.invitations.searchUsers("") }, modifier = Modifier.fillMaxWidth().testTag("meeting-invite-users")) { MeetingActionLabel(Icons.Outlined.PersonAdd, "Пригласить участников", "meeting-invite-users-icon") }
                if (owner && meeting.status in listOf("created", "scheduled")) {
                    Button(onClick = { actions.transition(meeting.id, MeetingTransition.START) }, enabled = !state.actionBusy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("meeting-detail-start")) { MeetingActionLabel(Icons.Outlined.PlayArrow, "Начать встречу", "meeting-detail-start-icon") }
                    if (meeting.status == "scheduled") OutlinedButton(onClick = { edit(meeting) }, enabled = !state.actionBusy, modifier = Modifier.fillMaxWidth().testTag("meeting-detail-edit")) { MeetingActionLabel(Icons.Outlined.EditCalendar, "Изменить расписание", "meeting-detail-edit-icon") }
                    TextButton(onClick = { confirm(meeting, MeetingTransition.CANCEL) }, enabled = !state.actionBusy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("meeting-detail-cancel")) { MeetingActionLabel(Icons.Outlined.EventBusy, "Отменить встречу", "meeting-detail-cancel-icon", MaterialTheme.colorScheme.error) }
                }
                if (owner && meeting.status == "active") TextButton(onClick = { confirm(meeting, MeetingTransition.FINISH) }, enabled = !state.actionBusy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("meeting-detail-finish")) { MeetingActionLabel(Icons.Outlined.CallEnd, "Завершить для всех", "meeting-detail-finish-icon", MaterialTheme.colorScheme.error) }
            }
            if (meeting.isClosed) {
                HorizontalDivider()
                Text("История встречи", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                MeetingLoadStatus(state.history, actions.refreshDetail)
                state.history.data?.let { history ->
                    history.ownerDisplayName?.takeIf { it.isNotBlank() }?.let { Text("Организатор: $it") }
                    history.durationSec?.let { Text("Длительность: ${it / 60} мин ${it % 60} сек") }
                    Text("Участников в истории: ${history.participantCount}")
                    history.participants.forEach { person ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(person.displayName, style = MaterialTheme.typography.titleSmall)
                            Text(participantLabel(person), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (history.participantsTruncated) Text("Сервер вернул ограниченную часть списка участников.", style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider()
                    if (history.recordings.ready > 0) Text("Доступные записи: ${history.recordings.ready}")
                }
            }
            if (meeting.status == "active" || meeting.isClosed) OutlinedButton(onClick = { actions.recordings(meeting.id) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("meeting-detail-recordings")) { MeetingActionLabel(Icons.Outlined.PlayCircleOutline, "Записи и материалы", "meeting-detail-recordings-icon") }
        }
    }
    if (invite) MeetingInvitationDialog(state.invitation, actions.invitations) { invite = false }
}

@Composable
private fun MeetingActionLabel(icon: ImageVector, text: String, iconTag: String, color: Color = LocalContentColor.current) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp).testTag(iconTag), tint = color)
    Spacer(Modifier.width(8.dp))
    Text(text, color = color)
}

@Composable
internal fun MeetingsAccount(state: MeetingsUiState, repository: RepositoryState, actions: MeetingsActions) {
    val user = repository.session?.user ?: return
    var name by remember(user.id, user.displayName) { mutableStateOf(user.displayName.orEmpty()) }
    val valid = name.trim().codePointCount(0, name.trim().length) in 1..100 && name.none { Character.isISOControl(it) }
    MeetingCard(Modifier.testTag("account-editor")) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(72.dp)) {
            Box(contentAlignment = Alignment.Center) { Text(user.displayLabel.trim().split(Regex("\\s+")).take(2).mapNotNull { it.firstOrNull()?.uppercaseChar() }.joinToString(""), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary) }
        }
        Text(user.displayLabel, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Text(user.email, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(if (user.isAdmin) "Администратор сервера" else "Участник пространства", style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(shape = RoundedCornerShape(12.dp), value = name, onValueChange = { name = it.take(200) }, label = { Text("Отображаемое имя") }, enabled = !state.profileBusy, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("account-name"), supportingText = { Text("От 1 до 100 символов") })
        Button(onClick = { actions.updateProfile(name.trim()) }, enabled = valid && name.trim() != user.displayName && !state.profileBusy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("account-save")) {
            Text(if (state.profileBusy) "Сохраняем…" else "Сохранить имя")
        }
        Text("Сейчас можно изменить только отображаемое имя.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MeetingFormDialog(form: MeetingForm, state: MeetingsUiState, actions: MeetingsActions, onDismiss: () -> Unit) {
    val editing = form.meeting != null
    val initial = form.meeting?.scheduledAt?.atZone(state.zone) ?: Instant.now().plusSeconds(3600).atZone(state.zone)
    var title by remember(form) { mutableStateOf(form.meeting?.title.orEmpty()) }
    var planned by remember(form) { mutableStateOf(form.scheduled) }
    var editZone by remember(form) { mutableStateOf(false) }
    var date by remember(form) { mutableStateOf(initial.toLocalDate().toString()) }
    var time by remember(form) { mutableStateOf(initial.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))) }
    var zoneText by remember(form) { mutableStateOf(state.zone.id) }
    var offsetText by remember(form) { mutableStateOf("") }
    var duration by remember(form) { mutableStateOf(form.meeting?.plannedDurationMin?.toString().orEmpty()) }
    var waiting by remember(form) { mutableStateOf(form.meeting?.waitingRoomEnabled ?: false) }
    var localError by remember { mutableStateOf<String?>(null) }
    val offsets = runCatching { val zone = ZoneId.of(zoneText); val local = LocalDate.parse(date).atTime(LocalTime.parse(time)); zone.rules.getValidOffsets(local) }.getOrDefault(emptyList())
    val submit = {
        try {
            require(title.trim().codePointCount(0, title.trim().length) in 1..200 && title.none { Character.isISOControl(it) }) { "Название должно содержать от 1 до 200 символов без управляющих знаков." }
            val at = if (planned) MeetingTime.resolve(LocalDate.parse(date), LocalTime.parse(time), ZoneId.of(zoneText), offsetText.takeIf { it.isNotBlank() }?.let(ZoneOffset::of)) else null
            if (at != null) require(at.isAfter(Instant.now())) { "Укажите время в будущем." }
            val minutes = if (planned && duration.isNotBlank()) duration.toIntOrNull().also { require(it != null && it in 1..1440) { "Длительность должна быть от 1 до 1440 минут." } } else null
            val input = MeetingInput(title.trim(), waiting, at, minutes)
            localError = null
            if (editing) actions.edit(form.meeting!!.id, input) else actions.create(input)
        } catch (failure: Exception) {
            localError = if (failure is IllegalArgumentException) failure.message ?: "Проверьте данные встречи." else "Проверьте дату ГГГГ-ММ-ДД, время ЧЧ:ММ и часовой пояс IANA."
        }
    }
    AlertDialog(
        modifier = Modifier.padding(horizontal = 20.dp).widthIn(max = 440.dp).fillMaxWidth(),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 0.dp,
        onDismissRequest = { if (!state.actionBusy) onDismiss() },
        title = { Text(if (editing) "Изменить расписание" else if (planned) "Запланировать встречу" else "Новая встреча", fontSize = 21.sp, lineHeight = 27.sp) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            if (editing) Text(title, style = MaterialTheme.typography.titleMedium)
            else OutlinedTextField(shape = RoundedCornerShape(12.dp), value = title, onValueChange = { title = it.take(400) }, label = { Text("Название встречи", fontSize = 13.sp) }, placeholder = { Text("Например, обсуждение проекта", fontSize = 13.sp) }, enabled = !state.actionBusy, modifier = Modifier.fillMaxWidth().testTag("meeting-form-title"))
            if (!editing) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) { Text("Запланировать встречу", fontSize = 13.sp); Text("Выберите дату и время заранее", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Switch(planned, { planned = it }, enabled = !state.actionBusy, modifier = Modifier.testTag("meeting-form-planned"))
            }
            if (planned) {
                val dateField: @Composable (Modifier) -> Unit = { field -> OutlinedTextField(shape = RoundedCornerShape(12.dp), value = date, onValueChange = { date = it.take(10); offsetText = "" }, label = { Text("Дата", fontSize = 13.sp) }, placeholder = { Text("ГГГГ-ММ-ДД", fontSize = 12.sp) }, textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), enabled = !state.actionBusy, singleLine = true, modifier = field.testTag("meeting-form-date")) }
                val timeField: @Composable (Modifier) -> Unit = { field -> OutlinedTextField(shape = RoundedCornerShape(12.dp), value = time, onValueChange = { time = it.take(8); offsetText = "" }, label = { Text("Время", fontSize = 13.sp) }, placeholder = { Text("ЧЧ:ММ", fontSize = 12.sp) }, textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), enabled = !state.actionBusy, singleLine = true, modifier = field.testTag("meeting-form-time")) }
                if (LocalDensity.current.fontScale > 1.3f) { dateField(Modifier.fillMaxWidth()); timeField(Modifier.fillMaxWidth()) }
                else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { dateField(Modifier.weight(1f)); timeField(Modifier.weight(1f)) }
                TextButton({ editZone = !editZone }, enabled = !state.actionBusy, modifier = Modifier.testTag("meeting-form-zone-toggle")) { Text("$zoneText · изменить часовой пояс", fontSize = 11.sp) }
                if (editZone) OutlinedTextField(shape = RoundedCornerShape(12.dp), value = zoneText, onValueChange = { zoneText = it.take(80); offsetText = "" }, label = { Text("Часовой пояс IANA") }, enabled = !state.actionBusy, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("meeting-form-zone"))
                if (offsets.size > 1) OutlinedTextField(shape = RoundedCornerShape(12.dp), value = offsetText, onValueChange = { offsetText = it.take(9) }, label = { Text("Выберите смещение UTC") }, supportingText = { Text("Это время встречается дважды: ${offsets.joinToString()}. Укажите нужное смещение.") }, enabled = !state.actionBusy, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("meeting-form-offset"))
                OutlinedTextField(shape = RoundedCornerShape(12.dp), value = duration, onValueChange = { duration = it.take(4) }, label = { Text("Длительность, мин · необязательно") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), enabled = !state.actionBusy, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("meeting-form-duration"))
            }
            if (!editing) Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) { Text("Зал ожидания", fontSize = 13.sp); Text("Для участников без допуска", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Switch(checked = waiting, onCheckedChange = { waiting = it }, enabled = !state.actionBusy, modifier = Modifier.testTag("meeting-form-waiting"))
            }
            if (!editing && waiting) Text("Действительная ссылка-приглашение позволяет войти без ожидания.", style = MaterialTheme.typography.bodySmall)
            Text("Пригласите коллег по ссылке после создания. Встречу запускает организатор.", fontSize = 11.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            (localError ?: state.error)?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("meeting-form-error").semantics { liveRegion = LiveRegionMode.Polite }) }
        } },
        confirmButton = { Button(onClick = submit, enabled = !state.actionBusy && (!state.creationUncertain || editing), modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).testTag("meeting-form-submit")) { Text(if (state.actionBusy) "Сохраняем…" else if (editing) "Сохранить расписание" else if (planned) "Запланировать" else "Создать встречу") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !state.actionBusy) { Text("Закрыть") } },
    )
}

@Composable
private fun MeetingJoinDialog(busy: Boolean, error: String?, dismiss: () -> Unit, join: (String) -> Unit) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        modifier = Modifier.padding(horizontal = 20.dp).widthIn(max = 480.dp).fillMaxWidth(),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 0.dp,
        onDismissRequest = { if (!busy) dismiss() },
        title = { Text("Войти по ссылке") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(shape = RoundedCornerShape(12.dp), value = value, onValueChange = { value = it.take(2048) }, label = { Text("Код или HTTPS-ссылка") }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("meeting-join-input"))
            Text("Принимается приглашение только вашего текущего сервера. Действительное приглашение подтверждает допуск; исключённым участникам сервер может отказать.", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        } },
        confirmButton = { Button(onClick = { join(value.trim()) }, enabled = !busy && value.isNotBlank(), modifier = Modifier.testTag("meeting-join-submit")) { Text(if (busy) "Подключаем…" else "Продолжить") } },
        dismissButton = { TextButton(onClick = dismiss, enabled = !busy) { Text("Закрыть") } },
    )
}

@Composable
private fun ZoneDialog(zone: ZoneId, dismiss: () -> Unit, save: (ZoneId) -> Unit) {
    var value by remember { mutableStateOf(zone.id) }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        modifier = Modifier.padding(horizontal = 20.dp).widthIn(max = 480.dp).fillMaxWidth(),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 0.dp,
        onDismissRequest = dismiss, title = { Text("Часовой пояс") },
        text = { OutlinedTextField(shape = RoundedCornerShape(12.dp), value = value, onValueChange = { value = it.take(80); error = false }, label = { Text("Часовой пояс IANA") }, isError = error, supportingText = { Text(if (error) "Неизвестный часовой пояс" else "Например: Europe/Moscow, Asia/Yekaterinburg") }, modifier = Modifier.fillMaxWidth().testTag("meeting-zone-input")) },
        confirmButton = { Button(onClick = { runCatching { ZoneId.of(value.trim()) }.onSuccess(save).onFailure { error = true } }, modifier = Modifier.testTag("meeting-zone-save")) { Text("Применить") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Закрыть") } },
    )
}

@Composable
internal fun ScopeFilter(scope: MeetingsScope, choose: (MeetingsScope) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("meeting-scope")) {
        MeetingsScope.entries.forEach { item -> FilterChip(selected = scope == item, onClick = { choose(item) }, label = { Text(when (item) { MeetingsScope.ALL -> "Мои встречи"; MeetingsScope.OWNED -> "Я организатор"; MeetingsScope.PARTICIPATING -> "Я участник" }) }, modifier = Modifier.testTag("meeting-scope-${item.name.lowercase()}")) }
    }
}

@Composable
private fun MeetingStatus(status: String) {
    Surface(shape = RoundedCornerShape(9.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Text(meetingStatusLabel(status), modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
internal fun <T> MeetingLoadStatus(load: MeetingsLoad<T>, retry: () -> Unit) {
    if (load.loading) Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text("Загружаем…", modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
    load.error?.let { error ->
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("meeting-load-error").semantics { liveRegion = LiveRegionMode.Polite }) {
            Text(error, color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = retry, enabled = !load.loading, modifier = Modifier.heightIn(min = 48.dp)) { Text("Повторить загрузку") }
        }
    }
}

@Composable
private fun MeetingsMessage(error: String?, notice: String?, dismiss: () -> Unit) {
    val text = error ?: notice ?: return
    Surface(color = if (error != null) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().testTag("meeting-message").semantics { liveRegion = LiveRegionMode.Polite }) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, modifier = Modifier.weight(1f), color = if (error != null) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer)
            IconButton(onClick = dismiss) { Icon(Icons.Outlined.Close, "Закрыть сообщение") }
        }
    }
}

@Composable
private fun MeetingCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = MeetSpaceTokens.CardShape, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

private fun formatTime(at: Instant, zone: ZoneId): String = at.atZone(zone).format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm XXX", Locale.forLanguageTag("ru")))
internal fun viewLabel(view: MeetingsView): String = when (view) { MeetingsView.UPCOMING -> "Предстоящие"; MeetingsView.ACTIVE -> "Активные"; MeetingsView.PAST -> "Завершённые" }
private fun participantLabel(person: MeetingParticipant): String = when {
    person.admissionState == "kicked" || person.status == "kicked" -> "Исключён из встречи"
    person.admissionState == "rejected" || person.status == "rejected" -> "Допуск отклонён"
    person.admissionState == "waiting" || person.status == "waiting" -> "Ожидает допуска"
    person.canParticipate -> "Допущен; членство активно"
    person.status == "left" && person.isAdmitted -> "Членство сохранено; сейчас не во встрече"
    else -> "Состояние не поддерживается приложением"
}

internal fun meetingStatusLabel(status: String): String = when (status) { "created" -> "Создана"; "scheduled" -> "Запланирована"; "active" -> "Активна"; "finished" -> "Завершена"; "cancelled" -> "Отменена"; else -> "Состояние не поддерживается приложением" }
