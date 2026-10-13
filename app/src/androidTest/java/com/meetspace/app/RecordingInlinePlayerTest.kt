package com.meetspace.app

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.meetspace.app.ui.RecordingsActions
import com.meetspace.app.ui.RecordingsLoad
import com.meetspace.app.ui.RecordingsUiState
import com.meetspace.app.ui.RecordingsWorkspace
import com.meetspace.core.data.RecordingAccess
import com.meetspace.core.data.RecordingStream
import com.meetspace.core.designsystem.MeetSpaceTheme
import com.meetspace.core.model.Recording
import com.meetspace.core.model.RecordingFile
import com.meetspace.core.model.ThemeMode
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real Media3 playback through the production data source; all media is generated locally. */
@androidx.annotation.OptIn(UnstableApi::class)
class RecordingInlinePlayerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun inlineAudioBecomesReadyInsideWorkspaceAndClosingDisposesNativeResources() {
        val access = LocalWaveAccess()
        val playback = RecordingPlayback(access, "Локальная проверка аудио").apply { playWhenReady = false }
        var visible by mutableStateOf(true)
        var closes = 0
        val recording = Recording("local-record", "local-meeting", "audio", "ready", Instant.EPOCH,
            durationSec = 30, files = listOf(access.file))
        compose.setContent {
            MeetSpaceTheme(ThemeMode.DARK) {
                RecordingsWorkspace(
                    state = RecordingsUiState(visible = true, meetingId = recording.conferenceId,
                        detailId = recording.id, detail = RecordingsLoad(recording)),
                    actions = RecordingsActions(), onWorkspaceTab = {},
                    playingFileId = if (visible) access.file.id else null,
                    inlinePlayer = if (visible) ({
                        RecordingPlayer(playback, close = { closes++; visible = false }, retry = {}, inline = true)
                    }) else null,
                )
            }
        }
        try {
            compose.onNodeWithTag("recording-detail").performScrollToNode(hasTestTag("recording-player-screen"))
            awaitReady()
            compose.onNodeWithTag("recordings-workspace").assertIsDisplayed()
            compose.onNodeWithTag("recording-detail").assertIsDisplayed()
            compose.onNodeWithTag("bottom-navigation").assertIsDisplayed()
            compose.onNodeWithTag("recording-player-loading").assertDoesNotExist()
            compose.onNodeWithTag("recording-player-error").assertDoesNotExist()
            lateinit var native: ExoPlayer
            lateinit var nativeView: PlayerView
            compose.runOnIdle {
                nativeView = requireNotNull(playerView(compose.activity.window.decorView))
                native = nativeView.player as ExoPlayer
                assertEquals(Player.STATE_READY, native.playbackState)
                assertTrue("The WAV extractor reports the generated 30-second duration", native.duration in 29_900..30_100)
                assertFalse(native.playWhenReady)
                assertTrue("The production RecordingDataSource opened local bytes", access.openCalls.get() > 0)
                assertTrue("ExoPlayer consumed the WAV payload", access.bytesRead.get() > 44)
            }

            compose.onNodeWithTag("recording-player-close").performClick()
            awaitReleased(native, nativeView, access)
            compose.runOnIdle { assertEquals(1, closes) }
            compose.onNodeWithTag("recording-player-screen").assertDoesNotExist()
            compose.onNodeWithTag("recordings-workspace").assertIsDisplayed()
            compose.onNodeWithTag("bottom-navigation").assertIsDisplayed()
            compose.onNodeWithTag("recording-play-${access.file.id}").assertExists()
            assertFalse("Composition releases streams; the ViewModel owns the access lease", access.closed.get())
        } finally {
            compose.runOnIdle { visible = false }
            compose.waitForIdle()
            access.close()
        }
    }

    @Test fun lifecycleStopPausesPlaybackAndRemountRetainsExplicitPauseAndSeekPosition() {
        val access = LocalWaveAccess()
        val playback = RecordingPlayback(access, "Локальная проверка восстановления")
        var visible by mutableStateOf(true)
        compose.setContent {
            MeetSpaceTheme(ThemeMode.LIGHT) {
                Column(Modifier.fillMaxSize()) {
                    Text("Запись остаётся внутри страницы")
                    if (visible) RecordingPlayer(playback, close = { visible = false }, retry = {}, inline = true)
                    Text("Материалы встречи")
                }
            }
        }
        try {
            awaitReady()
            compose.waitUntil(10_000) { playerValue { it.isPlaying } == true }
            lateinit var native: ExoPlayer
            lateinit var nativeView: PlayerView
            compose.runOnIdle {
                nativeView = requireNotNull(playerView(compose.activity.window.decorView))
                native = nativeView.player as ExoPlayer
                native.seekTo(7_000)
            }
            compose.waitUntil(10_000) { playerValue { it.playbackState == Player.STATE_READY && it.currentPosition >= 6_900 } == true }

            // Exercise the actual Activity lifecycle, not a simulated Player.Listener callback.
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            compose.runOnUiThread {
                assertFalse("Background playback must pause", native.playWhenReady)
                assertFalse(playback.playWhenReady)
                assertTrue("ON_STOP saves the current position", playback.positionMs >= 6_900)
            }
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.waitForIdle()
            compose.runOnIdle {
                assertFalse("Returning to the foreground must not silently resume", native.playWhenReady)
                native.seekTo(12_000)
                native.pause()
            }
            compose.waitUntil(10_000) { playerValue { it.playbackState == Player.STATE_READY && it.currentPosition in 11_900..12_100 } == true }
            compose.runOnIdle { visible = false }
            awaitReleased(native, nativeView, access)
            val savedPosition = playback.positionMs
            assertTrue("Disposal stores the explicit seek position", savedPosition in 11_900..12_100)
            assertFalse(playback.playWhenReady)
            assertFalse("A temporary composition removal does not revoke the ViewModel lease", access.closed.get())
            val opensBeforeRemount = access.openCalls.get()

            compose.runOnIdle { visible = true }
            awaitReady()
            compose.runOnIdle {
                val restored = requireNotNull(playerView(compose.activity.window.decorView)).player as ExoPlayer
                assertNotSame(native, restored)
                assertFalse("Explicit pause survives native-player recreation", restored.playWhenReady)
                assertFalse(restored.isPlaying)
                assertTrue("Seek position survives native-player recreation", kotlin.math.abs(restored.currentPosition - savedPosition) <= 100)
                assertTrue("The new player reopened the same local capability", access.openCalls.get() > opensBeforeRemount)
            }
        } finally {
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.runOnIdle { visible = false }
            compose.waitForIdle()
            access.close()
        }
    }

    private fun awaitReady() {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("recording-player-ready").fetchSemanticsNodes().isNotEmpty() &&
                playerValue { it.playbackState == Player.STATE_READY } == true
        }
    }

    private fun awaitReleased(player: ExoPlayer, view: PlayerView, access: LocalWaveAccess) {
        compose.waitUntil(10_000) {
            var released = false
            compose.runOnUiThread { released = player.isReleased && view.player == null && !view.keepScreenOn }
            released && access.activeStreams.isEmpty()
        }
    }

    private fun <T> playerValue(read: (ExoPlayer) -> T): T? {
        var value: T? = null
        compose.runOnUiThread { (playerView(compose.activity.window.decorView)?.player as? ExoPlayer)?.let { value = read(it) } }
        return value
    }

    private fun playerView(view: View): PlayerView? = when (view) {
        is PlayerView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { playerView(view.getChildAt(it)) }
        else -> null
    }

    /** Silent 8-kHz, 16-bit mono PCM: no assets, decoder fixture downloads, or network. */
    private class LocalWaveAccess : RecordingAccess {
        private val bytes = ByteBuffer.allocate(44 + 8_000 * 2 * 30).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(capacity() - 8)
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII)); putInt(16)
            putShort(1); putShort(1); putInt(8_000); putInt(16_000); putShort(2); putShort(16)
            put("data".toByteArray(Charsets.US_ASCII)); putInt(capacity() - 44)
        }.array()
        override val file = RecordingFile("local-wave", "final_audio", "local-playback.wav", "audio/wav",
            sizeBytes = bytes.size.toLong(), durationSec = 30)
        val closed = AtomicBoolean(false)
        val openCalls = AtomicInteger(0)
        val bytesRead = AtomicLong(0)
        val activeStreams = CopyOnWriteArraySet<ByteArrayInputStream>()

        override fun open(position: Long, length: Long?): RecordingStream {
            check(!closed.get())
            require(position in 0..bytes.size.toLong())
            require(length == null || length >= 0)
            val count = minOf(length ?: Long.MAX_VALUE, bytes.size - position).toInt()
            val stream = object : ByteArrayInputStream(bytes, position.toInt(), count) {
                private val streamClosed = AtomicBoolean(false)
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                    super.read(buffer, offset, length).also { if (it > 0) bytesRead.addAndGet(it.toLong()) }
                override fun close() {
                    if (streamClosed.compareAndSet(false, true)) { super.close(); activeStreams.remove(this) }
                }
            }
            activeStreams += stream
            openCalls.incrementAndGet()
            return RecordingStream(stream, count.toLong())
        }

        override suspend fun copyTo(destination: OutputStream, onProgress: (Long) -> Unit): Long {
            check(!closed.get())
            destination.write(bytes)
            onProgress(bytes.size.toLong())
            return bytes.size.toLong()
        }

        override fun close() { if (closed.compareAndSet(false, true)) activeStreams.toList().forEach { it.close() } }
    }
}
