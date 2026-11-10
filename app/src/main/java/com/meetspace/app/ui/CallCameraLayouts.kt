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
    pin: (String) -> Unit,
    localMicrophoneLevel: StateFlow<Float>?,
) {
    if (tiles.isEmpty()) return
    val largeStage = wide && width >= 960.dp
    val columns = when {
        tiles.size == 1 -> 1
        largeStage && tiles.size != 2 && tiles.size != 4 -> 3
        else -> 2
    }
    val rows = tiles.chunked(columns)
    val visibleRows = rows.size.coerceAtMost(if (largeStage) 2 else 3)
    val tileGap = if (wide) 10.dp else 7.dp
    val rowGaps = tileGap * (visibleRows - 1)
    val columnGaps = tileGap * (columns - 1)
    val availableWidth = if (wide) width else width.coerceAtMost(540.dp)
    val fittedWidth = (height - rowGaps) / visibleRows * CameraAspect
    val tileWidth = ((availableWidth - columnGaps) / columns)
        .coerceAtMost(fittedWidth.coerceAtLeast(if (tiles.size == 1) 0.dp else 150.dp))
    if (tileWidth <= 0.dp) return

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
                            VideoTile(tile, state, phone = !wide, Modifier.width(tileWidth).aspectRatio(CameraAspect),
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
    pin: (String) -> Unit,
    unpin: (() -> Unit)?,
    localMicrophoneLevel: StateFlow<Float>?,
) {
    val pages = (others.size + SpeakerPageSize - 1) / SpeakerPageSize
    val pageDots = pages > 1 && height >= 130.dp
    val tileGap = if (wide) 10.dp else 7.dp
    val stackGap = if (wide) 10.dp else 8.dp
    val availableWidth = if (wide) width else width.coerceAtMost(540.dp)
    val railGaps = tileGap * (SpeakerPageSize - 1)
    val thumbnailAspect = if (wide) CameraAspect else 1f
    val thumbnailHeightLimit = if (wide) 82.dp.coerceAtMost(height * .35f) else height * .35f
    val thumbnailWidth = ((availableWidth - railGaps) / SpeakerPageSize)
        .coerceIn(0.dp, thumbnailHeightLimit * thumbnailAspect)
    val showRail = pages > 0 && thumbnailWidth > 0.dp
    val thumbnailHeight = thumbnailWidth / thumbnailAspect
    val railReserve = if (showRail) thumbnailHeight + stackGap + (if (pageDots) 30.dp else 14.dp) else 0.dp
    val frameWidth = availableWidth.coerceAtMost((height - railReserve).coerceAtLeast(0.dp) * CameraAspect)

    Column(Modifier.fillMaxSize().testTag("call-grid"), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        if (frameWidth > 0.dp) {
            Box(Modifier.width(frameWidth).aspectRatio(CameraAspect).testTag("call-featured-video")) {
                VideoTile(featured, state, phone = !wide, Modifier.matchParentSize(), fillStage = true, fitVideo = true,
                    liveLevel = localMicrophoneLevel.takeIf { featured.id == "self" })
                if (unpin != null || state.isSpeaking(featured.participantId)) {
                    CallSpeakerBadge(unpin, Modifier.align(Alignment.TopStart).padding(10.dp))
                }
            }
        }
        if (showRail) {
            if (frameWidth > 0.dp) Spacer(Modifier.height(stackGap))
            val pager = rememberPagerState(pageCount = { pages })
            HorizontalPager(pager, Modifier.width(thumbnailWidth * SpeakerPageSize + railGaps)
                .height(thumbnailHeight).testTag("call-speaker-rail"), userScrollEnabled = pages > 1) { page ->
                val start = page * SpeakerPageSize
                val end = minOf(start + SpeakerPageSize, others.size)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(tileGap, Alignment.CenterHorizontally)) {
                    for (index in start until end) {
                        val tile = others[index]
                        key(tile.id) {
                            VideoTile(tile, state, phone = !wide, Modifier.width(thumbnailWidth).height(thumbnailHeight),
                                compact = true, fitVideo = true, onSelect = { pin(tile.id) },
                                liveLevel = localMicrophoneLevel.takeIf { tile.id == "self" })
                        }
                    }
                }
            }
            if (pageDots) {
                val scope = rememberCoroutineScope()
                Row(Modifier.widthIn(max = availableWidth).horizontalScroll(rememberScrollState())
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
