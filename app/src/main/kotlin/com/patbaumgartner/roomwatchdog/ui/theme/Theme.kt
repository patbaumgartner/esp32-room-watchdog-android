package com.patbaumgartner.roomwatchdog.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

/** Semantic colours that Material's scheme has no slot for. */
data class WatchdogAccents(
    val presence: Color,
    val warning: Color,
    val recording: Color,
    val textMuted: Color,
    val brand: Color,
)

val LocalWatchdogAccents = staticCompositionLocalOf {
    WatchdogAccents(Coral, Amber, RecordRed, InkMuted, BrandCyanDeep)
}

private val LightScheme = lightColorScheme(
    primary = BrandCyan,
    onPrimary = OnBrandCyan,
    background = Porcelain,
    onBackground = Ink,
    surface = Porcelain,
    onSurface = Ink,
    surfaceVariant = PorcelainRaised,
    onSurfaceVariant = InkMuted,
    error = Coral,
)

private val DarkScheme = darkColorScheme(
    primary = BrandCyan,
    onPrimary = OnBrandCyan,
    background = Graphite,
    onBackground = Bone,
    surface = Graphite,
    onSurface = Bone,
    surfaceVariant = GraphiteRaised,
    onSurfaceVariant = BoneMuted,
    error = CoralLight,
)

private val Trim = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

private fun typography(family: FontFamily) = Typography(
    displayLarge = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Light,
        fontSize = 44.sp,
        lineHeight = 50.sp,
        letterSpacing = (-0.8).sp,
        lineHeightStyle = Trim,
    ),
    headlineSmall = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        letterSpacing = 0.6.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = 0.4.sp,
    ),
)

@Composable
fun RoomWatchdogTheme(
    darkTheme: Boolean = androidx.compose.foundation.isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val accents = if (darkTheme) {
        WatchdogAccents(BrandCyanLight, AmberLight, RecordRedLight, BoneMuted, BrandCyanLight)
    } else {
        WatchdogAccents(BrandCyanDeep, Amber, RecordRed, InkMuted, BrandCyanDeep)
    }
    CompositionLocalProvider(LocalWatchdogAccents provides accents) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            typography = typography(WatchdogFontFamily),
            content = content,
        )
    }
}

object WatchdogTheme {
    val accents: WatchdogAccents
        @Composable @ReadOnlyComposable get() = LocalWatchdogAccents.current
}
