package com.meetspace.app

import com.meetspace.app.ui.WorkspaceTab
import com.meetspace.core.data.FoundationRepository
import com.meetspace.core.data.MeetingsRepository
import com.meetspace.core.model.*
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MeetingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    @Test fun avatarUploadPublishesAccountAndClearsTheOwnedImageBuffer() = runTest(dispatcher) {
        val foundation = FakeFoundation()
        val model = model(FakeMeetings(), foundation)
        val image = byteArrayOf(1, 2, 3)
        model.updateAvatar("image/jpeg", image)
        runCurrent()
        assertEquals("avatar-v1", foundation.state.value.session!!.user.avatarVersion)
        assertTrue(image.all { it == 0.toByte() })
        assertFalse(model.state.value.profileBusy)
        assertEquals("Фото профиля обновлено.", model.state.value.notice)
        model.deleteAvatar(); runCurrent()
        assertNull(foundation.state.value.session!!.user.avatarVersion)
        assertEquals("Фото профиля удалено.", model.state.value.notice)
    }

    @Test fun cancelledBeforeStartAvatarUploadCannotRetainTheImageBuffer() = runTest(dispatcher) {
        val model = model(FakeMeetings())
        val image = byteArrayOf(4, 5, 6)
        model.updateAvatar("image/jpeg", image)
        model.suspendWork()
        runCurrent()
        assertTrue(image.all { it == 0.toByte() })
        assertFalse(model.state.value.profileBusy)
        assertNull(model.state.value.notice)
    }

    @Test fun lateAvatarUploadCannotShowSuccessAfterSwitchingAccounts() = runTest(dispatcher) {
        val gate = CompletableDeferred<User>()
        val foundation = FakeFoundation().apply { avatarResult = { _, _ -> withContext(NonCancellable) { gate.await() } } }
        val model = model(FakeMeetings(), foundation)
        val image = byteArrayOf(7, 8, 9)
        model.updateAvatar("image/jpeg", image); runCurrent()
        assertTrue(model.state.value.profileBusy)
        foundation.state.value = foundation.state.value.copy(session = AccountSession(User("other", "other@example.test"), Capabilities(), true))
        runCurrent()
        gate.complete(User("current-user", "member@example.test", avatarVersion = "old-avatar")); runCurrent()
        assertTrue(image.all { it == 0.toByte() })
        assertNull(model.state.value.notice)
        assertFalse(model.state.value.profileBusy)
        assertEquals("other", foundation.state.value.session!!.user.id)
    }

    @Test fun homeRequestsRealUpcomingAndActiveOnlyOnceUntilRefreshed() = runTest(dispatcher) {
        val repository = FakeMeetings()
        val model = model(repository)
        model.activate(WorkspaceTab.HOME)
        model.activate(WorkspaceTab.HOME)
        runCurrent()
        assertEquals(listOf(MeetingsView.UPCOMING, MeetingsView.ACTIVE), repository.queries.map { it.view })
        assertTrue(repository.queries.all { it.limit == 6 && it.cursor == null })
        assertFalse(model.state.value.upcoming.loading)
        model.refreshHome()
        runCurrent()
        assertEquals(4, repository.queries.size)
    }

    @Test fun meetingsInitiallyLoadsUpcomingAndPreservesSelectedHistoryFilterOnReturn() = runTest(dispatcher) {
        val repository = FakeMeetings()
        val model = model(repository)
        model.activate(WorkspaceTab.CALLS); runCurrent()
        assertEquals(MeetingsView.UPCOMING, repository.queries.single().view)
        model.listFilter(MeetingsView.PAST, MeetingsScope.ALL); runCurrent()
        model.activate(WorkspaceTab.CALLS); runCurrent()
        assertEquals(listOf(MeetingsView.UPCOMING, MeetingsView.PAST), repository.queries.map { it.view })
        assertEquals(MeetingsView.PAST, model.state.value.listView)
    }

    @Test fun lateResponseFromPreviousAccountCannotRepopulateClearedWorkspace() = runTest(dispatcher) {
        val gate = CompletableDeferred<MeetingPage>()
        val foundation = FakeFoundation()
        val repository = FakeMeetings().apply { timelineResult = { withContext(NonCancellable) { gate.await() } } }
        val model = model(repository, foundation)
        model.refreshList()
        runCurrent()
        foundation.state.value = foundation.state.value.copy(session = AccountSession(User("other-user", "other@example.test"), Capabilities(), true))
        runCurrent()
        assertNull(model.state.value.list.data)
        gate.complete(MeetingPage(listOf(meeting("old-account")), null))
        runCurrent()
        assertNull(model.state.value.list.data)
        assertNull(model.state.value.error)
    }

    @Test fun periodUsesInclusiveLocalDaysAcrossDstAndKeepsBoundsDuringPagination() = runTest(dispatcher) {
        val repository = FakeMeetings().apply { timelineResult = { MeetingPage(emptyList(), if (it.cursor == null) "next-page" else null) } }
        val model = model(repository)
        model.zone(ZoneId.of("America/New_York")); runCurrent(); repository.queries.clear()
        val day = LocalDate.of(2030, 3, 10)
        model.listPeriod(day, day); runCurrent()
        model.moreList(); runCurrent()
        assertEquals(2, repository.queries.size)
        assertTrue(repository.queries.all { it.from == Instant.parse("2030-03-10T05:00:00Z") && it.to == Instant.parse("2030-03-11T03:59:59.999999Z") })
        assertEquals("next-page", repository.queries.last().cursor)
        model.listPeriod(day.plusDays(1), day); runCurrent()
        assertEquals(2, repository.queries.size)
        model.zone(ZoneId.of("Europe/Moscow")); runCurrent()
        val listQuery = repository.queries.last { it.limit == 20 }
        assertNull(listQuery.cursor)
        assertEquals(Instant.parse("2030-03-09T21:00:00Z"), listQuery.from)
        assertEquals(Instant.parse("2030-03-10T20:59:59.999999Z"), listQuery.to)
    }

    @Test fun latePeriodResponseCannotReplaceTheNewPeriodAndResetDropsBounds() = runTest(dispatcher) {
        val gate = CompletableDeferred<MeetingPage>()
        val first = LocalDate.of(2030, 1, 1)
        val second = first.plusDays(1)
        val repository = FakeMeetings().apply { timelineResult = { query ->
            if (query.from == first.atStartOfDay(ZoneId.of("UTC")).toInstant()) withContext(NonCancellable) { gate.await() }
            else MeetingPage(listOf(meeting("current")), null)
        } }
        val model = model(repository)
        model.zone(ZoneId.of("UTC")); runCurrent()
        model.listPeriod(first, first); runCurrent()
        model.listPeriod(second, second); runCurrent()
        gate.complete(MeetingPage(listOf(meeting("stale")), null)); runCurrent()
        assertEquals(listOf("current"), model.state.value.list.data!!.items.map { it.id })
        assertEquals(second, model.state.value.listFrom)
        model.listPeriod(null, null); runCurrent()
        assertNull(repository.queries.last().from); assertNull(repository.queries.last().to)
        assertNull(repository.queries.last().cursor)
    }

    @Test fun boundedPeriodCannotReconcileAnUncertainCreation() = runTest(dispatcher) {
        val repository = FakeMeetings().apply {
            createResult = { throw AppFailure(FailureKind.NETWORK, "Connection lost") }
            timelineResult = { throw AppFailure(FailureKind.NETWORK, "Connection lost") }
        }
        val model = model(repository)
        model.create(MeetingInput("Uncertain")); runCurrent()
        repository.timelineResult = { MeetingPage(emptyList(), null) }
        model.listPeriod(LocalDate.of(2030, 1, 1), LocalDate.of(2030, 1, 2)); runCurrent()
        model.acknowledgeUncertainCreate()
        assertTrue(model.state.value.creationUncertain)
        model.listPeriod(null, null); runCurrent()
        model.acknowledgeUncertainCreate()
        assertFalse(model.state.value.creationUncertain)
    }

    @Test fun logoutClearsDetailsAndBlocksMutationsWithoutAnAccount() = runTest(dispatcher) {
        val foundation = FakeFoundation()
        val repository = FakeMeetings()
        val model = model(repository, foundation)
        model.open("private-id")
        runCurrent()
        assertNotNull(model.state.value.detail.data)
        foundation.state.value = foundation.state.value.copy(session = null)
        runCurrent()
        assertNull(model.state.value.detailId)
        assertNull(model.state.value.detail.data)
        model.create(MeetingInput("Must not send"))
        runCurrent()
        assertEquals(0, repository.createCalls)
    }

    @Test fun staleFilterResponseCannotReplaceNewFilteredList() = runTest(dispatcher) {
        val old = CompletableDeferred<MeetingPage>()
        val repository = FakeMeetings().apply {
            timelineResult = { query -> if (query.view == MeetingsView.UPCOMING) withContext(NonCancellable) { old.await() }
                else MeetingPage(listOf(meeting("active", "active")), null) }
        }
        val model = model(repository)
        model.refreshList()
        runCurrent()
        model.listFilter(MeetingsView.ACTIVE, MeetingsScope.OWNED)
        runCurrent()
        old.complete(MeetingPage(listOf(meeting("stale")), null))
        runCurrent()
        assertEquals(MeetingsView.ACTIVE, model.state.value.listView)
        assertEquals(MeetingsScope.OWNED, model.state.value.listScope)
        assertEquals(listOf("active"), model.state.value.list.data!!.items.map { it.id })
        assertEquals(MeetingsScope.OWNED, repository.queries.last().scope)
    }

    @Test fun paginationDeduplicatesIdsUsingNewerProjectionAndStopsRepeatedCursor() = runTest(dispatcher) {
        val repository = FakeMeetings().apply {
            timelineResult = { query -> if (query.cursor == null) MeetingPage(listOf(meeting("same").copy(title = "Old")), "page-two")
                else MeetingPage(listOf(meeting("same").copy(title = "New"), meeting("second")), "page-two") }
        }
        val model = model(repository)
        model.refreshList(); runCurrent()
        model.moreList(); runCurrent()
        assertEquals("Old", model.state.value.list.data!!.items.single().title)
        assertNotNull(model.state.value.list.error)
        assertFalse(model.state.value.list.loading)
        repository.timelineResult = { MeetingPage(listOf(meeting("same").copy(title = "New"), meeting("second")), null) }
        model.moreList(); runCurrent()
        assertEquals(2, model.state.value.list.data!!.items.size)
        assertEquals("New", model.state.value.list.data!!.items.single { it.id == "same" }.title)
        assertNull(model.state.value.list.data!!.nextCursor)
        model.moreList(); runCurrent()
        assertEquals(3, repository.queries.size)
    }

    @Test fun doubleCreateTapSendsOnePostWhilePending() = runTest(dispatcher) {
        val gate = CompletableDeferred<Meeting>()
        val repository = FakeMeetings().apply { createResult = { gate.await() } }
        val model = model(repository)
        model.create(MeetingInput("One")); model.create(MeetingInput("Two")); runCurrent()
        assertEquals(1, repository.createCalls)
        assertTrue(model.state.value.actionBusy)
        gate.complete(meeting("created")); runCurrent()
        assertFalse(model.state.value.actionBusy)
        assertEquals("created", model.state.value.detailId)
        assertEquals(1L, model.state.value.mutationRevision)
    }

    @Test fun unknownCreateOutcomeRequiresSuccessfulReadAndExplicitAcknowledgementBeforeAnotherPost() = runTest(dispatcher) {
        val repository = FakeMeetings().apply {
            createResult = { throw AppFailure(FailureKind.NETWORK, "Сеть недоступна.") }
            timelineResult = { throw AppFailure(FailureKind.NETWORK, "Сеть недоступна.") }
        }
        val model = model(repository)
        model.create(MeetingInput("One")); runCurrent()
        assertTrue(model.state.value.creationUncertain)
        assertEquals(1, repository.createCalls)
        model.acknowledgeUncertainCreate()
        model.create(MeetingInput("Repeated")); runCurrent()
        assertTrue(model.state.value.creationUncertain)
        assertEquals(1, repository.createCalls)
        repository.timelineResult = { MeetingPage(emptyList(), null) }
        model.refreshHome(); runCurrent()
        model.create(MeetingInput("Still blocked")); runCurrent()
        assertEquals(1, repository.createCalls)
        model.acknowledgeUncertainCreate()
        assertFalse(model.state.value.creationUncertain)
        repository.createResult = { meeting("second") }
        model.create(MeetingInput("Checked")); runCurrent()
        assertEquals(2, repository.createCalls)
    }

    @Test fun rejectedCreateDoesNotBecomeAnUncertainPost() = runTest(dispatcher) {
        val repository = FakeMeetings().apply { createResult = { throw AppFailure(FailureKind.VALIDATION, "Некорректная дата.") } }
        val model = model(repository)
        model.create(MeetingInput("Invalid")); runCurrent()
        assertFalse(model.state.value.creationUncertain)
        assertFalse(model.state.value.actionBusy)
        assertEquals("Некорректная дата.", model.state.value.error)
        assertTrue(repository.queries.isEmpty())
    }

    @Test fun calendarFetchesAllViewsWithLocalMonthBoundsAndIndependentCursors() = runTest(dispatcher) {
        val repository = FakeMeetings().apply { timelineResult = { query -> MeetingPage(emptyList(), if (query.cursor == null) "next-${query.view}" else null) } }
        val model = model(repository)
        model.zone(ZoneId.of("America/New_York")); runCurrent()
        repository.queries.clear()
        model.month(YearMonth.of(2026, 3)); runCurrent()
        assertEquals(MeetingsView.entries.toSet(), repository.queries.map { it.view }.toSet())
        assertTrue(repository.queries.all { it.limit == 100 && it.from == Instant.parse("2026-03-01T05:00:00Z") && it.to == Instant.parse("2026-04-01T03:59:59.999999Z") })
        repository.queries.clear()
        model.moreCalendar(); runCurrent()
        assertEquals(3, repository.queries.size)
        assertTrue(repository.queries.all { it.cursor == "next-${it.view}" })
        assertTrue(model.state.value.calendarFeeds.values.all { it.data?.nextCursor == null })
    }

    @Test fun closingDetailsCancelsLateHistoryAndDoesNotLeakItToNextMeeting() = runTest(dispatcher) {
        val historyGate = CompletableDeferred<MeetingHistory>()
        val repository = FakeMeetings().apply {
            conferenceResult = { meeting(it, if (it == "closed") "finished" else "active") }
            historyResult = { withContext(NonCancellable) { historyGate.await() } }
        }
        val model = model(repository)
        model.open("closed"); runCurrent()
        assertTrue(model.state.value.history.loading)
        model.open("next"); runCurrent()
        historyGate.complete(history(meeting("closed", "finished"))); runCurrent()
        assertEquals("next", model.state.value.detail.data!!.id)
        assertNull(model.state.value.history.data)
        assertFalse(model.state.value.history.loading)
    }

    @Test fun deniedMeetingRefreshPurgesItsPrivateProjectionAndRejectsLateHistory() = runTest(dispatcher) {
        for (kind in listOf(FailureKind.FORBIDDEN, FailureKind.NOT_FOUND)) {
            val closed = meeting("closed", "finished").copy(title = "Private meeting title", inviteCode = "private-invite")
            val repository = FakeMeetings().apply {
                conferenceResult = { closed }
                timelineResult = { MeetingPage(listOf(closed, meeting("other")), null) }
            }
            val model = model(repository)
            model.refreshHome(); model.refreshList(); model.refreshCalendar(); model.open("closed"); runCurrent()
            assertEquals("Private meeting title", model.state.value.detail.data!!.title)
            assertEquals("private-invite", model.state.value.detail.data!!.inviteCode)
            assertNotNull(model.state.value.self.data); assertNotNull(model.state.value.history.data)
            val historyGate = CompletableDeferred<MeetingHistory>()
            repository.historyResult = { withContext(NonCancellable) { historyGate.await() } }
            model.refreshDetail(); runCurrent()
            assertTrue(model.state.value.history.loading)
            repository.conferenceResult = { throw AppFailure(kind, "Доступ отозван") }
            model.refreshDetail(); runCurrent()
            assertNull(model.state.value.detail.data); assertNull(model.state.value.self.data); assertNull(model.state.value.history.data)
            assertEquals("Доступ отозван", model.state.value.detail.error)
            val feeds = listOf(model.state.value.upcoming, model.state.value.active, model.state.value.list) + model.state.value.calendarFeeds.values
            assertTrue(feeds.all { it.data!!.items.map { row -> row.id } == listOf("other") })
            historyGate.complete(history(closed)); runCurrent()
            assertNull(model.state.value.history.data)
            model.suspendWork()
        }
    }

    @Test fun historyAndMembershipDenialsClearTheirCachedProjectionWithoutHidingReadableMeeting() = runTest(dispatcher) {
        for (denyHistory in listOf(false, true)) {
            val repository = FakeMeetings().apply { conferenceResult = { meeting(it, "finished") } }
            val model = model(repository)
            model.open("closed"); runCurrent()
            assertNotNull(model.state.value.history.data); assertNotNull(model.state.value.self.data)
            if (denyHistory) repository.historyResult = { throw AppFailure(FailureKind.FORBIDDEN, "История недоступна") }
            else repository.membershipResult = { throw AppFailure(FailureKind.NOT_FOUND, "Участие недоступно") }
            model.refreshDetail(); runCurrent()
            assertNotNull(model.state.value.detail.data)
            if (denyHistory) {
                assertNull(model.state.value.history.data); assertEquals("История недоступна", model.state.value.history.error)
                assertNotNull(model.state.value.self.data)
            } else {
                assertNull(model.state.value.self.data); assertEquals("Участие недоступно", model.state.value.self.error)
                assertNotNull(model.state.value.history.data)
            }
            model.suspendWork()
        }
    }

    @Test fun temporaryDetailNetworkFailureKeepsTheAuthorisedCachedProjection() = runTest(dispatcher) {
        val repository = FakeMeetings().apply { conferenceResult = { meeting(it, "finished") } }
        val model = model(repository)
        model.open("closed"); runCurrent()
        val before = model.state.value
        repository.conferenceResult = { throw AppFailure(FailureKind.NETWORK, "Нет сети") }
        model.refreshDetail(); runCurrent()
        assertEquals(before.detail.data, model.state.value.detail.data)
        assertEquals(before.self.data, model.state.value.self.data)
        assertEquals(before.history.data, model.state.value.history.data)
        assertEquals("Нет сети", model.state.value.detail.error)
        model.suspendWork()
    }

    @Test fun waitingAdmissionNeverClaimsConnectedAudioOrVideo() = runTest(dispatcher) {
        val repository = FakeMeetings().apply { joinResult = { participant("room").copy(status = "waiting", admissionState = "waiting") } }
        val model = model(repository)
        model.join("test-invite"); runCurrent()
        assertEquals("room", model.state.value.detailId)
        assertTrue(model.state.value.notice!!.contains("Ожидайте решения"))
        assertFalse(model.state.value.notice!!.contains("подтвердил вход"))
    }

    @Test fun failedRefreshKeepsExistingDataAndPresentsError() = runTest(dispatcher) {
        val repository = FakeMeetings().apply { timelineResult = { MeetingPage(listOf(meeting("kept")), null) } }
        val model = model(repository)
        model.refreshList(); runCurrent()
        repository.timelineResult = { throw AppFailure(FailureKind.NETWORK, "Обновление недоступно.") }
        model.refreshList(); runCurrent()
        assertEquals("kept", model.state.value.list.data!!.items.single().id)
        assertEquals("Обновление недоступно.", model.state.value.list.error)
        assertFalse(model.state.value.list.loading)
    }

    @Test fun failedReplacementRefreshStopsLoaderAndAllowsRetry() = runTest(dispatcher) {
        val firstResponse = CompletableDeferred<MeetingPage>()
        val repository = FakeMeetings().apply { timelineResult = { firstResponse.await() } }
        val model = model(repository)
        model.refreshList(); runCurrent()
        assertTrue(model.state.value.list.loading)
        repository.timelineResult = { throw AppFailure(FailureKind.NETWORK, "Повторное обновление недоступно.") }
        model.refreshList(); runCurrent()
        assertFalse(model.state.value.list.loading)
        assertEquals("Повторное обновление недоступно.", model.state.value.list.error)
        repository.timelineResult = { MeetingPage(listOf(meeting("retry")), null) }
        model.refreshList(); runCurrent()
        assertFalse(model.state.value.list.loading)
        assertNull(model.state.value.list.error)
        assertEquals("retry", model.state.value.list.data!!.items.single().id)
    }

    private fun model(repository: FakeMeetings, foundation: FakeFoundation = FakeFoundation()) =
        MeetingsViewModel(foundation, repository, Clock.fixed(Instant.parse("2026-10-11T12:00:00Z"), ZoneOffset.UTC))

    private class FakeMeetings : MeetingsRepository {
        val queries = mutableListOf<MeetingsQuery>()
        var createCalls = 0
        var timelineResult: suspend (MeetingsQuery) -> MeetingPage = { MeetingPage(emptyList(), null) }
        var conferenceResult: suspend (String) -> Meeting = { meeting(it) }
        var createResult: suspend (MeetingInput) -> Meeting = { meeting("created") }
        var historyResult: suspend (String) -> MeetingHistory = { history(meeting(it, "finished")) }
        var membershipResult: suspend (String) -> MeetingParticipant? = { participant(it) }
        var joinResult: suspend (String) -> MeetingParticipant = { participant("room") }
        override suspend fun timeline(query: MeetingsQuery): MeetingPage { queries += query; return timelineResult(query) }
        override suspend fun conference(id: String) = conferenceResult(id)
        override suspend fun createMeeting(input: MeetingInput): Meeting { createCalls++; return createResult(input) }
        override suspend fun scheduleMeeting(id: String, scheduledAt: Instant, plannedDurationMin: Int?) = meeting(id, "scheduled").copy(scheduledAt = scheduledAt, plannedDurationMin = plannedDurationMin)
        override suspend fun transitionMeeting(id: String, transition: MeetingTransition) = meeting(id)
        override suspend fun membership(id: String) = membershipResult(id)
        override suspend fun joinMeeting(id: String, inviteCode: String?) = joinResult(id)
        override suspend fun joinInvite(rawCodeOrUrl: String) = joinResult(rawCodeOrUrl)
        override suspend fun leaveMeeting(id: String) = participant(id).copy(status = "left")
        override suspend fun history(id: String) = historyResult(id)
        override suspend fun updateDisplayName(displayName: String) = User("current-user", "member@example.test", displayName)
    }

    private class FakeFoundation : FoundationRepository {
        override val state = MutableStateFlow(RepositoryState(
            profiles = listOf(ServerProfile("profile", "https://fixture.example.test")), selectedProfileId = "profile",
            session = AccountSession(User("current-user", "member@example.test"), Capabilities(), true),
        ))
        var avatarResult: suspend (String, ByteArray) -> User = { _, _ -> state.value.session!!.user.copy(avatarVersion = "avatar-v1").also { user -> state.value = state.value.copy(session = state.value.session!!.copy(user = user)) } }
        override suspend fun putUserAvatar(mimeType: String, bytes: ByteArray) = avatarResult(mimeType, bytes)
        override suspend fun deleteUserAvatar() = state.value.session!!.user.copy(avatarVersion = null).also { user -> state.value = state.value.copy(session = state.value.session!!.copy(user = user)) }
        override suspend fun restore() = Unit
        override suspend fun addServer(rawUrl: String) = state.value.selectedProfile!!
        override suspend fun selectServer(profileId: String) = Unit
        override suspend fun removeServer(profileId: String) = Unit
        override suspend fun login(email: String, password: String) = Unit
        override suspend fun logout() = LogoutResult(true)
        override suspend fun setTheme(mode: ThemeMode) = Unit
    }

    companion object {
        private val at = Instant.parse("2026-10-11T12:00:00Z")
        private fun meeting(id: String, status: String = "created") = Meeting(id, "current-user", "Meeting $id", "", "", status, at, at)
        private fun participant(id: String) = MeetingParticipant("participant", id, "current-user", "Member", "owner", "left", "admitted")
        private fun history(meeting: Meeting) = MeetingHistory(meeting, "current-user", "Owner", null, 1, listOf(participant(meeting.id)), false, MeetingRecordingsSummary(0, 0, 0, 0), true, true)
    }
}
