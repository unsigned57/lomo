package com.lomo.ui.theme

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Width caps that keep content readable on large windows. Phones never reach them; on tablets,
 * foldables and desktop windows content centers in its column while the gutters stay part of the
 * scrollable surface.
 */
object AdaptiveContentWidths {
    /** Memo cards and prose: roughly 75 Latin or 40 CJK characters per line at body size. */
    val Reading: Dp = 720.dp

    /** Settings rows, forms and dashboards that read as one column of controls. */
    val Panel: Dp = 840.dp
}

/**
 * Centers this element's content at most [maxWidth] wide. The element itself still takes the full
 * row, so a scroll container's gestures and a list's placement stay window-wide.
 */
fun Modifier.adaptiveContentWidth(maxWidth: Dp): Modifier = fillMaxWidth()
    .wrapContentWidth(Alignment.CenterHorizontally)
    .widthIn(max = maxWidth)
    .fillMaxWidth()
