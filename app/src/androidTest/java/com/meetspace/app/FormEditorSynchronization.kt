package com.meetspace.app

import android.os.SystemClock
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.DpRect
import java.util.concurrent.atomic.AtomicInteger

/** Tracks the platform editor without changing the form or its callback behavior. */
internal class FormEditorWindow {
    val imeBottom = AtomicInteger(0)
    var dismiss: () -> Unit = {}

    @Composable
    fun Observe() {
        val focus = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        val inset = WindowInsets.ime.getBottom(LocalDensity.current)
        SideEffect {
            imeBottom.set(inset)
            dismiss = { focus.clearFocus(force = true); keyboard?.hide() }
        }
    }
}

/**
 * Compose idle does not await the platform IME animation. Form callback tests dismiss
 * the editor, then scroll and wait for stable geometry before retaining a real pointer click.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeContentTestRule.settleFormEditorForClick(
    editor: FormEditorWindow,
    target: SemanticsNodeInteraction,
    scroll: Boolean = true,
): SemanticsNodeInteraction {
    runOnIdle { editor.dismiss() }
    waitUntil(timeoutMillis = 5_000) { editor.imeBottom.get() == 0 }
    if (scroll) target.performScrollTo()
    var bounds: DpRect? = null
    var stableSince = SystemClock.uptimeMillis()
    waitUntil(timeoutMillis = 5_000) {
        val current = target.getUnclippedBoundsInRoot()
        val now = SystemClock.uptimeMillis()
        if (current != bounds || editor.imeBottom.get() != 0) {
            bounds = current
            stableSince = now
        }
        editor.imeBottom.get() == 0 && now - stableSince >= 350
    }
    return target.assertIsDisplayed()
}
