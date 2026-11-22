package com.meetspace.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.meetspace.core.data.MeetingInvites
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
    val folderAssignment: MeetingFolderAssignmentState = MeetingFolderAssignmentState(),
    val actionBusy: Boolean = false,
    val profileBusy: Boolean = false,
    val creationUncertain: Boolean = false,
    val mutationRevision: Long = 0,
    val avatarRevision: Long = 0,
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
    val updateAvatar: (String, ByteArray) -> Unit = { _, _ -> },
    val deleteAvatar: () -> Unit = {},
    val recordings: (String?) -> Unit = {},
    val acknowledgeUncertainCreate: () -> Unit = {},
    val clearMessage: () -> Unit = {},
    val invitations: MeetingRoomActions = MeetingRoomActions(),
    val folders: MeetingFolderActions = MeetingFolderActions(),
    val updateAccountProfile: (String?, String?) -> Unit = { _, _ -> },
)

private data class MeetingForm(val scheduled: Boolean, val meeting: Meeting? = null)

private enum class MeetingDetailPage { DETAILS, LINK, PEOPLE, FOLDERS }

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
    var detailPage by remember(state.detailId, repository.selectedProfileId, repository.session?.user?.id, tab) {
        mutableStateOf(MeetingDetailPage.DETAILS)
    }
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
        val meeting = state.detail.data
        val self = state.self.data
        val canShare = meeting != null && meeting.isKnownStatus && !meeting.isClosed
        val owner = meeting != null && meeting.ownerId == repository.session?.user?.id
        val canInvite = canShare && (owner || meeting?.status == "active" && self?.canParticipate == true && self.role == "co_host")
        val activePage = when {
            detailPage == MeetingDetailPage.PEOPLE && canInvite -> MeetingDetailPage.PEOPLE
            detailPage == MeetingDetailPage.LINK && canShare -> MeetingDetailPage.LINK
            detailPage == MeetingDetailPage.FOLDERS && meeting != null -> MeetingDetailPage.FOLDERS
            else -> MeetingDetailPage.DETAILS
        }
        when (activePage) {
            MeetingDetailPage.DETAILS -> MeetingDetailsDialog(state, owner, actions,
                share = { detailPage = MeetingDetailPage.LINK },
                addToFolder = { target ->
                    actions.folders.load(target.id)
                    detailPage = MeetingDetailPage.FOLDERS
                },
                edit = { form = MeetingForm(true, it) },
                confirm = { target, transition -> confirmation = target to transition },
            )
            MeetingDetailPage.LINK -> if (meeting != null) {
                val origin = repository.selectedProfile?.origin
                val invitation = meeting.inviteCode.ifBlank { meeting.inviteUrl }
                val link = remember(origin, invitation) {
                    origin?.let { runCatching { MeetingInvites.shareUrl(it, invitation) }.getOrNull() }
                }
                MeetingLinkInvitationDialog(meeting.title, link, onInviteParticipants = if (canInvite) {
                    {
                        actions.invitations.searchUsers("")
                        detailPage = MeetingDetailPage.PEOPLE
                    }
                } else null, close = { detailPage = MeetingDetailPage.DETAILS })
            }
            MeetingDetailPage.PEOPLE -> MeetingInvitationDialog(state.invitation, actions.invitations) {
                detailPage = MeetingDetailPage.LINK
            }
            MeetingDetailPage.FOLDERS -> if (meeting != null) {
                MeetingFolderDialog(meeting, state.folderAssignment, actions.folders, dismiss = {
                    actions.folders.close()
                    detailPage = MeetingDetailPage.DETAILS
                })
            }
        }
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
                MeetingActionLabel(if (transition == MeetingTransition.CANCEL) MeetingIcons.CancelCalendar else CallIcons.PhoneOff,
                    if (transition == MeetingTransition.CANCEL) "Отменить встречу" else "Завершить", "meeting-confirm-transition-icon")
            } },
            dismissButton = { TextButton(onClick = { confirmation = null }, enabled = !state.actionBusy) { MeetingActionLabel(CallIcons.X, "Оставить встречу", "meeting-dismiss-transition-icon") } },
        )
    }
}

