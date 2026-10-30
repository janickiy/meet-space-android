package com.meetspace.app

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.meetspace.app.ui.*
import com.meetspace.core.designsystem.MeetSpaceTheme
import com.meetspace.core.media.*
import com.meetspace.core.model.ThemeMode
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SpeakingIndicatorUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun meetingHighlightsOnlyTalkingMicrophonesAndClearsWhenMutedOrReconnecting() {
        var state by mutableStateOf(call)
        compose.setContent { MeetSpaceTheme(ThemeMode.LIGHT) { CallScreen(state) } }
        speaking("call-mic", false); speaking("call-participant-self", false); speaking("call-participant-peer", false)
        compose.runOnIdle { state = state.copy(activeSpeakerIds = setOf("peer")) }
        speaking("call-mic", false); speaking("call-participant-self", false); speaking("call-participant-peer", true)
        tileColor("call-participant-peer", SpeakingBackground)
        compose.runOnIdle { state = state.copy(activeSpeakerIds = setOf("self", "peer", "muted")) }
        speaking("call-mic", true); speaking("call-participant-self", true); speaking("call-participant-peer", true)
        speaking("call-participant-muted", false)
        buttonColor("call-mic", SpeakingGreen, .2f)
        tileColor("call-participant-self", SpeakingBackground)
        capture("meeting-speaking", "call-screen")
        compose.runOnIdle { state = state.copy(microphone = false) }
        speaking("call-mic", false); speaking("call-participant-self", false); speaking("call-participant-peer", true)
        compose.onNodeWithTag("call-mic").assertIsOff()
        compose.runOnIdle { state = state.copy(participants = state.participants.map { it.copy(microphone = false) }) }
        speaking("call-participant-peer", false)
        compose.runOnIdle { state = call.copy(phase = CallPhase.RECONNECTING, activeSpeakerIds = setOf("self", "peer")) }
        speaking("call-mic", false); speaking("call-participant-self", false); speaking("call-participant-peer", false)
        compose.onNodeWithTag("call-mic").assertIsNotEnabled()
    }

    @Test fun personalCallSeparatesLocalAndRemoteSpeechAndResetsItsGreenBackground() {
        var state by mutableStateOf(PersonalCallUiState("fixture", "Участник стенда", "Вы", PersonalCallUiPhase.CONNECTED,
            media = call.copy(participants = call.participants.filter { it.id != "muted" })))
        compose.setContent { MeetSpaceTheme(ThemeMode.DARK) { PersonalCallUi(state, PersonalCallActions()) } }
        speaking("personal-call-stage", false); speaking("personal-call-self", false)
        compose.runOnIdle { state = state.copy(media = state.media.copy(activeSpeakerIds = setOf("self", "peer"))) }
        speaking("personal-call-stage", true); speaking("personal-call-self", true)
        speaking("personal-call-microphone", true)
        buttonColor("personal-call-microphone", SpeakingGreen, .1f)
        capture("personal-speaking", "personal-call-screen")
        compose.runOnIdle { state = state.copy(media = state.media.copy(activeSpeakerIds = setOf("peer"))) }
        speaking("personal-call-stage", true); speaking("personal-call-self", false)
        compose.onNodeWithTag("personal-call-microphone").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Включён"))
        compose.runOnIdle { state = state.copy(media = state.media.copy(activeSpeakerIds = emptySet())) }
        speaking("personal-call-stage", false); speaking("personal-call-self", false)
        compose.runOnIdle { state = state.copy(phase = PersonalCallUiPhase.RECONNECTING,
            media = state.media.copy(phase = CallPhase.RECONNECTING, activeSpeakerIds = setOf("self", "peer"))) }
        speaking("personal-call-stage", false); speaking("personal-call-self", false)
        compose.onNodeWithTag("personal-call-microphone").assertIsNotEnabled()
    }

    private fun speaking(tag: String, value: Boolean) = compose.onNodeWithTag(tag)
        .assertIsDisplayed().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, if (value) "Говорит" else "Не говорит"))
    private fun buttonColor(tag: String, expected: Color, y: Float) {
        val pixels = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
        colorEquals(expected, pixels[pixels.width / 2, (pixels.height * y).toInt()])
    }
    private fun tileColor(tag: String, expected: Color) {
        val pixels = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
        colorEquals(expected, pixels[(pixels.width * .15f).toInt(), (pixels.height * .15f).toInt()])
    }
    private fun colorEquals(expected: Color, actual: Color) {
        assertEquals(expected.red, actual.red, .01f); assertEquals(expected.green, actual.green, .01f); assertEquals(expected.blue, actual.blue, .01f)
    }
    private fun capture(prefix: String, tag: String) {
        val config = compose.activity.resources.configuration
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "$prefix-${config.screenWidthDp}x${config.screenHeightDp}.png")
            .outputStream().use { assertTrue(compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
    companion object {
        private val call = CallState(phase = CallPhase.CONNECTED, conferenceId = "fixture", participantId = "self", title = "Проверка речи", microphone = true,
            participants = listOf(CallParticipant("self", "Вы", true, true, false), CallParticipant("peer", "Участник стенда", true, true, false),
                CallParticipant("muted", "Микрофон выключен", true, false, false)))
    }
}
