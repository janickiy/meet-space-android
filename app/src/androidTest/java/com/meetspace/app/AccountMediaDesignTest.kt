package com.meetspace.app

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.meetspace.app.ui.*
import com.meetspace.core.designsystem.MeetSpaceTheme
import com.meetspace.core.model.*
import java.io.File
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class AccountMediaDesignTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun audioAndVideoRemainReadableAtPhoneTabletAndLargeFontSizes() {
        var size by mutableStateOf(DpSize(390.dp, 844.dp))
        var dark by mutableStateOf(false)
        var scale by mutableFloatStateOf(1f)
        var section by mutableStateOf("audio")
        compose.setContent { DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(size)) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                MeetSpaceTheme(if (dark) ThemeMode.DARK else ThemeMode.LIGHT) {
                    key(section) {
                        ProfileWorkspace(RepositoryState(), MeetingsUiState(), MeetingsActions(),
                            AppSettingsUiState("test", 0, true, false, false, false, false), AppSettingsActions(),
                            onTheme = {}, onLogout = {}, onServers = {}, onFolders = {}, initialSection = section)
                    }
                }
            }
        } }
        for ((width, height, font) in listOf(Triple(390, 844, 1f), Triple(1194, 834, 1f), Triple(320, 640, 1.5f))) {
            for (night in listOf(false, true)) for (page in listOf("audio", "video")) {
                compose.runOnIdle { size = DpSize(width.dp, height.dp); scale = font; dark = night; section = page }
                val labels = if (page == "audio") listOf("Микрофон", "Динамик", "Шумоподавление", "Громкость микрофона", "Громкость динамика", "Звонки и уведомления", "Громкость сигналов")
                    else listOf("Подключаться с выключенной камерой", "Видеть себя на звонке", "Скрыть видео участников")
                labels.forEach { label ->
                    val node = compose.onNodeWithText(label, useUnmergedTree = true).performScrollTo()
                    assertReadableTextAtActualBounds(node, label)
                }
                if (page == "audio") listOf("microphone", "speaker", "signal").forEach {
                    compose.onNodeWithTag("account-$it-volume").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(44.dp)
                } else compose.onNodeWithTag("account-camera-toggle").performScrollTo().assertIsDisplayed()
                if (font == 1f) {
                    compose.onNodeWithText(if (page == "audio") "Подготовьте звук к следующей встрече." else "Предпросмотр доступен только вам.", useUnmergedTree = true).performScrollTo()
                    val bitmap = compose.onNodeWithTag("profile").captureToImage().asAndroidBitmap()
                    val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "account-$page-$width-${if (night) "dark" else "light"}.png")
                    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            }
        }
    }
}