@Composable
private fun MeetingDetailsDialog(
    state: MeetingsUiState,
    owner: Boolean,
    actions: MeetingsActions,
    share: () -> Unit,
    addToFolder: (Meeting) -> Unit,
    edit: (Meeting) -> Unit,
    confirm: (Meeting, MeetingTransition) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val close = { if (!state.actionBusy) actions.closeDetail() }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).widthIn(max = 510.dp)
                .fillMaxWidth().testTag("meeting-detail-dialog"),
            shape = RoundedCornerShape(24.dp),
            color = colors.surface,
            border = BorderStroke(1.dp, colors.outlineVariant),
        ) {
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 12.dp, top = 10.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(if (state.detail.data?.isClosed == true) "Материалы встречи" else "О встрече",
                        Modifier.weight(1f).semantics { heading() }, fontSize = 21.sp, lineHeight = 27.sp,
                        fontWeight = FontWeight.SemiBold)
                    IconButton(close, enabled = !state.actionBusy, modifier = Modifier.size(48.dp).testTag("meeting-detail-back")) {
                        Icon(CallIcons.X, "Закрыть сведения о встрече", Modifier.size(20.dp))
                    }
                }
                HorizontalDivider(color = colors.outlineVariant)
                Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 22.dp, vertical = 18.dp).testTag("meeting-detail"),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    MeetingsMessage(state.error, state.notice, actions.clearMessage)
                    MeetingLoadStatus(state.detail, actions.refreshDetail)
                    val meeting = state.detail.data
                    if (meeting != null) MeetingDetails(meeting, state, owner, actions, share, addToFolder, edit, confirm)
                }
            }
        }
    }
}

