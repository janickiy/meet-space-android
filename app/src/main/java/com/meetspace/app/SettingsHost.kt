package com.meetspace.app

import android.Manifest
import android.content.Intent
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.meetspace.app.ui.*

@Composable
fun SettingsHost(push: PushState, notificationScope: String? = null, content: @Composable (AppSettingsUiState, AppSettingsActions) -> Unit = { state, actions -> AppSettings(state, actions) }, openNotifications: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val permissions = remember(revision) { listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).map {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    } }
    val notifications = remember(revision) { NotificationManagerCompat.from(context).areNotificationsEnabled() }
    val (devicePreferences, updateDevicePreferences) = rememberAccountDevicePreferences()
    val (soundEnabled, updateSound) = rememberNotificationSound(notificationScope)
    content(AppSettingsUiState(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, notifications,
        permissions[0], permissions[1], push.configured, push.enabled, devicePreferences, soundEnabled), AppSettingsActions(
        openNotifications = openNotifications,
        openSystemSettings = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) },
        updateDevicePreferences = updateDevicePreferences,
        updateNotificationSound = updateSound,
    ))
}

@Composable
private fun rememberNotificationSound(scope: String?): Pair<Boolean, (Boolean) -> Unit> {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { NotificationSoundSettings(context) }
    var current by remember(store, scope) { mutableStateOf(store.enabled(scope)) }
    val liveScope by rememberUpdatedState(scope)
    DisposableEffect(store, scope) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> current = store.enabled(scope) }
        store.preferences.registerOnSharedPreferenceChangeListener(listener)
        current = store.enabled(scope)
        onDispose { store.preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val update: (Boolean) -> Unit = remember(store, scope) { { enabled ->
        if (scope != null && scope == liveScope) { store.setEnabled(scope, enabled); current = store.enabled(scope) }
    } }
    return current to update
}

/** Both the profile and call host observe the same device-local preferences. */
@Composable
internal fun rememberAccountDevicePreferences(): Pair<AccountDevicePreferences, (AccountDevicePreferences) -> Unit> {
    val context = LocalContext.current.applicationContext
    val preferences = remember(context) { context.getSharedPreferences("account-device-preferences", Context.MODE_PRIVATE) }
    val store = remember(preferences) { AccountDevicePreferencesStore(preferences) }
    var current by remember(store) { mutableStateOf(store.read()) }
    DisposableEffect(preferences, store) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> current = store.read() }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        // Close the read/register gap when another active host updates preferences.
        current = store.read()
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val update: (AccountDevicePreferences) -> Unit = remember(store) { { value -> store.write(value); current = store.read() } }
    return current to update
}

internal class AccountDevicePreferencesStore(private val preferences: SharedPreferences) {
    fun read() = AccountDevicePreferences(
        autoMute = preferences.getBoolean("autoMute", true),
        cameraOff = preferences.getBoolean("cameraOff", true),
        showSelf = preferences.getBoolean("showSelf", true),
        hideParticipantVideo = preferences.getBoolean("hideParticipantVideo", false),
        speaker = preferences.getString("speaker", null)?.let { saved -> SpeakerPreference.entries.firstOrNull { it.name == saved } } ?: SpeakerPreference.DEFAULT,
        textScale = preferences.getFloat("textScale", 1f).let { if (it.isFinite()) it.coerceIn(.75f, 2f) else 1f },
    )

    fun write(value: AccountDevicePreferences) {
        preferences.edit()
            .putBoolean("autoMute", value.autoMute)
            .putBoolean("cameraOff", value.cameraOff)
            .putBoolean("showSelf", value.showSelf)
            .putBoolean("hideParticipantVideo", value.hideParticipantVideo)
            .putString("speaker", value.speaker.name)
            .putFloat("textScale", if (value.textScale.isFinite()) value.textScale.coerceIn(.75f, 2f) else 1f)
            .apply()
    }
}
