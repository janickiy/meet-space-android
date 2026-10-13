package com.meetspace.app

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/** Consent data is consumed once and never persisted or delivered after process restart. */
class ScreenShareService : Service() {
    private var started = false
    private var conferenceId: String? = null
    private var directCallId: String? = null
    private val engine get() = (application as MeetSpaceApplication).callEngine
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { engine.stopScreen(conferenceId, directCallId); stopSelf(); return START_NOT_STICKY }
        if (started) return START_NOT_STICKY
        val consent = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(CONSENT, Intent::class.java)
            else @Suppress("DEPRECATION") intent?.getParcelableExtra<Intent>(CONSENT)
        val targetConference = intent?.getStringExtra(CONFERENCE)
        val targetDirectCall = intent?.getStringExtra(DIRECT_CALL)
        val current = engine.state.value
        if (consent == null || targetConference.isNullOrBlank() || current.conferenceId != targetConference ||
            current.directCallId != targetDirectCall || current.phase != com.meetspace.core.media.CallPhase.CONNECTED) {
            stopSelf(); return START_NOT_STICKY
        }
        conferenceId = targetConference; directCallId = targetDirectCall
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Демонстрация экрана", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 600, Intent(this, ScreenShareService::class.java).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 601, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_meetspace)
            .setContentTitle("MeetSpace • экран виден участникам").setContentText("Нажмите «Остановить», чтобы завершить демонстрацию.")
            .setContentIntent(open).setOngoing(true).setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Остановить", stop).build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(606, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            else startForeground(606, notification)
            started = true
            engine.startScreen(consent, conferenceId, directCallId)
        } catch (_: Exception) {
            engine.message("Android не разрешил демонстрацию. Откройте приложение и повторите.")
            stopSelf()
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        if (started) engine.stopScreen(conferenceId, directCallId)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    override fun onTaskRemoved(rootIntent: Intent?) { engine.leave(); stopSelf() }
    companion object {
        const val CONSENT = "projection-consent"
        const val CONFERENCE = "projection-conference"
        const val DIRECT_CALL = "projection-direct-call"
        private const val STOP = "com.meetspace.app.STOP_SCREEN"
        private const val CHANNEL = "meetspace-screen"
    }
}
