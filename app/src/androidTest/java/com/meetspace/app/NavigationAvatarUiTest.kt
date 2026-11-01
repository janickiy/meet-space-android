package com.meetspace.app

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.meetspace.app.ui.*
import com.meetspace.core.designsystem.MeetSpaceTheme
import com.meetspace.core.media.CallPhase
import com.meetspace.core.media.CallState
import com.meetspace.core.model.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class NavigationAvatarUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun workspaceShowsCurrentPhotoAndRefreshesOnEditAccountSwitchAndRemoval() {
        var user by mutableStateOf(User("first", "first@example.test", "Анна Первая", avatarVersion = "one"))
        val color = AtomicInteger(android.graphics.Color.RED)
        val requested = ConcurrentLinkedQueue<String>()
        val loader = AvatarImageLoader(user = { id -> requested.add(id); photo(color.get()) },
            group = { error("Unexpected group avatar") }, isCurrent = { true })
        try {
            compose.setContent {
                CompositionLocalProvider(LocalAvatarImages provides loader) {
                    MeetSpaceTheme(ThemeMode.LIGHT) {
                        FoundationUi(FoundationUiState(FoundationScreen.WORKSPACE,
                            RepositoryState(session = AccountSession(user, Capabilities(), true))),
                            {}, {}, {}, { _, _ -> }, {}, {}, {}, {}, workspaceContent = { _, _ -> })
                    }
                }
            }
            waitForPhoto(Color.Red)
            assertEquals(listOf("first"), requested.toList())
            compose.runOnIdle { color.set(android.graphics.Color.BLUE); user = user.copy(avatarVersion = "two") }
            waitForPhoto(Color.Blue)
            assertEquals(listOf("first", "first"), requested.toList())
            compose.runOnIdle {
                color.set(android.graphics.Color.GREEN)
                user = User("second", "second@example.test", "Мария Вторая", avatarVersion = "one")
            }
            waitForPhoto(Color.Green)
            assertEquals(listOf("first", "first", "second"), requested.toList())
            compose.runOnIdle { user = user.copy(avatarVersion = null) }
            compose.onNode(hasText("МВ") and hasAnyAncestor(hasTestTag("navigation-profile-avatar")), useUnmergedTree = true).assertExists()
            assertEquals(3, requested.size)
        } finally { loader.close() }
    }

    @Test fun navigationAlignsIconsAndLabelsOnPhoneAndTablet() {
        var width by mutableStateOf(390)
        var selected by mutableStateOf(WorkspaceTab.CALLS)
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(width.dp, 844.dp))) {
                MeetSpaceTheme(ThemeMode.LIGHT) {
                    WorkspaceNavigation(selected, { selected = it }, "Анна Первая", width >= 780)
                }
            }
        }
        for (size in listOf(390, 1194)) {
            compose.runOnIdle { width = size }
            for (tab in listOf(WorkspaceTab.CALLS, WorkspaceTab.MESSAGES, WorkspaceTab.PROFILE)) {
                compose.onNodeWithTag("tab-${tab.tag}").performClick()
                assertAlignedNavigation()
            }
        }
    }

    @Test fun prejoinActiveCallAndRecordingsKeepTheAuthenticatedAvatar() {
        var destination by mutableStateOf(0)
        val user = User("signed-in", "member@example.test", "Анна Первая", avatarVersion = "one")
        val requested = ConcurrentLinkedQueue<String>()
        val loader = AvatarImageLoader(user = { id -> requested.add(id); photo(android.graphics.Color.RED) },
            group = { error("Unexpected group avatar") }, isCurrent = { true })
        try {
            compose.setContent {
                CompositionLocalProvider(LocalAvatarImages provides loader) {
                    MeetSpaceTheme(ThemeMode.LIGHT) {
                        when (destination) {
                            0 -> CallScreen(CallState(CallPhase.PREJOIN), onWorkspaceTab = {}, profileLabel = "Другое имя во встрече", profileUser = user)
                            1 -> CallScreen(CallState(CallPhase.CONNECTED), onWorkspaceTab = {}, profileLabel = "Другое имя во встрече", profileUser = user)
                            else -> RecordingsWorkspace(RecordingsUiState(), RecordingsActions(), onWorkspaceTab = {}, profileUser = user)
                        }
                    }
                }
            }
            for (screen in 0..2) {
                compose.runOnIdle { destination = screen }
                waitForPhoto(Color.Red)
                assertAlignedNavigation()
            }
            assertEquals(listOf("signed-in"), requested.toList())
        } finally { loader.close() }
    }

    private fun assertAlignedNavigation() {
        val icons = listOf("calls", "messages", "profile").map {
            compose.onNodeWithTag("tab-$it-icon", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        }
        val labels = listOf("calls", "messages", "profile").map {
            compose.onNodeWithTag("tab-$it-label", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        }
        icons.forEach { assertEquals(icons.first().center.y, it.center.y, .5f) }
        labels.forEach { assertEquals(labels.first().top, it.top, .5f) }
        val avatar = compose.onNodeWithTag("navigation-profile-avatar", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(icons.first().center.y, avatar.center.y, .5f)
    }

    private fun waitForPhoto(expected: Color) {
        compose.waitUntil(5_000) {
            val pixels = compose.onNodeWithTag("navigation-profile-avatar", useUnmergedTree = true).captureToImage().toPixelMap()
            val actual = pixels[pixels.width / 2, pixels.height / 2]
            kotlin.math.abs(actual.red - expected.red) < .05f &&
                kotlin.math.abs(actual.green - expected.green) < .05f &&
                kotlin.math.abs(actual.blue - expected.blue) < .05f
        }
    }

    private fun photo(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(color)
            ByteArrayOutputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output); output.toByteArray() }
        } finally { bitmap.recycle() }
    }
}
