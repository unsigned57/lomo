package com.lomo.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

/** Optional OLED surfaces are resolved after the palette and only for dark appearance. */
fun ColorScheme.withPureBlackSurfaces(isDark: Boolean, enabled: Boolean): ColorScheme = if (isDark && enabled) {
    copy(
        background = Color.Black,
        surface = Color.Black,
        surfaceDim = Color.Black,
        surfaceContainerLowest = Color.Black,
    )
} else {
    this
}
