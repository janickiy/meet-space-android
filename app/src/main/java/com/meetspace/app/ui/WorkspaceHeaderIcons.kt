package com.meetspace.app.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

internal val LocalWorkspaceHeaderIconSize = staticCompositionLocalOf { 27.dp }

/** The shared 24-unit outline paths and stroke used by the prototype's top toolbar. */
internal object WorkspaceHeaderIcons {
    val Calendar by lazy { icon("CalendarDays", "M8 2v3", "M16 2v3",
        "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2Z",
        "M3 9h18", "M8 13h.01", "M12 13h.01", "M16 13h.01", "M8 17h.01", "M12 17h.01", "M16 17h.01") }
    val Recordings by lazy { icon("CirclePlay",
        "M9 9.003a1 1 0 0 1 1.517-.859l4.997 2.997a1 1 0 0 1 0 1.718l-4.997 2.997A1 1 0 0 1 9 14.996Z",
        "M2 12a10 10 0 1 0 20 0a10 10 0 1 0-20 0") }
    val Bell by lazy { icon("Bell", "M10.268 21a2 2 0 0 0 3.464 0",
        "M3.262 15.326A1 1 0 0 0 4 17h16a1 1 0 0 0 .74-1.673C19.41 13.956 18 12.499 18 8A6 6 0 0 0 6 8c0 4.499-1.411 5.956-2.738 7.326") }

    private fun icon(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder("Workspace$name", 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { path ->
                addPath(PathParser().parsePathString(path).toNodes(), stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            }
        }.build()
}
