package com.meetspace.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.meetspace.core.model.InboxNotification

/** Generic lock-screen content. The inbox rechecks permissions; no signed URLs or message text in intents. */
class MessagingNotifications internal constructor(private val context: Context, private val identity: () -> String?,
    private val channelId: String = CHANNEL, private val foreground: () -> Boolean = { false }) {
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val deliveredPreferences = context.getSharedPreferences("meetspace-notification-delivery", Context.MODE_PRIVATE)
    private val delivered = NotificationDeliveryHistory(deliveredPreferences.getString("delivered", "").orEmpty())
    private val soundSettings = NotificationSoundSettings(context)
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var soundPreview: NotificationSoundPreview? = null

    fun show(item: InboxNotification) { show(item, identity()) }

    @Synchronized
    internal fun show(item: InboxNotification, expectedScope: String?) {
        val scope = expectedScope ?: return
        if (identity() != scope) return
        if (item.readAt != null || item.id.isBlank() || delivered.contains(scope, item.id)) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        manager.createNotificationChannel(NotificationChannel(channelId, "Чаты и встречи", NotificationManager.IMPORTANCE_DEFAULT))
        if (manager.getNotificationChannel(channelId)?.importance == NotificationManager.IMPORTANCE_NONE) return
        val intent = Intent(context, MainActivity::class.java).setAction(OPEN_INBOX).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val open = PendingIntent.getActivity(context, 707, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = when {
            item.conversationId != null -> "Новое событие в чате"
            item.type == "conference.soon" || item.type.contains("reminder") -> "Напоминание о встрече"
            item.type == "conference.invited" || item.type.contains("invitation") -> "Приглашение на встречу"
            else -> "Новое уведомление"
        }
        val devices = localDevicePreferences(context)
        val appSound = foreground() && (devices.signalVolume != 100 || devices.signalOutput != com.meetspace.app.ui.SpeakerPreference.DEFAULT)
        val notification = NotificationCompat.Builder(context, channelId).setSmallIcon(R.drawable.ic_meetspace)
            .setContentTitle("MeetSpace").setContentText(text).setContentIntent(open).setAutoCancel(true)
            // A custom foreground gain/route uses the same channel policy with one app-owned tone.
            // Background delivery retains Android's channel, volume and DND policy.
            // New events may alert again; a duplicate event is rejected by the scoped journal.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setOnlyAlertOnce(false)
            .setSilent(!soundSettings.enabled(scope) || devices.signalVolume == 0 || appSound).build()
        if (identity() != scope) return
        runCatching {
            manager.notify(TAG, 707, notification)
            delivered.record(scope, item.id)
            deliveredPreferences.edit().putString("delivered", delivered.snapshot()).apply()
            if (appSound) handler.post {
                if (identity() == scope && foreground()) {
                    soundPreview?.close()
                    soundPreview = NotificationSoundPreview(context, channelId).also { it.play(soundSettings.enabled(scope)) }
                }
            }
        }
    }
    @Synchronized
    fun clear() { manager.cancel(TAG, 707); stopSound() }
    internal fun stopSound() { handler.post { soundPreview?.close(); soundPreview = null } }
    companion object {
        const val OPEN_INBOX = "com.meetspace.app.OPEN_INBOX"
        internal const val CHANNEL = "meetspace-messaging"
        private const val TAG = "meetspace-messaging"
    }
}
