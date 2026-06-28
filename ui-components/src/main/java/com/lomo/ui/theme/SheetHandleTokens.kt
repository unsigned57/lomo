package com.lomo.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

internal object SheetHandleTokens {
    val VerticalPadding = 22.dp
    val Width = 32.dp
    val Height = 4.dp
    val Shape = AppShapes.ExtraSmall

    private const val HandleAlpha = 0.4f

    fun color(colorScheme: ColorScheme): Color =
        colorScheme.onSurfaceVariant.copy(alpha = HandleAlpha)
}
