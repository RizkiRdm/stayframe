package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = MidnightInk,
    onPrimary = PureWhite,
    primaryContainer = PaperWhite,
    onPrimaryContainer = MidnightInk,
    secondary = Slate,
    onSecondary = PureWhite,
    secondaryContainer = PaperWhite,
    onSecondaryContainer = Graphite,
    tertiary = OceanDrift,
    onTertiary = PureWhite,
    background = PureWhite,
    onBackground = MidnightInk,
    surface = PureWhite,
    onSurface = MidnightInk,
    surfaceVariant = PaperWhite,
    onSurfaceVariant = Graphite,
    outline = SilverMist,
    outlineVariant = Ash
)

private val DarkColorScheme = darkColorScheme(
    primary = PureWhite,
    onPrimary = MidnightInk,
    primaryContainer = Color(0xFF1E2430),
    onPrimaryContainer = PureWhite,
    secondary = SilverMist,
    onSecondary = MidnightInk,
    secondaryContainer = Color(0xFF111827),
    onSecondaryContainer = PureWhite,
    tertiary = CyanSplash,
    onTertiary = MidnightInk,
    background = Color(0xFF0A0E17),
    onBackground = PureWhite,
    surface = Color(0xFF0F172A),
    onSurface = PureWhite,
    surfaceVariant = Color(0xFF1E293B),
    onSurfaceVariant = SilverMist,
    outline = Color(0xFF334155),
    outlineVariant = Color(0xFF475569)
)

@Composable
fun StayFrameTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
