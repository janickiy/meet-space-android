package com.meetspace.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.meetspace.core.model.InboxNotification
import java.time.Instant
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Opt in on a disposable emulator. Observes OS posts/settings; does not assert perceived audibility. */
class MessagingNotificationsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val base = instrumentation.targetContext
    private val manager = base.getSystemService(NotificationManager::class.java)
    private val suffix = "-${UUID.randomUUID()}"
    private val preferenceNames = mutableSetOf<String>()
    private val isolatedContext = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolatedName = name + suffix
            preferenceNames += isolatedName
            return base.getSharedPreferences(isolatedName, mode)
        }
    }
    private val channelId = "meetspace-notification-sound-test$suffix"
    private var identity: String? = "https://sound-test.invalid|account-a"
    private var prepared = false
    private fun delivery() = MessagingNotifications(isolatedContext, { identity }, channelId)

    @Before fun prepareIsolatedDelivery() {
        val emulator = Build.HARDWARE in setOf("ranchu", "goldfish") || Build.MODEL.contains("Android SDK") || Build.MODEL.startsWith("sdk_")
        assumeTrue("Requires -e verifyNotificationSound true on a disposable emulator",
            emulator && InstrumentationRegistry.getArguments().getString("verifyNotificationSound") == "true")
        prepared = true
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(base.packageName, Manifest.permission.POST_NOTIFICATIONS)
        manager.cancel(TAG, ID)
        waitUntil("previous test notification is cleared") { posted() == null }
    }

    @After fun cleanUp() {
        if (!prepared) return
        manager.cancel(TAG, ID)
        manager.deleteNotificationChannel(channelId)
        preferenceNames.forEach { base.deleteSharedPreferences(it) }
    }

    @Test fun eachNewEventCanAlertWhileActivityIsResumed() {
        compose.runOnIdle { assertEquals(Lifecycle.State.RESUMED, compose.activity.lifecycle.currentState) }
        val notifications = delivery()
        notifications.show(event("first"))
        val first = awaitPost()
        assertSystemManagedAlert(first.notification)
        val channel = checkNotNull(manager.getNotificationChannel(channelId))
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
        assertNotNull(channel.sound)
        assertFalse(channel.canBypassDnd())
        // The platform rate-limits repeated updates. Separate genuine events so this checks
        // re-alert flags and posting rather than Android's burst/cooldown policy.
        SystemClock.sleep(1_100)
        notifications.show(event("second"))
        val second = awaitPostAfter(first.postTime)
        assertSystemManagedAlert(second.notification)
        assertEquals(1, manager.activeNotifications.count { it.tag == TAG && it.id == ID })
    }

    @Test fun duplicateEventDoesNotUpdateTheSystemNotification() {
        val notifications = delivery()
        val item = event("duplicate")
        notifications.show(item)
        val first = awaitPost()
        SystemClock.sleep(1_100)
        notifications.show(item)
        instrumentation.waitForIdleSync()
        SystemClock.sleep(200)
        val unchanged = checkNotNull(posted())
        assertEquals(first.postTime, unchanged.postTime)
        assertEquals(1, manager.activeNotifications.count { it.tag == TAG && it.id == ID })
    }

    @Test fun recreationAndClearingVisibleNotificationDoNotReplayAnEvent() {
        val item = event("persisted")
        delivery().show(item)
        awaitPost()
        delivery().clear()
        waitUntil("visible notification is cleared") { posted() == null }
        delivery().show(item)
        instrumentation.waitForIdleSync()
        SystemClock.sleep(200)
        assertNull(posted())
    }

    @Test fun deliveryHistoryIsScopedToTheAccount() {
        val item = event("shared-event-id")
        val notifications = delivery()
        notifications.show(item)
        awaitPost()
        notifications.clear()
        waitUntil("account A visible notification is cleared") { posted() == null }
        identity = "https://sound-test.invalid|account-b"
        SystemClock.sleep(1_100)
        notifications.show(item)
        awaitPost()
        notifications.clear()
        waitUntil("account B visible notification is cleared") { posted() == null }
        identity = "https://sound-test.invalid|account-a"
        notifications.show(item)
        instrumentation.waitForIdleSync()
        SystemClock.sleep(200)
        assertNull(posted())
    }

    @Test fun alreadyReadEventsAndMissingIdentityAreNotPosted() {
        val notifications = delivery()
        notifications.show(event("read").copy(readAt = Instant.now()))
        notifications.show(event("late-other-account"), "https://sound-test.invalid|previous-account")
        identity = null
        notifications.show(event("signed-out"))
        instrumentation.waitForIdleSync()
        SystemClock.sleep(200)
        assertNull(posted())
    }

    @Test fun userSilentLowImportanceChannelIsPreserved() {
        val audio = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        manager.createNotificationChannel(NotificationChannel(channelId, "Isolated silent notification fixture", NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, audio)
            enableVibration(false)
        })
        delivery().show(event("silent-channel"))
        val notification = awaitPost().notification
        val channel = checkNotNull(manager.getNotificationChannel(channelId))
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertNull(channel.sound)
        assertFalse(channel.shouldVibrate())
        assertFalse(channel.canBypassDnd())
        assertSystemManagedAlert(notification)
    }

    @Test fun postingInSilentVibrateAndDndDoesNotAlterDeviceSoundSettings() {
        val audio = base.getSystemService(AudioManager::class.java)
        assumeTrue("This fixture needs an adjustable emulator audio service", !audio.isVolumeFixed)
        val originalMode = audio.ringerMode
        val originalNotificationVolume = audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
        val originalRingVolume = audio.getStreamVolume(AudioManager.STREAM_RING)
        val originalFilter = manager.currentInterruptionFilter
        val originalAccess = manager.isNotificationPolicyAccessGranted
        val restoreFilter = when (originalFilter) {
            NotificationManager.INTERRUPTION_FILTER_ALL -> "all"
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "priority"
            NotificationManager.INTERRUPTION_FILTER_NONE -> "none"
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> "alarms"
            else -> null
        }
        assumeTrue("Cannot safely restore an unknown DND filter", restoreFilter != null)
        try {
            if (!originalAccess) shell("cmd notification allow_dnd ${base.packageName}")
            waitUntil("temporary test DND access is granted") { manager.isNotificationPolicyAccessGranted }
            val notifications = delivery()
            var previousPost: Long? = null
            val cases = listOf(
                Triple(AudioManager.RINGER_MODE_VIBRATE, "all", NotificationManager.INTERRUPTION_FILTER_ALL),
                Triple(AudioManager.RINGER_MODE_SILENT, "none", NotificationManager.INTERRUPTION_FILTER_NONE),
            )
            fun soundState() = Triple(audio.ringerMode, audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION), manager.currentInterruptionFilter)
            for ((mode, filter, interruptionFilter) in cases) {
                if (previousPost != null) SystemClock.sleep(1_100)
                shell("cmd notification set_dnd all")
                audio.ringerMode = mode
                shell("cmd notification set_dnd $filter")
                // DND changes ringer mode asynchronously on Android. Establish the requested
                // compatible combination and wait for a stable baseline before delivery.
                var last = soundState()
                var stableSince = SystemClock.uptimeMillis()
                waitUntil("ringer mode, volume and DND settle before posting") {
                    val current = soundState()
                    if (current != last) { last = current; stableSince = SystemClock.uptimeMillis() }
                    current.first == mode && current.third == interruptionFilter && SystemClock.uptimeMillis() - stableSince >= 500
                }
                val baseline = soundState()
                notifications.show(event("silent-dnd-$mode"))
                val post = previousPost?.let(::awaitPostAfter) ?: awaitPost()
                previousPost = post.postTime
                assertEquals(baseline, soundState())
                assertFalse(checkNotNull(manager.getNotificationChannel(channelId)).canBypassDnd())
                assertSystemManagedAlert(post.notification)
            }
        } finally {
            // This opt-in fixture owns the disposable emulator, but still restores every
            // global setting it touches, including policy access that the app did not have.
            try {
                audio.ringerMode = originalMode
                shell("cmd notification set_dnd $restoreFilter")
                if (audio.getStreamVolume(AudioManager.STREAM_RING) != originalRingVolume)
                    audio.setStreamVolume(AudioManager.STREAM_RING, originalRingVolume, 0)
                if (audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION) != originalNotificationVolume)
                    audio.setStreamVolume(AudioManager.STREAM_NOTIFICATION, originalNotificationVolume, 0)
                audio.ringerMode = originalMode
                shell("cmd notification set_dnd $restoreFilter")
            } finally {
                if (!originalAccess) shell("cmd notification disallow_dnd ${base.packageName}")
            }
        }
        waitUntil("original sound and DND settings are restored") {
            audio.ringerMode == originalMode && manager.currentInterruptionFilter == originalFilter &&
                manager.isNotificationPolicyAccessGranted == originalAccess &&
                audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION) == originalNotificationVolume &&
                audio.getStreamVolume(AudioManager.STREAM_RING) == originalRingVolume
        }
    }

    private fun assertSystemManagedAlert(notification: Notification) {
        assertEquals(channelId, notification.channelId)
        assertEquals(0, notification.flags and Notification.FLAG_ONLY_ALERT_ONCE)
        assertEquals(0, notification.flags and Notification.FLAG_INSISTENT)
        assertEquals(0, notification.defaults and (Notification.DEFAULT_SOUND or Notification.DEFAULT_VIBRATE))
        assertNull(notification.sound)
        assertNull(notification.vibrate)
        assertNull(notification.fullScreenIntent)
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertNotNull(notification.contentIntent)
    }

    private fun event(id: String) = InboxNotification(id + suffix, "chat.message", Instant.now(), conversationId = "chat")
    private fun posted(): StatusBarNotification? = manager.activeNotifications.firstOrNull { it.tag == TAG && it.id == ID }
    private fun awaitPost(): StatusBarNotification {
        waitUntil("system notification is posted") { posted() != null }
        return checkNotNull(posted())
    }
    private fun awaitPostAfter(previous: Long): StatusBarNotification {
        waitUntil("new event updates the system notification") { posted()?.postTime?.let { it > previous } == true }
        return checkNotNull(posted())
    }
    private fun waitUntil(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(25)
        assertTrue(description, condition())
    }
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    private companion object { const val TAG = "meetspace-messaging"; const val ID = 707 }
}
