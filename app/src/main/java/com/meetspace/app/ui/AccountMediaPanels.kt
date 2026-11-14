package com.meetspace.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

internal enum class AudioProbeKind { MICROPHONE, SPEAKER, RINGTONE }

/** Main-thread non-blocking polling makes stop/release ordered with every read and callback. */
internal class AccountAudioProbe(private val context: Context) : AutoCloseable {
    var kind: AudioProbeKind? by mutableStateOf(null); private set
    var lastKind: AudioProbeKind? by mutableStateOf(null); private set
    var level by mutableFloatStateOf(0f); private set
    var status by mutableStateOf(""); private set
    private val handler = Handler(Looper.getMainLooper())
    private var recorder: AudioRecord? = null
    private var track: AudioTrack? = null
    private val tone = com.meetspace.app.LocalSignalPlayer(context)
    private var noiseEffect: android.media.audiofx.NoiseSuppressor? = null
    private var microphoneGain = 1f
    private var outputLevel = 0f
    private var startedAt = 0L
    private var durationMs = 0L
    private val samples = ShortArray(1600)
    private val poll = object : Runnable {
        override fun run() {
            if (kind == null) return
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            try {
                if (kind == AudioProbeKind.MICROPHONE) {
                    val count = recorder?.read(samples, 0, samples.size, AudioRecord.READ_NON_BLOCKING) ?: 0
                    if (count < 0) { stop("Микрофон недоступен. Повторите проверку."); return }
                    if (count > 0) {
                        var power = 0.0
                        repeat(count) { power += samples[it].toDouble() * samples[it] }
                        val rms = sqrt(power / count) / Short.MAX_VALUE * microphoneGain
                        level = if (rms <= 0.00001) 0f else ((20 * log10(rms) + 60) / 60).toFloat().coerceIn(0f, 1f)
                    }
                } else level = outputLevel
                if (elapsed >= durationMs) { stop(if (kind == AudioProbeKind.MICROPHONE) "Проверка завершена. Звук не сохранён." else "Проверка завершена. Если звука не было, проверьте громкость Android."); return }
                handler.postDelayed(this, 50L)
            } catch (_: RuntimeException) { stop("Не удалось завершить проверку. Устройство освобождено.") }
        }
    }

    @SuppressLint("MissingPermission")
    fun microphone(inputId: Int?, volume: Int = 100, noiseSuppression: Boolean = true) {
        stop("")
        microphoneGain = volume.coerceIn(0, 100) / 100f
        lastKind = AudioProbeKind.MICROPHONE
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { status = "Разрешите доступ к микрофону."; return }
        try {
            val minimum = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0)
            val input = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(16_000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(max(minimum * 2, 6400)).build()
            recorder = input
            check(input.state == AudioRecord.STATE_INITIALIZED)
            if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
                noiseEffect = android.media.audiofx.NoiseSuppressor.create(input.audioSessionId)?.also { it.enabled = noiseSuppression }
            }
            if (inputId != null) {
                val device = context.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == inputId }
                check(device != null && input.setPreferredDevice(device))
            }
            input.startRecording()
            check(input.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            begin(AudioProbeKind.MICROPHONE, 8_000, "Говорите: индикатор показывает уровень микрофона.")
        } catch (_: Exception) { stop("Микрофон недоступен. Проверьте разрешение и подключение устройства.") }
    }

    fun speaker(preference: SpeakerPreference, volume: Int = 100) {
        stop(""); lastKind = AudioProbeKind.SPEAKER
        try {
            val output = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(16_000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(24_000).setTransferMode(AudioTrack.MODE_STATIC).build()
            track = output
            check(output.state != AudioTrack.STATE_UNINITIALIZED)
            if (preference != SpeakerPreference.DEFAULT) {
                val route = com.meetspace.app.audioOutput(context.getSystemService(AudioManager::class.java), preference)
                check(route != null && output.setPreferredDevice(route))
            }
            val pcm = ShortArray(12_000) { index ->
                val fade = minOf(index / 800.0, (11_999 - index) / 800.0, 1.0).coerceAtLeast(0.0)
                (sin(2.0 * Math.PI * 440 * index / 16_000) * Short.MAX_VALUE * .18 * fade).toInt().toShort()
            }
            check(output.write(pcm, 0, pcm.size) == pcm.size)
            check(output.state == AudioTrack.STATE_INITIALIZED)
            outputLevel = volume.coerceIn(0, 100) / 100f
            output.setVolume(outputLevel)
            output.play()
            begin(AudioProbeKind.SPEAKER, 1_000, "Воспроизводим короткий тестовый сигнал.")
        } catch (_: Exception) { stop("Выбранный аудиовыход недоступен. Подключите устройство или выберите другой выход.") }
    }

    fun ringtone(uri: Uri?, preference: SpeakerPreference = SpeakerPreference.DEFAULT, volume: Int = 100) {
        stop(""); lastKind = AudioProbeKind.RINGTONE
        if (uri == null) { status = "Звук уведомлений выключен в Android."; return }
        try {
            begin(AudioProbeKind.RINGTONE, 3_000, "Проверяем текущий сигнал уведомлений Android.")
            tone.play(uri, preference, volume, AudioAttributes.USAGE_NOTIFICATION,
                started = { outputLevel = volume.coerceIn(0, 100) / 100f },
                finished = { stop(it ?: "Проверка завершена.") })
        } catch (_: Exception) { stop("Сигнал недоступен. Проверьте настройки уведомлений Android.") }
    }

    private fun begin(value: AudioProbeKind, duration: Long, text: String) {
        kind = value; lastKind = value; startedAt = SystemClock.elapsedRealtime(); durationMs = duration; status = text; level = 0f
        handler.post(poll)
    }
    fun stop(message: String = "Проверка остановлена.") {
        handler.removeCallbacks(poll)
        noiseEffect?.release(); noiseEffect = null
        recorder?.let { runCatching { it.stop() }; runCatching { it.release() } }; recorder = null
        track?.let { runCatching { it.stop() }; runCatching { it.release() } }; track = null
        tone.close()
        kind = null; level = 0f; outputLevel = 0f; status = message
        samples.fill(0)
    }
    override fun close() { stop("") }
}

