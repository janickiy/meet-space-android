package com.meetspace.core.media

import android.content.Context
import android.media.*
import android.os.Build
import android.os.Handler
import android.os.Looper

@Suppress("DEPRECATION")
internal class CallAudio(private val context: Context, private val onChanged: (List<AudioRoute>) -> Unit, private val onFocusLost: () -> Unit) {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var started = false
    private var previousMode = AudioManager.MODE_NORMAL
    private var previousSpeaker = false
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setOnAudioFocusChangeListener({ if (it < 0) onFocusLost() }, handler).build()
    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
    }
    private val communicationListener = if (Build.VERSION.SDK_INT >= 31) AudioManager.OnCommunicationDeviceChangedListener { refresh() } else null
    fun start() {
        if (started) return
        previousMode = manager.mode; previousSpeaker = manager.isSpeakerphoneOn
        if (manager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) throw IllegalStateException("Аудиофокус недоступен")
        started = true
        manager.mode = AudioManager.MODE_IN_COMMUNICATION
        manager.registerAudioDeviceCallback(callback, handler)
        if (Build.VERSION.SDK_INT >= 31) manager.addOnCommunicationDeviceChangedListener(context.mainExecutor, communicationListener!!)
        refresh()
    }
    fun refresh() {
        if (!started) return
        val routes = runCatching {
            val devices = if (Build.VERSION.SDK_INT >= 31) manager.availableCommunicationDevices else manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
            val selected = if (Build.VERSION.SDK_INT >= 31) manager.communicationDevice?.id else null
            devices.mapNotNull { device ->
                val name = when (device.type) {
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Динамик"
                    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Телефон"
                    AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> "Гарнитура"
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_HEARING_AID -> "Bluetooth"
                    else -> null
                } ?: return@mapNotNull null
                AudioRoute(device.id, name, selected == device.id || selected == null && device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER && manager.isSpeakerphoneOn)
            }
        }.getOrDefault(emptyList())
        onChanged(routes)
    }
    fun select(id: Int): Boolean = runCatching {
        if (!started) return false
        if (Build.VERSION.SDK_INT >= 31) {
            val device = manager.availableCommunicationDevices.firstOrNull { it.id == id } ?: return false
            manager.setCommunicationDevice(device).also { refresh() }
        } else {
            val device = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == id } ?: return false
            manager.isSpeakerphoneOn = device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            if (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) { manager.startBluetoothSco(); manager.isBluetoothScoOn = true }
            else { manager.stopBluetoothSco(); manager.isBluetoothScoOn = false }
            refresh(); true
        }
    }.getOrDefault(false)
    fun close() {
        if (!started) return
        started = false
        runCatching { manager.unregisterAudioDeviceCallback(callback) }
        if (Build.VERSION.SDK_INT >= 31) runCatching { manager.removeOnCommunicationDeviceChangedListener(communicationListener!!) }
        runCatching { if (Build.VERSION.SDK_INT >= 31) manager.clearCommunicationDevice() else { manager.stopBluetoothSco(); manager.isBluetoothScoOn = false } }
        runCatching { manager.isSpeakerphoneOn = previousSpeaker; manager.mode = previousMode; manager.abandonAudioFocusRequest(focus) }
        onChanged(emptyList())
    }
}
