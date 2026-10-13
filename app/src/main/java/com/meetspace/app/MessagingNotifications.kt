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
class MessagingNotifications(private val context: Context) {
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    fun show(item: InboxNotification) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Чаты и встречи", NotificationManager.IMPORTANCE_DEFAULT))
        val intent = Intent(context, MainActivity::class.java).setAction(OPEN_INBOX).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val open = PendingIntent.getActivity(context, 707, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = when {
            item.conversationId != null -> "Новое событие в чате"
            item.type == "conference.soon" || item.type.contains("reminder") -> "Напоминание о встрече"
            item.type == "conference.invited" || item.type.contains("invitation") -> "Приглашение на встречу"
            else -> "Новое уведомление"
        }
        val notification = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_meetspace)
            .setContentTitle("MeetSpace").setContentText(text).setContentIntent(open).setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setOnlyAlertOnce(true).build()
        runCatching { manager.notify(TAG, 707, notification) }
    }
    fun clear() { manager.cancel(TAG, 707) }
    companion object {
        const val OPEN_INBOX = "com.meetspace.app.OPEN_INBOX"
        private const val CHANNEL = "meetspace-messaging"
        private const val TAG = "meetspace-messaging"
    }
}
