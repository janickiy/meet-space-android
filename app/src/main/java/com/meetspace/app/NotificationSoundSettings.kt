package com.meetspace.app

import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import androidx.core.content.edit
import java.security.MessageDigest

/** This device's sound choice, isolated by the same server/user identity as notification delivery. */
internal class NotificationSoundSettings(context: Context) {
    val preferences: SharedPreferences = context.getSharedPreferences("account-notification-sound", Context.MODE_PRIVATE)
    fun enabled(scope: String?): Boolean = key(scope)?.let { preferences.getBoolean(it, true) } ?: true
    fun setEnabled(scope: String?, enabled: Boolean) {
        key(scope)?.let { key -> preferences.edit { putBoolean(key, enabled) } }
    }
    private fun key(scope: String?): String? = scope?.takeIf { it.isNotBlank() }?.let {
        MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8)).joinToString("") { byte -> "%02x".format(byte) }
    }
}

/** A preview must never override the device's silent/vibrate mode, notification volume or DND. */
internal fun notificationSoundAllowed(
    enabled: Boolean, permission: Boolean, ringerMode: Int, interruptionFilter: Int,
    volume: Int, importance: Int, hasSound: Boolean,
): Boolean = enabled && permission && hasSound && volume > 0 &&
    ringerMode == AudioManager.RINGER_MODE_NORMAL && interruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL &&
    importance >= NotificationManager.IMPORTANCE_DEFAULT
