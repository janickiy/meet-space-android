package com.meetspace.app

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.work.*
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.meetspace.core.data.FoundationRepository
import com.meetspace.core.data.MessagingRepository
import com.meetspace.core.model.AppFailure
import com.meetspace.core.model.FailureKind
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class PushState(val configured: Boolean, val enabled: Boolean = false, val busy: Boolean = false, val message: String? = null)

/** Explicit opt-in, selected-account binding and bounded cleanup. Never persists the FCM token. */
class PushController(private val context: Context, private val foundation: FoundationRepository,
    private val messaging: MessagingRepository, private val notifications: MessagingNotifications) {
    private val prefs = context.getSharedPreferences("meetspace-push-binding", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val generation = AtomicLong()
    private val deliveryLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutable = MutableStateFlow(PushState(BuildConfig.FCM_CONFIGURED,
        message = if (BuildConfig.FCM_CONFIGURED) null else "Фоновые push ещё не настроены"))
    val state = mutable.asStateFlow()

    suspend fun enable() {
        val epoch = generation.get()
        mutex.withLock {
        if (epoch != generation.get()) return@withLock
        if (!BuildConfig.FCM_CONFIGURED) { mutable.value = PushState(false, message = "Фоновые push ещё не настроены"); return@withLock }
        val binding = identity() ?: run { mutable.value = PushState(true, message = "Сначала войдите в MeetSpace."); return@withLock }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            mutable.value = PushState(true, message = "Разрешите уведомления Android."); return@withLock
        }
        mutable.value = PushState(true, busy = true)
        try {
            withTimeout(20_000) {
                if (messaging.integrationCapabilities().push != "http") throw AppFailure(FailureKind.UNAVAILABLE, "На сервере не настроен push-шлюз.")
                if (identity() != binding || generation.get() != epoch) throw CancellationException()
                val firebase = firebase() ?: throw AppFailure(FailureKind.UNAVAILABLE, "Конфигурация Firebase недоступна.")
                if (prefs.getBoolean("needsReset", false) || prefs.getString("scope", null)?.let { it != binding } == true) {
                    firebase.isAutoInitEnabled = false
                    if (prefs.getString("scope", null) == binding) {
                        listOfNotNull(prefs.getString("device", null), prefs.getString("previousDevice", null)).distinct()
                            .forEach { messaging.revokePushDevice(it) }
                    }
                    firebase.deleteToken().awaitPush()
                    if (generation.get() != epoch) throw CancellationException()
                    prefs.edit().clear().commit()
                }
                if (generation.get() != epoch) throw CancellationException()
                prefs.edit().putString("scope", binding).putBoolean("consent", true).commit()
                val token = firebase.token.awaitPush()
                if (generation.get() != epoch) throw CancellationException()
                register(binding, token)
                if (identity() != binding || generation.get() != epoch) throw CancellationException()
                val preferences = messaging.notificationPreferences()
                if (!preferences.push) messaging.saveNotificationPreferences(preferences.copy(push = true))
                synchronized(deliveryLock) {
                    if (generation.get() != epoch || !authorized(binding)) throw CancellationException()
                    prefs.edit().putBoolean("registered", true).commit()
                    firebase.isAutoInitEnabled = true
                    mutable.value = PushState(true, enabled = true)
                }
            }
        } catch (cancelled: CancellationException) {
            failEnable(epoch, "Push не подтвердился вовремя. Повторите подключение.")
            if (cancelled !is TimeoutCancellationException) throw cancelled
        } catch (failure: Exception) {
            failEnable(epoch, (failure as? AppFailure)?.message ?: "Не удалось подключить push. Повторите позже.")
        }
        }
    }
    private fun failEnable(epoch: Long, message: String) {
        synchronized(deliveryLock) {
            if (epoch != generation.get()) return
            generation.incrementAndGet()
            prefs.edit().putBoolean("consent", false).putBoolean("registered", false).putBoolean("needsReset", true).commit()
            runCatching { firebase()?.isAutoInitEnabled = false }
            notifications.clear()
            WorkManager.getInstance(context).cancelAllWorkByTag(WORK_TAG)
            mutable.value = PushState(BuildConfig.FCM_CONFIGURED, message = message)
        }
    }

    /** Call after the foundation has completed restore/login, never before initial restore. */
    suspend fun reconcile() {
        if (!BuildConfig.FCM_CONFIGURED) return
        val current = identity()
        val bound = prefs.getString("scope", null)
        if (bound != null && bound != current) { disable(); return }
        if (current != null && prefs.getBoolean("consent", false) && prefs.getBoolean("registered", false)) {
            mutable.value = PushState(true, enabled = true)
            // A network outage must not erase previously confirmed consent on every foreground transition.
            scope.launch { runCatching { withTimeout(10_000) { firebase()?.token?.awaitPush()?.let(::onTokenRotated) } } }
        } else if (prefs.getBoolean("consent", false)) disable()
    }

    /** Returns a safe warning on incomplete remote cleanup; local notification delivery is disabled first. */
    suspend fun disable(): String? {
        val oldScope: String?
        val oldDevices: List<String>
        synchronized(deliveryLock) {
            generation.incrementAndGet()
            oldScope = prefs.getString("scope", null)
            oldDevices = listOfNotNull(prefs.getString("device", null), prefs.getString("previousDevice", null)).distinct()
            prefs.edit().clear().putBoolean("needsReset", true).commit()
            notifications.clear()
            WorkManager.getInstance(context).cancelAllWorkByTag(WORK_TAG)
            mutable.value = PushState(BuildConfig.FCM_CONFIGURED, message = if (BuildConfig.FCM_CONFIGURED) null else "Фоновые push ещё не настроены")
        }
        if (!BuildConfig.FCM_CONFIGURED) return null
        runCatching { firebase()?.isAutoInitEnabled = false }
        var complete = true
        val result = withTimeoutOrNull(8_000) {
            mutex.withLock {
                val firebase = firebase()
                firebase?.isAutoInitEnabled = false
                if (oldScope == identity()) for (oldDevice in oldDevices) {
                    try { withTimeout(2_000) { messaging.revokePushDevice(oldDevice) } } catch (e: Exception) { if (e is CancellationException && e !is TimeoutCancellationException) throw e; complete = false }
                }
                try { firebase?.deleteToken()?.awaitPush(); prefs.edit().remove("needsReset").commit() }
                catch (e: Exception) { if (e is CancellationException) throw e; complete = false }
            }
            true
        }
        if (result == null) complete = false
        val warning = if (complete) null else "Push отключён на устройстве. Серверный отзыв или сброс токена не подтверждён; повторное включение потребует связи."
        mutable.value = state.value.copy(message = warning ?: state.value.message)
        return warning
    }
    suspend fun beforeIdentityChange(): String? = disable()

    fun onTokenRotated(token: String) {
        if (!BuildConfig.FCM_CONFIGURED || !prefs.getBoolean("consent", false) || !prefs.getBoolean("registered", false)) return
        val expected = prefs.getString("scope", null) ?: return
        scope.launch {
            mutex.withLock {
                if (!prefs.getBoolean("consent", false) || prefs.getString("scope", null) != expected) return@withLock
                try { withTimeout(15_000) {
                    if (foundation.state.value.session == null) foundation.restore()
                    if (authorized(expected)) register(expected, token)
                } }
                catch (e: Exception) { if (e is CancellationException && e !is TimeoutCancellationException) throw e
                    mutable.value = PushState(true, message = "Обновление push-токена не подтверждено. Переподключите push.") }
            }
        }
    }
    private suspend fun register(expected: String, token: String) {
        if (!authorized(expected)) throw CancellationException()
        if (messaging.integrationCapabilities().push != "http") throw AppFailure(FailureKind.UNAVAILABLE, "Push-шлюз недоступен.")
        val old = prefs.getString("device", null)
        val device = messaging.registerPushDevice(token)
        if (!authorized(expected)) {
            if (identity() == expected) runCatching { messaging.revokePushDevice(device.id) }
            throw CancellationException()
        }
        // Track both IDs until revocation succeeds, so failed cleanup is retried without losing the new ID.
        val previous = prefs.getString("previousDevice", null) ?: old?.takeIf { it != device.id }
        prefs.edit().putString("device", device.id).putString("previousDevice", previous).commit()
        if (previous != null) {
            messaging.revokePushDevice(previous)
            prefs.edit().remove("previousDevice").commit()
        }
    }
    private fun firebase(): FirebaseMessaging? {
        if (!BuildConfig.FCM_CONFIGURED) return null
        if (FirebaseApp.getApps(context).isEmpty() && FirebaseApp.initializeApp(context) == null) return null
        return FirebaseMessaging.getInstance()
    }
    internal fun identity(): String? {
        val value = foundation.state.value
        val origin = value.selectedProfile?.origin ?: return null
        val user = value.session?.user?.id ?: return null
        return "$origin|$user"
    }
    private fun authorized(expected: String) = BuildConfig.FCM_CONFIGURED && prefs.getBoolean("consent", false) &&
        prefs.getString("scope", null) == expected && identity() == expected

    internal fun enqueue(raw: String?) {
        if (!BuildConfig.FCM_CONFIGURED || !prefs.getBoolean("consent", false) || !prefs.getBoolean("registered", false)) return
        val binding = prefs.getString("scope", null) ?: return
        val origin = binding.substringBeforeLast('|')
        val hint = raw?.let { PushHint.parse(origin, it) ?: return }
        val data = workDataOf("scope" to binding, "kind" to hint?.kind, "id" to hint?.id)
        val work = OneTimeWorkRequestBuilder<PushSyncWorker>().setInputData(data)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(WORK_TAG).build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_TAG, ExistingWorkPolicy.REPLACE, work)
    }
    internal suspend fun verifyAndShow(expected: String, hint: PushHint?) {
        if (!BuildConfig.FCM_CONFIGURED || !prefs.getBoolean("consent", false) || !prefs.getBoolean("registered", false) || prefs.getString("scope", null) != expected) return
        // A cold worker restores only the already selected account, with the normal encrypted credential store.
        if (foundation.state.value.session == null) foundation.restore()
        if (!authorized(expected)) return
        if (hint?.kind == "conversations") messaging.conversation(hint.id)
        if (hint?.kind == "conferences") (foundation as com.meetspace.core.data.MeetingsRepository).conference(hint.id)
        val page = messaging.notifications()
        val item = page.items.firstOrNull { it.readAt == null && (hint == null ||
            (hint.kind == "conversations" && it.conversationId == hint.id) || (hint.kind == "conferences" && it.conferenceId == hint.id)) } ?: return
        synchronized(deliveryLock) {
            if (!authorized(expected) || !prefs.getBoolean("registered", false)) return
            val delivered = prefs.getStringSet("delivered", emptySet()).orEmpty()
            if (item.id in delivered) return
            notifications.show(item)
            prefs.edit().putStringSet("delivered", (delivered.toList().takeLast(99) + item.id).toSet()).commit()
        }
    }
    companion object { internal const val WORK_TAG = "meetspace-scoped-push" }
}

private suspend fun <T> Task<T>.awaitPush(): T = suspendCancellableCoroutine { c ->
    addOnSuccessListener { value -> if (c.isActive) c.resume(value) }
    addOnFailureListener { if (c.isActive) c.resumeWithException(it) }
    addOnCanceledListener { c.cancel() }
}
