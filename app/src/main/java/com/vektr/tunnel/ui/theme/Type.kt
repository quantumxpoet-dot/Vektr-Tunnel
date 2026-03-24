package com.vektr.tunnel.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Vektr Typography — Roboto Mono for all data readouts (bitrate, node IDs, progress).
 * Tight letter-spacing and uppercase labels reinforce the "instrument" aesthetic.
 */
val VektrTypography = Typography(
    // App title: VEKTR // TUNNEL
    headlineSmall = TextStyle(
        fontFamily   = FontFamily.Monospace,
        fontWeight   = FontWeight.Bold,
        fontSize     = 18.sp,
        lineHeight   = 24.sp,
        letterSpacing = 4.sp
    ),
    // Section labels: LOCAL NODE, TARGET NODE ID, STATUS
    labelSmall = TextStyle(
        fontFamily   = FontFamily.Monospace,
        fontWeight   = FontWeight.Normal,
        fontSize     = 10.sp,
        lineHeight   = 14.sp,
        letterSpacing = 2.sp
    ),
    // Data readouts: bitrate, integrity, progress value
    labelMedium = TextStyle(
        fontFamily   = FontFamily.Monospace,
        fontWeight   = FontWeight.Medium,
        fontSize     = 12.sp,
        lineHeight   = 16.sp,
        letterSpacing = 1.5.sp
    ),
    // Body text in the console panel
    bodySmall = TextStyle(
        fontFamily   = FontFamily.Monospace,
        fontWeight   = FontWeight.Normal,
        fontSize     = 12.sp,
        lineHeight   = 18.sp,
        letterSpacing = 0.5.sp
    ),
    // Node ID value display
    bodyMedium = TextStyle(
        fontFamily   = FontFamily.Monospace,
        fontWeight   = FontWeight.Medium,
        fontSize     = 14.sp,
        lineHeight   = 20.sp,
        letterSpacing = 1.sp
    ),
    // Button label: OPEN TUNNEL, CONNECT
    labelLarge = TextStyle(
        fontFamily   = FontFamily.Monospace,
        fontWeight   = FontWeight.Bold,
        fontSize     = 13.sp,
        lineHeight   = 18.sp,
        letterSpacing = 3.sp
    )
)
