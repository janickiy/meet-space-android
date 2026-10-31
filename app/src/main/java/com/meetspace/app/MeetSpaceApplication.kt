package com.meetspace.app

import android.app.Application
import com.meetspace.core.data.FoundationRepository
import com.meetspace.core.data.FoundationRepositoryFactory
import com.meetspace.core.data.MeetingsRepository
import com.meetspace.core.ssh.SshPreflightRepository
import com.meetspace.core.ssh.SshPreflightRepositoryFactory

open class MeetSpaceApplication : Application() {
    @Volatile private var visibleActivities = 0
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: android.app.Activity) { visibleActivities++ }
            override fun onActivityStopped(activity: android.app.Activity) { visibleActivities = (visibleActivities - 1).coerceAtLeast(0); if (visibleActivities == 0 && notificationsDelegate.isInitialized()) messagingNotifications.stopSound() }
            override fun onActivityCreated(activity: android.app.Activity, state: android.os.Bundle?) = Unit
            override fun onActivityResumed(activity: android.app.Activity) = Unit
            override fun onActivityPaused(activity: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(activity: android.app.Activity, state: android.os.Bundle) = Unit
            override fun onActivityDestroyed(activity: android.app.Activity) = Unit
        })
    }
    private var deviceSettingsListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null
    val outgoingMessageStore: OutgoingMessageStore by lazy { EncryptedOutgoingMessageStore(this) }
    open val callEngine: com.meetspace.core.media.CallEngine by lazy {
        com.meetspace.core.media.CallEngine(this, repository as com.meetspace.core.data.CallRepository, meetingsRepository) {
            stopService(android.content.Intent(this, ScreenShareService::class.java))
        }.also { engine ->
            val preferences = getSharedPreferences("account-device-preferences", MODE_PRIVATE)
            val store = AccountDevicePreferencesStore(preferences)
            deviceSettingsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> engine.configureDevices(store.read().mediaSettings()) }
            preferences.registerOnSharedPreferenceChangeListener(deviceSettingsListener)
            engine.configureDevices(store.read().mediaSettings())
        }
    }
    val installerRepository: com.meetspace.core.ssh.SshInstallerRepository by lazy { com.meetspace.core.ssh.SshInstallerRepositoryFactory.create(this) }
    open val repository: FoundationRepository by lazy { FoundationRepositoryFactory.create(this) }
    val meetingsRepository: MeetingsRepository get() = repository as MeetingsRepository
    val meetingChatRepository: com.meetspace.core.data.MeetingChatRepository get() = repository as com.meetspace.core.data.MeetingChatRepository
    val recordingsRepository: com.meetspace.core.data.RecordingsRepository get() = repository as com.meetspace.core.data.RecordingsRepository
    val messagingRepository: com.meetspace.core.data.MessagingRepository get() = repository as com.meetspace.core.data.MessagingRepository
    val directCallsRepository: com.meetspace.core.data.DirectCallsRepository get() = repository as com.meetspace.core.data.DirectCallsRepository
    val pushController by lazy { PushController(this, repository, messagingRepository, messagingNotifications) }
    private val notificationsDelegate = lazy { MessagingNotifications(this, identity = {
        val current = repository.state.value
        if (current.session?.guestMeeting != null) null else {
            val origin = current.selectedProfile?.origin
            val user = current.session?.user?.id
            if (origin != null && user != null) "$origin|$user" else null
        }
    }, foreground = { visibleActivities > 0 }) }
    val messagingNotifications by notificationsDelegate
    val sshRepository: SshPreflightRepository by lazy { SshPreflightRepositoryFactory.create(this) }
}
