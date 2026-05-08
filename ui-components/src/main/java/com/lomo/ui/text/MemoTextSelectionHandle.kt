package com.lomo.ui.text

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

internal val MemoTextSelectionHandleTouchSize = 25.dp

internal fun resolveMemoTextSelectionHandleTopLeft(
    anchorPosition: Offset,
    handleSizePx: Float,
): IntOffset =
    IntOffset(
        x = (anchorPosition.x - handleSizePx / 2f).roundToInt(),
        y = anchorPosition.y.roundToInt(),
    )
