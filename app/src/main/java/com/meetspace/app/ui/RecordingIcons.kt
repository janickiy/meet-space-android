package com.meetspace.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

internal object RecordingIcons {
    val List by lazy { icon("List", "M9 6h11", "M9 12h11", "M9 18h11", "M4 6h.01", "M4 12h.01", "M4 18h.01") }
    val Grid by lazy { icon("Grid2X2", "M5 3H19a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2Z", "M12 3v18", "M3 12h18") }
    val FolderOpen by lazy { icon("FolderOpen",
        "m6 14 1.5-2.9A2 2 0 0 1 9.22 10h10.76a2 2 0 0 1 1.94 2.5l-1.54 5.99A2 2 0 0 1 18.44 20H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h3.9a2 2 0 0 1 1.69.9l.82 1.2a2 2 0 0 0 1.69.9H18a2 2 0 0 1 2 2v2") }

    private fun icon(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder("Recording$name", 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { path ->
                addPath(PathParser().parsePathString(path).toNodes(), stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            }
        }.build()
}
