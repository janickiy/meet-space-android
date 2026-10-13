package com.meetspace.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CallEnd
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Static artwork only: these decorative initials and controls never represent session data. */
@Composable
internal fun OnboardingArtwork(modifier: Modifier = Modifier) {
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val panel = if (dark) Color(0xFF303E54) else Color.White
    val panelBorder = if (dark) Color(0xFF40506B) else Color.White
    val ink = if (dark) Color(0xFFD5DFF0) else Color(0xFF535F78)
    val orbit = if (dark) Color(0xFF4A5B79) else Color(0xFFDBE2F0)
    val accent = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current
    BoxWithConstraints(modifier.clipToBounds().clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        val scale = minOf(maxWidth.value / 400f, maxHeight.value / 285f)
        // The illustration scales as one drawing. Font accessibility is handled by the
        // adjacent heading and actionable controls, outside this semantic-free artwork.
        CompositionLocalProvider(LocalDensity provides Density(density.density, 1f)) {
            Box(Modifier.requiredSize(400.dp, 285.dp).graphicsLayer { scaleX = scale; scaleY = scale }, contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize().graphicsLayer {
                    compositingStrategy = CompositingStrategy.Offscreen
                }.drawWithContent {
                    drawContent()
                    // The larger decorative orbits extend past the viewport. Fade only
                    // their canvas so their edges dissolve without dimming the artwork.
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to Color.Transparent,
                            .15f to Color.Black,
                            .85f to Color.Black,
                            1f to Color.Transparent,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                }) {
                    val center = Offset(200.dp.toPx(), 148.dp.toPx())
                    val glow = Brush.radialGradient(listOf((if (dark) Color(0xFF27354B) else Color(0xFFF1F4FC)).copy(alpha = .85f), Color.Transparent), center, 200.dp.toPx())
                    drawCircle(glow, 200.dp.toPx(), center)
                    drawCircle(orbit, 123.dp.toPx(), center, style = Stroke(1.dp.toPx()))
                    drawCircle(orbit.copy(alpha = .7f), 166.5.dp.toPx(), center,
                        style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 5.dp.toPx()))))
                    drawCircle(orbit.copy(alpha = .4f), 209.dp.toPx(), center, style = Stroke(1.dp.toPx()))
                    rotate(-25f, Offset(200.dp.toPx(), 165.dp.toPx())) {
                        drawLine(if (dark) Color(0xFF536484) else Color(0xFFCAD4E8), Offset(40.dp.toPx(), 165.dp.toPx()), Offset(360.dp.toPx(), 165.dp.toPx()),
                            strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 5.dp.toPx())))
                    }
                }
                Box(Modifier.offset(y = (-6).dp).size(220.dp, 168.dp).rotate(-7f)) {
                    Surface(Modifier.fillMaxSize(), shape = RoundedCornerShape(16.dp), color = panel, border = BorderStroke(5.dp, panelBorder), shadowElevation = 9.dp) {
                        Column(Modifier.padding(5.dp)) {
                            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                ArtworkPerson("АК", Color(0xFFDEDBF2), Color(0xFF5B5393), Modifier.weight(1f))
                                ArtworkPerson("МИ", Color(0xFFD6E5EF), Color(0xFF4D7A91), Modifier.weight(1f))
                            }
                            Row(Modifier.fillMaxWidth().height(30.dp), horizontalArrangement = Arrangement.spacedBy(13.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                                ArtworkControl(Icons.Outlined.Videocam, dark)
                                ArtworkControl(Icons.Outlined.MicNone, dark)
                                ArtworkControl(Icons.Outlined.CallEnd, dark, end = true)
                            }
                        }
                    }
                    Surface(Modifier.offset(x = 12.dp, y = (-16).dp), shape = RoundedCornerShape(6.dp), color = panel, shadowElevation = 3.dp) {
                        Row(Modifier.padding(horizontal = 9.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Box(Modifier.size(5.dp).background(Color(0xFF65A98D), CircleShape))
                            Text("Встреча команды", fontSize = 8.sp, lineHeight = 10.sp, color = ink)
                        }
                    }
                }
                Surface(Modifier.offset(x = 72.dp, y = 93.dp).rotate(4f), shape = RoundedCornerShape(12.dp), color = panel,
                    border = BorderStroke(1.dp, if (dark) panelBorder else Color(0xFFE6EAF2)), shadowElevation = 3.dp) {
                    Row(Modifier.padding(horizontal = 13.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.ChatBubbleOutline, null, Modifier.size(16.dp), tint = accent)
                        Column {
                            Text("Хорошие идеи", fontSize = 10.sp, lineHeight = 16.sp, color = ink)
                            Text("начинаются здесь.", fontSize = 10.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, color = ink)
                        }
                        Text("09:41", Modifier.align(Alignment.Bottom), fontSize = 7.sp, lineHeight = 10.sp, color = if (dark) Color(0xFFB3A9DA) else Color(0xFFA1A9B8))
                    }
                }
                Surface(Modifier.offset(x = (-154).dp, y = 42.dp).size(44.dp).rotate(-12f), shape = RoundedCornerShape(15.dp),
                    color = if (dark) Color(0xFF2D4E49) else Color(0xFFEDF7F1), border = BorderStroke(4.dp, if (dark) Color(0xFF35404E) else Color(0xFFF7FAFB))) {
                    Icon(Icons.Outlined.VerifiedUser, null, Modifier.padding(10.dp), tint = if (dark) Color(0xFF99C9B4) else Color(0xFF62A687))
                }
                Text("✦", Modifier.offset(x = 139.dp, y = (-91).dp), fontSize = 35.sp, lineHeight = 40.sp, color = Color(0xFFB3A9DA))
            }
        }
    }
}

@Composable
private fun ArtworkPerson(initials: String, background: Color, ink: Color, modifier: Modifier) {
    Box(modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(background), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val centerX = size.width / 2f
            drawOval(Color.White.copy(alpha = .22f), topLeft = Offset(centerX - 47.dp.toPx(), size.height - 50.dp.toPx()), size = androidx.compose.ui.geometry.Size(94.dp.toPx(), 90.dp.toPx()))
            drawCircle(Color.White.copy(alpha = .22f), 21.dp.toPx(), Offset(centerX, size.height - 61.dp.toPx()))
        }
        Text(initials, fontSize = 30.sp, lineHeight = 38.sp, letterSpacing = (-2).sp, fontWeight = FontWeight.SemiBold, color = ink)
    }
}

@Composable
private fun ArtworkControl(icon: ImageVector, dark: Boolean, end: Boolean = false) {
    Surface(Modifier.size(23.dp), shape = RoundedCornerShape(7.dp),
        color = if (end) { if (dark) Color(0xFF694351) else Color(0xFFFCE6E7) } else if (dark) Color(0xFF3B4C68) else Color(0xFFF3F5F8)) {
        Icon(icon, null, Modifier.padding(5.5.dp), tint = if (end) { if (dark) Color(0xFFFAC0CC) else Color(0xFFD66571) } else if (dark) Color(0xFFBED0ED) else Color(0xFF748097))
    }
}
