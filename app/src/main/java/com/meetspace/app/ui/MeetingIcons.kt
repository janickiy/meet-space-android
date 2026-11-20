package com.meetspace.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Outline paths from the meeting prototype's icon set. */
internal object MeetingIcons {
    val ArrowUpRight by lazy { icon("ArrowUpRight", "M7 7h10v10", "M7 17 17 7") }
    val Filters by lazy {
        icon("SlidersHorizontal", "M10 5H3", "M12 19H3", "M14 3v4", "M16 17v4",
            "M21 12h-9", "M21 19h-5", "M21 5h-7", "M8 10v4", "M8 12H3")
    }
    val Reset by lazy {
        icon("RotateCcw", "M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8", "M3 3v5h5")
    }
    val Folder by lazy {
        icon("Folder", "M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z")
    }
    val Clock by lazy { icon("Clock", "M2 12a10 10 0 1 0 20 0a10 10 0 1 0-20 0", "M12 6v6l4 2") }
    val CancelCalendar by lazy {
        icon("CalendarX", "M8 2v3", "M16 2v3",
            "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2Z",
            "M3 9h18", "m14 13-4 4", "m10 13 4 4")
    }
    val Edit by lazy {
        icon("SquarePen", "M12 3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7",
            "M18.375 2.625a1 1 0 0 1 3 3l-9.013 9.014a2 2 0 0 1-.853.505l-2.873.84a.5.5 0 0 1-.62-.62l.84-2.873a2 2 0 0 1 .506-.852z")
    }

    private fun icon(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder("Meeting$name", 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { path ->
                addPath(PathParser().parsePathString(path).toNodes(), stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            }
        }.build()
}
