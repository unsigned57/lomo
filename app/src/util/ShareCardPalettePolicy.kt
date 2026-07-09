package com.lomo.app.util

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.toArgb

internal fun shareCardPaletteFromColorScheme(colorScheme: ColorScheme): ShareCardPalette =
    ShareCardPalette(
        bgStart = colorScheme.surfaceContainerLowest.toArgb(),
        bgEnd = colorScheme.surface.toArgb(),
        card = colorScheme.surfaceContainerLow.toArgb(),
        cardBorder = colorScheme.outlineVariant.toArgb(),
        bodyText = colorScheme.onSurface.toArgb(),
        secondaryText = colorScheme.onSurfaceVariant.toArgb(),
        tagBg = colorScheme.secondaryContainer.toArgb(),
        tagText = colorScheme.onSecondaryContainer.toArgb(),
        divider = colorScheme.outlineVariant.toArgb(),
        quoteIndicator = colorScheme.primary.toArgb(),
        linkText = colorScheme.primary.toArgb(),
    )
