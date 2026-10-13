package com.meetspace.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meetspace.core.model.ThemeMode

/** Shared tokens from the MeetSpace Android concept (8766) and its three-tab shell. */
object MeetSpaceTokens {
    val MinimumTouchTarget = 48.dp
    val FormWidth = 460.dp
    val ContentWidth = 1040.dp
    val PagePadding = 24.dp
    val CardShape = RoundedCornerShape(24.dp)
    val BrandBlue = Color(0xFF3668ED)
    val BrandViolet = Color(0xFF8545FF)
    val BrandCyan = Color(0xFF00B9F4)
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF3668ED),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEAF0FF),
    onPrimaryContainer = Color(0xFF3668ED),
    secondary = Color(0xFF5976BE),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEAF0FF),
    onSecondaryContainer = Color(0xFF294DAD),
    tertiary = Color(0xFF21816B),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDFF3EC),
    onTertiaryContainer = Color(0xFF165644),
    background = Color(0xFFF6F7FB),
    onBackground = Color(0xFF202737),
    surface = Color.White,
    onSurface = Color(0xFF202737),
    surfaceVariant = Color(0xFFEEF1F7),
    onSurfaceVariant = Color(0xFF737B8D),
    surfaceTint = Color.White,
    surfaceDim = Color(0xFFEEF1F7),
    surfaceBright = Color.White,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF6F7FB),
    surfaceContainer = Color(0xFFEEF1F7),
    surfaceContainerHigh = Color(0xFFE7ECF5),
    surfaceContainerHighest = Color(0xFFE5E8F0),
    inverseSurface = Color(0xFF202737),
    inverseOnSurface = Color(0xFFF0F3FA),
    inversePrimary = Color(0xFF8BA9FF),
    outline = Color(0xFF737B8D),
    outlineVariant = Color(0xFFE5E8F0),
    error = Color(0xFFD14857),
    onError = Color.White,
    errorContainer = Color(0xFFFFE9EC),
    onErrorContainer = Color(0xFF8B1C2E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8BA9FF),
    onPrimary = Color(0xFF1B2D5B),
    primaryContainer = Color(0xFF2B3857),
    onPrimaryContainer = Color(0xFF8BA9FF),
    secondary = Color(0xFFB7C8F4),
    onSecondary = Color(0xFF1B2D5B),
    secondaryContainer = Color(0xFF2B3857),
    onSecondaryContainer = Color(0xFFDFE7FF),
    tertiary = Color(0xFF7BD6BB),
    onTertiary = Color(0xFF07382D),
    tertiaryContainer = Color(0xFF21483E),
    onTertiaryContainer = Color(0xFFADEEDC),
    background = Color(0xFF141416),
    onBackground = Color(0xFFF3F3F6),
    surface = Color(0xFF232326),
    onSurface = Color(0xFFF3F3F6),
    surfaceVariant = Color(0xFF38393F),
    onSurfaceVariant = Color(0xFFA3A3AF),
    surfaceTint = Color(0xFF232326),
    surfaceDim = Color(0xFF141416),
    surfaceBright = Color(0xFF38393F),
    surfaceContainerLowest = Color(0xFF141416),
    surfaceContainerLow = Color(0xFF232326),
    surfaceContainer = Color(0xFF29292D),
    surfaceContainerHigh = Color(0xFF38393F),
    surfaceContainerHighest = Color(0xFF414148),
    inverseSurface = Color(0xFFF3F3F6),
    inverseOnSurface = Color(0xFF202737),
    inversePrimary = Color(0xFF3668ED),
    outline = Color(0xFF8D8D9B),
    outlineVariant = Color(0xFF3A3A42),
    error = Color(0xFFFF8C97),
    onError = Color(0xFF5F1424),
    errorContainer = Color(0xFF4B222C),
    onErrorContainer = Color(0xFFFFD9DE),
)

// Bundle the same Inter family as the reference instead of depending on a device's system font.
private val MeetSpaceFontFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    // The reference's 600–900 CSS face is the same semibold file.
    Font(R.font.inter_semibold, FontWeight.Bold),
)

private val MeetSpaceTypography = Typography(
    displayLarge = TextStyle(fontFamily = MeetSpaceFontFamily, fontSize = 57.sp, lineHeight = 64.sp, letterSpacing = (-0.25).sp),
    displayMedium = TextStyle(fontFamily = MeetSpaceFontFamily, fontSize = 45.sp, lineHeight = 52.sp),
    titleSmall = TextStyle(fontFamily = MeetSpaceFontFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = MeetSpaceFontFamily, fontSize = 12.sp, lineHeight = 16.sp),
    displaySmall = TextStyle(fontFamily = MeetSpaceFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 38.sp, lineHeight = 44.sp, letterSpacing = (-1).sp),
    headlineLarge = TextStyle(fontFamily = MeetSpaceFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 39.sp, letterSpacing = (-0.7).sp),
    headlineMedium = TextStyle(fontFamily = MeetSpaceFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 27.sp, lineHeight = 34.sp, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontFamily = MeetSpaceFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 23.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontFamily = MeetSpaceFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = MeetSpaceFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontFamily = MeetSpaceFontFamily, fontSize = 16.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontFamily = MeetSpaceFontFamily, fontSize = 14.sp, lineHeight = 22.sp),
    labelLarge = TextStyle(fontFamily = MeetSpaceFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = MeetSpaceFontFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 18.sp),
    labelSmall = TextStyle(fontFamily = MeetSpaceFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 1.sp),
)

@Composable
fun MeetSpaceTheme(themeMode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = MeetSpaceTypography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp),
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(20.dp),
            large = MeetSpaceTokens.CardShape,
            extraLarge = RoundedCornerShape(32.dp),
        ),
        content = content,
    )
}
