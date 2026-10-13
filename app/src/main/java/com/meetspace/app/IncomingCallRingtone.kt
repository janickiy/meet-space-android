package com.meetspace.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Uses the user's ringtone/volume, and stops immediately on answer, backgrounding or disposal. */
@Composable
internal fun IncomingCallRingtone(session: DirectCallSession) {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(session.call?.id, session.incoming, session.ending, lifecycle) {
        var ringtone: Ringtone? = null
        fun stop() { runCatching { ringtone?.stop() }; ringtone = null }
        fun start() {
            if (!session.incoming || session.ending || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) || ringtone != null) return
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
            ringtone = runCatching {
                RingtoneManager.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))?.apply {
                    audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                    if (android.os.Build.VERSION.SDK_INT >= 28) isLooping = true
                    play()
                }
            }.getOrNull()
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) { Lifecycle.Event.ON_START -> start(); Lifecycle.Event.ON_STOP -> stop(); else -> Unit }
        }
        lifecycle.addObserver(observer); start()
        onDispose { lifecycle.removeObserver(observer); stop() }
    }
}
