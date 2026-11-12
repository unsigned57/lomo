package com.lomo.ui.component.common

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp

/**
 * LomoTouchTarget rule: visual size and interactive size are separate concerns.
 *
 * Interactive elements keep their own (typically `minimumInteractiveComponentSize`-inflated)
 * bounds so the >=48dp touch target stays centered, but report [visualHeight] to the parent so
 * compact rows keep their intended visual rhythm.
 *
 * Use only where the surrounding layout cannot be disturbed by the taller element overflowing
 * its reported bounds — e.g. a single-row header whose neighbours are smaller than the inflated
 * element. Elements that can wrap inside packed flow layouts must report their full interactive
 * height instead, so wrapped lines never overlap.
 */
fun Modifier.lomoCompactInteractiveHeight(visualHeight: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val reportedHeight = minOf(placeable.height, visualHeight.roundToPx())
    layout(placeable.width, reportedHeight) {
        placeable.place(0, (reportedHeight - placeable.height) / 2)
    }
}
