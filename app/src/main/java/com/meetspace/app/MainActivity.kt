package com.meetspace.app

import android.os.Bundle
import android.content.Intent
import android.media.AudioManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import com.meetspace.app.ui.CallHost
import androidx.compose.ui.graphics.luminance
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meetspace.app.ui.GuestMeetingLobby
import com.meetspace.app.ui.FoundationUi
import com.meetspace.app.ui.FoundationScreen
import com.meetspace.app.ui.SshWizardUi
import com.meetspace.app.ui.InstallerUi
import com.meetspace.app.ui.HomeDashboard
import com.meetspace.app.ui.WorkspaceTab
import com.meetspace.app.ui.ProfileWorkspace
import com.meetspace.app.ui.MeetingsWorkspace
import com.meetspace.core.media.CallPhase
import com.meetspace.core.designsystem.MeetSpaceTheme

class MainActivity : ComponentActivity() {
    private val openInbox = MutableStateFlow(false)
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == MessagingNotifications.OPEN_INBOX) openInbox.value = true
    }
    override fun onStop() {
        if (!isChangingConfigurations) (application as MeetSpaceApplication).callEngine.pauseDevices()
        super.onStop()
    }
    override fun onDestroy() {
        if (isFinishing) (application as MeetSpaceApplication).callEngine.leave()
        super.onDestroy()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val meetSpace = application as MeetSpaceApplication
        val repository = meetSpace.repository
        if (intent.action == MessagingNotifications.OPEN_INBOX) openInbox.value = true
        setContent {
            val model: FoundationViewModel = viewModel(factory = FoundationViewModel.Factory(repository, meetSpace.pushController::beforeIdentityChange))
            val meetingsModel: MeetingsViewModel = viewModel(factory = MeetingsViewModel.Factory(repository, meetSpace.meetingsRepository))
            val recordingsModel: RecordingsViewModel = viewModel(factory = RecordingsViewModel.Factory(repository, meetSpace.meetingsRepository, meetSpace.recordingsRepository))
            val recordingsState = recordingsModel.state.collectAsStateWithLifecycle().value
            val recordingPlayback = recordingsModel.playback.collectAsStateWithLifecycle().value
            val foundationHolder = rememberSaveableStateHolder()
            val messagingModel: MessagingViewModel = viewModel(factory = MessagingViewModel.Factory(repository, meetSpace.messagingRepository))
            val messagingState = messagingModel.state.collectAsStateWithLifecycle().value
            val directCalls: DirectCallViewModel = viewModel(factory = DirectCallViewModel.Factory(repository, meetSpace.directCallsRepository, meetSpace.callEngine) {
                !AccountDevicePreferencesStore(meetSpace.getSharedPreferences("account-device-preferences", MODE_PRIVATE)).read().autoMute
            })
            val directSession = directCalls.state.collectAsStateWithLifecycle().value
            DisposableEffect(directSession.incoming && !directSession.ending) {
                val previous = volumeControlStream
                if (directSession.incoming && !directSession.ending) volumeControlStream = AudioManager.STREAM_RING
                onDispose { volumeControlStream = previous }
            }
            IncomingCallRingtone(directSession)
            val requestedInbox = openInbox.collectAsStateWithLifecycle().value
            val meetingsState = meetingsModel.state.collectAsStateWithLifecycle().value
            val callState = meetSpace.callEngine.state.collectAsStateWithLifecycle().value
            val callDiscussion: CallDiscussionViewModel = viewModel(factory = CallDiscussionViewModel.Factory(repository, meetSpace.meetingChatRepository, meetSpace.meetingsRepository, meetSpace.recordingsRepository))
            val callDetails = callDiscussion.state.collectAsStateWithLifecycle().value
            LaunchedEffect(callState.conferenceId, callState.phase, callState.participantId, callState.participants, callState.discussionRevision, callState.recordingRevision, callState.directCallId) {
                callDiscussion.bind(if (callState.directCallId == null) callState else com.meetspace.core.media.CallState())
            }
            LaunchedEffect(directCalls) {
                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    directCalls.foreground(true)
                    try { awaitCancellation() } finally { directCalls.foreground(false) }
                }
            }
            DisposableEffect(messagingModel, directCalls) {
                messagingModel.onCallEvent(directCalls::event)
                onDispose { messagingModel.onCallEvent(null) }
            }
            LaunchedEffect(directSession.call?.id, directSession.call?.version) {
                if (directSession.call != null) messagingModel.refresh()
            }
            LaunchedEffect(callDiscussion) {
                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    callDiscussion.foreground(true)
                    try { awaitCancellation() } finally { callDiscussion.foreground(false) }
                }
            }
            val state = model.state.collectAsStateWithLifecycle().value
            val callMinimized = rememberSaveable(callState.conferenceId, state.repository.selectedProfile?.id, state.repository.session?.user?.id) { mutableStateOf(false) }
            val pushState = meetSpace.pushController.state.collectAsStateWithLifecycle().value
            val sshModel: SshWizardViewModel = viewModel(factory = SshWizardViewModel.Factory(meetSpace.sshRepository))
            val installerModel: InstallerViewModel = viewModel(factory = InstallerViewModel.Factory(meetSpace.installerRepository))
            val installState = installerModel.state.collectAsStateWithLifecycle().value
            val sshState = sshModel.state.collectAsStateWithLifecycle().value
            LaunchedEffect(state.screen) {
                if (state.screen != FoundationScreen.INSTALLER) {
                    sshModel.reset()
                    installerModel.detach()
                }
            }
            LaunchedEffect(requestedInbox, state.repository.session?.user?.id, recordingsState.visible) {
                // A notification is an explicit navigation request even when Foundation is
                // temporarily outside composition behind the recording/player route.
                if (requestedInbox && state.repository.session != null && recordingsState.visible) recordingsModel.close()
            }
            LaunchedEffect(messagingModel) {
                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    messagingModel.foreground(true)
                    try { awaitCancellation() } finally { messagingModel.foreground(false) }
                }
            }
            LaunchedEffect(installerModel) {
                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    installerModel.foreground(true)
                    try { awaitCancellation() } finally { installerModel.foreground(false) }
                }
            }
            LaunchedEffect(state.busy, state.repository.selectedProfile?.id, state.repository.session?.user?.id) {
                if (!state.busy) {
                    if (state.repository.session != null && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) messagingModel.foreground(true)
                    meetSpace.pushController.reconcile()
                }
            }
            DisposableEffect(messagingModel) {
                messagingModel.onNotification(meetSpace.messagingNotifications::show)
                onDispose { messagingModel.onNotification(null) }
            }
            MeetSpaceTheme(themeMode = state.repository.theme) {
                val meetingVisible = callState.visible && callState.directCallId == null
                val darkCallCanvas = directSession.visible || meetingVisible && !callMinimized.value && callState.phase !in setOf(CallPhase.PREJOIN, CallPhase.FAILED)
                val lightBars = !darkCallCanvas && MaterialTheme.colorScheme.background.luminance() > 0.5f
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = lightBars
                        isAppearanceLightNavigationBars = lightBars
                    }
                }
                val authIdentity = state.repository.selectedProfile?.id to state.repository.session?.user?.id
                val lastIdentity = remember { mutableStateOf(authIdentity) }
                val newChatRequested = remember(authIdentity) { mutableStateOf(false) }
                val foldersRequested = remember(authIdentity) { mutableStateOf(false) }
                val callsSearchVisible = remember(authIdentity) { mutableStateOf(false) }
                val workspaceRequest = rememberSaveable(authIdentity) { mutableStateOf<WorkspaceTab?>(null) }
                val activeWorkspace = remember(authIdentity) { mutableStateOf(WorkspaceTab.CALLS) }
                val personalBannerVisible = directSession.active && directSession.minimized &&
                    (activeWorkspace.value != WorkspaceTab.MESSAGES || messagingState.selectedId != directSession.call?.conversationId || recordingsState.visible)
                LaunchedEffect(authIdentity) {
                    if (lastIdentity.value != authIdentity && callState.visible) meetSpace.callEngine.leave(updateMembership = false)
                    if (lastIdentity.value != authIdentity) meetSpace.messagingNotifications.clear()
                    lastIdentity.value = authIdentity
                }
                Column(Modifier.fillMaxSize()) {
                if (personalBannerVisible) Surface(color = Color(0xFF21304A), contentColor = Color.White,
                    modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
                    TextButton(onClick = directCalls::resume, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("return-to-personal-call")) {
                        Text("${if (directSession.incoming) "Входящий звонок" else "Вернуться к звонку"} · ${directSession.peer?.displayName.orEmpty()}", color = Color(0xFFBDD0F7), maxLines = 1)
                    }
                }
                if (meetingVisible && callMinimized.value) Surface(color = Color(0xFF1E2838), contentColor = Color.White,
                    modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
                    TextButton(onClick = { callMinimized.value = false }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("return-to-active-call")) {
                        Text("Вернуться к встрече · ${callState.title}", color = Color(0xFFB5CAFF), maxLines = 1)
                    }
                }
                Box(Modifier.fillMaxWidth().weight(1f).then(if (meetingVisible && callMinimized.value || personalBannerVisible) Modifier.consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)) else Modifier)) {
                if (directSession.visible) PersonalCallHost(directCalls, directSession, meetSpace.callEngine, callState,
                    selfName = state.repository.session?.user?.displayLabel.orEmpty(), minimize = {
                        directCalls.minimize(); directSession.call?.conversationId?.let(messagingModel::open)
                        if (meetingVisible) callMinimized.value = true
                        recordingsModel.close(); workspaceRequest.value = WorkspaceTab.MESSAGES
                    })
                else if (meetingVisible && !callMinimized.value) MeetingCallHost(meetSpace.callEngine, callState, callDiscussion, callDetails,
                    authScope = listOf(state.repository.selectedProfile?.id, state.repository.selectedProfile?.origin, state.repository.session?.user?.id).joinToString("|"),
                    profileLabel = state.repository.session?.user?.displayLabel.orEmpty(),
                    onWorkspaceTab = if (state.repository.session?.guestMeeting != null) null else { tab -> callDiscussion.actions.open(null); recordingsModel.close(); callMinimized.value = true; workspaceRequest.value = tab })
                else if (recordingsState.visible) RecordingsHost(recordingsModel, recordingsState, recordingPlayback, onWorkspaceTab = { recordingsModel.close(); workspaceRequest.value = it }, profileLabel = state.repository.session?.user?.displayLabel.orEmpty())
                else if (state.repository.session?.guestMeeting != null) GuestMeetingLobby(state,
                    join = { state.repository.session?.guestMeeting?.let(meetSpace.callEngine::prepare) }, refresh = model::refreshGuest, leave = model::logout)
                else foundationHolder.SaveableStateProvider("foundation") { FoundationUi(
                    state = state,
                    onConnect = { recordingsModel.close(); meetingsModel.suspendWork(); messagingModel.suspendWork(); model.connect(it) },
                    onSelectProfile = { recordingsModel.close(); meetingsModel.suspendWork(); messagingModel.suspendWork(); model.selectProfile(it) },
                    onRemoveProfile = { recordingsModel.close(); meetingsModel.suspendWork(); messagingModel.suspendWork(); model.removeProfile(it) },
                    onLogin = { email, password -> recordingsModel.close(); meetingsModel.suspendWork(); messagingModel.suspendWork(); model.login(email, password) },
                    onRegister = model::register, onGuest = model::joinGuest,
                    onLogout = { recordingsModel.close(); meetingsModel.suspendWork(); messagingModel.suspendWork(); meetSpace.messagingNotifications.clear(); model.logout() },
                    onTheme = model::theme,
                    onNavigate = model::navigate,
                    onClearMessage = model::clearMessage,
                    onNotifications = { openInbox.value = true },
                    unreadCount = messagingState.notificationUnread.coerceIn(0, 99).toInt(),
                    messageThreadOpen = messagingState.selectedId != null,
                    requestedWorkspaceTab = workspaceRequest.value,
                    consumeWorkspaceTab = { workspaceRequest.value = null },
                    onCallsSearch = { callsSearchVisible.value = !callsSearchVisible.value },
                    workspaceContent = { tab, onTab ->
                        key(state.repository.selectedProfile?.id, state.repository.selectedProfile?.origin, state.repository.session?.user?.id) {
                            LaunchedEffect(tab) {
                                activeWorkspace.value = tab
                                if (tab == com.meetspace.app.ui.WorkspaceTab.HOME) {
                                    messagingModel.activate()
                                    if (messagingState.selectedFolderId != null) messagingModel.filter(messagingState.filter)
                                }
                            }
                            DisposableEffect(tab) {
                                messagingModel.workspaceVisible(tab == com.meetspace.app.ui.WorkspaceTab.MESSAGES)
                                onDispose { messagingModel.workspaceVisible(false) }
                            }
                            LaunchedEffect(requestedInbox) {
                                if (requestedInbox) onTab(com.meetspace.app.ui.WorkspaceTab.MESSAGES)
                            }
                            if (tab == com.meetspace.app.ui.WorkspaceTab.MESSAGES) MessagingHost(messagingModel, messagingState, meetSpace.pushController, pushState,
                                listOf(state.repository.selectedProfile?.origin, state.repository.session?.user?.id).joinToString("|"),
                                openMeeting = { id -> meetingsModel.open(id); onTab(com.meetspace.app.ui.WorkspaceTab.CALLS) },
                                serverOrigin = state.repository.selectedProfile?.origin,
                                onStartCall = messagingState.detail.data?.takeIf { it.type == "direct" }?.peer?.let { peer -> { directCalls.start(peer) } },
                                personalCall = directSession.takeIf { it.active && it.call?.conversationId == messagingState.selectedId },
                                onResumeCall = directCalls::resume,
                                newChatRequested = newChatRequested.value, foldersRequested = foldersRequested.value,
                                consumeWorkspaceRequest = { newChatRequested.value = false; foldersRequested.value = false },
                                inboxRequested = requestedInbox, consumeInbox = {
                                    openInbox.value = false
                                    // onCreate sees this Intent again after rotation. Consume
                                    // only this action, retaining all other launch information.
                                    if (intent.action == MessagingNotifications.OPEN_INBOX) setIntent(Intent(intent).setAction(null))
                                })
                            else if (tab == com.meetspace.app.ui.WorkspaceTab.PROFILE) SettingsHost(pushState, content = { settings, settingsActions ->
                                val changeAccount: (() -> Unit) -> Unit = { action ->
                                    when {
                                        directSession.active -> directCalls.message("Сначала завершите личный звонок, чтобы изменить аккаунт.")
                                        callState.visible -> meetSpace.callEngine.message("Сначала завершите встречу, чтобы изменить аккаунт.")
                                        else -> action()
                                    }
                                }
                                ProfileWorkspace(state.repository, meetingsState, meetingsModel.actions, settings, settingsActions,
                                    busy = state.busy, onTheme = model::theme,
                                    onLogout = { recordingsModel.close(); meetingsModel.suspendWork(); messagingModel.suspendWork(); meetSpace.messagingNotifications.clear(); model.logout() },
                                    onServers = { model.navigate(FoundationScreen.CONNECT) },
                                    onFolders = { foldersRequested.value = true; onTab(com.meetspace.app.ui.WorkspaceTab.MESSAGES) },
                                    messaging = messagingState, messagingActions = messagingModel.actions,
                                    onServerAddress = { origin -> recordingsModel.close(); meetingsModel.suspendWork(); messagingModel.suspendWork(); model.connect(origin) },
                                    onSelectAccount = { id -> changeAccount { model.selectProfile(id) } },
                                    initialSection = state.accountSection, registeredAccountEmail = state.accountCreatedEmail,
                                    onAddAccount = { url, email, password, name, confirmation -> changeAccount { model.addAccount(url, email, password, name, confirmation) } },
                                    onDeleteAccount = { password -> changeAccount { model.deleteAccount(password) } })
                            }) { openInbox.value = true; onTab(com.meetspace.app.ui.WorkspaceTab.MESSAGES) }
                            else {
                                MeetingsWorkspace(tab, meetingsState, state.repository, meetingsModel.actions.copy(call = { meeting ->
                                    if (directSession.active) { directCalls.resume(); directCalls.message("Сначала завершите личный звонок, чтобы подключиться к встрече.") }
                                    else if (callState.visible) { callMinimized.value = false; meetSpace.callEngine.message("Сначала завершите текущую встречу, чтобы подключиться к другой.") }
                                    else meetSpace.callEngine.prepare(meeting)
                                }, recordings = recordingsModel::show), onTab,
                                    callsSearchVisible = callsSearchVisible.value,
                                    onFolders = { foldersRequested.value = true; onTab(com.meetspace.app.ui.WorkspaceTab.MESSAGES) },
                                    homeContent = { create, schedule, join ->
                                        HomeDashboard(state.repository, messagingState, messagingModel.actions,
                                            meetingsState.actionBusy, meetingsState.actionBusy || meetingsState.creationUncertain,
                                            onCreate = create,
                                            onNewChat = { newChatRequested.value = true; onTab(com.meetspace.app.ui.WorkspaceTab.MESSAGES) },
                                            onJoin = join, onSchedule = schedule,
                                            onOpenChat = { id -> messagingModel.open(id); onTab(com.meetspace.app.ui.WorkspaceTab.MESSAGES) },
                                            onFolders = { foldersRequested.value = true; onTab(com.meetspace.app.ui.WorkspaceTab.MESSAGES) },
                                            onProfile = { onTab(com.meetspace.app.ui.WorkspaceTab.PROFILE) })
                                    })
                            }
                        }
                    },
                    installerContent = {
                        if (installState.active) InstallerUi(
                            state = installState,
                            onImportKey = { uri -> installerModel.importKey { contentResolver.openInputStream(uri) } },
                            onAuthenticate = installerModel::authenticate, onApply = installerModel::apply,
                            onControl = installerModel::control, onDetach = installerModel::detach,
                            onReconnect = installerModel::reconnect, onForgetCompleted = installerModel::forgetCompleted,
                            onRefreshPlan = installerModel::refreshMissingPlan,
                            onEditOptions = installerModel::editOptions,
                            onConnect = { origin -> installerModel.detach(); sshModel.reset(); model.navigate(FoundationScreen.CONNECT); model.connect(origin) },
                            onCreateAccount = { origin -> installerModel.detach(); sshModel.reset(); model.navigate(FoundationScreen.CONNECT); model.connectForRegistration(origin) },
                        ) else SshWizardUi(
                            state = sshState,
                            onProbe = sshModel::probe,
                            onConfirmTrust = sshModel::confirmTrust,
                            onForgetTrust = sshModel::forgetTrust,
                            onImportKey = { uri -> sshModel.importPrivateKey { contentResolver.openInputStream(uri) } },
                            onClearKey = sshModel::clearImportedKey,
                            onPasswordPreflight = sshModel::preflightPassword,
                            onKeyPreflight = sshModel::preflightKey,
                            onCancel = sshModel::cancelOperation,
                            onBack = sshModel::back,
                            onExit = { sshModel.reset(); model.navigate(FoundationScreen.WELCOME) },
                            onClearMessage = sshModel::clearMessage,
                            onInstall = if (installState.pending != null) null else sshState.request?.let { request -> {
                                installerModel.start(request.endpoint, request.domain, sshModel.takeInstallationAccess())
                            } },
                            onReconnectInstall = if (installState.pending != null) installerModel::reconnect else null,
                        )
                    },
                ) }
                }
                }
                if (!directSession.visible && directSession.error != null) AlertDialog(onDismissRequest = directCalls::clearMessage,
                    title = { Text("Личный звонок") }, text = { Text(directSession.error) },
                    confirmButton = { TextButton(onClick = directCalls::clearMessage) { Text("Понятно") } })
            }
        }
    }
}
