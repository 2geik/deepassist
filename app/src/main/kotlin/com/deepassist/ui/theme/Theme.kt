package com.deepassist.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * "Sıcak" — cream paper, terracotta accent, serif for what the assistant says.
 * A warm dark variant follows the system setting (night radio-drama hours).
 */
object WarmPalette {
    val Cream = Color(0xFFFBF7F0)
    val Paper = Color(0xFFFFFDF9)
    val Sand = Color(0xFFF3EBDF)
    val Line = Color(0xFFEADFCD)
    val Terracotta = Color(0xFFC8643B)
    val TerracottaSoft = Color(0xFFF6DCCD)
    val Ink = Color(0xFF2B1D14)
    val InkSoft = Color(0xFF6E5A4C)
    val Moss = Color(0xFF3E7B4F)

    val NightBg = Color(0xFF1E1611)
    val NightSurface = Color(0xFF271D17)
    val NightRaised = Color(0xFF33271F)
    val NightLine = Color(0xFF3E3129)
    val Ember = Color(0xFFE8875C)
    val NightInk = Color(0xFFF3E8DC)
    val NightInkSoft = Color(0xFFCDBBAA)
}

private val LightColors = lightColorScheme(
    primary = WarmPalette.Terracotta,
    onPrimary = Color(0xFFFFF7EF),
    primaryContainer = WarmPalette.TerracottaSoft,
    onPrimaryContainer = Color(0xFF5A2410),
    secondary = Color(0xFF8A6A55),
    onSecondary = Color.White,
    secondaryContainer = WarmPalette.Sand,
    onSecondaryContainer = WarmPalette.Ink,
    background = WarmPalette.Cream,
    onBackground = WarmPalette.Ink,
    surface = WarmPalette.Cream,
    onSurface = WarmPalette.Ink,
    surfaceVariant = WarmPalette.Sand,
    onSurfaceVariant = WarmPalette.InkSoft,
    surfaceContainerLowest = WarmPalette.Paper,
    surfaceContainerLow = WarmPalette.Paper,
    surfaceContainer = Color(0xFFF7F0E6),
    surfaceContainerHigh = WarmPalette.Sand,
    surfaceContainerHighest = Color(0xFFEDE3D5),
    outline = Color(0xFFD9C9B5),
    outlineVariant = WarmPalette.Line,
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDA),
    onErrorContainer = Color(0xFF5C1410)
)

private val DarkColors = darkColorScheme(
    primary = WarmPalette.Ember,
    onPrimary = Color(0xFF3A1606),
    primaryContainer = Color(0xFF5A2E1A),
    onPrimaryContainer = Color(0xFFFCD9C6),
    secondary = Color(0xFFD9B8A0),
    onSecondary = Color(0xFF3A2414),
    secondaryContainer = WarmPalette.NightRaised,
    onSecondaryContainer = WarmPalette.NightInk,
    background = WarmPalette.NightBg,
    onBackground = WarmPalette.NightInk,
    surface = WarmPalette.NightBg,
    onSurface = WarmPalette.NightInk,
    surfaceVariant = WarmPalette.NightRaised,
    onSurfaceVariant = WarmPalette.NightInkSoft,
    surfaceContainerLowest = Color(0xFF18110D),
    surfaceContainerLow = WarmPalette.NightSurface,
    surfaceContainer = WarmPalette.NightSurface,
    surfaceContainerHigh = WarmPalette.NightRaised,
    surfaceContainerHighest = Color(0xFF3D3027),
    outline = Color(0xFF5C4A3E),
    outlineVariant = WarmPalette.NightLine,
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDA)
)

/** Colors Material doesn't name: the "all good" state and its tint. */
@Immutable
data class ExtraColors(val success: Color, val successContainer: Color)

val LocalExtraColors = staticCompositionLocalOf {
    ExtraColors(success = WarmPalette.Moss, successContainer = Color(0xFFE1EEDD))
}

/** Serif voice of the assistant: answers and large headings. */
val AssistantFont: FontFamily = FontFamily.Serif

private val AppTypography = Typography(
    displaySmall = TextStyle(fontFamily = AssistantFont, fontWeight = FontWeight.Medium, fontSize = 34.sp, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontFamily = AssistantFont, fontWeight = FontWeight.Medium, fontSize = 28.sp, lineHeight = 34.sp),
    headlineSmall = TextStyle(fontFamily = AssistantFont, fontWeight = FontWeight.Medium, fontSize = 23.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp)
)

/** What the assistant said, in the panel and in chat history. */
val AnswerStyle = TextStyle(fontFamily = AssistantFont, fontSize = 18.sp, lineHeight = 27.sp)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun DeepAssistTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors: ColorScheme = if (dark) DarkColors else LightColors
    val extra = if (dark) {
        ExtraColors(success = Color(0xFF9CD3A8), successContainer = Color(0xFF1F3A27))
    } else {
        ExtraColors(success = WarmPalette.Moss, successContainer = Color(0xFFE1EEDD))
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalExtraColors provides extra) {
        MaterialTheme(colorScheme = colors, typography = AppTypography, shapes = AppShapes, content = content)
    }
}
