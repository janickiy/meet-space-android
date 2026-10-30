package com.meetspace.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meetspace.app.ui.MeetingRoomUiState
import com.meetspace.app.ui.MeetingRoomActions
import kotlinx.coroutines.delay
import com.meetspace.app.ui.MeetingsActions
import com.meetspace.app.ui.MeetingsLoad
import com.meetspace.app.ui.MeetingsUiState
import com.meetspace.app.ui.WorkspaceTab
import com.meetspace.core.data.FoundationRepository
import com.meetspace.core.data.MeetingsRepository
import com.meetspace.core.model.*
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/** Requests and memory belong to one selected server/account generation. No persisted meeting cache. */
class MeetingsViewModel(
    private val foundation: FoundationRepository,
    private val repository: MeetingsRepository,
    private val clock: Clock = Clock.systemUTC(),
) : ViewModel() {
    private val mutableState = MutableStateFlow(freshState())
    val state = mutableState.asStateFlow()
    private val jobs = mutableMapOf<String, Job>()
    private val tickets = mutableMapOf<String, Long>()
    private var generation = 0L
    private var accountScope: String? = scope()
    private var creationReconciled = false

    init {
        viewModelScope.launch {
            foundation.state.collect {
                val next = scope()
                if (next != accountScope) {
                    suspendWork()
                    accountScope = next
                }
            }
        }
    }

    val actions: MeetingsActions get() = MeetingsActions(
        activate = ::activate, refreshHome = ::refreshHome, listFilter = ::listFilter, listPeriod = ::listPeriod,
        refreshList = ::refreshList, moreList = ::moreList, calendarFilter = ::calendarFilter,
        month = ::month, day = ::day, zone = ::zone, refreshCalendar = ::refreshCalendar,
        moreCalendar = ::moreCalendar, open = ::open, closeDetail = ::closeDetail,
        refreshDetail = ::refreshDetail, create = ::create, edit = ::edit,
        transition = ::transition, join = ::join, joinMeeting = ::joinMeeting, leave = ::leave,
        updateProfile = ::updateProfile, updateAvatar = ::updateAvatar, deleteAvatar = ::deleteAvatar,
        acknowledgeUncertainCreate = ::acknowledgeUncertainCreate,
        clearMessage = ::clearMessage, invitations = MeetingRoomActions(searchUsers = ::searchInviteUsers, invite = ::inviteUsers),
    )

    /** Called before logout/profile switches so a pending GET cannot delay credential cleanup. */
    fun suspendWork() {
        generation++
        jobs.values.toList().forEach(Job::cancel)
        jobs.clear()
        tickets.clear()
        creationReconciled = false
        mutableState.value = freshState(state.value.zone)
    }

    fun activate(tab: WorkspaceTab) {
        if (scope() == null) return
        when (tab) {
            WorkspaceTab.HOME -> {
                if (state.value.upcoming.data == null && !state.value.upcoming.loading && state.value.upcoming.error == null) loadHome(MeetingsView.UPCOMING)
                if (state.value.active.data == null && !state.value.active.loading && state.value.active.error == null) loadHome(MeetingsView.ACTIVE)
            }
            WorkspaceTab.CALLS -> if (state.value.list.data == null && !state.value.list.loading && state.value.list.error == null) refreshList()
            WorkspaceTab.CALENDAR -> if (state.value.calendarFeeds.isEmpty()) refreshCalendar()
            WorkspaceTab.PROFILE, WorkspaceTab.MESSAGES -> Unit
        }
    }

    fun refreshHome() {
        loadHome(MeetingsView.UPCOMING)
        loadHome(MeetingsView.ACTIVE)
    }

    private fun loadHome(view: MeetingsView) {
        val previous = if (view == MeetingsView.UPCOMING) state.value.upcoming else state.value.active
        homeFeed(view, previous.copy(loading = true, error = null))
        request("home-$view", failure = { homeFeed(view, previous.copy(loading = false, error = it.message)) }) {
            val page = repository.timeline(MeetingsQuery(view = view, limit = 6))
            checkCurrent()
            homeFeed(view, MeetingsLoad(page))
            if (view == MeetingsView.UPCOMING && state.value.creationUncertain) creationReconciled = true
        }
    }

    private fun homeFeed(view: MeetingsView, feed: MeetingsLoad<MeetingPage>) = mutableState.update {
        if (view == MeetingsView.UPCOMING) it.copy(upcoming = feed) else it.copy(active = feed)
    }

    fun listFilter(view: MeetingsView, scope: MeetingsScope) {
        if (state.value.listView == view && state.value.listScope == scope) return
        cancel("list")
        mutableState.update { it.copy(listView = view, listScope = scope, list = MeetingsLoad()) }
        refreshList()
    }

    fun refreshList() = loadList(append = false)
    fun listPeriod(from: LocalDate?, to: LocalDate?) {
        if (from != null && to != null && from > to) return
        if (state.value.listFrom == from && state.value.listTo == to) return
        cancel("list")
        mutableState.update { it.copy(listFrom = from, listTo = to, list = MeetingsLoad()) }
        refreshList()
    }
    fun moreList() { if (!state.value.list.loading && state.value.list.data?.nextCursor != null) loadList(append = true) }

    private fun loadList(append: Boolean) {
        val current = state.value
        val previous = current.list
        val cursor = previous.data?.nextCursor.takeIf { append }
        if (append && cursor == null) return
        mutableState.update { it.copy(list = previous.copy(loading = true, error = null)) }
        request("list", failure = { mutableState.update { state -> state.copy(list = previous.copy(loading = false, error = it.message)) } }) {
            val page = repository.timeline(MeetingsQuery(current.listView, current.listScope,
                from = current.listFrom?.atStartOfDay(current.zone)?.toInstant(),
                // Timeline uses inclusive bounds and PostgreSQL timestamps have microsecond precision.
                to = current.listTo?.plusDays(1)?.atStartOfDay(current.zone)?.toInstant()?.minusNanos(1_000), cursor = cursor))
            checkCurrent()
            mutableState.update { it.copy(list = MeetingsLoad(mergePage(previous.data, page, cursor, append))) }
            if (current.listView == MeetingsView.UPCOMING && current.listScope != MeetingsScope.PARTICIPATING &&
                current.listFrom == null && current.listTo == null && state.value.creationUncertain) creationReconciled = true
        }
    }

    fun month(month: YearMonth) {
        if (month == state.value.calendarMonth) return
        mutableState.update { it.copy(calendarMonth = month, calendarDay = month.atDay(1), calendarFeeds = emptyMap()) }
        refreshCalendar()
    }

    fun day(day: LocalDate) {
        if (YearMonth.from(day) != state.value.calendarMonth) month(YearMonth.from(day))
        mutableState.update { it.copy(calendarDay = day) }
    }

    fun zone(zone: ZoneId) {
        if (zone == state.value.zone) return
        mutableState.update { it.copy(zone = zone, calendarFeeds = emptyMap()) }
        refreshCalendar()
        if (state.value.listFrom != null || state.value.listTo != null) {
            cancel("list")
            mutableState.update { it.copy(list = MeetingsLoad()) }
            refreshList()
        }
    }

    fun calendarFilter(scope: MeetingsScope) {
        if (scope == state.value.calendarScope) return
        mutableState.update { it.copy(calendarScope = scope, calendarFeeds = emptyMap()) }
        refreshCalendar()
    }

    fun refreshCalendar() { MeetingsView.entries.forEach { loadCalendar(it, append = false) } }
    fun moreCalendar() {
        MeetingsView.entries.forEach { view ->
            val feed = state.value.calendarFeeds[view]
            if (feed?.loading != true && feed?.data?.nextCursor != null) loadCalendar(view, append = true)
        }
    }

    private fun loadCalendar(view: MeetingsView, append: Boolean) {
        val current = state.value
        val previous = current.calendarFeeds[view] ?: MeetingsLoad()
        val cursor = previous.data?.nextCursor.takeIf { append }
        if (append && cursor == null) return
        val (from, to) = MeetingTime.calendarRange(current.calendarMonth, current.zone)
        calendarFeed(view, previous.copy(loading = true, error = null))
        request("calendar-$view", failure = { calendarFeed(view, previous.copy(loading = false, error = it.message)) }) {
            val page = repository.timeline(MeetingsQuery(view, current.calendarScope, from, to, cursor, limit = 100))
            checkCurrent()
            calendarFeed(view, MeetingsLoad(mergePage(previous.data, page, cursor, append)))
        }
    }

    private fun calendarFeed(view: MeetingsView, feed: MeetingsLoad<MeetingPage>) = mutableState.update {
        it.copy(calendarFeeds = it.calendarFeeds + (view to feed))
    }

    fun open(id: String) {
        closeDetail()
        mutableState.update { it.copy(detailId = id) }
        refreshDetail()
    }

    fun closeDetail() {
        listOf("detail", "self", "history", "invite-search", "invite").forEach(::cancel)
        mutableState.update { it.copy(detailId = null, detail = MeetingsLoad(), self = MeetingsLoad(), history = MeetingsLoad(), invitation = MeetingRoomUiState()) }
    }

    private fun canInvite(): Boolean {
        val s = state.value; val meeting = s.detail.data ?: return false
        return meeting.status in setOf("created", "scheduled", "active") &&
            (meeting.ownerId == foundation.state.value.session?.user?.id || meeting.status == "active" && s.self.data?.let { it.canParticipate && it.role == "co_host" } == true)
    }
    fun searchInviteUsers(query: String) {
        val id = state.value.detailId ?: return
        if (!canInvite()) return
        val q = query.take(100)
        mutableState.update { it.copy(invitation = it.invitation.copy(query = q, users = emptyList(), error = null, notice = null)) }
        request("invite-search", { e -> mutableState.update { it.copy(invitation = it.invitation.copy(error = e.message)) } }) {
            delay(300); val users = repository.invitationUsers(id, q.trim()); checkCurrent()
            mutableState.update { it.copy(invitation = it.invitation.copy(users = users)) }
        }
    }
    fun inviteUsers(ids: List<String>, emails: List<String>) {
        val id = state.value.detailId ?: return
        if (!canInvite() || state.value.invitation.busy || ids.size + emails.size !in 1..20) return
        mutableState.update { it.copy(invitation = it.invitation.copy(busy = true, error = null, notice = null)) }
        request("invite", { e -> mutableState.update { it.copy(invitation = it.invitation.copy(busy = false, error = e.message)) } }) {
            repository.inviteParticipants(id, ids, emails); checkCurrent()
            mutableState.update { it.copy(invitation = it.invitation.copy(busy = false, notice = "Приглашения приняты сервером.", query = "", users = emptyList())) }
        }
    }

    fun refreshDetail() {
        val id = state.value.detailId ?: return
        val previous = state.value.detail
        mutableState.update { it.copy(detail = previous.copy(loading = true, error = null)) }
        request("detail", failure = { failure ->
            if (accessDenied(failure)) clearDeniedMeeting(id, failure)
            else mutableState.update { state -> state.copy(detail = previous.copy(loading = false, error = failure.message)) }
        }) {
            val meeting = repository.conference(id)
            checkCurrent()
            mutableState.update { it.copy(detail = MeetingsLoad(meeting)) }
            loadMembership(id)
            if (meeting.isClosed) loadHistory(id) else {
                cancel("history")
                mutableState.update { it.copy(history = MeetingsLoad()) }
            }
        }
    }

    private fun loadMembership(id: String) {
        val previous = state.value.self
        mutableState.update { it.copy(self = previous.copy(loading = true, error = null)) }
        request("self", failure = { failure -> mutableState.update { state -> state.copy(self =
            if (accessDenied(failure)) MeetingsLoad(error = failure.message) else previous.copy(loading = false, error = failure.message)) } }) {
            val participant = repository.membership(id)
            checkCurrent()
            mutableState.update { it.copy(self = MeetingsLoad(participant)) }
        }
    }

    private fun loadHistory(id: String) {
        val previous = state.value.history
        mutableState.update { it.copy(history = previous.copy(loading = true, error = null)) }
        request("history", failure = { failure -> mutableState.update { state -> state.copy(history =
            if (accessDenied(failure)) MeetingsLoad(error = failure.message) else previous.copy(loading = false, error = failure.message)) } }) {
            val history = repository.history(id)
            checkCurrent()
            mutableState.update { it.copy(history = MeetingsLoad(history)) }
        }
    }

    private fun accessDenied(failure: AppFailure) = failure.kind in setOf(FailureKind.FORBIDDEN, FailureKind.NOT_FOUND, FailureKind.SESSION_EXPIRED)

    private fun clearDeniedMeeting(id: String, failure: AppFailure) {
        listOf("self", "history", "invite-search", "invite").forEach(::cancel)
        fun MeetingsLoad<MeetingPage>.withoutMeeting() = copy(data = data?.let { it.copy(items = it.items.filterNot { meeting -> meeting.id == id }) })
        mutableState.update { state -> state.copy(detail = MeetingsLoad(error = failure.message), self = MeetingsLoad(), history = MeetingsLoad(),
            invitation = MeetingRoomUiState(), upcoming = state.upcoming.withoutMeeting(), active = state.active.withoutMeeting(),
            list = state.list.withoutMeeting(), calendarFeeds = state.calendarFeeds.mapValues { it.value.withoutMeeting() }) }
    }

    fun create(input: MeetingInput) {
        if (state.value.creationUncertain) {
            mutableState.update { it.copy(error = "Проверьте обновлённый список встреч перед повторным созданием.") }
            return
        }
        mutate(creating = true) {
            val meeting = repository.createMeeting(input)
            checkCurrent()
            open(meeting.id)
            "Встреча создана."
        }
    }

    fun edit(id: String, input: MeetingInput) = mutate {
        val at = input.scheduledAt ?: throw AppFailure(FailureKind.VALIDATION, "Укажите дату и время встречи.")
        repository.scheduleMeeting(id, at, input.plannedDurationMin)
        checkCurrent()
        if (state.value.detailId == id) refreshDetail()
        "Расписание обновлено."
    }

    fun transition(id: String, transition: MeetingTransition) = mutate {
        repository.transitionMeeting(id, transition)
        checkCurrent()
        if (state.value.detailId == id) refreshDetail()
        when (transition) {
            MeetingTransition.START -> "Встреча начата на сервере."
            MeetingTransition.FINISH -> "Встреча завершена."
            MeetingTransition.CANCEL -> "Встреча отменена."
        }
    }

    fun join(rawCodeOrUrl: String) = mutate {
        val participant = repository.joinInvite(rawCodeOrUrl)
        checkCurrent()
        open(participant.conferenceId)
        admissionNotice(participant)
    }

    fun joinMeeting(id: String) = mutate {
        val participant = repository.joinMeeting(id)
        checkCurrent()
        open(participant.conferenceId)
        admissionNotice(participant)
    }

    fun leave(id: String) = mutate {
        repository.leaveMeeting(id)
        checkCurrent()
        if (state.value.detailId == id) refreshDetail()
        "Вы вышли из встречи."
    }

    fun updateProfile(name: String) = mutate(profile = true) {
        repository.updateDisplayName(name)
        checkCurrent()
        "Имя сохранено."
    }

    fun updateAvatar(mimeType: String, bytes: ByteArray) {
        if (scope() == null || state.value.actionBusy) { bytes.fill(0); return }
        mutate(profile = true, avatar = true) {
            try {
                foundation.putUserAvatar(mimeType, bytes)
                checkCurrent()
                "Фото профиля обновлено."
            } finally { bytes.fill(0) }
        }
        // Also clear an upload cancelled before its lazy coroutine enters the block.
        jobs["action"]?.invokeOnCompletion { bytes.fill(0) }
    }

    fun deleteAvatar() = mutate(profile = true, avatar = true) {
        foundation.deleteUserAvatar()
        checkCurrent()
        "Фото профиля удалено."
    }

    fun acknowledgeUncertainCreate() {
        if (!creationReconciled) {
            mutableState.update { it.copy(error = "Сначала обновите главную или список ваших будущих встреч и проверьте результат.") }
            return
        }
        mutableState.update { it.copy(creationUncertain = false, error = null, notice = "Повторное создание разрешено после вашей проверки списка.") }
    }

    fun clearMessage() { mutableState.update { it.copy(error = null, notice = null) } }

    private fun mutate(creating: Boolean = false, profile: Boolean = false, avatar: Boolean = false, operation: suspend () -> String) {
        if (scope() == null || state.value.actionBusy) return
        mutableState.update { it.copy(actionBusy = true, profileBusy = profile, error = null, notice = null) }
        request("action", failure = { failure ->
            val uncertain = failure.kind in setOf(FailureKind.NETWORK, FailureKind.TIMEOUT, FailureKind.INVALID_RESPONSE, FailureKind.UNAVAILABLE, FailureKind.TLS)
            if (creating && uncertain) creationReconciled = false
            mutableState.update { it.copy(
                actionBusy = false, profileBusy = false,
                avatarRevision = it.avatarRevision + if (avatar) 1 else 0,
                creationUncertain = it.creationUncertain || (creating && uncertain),
                error = if (uncertain && !profile) "${failure.message} Результат действия неизвестен. Обновите данные перед повтором." else failure.message,
            ) }
            if (uncertain && !profile) refreshKnownData()
        }) {
            val notice = operation()
            checkCurrent()
            mutableState.update { it.copy(actionBusy = false, profileBusy = false, notice = notice, error = null,
                mutationRevision = it.mutationRevision + 1, avatarRevision = it.avatarRevision + if (avatar) 1 else 0) }
            if (!profile) refreshKnownData()
        }
    }

    private fun refreshKnownData() {
        refreshHome()
        if (state.value.list.data != null) refreshList()
        if (state.value.calendarFeeds.isNotEmpty()) refreshCalendar()
        if (state.value.detailId != null) refreshDetail()
    }

    private fun admissionNotice(participant: MeetingParticipant): String = when {
        participant.admissionState == "waiting" || participant.status == "waiting" -> "Заявка отправлена. Ожидайте решения организатора и обновите допуск."
        participant.canParticipate -> "Сервер подтвердил вход. Можно подключиться к звонку."
        participant.isAdmitted && participant.status == "left" -> "Вы зарегистрированы на встречу. Войти можно после её начала."
        else -> "Сервер вернул состояние участия: ${participant.admissionState ?: participant.status}."
    }

    private fun mergePage(previous: MeetingPage?, page: MeetingPage, cursor: String?, append: Boolean): MeetingPage {
        if (cursor != null && page.nextCursor == cursor) throw AppFailure(FailureKind.INVALID_RESPONSE, "Сервер повторил курсор страницы. Обновите список.")
        // A more recent projection wins for an ID that moved while the page was loading.
        val items = ((previous?.items.orEmpty().takeIf { append } ?: emptyList()) + page.items)
            .associateBy { it.id }.values.sortedWith(compareByDescending<Meeting> { it.timelineAt }.thenByDescending { it.id })
        return MeetingPage(items, page.nextCursor)
    }

    private fun scope(): String? {
        val snapshot = foundation.state.value
        val profile = snapshot.selectedProfile ?: return null
        val user = snapshot.session?.user ?: return null
        return "${profile.id}|${profile.origin}|${user.id}"
    }

    private fun cancel(key: String) {
        tickets[key] = (tickets[key] ?: 0) + 1
        jobs.remove(key)?.cancel()
    }

    private fun request(key: String, failure: (AppFailure) -> Unit, operation: suspend () -> Unit) {
        if (scope() == null) return
        cancel(key)
        val ticket = tickets.getValue(key)
        val epoch = generation
        val requestScope = scope()
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                operation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                coroutineContext.ensureActive()
                if (epoch == generation && requestScope == scope() && tickets[key] == ticket) {
                    failure(error as? AppFailure ?: AppFailure(FailureKind.UNAVAILABLE, "Не удалось выполнить действие. Попробуйте ещё раз."))
                }
            } finally {
                if (tickets[key] == ticket) jobs.remove(key)
            }
        }
        jobs[key] = job
        job.start()
    }

    private suspend fun checkCurrent() {
        coroutineContext.ensureActive()
        // Even before the StateFlow collector runs, a logout must stop presenting old data.
        if (scope() != accountScope) throw CancellationException("Account scope changed")
    }

    private fun freshState(zone: ZoneId = ZoneId.systemDefault()): MeetingsUiState {
        val today = LocalDate.now(clock.withZone(zone))
        return MeetingsUiState(zone = zone, calendarDay = today, calendarMonth = YearMonth.from(today))
    }

    class Factory(private val foundation: FoundationRepository, private val repository: MeetingsRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(MeetingsViewModel::class.java))
            return MeetingsViewModel(foundation, repository) as T
        }
    }
}
