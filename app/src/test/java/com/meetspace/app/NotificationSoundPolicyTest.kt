package com.meetspace.app

import android.app.NotificationManager
import android.media.AudioManager
import org.junit.Assert.*
import org.junit.Test

class NotificationSoundPolicyTest {
    private fun allowed(enabled: Boolean = true, permission: Boolean = true, mode: Int = AudioManager.RINGER_MODE_NORMAL,
        filter: Int = NotificationManager.INTERRUPTION_FILTER_ALL, volume: Int = 5,
        importance: Int = NotificationManager.IMPORTANCE_DEFAULT, sound: Boolean = true) =
        notificationSoundAllowed(enabled, permission, mode, filter, volume, importance, sound)

    @Test fun previewRequiresTheAccountChoiceAndSystemNotificationPermission() {
        assertTrue(allowed())
        assertFalse(allowed(enabled = false)); assertFalse(allowed(permission = false)); assertFalse(allowed(sound = false))
    }

    @Test fun silentVibrateAndEveryDndModePreventThePreview() {
        for (mode in listOf(AudioManager.RINGER_MODE_SILENT, AudioManager.RINGER_MODE_VIBRATE)) assertFalse(allowed(mode = mode))
        for (filter in listOf(NotificationManager.INTERRUPTION_FILTER_NONE, NotificationManager.INTERRUPTION_FILTER_PRIORITY,
            NotificationManager.INTERRUPTION_FILTER_ALARMS, NotificationManager.INTERRUPTION_FILTER_UNKNOWN)) assertFalse(allowed(filter = filter))
    }

    @Test fun mutedVolumeAndSilentChannelAreNeverOverridden() {
        assertFalse(allowed(volume = 0))
        for (importance in listOf(NotificationManager.IMPORTANCE_NONE, NotificationManager.IMPORTANCE_MIN, NotificationManager.IMPORTANCE_LOW))
            assertFalse(allowed(importance = importance))
        assertTrue(allowed(importance = NotificationManager.IMPORTANCE_HIGH, volume = 1))
    }
}
