package com.meetspace.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Outline paths shared with the first-run reference. */
internal object OnboardingIcons {
    val Server by lazy { icon("Server", "M4 2h16a2 2 0 0 1 2 2v4a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2Z",
        "M4 14h16a2 2 0 0 1 2 2v4a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2v-4a2 2 0 0 1 2-2Z", "M6 6h.01", "M6 18h.01") }
    val Shield by lazy { icon("ShieldCheck", "M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z", "m9 12 2 2 4-4") }
    val Lock by lazy { icon("LockKeyhole", "M5 10h14a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2Z", "M7 10V7a5 5 0 0 1 10 0v3", "M12 14v4") }

    private fun icon(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder("Onboarding$name", 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { path -> addPath(PathParser().parsePathString(path).toNodes(), stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) }
        }.build()
}
