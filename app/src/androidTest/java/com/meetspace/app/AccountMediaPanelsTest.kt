package com.meetspace.app

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.RingtoneManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.meetspace.app.ui.*
import com.meetspace.core.designsystem.MeetSpaceTheme
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class AccountMediaPanelsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val initial = AppSettingsUiState("test", 0, false, false, false, false, false)

    @Test fun audioStartsSilentAndChangesOnlyTheRequestedPreferences() {
        var settings by mutableStateOf(initial)
        compose.setContent { Frame { AccountAudioPanel(settings, AppSettingsActions(updateDevicePreferences = { settings = settings.copy(devicePreferences = it) })) } }
        listOf("mic", "speaker", "ringtone").forEach { id ->
            compose.onNodeWithTag("account-test-$id").performScrollTo().assertIsEnabled()
            compose.onNodeWithTag("account-$id-meter").assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(0f, 0f..1f)))
            compose.onNodeWithTag("account-$id-test-status").assertDoesNotExist()
        }
        compose.onNodeWithTag("account-auto-mute").performScrollTo().assertIsOn().performClick()
        compose.runOnIdle { assertFalse(settings.devicePreferences.autoMute); assertTrue(settings.devicePreferences.cameraOff) }
        compose.onNodeWithTag("account-noise").assertDoesNotExist()
        compose.onNodeWithTag("account-speaker").performScrollTo().performClick()
        compose.onNodeWithText("Наушники").performClick()
        compose.runOnIdle { assertEquals(SpeakerPreference.HEADSET, settings.devicePreferences.speaker) }
        compose.onAllNodesWithText("Остановить проверку").assertCountEquals(0)
    }

    @Test fun videoStartsWithoutCameraAndPreferenceCallbacksDoNotStartIt() {
        var settings by mutableStateOf(initial)
        compose.setContent { Frame { AccountVideoPanel("Анна Смирнова", settings, AppSettingsActions(updateDevicePreferences = { settings = settings.copy(devicePreferences = it) })) } }
        compose.onNodeWithTag("account-camera-status").assertTextEquals("Камера выключена")
        compose.onNodeWithTag("account-camera-live").assertDoesNotExist()
        compose.onNodeWithTag("account-camera-starting").assertDoesNotExist()
        compose.onNodeWithTag("account-camera-off").performScrollTo().assertIsOn().performClick()
        compose.onNodeWithTag("account-self-video").performScrollTo().assertIsOn().performClick()
        compose.onNodeWithTag("account-hide-video").performScrollTo().assertIsOff().performClick()
        compose.runOnIdle {
            assertFalse(settings.devicePreferences.cameraOff)
            assertFalse(settings.devicePreferences.showSelf)
            assertTrue(settings.devicePreferences.hideParticipantVideo)
        }
        compose.onNodeWithTag("account-camera-status").performScrollTo().assertTextEquals("Камера выключена")
    }

    @Test fun largeFontVideoKeepsPreviewAndPreferenceLabelsReadable() {
        compose.setContent { Frame(fontScale = 1.5f) { AccountVideoPanel("Анна Смирнова", initial, AppSettingsActions()) } }
        compose.onNodeWithTag("account-camera-toggle").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        listOf("Показать предпросмотр", "Подключаться с выключенной камерой", "Видеть себя на встрече", "Скрыть видео участников").forEach { label ->
            val node = compose.onNodeWithText(label, useUnmergedTree = true).performScrollTo()
            assertReadableTextAtActualBounds(node, label)
        }
    }

    @Test fun notificationSoundUsesALocalSwitchAndDoesNotAutoplay() {
        var enabled by mutableStateOf(true)
        compose.setContent { Frame { AccountNotificationSound(enabled, true) { enabled = it } } }
        compose.onNodeWithTag("account-sound-enabled").assertIsEnabled().assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Switch))
        compose.onNodeWithTag("account-test-notification").assertIsEnabled()
        compose.onNodeWithTag("account-notification-test-status").assertDoesNotExist()
        compose.onNodeWithTag("account-sound-enabled").performClick().assertIsOff()
        compose.onNodeWithTag("account-test-notification").performClick()
        compose.onNodeWithTag("account-notification-test-status").assertTextEquals("Включите звуковой сигнал для проверки")
        compose.onNodeWithTag("account-ringtone-meter").assertDoesNotExist()
    }

    /** Explicitly opt in only on the owned test emulator, with permissions pregranted by the runner. */
    @Test fun realMicrophoneStopsOnBackgroundAndCanBeOpenedAgain() {
        requireHardwareOptIn(Manifest.permission.RECORD_AUDIO)
        compose.setContent { Frame { AccountAudioPanel(initial, AppSettingsActions()) } }
        compose.onNodeWithTag("account-test-mic").performScrollTo().performClick()
        compose.onNodeWithTag("account-mic-test-status").assertTextContains("Говорите", substring = true)
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithTag("account-mic-test-status").assertTextEquals("Проверка остановлена.")
        compose.onNodeWithTag("account-test-mic").performScrollTo().performClick()
        compose.onNodeWithTag("account-mic-test-status").assertTextContains("Говорите", substring = true)
        compose.onNodeWithTag("account-test-mic").performClick()
        compose.onNodeWithTag("account-mic-test-status").assertTextEquals("Проверка остановлена.")
    }

    @Test fun realCameraProducesFramesStopsAndCanBeOpenedAgain() {
        requireHardwareOptIn(Manifest.permission.CAMERA)
        assumeTrue(compose.activity.getSystemService(CameraManager::class.java).cameraIdList.isNotEmpty())
        compose.setContent { Frame { AccountVideoPanel("Проверка камеры", initial, AppSettingsActions()) } }
        repeat(2) {
            compose.onNodeWithTag("account-camera-toggle").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("account-camera-live").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("account-camera-status").assertTextEquals("Предпросмотр камеры")
            if (it == 0) {
                compose.onNodeWithTag("account-camera-toggle").performScrollTo().performClick()
            } else {
                compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
                compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            }
            compose.onNodeWithTag("account-camera-status").performScrollTo().assertTextEquals("Камера выключена")
            compose.onNodeWithTag("account-camera-live").assertDoesNotExist()
        }
    }

    @Test fun realSpeakerAndNotificationTestsReleaseAtTheirDeadlines() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("accountMediaHardware") == "true")
        lateinit var probe: AccountAudioProbe
        compose.runOnIdle { probe = AccountAudioProbe(compose.activity.applicationContext) }
        try {
            compose.runOnIdle { probe.speaker(SpeakerPreference.DEFAULT); assertEquals(AudioProbeKind.SPEAKER, probe.kind) }
            compose.waitUntil(3_000) { probe.kind == null }
            compose.runOnIdle { assertTrue(probe.status.startsWith("Проверка завершена")) }
            compose.runOnIdle {
                probe.ringtone(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
                assertEquals(AudioProbeKind.RINGTONE, probe.kind)
            }
            compose.waitUntil(5_000) { probe.kind == null }
            compose.runOnIdle { assertTrue(probe.status.startsWith("Проверка завершена")) }
        } finally { compose.runOnIdle { probe.close(); assertNull(probe.kind) } }
    }

    private fun requireHardwareOptIn(permission: String) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("accountMediaHardware") == "true")
        assumeTrue("The owner must pregrant test permissions", ContextCompat.checkSelfPermission(compose.activity, permission) == PackageManager.PERMISSION_GRANTED)
    }

    @Composable private fun Frame(fontScale: Float = 1f, content: @Composable () -> Unit) {
        DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(390.dp, 844.dp))) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MeetSpaceTheme { SurfaceForTest { content() } }
            }
        }
    }
}

@Composable
private fun SurfaceForTest(content: @Composable () -> Unit) {
    androidx.compose.material3.Surface {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) { content() }
    }
}
