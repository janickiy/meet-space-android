package com.meetspace.app

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.view.WindowCompat
import com.meetspace.app.ui.*
import com.meetspace.core.designsystem.MeetSpaceTheme
import com.meetspace.core.model.*
import java.io.File
import java.time.Instant
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.Assume.assumeTrue

/** Fixtures stay in androidTest. Every mutation is checked as a request, never a simulated success. */
@OptIn(ExperimentalTestApi::class)
class MessagingUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun homeNewChatShortcutOpensCreationEvenWithPreviousConversationSelected() {
        var request by mutableStateOf(true)
        var searches = 0
        compose.setContent {
            MeetSpaceTheme(ThemeMode.DARK) {
                MessagingWorkspace(ready.copy(mutationRevision = 4), MessagingActions(searchUsers = { searches++ }),
                    newChatRequested = request, consumeWorkspaceRequest = { request = false })
            }
        }
        compose.onNodeWithTag("chat-user-search").assertIsDisplayed()
        compose.onNodeWithTag("create-chat-group").assertIsDisplayed()
        compose.runOnIdle { assertFalse(request); assertEquals(1, searches) }
        compose.onNodeWithText("Закрыть").performClick()
        compose.onNodeWithTag("chat-user-search").assertDoesNotExist()
    }

    @Test fun homeFoldersShortcutOpensExistingFolderManager() {
        var request by mutableStateOf(true)
        compose.setContent {
            MeetSpaceTheme(ThemeMode.DARK) {
                MessagingWorkspace(ready, MessagingActions(), foldersRequested = request,
                    consumeWorkspaceRequest = { request = false })
            }
        }
        compose.onNodeWithTag("folder-name").assertIsDisplayed()
        compose.runOnIdle { assertFalse(request) }
    }

    @Test fun tabletKeepsListAndHistoryVisibleWhilePhoneShowsDetailAndBack() {
        var compact by mutableStateOf(false)
        var back = 0
        compose.setContent { Screen(ready, MessagingActions(back = { back++ }), if (compact) 360 else 1000) }
        compose.onNodeWithTag("messaging-two-pane").assertExists()
        compose.onNodeWithTag("conversation-list").assertIsDisplayed()
        compose.onNodeWithTag("message-draft").assertIsDisplayed()
        capture("stage07-messaging-tablet.png")
        compose.runOnIdle { compact = true }
        compose.onNodeWithTag("messaging-single-pane").assertExists()
        compose.onNodeWithTag("conversation-list").assertDoesNotExist()
        compose.onNodeWithTag("conversation-back").performClick()
        compose.runOnIdle { assertEquals(1, back) }
    }

    @Test fun largeFontUsesSinglePaneAndKeepsComposerUsable() {
        var sent = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                Screen(ready.copy(draft = "Сообщение крупным шрифтом"), MessagingActions(send = { sent++ }), 360)
            }
        }
        compose.onNodeWithTag("messaging-single-pane").assertExists()
        compose.onNodeWithTag("message-send").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, sent) }
        capture("stage07-messaging-phone-large-font.png")
    }

    @Test fun shortLandscapeWithLargeFontPreservesHistoryAndAllComposeActions() {
        var state by mutableStateOf(ready.copy(draft = "Черновик с длинной первой строкой\nи второй строкой, который не должен вытеснять историю"))
        var cancelled = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                MeetSpaceTheme {
                    Box(Modifier.width(600.dp).height(280.dp)) {
                        MessagingWorkspace(state, MessagingActions(cancelCompose = { cancelled++ }))
                    }
                }
            }
        }
        compose.onNodeWithTag("message-history").assertHeightIsAtLeast(72.dp)
        compose.onNodeWithTag("message-send").assertIsDisplayed()
        compose.onNodeWithTag("message-attach").assertIsDisplayed()
        capture("stage07-messaging-landscape-large-font.png")
        compose.runOnIdle {
            state = state.copy(replyTo = peerMessage, attachments = (1..5).map { file.copy(id = "pending-$it") },
                error = "Подключение прервано. Уже загруженная история доступна в памяти приложения. Обновите сообщения после восстановления подключения к серверу.")
        }
        compose.onNodeWithTag("message-history").assertHeightIsAtLeast(72.dp)
        compose.onNodeWithTag("message-send").assertIsDisplayed()
        compose.onNodeWithTag("message-attach").assertIsDisplayed()
        compose.onNodeWithTag("message-compose-context").assertIsDisplayed().performClick()
        compose.onNodeWithTag("pending-attachment-pending-5").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("message-cancel-compose").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, cancelled) }
    }

    @Test fun resizingComposerBetweenTallAndCompactPreservesDraftAndInput() {
        val originalDraft = "Первая строка черновика\nВторая строка остаётся после поворота"
        var state by mutableStateOf(ready.copy(draft = originalDraft))
        var panelHeight by mutableStateOf(540.dp)
        var keyboardController: SoftwareKeyboardController? = null
        compose.setContent {
            val density = LocalDensity.current
            keyboardController = LocalSoftwareKeyboardController.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                MeetSpaceTheme {
                    Box(Modifier.width(600.dp).height(panelHeight)) {
                        MessagingWorkspace(state, MessagingActions(draft = { state = state.copy(draft = it) }))
                    }
                }
            }
        }
        fun assertDraftAvailable() {
            compose.onNodeWithTag("message-draft").assertIsDisplayed().assertIsEnabled()
                .assert(hasSetTextAction())
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(originalDraft)))
        }
        assertDraftAvailable()
        compose.onNodeWithTag("message-draft").performClick().assertIsFocused()
        // Keep the focused field while isolating the explicit layout resize from IME insets.
        compose.runOnIdle { keyboardController?.hide() }
        compose.runOnIdle { panelHeight = 280.dp }
        assertDraftAvailable()
        compose.onNodeWithTag("message-draft").assertIsFocused()
        compose.onNodeWithTag("message-history").assertHeightIsAtLeast(72.dp)
        compose.runOnIdle { panelHeight = 540.dp }
        assertDraftAvailable()
        compose.onNodeWithTag("message-draft").assertIsFocused()
        compose.onNodeWithTag("message-draft").performTextReplacement("Продолжаем после поворота")
        compose.runOnIdle { assertEquals("Продолжаем после поворота", state.draft) }
    }

    @Test fun paginationAndServerSearchAreExplicitCallbacks() {
        var query = ""
        var filter: ChatFilter? = null
        var more = 0
        val state = ready.copy(selectedId = null, conversationCursor = "opaque")
        compose.setContent { Screen(state, MessagingActions(query = { query = it }, filter = { filter = it }, moreConversations = { more++ }), 360) }
        compose.onNodeWithTag("messaging-search-toggle").performClick()
        compose.onNodeWithTag("conversation-search").performTextInput("Анна")
        compose.runOnIdle { assertEquals("Анна", query) }
        compose.onNodeWithTag("chat-filter-direct").performClick()
        compose.runOnIdle { assertEquals(ChatFilter.DIRECT, filter) }
        compose.onNodeWithTag("conversation-list").performScrollToNode(hasTestTag("conversations-more"))
        compose.onNodeWithTag("conversations-more").performClick()
        compose.runOnIdle { assertEquals(1, more) }
    }

    @Test fun failedHistoryPreservesCachedMessagesAndNeverClaimsSuccessfulEmptyState() {
        compose.setContent { Screen(ready.copy(messages = MessagingLoad(listOf(ownMessage), error = "Нет подключения. Загруженные сообщения сохранены в памяти.")), width = 360) }
        compose.onNodeWithTag("message-${ownMessage.id}").assertExists()
        compose.onNodeWithText("Начните разговор").assertDoesNotExist()
        compose.onNodeWithText("Нет подключения. Загруженные сообщения сохранены в памяти.").assertExists()
    }

    @Test fun composerRequiresContentAndBlocksDuplicateSendUntilServerReturns() {
        var state by mutableStateOf(ready.copy(draft = ""))
        var sends = 0
        var draft = ""
        compose.setContent { Screen(state, MessagingActions(send = { sends++ }, draft = { draft = it }), 360) }
        compose.onNodeWithTag("message-send").assertIsNotEnabled()
        compose.onNodeWithTag("message-draft").performTextInput("Проверка")
        compose.runOnIdle { assertEquals("Проверка", draft); state = state.copy(draft = draft) }
        compose.onNodeWithTag("message-send").performClick()
        compose.runOnIdle { assertEquals(1, sends); state = state.copy(sending = true) }
        compose.onNodeWithTag("message-send").assertIsNotEnabled()
        compose.onNodeWithTag("message-attach").assertIsNotEnabled()
        compose.onNodeWithTag("message-draft").assertIsNotEnabled()
    }

    @Test fun ambiguousSendLocksPayloadAndOffersRetryWithoutFakeDeliveredStatus() {
        var retries = 0
        compose.setContent { Screen(ready.copy(pendingSend = true, draft = "Ожидает подтверждения"), MessagingActions(send = { retries++ }), 360) }
        compose.onNodeWithTag("message-pending-send").assertExists()
        compose.onNodeWithTag("message-draft").assertIsNotEnabled()
        compose.onNodeWithTag("message-attach").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Повторить отправку").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
        compose.onNodeWithText("Доставлено").assertDoesNotExist()
    }

    @Test fun ownMessageActionsRequireConfirmationForDeleteAndOtherAuthorsCannotBeEdited() {
        var reply: ChatMessage? = null
        var edit: ChatMessage? = null
        var deleted: ChatMessage? = null
        compose.setContent { Screen(ready, MessagingActions(reply = { reply = it }, edit = { edit = it }, delete = { deleted = it }), 1000) }
        compose.onNodeWithTag("message-menu-${ownMessage.id}").performClick()
        compose.onNodeWithTag("message-edit-${ownMessage.id}").performClick()
        compose.runOnIdle { assertEquals(ownMessage, edit) }
        compose.onNodeWithTag("message-menu-${ownMessage.id}").performClick()
        compose.onNodeWithTag("message-delete-${ownMessage.id}").performClick()
        compose.runOnIdle { assertNull(deleted) }
        compose.onNodeWithTag("message-confirm-delete").performClick()
        compose.runOnIdle { assertEquals(ownMessage, deleted) }
        compose.onNodeWithTag("message-history").performScrollToNode(hasTestTag("message-menu-${peerMessage.id}"))
        compose.onNodeWithTag("message-menu-${peerMessage.id}").performClick()
        compose.onNodeWithTag("message-edit-${peerMessage.id}").assertDoesNotExist()
        compose.onNodeWithTag("message-delete-${peerMessage.id}").assertDoesNotExist()
        compose.onNodeWithTag("message-reply-${peerMessage.id}").performClick()
        compose.runOnIdle { assertEquals(peerMessage, reply) }
    }

    @Test fun tombstoneDoesNotExposeTextFilesOrActions() {
        val deleted = ownMessage.copy(text = "private removed text", attachments = listOf(file), deletedAt = now)
        compose.setContent { Screen(ready.copy(messages = MessagingLoad(listOf(deleted))), width = 360) }
        compose.onNodeWithText("Сообщение удалено").assertExists()
        compose.onNodeWithText("private removed text").assertDoesNotExist()
        compose.onNodeWithTag("attachment-${file.id}").assertDoesNotExist()
        compose.onNodeWithTag("message-menu-${ownMessage.id}").assertDoesNotExist()
    }

    @Test fun attachmentsUsePickerAndExplicitDownloadWithoutOpeningRemoteLinks() {
        var picked = 0
        var downloaded: ChatAttachment? = null
        compose.setContent { Screen(ready.copy(messages = MessagingLoad(listOf(ownMessage.copy(attachments = listOf(file))))), MessagingActions(pickAttachment = { picked++ }, download = { downloaded = it }), 1000) }
        compose.onNodeWithTag("message-attach").performClick()
        compose.runOnIdle { assertEquals(1, picked) }
        compose.onNodeWithTag("attachment-${file.id}").performClick()
        compose.runOnIdle { assertEquals(file, downloaded) }
    }

    @Test fun createGroupNeedsTitleAndSelectedUserAndWaitsForAcknowledgment() {
        var created: Triple<String, String, List<String>>? = null
        var state by mutableStateOf(ready.copy(selectedId = null, users = MessagingLoad(listOf(peer))))
        compose.setContent { Screen(state, MessagingActions(createGroup = { name, description, users -> created = Triple(name, description, users) }), 1000) }
        compose.onNodeWithTag("messaging-new").performClick()
        compose.onNodeWithTag("create-chat-group").performClick()
        compose.onNodeWithTag("create-group-submit").assertIsNotEnabled()
        compose.onNodeWithTag("create-group-name").performTextInput("  Команда  ")
        compose.onNodeWithTag("group-select-${peer.id}").performScrollTo().performClick()
        compose.onNodeWithTag("create-group-submit").performClick()
        compose.runOnIdle { assertEquals(Triple("Команда", "", listOf(peer.id)), created); state = state.copy(busy = true) }
        compose.onNodeWithTag("create-group-submit").assertIsNotEnabled()
        compose.onNodeWithTag("create-group-name").assertExists()
    }

    @Test fun directChatUsesSelectedPersonIdentity() {
        var target: String? = null
        compose.setContent { Screen(ready.copy(selectedId = null, users = MessagingLoad(listOf(peer))), MessagingActions(createDirect = { target = it }), 360) }
        compose.onNodeWithTag("messaging-new").performClick()
        compose.onNodeWithTag("direct-create-${peer.id}").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(peer.id, target) }
    }

    @Test fun foldersIncludeMeetingsAndRemovingMembershipDoesNotDeleteResource() {
        var meetingId: String? = null
        var membership: List<Any>? = null
        val meeting = Meeting("meeting", "self", "Планирование", "", "", "scheduled", now, now, scheduledAt = now)
        val state = ready.copy(selectedId = null, selectedFolderId = folder.id, folders = MessagingLoad(listOf(folder)), folderEntries = MessagingLoad(listOf(FolderEntry("conference", meeting = meeting))))
        compose.setContent { Screen(state, MessagingActions(openMeeting = { meetingId = it }, folderMembership = { id, kind, item, included -> membership = listOf(id, kind, item, included) }), 360) }
        compose.onNodeWithTag("folder-meeting-${meeting.id}").performClick()
        compose.runOnIdle { assertEquals(meeting.id, meetingId) }
        compose.onNodeWithTag("folder-remove-${meeting.id}").performClick()
        compose.runOnIdle { assertEquals(listOf(folder.id, "conference", meeting.id, false), membership) }
    }

    @Test fun folderNamesAreValidatedBeforeCreateAndDeletionRequiresExplicitConfirmation() {
        var created: String? = null
        var deleted: String? = null
        compose.setContent { Screen(ready.copy(selectedId = null, folders = MessagingLoad(listOf(folder))), MessagingActions(createFolder = { created = it }, deleteFolder = { deleted = it }), 1000) }
        compose.onNodeWithTag("chat-folder-filter").performScrollTo().performClick()
        compose.onNodeWithTag("folders-manage").performClick()
        compose.onNodeWithTag("folder-save").assertIsNotEnabled()
        compose.onNodeWithTag("folder-name").performScrollTo().performTextInput("  Работа  ")
        compose.onNodeWithTag("folder-save").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("Работа", created) }
        compose.onNodeWithTag("folder-delete-${folder.id}").performScrollTo().performClick()
        compose.runOnIdle { assertNull(deleted) }
        compose.onNodeWithText("Чаты и встречи сохранятся. Удалится только папка.").assertExists()
        compose.onNodeWithTag("folder-confirm-delete").performClick()
        compose.runOnIdle { assertEquals(folder.id, deleted) }
    }

    @Test fun notificationPreferencesPreserveTransportFlagsAndOpenResourceIsSeparateFromRead() {
        var read: String? = null
        var opened: InboxNotification? = null
        var saved: NotificationPreferences? = null
        val event = InboxNotification("n1", "conference.reminder", now, conferenceId = "meeting", scheduledAt = now)
        val prefs = NotificationPreferences(email = true, push = true)
        compose.setContent { Screen(ready.copy(notifications = MessagingLoad(listOf(event)), notificationUnread = 1, notificationPreferences = MessagingLoad(prefs)), MessagingActions(readNotification = { read = it }, openNotification = { opened = it }, saveNotificationPreferences = { saved = it }), 1000) }
        compose.onNodeWithTag("chat-folder-filter").performScrollTo().performClick()
        compose.onNodeWithTag("messaging-notifications").performClick()
        compose.onNodeWithTag("notification-read-${event.id}").performClick()
        compose.runOnIdle { assertEquals(event.id, read); assertNull(opened) }
        compose.onNodeWithTag("notifications-settings").performClick()
        compose.onNodeWithText("Системные уведомления приходят, пока приложение открыто.").assertExists()
        compose.onNodeWithTag("notification-pref-reminder").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(prefs.copy(reminder = false), saved) }
        compose.onNodeWithTag("notifications-settings").performClick()
        compose.onNodeWithTag("notification-open-${event.id}").performClick()
        compose.runOnIdle { assertEquals(event, opened) }
    }

    @Test fun compactNotificationRowsKeepActualDatesPagingAndChatsActionAtLargeText() {
        var back = 0
        var more = 0
        var read: String? = null
        var opened: InboxNotification? = null
        val reminder = InboxNotification("reminder", "conference.soon", now, conferenceId = "meeting", scheduledAt = now.plusSeconds(3600))
        val message = InboxNotification("message", "chat.message.created", now, readAt = now, conversationId = group.id)
        val state = ready.copy(notifications = MessagingLoad(listOf(reminder, message)), notificationUnread = 1, notificationCursor = "next")
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(430.dp, 932.dp))) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                    MeetSpaceTheme { MessagingWorkspace(state, MessagingActions(back = { back++ }, moreNotifications = { more++ }, readNotification = { read = it }, openNotification = { opened = it }), inboxRequested = true) }
                }
            }
        }
        val created = now.atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", java.util.Locale.forLanguageTag("ru")))
        compose.onNodeWithTag("notification-date-${reminder.id}").assertTextEquals("Получено: $created")
        compose.onNodeWithTag("notification-read-${reminder.id}").performClick()
        compose.runOnIdle { assertEquals(reminder.id, read); assertNull(opened) }
        compose.onNodeWithTag("notifications-list").performScrollToNode(hasTestTag("notification-row-${message.id}"))
        compose.onNodeWithText("Новое сообщение").assertIsDisplayed()
        compose.onNodeWithTag("notification-read-${message.id}").assertDoesNotExist()
        compose.onNodeWithTag("notifications-list").performScrollToNode(hasTestTag("notifications-more"))
        compose.onNodeWithTag("notifications-more").performClick()
        compose.runOnIdle { assertEquals(1, more) }
        compose.onNodeWithTag("notifications-open-chats").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, back); assertNull(opened) }
        compose.onNodeWithTag("notifications-dialog").assertDoesNotExist()
    }

    @Test fun failedListNeverShowsEmptySuccessAndRefreshRemainsAvailable() {
        var refreshed = 0
        compose.setContent { Screen(ready.copy(selectedId = null, conversations = MessagingLoad(emptyList(), error = "Сервер недоступен")), MessagingActions(refresh = { refreshed++ }), 360) }
        compose.onNodeWithText("Сервер недоступен").assertExists()
        compose.onNodeWithText("Пока нет чатов в этом разделе").assertDoesNotExist()
        compose.onNodeWithTag("chat-folder-filter").performScrollTo().performClick()
        compose.onNodeWithTag("conversations-refresh").performClick()
        compose.runOnIdle { assertEquals(1, refreshed) }
    }

    @Test fun unconfiguredPushShowsUnavailableWithoutOfferingRegistration() {
        compose.setContent { Screen(ready.copy(pushConfigured = false, pushEnabled = false), width = 1000) }
        compose.onNodeWithTag("chat-folder-filter").performScrollTo().performClick()
        compose.onNodeWithTag("messaging-notifications").performClick()
        compose.onNodeWithTag("notifications-settings").performClick()
        compose.onNodeWithText("Фоновые push ещё не настроены.").assertIsDisplayed()
        compose.onNodeWithText("Системные уведомления приходят, пока приложение открыто.").assertIsDisplayed()
        compose.onNodeWithTag("notifications-push-enable").assertDoesNotExist()
        compose.onNodeWithTag("notifications-push-disable").assertDoesNotExist()
    }

    @Test fun configuredPushWaitsForAcknowledgmentAndBlocksBothActionsWhileBusy() {
        var state by mutableStateOf(ready.copy(pushConfigured = true))
        var enables = 0
        var disables = 0
        compose.setContent { Screen(state, MessagingActions(enablePush = { enables++ }, disablePush = { disables++ }), 1000) }
        compose.onNodeWithTag("chat-folder-filter").performScrollTo().performClick()
        compose.onNodeWithTag("messaging-notifications").performClick()
        compose.onNodeWithTag("notifications-settings").performClick()
        compose.onNodeWithTag("notifications-push-enable").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, enables); assertEquals(0, disables); state = state.copy(pushBusy = true) }
        compose.onNodeWithTag("notifications-push-enable").assertIsNotEnabled()
        compose.onNodeWithTag("notifications-push-disable").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(pushBusy = false, pushEnabled = true) }
        compose.onNodeWithText("Фоновые push включены на этом устройстве.").assertExists()
        compose.onNodeWithTag("notifications-push-disable").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, disables); state = state.copy(pushBusy = true) }
        compose.onNodeWithTag("notifications-push-disable").assertIsNotEnabled()
        compose.onNodeWithTag("notifications-push-enable").assertDoesNotExist()
    }

    @Test fun approvedNavigationSwitchesAt780EvenWithLargeFont() {
        var width by mutableStateOf(780.dp)
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(width, 900.dp))) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                    MeetSpaceTheme(ThemeMode.DARK) { MessagingWorkspace(ready, MessagingActions()) }
                }
            }
        }
        compose.onNodeWithTag("messaging-two-pane").assertIsDisplayed()
        compose.onNodeWithTag("messaging-header").assertIsDisplayed()
        compose.onNodeWithTag("conversation-list").assertIsDisplayed()
        compose.onNodeWithTag("message-send").assertIsDisplayed()
        compose.runOnIdle { width = 779.dp }
        compose.onNodeWithTag("messaging-single-pane").assertIsDisplayed()
        compose.onNodeWithTag("messaging-header").assertDoesNotExist()
        compose.onNodeWithTag("conversation-list").assertDoesNotExist()
        compose.onNodeWithTag("conversation-back").assertIsDisplayed()
        compose.onNodeWithTag("message-send").assertIsDisplayed()
    }

    @Test fun listSearchAndInviteBannerCanBeDismissedWithoutLosingConversations() {
        var state by mutableStateOf(ready.copy(selectedId = null, query = ""))
        var opened: String? = null
        var searches = 0
        compose.setContent {
            MeetSpaceTheme(ThemeMode.LIGHT) {
                Box(Modifier.width(390.dp).fillMaxHeight()) {
                    MessagingWorkspace(state, MessagingActions(query = { state = state.copy(query = it); searches++ }, open = { opened = it }), serverOrigin = "https://team.example")
                }
            }
        }
        compose.onNodeWithTag("conversation-search").assertDoesNotExist()
        compose.onNodeWithTag("chat-invite-copy").assertIsDisplayed()
        compose.onNodeWithTag("chat-invite-dismiss").performClick()
        compose.onNodeWithTag("chat-invite").assertDoesNotExist()
        compose.onNodeWithTag("messaging-search-toggle").performClick()
        compose.onNodeWithTag("conversation-search").performTextInput("Команда")
        compose.runOnIdle { assertEquals("Команда", state.query) }
        compose.onNodeWithTag("messaging-search-toggle").performClick()
        compose.runOnIdle { assertEquals("", state.query); assertEquals(2, searches) }
        compose.onNodeWithTag("conversation-${group.id}").performClick()
        compose.runOnIdle { assertEquals(group.id, opened) }
    }

    @Test fun threadMenuPreservesGroupManagementAndDirectNotificationActions() {
        var state by mutableStateOf(ready)
        var members = 0
        var notificationValue: Boolean? = null
        var reads = 0
        compose.setContent { Screen(state, MessagingActions(loadMembers = { members++ }, conversationNotifications = { notificationValue = it }, markRead = { reads++ }), 390) }
        compose.onNodeWithTag("conversation-call").assertDoesNotExist()
        compose.onNodeWithTag("conversation-options").performClick()
        compose.onNodeWithTag("group-members").performClick()
        compose.runOnIdle { assertEquals(1, members) }
        compose.onNodeWithText("Готово").performClick()
        compose.runOnIdle { state = ready.copy(selectedId = "direct", detail = MessagingLoad(ChatConversation("direct", "direct", peer, unreadCount = 1))) }
        compose.onNodeWithTag("conversation-options").performClick()
        compose.onNodeWithTag("conversation-notifications").performClick()
        compose.runOnIdle { assertEquals(false, notificationValue) }
        compose.onNodeWithTag("conversation-options").performClick()
        compose.onNodeWithTag("conversation-mark-read").performClick()
        compose.runOnIdle { assertEquals(1, reads) }
    }

    @Test fun busyNewChatFabCannotStartAnotherMutation() {
        var searches = 0
        compose.setContent { Screen(ready.copy(selectedId = null, busy = true), MessagingActions(searchUsers = { searches++ }), 390) }
        compose.onNodeWithTag("messaging-new").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, searches) }
    }

    @Test fun newChatMovesAboveTabletComposerAndRemainsAvailableWithoutSelection() {
        var state by mutableStateOf(ready)
        var width by mutableStateOf(780.dp)
        var searches = 0
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(width, 900.dp))) {
                MeetSpaceTheme(ThemeMode.LIGHT) { MessagingWorkspace(state, MessagingActions(searchUsers = { searches++ })) }
            }
        }
        compose.onNodeWithTag("messaging-options").assertDoesNotExist()
        compose.onNodeWithTag("thread-create-actions").assertIsDisplayed()
        val create = compose.onNodeWithTag("messaging-new").getUnclippedBoundsInRoot()
        val list = compose.onNodeWithTag("conversation-list").getUnclippedBoundsInRoot()
        val draft = compose.onNodeWithTag("message-draft").getUnclippedBoundsInRoot()
        assertTrue("Tablet creation belongs to the conversation pane", create.left > list.right)
        assertTrue("Tablet creation stays above the composer", create.bottom <= draft.top)
        compose.onNodeWithTag("messaging-new").performClick()
        compose.onNodeWithTag("chat-user-search").assertIsDisplayed()
        compose.onNodeWithText("Закрыть").performClick()
        compose.runOnIdle { state = ready.copy(selectedId = null, detail = MessagingLoad()) }
        compose.onNodeWithTag("messaging-new").assertIsDisplayed().performClick()
        compose.onNodeWithTag("chat-user-search").assertIsDisplayed()
        compose.onNodeWithText("Закрыть").performClick()
        compose.runOnIdle { width = 390.dp }
        compose.onNodeWithTag("thread-create-actions").assertDoesNotExist()
        compose.onNodeWithTag("messaging-new").assertIsDisplayed().assertTextContains("Новый чат").performClick()
        compose.runOnIdle { assertEquals(3, searches) }
    }

    @Test fun shortTabletKeepsCreationReachableWhilePreservingMessageHistory() {
        var busy by mutableStateOf(false)
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(1000.dp, 440.dp))) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                    MeetSpaceTheme(ThemeMode.DARK) { MessagingWorkspace(ready.copy(busy = busy), MessagingActions()) }
                }
            }
        }
        compose.onNodeWithTag("thread-create-actions").assertDoesNotExist()
        compose.onNodeWithTag("messaging-new").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("message-history").assertHeightIsAtLeast(72.dp)
        compose.runOnIdle { busy = true }
        compose.onNodeWithTag("messaging-new").assertIsNotEnabled()
    }

    @Test fun tabletHeaderAndLargeTextKeepHistoryVisibleWithErrorsAndAttachments() {
        val state = ready.copy(draft = "Первая строка\nВторая строка\nТретья строка\nЧетвёртая строка",
            replyTo = peerMessage, attachments = (1..5).map { file.copy(id = "pending-$it") },
            error = "Нет связи. Уже загруженные сообщения доступны, повторите после восстановления подключения.",
            detail = ready.detail.copy(error = "Не удалось обновить сведения о чате."),
            messages = ready.messages.copy(error = "Не удалось обновить историю сообщений."))
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(1000.dp, 440.dp))) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                    MeetSpaceTheme(ThemeMode.DARK) { MessagingWorkspace(state, MessagingActions()) }
                }
            }
        }
        compose.onNodeWithTag("messaging-header").assertIsDisplayed()
        compose.onNodeWithTag("message-history").assertHeightIsAtLeast(72.dp)
        compose.onNodeWithTag("message-send").assertIsDisplayed()
        compose.onNodeWithTag("message-attach").assertIsDisplayed()
        compose.onNodeWithTag("message-compose-context").assertIsDisplayed().performClick()
        compose.onNodeWithTag("pending-attachment-pending-5").performScrollTo().assertIsDisplayed()
    }

    /** Explicit visual fixture: all people and messages live only in this test APK. */
    @Test fun captureApprovedMessagingScreenshot() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Opt-in design screenshots", args.getString("designScreenshots") == "true")
        val theme = args.getString("designTheme", "light")
        val screen = args.getString("designScreen", "messages")
        require(theme in setOf("light", "dark"))
        require(screen in setOf("messages", "thread"))
        val configuration = instrumentation.targetContext.resources.configuration
        val snapshot = if (screen == "thread" || configuration.screenWidthDp >= 780) ready.copy(messages = MessagingLoad(listOf(ownMessage, peerMessage.copy(attachments = listOf(file)))))
            else ready.copy(selectedId = null)
        val profile = ServerProfile("visual-server", "https://team.example")
        val repository = RepositoryState(profiles = listOf(profile), selectedProfileId = profile.id,
            session = AccountSession(User("self", "member@example.test", "Александр"), Capabilities(), true))
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
        compose.setContent {
            MeetSpaceTheme(if (theme == "dark") ThemeMode.DARK else ThemeMode.LIGHT) {
                val lightBars = MaterialTheme.colorScheme.background.luminance() > .5f
                SideEffect { WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView).apply {
                    isAppearanceLightStatusBars = lightBars; isAppearanceLightNavigationBars = lightBars
                } }
                FoundationUi(FoundationUiState(FoundationScreen.WORKSPACE, repository),
                    onConnect = {}, onSelectProfile = {}, onRemoveProfile = {}, onLogin = { _, _ -> }, onLogout = {}, onTheme = {}, onNavigate = {}, onClearMessage = {},
                    messageThreadOpen = snapshot.selectedId != null,
                    workspaceContent = { tab, _ -> if (tab == WorkspaceTab.MESSAGES) MessagingWorkspace(snapshot, MessagingActions(), serverOrigin = profile.origin) })
            }
        }
        // Keep pointer press/ripple effects out of the visual fixture.
        compose.onNodeWithTag("tab-messages").performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        compose.onNodeWithTag("messaging-workspace").assertIsDisplayed()
        if (configuration.screenWidthDp < 780 && screen == "thread") compose.onNodeWithTag("bottom-navigation").assertDoesNotExist()
        else compose.onNodeWithTag("bottom-navigation").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        val directory = checkNotNull(instrumentation.targetContext.getExternalFilesDir("screenshots"))
        check(directory.isDirectory || directory.mkdirs())
        val filename = "$screen-$theme-${configuration.screenWidthDp}x${configuration.screenHeightDp}-font${configuration.fontScale}.png"
        val image = compose.onNodeWithTag("workspace").captureToImage().asAndroidBitmap()
        File(directory, filename).outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    private fun capture(name: String) {
        val bitmap = compose.onNodeWithTag("messaging-workspace").captureToImage().asAndroidBitmap()
        val target = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, name)
        target.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Composable private fun Screen(state: MessagingUiState, actions: MessagingActions = MessagingActions(), width: Int = 1000) {
        MeetSpaceTheme { Box(Modifier.width(width.dp).fillMaxHeight()) { MessagingWorkspace(state, actions) } }
    }

    companion object {
        private val now = Instant.parse("2026-10-11T09:00:00Z")
        private val peer = ChatPeer("peer", "Анна Тестовая")
        private val group = ChatConversation("group", "group", name = "Команда проекта", myRole = "owner", memberCount = 3, preview = "Обсудим на встрече", unreadCount = 2, lastMessageAt = now)
        private val ownMessage = ChatMessage("message-own", group.id, "self", "Вы", "req-own", "Спасибо! Посмотрю перед встречей.", 2, 1, now, now)
        private val peerMessage = ChatMessage("message-peer", group.id, peer.id, peer.displayName, "req-peer", "Доброе утро! Подготовила материалы.", 1, 1, now, now)
        private val file = ChatAttachment("file", group.id, "Материалы.pdf", "application/pdf", 24500, "attached", messageId = ownMessage.id)
        private val folder = ChatFolder("folder", "Команда", 0, 1, 0, 1)
        private val ready = MessagingUiState(selfId = "self", conversations = MessagingLoad(listOf(group, ChatConversation("direct", "direct", peer))), selectedId = group.id,
            detail = MessagingLoad(group), messages = MessagingLoad(listOf(ownMessage, peerMessage)), folders = MessagingLoad(emptyList()), realtimeLabel = "Обновления подключены")
    }
}
