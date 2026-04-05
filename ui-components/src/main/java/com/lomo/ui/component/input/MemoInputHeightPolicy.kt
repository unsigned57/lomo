package com.lomo.ui.component.input

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit

internal fun resolveMemoInputMinimumContentHeightPx(
    style: TextStyle,
    density: Density,
    minLines: Int = INPUT_EDITOR_MIN_LINES,
): Int {
    val minimumLineHeightPx =
        with(density) {
            when {
                style.lineHeight != TextUnit.Unspecified -> style.lineHeight.roundToPx()
                style.fontSize != TextUnit.Unspecified -> style.fontSize.roundToPx()
                else -> 0
            }
        }
    return (minimumLineHeightPx * minLines.coerceAtLeast(1)).coerceAtLeast(0)
}
