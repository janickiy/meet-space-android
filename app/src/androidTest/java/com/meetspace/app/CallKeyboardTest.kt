package com.meetspace.app

import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.meetspace.app.ui.CallPanelActions
import com.meetspace.app.ui.CallScreen
import com.meetspace.app.ui.CallTextEntry
import com.meetspace.app.ui.CallTextPanelState
import com.meetspace.app.ui.CallUiDetails
import com.meetspace.core.designsystem.MeetSpaceTheme
import com.meetspace.core.media.CallPhase
import com.meetspace.core.media.CallState
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Runs on a real compact viewport with a software IME, without ForcedSize or mocked insets. */
class CallKeyboardTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun chatKeepsFocusAndCanSendAfterTheSoftwareKeyboardResizesTheCall() {
        var discussion by mutableStateOf(CallTextPanelState(canSend = true, entries = listOf(
            CallTextEntry("server-message", "Участник", "Обсудим сценарий входа.", "10:08"),
        )))
        var submitted: String? = null
        val imeHeight = AtomicInteger(0)
        compose.runOnUiThread {
            compose.activity.enableEdgeToEdge()
            compose.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        compose.setContent {
            val measuredIme = WindowInsets.ime.getBottom(LocalDensity.current)
            SideEffect { imeHeight.set(measuredIme) }
            MeetSpaceTheme {
                CallScreen(CallState(CallPhase.CONNECTED, conferenceId = "keyboard-meeting", title = "Обсуждение интерфейса"),
                    details = CallUiDetails(chat = discussion),
                    panelActions = CallPanelActions(
                        chatDraft = { discussion = discussion.copy(draft = it) },
                        sendChat = { submitted = discussion.draft; discussion = discussion.copy(draft = "") },
                    ))
            }
        }
        compose.onNodeWithTag("call-chat").performClick()
        compose.onNodeWithTag("call-chat-panel").assertIsDisplayed()
        val initialBounds = compose.onNodeWithTag("call-chat-panel").getUnclippedBoundsInRoot()
        val initialHeight = initialBounds.bottom - initialBounds.top
        compose.onNodeWithTag("call-chat-draft").performClick()
        compose.waitUntil(timeoutMillis = 10_000) { imeHeight.get() > 0 }
        // Let the real IME animation settle; losing focus during resize can hide it again.
        SystemClock.sleep(600)
        compose.waitForIdle()
        assertTrue("Software keyboard must remain visible after the panel resizes", imeHeight.get() > 0)
        compose.onNodeWithTag("call-chat-draft").assertIsFocused().assertIsDisplayed()
        val resizedBounds = compose.onNodeWithTag("call-chat-panel").getUnclippedBoundsInRoot()
        val resizedHeight = resizedBounds.bottom - resizedBounds.top
        assertTrue("The actual IME must reduce the call body's available height", resizedHeight < initialHeight)
        compose.onNodeWithTag("call-chat-draft").performTextInput("Важное уточнение после открытия клавиатуры")
        compose.onNodeWithTag("call-chat-draft").assertTextContains("Важное уточнение после открытия клавиатуры")
        compose.onNodeWithTag("call-chat-send").assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals("Важное уточнение после открытия клавиатуры", submitted) }
    }
}
