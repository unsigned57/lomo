package com.lomo.ui.theme

import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

private object MemoTextInteractionAlpha {
    const val SELECTION_BACKGROUND = 0.24f
}

internal val memoTextSelectionBackgroundAlpha: Float
    get() = MemoTextInteractionAlpha.SELECTION_BACKGROUND

internal fun memoTextSelectionColors(colorScheme: ColorScheme): TextSelectionColors =
    TextSelectionColors(
        handleColor = memoPlatformTextHandleColor(colorScheme),
        backgroundColor = memoPlatformTextSelectionHighlightColor(colorScheme),
    )

internal fun memoPlatformTextHandleColor(colorScheme: ColorScheme): Color = colorScheme.primary

internal fun memoPlatformTextSelectionHighlightColor(colorScheme: ColorScheme): Color =
    colorScheme.primary.copy(alpha = memoTextSelectionBackgroundAlpha)
