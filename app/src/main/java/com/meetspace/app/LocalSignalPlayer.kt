package com.meetspace.app

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.meetspace.app.ui.SpeakerPreference

/** App-owned sound with bounded lifetime. Never changes global volume, routing or quiet mode. */
internal class LocalSignalPlayer(private val context: Context) : AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var generation = 0
    private var finish: Runnable? = null
    fun play(uri: Uri, output: SpeakerPreference, volume: Int, usage: Int, durationMs: Long = 3_000,
        loop: Boolean = false, started: () -> Unit = {}, finished: (String?) -> Unit = {}) {
        close()
        val ticket = generation
        val audio = context.getSystemService(AudioManager::class.java)
        val notifications = context.getSystemService(NotificationManager::class.java)
        val stream = if (usage == AudioAttributes.USAGE_NOTIFICATION_RINGTONE) AudioManager.STREAM_RING else AudioManager.STREAM_NOTIFICATION
        fun allowed() = volume > 0 && audio.ringerMode == AudioManager.RINGER_MODE_NORMAL &&
            audio.getStreamVolume(stream) > 0 && notifications.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL
        if (!allowed()) { finished("Звук выключен в настройках приложения или Android"); return }
        try {
            val media = MediaPlayer(); player = media
            media.setAudioAttributes(AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            media.setDataSource(context, uri)
            if (android.os.Build.VERSION.SDK_INT >= 28) audioOutput(audio, output)?.let { check(media.setPreferredDevice(it)) }
            val gain = volume.coerceIn(0, 100) / 100f
            media.setVolume(gain, gain); media.isLooping = loop
            media.setOnPreparedListener {
                if (ticket != generation) return@setOnPreparedListener
                if (!allowed()) { close(); finished("Звук выключен в настройках Android"); return@setOnPreparedListener }
                runCatching { media.start() }.onSuccess { started() }.onFailure { close(); finished("Звуковой сигнал недоступен") }
            }
            media.setOnCompletionListener { if (ticket == generation) { close(); finished(null) } }
            media.setOnErrorListener { _, _, _ -> if (ticket == generation) { close(); finished("Звуковой сигнал недоступен") }; true }
            // Also bounds asynchronous preparation, and rechecks silent/DND during long incoming rings.
            val deadline = android.os.SystemClock.elapsedRealtime() + durationMs
            val guard = object : Runnable {
                override fun run() {
                    if (ticket != generation) return
                    if (!allowed() || android.os.SystemClock.elapsedRealtime() >= deadline) { close(); finished(null) }
                    else handler.postDelayed(this, 250)
                }
            }
            finish = guard; handler.post(guard); media.prepareAsync()
        } catch (_: Exception) { close(); finished("Звуковой сигнал недоступен") }
    }
    override fun close() {
        generation++
        finish?.let(handler::removeCallbacks); finish = null
        player?.let { it.setOnPreparedListener(null); it.setOnCompletionListener(null); it.setOnErrorListener(null); runCatching { it.release() } }
        player = null
    }
}

internal fun audioOutput(manager: AudioManager, preference: SpeakerPreference): AudioDeviceInfo? =
    manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { when (preference) {
        SpeakerPreference.DEFAULT -> false
        SpeakerPreference.SPEAKER -> it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        SpeakerPreference.HEADSET -> it.type in setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE)
        SpeakerPreference.BLUETOOTH -> it.type in setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_HEARING_AID)
    } }

internal fun localDevicePreferences(context: Context) = AccountDevicePreferencesStore(context.getSharedPreferences("account-device-preferences", Context.MODE_PRIVATE)).read()
