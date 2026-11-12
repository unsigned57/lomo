package com.lomo.ui.component.input

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints

/** Top padding above the header row; it grows into the long form's top padding. */
@Composable
internal fun InputSheetHeaderTopInset(expansion: () -> Float) {
    Box(
        modifier =
        Modifier
            .fillMaxWidth()
            .layout { measurable, constraints ->
                val height =
                    resolveInputSheetHeaderTopInsetPx(
                        fraction = expansion(),
                        compactPx = InputSheetTokens.HeaderTopInsetCompact.toPx(),
                        expandedPx = InputSheetTokens.HeaderTopInsetExpanded.toPx(),
                    )
                val placeable = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            },
    )
}

/**
 * The drag affordance between the header's start and end slots. The compact card shows it; the long
 * form fades it and gives its width back to the mode switch.
 */
@Composable
internal fun InputSheetHandleSlot(expansion: () -> Float) {
    val color = MaterialTheme.colorScheme.outlineVariant
    Box(
        modifier =
        Modifier
            .layout { measurable, constraints ->
                val width =
                    resolveInputSheetHandleSlotWidthPx(
                        fraction = expansion(),
                        slotPx = (InputSheetTokens.DragHandleWidth + InputSheetTokens.DragHandleSideGap * 2).toPx(),
                    )
                val height = InputSheetTokens.HeaderActionClusterHeight.roundToPx()
                val placeable = measurable.measure(Constraints.fixed(width, height))
                layout(width, height) { placeable.place(0, 0) }
            }.drawBehind {
                val handleAlpha = 1f - expansion().coerceIn(0f, 1f)
                if (handleAlpha <= 0f) return@drawBehind
                val handleSize =
                    Size(InputSheetTokens.DragHandleWidth.toPx(), InputSheetTokens.DragHandleHeight.toPx())
                drawRoundRect(
                    color = color,
                    topLeft = Offset((size.width - handleSize.width) / 2f, (size.height - handleSize.height) / 2f),
                    size = handleSize,
                    cornerRadius = CornerRadius(handleSize.height / 2f),
                    alpha = handleAlpha,
                )
            },
    )
}