internal data class PreviewCamera(val id: String, val front: Boolean)
internal fun availablePreviewCameras(manager: CameraManager): List<PreviewCamera> = runCatching {
    manager.cameraIdList.mapNotNull { id ->
        val facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
        if (facing == CameraCharacteristics.LENS_FACING_FRONT || facing == CameraCharacteristics.LENS_FACING_BACK) PreviewCamera(id, facing == CameraCharacteristics.LENS_FACING_FRONT) else null
    }.distinctBy { it.front }.sortedByDescending { it.front }
}.getOrDefault(emptyList())

/** All callbacks use the main looper. An epoch rejects late camera-open/session callbacks. */
internal class AccountCameraPreview(private val manager: CameraManager) : AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private var epoch = 0
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var surface: Surface? = null
    private var buffer = Size(640, 480)
    private var sensor = 90
    private var front = true

    @SuppressLint("MissingPermission")
    fun open(id: String, view: TextureView, texture: SurfaceTexture, width: Int, height: Int, rotation: Int, failure: (String) -> Unit) {
        close()
        val ticket = epoch
        fun fail() { if (ticket == epoch) { close(); failure("Камера недоступна. Закройте другое приложение с камерой и повторите.") } }
        try {
            val characteristics = manager.getCameraCharacteristics(id)
            sensor = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            front = characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            val choices = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(SurfaceTexture::class.java).orEmpty()
            val candidates = choices.filter { max(it.width, it.height) <= 1280 }.ifEmpty { choices.toList() }
            val turns = relativeRotation(rotation) % 180 != 0
            val ratio = width.toDouble() / height.coerceAtLeast(1)
            buffer = candidates.minByOrNull { abs((if (turns) it.height.toDouble() / it.width else it.width.toDouble() / it.height) - ratio) } ?: Size(640, 480)
            texture.setDefaultBufferSize(buffer.width, buffer.height)
            transform(view, width, height, rotation)
            val output = Surface(texture); surface = output
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (ticket != epoch) { camera.close(); return }
                    device = camera
                    try {
                        @Suppress("DEPRECATION")
                        camera.createCaptureSession(listOf(output), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(value: CameraCaptureSession) {
                                if (ticket != epoch) { value.close(); return }
                                session = value
                                try {
                                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                        addTarget(output)
                                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                    }.build()
                                    value.setRepeatingRequest(request, null, handler)
                                } catch (_: Exception) { fail() }
                            }
                            override fun onConfigureFailed(value: CameraCaptureSession) { value.close(); fail() }
                        }, handler)
                    } catch (_: Exception) { fail() }
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close(); fail() }
                override fun onError(camera: CameraDevice, error: Int) { camera.close(); fail() }
            }, handler)
        } catch (_: Exception) { fail() }
    }

    private fun relativeRotation(rotation: Int): Int {
        val degrees = when (rotation) { Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0 }
        return if (front) (360 - (sensor + degrees) % 360) % 360 else (sensor - degrees + 360) % 360
    }

    fun transform(view: TextureView, width: Int, height: Int, rotation: Int) {
        if (width <= 0 || height <= 0) return
        val degrees = relativeRotation(rotation)
        val turns = degrees % 180 != 0
        val rotatedWidth = if (turns) buffer.height else buffer.width
        val rotatedHeight = if (turns) buffer.width else buffer.height
        val scale = max(width.toFloat() / rotatedWidth, height.toFloat() / rotatedHeight)
        val matrix = Matrix().apply {
            setScale(buffer.width.toFloat() / width, buffer.height.toFloat() / height)
            postTranslate(-buffer.width / 2f, -buffer.height / 2f)
            postRotate(degrees.toFloat())
            postScale(scale, scale)
            postTranslate(width / 2f, height / 2f)
            if (front) postScale(-1f, 1f, width / 2f, height / 2f)
        }
        view.setTransform(matrix)
    }

    override fun close() {
        epoch++
        session?.let { runCatching { it.stopRepeating() }; runCatching { it.close() } }; session = null
        device?.let { runCatching { it.close() } }; device = null
        surface?.let { runCatching { it.release() } }; surface = null
    }
}
