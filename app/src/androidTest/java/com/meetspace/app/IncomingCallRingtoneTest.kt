package com.meetspace.app

import android.app.NotificationManager
import android.media.AudioManager
import android.media.RingtoneManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.meetspace.core.model.ChatPeer
import com.meetspace.core.model.DirectCall
import java.time.Instant
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt in on a disposable emulator: checks Android's actual ring stream, not perceived loudness. */
class IncomingCallRingtoneTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun incomingRingRaisesSystemRingStreamAndAnswerRestoresIt() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyRingtoneVolume") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val audio = context.getSystemService(AudioManager::class.java)
        val notifications = context.getSystemService(NotificationManager::class.java)
        assumeTrue(audio.ringerMode == AudioManager.RINGER_MODE_NORMAL && !audio.isVolumeFixed)
        assumeTrue(notifications.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL)
        assumeTrue(RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE) != null)
        val previous = audio.getStreamVolume(AudioManager.STREAM_RING)
        val maximum = audio.getStreamMaxVolume(AudioManager.STREAM_RING)
        assumeTrue(maximum > 2)
        val call = DirectCall("test-call", "chat", null, "caller", "self", "remote", null,
            ChatPeer("caller", "Коллега"), ChatPeer("self", "Вы"), "ringing", 1, Instant.EPOCH, Instant.EPOCH.plusSeconds(30))
        var state by mutableStateOf(DirectCallSession(call = call, selfId = "self"))
        try {
            audio.setStreamVolume(AudioManager.STREAM_RING, 1, 0)
            compose.setContent { IncomingCallRingtone(state) }
            compose.waitUntil(5_000) { audio.getStreamVolume(AudioManager.STREAM_RING) == (maximum * 4 + 4) / 5 }
            compose.runOnIdle { state = state.copy(call = call.copy(status = "accepted")) }
            compose.waitUntil(5_000) { audio.getStreamVolume(AudioManager.STREAM_RING) == 1 }
            assertEquals(1, audio.getStreamVolume(AudioManager.STREAM_RING))
        } finally {
            compose.runOnIdle { state = DirectCallSession() }
            compose.waitForIdle()
            audio.setStreamVolume(AudioManager.STREAM_RING, previous, 0)
        }
    }
}
