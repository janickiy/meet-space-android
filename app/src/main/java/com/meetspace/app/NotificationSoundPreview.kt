package com.meetspace.app

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationManagerCompat

/** A bounded notification-tone preview; no recorder, volume changes or new notification channel. */
internal class NotificationSoundPreview(private val context: Context, private val channelId: String = MessagingNotifications.CHANNEL) : AutoCloseable {
    var playing by mutableStateOf(false); private set
    var status by mutableStateOf(""); private set
    private val handler = Handler(Looper.getMainLooper())
    private var tone: Ringtone? = null
    private val finish = Runnable { stop("Проверка завершена") }

    fun play(enabled: Boolean) {
        stop("")
        val manager = context.getSystemService(NotificationManager::class.java)
        val audio = context.getSystemService(AudioManager::class.java)
        val channel = manager.getNotificationChannel(channelId)
        val uri = if (channel != null) channel.sound else RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val hasSound = uri != null && (!RingtoneManager.isDefault(uri) ||
            RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.getDefaultType(uri)) != null)
        if (!notificationSoundAllowed(enabled, NotificationManagerCompat.from(context).areNotificationsEnabled(),
                audio.ringerMode, manager.currentInterruptionFilter, audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION),
                channel?.importance ?: NotificationManager.IMPORTANCE_DEFAULT, hasSound)) {
            status = if (!enabled) "Включите звуковой сигнал для проверки" else "Звук уведомлений выключен в настройках Android"
            return
        }
        try {
            val ringtone = RingtoneManager.getRingtone(context, uri) ?: error("Notification tone unavailable")
            tone = ringtone
            ringtone.audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            ringtone.play()
            playing = true; status = "Проверяем звук уведомления…"
            handler.postDelayed(finish, 3_000)
        } catch (_: Exception) { stop("Звуковой сигнал недоступен") }
    }

    fun stop(message: String = "") {
        handler.removeCallbacks(finish)
        tone?.let { runCatching { it.stop() } }; tone = null
        playing = false; status = message
    }
    override fun close() = stop()
}
