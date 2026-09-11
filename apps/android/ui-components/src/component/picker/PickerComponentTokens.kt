package com.lomo.ui.component.picker

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.lomo.ui.theme.AppShapes

internal object PickerComponentTokens {
    val WheelViewportShape = AppShapes.Large
    val WheelSelectionShape = AppShapes.Medium

    private const val WheelSelectionAlpha = 0.35f

    fun wheelSelectionColor(colorScheme: ColorScheme): Color =
        colorScheme.primaryContainer.copy(alpha = WheelSelectionAlpha)
}
