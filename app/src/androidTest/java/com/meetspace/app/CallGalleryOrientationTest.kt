package com.meetspace.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.meetspace.app.ui.CallScreen
import com.meetspace.app.ui.CallTextPanelState
import com.meetspace.app.ui.CallUiDetails
import com.meetspace.core.designsystem.MeetSpaceTheme
import com.meetspace.core.media.CallParticipant
import com.meetspace.core.media.CallPhase
import com.meetspace.core.media.CallState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

@OptIn(ExperimentalTestApi::class)
class CallGalleryOrientationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pairStacksInPortraitAndReturnsToOneRowAfterRotation() {
        var viewport by mutableStateOf(DpSize(1194.dp, 834.dp))
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(viewport)) {
                MeetSpaceTheme { CallScreen(state(), details = CallUiDetails(chat = CallTextPanelState(canSend = true))) }
            }
        }
        compose.onNodeWithTag("call-layout-gallery").assertIsSelected()
        assertPair(stacked = false)
        compose.runOnIdle { viewport = DpSize(834.dp, 1194.dp) }
        compose.onNodeWithTag("call-layout-gallery").assertIsSelected()
        assertPair(stacked = true)
        capture("portrait-gallery")

        // The stage also becomes tall/narrow when the tablet side panel opens.
        compose.onNodeWithTag("call-chat").performClick()
        val chat = bounds("call-chat-panel")
        assertPair(stacked = true)
        assertTrue(bounds("call-participant-remote").right <= chat.left)
        compose.onNodeWithTag("call-chat").performClick()

        // Cross the compact breakpoint while retaining even the initial default mode.
        compose.runOnIdle { viewport = DpSize(720.dp, 1024.dp) }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("call-layout-gallery").assertIsSelected()
        assertPair(stacked = true)
        compose.runOnIdle { viewport = DpSize(1194.dp, 834.dp) }
        compose.onNodeWithTag("call-layout-gallery").assertIsSelected()
        assertPair(stacked = false)
        capture("landscape-gallery")
    }

    @Test fun portraitGroupsKeepFittedTilesAndPinnedParticipantSurvivesRotation() {
        var viewport by mutableStateOf(DpSize(834.dp, 1194.dp))
        val restoration = StateRestorationTester(compose)
        var roster by mutableStateOf(listOf(
            CallParticipant("remote", "Анна", true, true, false),
            CallParticipant("second", "Мария", true, false, false),
            CallParticipant("self", "Александр", true, true, false),
        ))
        restoration.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(viewport)) {
                MeetSpaceTheme { CallScreen(state().copy(participants = roster)) }
            }
        }
        compose.onNodeWithTag("call-layout-gallery").assertIsSelected()
        val triple = listOf("remote", "second", "self").map { bounds("call-participant-$it") }
        triple.forEach(::assertFitted)
        assertTrue(triple.zipWithNext().all { (previous, next) -> previous.bottom < next.top })

        compose.runOnIdle { roster = roster + CallParticipant("third", "Дмитрий", true, false, false) }
        val grid = listOf("remote", "second", "third", "self").map { bounds("call-participant-$it") }
        grid.forEach(::assertFitted)
        assertEquals("Larger portrait groups use a compact two-column grid", 2, grid.map { it.left }.distinct().size)
        assertEquals(2, grid.map { it.top }.distinct().size)

        compose.onNodeWithTag("call-participant-second").performClick()
        compose.onNodeWithTag("call-layout-speaker").assertIsSelected()
        assertPinned()
        compose.runOnIdle { viewport = DpSize(1194.dp, 834.dp) }
        compose.onNodeWithTag("call-layout-speaker").assertIsSelected()
        assertPinned()
        compose.runOnIdle { viewport = DpSize(720.dp, 1024.dp) }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("call-layout-speaker").assertIsSelected()
        assertPinned()
    }

    @Test fun speakerPairKeepsEqualTilesAndAlignsWithControlsAfterRotation() {
        var viewport by mutableStateOf(DpSize(720.dp, 1024.dp))
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(viewport)) {
                MeetSpaceTheme { CallScreen(state(), details = CallUiDetails(chat = CallTextPanelState(canSend = true))) }
            }
        }

        for (portrait in listOf(viewport, DpSize(834.dp, 1194.dp), DpSize(834.dp, 844.dp), DpSize(390.dp, 844.dp))) {
            compose.runOnIdle { viewport = portrait }
            compose.onNodeWithTag("call-layout-speaker").assertIsSelected()
            val featured = bounds("call-featured-video")
            val thumbnail = bounds("call-participant-self")
            assertTrue(featured.bottom < thumbnail.top)
            assertEqualTileSize(featured, thumbnail)
            assertControlWidth(featured.left, featured.right)
            assertControlWidth(thumbnail.left, thumbnail.right)
            if (portrait == DpSize(834.dp, 1194.dp)) capture("portrait-speaker-wide")
        }

        compose.onNodeWithTag("call-chat").performClick()
        val featuredWithChat = bounds("call-featured-video")
        val peerWithChat = bounds("call-participant-self")
        assertEqualTileSize(featuredWithChat, peerWithChat)
        assertTrue(featuredWithChat.bottom < peerWithChat.top && peerWithChat.bottom <= bounds("call-chat-panel").top)
        compose.onNodeWithTag("call-chat").performClick()

        compose.runOnIdle { viewport = DpSize(844.dp, 390.dp) }
        compose.onNodeWithTag("call-layout-speaker").assertIsSelected()
        val rotatedFeatured = bounds("call-featured-video")
        val rotatedThumbnail = bounds("call-participant-self")
        assertTrue(rotatedFeatured.right < rotatedThumbnail.left)
        assertEqualTileSize(rotatedFeatured, rotatedThumbnail)
        assertControlWidth(rotatedFeatured.left, rotatedThumbnail.right)
        capture("landscape-speaker-wide")

        compose.onNodeWithTag("call-layout-gallery").performClick().assertIsSelected()
        val galleryRemote = bounds("call-participant-remote")
        val gallerySelf = bounds("call-participant-self")
        assertTrue(galleryRemote.right < gallerySelf.left)
        assertControlWidth(galleryRemote.left, gallerySelf.right)

        compose.runOnIdle { viewport = DpSize(1194.dp, 834.dp) }
        compose.onNodeWithTag("call-layout-speaker").performClick().assertIsSelected()
        val tabletFeatured = bounds("call-featured-video")
        val tabletPeer = bounds("call-participant-self")
        assertTrue(tabletFeatured.right < tabletPeer.left)
        assertEqualTileSize(tabletFeatured, tabletPeer)
    }

    private fun assertEqualTileSize(featured: DpRect, peer: DpRect) {
        assertEquals("The presenter and interlocutor have equal widths",
            (featured.right - featured.left).value, (peer.right - peer.left).value, 1f)
        assertEquals("The presenter and interlocutor have equal heights",
            (featured.bottom - featured.top).value, (peer.bottom - peer.top).value, 1f)
    }

    private fun assertControlWidth(left: Dp, right: Dp) {
        val controls = bounds("call-controls")
        assertEquals(controls.left.value, left.value, 1f)
        assertEquals(controls.right.value, right.value, 1f)
    }

    private fun state() = CallState(CallPhase.CONNECTED, conferenceId = "portrait-layout-fixture", title = "Встреча",
        participantId = "self", microphone = true, participants = listOf(
            CallParticipant("remote", "Анна", true, true, false),
            CallParticipant("self", "Александр", true, true, false),
        ))

    private fun bounds(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)
        .assertIsDisplayed().getUnclippedBoundsInRoot()

    private fun assertPair(stacked: Boolean) {
        val remote = bounds("call-participant-remote")
        val self = bounds("call-participant-self")
        assertFitted(remote); assertFitted(self)
        assertEquals((remote.right - remote.left).value, (self.right - self.left).value, 1f)
        if (stacked) {
            assertTrue("Portrait participants must be one above the other", remote.bottom < self.top)
            assertEquals(remote.left.value, self.left.value, 1f)
        } else {
            assertTrue("Landscape participants must be side by side", remote.right < self.left)
            assertEquals(remote.top.value, self.top.value, 1f)
        }
    }

    private fun assertFitted(tile: DpRect) {
        val stage = bounds("call-grid")
        assertEquals("Camera tiles retain their 16:9 frame", (tile.right - tile.left).value * 9f / 16f,
            (tile.bottom - tile.top).value, 1.5f)
        assertTrue(tile.left >= stage.left && tile.right <= stage.right && tile.top >= stage.top && tile.bottom <= stage.bottom)
    }

    private fun assertPinned() {
        compose.onNode(hasTestTag("call-participant-second") and hasAnyAncestor(hasTestTag("call-featured-video")),
            useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("call-unpin").assertIsDisplayed()
    }

    private fun capture(name: String) {
        val image = compose.onNodeWithTag("call-screen").captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "$name.png").outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
