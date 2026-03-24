package com.vektr.tunnel.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * Vektr Theme — Premium adaptive Light / Dark.
 * Sharp-edged shapes reinforce the "instrument panel" aesthetic.
 * High-contrast, data-first palette with zero decorative noise.
 */

private val DarkColors = darkColorScheme(
    primary          = VektrCyan,
    onPrimary        = VektrBlack,
    primaryContainer = VektrCyanDim,
    secondary        = VektrGray,
    onSecondary      = VektrWhite,
    background       = VektrBlack,
    onBackground     = VektrWhite,
    surface          = VektrSurface,
    onSurface        = VektrWhite,
    surfaceVariant   = VektrSurfaceHigh,
    onSurfaceVariant = VektrGray,
    outline          = VektrBorder,
    error            = VektrRed,
    onError          = VektrWhite
)

private val LightColors = lightColorScheme(
    primary          = VektrNavy,
    onPrimary        = VektrWhiteBase,
    primaryContainer = VektrNavyDark,
    secondary        = VektrInkDim,
    onSecondary      = VektrWhiteBase,
    background       = VektrWhiteBase,
    onBackground     = VektrInk,
    surface          = VektrLightSurface,
    onSurface        = VektrInk,
    surfaceVariant   = VektrWhiteBase,
    onSurfaceVariant = VektrInkDim,
    outline          = VektrLightBorder,
    error            = VektrRedLight,
    onError          = VektrWhiteBase
)

/** Sharp-cornered shapes — "instrument panel" feel, no pill buttons. */
private val VektrShapes = Shapes(
    extraSmall = RoundedCornerShape(0.dp),
    small      = RoundedCornerShape(2.dp),
    medium     = RoundedCornerShape(2.dp),
    large      = RoundedCornerShape(2.dp),
    extraLarge = RoundedCornerShape(4.dp)
)

@Composable
fun VektrTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography  = VektrTypography,
        shapes      = VektrShapes,
        content     = content
    )
}
