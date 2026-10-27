package com.meetspace.app

import android.content.Context
import android.app.NotificationManager
import android.media.AudioDeviceInfo
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

/** Ring-only boost on the device speaker; quiet modes and manual volume changes always win. */
@Composable
internal fun IncomingCallRingtone(session: DirectCallSession) {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(session.call?.id, session.incoming, session.ending, lifecycle) {
        var ringtone: Ringtone? = null
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val notifications = context.getSystemService(NotificationManager::class.java)
        fun soundPolicyAllowsChange() = audio.ringerMode == AudioManager.RINGER_MODE_NORMAL && !audio.isVolumeFixed &&
            notifications.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL
        val volume = RingtoneVolume(
            current = { audio.getStreamVolume(AudioManager.STREAM_RING) },
            maximum = { audio.getStreamMaxVolume(AudioManager.STREAM_RING) },
            allowed = { soundPolicyAllowsChange() &&
                audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).none { it.type in externalRingtoneDevices } },
            // Connecting a headset must not leave our speaker boost behind after ringing.
            // Silent/vibrate/DND and a user's manual volume change still prevent restoration.
            restoreAllowed = ::soundPolicyAllowsChange,
            set = { audio.setStreamVolume(AudioManager.STREAM_RING, it, 0) },
        )
        fun stop() { runCatching { ringtone?.stop() }; ringtone = null; volume.close() }
        fun start() {
            if (!session.incoming || session.ending || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) || ringtone != null) return
            if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
            try {
                // A deliberately silent ringtone must remain silent.
                if (RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE) == null) return
                ringtone = RingtoneManager.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
                ringtone?.apply {
                    audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                    if (android.os.Build.VERSION.SDK_INT >= 28) { isLooping = true; setVolume(1f) }
                    volume.raise()
                    play()
                }
            } catch (_: Exception) { stop() }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) { Lifecycle.Event.ON_START -> start(); Lifecycle.Event.ON_STOP -> stop(); else -> Unit }
        }
        lifecycle.addObserver(observer); start()
        onDispose { lifecycle.removeObserver(observer); stop() }
    }
}

private val externalRingtoneDevices = buildSet {
    addAll(listOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
    if (android.os.Build.VERSION.SDK_INT >= 28) add(AudioDeviceInfo.TYPE_HEARING_AID)
    if (android.os.Build.VERSION.SDK_INT >= 31) { add(AudioDeviceInfo.TYPE_BLE_HEADSET); add(AudioDeviceInfo.TYPE_BLE_SPEAKER) }
}
