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
        compose.runOnIdle { state = state.copy(activeSpeakerIds = setOf("self", "peer", "muted"), microphoneLevels = mapOf("self" to 1f)) }
        speaking("call-mic", true); speaking("call-participant-self", true); speaking("call-participant-peer", true)
        speaking("call-participant-muted", false)
        buttonColor("call-mic", Color(0xFF303C4F), .2f)
        assertTrue(meter("call-mic-icon").count > 0)
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
        compose.runOnIdle { state = state.copy(media = state.media.copy(activeSpeakerIds = setOf("self", "peer"), microphoneLevels = mapOf("self" to 1f))) }
        speaking("personal-call-stage", true); speaking("personal-call-self", true)
        speaking("personal-call-microphone", true)
        buttonColor("personal-call-microphone", Color(0xFFD9E5FA), .1f)
        assertTrue(meter("personal-call-microphone-icon").count > 0)
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

    @Test fun meetingMicrophoneFillsBottomUpWithVolumeAndKeepsItsButtonBackgroundFixed() {
        var state by mutableStateOf(call.copy(activeSpeakerIds = setOf("self", "peer")))
        compose.setContent { MeetSpaceTheme(ThemeMode.LIGHT) { CallScreen(state) } }
        buttonColor("call-mic", Color(0xFF303C4F), .2f)
        assertEquals(0, meter("call-mic-icon").count)
        compose.runOnIdle { state = state.copy(microphoneLevels = mapOf("self" to .025f)) }
        buttonColor("call-mic", Color(0xFF303C4F), .2f)
        val quiet = meter("call-mic-icon")
        assertTrue("Quiet speech fills the microphone", quiet.count > 0)
        capture("meeting-fill-quiet", "call-toolbar")
        compose.runOnIdle { state = state.copy(microphoneLevels = mapOf("self" to .25f)) }
        buttonColor("call-mic", Color(0xFF303C4F), .2f)
        val loud = meter("call-mic-icon")
        assertTrue("Louder voice increases fill height", loud.count > quiet.count * 2 && loud.top < quiet.top)
        assertEquals("The fill grows upwards from the same bottom", quiet.bottom, loud.bottom)
        capture("meeting-fill-loud", "call-toolbar")
        compose.runOnIdle { state = state.copy(microphoneLevels = emptyMap()) }
        buttonColor("call-mic", Color(0xFF303C4F), .2f)
        assertEquals(0, meter("call-mic-icon").count)
        // Tiles retain their existing speech hold, independently of the mic's level fill.
        speaking("call-participant-self", true); speaking("call-participant-peer", true)
        compose.runOnIdle { state = state.copy(microphoneLevels = mapOf("self" to 1f)) }
        assertTrue(meter("call-mic-icon").count > 0)
        compose.runOnIdle { state = state.copy(microphone = false) }
        buttonColor("call-mic", Color(0xFFE2E8F0), .2f)
        compose.onNodeWithTag("call-mic-icon", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { state = state.copy(microphone = true, microphoneLevels = emptyMap()) }
        assertEquals(0, meter("call-mic-icon").count)
        compose.runOnIdle { state = call.copy(phase = CallPhase.RECONNECTING,
            activeSpeakerIds = setOf("self"), microphoneLevels = mapOf("self" to 1f)) }
        buttonColor("call-mic", Color(0xFF303C4F), .2f)
        assertEquals(0, meter("call-mic-icon").count)
    }

    @Test fun personalMicrophoneFillsWithOwnVolumeOnlyWithoutChangingTheButtonBackground() {
        var state by mutableStateOf(PersonalCallUiState("fixture", "Участник стенда", "Вы", PersonalCallUiPhase.CONNECTED,
            media = call.copy(activeSpeakerIds = setOf("self", "peer"), microphoneLevels = mapOf("peer" to 1f))))
        compose.setContent { MeetSpaceTheme(ThemeMode.DARK) { PersonalCallUi(state, PersonalCallActions()) } }
        buttonColor("personal-call-microphone", Color(0xFFD9E5FA), .1f)
        assertEquals(0, meter("personal-call-microphone-icon").count)
        compose.runOnIdle { state = state.copy(media = state.media.copy(microphoneLevels = mapOf("self" to .025f))) }
        buttonColor("personal-call-microphone", Color(0xFFD9E5FA), .1f)
        val quiet = meter("personal-call-microphone-icon")
        assertTrue(quiet.count > 0)
        capture("personal-fill-quiet", "personal-call-screen")
        compose.runOnIdle { state = state.copy(media = state.media.copy(microphoneLevels = mapOf("self" to .25f))) }
        buttonColor("personal-call-microphone", Color(0xFFD9E5FA), .1f)
        val loud = meter("personal-call-microphone-icon")
        assertTrue(loud.count > quiet.count * 2 && loud.top < quiet.top)
        assertEquals(quiet.bottom, loud.bottom)
        capture("personal-fill-loud", "personal-call-screen")
        compose.runOnIdle { state = state.copy(media = state.media.copy(microphoneLevels = emptyMap())) }
        buttonColor("personal-call-microphone", Color(0xFFD9E5FA), .1f)
        assertEquals(0, meter("personal-call-microphone-icon").count)
        speaking("personal-call-self", true); speaking("personal-call-stage", true)
        compose.runOnIdle { state = state.copy(media = state.media.copy(microphone = false, microphoneLevels = mapOf("self" to 1f))) }
        buttonColor("personal-call-microphone", Color(0xFF2B3951), .1f)
        compose.onNodeWithTag("personal-call-microphone-icon", useUnmergedTree = true).assertDoesNotExist()
    }

    private data class Meter(val count: Int, val top: Int, val bottom: Int)
    private fun meter(tag: String): Meter {
        val pixels = compose.onNodeWithTag(tag, useUnmergedTree = true).captureToImage().toPixelMap()
        var count = 0; var top = pixels.height; var bottom = -1
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val c = pixels[x, y]
            if (kotlin.math.abs(c.red - SpeakingGreen.red) < .02f && kotlin.math.abs(c.green - SpeakingGreen.green) < .02f && kotlin.math.abs(c.blue - SpeakingGreen.blue) < .02f) {
                count++; top = minOf(top, y); bottom = maxOf(bottom, y)
            }
        }
        return Meter(count, top, bottom)
    }

    private fun speaking(tag: String, value: Boolean) = compose.onNodeWithTag(tag)
        .assertIsDisplayed().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, if (value) "Говорит" else "Не говорит"))
    private fun buttonColor(tag: String, expected: Color, y: Float) {
        colorEquals(expected, buttonPixel(tag, y))
    }
    private fun buttonPixel(tag: String, y: Float): Color {
        val pixels = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
        return pixels[pixels.width / 2, (pixels.height * y).toInt()]
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
