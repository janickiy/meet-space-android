package com.meetspace.app

import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.meetspace.app.ui.*
import com.meetspace.core.designsystem.MeetSpaceTheme
import com.meetspace.core.model.ThemeMode
import com.meetspace.core.media.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalTestApi::class)
class CallUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun prejoinRequiresExplicitConnectAndDeviceActions() {
        var connects = 0; var mic = 0; var camera = 0
        compose.setContent { MeetSpaceTheme { CallScreen(CallState(CallPhase.PREJOIN, title = "Тестовая встреча", status = "Перед входом"),
            microphone = { mic++ }, camera = { camera++ }, connect = { connects++ }) } }
        compose.onNodeWithTag("call-share").assertDoesNotExist()
        compose.onNodeWithTag("call-mic").performClick(); compose.onNodeWithTag("call-camera").performClick()
        assertEquals(0, connects); assertEquals(1, mic); assertEquals(1, camera)
        compose.onNodeWithTag("call-connect").performScrollTo().assertIsDisplayed().performClick(); assertEquals(1, connects)
    }
    @Test fun blockedDevicesCannotPublishAndLeaveRemainsAvailable() {
        var clicked = 0
        compose.setContent { MeetSpaceTheme { CallScreen(CallState(CallPhase.CONNECTED, title = "Встреча", microphoneBlocked = true, cameraBlocked = true, screenBlocked = true), leave = { clicked++ }) } }
        compose.onNodeWithTag("call-mic").assertIsNotEnabled(); compose.onNodeWithTag("call-camera").assertIsNotEnabled()
        openInfoActionIfNeeded("call-share")
        compose.onNodeWithTag("call-share").assertIsNotEnabled()
        closeInfoMenuIfOpen()
        compose.onNodeWithTag("call-leave").performClick()
        assertEquals(1, clicked)
    }
    @Test fun reconnectNeverDisplaysConnectedAndDisablesCapture() {
        compose.setContent { MeetSpaceTheme { CallScreen(CallState(CallPhase.RECONNECTING, title = "Встреча", status = "Восстанавливаем связь…")) } }
        compose.onNodeWithTag("call-status").assertTextContains("Восстанавливаем связь…", substring = true)
        compose.onNodeWithTag("call-camera").assertIsNotEnabled()
        openInfoActionIfNeeded("call-share")
        compose.onNodeWithTag("call-share").assertIsNotEnabled()
        closeInfoMenuIfOpen()
        compose.onNodeWithTag("call-leave").assertIsEnabled()
    }
    @Test fun failedCallOffersRetryAndShowsActualFailure() {
        var retry = false
        compose.setContent { MeetSpaceTheme { CallScreen(CallState(CallPhase.FAILED, title = "Встреча", error = "Сервер недоступен"), connect = { retry = true }) } }
        compose.onNodeWithTag("call-error").assertTextEquals("Сервер недоступен")
        compose.onNodeWithTag("call-connect").performScrollTo().assertIsDisplayed().performClick(); assertTrue(retry)
    }
    @Test fun participantPanelContainsRealRoster() {
        compose.setContent { MeetSpaceTheme { CallScreen(CallState(CallPhase.CONNECTED, title = "Встреча",
            participants = listOf(CallParticipant("one", "Участник стенда", true, false, false)))) } }
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        UiDevice.getInstance(instrumentation).takeScreenshot(java.io.File(instrumentation.targetContext.cacheDir, "stage06-call-layout.png"))
        compose.onNodeWithTag("call-people").performClick()
        compose.onAllNodesWithText("Участник стенда").onFirst().assertExists()
        compose.onNodeWithText("Микрофон выключен").assertExists()
    }
    @Test fun prejoinUsesSideBySideTabletAndStackedPhoneWithoutAutomaticCapture() {
        var width by mutableStateOf(1000.dp)
        var destination: WorkspaceTab? = null
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(width, 900.dp))) {
                MeetSpaceTheme { CallScreen(CallState(CallPhase.PREJOIN, title = "Дизайн-ревью", status = "Перед входом"), onWorkspaceTab = { destination = it }, profileLabel = "Тестовый профиль") }
            }
        }
        compose.onNodeWithTag("call-prejoin-wide").assertExists()
        val preview = compose.onNodeWithTag("call-preview").getUnclippedBoundsInRoot()
        val join = compose.onNodeWithTag("call-connect").getUnclippedBoundsInRoot()
        assertTrue(preview.right <= join.left)
        compose.onNodeWithText("Тестовый профиль").assertExists()
        compose.onNodeWithTag("call-mic").assertIsOff()
        compose.onNodeWithTag("call-camera").assertIsOff()
        compose.onNodeWithTag("tab-calls").assertIsSelected()
        compose.onNodeWithTag("tab-profile").performClick()
        compose.runOnIdle { assertEquals(WorkspaceTab.PROFILE, destination); width = 839.dp }
        compose.onNodeWithTag("call-prejoin-compact").assertExists()
        val compactPreview = compose.onNodeWithTag("call-preview").getUnclippedBoundsInRoot()
        assertTrue(compactPreview.right - compactPreview.left <= 520.dp)
        compose.runOnIdle { width = 841.dp }
        compose.onNodeWithTag("call-prejoin-wide").assertExists()
        compose.runOnIdle { width = 360.dp }
        compose.onNodeWithTag("call-prejoin-compact").assertExists()
        compose.onNodeWithTag("call-connect").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Присоединиться").assertExists()
    }

    @Test fun hiddenSelfWithNoOtherParticipantsKeepsCallControlsAndHonestEmptyState() {
        var leaves = 0
        compose.setContent { MeetSpaceTheme { CallScreen(CallState(CallPhase.CONNECTED, title = "Встреча"),
            devicePreferences = AccountDevicePreferences(showSelf = false), leave = { leaves++ }) } }
        compose.onNodeWithTag("call-participant-self").assertDoesNotExist()
        compose.onNodeWithTag("call-no-visible-participants").assertIsDisplayed()
        compose.onNodeWithTag("call-leave").performClick()
        compose.runOnIdle { assertEquals(1, leaves) }
    }

    @Test fun hidingParticipantVideoKeepsTheirActualRosterAndAudioControls() {
        compose.setContent { MeetSpaceTheme { CallScreen(CallState(CallPhase.CONNECTED, title = "Встреча", participantId = "self",
            participants = listOf(CallParticipant("other", "Реальный участник", true, true, true))),
            devicePreferences = AccountDevicePreferences(showSelf = false, hideParticipantVideo = true)) } }
        compose.onNodeWithTag("call-participant-self").assertDoesNotExist()
        compose.onNodeWithTag("call-participant-other").assertExists()
        compose.onNodeWithText("Реальный участник").assertExists()
        openInfoActionIfNeeded("call-audio")
        compose.onNodeWithTag("call-audio").assertIsEnabled()
    }

    @Test fun permissionRequestKeepsJoinDisabledUntilResult() {
        var connects = 0
        compose.setContent { MeetSpaceTheme { CallScreen(CallState(CallPhase.PREJOIN, title = "Встреча"), joinPending = true, connect = { connects++ }) } }
        compose.onNodeWithTag("call-connect").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, connects) }
    }

    @Test fun prejoinDeviceSettingsUseExistingActionsAndActualRoutes() {
        var state by mutableStateOf(CallState(CallPhase.PREJOIN, title = "Встреча",
            routes = listOf(AudioRoute(17, "Подключённая гарнитура", true))))
        var connects = 0; var mic = 0; var camera = 0; var settings = 0
        var route: Int? = null
        compose.setContent { MeetSpaceTheme { CallScreen(state, profileLabel = "Тестовый профиль",
            microphone = { mic++; state = state.copy(microphone = !state.microphone) },
            camera = { camera++; state = state.copy(camera = !state.camera) },
            connect = { connects++ }, route = { route = it }, openSystemSettings = { settings++ }) } }
        val label = compose.onNodeWithTag("call-prejoin-name-label").getUnclippedBoundsInRoot()
        val name = compose.onNodeWithTag("call-prejoin-name").getUnclippedBoundsInRoot()
        assertTrue("The name label must sit above the input", label.bottom <= name.top)
        compose.onNodeWithTag("call-prejoin-name").assertTextContains("Тестовый профиль")
        compose.onNodeWithTag("call-devices").performScrollTo().performClick()
        compose.onNodeWithTag("call-device-mic").assertIsOff().performClick().assertIsOn()
        compose.onNodeWithTag("call-device-camera").assertIsOff().performClick().assertIsOn()
        compose.onNodeWithText("✓ Подключённая гарнитура").performClick()
        compose.onNodeWithTag("call-device-permissions").performScrollTo().performClick()
        compose.onNodeWithTag("call-device-mic").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, mic); assertEquals(1, camera); assertEquals(1, settings)
            assertEquals(17, route); assertEquals("Opening device settings must not join a call", 0, connects)
        }
    }

    @Test fun prejoinDeviceSettingsRespectPolicyAndPendingPermissionRequest() {
        var state by mutableStateOf(CallState(CallPhase.PREJOIN, title = "Встреча", microphoneBlocked = true, cameraBlocked = true))
        var pending by mutableStateOf(false)
        compose.setContent { MeetSpaceTheme { CallScreen(state, joinPending = pending) } }
        compose.onNodeWithTag("call-devices").performScrollTo().performClick()
        fun assertBlocked() {
            compose.onNodeWithTag("call-device-mic").assertIsNotEnabled()
            compose.onNodeWithTag("call-device-camera").assertIsNotEnabled()
        }
        assertBlocked()
        compose.onNodeWithText("Часть устройств отключена организатором.").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(microphoneBlocked = false, cameraBlocked = false); pending = true }
        assertBlocked()
        compose.runOnIdle { pending = false; state = state.copy(phase = CallPhase.FAILED) }
        assertBlocked()
        compose.onNodeWithTag("call-device-permissions").assertDoesNotExist()
    }

    @Test fun phoneCallLabelsStayOnOneLineAndLeaveButtonHasItsOwnWidth() {
        var width by mutableStateOf(390.dp)
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(width, 844.dp))) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1f)) {
                    MeetSpaceTheme { CallScreen(CallState(CallPhase.CONNECTED, title = "Встреча", status = "Вы в звонке")) }
                }
            }
        }
        assertSingleLineLabels()
        compose.onNodeWithTag("call-leave").assertIsDisplayed().assertWidthIsAtLeast(51.dp).assertHeightIsAtLeast(48.dp)
        val mic = compose.onNodeWithTag("call-mic").getUnclippedBoundsInRoot()
        val leave = compose.onNodeWithTag("call-leave").getUnclippedBoundsInRoot()
        assertEquals("Normal phone controls should fit in a single row", mic.top, leave.top)
        compose.runOnIdle { width = 1194.dp }
        compose.onNodeWithTag("call-leave").assertWidthIsAtLeast(68.dp).assertHeightIsAtLeast(48.dp)
    }

    @Test fun connectedCallUsesTheBlueCanvasInBothThemes() {
        var dark by mutableStateOf(false)
        compose.setContent { MeetSpaceTheme(if (dark) ThemeMode.DARK else ThemeMode.LIGHT) {
            CallScreen(CallState(CallPhase.CONNECTED, title = "Встреча"))
        } }
        fun assertCanvas() {
            val pixel = compose.onNodeWithTag("call-screen").captureToImage().toPixelMap()[1, 1]
            assertEquals(Color(0xFF171F2D), pixel)
        }
        assertCanvas()
        compose.runOnIdle { dark = true }
        assertCanvas()
    }

    @Test fun activeDeviceSemanticsAndAudioSelectionReflectActualState() {
        var chosen: Int? = null
        var refresh = 0
        compose.setContent { MeetSpaceTheme { CallScreen(CallState(CallPhase.CONNECTED, title = "Встреча", microphone = true,
            routes = listOf(AudioRoute(17, "Тестовая гарнитура", true))), route = { chosen = it }, refreshRoutes = { refresh++ }) } }
        compose.onNodeWithTag("call-mic").assertIsOn()
        compose.onNodeWithTag("call-camera").assertIsOff()
        openInfoActionIfNeeded("call-audio")
        compose.onNodeWithTag("call-audio").performClick()
        compose.onNodeWithText("✓ Тестовая гарнитура").performClick()
        compose.runOnIdle { assertEquals(17, chosen); assertEquals(1, refresh) }
    }

    @Test fun largeFontShortLandscapeKeepsCaptureAndLeaveControlsReachable() {
        var leaves = 0
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(600.dp, 320.dp))) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                    MeetSpaceTheme { CallScreen(CallState(CallPhase.CONNECTED, title = "Встреча", status = "Вы в звонке"), leave = { leaves++ }) }
                }
            }
        }
        listOf("call-mic", "call-camera", "call-people", "call-chat", "call-leave").forEach {
            compose.onNodeWithTag(it).assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        }
        assertSingleLineLabels()
        openInfoActionIfNeeded("call-share")
        compose.onNodeWithTag("call-share").assertIsDisplayed().assertIsEnabled()
        openInfoActionIfNeeded("call-audio")
        compose.onNodeWithTag("call-audio").assertIsDisplayed().assertIsEnabled()
        closeInfoMenuIfOpen()
        compose.onNodeWithTag("call-leave").performClick()
        compose.runOnIdle { assertEquals(1, leaves) }
    }

    @Test fun shortSharedStageKeepsContentAndScrollableParticipantsUsable() {
        var leaves = 0
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(844.dp, 390.dp))) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1f)) {
                    MeetSpaceTheme {
                        CallScreen(CallState(CallPhase.CONNECTED, participantId = "me", title = "Встреча", participants = listOf(
                            CallParticipant("one", "Анна Кузнецова", true, true, false),
                            CallParticipant("two", "Мария Полякова", true, false, false),
                            CallParticipant("three", "Дмитрий Волков", true, false, false),
                            CallParticipant("four", "Иван Соколов", true, false, false),
                            CallParticipant("me", "Мой профиль", true, false, false),
                        )), leave = { leaves++ }, sharePreviewName = "Анна Кузнецова", stageContentOverride = {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("Содержимое демонстрации", Modifier.testTag("short-share-test-content"))
                            }
                        })
                    }
                }
            }
        }
        compose.onNodeWithTag("call-short-share-layout").assertIsDisplayed()
        compose.onNodeWithTag("bottom-navigation").assertIsDisplayed()
        listOf("tab-calls", "tab-messages", "tab-profile").forEach {
            compose.onNodeWithTag(it).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        }
        compose.onNodeWithTag("call-share-content").assertHeightIsAtLeast(80.dp).assertWidthIsAtLeast(400.dp)
        assertReadableTextAtActualBounds(compose.onNodeWithTag("short-share-test-content"), "Содержимое демонстрации", singleLine = true)
        assertReadableTextAtActualBounds(compose.onNodeWithTag("call-sharing-label"), "Анна Кузнецова демонстрирует экран", singleLine = true)
        val content = compose.onNodeWithTag("call-share-content").getUnclippedBoundsInRoot()
        val caption = compose.onNodeWithTag("call-sharing-label").getUnclippedBoundsInRoot()
        val firstTile = compose.onNodeWithTag("call-participant-one").getUnclippedBoundsInRoot()
        val grid = compose.onNodeWithTag("call-grid").getUnclippedBoundsInRoot()
        assertTrue("The caption must occupy its own space below the shared picture", content.bottom <= caption.top)
        assertTrue("At least one complete participant tile must fit in the short rail", firstTile.top >= grid.top && firstTile.bottom <= grid.bottom)
        assertReadableTextAtActualBounds(compose.onNodeWithText("Анна Кузнецова", useUnmergedTree = true), "Анна Кузнецова", singleLine = true)
        compose.onNodeWithTag("call-grid").performScrollToIndex(4)
        compose.onNodeWithTag("call-participant-four").assertIsDisplayed()
        assertReadableTextAtActualBounds(compose.onNodeWithText("Иван Соколов", useUnmergedTree = true), "Иван Соколов", singleLine = true)
        compose.onNodeWithTag("call-leave").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, leaves) }
    }

    @Test fun textReadabilityAssertionRejectsEllipsisAndHeightClipping() {
        compose.setContent { MeetSpaceTheme { Column {
            Text("Участники", modifier = Modifier.requiredSize(16.dp, 28.dp).testTag("truncated-label"), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Первая строка\nВторая строка", modifier = Modifier.requiredSize(200.dp, 8.dp).testTag("short-label"))
        } } }
        assertThrows(AssertionError::class.java) { assertReadableTextAtActualBounds(compose.onNodeWithTag("truncated-label", useUnmergedTree = true), "Участники", singleLine = true) }
        assertThrows(AssertionError::class.java) { assertReadableTextAtActualBounds(compose.onNodeWithTag("short-label", useUnmergedTree = true), "Первая строка\nВторая строка") }
    }

    @Test fun singleChatAndParticipantsAreExclusiveAndClosingOrBackNeverLeavesTheCall() {
        var width by mutableStateOf(390.dp)
        var leaves = 0
        val opened = mutableListOf<CallPanel?>()
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(width, 844.dp))) {
                MeetSpaceTheme { CallScreen(CallState(CallPhase.CONNECTED, conferenceId = "meeting-a", title = "Командная встреча"),
                    leave = { leaves++ }, details = CallUiDetails(chat = CallTextPanelState(canSend = true)),
                    panelActions = CallPanelActions(open = { opened += it })) }
            }
        }
        compose.onNodeWithTag("call-comments").assertDoesNotExist()
        compose.onNodeWithTag("call-chat").performClick()
        compose.onNodeWithTag("call-chat-panel").assertIsDisplayed()
        compose.onNodeWithTag("call-people").performClick()
        compose.onNodeWithTag("call-participants-panel").assertIsDisplayed()
        compose.onNodeWithTag("call-chat-panel").assertDoesNotExist()
        compose.onNodeWithTag("call-panel-close").performClick()
        compose.onNodeWithTag("call-participants-panel").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, leaves); assertEquals(null, opened.last()); width = 780.dp }
        compose.onNodeWithTag("call-chat").performClick()
        compose.onNodeWithTag("call-chat-panel").assertIsDisplayed()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        compose.onNodeWithTag("call-chat-panel").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf(CallPanel.CHAT, CallPanel.PARTICIPANTS, null, CallPanel.CHAT, null), opened)
            assertEquals("Closing discussion must not invoke the leave action", 0, leaves)
        }
    }

    @Test fun chatSubmissionUsesOwnedDraftAndDoesNotPretendAnUnacknowledgedMessageWasSent() {
        val editor = EditorWindow()
        var panel by mutableStateOf(CallTextPanelState(canSend = true,
            entries = listOf(CallTextEntry("actual-message", "Участник сервера", "Подтверждённое сообщение", "10:02"))))
        var submissions = 0
        compose.setContent { MeetSpaceTheme { editor.Observe(); CallScreen(CallState(CallPhase.CONNECTED, title = "Встреча"),
            details = CallUiDetails(chat = panel), panelActions = CallPanelActions(
                chatDraft = { panel = panel.copy(draft = it) }, sendChat = { submissions++; panel = panel.copy(sending = true) })) } }
        compose.onNodeWithTag("call-chat").performClick()
        compose.onNodeWithText("Подтверждённое сообщение").assertIsDisplayed()
        compose.onNodeWithTag("call-chat-send").assertIsNotEnabled()
        compose.onNodeWithTag("call-chat-draft").performTextInput("   ")
        compose.onNodeWithTag("call-chat-send").assertIsNotEnabled()
        compose.onNodeWithTag("call-chat-draft").performTextReplacement("Новая реплика")
        settleEditor(editor, "call-chat-send")
        compose.onNodeWithTag("call-chat-send").assertIsEnabled().performClick()
        compose.onNodeWithTag("call-chat-send").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(1, submissions)
            assertEquals("Новая реплика", panel.draft)
            assertEquals(1, panel.entries.size)
            panel = panel.copy(sending = false, error = "Нет подтверждения сервера")
        }
        revealPanelItem("chat", "call-chat-error")
        compose.onNodeWithTag("call-chat-error").assertTextContains("Нет подтверждения сервера")
        revealPanelItem("chat", "call-chat-draft")
        compose.onNodeWithTag("call-chat-draft").assertTextContains("Новая реплика")
        revealPanelItem("chat", "call-panel-close")
        compose.onNodeWithTag("call-panel-close").performClick()
        compose.onNodeWithTag("call-chat").performClick()
        compose.onNodeWithTag("call-chat-draft").assertTextContains("Новая реплика")
    }

    @Test fun meetingChatRespectsPermissionAndExposesActualRetryAndPagingActions() {
        var panel by mutableStateOf(CallTextPanelState(
            entries = listOf(CallTextEntry("actual-comment", "Автор сервера", "Замечание к сценарию", "10:11", dateLabel = "12 октября")),
            draft = "Моё замечание", canSend = false, hasMore = true, error = "Не удалось загрузить старые сообщения"))
        var retry = 0; var more = 0; var submits = 0
        compose.setContent { MeetSpaceTheme { CallScreen(CallState(CallPhase.CONNECTED, title = "Встреча"),
            details = CallUiDetails(chat = panel), panelActions = CallPanelActions(
                chatDraft = { panel = panel.copy(draft = it) }, sendChat = { submits++ }, retryChat = { retry++ }, moreChat = { more++ })) } }
        compose.onNodeWithTag("call-chat").performClick()
        compose.onNodeWithText("Замечание к сценарию").assertExists()
        compose.onNodeWithTag("call-chat-send").assertIsNotEnabled()
        compose.onNodeWithTag("call-chat-draft").assertIsNotEnabled()
        compose.onNodeWithTag("call-chat-retry").performClick()
        compose.onNodeWithTag("call-chat-more").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, retry); assertEquals(1, more); assertEquals(0, submits); panel = panel.copy(canSend = true, hasMore = false, error = null) }
        compose.onNodeWithTag("call-chat-send").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, submits); panel = panel.copy(canSend = false, unavailableReason = "Доступ к обсуждению закрыт") }
        compose.onNodeWithTag("call-chat-send").assertIsNotEnabled()
        revealPanelItem("chat", "call-chat-unavailable")
        compose.onNodeWithText("Доступ к обсуждению закрыт").assertExists()
    }

    @Test fun recordingAndElapsedIndicatorsOnlyClaimActualMetadata() {
        var details by mutableStateOf(CallUiDetails())
        compose.setContent { MeetSpaceTheme { CallScreen(CallState(CallPhase.CONNECTED, title = "Встреча"), details = details) } }
        compose.onNodeWithTag("call-recording-status", useUnmergedTree = true).assertTextEquals("Запись")
        compose.onNodeWithText("Идёт запись").assertDoesNotExist()
        compose.onNodeWithTag("call-elapsed").assertDoesNotExist()
        compose.onNodeWithTag("call-screen-share").assertDoesNotExist()
        compose.runOnIdle { details = details.copy(recordingState = CallRecordingUiState(status = CallRecordingStatus.RECORDING), elapsedSeconds = 768) }
        compose.onNodeWithTag("call-recording-status", useUnmergedTree = true).assertTextEquals("Идёт запись")
        compose.onNodeWithTag("call-elapsed").assertTextContains("12:48", substring = true)
        compose.runOnIdle { details = CallUiDetails() }
        compose.onNodeWithTag("call-recording-status", useUnmergedTree = true).assertTextEquals("Запись")
        compose.onNodeWithTag("call-elapsed").assertDoesNotExist()
    }

    @Test fun startingRecordingCanBeStoppedButBusyStatePreventsRepeatedStop() {
        var recording by mutableStateOf(CallRecordingUiState(status = CallRecordingStatus.STARTING, canStop = true))
        var stops = 0
        compose.setContent { MeetSpaceTheme {
            CallScreen(CallState(CallPhase.CONNECTED, title = "Встреча"),
                details = CallUiDetails(recordingState = recording),
                recordingActions = CallRecordingActions(stop = { stops++; recording = recording.copy(busy = true) }))
        } }
        compose.onNodeWithTag("call-recording-control").performClick()
        compose.onNodeWithTag("call-recording-dialog").assertIsDisplayed()
        compose.onNodeWithTag("call-recording-stop").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, stops); assertEquals(CallRecordingStatus.STARTING, recording.status) }
        compose.onNodeWithTag("call-recording-stop").performScrollTo().assertIsDisplayed().assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals("A pending stop must not issue a second request", 1, stops) }
    }

    @Test fun reopeningTheOnlyMeetingChatKeepsItsDraftAndDoesNotDuplicateServerHistory() {
        val editor = EditorWindow()
        val confirmed = CallTextEntry("same-server-id", "Автор встречи", "Общая история встречи", "11:04")
        var shared by mutableStateOf(CallTextPanelState(entries = listOf(confirmed), canSend = true))
        var sends = 0; var legacySends = 0
        compose.setContent { MeetSpaceTheme { editor.Observe(); CallScreen(CallState(CallPhase.CONNECTED, title = "Встреча"),
            details = CallUiDetails(chat = shared),
            panelActions = CallPanelActions(chatDraft = { shared = shared.copy(draft = it) },
                sendChat = { sends++ }, sendComment = { legacySends++ })) } }
        compose.onNodeWithTag("call-comments").assertDoesNotExist()
        compose.onNodeWithTag("call-chat").performClick()
        compose.onAllNodesWithText("Общая история встречи").assertCountEquals(1)
        compose.onNodeWithTag("call-chat-draft").performTextInput("Общий черновик")
        settleEditor(editor, "call-panel-close")
        compose.onNodeWithTag("call-panel-close").performClick()
        compose.onNodeWithTag("call-chat").performClick()
        compose.onAllNodesWithText("Общая история встречи").assertCountEquals(1)
        compose.onNodeWithTag("call-chat-draft").assertTextContains("Общий черновик")
        compose.onNodeWithTag("call-chat-send").assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, sends); assertEquals(0, legacySends); assertEquals(listOf(confirmed), shared.entries) }
    }

    @Test fun compactScreenSharingIsOnlyInvokedAfterSelectingTheExplicitMenuAction() {
        var shares = 0
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(390.dp, 844.dp))) {
                MeetSpaceTheme { CallScreen(CallState(CallPhase.CONNECTED, title = "Встреча"), share = { shares++ }) }
            }
        }
        compose.onNodeWithTag("call-share").assertDoesNotExist()
        compose.onNodeWithTag("call-info").performClick()
        compose.runOnIdle { assertEquals(0, shares) }
        compose.onNodeWithTag("call-share").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, shares) }
        compose.onNodeWithTag("call-leave").assertIsDisplayed()
    }

    @Test fun deletedMeetingMessagesNeverRevealTheirBodyOrDownloadableAttachment() {
        val history = CallTextPanelState(entries = listOf(CallTextEntry("deleted-id", "Автор", "Скрытый текст удалённого сообщения", "10:12",
            attachmentLabel = "Старое имя.pdf", deleted = true,
            attachments = listOf(CallAttachmentUi("private-file", "Удалённый-файл.pdf", "application/pdf", 2048)))))
        compose.setContent { MeetSpaceTheme { CallScreen(CallState(CallPhase.CONNECTED, title = "Встреча"), details = CallUiDetails(chat = history)) } }
        compose.onNodeWithTag("call-chat").performClick()
        compose.onNodeWithText("Сообщение удалено").assertExists()
        compose.onNodeWithText("Скрытый текст удалённого сообщения").assertDoesNotExist()
        compose.onNodeWithText("Удалённый-файл.pdf").assertDoesNotExist()
        compose.onNodeWithText("Старое имя.pdf").assertDoesNotExist()
        compose.onNodeWithTag("call-chat-attachment-deleted-id-private-file").assertDoesNotExist()
    }

    @Test fun phoneLargeFontChatStaysInsideCallBodyWithCloseAndLeaveReachable() {
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(430.dp, 932.dp))) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                    MeetSpaceTheme { CallScreen(CallState(CallPhase.CONNECTED, title = "Дизайн-синхронизация"),
                        details = CallUiDetails(chat = CallTextPanelState(canSend = true,
                            entries = listOf(CallTextEntry("long", "Имя участника", "Комментарий с длинным текстом, который должен переноситься внутри панели.", "10:11"))))) }
                }
            }
        }
        compose.onNodeWithTag("call-chat").performClick()
        compose.onNodeWithTag("call-panel-close").assertIsDisplayed()
        compose.onNodeWithTag("call-chat-draft").assertIsDisplayed()
        compose.onNodeWithTag("call-chat-send").assertIsDisplayed()
        compose.onNodeWithTag("call-leave").assertIsDisplayed()
        val panel = compose.onNodeWithTag("call-chat-panel").getUnclippedBoundsInRoot()
        val leave = compose.onNodeWithTag("call-leave").getUnclippedBoundsInRoot()
        assertTrue("The chat must not cover the persistent call toolbar", panel.bottom <= leave.top)
        assertTrue("The panel must fit the phone viewport", panel.left >= 0.dp && panel.right <= 430.dp)
    }

    @Test fun pendingAttachmentsRequireConfirmedUploadAndRemainOwnedUntilSendAcknowledgement() {
        val readyFile = CallPendingAttachmentUi("upload-id", "Повестка.txt", "text/plain", 452)
        var panel by mutableStateOf(CallTextPanelState(canSend = true, canAttach = true))
        var attachments = 0; var sends = 0
        val removed = mutableListOf<String>()
        compose.setContent { MeetSpaceTheme { CallScreen(CallState(CallPhase.CONNECTED, title = "Встреча"),
            details = CallUiDetails(chat = panel), panelActions = CallPanelActions(
                attachChat = { attachments++ },
                removeChatAttachment = { id -> removed += id; panel = panel.copy(pendingFiles = panel.pendingFiles.filterNot { it.id == id }) },
                sendChat = { sends++; panel = panel.copy(sending = true) })) } }
        compose.onNodeWithTag("call-chat").performClick()
        compose.onNodeWithTag("call-chat-attach").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, attachments)
            panel = panel.copy(pendingFiles = listOf(readyFile.copy(status = CallAttachmentStatus.UPLOADING)))
        }
        compose.onNodeWithTag("call-chat-send").assertIsNotEnabled()
        compose.onNodeWithTag("call-chat-remove-upload-id").performClick()
        compose.runOnIdle { assertEquals(listOf("upload-id"), removed); assertTrue(panel.pendingFiles.isEmpty()); panel = panel.copy(pendingFiles = listOf(readyFile.copy(status = CallAttachmentStatus.UNKNOWN))) }
        compose.onNodeWithTag("call-chat-send").assertIsNotEnabled()
        compose.runOnIdle { panel = panel.copy(pendingFiles = listOf(readyFile.copy(sizeBytes = 10_485_761))) }
        compose.onNodeWithTag("call-chat-send").assertIsNotEnabled()
        compose.runOnIdle { panel = panel.copy(pendingFiles = (1..6).map { readyFile.copy(id = "file-$it") }) }
        compose.onNodeWithTag("call-chat-send").assertIsNotEnabled()
        compose.onNodeWithTag("call-chat-attach").assertIsNotEnabled()
        compose.runOnIdle { panel = panel.copy(pendingFiles = listOf(readyFile)) }
        // An attachment-only message is valid once its upload is confirmed.
        compose.onNodeWithTag("call-chat-send").assertIsEnabled().performClick()
        compose.onNodeWithTag("call-chat-send").assertIsNotEnabled()
        compose.onNodeWithTag("call-chat-attach").assertIsNotEnabled()
        compose.onNodeWithTag("call-chat-remove-upload-id").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, sends); assertEquals(listOf(readyFile), panel.pendingFiles); assertTrue(panel.entries.isEmpty()); panel = panel.copy(sending = false, canSend = false, status = "Ожидаем подтверждения сервера") }
        compose.onNodeWithTag("call-chat-status").assertTextEquals("Ожидаем подтверждения сервера")
        compose.onNodeWithTag("call-chat-draft").assertIsNotEnabled()
        compose.onNodeWithTag("call-chat-send").assertIsNotEnabled()
        compose.onNodeWithTag("call-chat-remove-upload-id").assertIsNotEnabled()
    }

    @Test fun ownBubblesAlignRightAndFileDownloadsUseTheirExactMessageAndAttachmentIds() {
        val attachment = CallAttachmentUi("file-id", "Повестка встречи.txt", "text/plain", 452)
        val incoming = CallTextEntry("incoming", "Анна", "Короткий текст", "10:02")
        val outgoing = CallTextEntry("outgoing", "Александр", "Короткий текст", "10:03", own = true)
        val fileEntry = CallTextEntry("file-message", "Мария", "", "10:04", attachments = listOf(attachment))
        var panel by mutableStateOf(CallTextPanelState(entries = listOf(incoming, outgoing, fileEntry), participantCount = 5))
        val downloads = mutableListOf<Pair<String, String>>()
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(1194.dp, 834.dp))) {
                MeetSpaceTheme { CallScreen(CallState(CallPhase.CONNECTED, title = "Встреча"), details = CallUiDetails(chat = panel),
                    panelActions = CallPanelActions(downloadChatAttachment = { entryId, fileId ->
                        downloads += entryId to fileId
                        panel = panel.copy(entries = listOf(incoming, outgoing, fileEntry.copy(attachments = listOf(attachment.copy(downloading = true)))))
                    })) }
            }
        }
        compose.onNodeWithTag("call-chat").performClick()
        compose.onNodeWithText("Сообщения и файлы · 5 участников").assertIsDisplayed()
        val incomingText = compose.onNode(hasText("Короткий текст") and hasAnyAncestor(hasTestTag("call-chat-entry-incoming")), useUnmergedTree = true).getUnclippedBoundsInRoot()
        val outgoingText = compose.onNode(hasText("Короткий текст") and hasAnyAncestor(hasTestTag("call-chat-entry-outgoing")), useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("Own messages must align to the right of an equally short incoming bubble", outgoingText.left > incomingText.left + 20.dp)
        compose.onNodeWithText("10:03 · Вы").assertIsDisplayed()
        val card = compose.onNodeWithTag("call-chat-attachment-file-message-file-id")
        card.performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf("file-message" to "file-id"), downloads) }
        card.assertIsNotEnabled()
        compose.onNodeWithText("Скачивание…").assertExists()
        compose.runOnIdle { panel = panel.copy(entries = listOf(incoming, outgoing, fileEntry.copy(attachments = listOf(attachment.copy(canDownload = false, error = "Доступ к файлу закрыт"))))) }
        card.assertIsNotEnabled()
        compose.onNodeWithTag("call-chat-attachment-error-file-id").assertTextEquals("Доступ к файлу закрыт")
    }

    @Test fun meetingDraftAcceptsFourThousandCharactersAndRejectsAdditionalInput() {
        val editor = EditorWindow()
        var panel by mutableStateOf(CallTextPanelState(canSend = true))
        var submitted: String? = null
        compose.setContent { MeetSpaceTheme { editor.Observe(); CallScreen(CallState(CallPhase.CONNECTED, title = "Встреча"),
            details = CallUiDetails(chat = panel), panelActions = CallPanelActions(
                chatDraft = { panel = panel.copy(draft = it) }, sendChat = { submitted = panel.draft })) } }
        compose.onNodeWithTag("call-chat").performClick()
        val maximumDraft = "я".repeat(4000)
        compose.onNodeWithTag("call-chat-draft").performTextInput(maximumDraft)
        compose.runOnIdle { assertEquals(maximumDraft, panel.draft) }
        compose.onNodeWithTag("call-chat-draft").performTextInput("Лишний символ")
        compose.runOnIdle { assertEquals(maximumDraft, panel.draft) }
        settleEditor(editor, "call-chat-send")
        compose.onNodeWithTag("call-chat-send").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(maximumDraft, submitted) }
    }

    @Test fun activeCallKeepsThreeWorkspaceDestinationsWithoutInvokingLeave() {
        var width by mutableStateOf(390.dp)
        var leaves = 0
        val destinations = mutableListOf<WorkspaceTab>()
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(width, 844.dp))) {
                MeetSpaceTheme { CallScreen(CallState(CallPhase.CONNECTED, title = "Встреча"),
                    onWorkspaceTab = { destinations += it }, profileLabel = "Тестовый профиль", leave = { leaves++ }) }
            }
        }
        compose.onNodeWithTag("call-comments").assertDoesNotExist()
        compose.onNodeWithTag("call-chat").performClick()
        compose.onNodeWithTag("call-chat-panel").assertIsDisplayed()
        compose.onNodeWithTag("bottom-navigation").assertIsDisplayed()
        compose.onNodeWithTag("tab-calls").assertIsDisplayed().assertIsSelected().performClick()
        compose.onNodeWithTag("tab-messages").assertIsDisplayed().performClick()
        compose.onNodeWithTag("tab-profile").assertIsDisplayed().performClick()
        val navigation = compose.onNodeWithTag("bottom-navigation").getUnclippedBoundsInRoot()
        val toolbar = compose.onNodeWithTag("call-toolbar").getUnclippedBoundsInRoot()
        assertTrue("Workspace navigation must remain below the call controls", navigation.top >= toolbar.bottom)
        compose.runOnIdle { assertEquals(listOf(WorkspaceTab.CALLS, WorkspaceTab.MESSAGES, WorkspaceTab.PROFILE), destinations); assertEquals(0, leaves); width = 1194.dp }
        compose.onNodeWithTag("bottom-navigation").assertIsDisplayed()
        compose.onNodeWithTag("tab-profile").performClick()
        compose.runOnIdle { assertEquals(WorkspaceTab.PROFILE, destinations.last()); assertEquals(0, leaves) }
        compose.onNodeWithTag("call-leave").performClick()
        compose.runOnIdle { assertEquals(1, leaves) }
    }

    private fun assertSingleLineLabels() {
        listOf("call-mic-label" to "Без звука", "call-camera-label" to "Камера", "call-people-label" to "Участники",
            "call-chat-label" to "Чат", "call-leave-label" to "Выйти").forEach { (tag, label) ->
            assertReadableTextAtActualBounds(compose.onNodeWithTag(tag, useUnmergedTree = true), label, singleLine = true)
        }
    }

    private fun openInfoActionIfNeeded(tag: String) {
        if (compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()) compose.onNodeWithTag("call-info").performClick()
    }

    private fun closeInfoMenuIfOpen() {
        if (compose.onAllNodesWithTag("call-audio").fetchSemanticsNodes().isNotEmpty()) {
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
            compose.waitForIdle()
        }
    }

    private fun revealPanelItem(panel: String, tag: String) {
        if (compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("call-$panel-scroll").performScrollToNode(hasTestTag(tag))
        }
    }

    /** Callback tests dismiss the IME before pointer clicks; CallKeyboardTest covers live IME input. */
    private class EditorWindow {
        val imeBottom = AtomicInteger(0)
        var dismiss: () -> Unit = {}

        @Composable fun Observe() {
            val focus = LocalFocusManager.current
            val keyboard = LocalSoftwareKeyboardController.current
            val inset = WindowInsets.ime.getBottom(LocalDensity.current)
            SideEffect {
                imeBottom.set(inset)
                dismiss = { focus.clearFocus(force = true); keyboard?.hide() }
            }
        }
    }

    private fun settleEditor(editor: EditorWindow, clickTag: String) {
        compose.runOnIdle { editor.dismiss() }
        var bounds: DpRect? = null
        var stableSince = SystemClock.uptimeMillis()
        compose.waitUntil(timeoutMillis = 5_000) {
            val current = compose.onNodeWithTag(clickTag).getUnclippedBoundsInRoot()
            val now = SystemClock.uptimeMillis()
            if (current != bounds || editor.imeBottom.get() != 0) {
                bounds = current
                stableSince = now
            }
            editor.imeBottom.get() == 0 && now - stableSince >= 350
        }
        compose.onNodeWithTag(clickTag).assertIsDisplayed()
    }

}
