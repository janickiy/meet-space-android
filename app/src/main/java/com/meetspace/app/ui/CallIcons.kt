package com.meetspace.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** The same outline paths as the concept's assets/icons.js; no bitmap or platform-font dependency. */
internal object CallIcons {
    val Mic by lazy { icon("Mic", "M12 19v3", "M19 10v2a7 7 0 0 1-14 0v-2", "M12 2H12a3 3 0 0 1 3 3V12a3 3 0 0 1 -3 3H12a3 3 0 0 1 -3 -3V5a3 3 0 0 1 3 -3Z") }
    val MicOff by lazy { icon("MicOff", "M12 19v3", "M15 9.34V5a3 3 0 0 0-5.68-1.33", "M16.95 16.95A7 7 0 0 1 5 12v-2", "M18.89 13.23A7 7 0 0 0 19 12v-2", "m2 2 20 20", "M9 9v3a3 3 0 0 0 5.12 2.12") }
    val Video by lazy { icon("Video", "m16 13 5.223 3.482a.5.5 0 0 0 .777-.416V7.87a.5.5 0 0 0-.752-.432L16 10.5", "M4 6H14a2 2 0 0 1 2 2V16a2 2 0 0 1 -2 2H4a2 2 0 0 1 -2 -2V8a2 2 0 0 1 2 -2Z") }
    val VideoOff by lazy { icon("VideoOff", "M10.66 6H14a2 2 0 0 1 2 2v2.5l5.248-3.062A.5.5 0 0 1 22 7.87v8.196", "M16 16a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h2", "m2 2 20 20") }
    val Users by lazy { icon("Users", "M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2", "M16 3.128a4 4 0 0 1 0 7.744", "M22 21v-2a4 4 0 0 0-3-3.87", "M5 7a4 4 0 1 0 8 0a4 4 0 1 0 -8 0") }
    val MessageCircle by lazy { icon("MessageCircle", "M2.992 16.342a2 2 0 0 1 .094 1.167l-1.065 3.29a1 1 0 0 0 1.236 1.168l3.413-.998a2 2 0 0 1 1.099.092 10 10 0 1 0-4.777-4.719") }
    val MessagesSquare by lazy { icon("MessagesSquare", "M16 10a2 2 0 0 1-2 2H6.828a2 2 0 0 0-1.414.586l-2.202 2.202A.71.71 0 0 1 2 14.286V4a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2z", "M20 9a2 2 0 0 1 2 2v10.286a.71.71 0 0 1-1.212.502l-2.202-2.202A2 2 0 0 0 17.172 19H10a2 2 0 0 1-2-2v-1") }
    val PhoneOff by lazy { icon("PhoneOff", "M10.1 13.9a14 14 0 0 0 3.732 2.668 1 1 0 0 0 1.213-.303l.355-.465A2 2 0 0 1 17 15h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2 18 18 0 0 1-12.728-5.272", "M22 2 2 22", "M4.76 13.582A18 18 0 0 1 2 4a2 2 0 0 1 2-2h3a2 2 0 0 1 2 2v3a2 2 0 0 1-.8 1.6l-.468.351a1 1 0 0 0-.292 1.233 14 14 0 0 0 .244.473") }
    val MonitorUp by lazy { icon("MonitorUp", "m9 10 3-3 3 3", "M12 13V7", "M4 3H20a2 2 0 0 1 2 2V15a2 2 0 0 1 -2 2H4a2 2 0 0 1 -2 -2V5a2 2 0 0 1 2 -2Z", "M12 17v4", "M8 21h8") }
    val Monitor by lazy { icon("Monitor", "M4 3H20a2 2 0 0 1 2 2V15a2 2 0 0 1 -2 2H4a2 2 0 0 1 -2 -2V5a2 2 0 0 1 2 -2Z", "M8 21L16 21", "M12 17L12 21") }
    val Ellipsis by lazy { icon("Ellipsis", "M11 12a1 1 0 1 0 2 0a1 1 0 1 0 -2 0", "M18 12a1 1 0 1 0 2 0a1 1 0 1 0 -2 0", "M4 12a1 1 0 1 0 2 0a1 1 0 1 0 -2 0") }
    val AudioLines by lazy { icon("AudioLines", "M2 10v3", "M6 6v11", "M10 3v18", "M14 8v7", "M18 5v13", "M22 10v3") }
    val ArrowUp by lazy { icon("ArrowUp", "m5 12 7-7 7 7", "M12 19V5") }
    val X by lazy { icon("X", "M18 6 6 18", "m6 6 12 12") }

    val CircleDot by lazy { icon("CircleDot", "M11 12a1 1 0 1 0 2 0a1 1 0 1 0 -2 0", "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0") }
    val Circle by lazy { icon("Circle", "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0") }
    val Pause by lazy { icon("Pause", "M15 3H18a1 1 0 0 1 1 1V20a1 1 0 0 1 -1 1H15a1 1 0 0 1 -1 -1V4a1 1 0 0 1 1 -1Z", "M6 3H9a1 1 0 0 1 1 1V20a1 1 0 0 1 -1 1H6a1 1 0 0 1 -1 -1V4a1 1 0 0 1 1 -1Z") }
    val Play by lazy { icon("Play", "M5 5a2 2 0 0 1 3.008-1.728l11.997 6.998a2 2 0 0 1 .003 3.458l-12 7A2 2 0 0 1 5 19z") }
    val Square by lazy { icon("Square", "M5 3H19a2 2 0 0 1 2 2V19a2 2 0 0 1 -2 2H5a2 2 0 0 1 -2 -2V5a2 2 0 0 1 2 -2Z") }
    val ChevronDown by lazy { icon("ChevronDown", "m6 9 6 6 6-6") }
    val Paperclip by lazy { icon("Paperclip", "m16 6-8.414 8.586a2 2 0 0 0 2.829 2.829l8.414-8.586a4 4 0 1 0-5.657-5.657l-8.379 8.551a6 6 0 1 0 8.485 8.485l8.379-8.551") }
    val FileImage by lazy { icon("FileImage", "M6 22a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h8a2.4 2.4 0 0 1 1.704.706l3.588 3.588A2.4 2.4 0 0 1 20 8v12a2 2 0 0 1-2 2z", "M14 2v5a1 1 0 0 0 1 1h5", "M8 12a2 2 0 1 0 4 0a2 2 0 1 0 -4 0", "m20 17-1.296-1.296a2.41 2.41 0 0 0-3.408 0L9 22") }
    val FileText by lazy { icon("FileText", "M6 22a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h8a2.4 2.4 0 0 1 1.704.706l3.588 3.588A2.4 2.4 0 0 1 20 8v12a2 2 0 0 1-2 2z", "M14 2v5a1 1 0 0 0 1 1h5", "M10 9H8", "M16 13H8", "M16 17H8") }
    val Download by lazy { icon("Download", "M12 15V3", "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4", "m7 10 5 5 5-5") }

    private fun icon(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name = "Call$name", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            paths.forEach { data -> addPath(pathData = PathParser().parsePathString(data).toNodes(), fill = null,
                stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) }
        }.build()
}
