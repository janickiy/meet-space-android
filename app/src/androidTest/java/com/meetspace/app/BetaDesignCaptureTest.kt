package com.meetspace.app

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.meetspace.app.ui.*
import com.meetspace.core.designsystem.MeetSpaceTheme
import com.meetspace.core.model.*
import java.io.File
import java.time.Instant
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in presentation evidence only. Fixtures cannot register users, make calls or send messages. */
class BetaDesignCaptureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun captureRegistration() {
        selected("beta-register")
        setContent {
            val profile = ServerProfile("beta-design-profile", "https://meet.example.org")
            FoundationUi(FoundationUiState(screen = FoundationScreen.REGISTER,
                repository = RepositoryState(profiles = listOf(profile), selectedProfileId = profile.id)),
                onConnect = {}, onSelectProfile = {}, onRemoveProfile = {}, onLogin = { _, _ -> },
                onRegister = { _, _, _, _ -> }, onLogout = {}, onTheme = {}, onNavigate = {}, onClearMessage = {})
        }
        compose.onNodeWithTag("register").assertIsDisplayed()
        listOf("Создайте аккаунт.", "Для встреч и общения с вашей командой.", "meet.example.org",
            "Имя для встреч", "Электронная почта", "Пароль", "Повторите пароль", "Создать аккаунт", "Уже есть аккаунт?").forEach { text ->
            val node = compose.onNodeWithText(text, useUnmergedTree = true)
            node.performScrollTo()
            assertReadableTextAtActualBounds(node, text)
        }
        compose.onNodeWithTag("register-submit").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("register-password").assertTextContains("")
        compose.onNodeWithTag("register-confirm").assertTextContains("")
        // Reset the form pane after testing its bottom. The phone's brand precedes Back;
        // on tablet that brand belongs to the independent artwork pane.
        compose.onNodeWithTag("back").performScrollTo()
        if (InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.screenWidthDp < 760) {
            compose.onNodeWithText("MeetSpace", useUnmergedTree = true).performScrollTo()
        }
        capture("beta-register")
    }

    @Test fun capturePersonalThreadBannerAndReadOnlyCallHistory() {
        selected("beta-thread-call")
        setContent {
            // Workspace normally receives the Foundation shell's safe drawing insets.
            MessagingWorkspace(thread, MessagingActions(), modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
                onStartCall = {}, personalCallLabel = "02:05", onResumeCall = {})
        }
        compose.onNodeWithTag("conversation-call").assertIsDisplayed()
        compose.onNodeWithTag("personal-call-chat-banner").assertIsDisplayed().assertTextContains("02:05")
        compose.onNodeWithTag("call-history-beta-call-message").assertIsDisplayed()
        listOf("Личный звонок", "02:05", "Вернуться ↗", "Исходящий звонок", "Завершён · 2:05").forEach { text ->
            assertReadableTextAtActualBounds(compose.onNodeWithText(text, useUnmergedTree = true), text)
        }
        compose.onNodeWithTag("message-menu-beta-call-message").assertDoesNotExist()
        compose.onNodeWithTag("message-draft").assertIsDisplayed()
        capture("beta-thread-call")
    }

    private fun selected(name: String) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Opt-in beta design screenshots", args.getString("designScreenshots") == "true")
        args.getString("designScreen")?.let { assumeTrue("Selected beta design screen", it == name) }
    }

    private fun setContent(content: @androidx.compose.runtime.Composable () -> Unit) {
        val theme = InstrumentationRegistry.getArguments().getString("designTheme", "light")
        require(theme in setOf("light", "dark"))
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
        compose.setContent {
            MeetSpaceTheme(if (theme == "dark") ThemeMode.DARK else ThemeMode.LIGHT) {
                val lightBars = MaterialTheme.colorScheme.background.luminance() > .5f
                SideEffect {
                    WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView).apply {
                        isAppearanceLightStatusBars = lightBars
                        isAppearanceLightNavigationBars = lightBars
                    }
                }
                Box(Modifier.fillMaxSize().testTag("beta-design-screen")) { content() }
            }
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = context.resources.configuration
        val directory = checkNotNull(context.getExternalFilesDir("screenshots"))
        check(directory.isDirectory || directory.mkdirs())
        val theme = InstrumentationRegistry.getArguments().getString("designTheme", "light")
        val file = File(directory, "$name-$theme-${config.screenWidthDp}x${config.screenHeightDp}-font${config.fontScale}.png")
        file.outputStream().use { stream ->
            check(compose.onNodeWithTag("beta-design-screen").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
    }

    companion object {
        private val now = Instant.parse("2026-10-12T12:00:00Z")
        private val peer = ChatPeer("beta-design-peer", "Максим Волков")
        private val conversation = ChatConversation("beta-design-thread", "direct", peer, preview = "Да, давай созвонимся.")
        private val history = DirectCallHistory("beta-design-old-call", "beta-design-self", peer.id, "ended", now,
            acceptedAt = now.plusSeconds(3), endedAt = now.plusSeconds(128), durationSec = 125)
        private val messages = listOf(
            ChatMessage("beta-peer-message", conversation.id, peer.id, peer.displayName, "", "Можно обсудить обновление MeetSpace?", 1, 1, now.minusSeconds(360), now.minusSeconds(360)),
            ChatMessage("beta-call-message", conversation.id, "beta-design-self", "Анна Смирнова", "", "", 2, 1, now, now, kind = "call", call = history),
            ChatMessage("beta-self-message", conversation.id, "beta-design-self", "Анна Смирнова", "", "Да, давай созвонимся.", 3, 1, now.plusSeconds(180), now.plusSeconds(180)),
        )
        private val thread = MessagingUiState(selfId = "beta-design-self", conversations = MessagingLoad(listOf(conversation)),
            selectedId = conversation.id, detail = MessagingLoad(conversation), messages = MessagingLoad(messages), peerOnline = true)
    }
}
