package com.meetspace.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Outline icons from the Android chat reference. */
internal object ChatMessageIcons {
    val Reply by lazy { icon("Reply", "M20 18v-2a4 4 0 0 0-4-4H4", "m9 17-5-5 5-5") }
    val Copy by lazy { icon("Copy", "M10 8h10a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H10a2 2 0 0 1-2-2V10a2 2 0 0 1 2-2Z",
        "M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2") }
    val Select by lazy { icon("Select", "M2 12a10 10 0 1 0 20 0a10 10 0 1 0-20 0", "m16 9-5.5 5.5L8 12") }
    val Delete by lazy { icon("Delete", "M10 11v6", "M14 11v6", "M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6", "M3 6h18",
        "M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2") }
    val Forward by lazy { icon("Forward", "M5 12h14", "m12 5 7 7-7 7") }
    val ChevronRight by lazy { icon("ChevronRight", "m9 18 6-6-6-6") }
    val Pin by lazy { icon("Pin", "M12 17v5",
        "M9 10.76a2 2 0 0 1-1.11 1.79l-1.78.9A2 2 0 0 0 5 15.24V16a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-.76a2 2 0 0 0-1.11-1.79l-1.78-.9A2 2 0 0 1 15 10.76V7a1 1 0 0 1 1-1 2 2 0 0 0 0-4H8a2 2 0 0 0 0 4 1 1 0 0 1 1 1z") }
    val Edit by lazy { icon("Edit",
        "M21.174 6.812a1 1 0 0 0-3.986-3.987L3.842 16.174a2 2 0 0 0-.5.83l-1.321 4.352a.5.5 0 0 0 .623.622l4.353-1.32a2 2 0 0 0 .83-.497z",
        "m15 5 4 4") }
    val Delivered by lazy { icon("Delivered", "M18 6 7 17l-5-5", "m22 10-7.5 7.5L13 16") }
    val Search by lazy { icon("Search", "m21 21-4.34-4.34", "M3 11a8 8 0 1 0 16 0a8 8 0 1 0-16 0") }
    val Bell by lazy { icon("Bell", "M10.268 21a2 2 0 0 0 3.464 0",
        "M3.262 15.326A1 1 0 0 0 4 17h16a1 1 0 0 0 .74-1.673C19.41 13.956 18 12.499 18 8A6 6 0 0 0 6 8c0 4.499-1.411 5.956-2.738 7.326") }
    val UserPlus by lazy { icon("UserPlus", "M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2",
        "M5 7a4 4 0 1 0 8 0a4 4 0 1 0-8 0", "M19 8v6", "M22 11h-6") }
    val Phone by lazy { icon("Phone", "M13.832 16.568a1 1 0 0 0 1.213-.303l.355-.465A2 2 0 0 1 17 15h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2A18 18 0 0 1 2 4a2 2 0 0 1 2-2h3a2 2 0 0 1 2 2v3a2 2 0 0 1-.8 1.6l-.468.351a1 1 0 0 0-.292 1.233 14 14 0 0 0 6.392 6.384") }
    val Plus by lazy { icon("Plus", "M5 12h14", "M12 5v14") }
    val Download by lazy { icon("Download", "M12 15V3", "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4", "m7 10 5 5 5-5") }

    private fun icon(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder("ChatMessage$name", 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { path ->
                addPath(
                    pathData = PathParser().parsePathString(path).toNodes(),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.75f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()
}
