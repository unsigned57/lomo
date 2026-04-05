package com.lomo.app.feature.main

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import kotlin.math.roundToInt

internal fun Modifier.memoInsertSpaceModifier(
    spaceFraction: Float,
    bottomSpacing: Dp,
): Modifier =
    clipToBounds().layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val bottomSpacingPx = bottomSpacing.roundToPx()
        val animatedHeight = (placeable.height * spaceFraction).roundToInt()
        val animatedBottomSpacing = (bottomSpacingPx * spaceFraction).roundToInt()

        layout(placeable.width, animatedHeight + animatedBottomSpacing) {
            placeable.placeRelative(0, 0)
        }
    }
