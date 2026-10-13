package com.meetspace.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.meetspace.app.ui.MessagingActions
import com.meetspace.app.ui.MessagingLoad
import com.meetspace.app.ui.MessagingUiState
import com.meetspace.app.ui.MessagingWorkspace
import com.meetspace.core.designsystem.MeetSpaceTheme
import com.meetspace.core.model.ChatConversation
import com.meetspace.core.model.ChatMessage
import com.meetspace.core.model.ChatPeer
import com.meetspace.core.model.DirectCallHistory
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

/** Existing personal-thread entry and history, using synthetic androidTest data only. */
class PersonalCallEntryTest {
    @get:Rule val compose = createComposeRule()

    @Test fun directHeaderRequestsTheExactCurrentPeerAfterThreadChanges() {
        var state by mutableStateOf(thread)
        var requested: ChatPeer? = null
        compose.setContent {
            MeetSpaceTheme {
                Box(Modifier.width(390.dp).fillMaxHeight()) {
                    MessagingWorkspace(state, MessagingActions(), onStartCall = state.detail.data?.peer?.let { peer -> { requested = peer } })
                }
            }
        }
        compose.onNodeWithTag("conversation-call").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(peer, requested)
            val next = direct.copy(id = "test-other-thread", peer = ChatPeer("test-other-peer", "Другой собеседник"))
            state = thread.copy(selectedId = next.id, detail = MessagingLoad(next), conversations = MessagingLoad(listOf(next)))
        }
        compose.onNodeWithTag("conversation-call").performClick()
        compose.runOnIdle { assertEquals(state.detail.data?.peer, requested) }
    }

    @Test fun groupHeaderAndBannerNeverExposePersonalCallEvenIfCallbacksWereSupplied() {
        val group = direct.copy(type = "group", peer = null, name = "Тестовая группа", myRole = "owner", memberCount = 3)
        render(thread.copy(detail = MessagingLoad(group)), onStartCall = {}, banner = "Вы звоните…")
        compose.onNodeWithTag("conversation-call").assertDoesNotExist()
        compose.onNodeWithTag("personal-call-chat-banner").assertDoesNotExist()
    }

    @Test fun directThreadWithoutCallCapabilityDoesNotShowAnInertCallButton() {
        render(thread)
        compose.onNodeWithTag("conversation-call").assertDoesNotExist()
    }

    @Test fun busyThreadPreventsAnotherCallRequest() {
        render(thread.copy(busy = true), onStartCall = {})
        compose.onNodeWithTag("conversation-call").assertIsNotEnabled()
    }

    @Test fun minimizedCallBannerReturnsToTheSameCallWithoutInventingStateChanges() {
        var resumed = 0
        render(thread, onStartCall = {}, banner = "02:05", resume = { resumed++ })
        compose.onNodeWithTag("personal-call-chat-banner").assertIsDisplayed()
            .assertTextContains("02:05").performClick()
        compose.runOnIdle { assertEquals(1, resumed) }
        compose.onNodeWithTag("personal-call-chat-banner").assertIsDisplayed()
        compose.onNodeWithTag("message-draft").assertIsDisplayed()
    }

    @Test fun callHistoryShowsDirectionAndDurationAndHasNoMessageMutationMenu() {
        val now = Instant.parse("2026-10-12T12:00:00Z")
        val history = DirectCallHistory("test-history-call", thread.selfId, peer.id, "ended", now,
            acceptedAt = now.plusSeconds(3), endedAt = now.plusSeconds(128), durationSec = 125)
        val message = ChatMessage("test-call-message", direct.id, thread.selfId, "Тестовый пользователь", "",
            "", 1, 1, now, now, kind = "call", call = history)
        render(thread.copy(messages = MessagingLoad(listOf(message))), onStartCall = {})
        val row = compose.onNodeWithTag("call-history-${message.id}")
        row.assertIsDisplayed().assert(hasAnyDescendant(hasText("Исходящий звонок")))
            .assert(hasAnyDescendant(hasText("Завершён · 2:05")))
        assertFalse(row.fetchSemanticsNode().config.contains(SemanticsActions.OnClick))
        row.assert(!hasAnyDescendant(hasClickAction()))
        listOf("menu", "reply", "edit", "delete").forEach {
            compose.onNodeWithTag("message-$it-${message.id}").assertDoesNotExist()
        }
    }

    @Test fun missedHistoryUsesIncomingDirectionRelativeToCurrentAccount() {
        val now = Instant.parse("2026-10-12T12:00:00Z")
        val history = DirectCallHistory("test-missed-call", peer.id, thread.selfId, "missed", now)
        val message = ChatMessage("test-missed-message", direct.id, peer.id, peer.displayName, "", "", 1, 1, now, now,
            kind = "call", call = history)
        render(thread.copy(messages = MessagingLoad(listOf(message))))
        compose.onNodeWithTag("call-history-${message.id}").assertIsDisplayed()
            .assert(hasAnyDescendant(hasText("Входящий звонок")))
            .assert(hasAnyDescendant(hasText("Пропущенный вызов")))
        compose.onNodeWithTag("message-menu-${message.id}").assertDoesNotExist()
    }

    private fun render(state: MessagingUiState, onStartCall: (() -> Unit)? = null, banner: String? = null, resume: () -> Unit = {}) {
        compose.setContent {
            MeetSpaceTheme {
                Box(Modifier.width(390.dp).fillMaxHeight()) {
                    MessagingWorkspace(state, MessagingActions(), onStartCall = onStartCall, personalCallLabel = banner, onResumeCall = resume)
                }
            }
        }
    }

    companion object {
        private val peer = ChatPeer("test-selected-peer", "Максим Волков")
        private val direct = ChatConversation("test-personal-thread", "direct", peer)
        private val thread = MessagingUiState(selfId = "test-current-account", conversations = MessagingLoad(listOf(direct)),
            selectedId = direct.id, detail = MessagingLoad(direct), messages = MessagingLoad(emptyList()))
    }
}
