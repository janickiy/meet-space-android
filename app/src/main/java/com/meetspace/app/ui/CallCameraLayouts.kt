package com.meetspace.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meetspace.core.media.CallState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private const val CameraAspect = 16f / 9f
private const val SpeakerPageSize = 3

@Composable
internal fun CallCameraGallery(
    state: CallState,
    tiles: List<VideoTileItem>,
    width: Dp,
    height: Dp,
    wide: Boolean,
    shortLandscape: Boolean,
    pin: (String) -> Unit,
    localMicrophoneLevel: StateFlow<Float>?,
) {
    if (tiles.isEmpty()) return
    // A tablet can remain "wide" after rotation. Choose the arrangement from the
    // actual stage, including the space left beside an open participant/chat panel.
    val portraitStage = height > width
    val largeStage = wide && !portraitStage && width >= 960.dp
    val columns = when {
        tiles.size == 1 -> 1
        portraitStage && tiles.size <= 3 -> 1
        largeStage && tiles.size != 2 && tiles.size != 4 -> 3
        else -> 2
    }
    val rows = tiles.chunked(columns)
    val tileGap = if (wide) 10.dp else 7.dp
    val maxRows = if (shortLandscape) ((height + tileGap) / (84.dp + tileGap)).toInt().coerceIn(1, 2)
        else if (largeStage) 2 else 3
    val visibleRows = rows.size.coerceAtMost(maxRows)
    val columnGaps = tileGap * (columns - 1)
    // Fit an integer number of pixels per row so rounding never clips the last tile.
    val fittedHeight = with(LocalDensity.current) {
        ((height.roundToPx() - tileGap.roundToPx() * (visibleRows - 1)) / visibleRows).toDp()
    }
    val fittedWidth = fittedHeight * CameraAspect
    val columnWidth = (width - columnGaps) / columns
    val tileWidth = if (shortLandscape) columnWidth
        else columnWidth.coerceAtMost(fittedWidth.coerceAtLeast(if (tiles.size == 1) 0.dp else 150.dp))
    val tileHeight = if (shortLandscape) fittedHeight else tileWidth / CameraAspect
    if (tileWidth <= 0.dp || tileHeight <= 0.dp) return

    Box(Modifier.fillMaxSize().testTag("call-gallery"), contentAlignment = Alignment.Center) {
        LazyColumn(
            Modifier.width(tileWidth * columns + columnGaps).height(height).testTag("call-grid"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(tileGap, Alignment.CenterVertically),
        ) {
            items(rows, key = { row -> row.first().id }) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(tileGap)) {
                    row.forEach { tile ->
                        key(tile.id) {
                            VideoTile(tile, state, phone = !wide, Modifier.width(tileWidth).height(tileHeight),
                                gallery = true, fitVideo = true, onSelect = { pin(tile.id) },
                                liveLevel = localMicrophoneLevel.takeIf { tile.id == "self" })
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun CallCameraSpeaker(
    state: CallState,
    featured: VideoTileItem,
    others: List<VideoTileItem>,
    width: Dp,
    height: Dp,
    wide: Boolean,
    shortLandscape: Boolean,
    landscapeViewport: Boolean,
    pin: (String) -> Unit,
    unpin: (() -> Unit)?,
    localMicrophoneLevel: StateFlow<Float>?,
) {
    val tileGap = if (wide) 10.dp else 7.dp
    val peer = others.singleOrNull()
    if (peer != null) {
        // A portrait call keeps its stack beside or above an open panel, unless the keyboard
        // leaves too little height for two useful rows.
        val sideBySide = width > height && (landscapeViewport || height < 120.dp)
        CallSpeakerPair(state, featured, peer, width, height, tileGap, wide,
            sideBySide, pin, unpin, localMicrophoneLevel)
        return
    }
    val showRail = others.isNotEmpty()
    if (shortLandscape) {
        val railWidth = if (showRail) (width - tileGap) / 3 else 0.dp
        val controlsHeight = if (others.size > 1 && height >= 106.dp) 30.dp else 0.dp
        Row(Modifier.fillMaxSize().testTag("call-grid"), horizontalArrangement = Arrangement.spacedBy(tileGap),
            verticalAlignment = Alignment.CenterVertically) {
            CallFeaturedParticipant(state, featured, wide, unpin, localMicrophoneLevel, Modifier.weight(1f).fillMaxHeight())
            if (railWidth > 0.dp) CallSpeakerRail(state, others, railWidth, height - controlsHeight, slots = 1,
                tileGap, controlsHeight > 0.dp, wide, pin, localMicrophoneLevel)
        }
        return
    }
    val slots = minOf(others.size, SpeakerPageSize).coerceAtLeast(1)
    val pageDots = others.size > slots && height >= 130.dp
    val stackGap = if (wide) 10.dp else 8.dp
    val railGaps = tileGap * (slots - 1)
    val railReserve = if (showRail) stackGap + (if (pageDots) 30.dp else 0.dp) else 0.dp
    val usableHeight = (height - railReserve).coerceAtLeast(0.dp)
    val railHeightLimit = usableHeight * .35f
    // Share one width between the featured video and the rail. The rail takes at most
    // about a third of the height to leave room for the featured video.
    val fittedStackWidth = (usableHeight * CameraAspect * slots + railGaps) / (slots + 1)
    val frameWidth = if (showRail) width.coerceAtMost(maxOf(fittedStackWidth, (usableHeight - railHeightLimit) * CameraAspect))
        else width.coerceAtMost(usableHeight * CameraAspect)
    val thumbnailHeight = ((frameWidth - railGaps) / slots / CameraAspect).coerceIn(0.dp, railHeightLimit)

    Column(Modifier.fillMaxSize().testTag("call-grid"), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        if (frameWidth > 0.dp) {
            CallFeaturedParticipant(state, featured, wide, unpin, localMicrophoneLevel,
                Modifier.width(frameWidth).aspectRatio(CameraAspect))
        }
        if (showRail && thumbnailHeight > 0.dp) {
            Spacer(Modifier.height(stackGap))
            CallSpeakerRail(state, others, frameWidth, thumbnailHeight, slots, tileGap, pageDots, wide, pin, localMicrophoneLevel)
        }
    }
}

@Composable
private fun CallSpeakerPair(state: CallState, featured: VideoTileItem, peer: VideoTileItem,
    width: Dp, height: Dp, gap: Dp, wide: Boolean, sideBySide: Boolean,
    pin: (String) -> Unit, unpin: (() -> Unit)?, localMicrophoneLevel: StateFlow<Float>?) {
    // Use integral pixel bounds for both tiles, including the gap, to avoid clipping the second tile.
    val tileWidth = if (sideBySide) with(LocalDensity.current) { ((width.roundToPx() - gap.roundToPx()) / 2).toDp() } else width
    val availableHeight = if (sideBySide) height else with(LocalDensity.current) { ((height.roundToPx() - gap.roundToPx()) / 2).toDp() }
    val tileHeight = minOf(availableHeight, tileWidth / CameraAspect)
    if (tileWidth <= 0.dp || tileHeight <= 0.dp) return
    val tile = Modifier.width(tileWidth).height(tileHeight)
    val tiles: @Composable () -> Unit = {
        CallFeaturedParticipant(state, featured, wide, unpin, localMicrophoneLevel, tile)
        VideoTile(peer, state, phone = !wide, tile, fillStage = true, fitVideo = true,
            liveLevel = localMicrophoneLevel.takeIf { peer.id == "self" }, onSelect = { pin(peer.id) })
    }
    Box(Modifier.fillMaxSize().testTag("call-grid"), contentAlignment = Alignment.Center) {
        if (sideBySide) Row(horizontalArrangement = Arrangement.spacedBy(gap)) { tiles() }
        else Column(verticalArrangement = Arrangement.spacedBy(gap)) { tiles() }
    }
}

@Composable
private fun CallFeaturedParticipant(state: CallState, featured: VideoTileItem, wide: Boolean, unpin: (() -> Unit)?,
    localMicrophoneLevel: StateFlow<Float>?, modifier: Modifier) {
    Box(modifier.testTag("call-featured-video")) {
        VideoTile(featured, state, phone = !wide, Modifier.matchParentSize(), fillStage = true, fitVideo = true,
            liveLevel = localMicrophoneLevel.takeIf { featured.id == "self" })
        if (unpin != null || state.isSpeaking(featured.participantId)) {
            CallSpeakerBadge(unpin, Modifier.align(Alignment.TopStart).padding(10.dp))
        }
    }
}

@Composable
private fun CallSpeakerRail(state: CallState, others: List<VideoTileItem>, width: Dp, height: Dp, slots: Int, tileGap: Dp,
    pageDots: Boolean, wide: Boolean, pin: (String) -> Unit, localMicrophoneLevel: StateFlow<Float>?) {
    val pages = (others.size + slots - 1) / slots
    val thumbnailWidth = (width - tileGap * (slots - 1)) / slots
    if (thumbnailWidth <= 0.dp || height <= 0.dp) return
    val pager = rememberPagerState(pageCount = { pages })
    Column(Modifier.width(width), horizontalAlignment = Alignment.CenterHorizontally) {
        HorizontalPager(pager, Modifier.fillMaxWidth().height(height).testTag("call-speaker-rail"),
            userScrollEnabled = pages > 1) { page ->
            val start = page * slots
            val end = minOf(start + slots, others.size)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(tileGap, Alignment.CenterHorizontally)) {
                for (index in start until end) {
                    val tile = others[index]
                    key(tile.id) {
                        VideoTile(tile, state, phone = !wide, Modifier.width(thumbnailWidth).height(height),
                            compact = true, fitVideo = true, onSelect = { pin(tile.id) },
                            liveLevel = localMicrophoneLevel.takeIf { tile.id == "self" })
                    }
                }
            }
        }
        if (pageDots) {
            val scope = rememberCoroutineScope()
            Row(Modifier.widthIn(max = width).horizontalScroll(rememberScrollState())
                .padding(top = 2.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                repeat(pages) { index ->
                    val selected = pager.currentPage == index
                    Box(Modifier.size(28.dp).clip(CircleShape).selectable(selected, role = Role.Tab,
                        onClick = { scope.launch { pager.animateScrollToPage(index) } })
                        .semantics { contentDescription = "Страница ${index + 1} из $pages" }
                        .testTag("call-speaker-page-${index + 1}"), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(7.dp).background(if (selected) CallForeground else Color(0xFF82758B), CircleShape))
                    }
                }
            }
        }
    }
}

@Composable
private fun CallSpeakerBadge(unpin: (() -> Unit)?, modifier: Modifier) {
    val pinned = unpin != null
    val shape = RoundedCornerShape(8.dp)
    val foreground = if (pinned) CallForeground else Color(0xFF88DBB4)
    Row(modifier.clip(shape).background(if (pinned) Color(0xEF292033) else Color(0xED192E28))
        .border(1.dp, if (pinned) Color(0xFFA98BBD) else Color(0xFF456B5D), shape)
        .then(if (unpin != null) Modifier.clickable(role = Role.Button, onClick = unpin).testTag("call-unpin") else Modifier)
        .heightIn(min = if (pinned) 36.dp else 30.dp).padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (pinned) Icons.Outlined.PushPin else CallIcons.AudioLines, null, Modifier.size(14.dp), tint = foreground)
        Text(if (pinned) "Открепить" else "Говорит", color = foreground, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}
