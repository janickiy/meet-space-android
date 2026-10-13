package com.meetspace.app

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.view.WindowCompat
import com.meetspace.app.ui.PersonalCallActions
import com.meetspace.app.ui.PersonalCallUi
import com.meetspace.app.ui.PersonalCallUiPhase
import com.meetspace.app.ui.PersonalCallUiState
import com.meetspace.core.designsystem.MeetSpaceTheme
import com.meetspace.core.media.CallParticipant
import com.meetspace.core.media.CallPhase
import com.meetspace.core.media.CallState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** All names and signaling below are androidTest fixtures; no devices, repositories or network. */
class PersonalCallUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun outgoingDoesNotInventAnAnswerAndCannotShareBeforeAcceptance() {
        var endings = 0
        render(call, PersonalCallActions(end = { endings++ }))
        compose.onNodeWithTag("personal-call-status").assertTextEquals("Вы звоните…")
        compose.onNodeWithTag("personal-call-share").assertIsNotEnabled()
        compose.onNodeWithTag("personal-call-accept").assertDoesNotExist()
        compose.onNodeWithTag("personal-call-remote-camera-off").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(10_000)
        compose.onNodeWithTag("personal-call-status").assertTextEquals("Вы звоните…")
        compose.onNodeWithTag("personal-call-elapsed").assertDoesNotExist()
        compose.onNodeWithTag("personal-call-end").performClick()
        compose.runOnIdle { assertEquals(1, endings) }
    }

    @Test fun incomingOffersExplicitAcceptOrDeclineAndDoesNotAssumeMediaCapture() {
        var accepted = 0
        var declined = 0
        render(call.copy(phase = PersonalCallUiPhase.INCOMING), PersonalCallActions(accept = { accepted++ }, decline = { declined++ }))
        compose.onNodeWithTag("personal-call-status").assertTextEquals("Вам звонят…")
        compose.onNodeWithTag("personal-call-microphone").assertIsOff()
        compose.onNodeWithTag("personal-call-camera").assertIsOff()
        compose.onNodeWithTag("personal-call-share").assertDoesNotExist()
        compose.onNodeWithTag("personal-call-end").assertDoesNotExist()
        compose.onNodeWithTag("personal-call-decline").performClick()
        compose.onNodeWithTag("personal-call-accept").performClick()
        compose.runOnIdle { assertEquals(1, accepted); assertEquals(1, declined) }
    }

    @Test fun mediaButtonsRequestChangesAndWaitForActualHostState() {
        var state by mutableStateOf(call)
        var microphone = 0
        var camera = 0
        compose.setContent {
            MeetSpaceTheme { PersonalCallUi(state, PersonalCallActions(microphone = { microphone++ }, camera = { camera++ })) }
        }
        compose.onNodeWithTag("personal-call-microphone").performClick().assertIsOff()
        compose.onNodeWithTag("personal-call-camera").performClick().assertIsOff()
        compose.runOnIdle {
            assertEquals(1, microphone); assertEquals(1, camera)
            state = state.copy(media = state.media.copy(microphone = true, camera = true))
        }
        compose.onNodeWithTag("personal-call-microphone").assertIsOn()
        compose.onNodeWithTag("personal-call-camera").assertIsOn()
    }

    @Test fun connectedUsesActualDurationAndOnlyKnownRemoteCameraState() {
        var state by mutableStateOf(connected.copy(media = connected.media.copy(participants = emptyList())))
        compose.setContent { MeetSpaceTheme { PersonalCallUi(state, PersonalCallActions()) } }
        compose.onNodeWithTag("personal-call-status").assertTextEquals("На связи")
        compose.onNodeWithTag("personal-call-elapsed").assertTextEquals("02:05")
        compose.onNodeWithTag("personal-call-share").assertIsEnabled()
        compose.onNodeWithTag("personal-call-remote-camera-off").assertDoesNotExist()
        compose.runOnIdle { state = connected }
        compose.onNodeWithTag("personal-call-remote-camera-off").assertTextEquals("Камера собеседника выключена")
    }

    @Test fun acceptedOnAnotherDeviceCannotControlOrEndItsMedia() {
        render(connected.copy(controlsEnabled = false, statusText = "Звонок принят на другом устройстве"))
        compose.onNodeWithTag("personal-call-status").assertTextEquals("Звонок принят на другом устройстве")
        listOf("microphone", "camera", "share", "end").forEach { compose.onNodeWithTag("personal-call-$it").assertIsNotEnabled() }
        compose.onNodeWithTag("personal-call-minimize").assertIsEnabled()
    }

    @Test fun busyIncomingPreventsDuplicateCommands() {
        render(call.copy(phase = PersonalCallUiPhase.INCOMING, busy = true))
        listOf("microphone", "camera", "accept", "decline").forEach { compose.onNodeWithTag("personal-call-$it").assertIsNotEnabled() }
    }

    @Test fun reconnectingDisablesUnavailableDeviceChangesButKeepsEndingAvailable() {
        render(connected.copy(phase = PersonalCallUiPhase.RECONNECTING, media = connected.media.copy(phase = CallPhase.RECONNECTING)))
        listOf("microphone", "camera", "share").forEach { compose.onNodeWithTag("personal-call-$it").assertIsNotEnabled() }
        compose.onNodeWithTag("personal-call-end").assertIsEnabled()
        compose.onNodeWithTag("personal-call-status").assertTextEquals("Восстанавливаем связь…")
    }

    @Test fun failedMediaStillAllowsEndingTheDurableServerCall() {
        var endings = 0
        render(connected.copy(phase = PersonalCallUiPhase.FAILED, media = connected.media.copy(phase = CallPhase.FAILED)),
            PersonalCallActions(end = { endings++ }))
        listOf("microphone", "camera", "share").forEach { compose.onNodeWithTag("personal-call-$it").assertIsNotEnabled() }
        compose.onNodeWithTag("personal-call-end").assertIsEnabled().performClick()
        compose.onNodeWithTag("personal-call-return-chat").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, endings) }
    }

    @Test fun terminalShowsAuthoritativeResultAndReturnsToExistingThread() {
        var minimized = 0
        render(call.copy(phase = PersonalCallUiPhase.ENDED, statusText = "Собеседник отклонил вызов"),
            PersonalCallActions(minimize = { minimized++ }))
        compose.onNodeWithTag("personal-call-status").assertTextEquals("Собеседник отклонил вызов")
        compose.onNodeWithTag("personal-call-microphone").assertDoesNotExist()
        compose.onNodeWithTag("personal-call-accept").assertDoesNotExist()
        compose.onNodeWithTag("personal-call-return-chat").performClick()
        compose.runOnIdle { assertEquals(1, minimized) }
    }

    @Test fun narrowWindowWithLargeFontsRetainsReadableControlsAndCallerName() {
        compose.setContent {
            MeetSpaceTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 1.6f)) {
                    Box(Modifier.requiredSize(320.dp, 640.dp)) { PersonalCallUi(call, PersonalCallActions()) }
                }
            }
        }
        listOf("microphone", "camera", "share", "end").forEach { compose.onNodeWithTag("personal-call-$it").assertIsDisplayed() }
        assertReadableTextAtActualBounds(compose.onNodeWithTag("personal-call-peer", useUnmergedTree = true), "Максим Волков")
        listOf("Без звука", "Камера", "Экран", "Завершить").forEach { label ->
            assertReadableTextAtActualBounds(compose.onNodeWithText(label, useUnmergedTree = true), label)
        }
    }

    @Test fun capturePersonalCallDesign() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Opt-in native design capture", arguments.getString("designScreenshots") == "true")
        val requested = arguments.getString("designScreen", "personal-outgoing")
        val fixture = when (requested) {
            "personal-outgoing" -> call
            "personal-incoming" -> call.copy(phase = PersonalCallUiPhase.INCOMING)
            "personal-connected" -> connected
            else -> { assumeTrue("Selected personal call screen", false); return }
        }
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
        compose.setContent {
            MeetSpaceTheme {
                SideEffect {
                    WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView).apply {
                        isAppearanceLightStatusBars = false
                        isAppearanceLightNavigationBars = false
                    }
                }
                PersonalCallUi(fixture, PersonalCallActions())
            }
        }
        compose.onNodeWithTag("personal-call-screen").assertIsDisplayed()
        compose.waitForIdle()
        val config = instrumentation.targetContext.resources.configuration
        val directory = checkNotNull(instrumentation.targetContext.getExternalFilesDir("screenshots"))
        check(directory.isDirectory || directory.mkdirs())
        val target = File(directory, "$requested-${config.screenWidthDp}x${config.screenHeightDp}-font${config.fontScale}.png")
        target.outputStream().use { stream ->
            check(compose.onNodeWithTag("personal-call-screen").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
    }

    private fun render(state: PersonalCallUiState, actions: PersonalCallActions = PersonalCallActions()) {
        compose.setContent { MeetSpaceTheme { PersonalCallUi(state, actions) } }
    }

    companion object {
        private val call = PersonalCallUiState("android-test-call", "Максим Волков", "Анна Смирнова", PersonalCallUiPhase.OUTGOING)
        private val connected = call.copy(phase = PersonalCallUiPhase.CONNECTED, elapsedSeconds = 125L,
            media = CallState(phase = CallPhase.CONNECTED, participantId = "android-test-self", microphone = true,
                participants = listOf(CallParticipant("android-test-self", call.selfName, true, true, false),
                    CallParticipant("android-test-peer", call.peerName, true, true, false))))
    }
}
