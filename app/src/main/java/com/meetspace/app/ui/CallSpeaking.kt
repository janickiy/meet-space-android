package com.meetspace.app.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription

internal val SpeakingGreen = Color(0xFF38C976)
internal val SpeakingBackground = Color(0xFF173E2C)
internal val SpeakingForeground = Color(0xFFE4FFEF)

internal fun Modifier.speakingStatus(active: Boolean): Modifier = semantics {
    stateDescription = if (active) "Говорит" else "Не говорит"
}
