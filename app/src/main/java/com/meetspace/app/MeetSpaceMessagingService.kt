package com.meetspace.app

import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.meetspace.core.model.AppFailure
import com.meetspace.core.model.FailureKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/** Only data-only hints are accepted. Raw notification content must never reach the system tray. */
class MeetSpaceMessagingService : FirebaseMessagingService() {
    // Pinned SDK exposes this dispatch hook; block its automatic background notification path.
    override fun handleIntent(intent: Intent) {
        if (intent.extras?.keySet()?.any { it.startsWith("gcm.n.") || it.startsWith("gcm.notification.") } == true) return
        super.handleIntent(intent)
    }
    override fun onMessageReceived(message: RemoteMessage) {
        if (message.notification != null || message.data.size > 16 || message.data.values.sumOf { it.length } > 4096) return
        val url = message.data["url"] ?: return
        (application as MeetSpaceApplication).pushController.enqueue(url)
    }
    override fun onDeletedMessages() { (application as MeetSpaceApplication).pushController.enqueue(null) }
    override fun onNewToken(token: String) { (application as MeetSpaceApplication).pushController.onTokenRotated(token) }
}

class PushSyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val expected = inputData.getString("scope") ?: return Result.success()
        val kind = inputData.getString("kind"); val id = inputData.getString("id")
        val hint = if (kind != null && id != null) PushHint.parse(expected.substringBeforeLast('|'),
            expected.substringBeforeLast('|') + "/$kind/$id") ?: return Result.success() else null
        return try {
            withTimeout(25_000) { (applicationContext as MeetSpaceApplication).pushController.verifyAndShow(expected, hint) }
            Result.success()
        } catch (cancelled: CancellationException) {
            if (cancelled !is TimeoutCancellationException) throw cancelled
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        } catch (failure: Exception) {
            if (failure is AppFailure && failure.kind in setOf(FailureKind.NETWORK, FailureKind.TIMEOUT, FailureKind.UNAVAILABLE) && runAttemptCount < 3)
                Result.retry() else Result.failure()
        }
    }
}
