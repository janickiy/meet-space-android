package com.meetspace.app

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.meetspace.core.designsystem.MeetSpaceTheme
import com.meetspace.core.model.ThemeMode
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Controlled UI fixtures: callbacks request work; the editor never simulates an upload ACK. */
@OptIn(ExperimentalTestApi::class)
class AvatarCropEditorUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun anEmptyEditorRequestsAnImageAndCancelDoesNotSave() {
        var chosen = 0
        var saved = 0
        var dismissed = 0
        compose.setContent { Frame(DpSize(390.dp, 780.dp)) {
            AvatarCropDialog("Фото профиля", null, AvatarCrop(), false, false, null,
                {}, { chosen++ }, { saved++ }, { dismissed++ })
        } }
        compose.onNodeWithTag("avatar-editor").assertIsDisplayed()
        compose.onNodeWithText("Фото профиля").assertIsDisplayed()
        assertTabletControlsAreBesidePreview()
        if (compose.onAllNodesWithTag("avatar-preview-placeholder").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("avatar-preview-placeholder").assertIsDisplayed()
            compose.onNodeWithTag("avatar-zoom").assertIsNotEnabled()
            compose.onNodeWithTag("avatar-reset").assertIsNotEnabled()
            val configuration = compose.activity.resources.configuration
            capture("avatar-editor-empty-${configuration.screenWidthDp}x${configuration.screenHeightDp}.png")
        }
        compose.onNodeWithTag("avatar-choose").assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, chosen); assertEquals(0, saved); assertEquals(0, dismissed) }
        if (compose.onAllNodesWithTag("avatar-save").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("avatar-save").assertIsNotEnabled()
        }
        compose.onNodeWithTag("avatar-cancel").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, dismissed); assertEquals(0, saved) }
    }

    @Test fun cropControlsDragResetAndReplaceKeepSaveExplicit() {
        var crop by mutableStateOf(AvatarCrop())
        var chosen = 0
        var saved = 0
        var dismissed = 0
        compose.setContent { Frame(DpSize(1000.dp, 834.dp)) {
            AvatarCropDialog("Фото группы", fixture, crop, false, false, null,
                { crop = it }, { chosen++ }, { saved++ }, { dismissed++ })
        } }
        compose.onNodeWithTag("avatar-zoom").assertIsEnabled().setProgress(2f)
        compose.onNodeWithTag("avatar-x").assertIsEnabled().setProgress(.6f)
        compose.onNodeWithTag("avatar-y").assertIsEnabled().setProgress(-.4f)
        compose.runOnIdle {
            assertEquals(2f, crop.zoom, .001f)
            assertEquals(.6f, crop.x, .001f)
            assertEquals(-.4f, crop.y, .001f)
            crop = AvatarCrop(zoom = 2f)
        }
        compose.onNodeWithTag("avatar-crop-stage").performTouchInput {
            swipe(center, center + Offset(42f, 24f), durationMillis = 180)
        }
        compose.runOnIdle {
            assertTrue("Dragging moves the photo horizontally", crop.x > 0f)
            assertTrue("Dragging moves the photo vertically when zoomed", crop.y > 0f)
            assertTrue(crop.x in -1f..1f && crop.y in -1f..1f)
        }
        compose.onNodeWithTag("avatar-reset").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(AvatarCrop(), crop); assertEquals(0, saved) }
        // Cover geometry has no vertical overflow for this landscape source at 100%.
        compose.onNodeWithTag("avatar-y").assertIsNotEnabled()
        compose.onNodeWithTag("avatar-choose").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, chosen); assertEquals(0, saved); assertEquals(AvatarCrop(), crop) }
        compose.onNodeWithTag("avatar-save").assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, saved); assertEquals(0, dismissed) }
        compose.onNodeWithTag("avatar-editor").assertExists()
    }

    @Test fun loadingAndSavingDisableActionsAndFailureKeepsTheCropForRetry() {
        var source by mutableStateOf<Bitmap?>(null)
        var loading by mutableStateOf(true)
        var saving by mutableStateOf(false)
        var error by mutableStateOf<String?>(null)
        var crop by mutableStateOf(AvatarCrop(2f, .3f, -.2f))
        var saved = 0
        var dismissed = 0
        compose.setContent { Frame(DpSize(390.dp, 780.dp)) {
            AvatarCropDialog("Фото профиля", source, crop, loading, saving, error,
                { crop = it }, {}, { saved++; saving = true }, { dismissed++ })
        } }
        compose.onNodeWithTag("avatar-editor-loading").assertExists()
        compose.onNodeWithTag("avatar-choose").assertIsNotEnabled()
        compose.runOnIdle { source = fixture; loading = false }
        compose.onNodeWithTag("avatar-save").assertIsEnabled().performClick()
        compose.onNodeWithTag("avatar-save").assertIsNotEnabled()
        compose.onNodeWithTag("avatar-cancel").assertIsNotEnabled()
        compose.onNodeWithTag("avatar-choose").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("avatar-zoom").assertIsNotEnabled()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        compose.onNodeWithTag("avatar-editor").assertExists()
        compose.runOnIdle { assertEquals(1, saved); assertEquals(0, dismissed); saving = false; error = "Не удалось сохранить фото. Повторите попытку." }
        compose.onNodeWithTag("avatar-editor-error").assertExists().assertTextContains("Не удалось сохранить фото", substring = true)
        compose.onNodeWithTag("avatar-save").assertIsDisplayed().assertIsEnabled()
        compose.runOnIdle { assertEquals(AvatarCrop(2f, .3f, -.2f), crop); assertEquals(0, dismissed) }
        compose.onNodeWithTag("avatar-save").performClick()
        compose.runOnIdle { assertEquals(2, saved); assertEquals(0, dismissed) }
    }

    /** The runner changes the disposable AVD's real wm size between fresh test processes.
     * A Compose ForcedSize cannot resize Dialog's separate Android window. */
    @Test fun footerStaysVisibleWhileControlsScrollAtTheCurrentDeviceSize() {
        var crop by mutableStateOf(AvatarCrop(2f))
        compose.setContent {
            MeetSpaceTheme(ThemeMode.LIGHT) { Box(Modifier.fillMaxSize()) {
                AvatarCropDialog("Фото группы", fixture, crop, false, false, null, { crop = it }, {}, {}, {})
            } }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("avatar-editor").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag("avatar-save").fetchSemanticsNodes().isNotEmpty()
        }
        val configuration = compose.activity.resources.configuration
        val dimensions = DpSize(configuration.screenWidthDp.dp, configuration.screenHeightDp.dp)
        captureDiagnostics(dimensions)
        assertTabletControlsAreBesidePreview()
        compose.onNodeWithTag("avatar-save").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("avatar-cancel").assertIsDisplayed()
        compose.onNodeWithTag("avatar-y").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("avatar-choose").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("avatar-save").assertIsDisplayed()
        compose.onNodeWithTag("avatar-cancel").assertIsDisplayed()
        capture("avatar-editor-${dimensions.width.value.toInt()}x${dimensions.height.value.toInt()}-font-${configuration.fontScale}-after-scroll.png")
    }

    /** Verify the actual Dialog window: both empty and populated editors keep the same columns. */
    private fun assertTabletControlsAreBesidePreview() {
        val editor = compose.onNodeWithTag("avatar-editor").fetchSemanticsNode().boundsInRoot
        val density = compose.activity.resources.displayMetrics.density
        val fontScale = compose.activity.resources.configuration.fontScale
        val wide = editor.width / density >= 560f && fontScale < 1.5f
        if (!wide) {
            compose.onNodeWithTag("avatar-editor-preview-panel").assertDoesNotExist()
            return
        }
        val preview = compose.onNodeWithTag("avatar-editor-preview-panel").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val controls = compose.onNodeWithTag("avatar-editor-controls").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val choose = compose.onNodeWithTag("avatar-choose").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Controls must be to the right of the preview", controls.left >= preview.right + 20f * density)
        assertEquals("Preview and controls share the same top", preview.top, controls.top, 2f)
        assertTrue("The file button belongs to the right column", choose.left >= controls.left && choose.right <= controls.right)
        if (compose.activity.resources.configuration.screenHeightDp >= 700 && fontScale == 1f) {
            val previewNode = compose.onNodeWithTag("avatar-editor-preview-panel").fetchSemanticsNode()
            assertEquals("The full preview fits without scrolling on a tablet", previewNode.size.height.toFloat(), preview.height, 2f)
        }
        val save = compose.onNodeWithTag("avatar-save").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val cancel = compose.onNodeWithTag("avatar-cancel").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals("Save stays at the right edge of the footer", editor.right - 24f * density, save.right, 2f)
        assertTrue("Cancel precedes Save without overlap", cancel.right < save.left)
        assertEquals("Footer actions share a row", cancel.center.y, save.center.y, 2f)
    }

    /** Capture layout evidence before the unchanged visibility assertions run. */
    private fun captureDiagnostics(size: DpSize) {
        val prefix = "avatar-editor-${size.width.value.toInt()}x${size.height.value.toInt()}-font-${compose.activity.resources.configuration.fontScale}"
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = instrumentation.targetContext.cacheDir
        val lines = mutableListOf("requestedSize=$size")
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            lines += "activityDecor=${view.width}x${view.height}"
            val configuration = compose.activity.resources.configuration
            lines += "activityConfiguration=${configuration.screenWidthDp}x${configuration.screenHeightDp}, densityDpi=${configuration.densityDpi}"
        }
        for (tag in listOf("avatar-editor", "avatar-crop-stage", "avatar-editor-content", "avatar-save", "avatar-cancel", "avatar-choose")) {
            lines += runCatching {
                val interaction = compose.onNodeWithTag(tag)
                val node = interaction.fetchSemanticsNode()
                "$tag boundsInRoot=${node.boundsInRoot}, positionInWindow=${node.positionInWindow}, size=${node.size}, displayed=${interaction.isDisplayed()}"
            }.getOrElse { "$tag diagnosticError=${it.javaClass.simpleName}: ${it.message}" }
        }
        lines += runCatching {
            "roots=" + compose.onAllNodes(isRoot()).fetchSemanticsNodes().joinToString { "${it.boundsInRoot}, positionInWindow=${it.positionInWindow}, size=${it.size}" }
        }.getOrElse { "rootsError=${it.message}" }
        runCatching { capture("$prefix-before.png") }.onFailure { lines += "editorCaptureError=${it.javaClass.simpleName}: ${it.message}" }
        runCatching {
            checkNotNull(instrumentation.uiAutomation.takeScreenshot()).also { image ->
                File(directory, "$prefix-window-before.png").outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                image.recycle()
            }
        }.onFailure { lines += "windowCaptureError=${it.javaClass.simpleName}: ${it.message}" }
        val report = lines.joinToString("\n") + "\n"
        File(directory, "$prefix-bounds.txt").writeText(report)
        Log.i("AvatarEditorBounds", report)
    }

    private fun SemanticsNodeInteraction.setProgress(value: Float) = performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(value)) }

    @Composable private fun Frame(size: DpSize, content: @Composable () -> Unit) {
        DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(size)) {
            MeetSpaceTheme(ThemeMode.LIGHT) { Box(Modifier.fillMaxSize()) { content() } }
        }
    }

    private fun capture(name: String) {
        val image = compose.onNodeWithTag("avatar-editor").captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, name).outputStream().use {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    companion object {
        // Tiny non-personal fixture retained for the instrumentation process, never recycled while drawn.
        private val fixture by lazy { Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(65, 128, 198)) } }
    }
}
