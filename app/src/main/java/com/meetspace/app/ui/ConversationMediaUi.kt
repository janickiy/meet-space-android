package com.meetspace.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutBoundsHolder
import androidx.compose.ui.layout.onVisibilityChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.meetspace.app.ChatMediaPreview
import com.meetspace.app.LocalChatMedia
import com.meetspace.core.model.AppFailure
import com.meetspace.core.model.ChatAttachment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext

@Composable
internal fun ConversationMediaGrid(files: List<ChatAttachment>, viewport: LayoutBoundsHolder, open: (ChatAttachment) -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("conversation-info-media-grid"), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        files.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                row.forEach { file ->
                    key(file.id) { ConversationMediaTile(file, viewport, Modifier.weight(1f)) { open(file) } }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ConversationMediaTile(file: ChatAttachment, viewport: LayoutBoundsHolder, modifier: Modifier, open: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val loader = LocalChatMedia.current
    var visible by remember { mutableStateOf(false) }
    val thumbnail = if (visible && loader != null) {
        val bitmap by key(loader, file) {
            produceState<Bitmap?>(null) { value = loader.thumbnail(file) }
        }
        bitmap
    } else null
    val video = file.mimeType.startsWith("video/")
    Box(
        modifier.aspectRatio(1f).clip(RoundedCornerShape(9.dp)).background(colors.surfaceVariant)
            .onVisibilityChanged(minDurationMs = 80, minFractionVisible = .1f, viewportBounds = viewport) { visible = it }
            .clickable(onClick = open, role = Role.Button)
            .semantics { contentDescription = "Открыть ${file.filename}" }
            .testTag("conversation-info-material-${file.id}"),
        contentAlignment = Alignment.Center,
    ) {
        if (thumbnail != null) {
            Image(remember(thumbnail) { thumbnail.asImageBitmap() }, contentDescription = null,
                modifier = Modifier.matchParentSize().testTag("conversation-info-thumbnail-${file.id}"), contentScale = ContentScale.Crop)
            if (video) Box(Modifier.size(30.dp).background(Color.Black.copy(alpha = .62f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(CallIcons.Play, null, Modifier.size(16.dp), tint = Color.White)
            }
        } else Column(Modifier.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically)) {
            Icon(if (video) CallIcons.Video else ChatMessageIcons.Image, null, Modifier.size(22.dp), tint = colors.primary)
            Text(file.filename, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private sealed interface MediaViewerState {
    data object Loading : MediaViewerState
    data class Photo(val bitmap: Bitmap) : MediaViewerState
    data class Failed(val message: String) : MediaViewerState

    @androidx.annotation.OptIn(UnstableApi::class)
    class Video(val player: ExoPlayer) : MediaViewerState {
        private var view: PlayerView? = null
        private var released = false

        fun attach(target: PlayerView) {
            if (released || view === target) return
            view?.player = null
            view = target
            target.player = player
        }

        fun detach(target: PlayerView) {
            if (view !== target) return
            view = null
            target.player = null
        }

        fun release() {
            released = true
            try { view?.let(::detach) }
            finally { try { player.stop() } finally { player.release() } }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun ConversationMediaViewer(file: ChatAttachment, compact: Boolean, downloadEnabled: Boolean, download: () -> Unit, back: () -> Unit) {
    val loader = LocalChatMedia.current
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val colors = MaterialTheme.colorScheme
    var retry by remember(file, loader) { mutableIntStateOf(0) }
    val state by key(loader, file, lifecycle, retry) {
        produceState<MediaViewerState>(MediaViewerState.Loading) {
            var preview: ChatMediaPreview? = null
            var videoToRelease: MediaViewerState.Video? = null
            var observer: LifecycleEventObserver? = null
            try {
                val media = loader?.open(file) ?: error("Chat media unavailable")
                preview = media
                when (media) {
                    is ChatMediaPreview.Photo -> {
                        value = MediaViewerState.Photo(media.bitmap)
                        awaitCancellation()
                    }
                    is ChatMediaPreview.Video -> {
                        val playbackFailed = CompletableDeferred<Unit>()
                        val video = withContext(Dispatchers.Main.immediate) {
                            val playback = ExoPlayer.Builder(context).build()
                            val session = MediaViewerState.Video(playback)
                            videoToRelease = session
                            playback.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                            playback.setHandleAudioBecomingNoisy(true)
                            playback.addListener(object : Player.Listener {
                                override fun onPlayerError(error: PlaybackException) { playbackFailed.complete(Unit) }
                            })
                            val pauseOnStop = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) playback.pause() }
                            observer = pauseOnStop
                            lifecycle.addObserver(pauseOnStop)
                            playback.setMediaSource(media.mediaSource)
                            playback.playWhenReady = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                            playback.prepare()
                            session
                        }
                        value = video
                        playbackFailed.await()
                        value = MediaViewerState.Failed("Не удалось воспроизвести видео.")
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                value = MediaViewerState.Failed((failure as? AppFailure)?.message ?: "Не удалось открыть медиафайл.")
            } finally {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    try {
                        observer?.let(lifecycle::removeObserver)
                        videoToRelease?.release()
                    } finally { preview?.close() }
                }
            }
        }
    }

    Surface(
        Modifier.padding(if (compact) 14.dp else 20.dp).widthIn(max = 780.dp).fillMaxWidth().testTag("conversation-media-viewer"),
        shape = RoundedCornerShape(if (compact) 23.dp else 24.dp), color = colors.surface,
    ) {
        BoxWithConstraints {
            val maxMediaHeight = (maxHeight - 164.dp).coerceIn(96.dp, 600.dp)
            val gutter = if (compact) 16.dp else 24.dp
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = gutter, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(file.filename, Modifier.weight(1f).semantics { heading() }, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                        lineHeight = 22.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    IconButton(back, Modifier.testTag("conversation-media-close")) { Icon(CallIcons.X, "Закрыть", Modifier.size(20.dp)) }
                }
                Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(start = gutter, end = gutter, bottom = gutter), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val mediaModifier = Modifier.fillMaxWidth().heightIn(max = maxMediaHeight).clip(RoundedCornerShape(12.dp))
                    when (val media = state) {
                        MediaViewerState.Loading -> Box(mediaModifier.height(164.dp).background(colors.background)
                            .testTag("conversation-media-loading"), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        is MediaViewerState.Photo -> Image(remember(media.bitmap) { media.bitmap.asImageBitmap() }, file.filename,
                            mediaModifier.aspectRatio(media.bitmap.width.toFloat() / media.bitmap.height).background(colors.background)
                                .testTag("conversation-media-photo"), contentScale = ContentScale.Fit)
                        is MediaViewerState.Video -> ConversationVideoSurface(media, mediaModifier)
                        is MediaViewerState.Failed -> Column(mediaModifier.background(colors.background).padding(12.dp)
                            .testTag("conversation-media-error"), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(media.message, color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
                            TextButton({ retry++ }, Modifier.testTag("conversation-media-retry")) { Text("Повторить") }
                        }
                    }
                    Text("${file.filename} · ${sizeLabel(file.size)}", fontSize = 11.sp, color = colors.onSurfaceVariant)
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = back, modifier = Modifier.testTag("conversation-media-back")) {
                            Icon(WorkspaceHeaderIcons.Back, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("К информации")
                        }
                        OutlinedButton(onClick = download, enabled = downloadEnabled, modifier = Modifier.testTag("conversation-media-download")) {
                            Icon(ChatMessageIcons.Download, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Скачать")
                        }
                    }
                }
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun ConversationVideoSurface(video: MediaViewerState.Video, modifier: Modifier) {
    val player = video.player
    var ready by remember(player) { mutableStateOf(player.playbackState in setOf(Player.STATE_READY, Player.STATE_ENDED)) }
    var rendered by remember(player) { mutableStateOf(false) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) { ready = state == Player.STATE_READY || state == Player.STATE_ENDED }
            override fun onRenderedFirstFrame() { rendered = true }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    BoxWithConstraints(modifier.background(Color.Black).testTag("conversation-media-video"), contentAlignment = Alignment.Center) {
        val height = (maxWidth * 9f / 16f).coerceAtMost(maxHeight)
        AndroidView(factory = { PlayerView(it).apply {
            setShutterBackgroundColor(android.graphics.Color.BLACK)
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        } }, update = video::attach, onRelease = video::detach,
            modifier = Modifier.fillMaxWidth().height(height).then(if (rendered) Modifier.testTag("conversation-media-video-frame") else Modifier))
        if (ready) Box(Modifier.matchParentSize().testTag("conversation-media-video-ready"))
        else LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
    }
}
