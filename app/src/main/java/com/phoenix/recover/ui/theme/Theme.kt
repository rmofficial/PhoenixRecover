package com.phoenix.recover.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val BgDeep = Color(0xFF04060B)
val BgPanel = Color(0xFF0A0E18)
val CardBg = Color(0xFF0D1220)
val TextPrimary = Color(0xFFF2F6FF)
val TextSecondary = Color(0xFF8E9BB5)
val Danger = Color(0xFFFF5C7A)
val Success = Color(0xFF34D399)
val Warn = Color(0xFFFBBF24)

data class AccentPalette(
    val key: String,
    val label: String,
    val a: Color,
    val b: Color,
    val c: Color,
)

val Accents = listOf(
    AccentPalette("aurora", "Aurora", Color(0xFF22D3EE), Color(0xFF8B5CF6), Color(0xFFF472B6)),
    AccentPalette("ember", "Ember", Color(0xFFFFB020), Color(0xFFFF5C7A), Color(0xFFF472B6)),
    AccentPalette("ocean", "Ocean", Color(0xFF38BDF8), Color(0xFF2DD4BF), Color(0xFF818CF8)),
    AccentPalette("lime", "Neon", Color(0xFFA3E635), Color(0xFF22D3EE), Color(0xFF34D399)),
    AccentPalette("royal", "Royal", Color(0xFF818CF8), Color(0xFFC084FC), Color(0xFF38BDF8)),
)

val LocalAccent = staticCompositionLocalOf { Accents[0] }

private val PhoenixTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 44.sp,
        lineHeight = 48.sp,
        letterSpacing = (-1.2).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.8).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.4).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp,
        lineHeight = 27.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.15.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 13.sp,
        letterSpacing = 0.9.sp,
    ),
)

@Composable
fun PhoenixTheme(accent: AccentPalette = Accents[0], content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = accent.a,
        onPrimary = Color(0xFF04121A),
        secondary = accent.b,
        tertiary = accent.c,
        background = BgDeep,
        onBackground = TextPrimary,
        surface = BgPanel,
        onSurface = TextPrimary,
        surfaceVariant = CardBg,
        onSurfaceVariant = TextSecondary,
        error = Danger,
    )
    CompositionLocalProvider(LocalAccent provides accent) {
        MaterialTheme(colorScheme = scheme, typography = PhoenixTypography, content = content)
    }
}