@Composable
private fun MeetingDetails(
    meeting: Meeting,
    state: MeetingsUiState,
    owner: Boolean,
    actions: MeetingsActions,
    share: () -> Unit,
    addToFolder: (Meeting) -> Unit,
    edit: (Meeting) -> Unit,
    confirm: (Meeting, MeetingTransition) -> Unit,
) {
    val self = state.self.data
    val colors = MaterialTheme.colorScheme
    val enabled = !state.actionBusy
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        MeetingStatus(meeting.status)
        Text(meeting.title, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() })
        Text(if (owner) "Вы — организатор" else "Встреча вашего пространства", style = MaterialTheme.typography.labelLarge,
            color = colors.onSurfaceVariant)
    }
    val times = listOfNotNull(
        meeting.scheduledAt?.let { "Запланирована: ${formatTime(it, state.zone)}" },
        meeting.plannedDurationMin?.let { "Плановая длительность: $it мин" },
        meeting.startedAt?.let { "Началась: ${formatTime(it, state.zone)}" },
        meeting.finishedAt?.let { "Завершилась: ${formatTime(it, state.zone)}" },
    )
    val open = !meeting.isClosed && meeting.isKnownStatus
    if (times.isNotEmpty() || open) {
        Surface(shape = RoundedCornerShape(14.dp), color = colors.background, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                times.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
                if (meeting.status == "scheduled") Text("Встреча не начнётся автоматически.",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                if (open) Text(if (meeting.waitingRoomEnabled) "Зал ожидания включён. По действительному приглашению можно войти без ожидания."
                    else "Зал ожидания отключён.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
    }

    if (open) {
        MeetingLoadStatus(state.self, actions.refreshDetail)
        if (self != null) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(participantLabel(self), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("meeting-membership"))
            if (self.status == "waiting" || self.admissionState == "waiting") {
                Text("Ожидайте решения организатора.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                TextButton(actions.refreshDetail, enabled = enabled && !state.detail.loading && !state.self.loading) {
                    Text("Проверить допуск")
                }
            }
        }
    }
    if (meeting.isClosed) {
        HorizontalDivider(color = colors.outlineVariant)
        Text("История встречи", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        MeetingLoadStatus(state.history, actions.refreshDetail)
        state.history.data?.let { history ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                history.ownerDisplayName?.takeIf { it.isNotBlank() }?.let { Text("Организатор: $it") }
                history.durationSec?.let { Text("Длительность: ${it / 60} мин ${it % 60} сек") }
                Text("Участников в истории: ${history.participantCount}")
                history.participants.forEach { person ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(person.displayName, style = MaterialTheme.typography.titleSmall)
                        Text(participantLabel(person), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                }
                if (history.participantsTruncated) Text("Сервер вернул ограниченную часть списка участников.", style = MaterialTheme.typography.bodySmall)
                if (history.recordings.ready > 0) Text("Доступные записи: ${history.recordings.ready}")
            }
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (open) {
            val call = actions.call
            val canCall = meeting.status == "active" && call != null && self?.admissionState !in listOf("kicked", "rejected", "waiting")
            if (canCall) {
                MeetingDetailAction("Подключиться к звонку", CallIcons.Video, "meeting-call", enabled, primary = true) { call(meeting) }
            }
            val canJoin = !state.self.loading && state.self.error == null &&
                (self == null || meeting.status == "active" && self.status == "left" && self.isAdmitted)
            if (canJoin) {
                val registration = meeting.status in listOf("created", "scheduled")
                val label = if (registration) "Зарегистрироваться на встречу" else "Войти во встречу"
                val icon = if (registration) WorkspaceHeaderIcons.Calendar else Icons.AutoMirrored.Outlined.Login
                MeetingDetailAction(label, icon, "meeting-detail-join", enabled, primary = !canCall) { actions.joinMeeting(meeting.id) }
            }
            if (owner && meeting.status in listOf("created", "scheduled")) {
                MeetingDetailAction("Начать встречу", CallIcons.Play, "meeting-detail-start", enabled, primary = true) {
                    actions.transition(meeting.id, MeetingTransition.START)
                }
                if (meeting.status == "scheduled") {
                    MeetingDetailAction("Изменить расписание", MeetingIcons.Edit, "meeting-detail-edit", enabled) { edit(meeting) }
                }
            }
            MeetingDetailAction("Пригласить по ссылке", CallIcons.Link, "meeting-invite-link-action", enabled, action = share)
            if (self?.status in listOf("joined", "waiting") && self?.admissionState !in listOf("kicked", "rejected")) {
                MeetingDetailAction("Выйти из встречи", CallIcons.LogOut, "meeting-detail-leave", enabled) { actions.leave(meeting.id) }
            }
        }
        MeetingDetailAction("Добавить в папку", ChatMessageIcons.FolderPlus, "meeting-add-to-folder", enabled) { addToFolder(meeting) }
        if (meeting.status == "active" || meeting.isClosed) {
            MeetingDetailAction("Записи и материалы", WorkspaceHeaderIcons.Recordings, "meeting-detail-recordings", enabled) {
                actions.recordings(meeting.id)
            }
        }
        if (owner && meeting.status in listOf("created", "scheduled")) {
            MeetingDetailAction("Отменить встречу", MeetingIcons.CancelCalendar, "meeting-detail-cancel", enabled, destructive = true) {
                confirm(meeting, MeetingTransition.CANCEL)
            }
        }
        if (owner && meeting.status == "active") {
            MeetingDetailAction("Завершить для всех", CallIcons.PhoneOff, "meeting-detail-finish", enabled, destructive = true) {
                confirm(meeting, MeetingTransition.FINISH)
            }
        }
    }
}

@Composable
private fun MeetingDetailAction(
    label: String,
    icon: ImageVector,
    tag: String,
    enabled: Boolean,
    primary: Boolean = false,
    destructive: Boolean = false,
    action: () -> Unit,
) {
    val modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)
    val shape = RoundedCornerShape(12.dp)
    val content: @Composable () -> Unit = { MeetingActionLabel(icon, label, "$tag-icon") }
    when {
        primary -> Button(action, modifier, enabled = enabled, shape = shape) { content() }
        destructive -> TextButton(action, modifier, enabled = enabled, shape = shape,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { content() }
        else -> OutlinedButton(action, modifier, enabled = enabled, shape = shape,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) { content() }
    }
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
internal fun MeetingDialogTitle(title: String, enabled: Boolean = true, close: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f).semantics { heading() }, fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold)
        IconButton(close, enabled = enabled, modifier = Modifier.size(48.dp)) {
            Icon(CallIcons.X, "Закрыть", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MeetingFormField(
    label: String,
    value: String,
    change: (String) -> Unit,
    enabled: Boolean,
    tag: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
        OutlinedTextField(value = value, onValueChange = change, shape = RoundedCornerShape(12.dp),
            placeholder = hint?.let { { Text(it, fontSize = 13.sp) } }, trailingIcon = trailing,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType), enabled = enabled, singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag(tag).semantics { contentDescription = label })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MeetingTimePicker(initial: LocalTime, dismiss: () -> Unit, select: (LocalTime) -> Unit) {
    val picker = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
    AlertDialog(onDismissRequest = dismiss, containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("Время встречи") }, text = { TimeInput(picker) },
        confirmButton = { TextButton({ select(LocalTime.of(picker.hour, picker.minute)); dismiss() }) { Text("Выбрать") } },
        dismissButton = { TextButton(dismiss) { Text("Отмена") } })
}

@Composable
private fun MeetingFormDialog(form: MeetingForm, state: MeetingsUiState, actions: MeetingsActions, onDismiss: () -> Unit) {
    val editing = form.meeting != null
    val initial = form.meeting?.scheduledAt?.atZone(state.zone) ?: Instant.now().plusSeconds(3600).atZone(state.zone)
    var title by remember(form) { mutableStateOf(form.meeting?.title.orEmpty()) }
    var planned by remember(form) { mutableStateOf(form.scheduled) }
    var editZone by remember(form) { mutableStateOf(false) }
    var selectDate by remember(form) { mutableStateOf(false) }
    var selectTime by remember(form) { mutableStateOf(false) }
    var durationMenu by remember(form) { mutableStateOf(false) }
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
        title = { MeetingDialogTitle(if (editing) "Изменить расписание" else if (planned) "Запланировать встречу" else "Новая встреча",
            enabled = !state.actionBusy, close = onDismiss) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (editing) Text(title, style = MaterialTheme.typography.titleMedium)
            else MeetingFormField("Название встречи", title, { title = it.take(400) }, !state.actionBusy,
                "meeting-form-title", hint = "Например, обсуждение проекта")
            if (!editing) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) { Text("Запланировать встречу", fontSize = 13.sp); Text("Выберите дату и время заранее", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Switch(planned, { planned = it }, enabled = !state.actionBusy, modifier = Modifier.testTag("meeting-form-planned"))
            }
            if (planned) {
                val dateField: @Composable (Modifier) -> Unit = { field ->
                    MeetingFormField("Дата", date, { date = it.take(10); offsetText = "" }, !state.actionBusy,
                        "meeting-form-date", field, "ГГГГ-ММ-ДД") {
                        IconButton({ selectDate = true }, enabled = !state.actionBusy) {
                            Icon(WorkspaceHeaderIcons.Calendar, "Выбрать дату встречи", Modifier.size(18.dp))
                        }
                    }
                }
                val timeField: @Composable (Modifier) -> Unit = { field ->
                    MeetingFormField("Время", time, { time = it.take(8); offsetText = "" }, !state.actionBusy,
                        "meeting-form-time", field, "ЧЧ:ММ") {
                        IconButton({ selectTime = true }, enabled = !state.actionBusy) {
                            Icon(MeetingIcons.Clock, "Выбрать время встречи", Modifier.size(18.dp))
                        }
                    }
                }
                if (LocalDensity.current.fontScale > 1.3f) { dateField(Modifier.fillMaxWidth()); timeField(Modifier.fillMaxWidth()) }
                else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { dateField(Modifier.weight(1f)); timeField(Modifier.weight(1f)) }
                MeetingFormField("Длительность, мин · необязательно", duration, { duration = it.take(4) }, !state.actionBusy,
                    "meeting-form-duration", keyboardType = KeyboardType.Number) {
                    Box {
                        IconButton({ durationMenu = true }, enabled = !state.actionBusy) {
                            Icon(CallIcons.ChevronDown, "Выбрать длительность встречи", Modifier.size(18.dp))
                        }
                        DropdownMenu(durationMenu, { durationMenu = false }, containerColor = MaterialTheme.colorScheme.surface) {
                            listOf(30 to "30 минут", 45 to "45 минут", 60 to "1 час", 90 to "1,5 часа").forEach { (minutes, label) ->
                                DropdownMenuItem(text = { Text(label) }, onClick = { duration = minutes.toString(); durationMenu = false })
                            }
                        }
                    }
                }
                TextButton({ editZone = !editZone }, enabled = !state.actionBusy, modifier = Modifier.testTag("meeting-form-zone-toggle")) { Text("$zoneText · изменить часовой пояс", fontSize = 11.sp) }
                if (editZone) MeetingFormField("Часовой пояс IANA", zoneText, { zoneText = it.take(80); offsetText = "" }, !state.actionBusy, "meeting-form-zone")
                if (offsets.size > 1) OutlinedTextField(shape = RoundedCornerShape(12.dp), value = offsetText, onValueChange = { offsetText = it.take(9) }, label = { Text("Выберите смещение UTC") }, supportingText = { Text("Это время встречается дважды: ${offsets.joinToString()}. Укажите нужное смещение.") }, enabled = !state.actionBusy, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("meeting-form-offset"))
            }
            if (!editing) Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) { Text("Зал ожидания", fontSize = 13.sp); Text("Участники с действующей ссылкой входят без ожидания", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Switch(checked = waiting, onCheckedChange = { waiting = it }, enabled = !state.actionBusy, modifier = Modifier.testTag("meeting-form-waiting"))
            }
            Text("Пригласите коллег по ссылке после создания. Встречу запускает организатор.", fontSize = 11.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            (localError ?: state.error)?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("meeting-form-error").semantics { liveRegion = LiveRegionMode.Polite }) }
        } },
        confirmButton = { Button(onClick = submit, enabled = !state.actionBusy && (!state.creationUncertain || editing), shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("meeting-form-submit")) {
            Text(if (state.actionBusy) "Сохраняем…" else if (editing) "Сохранить расписание" else if (planned) "Запланировать" else "Создать встречу")
        } },
    )
    if (selectDate) MeetingDatePicker("Дата встречи", runCatching { LocalDate.parse(date) }.getOrNull(),
        dismiss = { selectDate = false }, select = { date = it.toString(); offsetText = "" }, confirmTag = "meeting-form-date-confirm")
    if (selectTime) MeetingTimePicker(runCatching { LocalTime.parse(time) }.getOrDefault(initial.toLocalTime()),
        dismiss = { selectTime = false }, select = { time = it.format(DateTimeFormatter.ofPattern("HH:mm")); offsetText = "" })
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
        title = { MeetingDialogTitle("Войти по ссылке", enabled = !busy, close = dismiss) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Вставьте ссылку, которую прислал организатор встречи.", style = MaterialTheme.typography.bodySmall)
            MeetingFormField("Код или HTTPS-ссылка", value, { value = it.take(2048) }, !busy, "meeting-join-input",
                hint = "https://meet.example.org/…", keyboardType = KeyboardType.Uri)
            Text("Приглашение должно быть с вашего текущего сервера.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        } },
        confirmButton = { Button(onClick = { join(value.trim()) }, enabled = !busy && value.isNotBlank(), shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("meeting-join-submit")) {
            Text(if (busy) "Подключаем…" else "Продолжить")
            Spacer(Modifier.width(8.dp))
            Icon(ChatMessageIcons.Forward, null, Modifier.size(18.dp))
        } },
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
            IconButton(onClick = dismiss) { Icon(CallIcons.X, "Закрыть сообщение") }
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
