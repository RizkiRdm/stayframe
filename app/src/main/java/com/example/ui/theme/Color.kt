package com.example.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// Sleeve Design System Colors
val MidnightInk = Color(0xFF000000)
val Graphite = Color(0xFF333333)
val Slate = Color(0xFF374151)
val SilverMist = Color(0xFFE5E7EB)
val Ash = Color(0xFFCCCCCC)
val Smoke = Color(0xFF999999)
val PureWhite = Color(0xFFFFFFFF)
val PaperWhite = Color(0xFFF9FAFB)

// Chromatic Accents
val OceanDrift = Color(0xFF0E95EE)
val CyanSplash = Color(0xFF27C9F5)

// Status semantic tints
val StatusGreen = Color(0xFF10B981)
val StatusGreenSoft = Color(0xFFECFDF5)
val StatusAmber = Color(0xFFF59E0B)
val StatusAmberSoft = Color(0xFFFFFBEB)
val StatusRed = Color(0xFFEF4444)
val StatusRedSoft = Color(0xFFFEF2F2)
val StatusBlueSoft = Color(0xFFEFF6FF)

val OceanDriftBrush = Brush.linearGradient(
    colors = listOf(OceanDrift, CyanSplash)
)
