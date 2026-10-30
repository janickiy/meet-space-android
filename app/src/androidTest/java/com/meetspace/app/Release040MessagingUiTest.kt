package com.meetspace.app

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.meetspace.app.ui.*
import com.meetspace.core.designsystem.MeetSpaceTheme
import com.meetspace.core.model.*
import java.time.Instant
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class Release040MessagingUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun failedChatMessageStaysInHistoryWhileComposerAndRetryDiscardWorkOnPhoneAndTablet() {
        var width by mutableStateOf(390)
        var state by mutableStateOf(ready.copy(localMessages = listOf(local), draft = "Следующее сообщение"))
        var retried: String? = null
        var discarded: String? = null
        var sent = 0
        compose.setContent { Frame(width) {
            MessagingWorkspace(state, MessagingActions(retryMessage = { retried = it }, discardMessage = { request ->
                discarded = request; state = state.copy(localMessages = state.localMessages.filterNot { it.clientRequestId == request })
            }, send = { sent++ }))
        } }
        for (size in listOf(390, 1000)) {
            compose.runOnIdle { width = size }
            compose.onNodeWithTag("local-message-local-request").assertIsDisplayed()
            compose.onNodeWithText("Сообщение осталось на устройстве").assertIsDisplayed()
            compose.onNodeWithTag("message-failed-local-request").assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithTag("message-draft").assertIsDisplayed().assertIsEnabled().assert(hasSetTextAction())
            compose.onNodeWithTag("message-send").assertIsEnabled()
            compose.onNodeWithTag("messaging-notice").assertDoesNotExist()
            compose.onNodeWithTag("message-pending-send").assertDoesNotExist()
        }
        assertRedFailureIcon("local-request")
        compose.onNodeWithTag("message-failed-local-request").performClick()
        compose.onNodeWithTag("message-retry-local-request").performClick()
        compose.runOnIdle { assertEquals("local-request", retried) }
        compose.onNodeWithTag("message-send").performClick()
        compose.runOnIdle { assertEquals(1, sent) }
        compose.onNodeWithTag("message-failed-local-request").performClick()
        compose.onNodeWithTag("message-discard-local-request").performClick()
        compose.runOnIdle { assertEquals("local-request", discarded) }
        compose.onNodeWithTag("local-message-local-request").assertDoesNotExist()
    }

    @Test fun failedMeetingChatAndCommentHaveInlineActionsWithoutAStatusAnnouncement() {
        var width by mutableStateOf(390)
        var panel by mutableStateOf(CallPanel.COMMENTS)
        var entries by mutableStateOf(listOf(CallTextEntry("local-call", "Вы", "Неотправленный комментарий", "10:00", own = true,
            localRequestId = "call-request", deliveryFailed = true)))
        var retried: String? = null
        var discarded: String? = null
        compose.setContent { Frame(width, dark = true) {
            CallTextPanel(panel, "Чат встречи", CallTextPanelState(entries = entries, draft = "Следующий комментарий", canSend = true), null,
                CallPanelActions(retryMessage = { retried = it }, discardMessage = { discarded = it; entries = emptyList() }), {})
        } }
        for ((size, currentPanel) in listOf(390 to CallPanel.COMMENTS, 1000 to CallPanel.CHAT)) {
            compose.runOnIdle { width = size; panel = currentPanel }
            val slug = if (currentPanel == CallPanel.COMMENTS) "comments" else "chat"
            compose.onNodeWithText("Неотправленный комментарий").assertIsDisplayed()
            compose.onNodeWithTag("message-failed-call-request").assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithTag("call-$slug-draft").assertIsDisplayed().assertIsEnabled().assert(hasSetTextAction())
            compose.onNodeWithTag("call-$slug-send").assertIsEnabled()
            compose.onNodeWithTag("call-$slug-status").assertDoesNotExist()
            compose.onNodeWithTag("call-$slug-error").assertDoesNotExist()
        }
        assertRedFailureIcon("call-request")
        compose.onNodeWithTag("message-failed-call-request").performClick()
        compose.onNodeWithTag("message-retry-call-request").performClick()
        compose.runOnIdle { assertEquals("call-request", retried) }
        compose.onNodeWithTag("message-failed-call-request").performClick()
        compose.onNodeWithTag("message-discard-call-request").performClick()
        compose.runOnIdle { assertEquals("call-request", discarded) }
        compose.onNodeWithText("Неотправленный комментарий").assertDoesNotExist()
    }

    @Test fun groupOwnerCanAddAndReplaceAvatarFromBothLayouts() {
        var width by mutableStateOf(390)
        var state by mutableStateOf(ready)
        var picked = 0
        var removed = 0
        compose.setContent { Frame(width) {
            MessagingWorkspace(state, MessagingActions(pickGroupAvatar = { picked++ }, deleteGroupAvatar = { removed++ }))
        } }
        compose.onNodeWithTag("conversation-options").performClick()
        compose.onNodeWithTag("group-members").performClick()
        compose.onNodeWithTag("group-avatar-pick").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("Добавить фото").assertExists()
        compose.onNodeWithTag("group-avatar-delete").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, picked)
            width = 1000
            state = state.copy(detail = MessagingLoad(group.copy(avatarVersion = "version-1")))
        }
        compose.onNodeWithText("Изменить фото").assertExists()
        compose.onNodeWithTag("group-avatar-pick").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("group-avatar-delete").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("Удалить фото?").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, removed) }
        compose.onNodeWithTag("group-avatar-remove-cancel").performClick()
        compose.runOnIdle { assertEquals(0, removed) }
        compose.onNodeWithTag("group-avatar-delete").performScrollTo().performClick()
        compose.onNodeWithTag("group-avatar-remove-confirm").performClick()
        compose.runOnIdle { assertEquals(2, picked); assertEquals(1, removed) }
    }

    @Test fun ordinaryGroupMemberCannotChangeTheAvatar() {
        compose.setContent { Frame(390) {
            MessagingWorkspace(ready.copy(detail = MessagingLoad(group.copy(myRole = "member", avatarVersion = "version-1"))), MessagingActions())
        } }
        compose.onNodeWithTag("conversation-options").performClick()
        compose.onNodeWithTag("group-members").performClick()
        compose.onNodeWithTag("group-avatar-pick").assertDoesNotExist()
        compose.onNodeWithTag("group-avatar-delete").assertDoesNotExist()
        compose.onNodeWithTag("group-avatar-permission").assertExists()
        compose.onNodeWithTag("group-save").assertDoesNotExist()
    }

    private fun assertRedFailureIcon(request: String) {
        val image = compose.onNodeWithTag("message-failed-$request").captureToImage().asAndroidBitmap()
        val pixels = IntArray(image.width * image.height)
        image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
        assertTrue("The delivery failure marker must be visibly red", pixels.any { Color.red(it) > 160 && Color.green(it) < 120 && Color.blue(it) < 130 })
    }

    @Composable private fun Frame(width: Int, dark: Boolean = false, content: @Composable () -> Unit) {
        DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(width.dp, 780.dp))) {
            MeetSpaceTheme(if (dark) ThemeMode.DARK else ThemeMode.LIGHT) { Box(Modifier.fillMaxSize()) { content() } }
        }
    }

    companion object {
        private val at = Instant.parse("2026-10-30T06:00:00Z")
        private val group = ChatConversation("group", "group", name = "Команда", myRole = "owner", memberCount = 1)
        private val local = LocalOutgoingMessage("local-request", OutgoingMessageKind.CONVERSATION, "group", "Сообщение осталось на устройстве", "self", "Вы", at, failed = true, sending = false)
        private val ready = MessagingUiState(selfId = "self", conversations = MessagingLoad(listOf(group)), selectedId = "group",
            detail = MessagingLoad(group), messages = MessagingLoad(emptyList()), members = MessagingLoad(emptyList()), folders = MessagingLoad(emptyList()), realtimeLabel = "Обновления подключены")
    }
}
