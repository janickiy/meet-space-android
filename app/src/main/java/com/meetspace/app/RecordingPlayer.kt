package com.meetspace.app

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.ui.PlayerView
import com.meetspace.core.data.RecordingAccess
import com.meetspace.core.data.RecordingStream

/** The player knows an opaque local URI only. HTTPS URLs/auth stay behind the repository lease. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class RecordingDataSource(private val access: RecordingAccess) : BaseDataSource(true) {
    private var stream: RecordingStream? = null
    private var opened = false
    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        stream = access.open(dataSpec.position, dataSpec.length.takeIf { it != C.LENGTH_UNSET.toLong() })
        opened = true
        transferStarted(dataSpec)
        return stream!!.length ?: C.LENGTH_UNSET.toLong()
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val count = checkNotNull(stream).input.read(buffer, offset, length)
        if (count > 0) bytesTransferred(count)
        return if (count < 0) C.RESULT_END_OF_INPUT else count
    }
    override fun getUri(): Uri? = if (opened) Uri.parse("meetspace-recording://media") else null
    override fun close() {
        try { stream?.close() } finally { stream = null; if (opened) { opened = false; transferEnded() } }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun RecordingPlayer(playback: RecordingPlayback, close: () -> Unit, retry: () -> Unit, inline: Boolean = false) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var renderedFrame by remember(playback) { mutableStateOf(false) }
    var failed by remember(playback) { mutableStateOf(false) }
    var playing by remember(playback) { mutableStateOf(false) }
    var ready by remember(playback) { mutableStateOf(false) }
    val player = remember(playback) {
        ExoPlayer.Builder(context.applicationContext).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            setHandleAudioBecomingNoisy(true)
            val source = ProgressiveMediaSource.Factory(DataSource.Factory { RecordingDataSource(playback.access) })
                .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(0))
                .createMediaSource(MediaItem.Builder().setUri("meetspace-recording://media").setMimeType(playback.access.file.mimeType).build())
            setMediaSource(source)
            seekTo(playback.positionMs)
            playWhenReady = playback.playWhenReady
        }
    }
    LaunchedEffect(player, playback) { playback.seekRequest.collect { it?.let { (_, position) -> player.seekTo(position) } } }
    DisposableEffect(player, lifecycle) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() { renderedFrame = true }
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { playback.playWhenReady = playWhenReady }
            override fun onPlayerError(error: PlaybackException) { failed = true; ready = false }
            override fun onPlaybackStateChanged(playbackState: Int) { ready = playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                playback.positionMs = player.currentPosition
                if ((context as? android.app.Activity)?.isChangingConfigurations != true) player.pause()
            }
        }
        player.addListener(listener); lifecycle.addObserver(observer); player.prepare()
        onDispose {
            playback.positionMs = player.currentPosition
            playback.playWhenReady = player.playWhenReady
            lifecycle.removeObserver(observer); player.removeListener(listener)
            // Media3 1.11.1 can flush a final onEvents while removing its stuck-player
            // listener. Enter IDLE first so that callback cannot re-arm a duration timer.
            try { player.stop() } finally { player.release() }
        }
    }
    BackHandler(onBack = close)
    if (inline) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).heightIn(min = 180.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFF252F40)).testTag("recording-player-screen")) {
            if (failed) Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Не удалось воспроизвести запись. Проверьте соединение и повторите.", color = Color.White, modifier = Modifier.testTag("recording-player-error"))
                Button(retry, modifier = Modifier.testTag("recording-player-retry")) { Text("Повторить") }
            } else {
                Box(Modifier.fillMaxSize().testTag(if (ready) "recording-player-ready" else "recording-player")) {
                    AndroidView(factory = { PlayerView(it).apply { this.player = player; setShutterBackgroundColor(0xFF252F40.toInt()); setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING); keepScreenOn = playing } },
                        update = { it.player = player; it.keepScreenOn = playing }, onRelease = { it.keepScreenOn = false; it.player = null },
                        modifier = Modifier.fillMaxSize().testTag(if (renderedFrame) "recording-video-frame" else "recording-video"))
                }
                if (!ready) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter).testTag("recording-player-loading"))
            }
            IconButton(close, modifier = Modifier.align(Alignment.TopStart).size(48.dp).testTag("recording-player-close")) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Закрыть проигрыватель", tint = Color.White) }
        }
        return
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, contentWindowInsets = WindowInsets.safeDrawing) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).testTag("recording-player-screen")) {
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                IconButton(onClick = close, modifier = Modifier.size(48.dp).testTag("recording-player-close")) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Закрыть проигрыватель") }
                Text(playback.title, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
            }
            if (failed) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Не удалось воспроизвести запись. Проверьте соединение и повторите — приложение заново запросит доступ.", modifier = Modifier.testTag("recording-player-error"))
                    Button(onClick = retry, modifier = Modifier.testTag("recording-player-retry")) { Text("Повторить") }
                }
            } else {
                if (!ready) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("recording-player-loading"))
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 20.dp, vertical = 12.dp).testTag(if (ready) "recording-player-ready" else "recording-player"), contentAlignment = Alignment.Center) { AndroidView(factory = { PlayerView(it).apply { this.player = player; setShutterBackgroundColor(0xFF252F40.toInt()); setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING); keepScreenOn = playing } },
                    update = { it.player = player; it.keepScreenOn = playing }, onRelease = { it.keepScreenOn = false; it.player = null },
                    modifier = Modifier.widthIn(max = 1080.dp).fillMaxSize().clip(RoundedCornerShape(if (maxWidth < 600.dp) 16.dp else 22.dp)).background(Color(0xFF252F40)).testTag(if (renderedFrame) "recording-video-frame" else "recording-video")) }
            }
        }
    }
}
