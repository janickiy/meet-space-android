package com.meetspace.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import kotlin.math.log10

internal val SpeakingGreen = Color(0xFF38C976)
internal val SpeakingBackground = Color(0xFF173E2C)
internal val SpeakingForeground = Color(0xFFE4FFEF)

/** Logarithmic meter height, from quiet speech to a strong voice; never changes audio gain. */
internal fun microphoneFillFraction(rms: Float): Float {
    if (!rms.isFinite() || rms < .012f) return 0f
    return ((log10(rms) - log10(.012f)) / (log10(.25f) - log10(.012f))).coerceIn(0f, 1f)
}

@Composable
private fun animatedMicrophoneFill(rms: Float, enabled: Boolean): Float {
    // Remove animation state on mute/disconnect so a quick re-enable cannot revive old levels.
    if (!enabled) return 0f
    val target = microphoneFillFraction(rms)
    val intensity by animateFloatAsState(target,
        animationSpec = tween(if (target > 0f) 80 else 140, easing = LinearEasing), label = "microphone-level")
    return intensity
}

/** Only the capsule fills from bottom to top. The outline, button background and opacity stay fixed. */
@Composable
internal fun MicrophoneLevelIcon(rms: Float, enabled: Boolean, tint: Color, contentDescription: String?, modifier: Modifier = Modifier) {
    val fill = animatedMicrophoneFill(rms, enabled)
    Box(modifier) {
        Icon(CallIcons.Mic, contentDescription, Modifier.matchParentSize(), tint = tint)
        Icon(CallIcons.MicFill, null, Modifier.matchParentSize().drawWithContent {
            if (fill > 0f) clipRect(top = size.height * (15f - 13f * fill) / 24f) { this@drawWithContent.drawContent() }
        }, tint = SpeakingGreen)
    }
}

internal fun Modifier.speakingStatus(active: Boolean): Modifier = semantics {
    stateDescription = if (active) "Говорит" else "Не говорит"
}
