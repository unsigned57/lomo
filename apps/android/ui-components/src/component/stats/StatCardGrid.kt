package com.lomo.ui.component.stats

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import com.lomo.ui.theme.AppSpacing

/**
 * Lays statistic tiles out in equal-width columns and gives up columns, never content: the grid
 * uses the most columns (up to three) at which every tile is at least as wide as its unbroken
 * content, so a value is not cut short on a narrow window or at a large font scale.
 */
@Composable
fun StatCardGrid(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier, measurePolicy = StatCardGridMeasurePolicy)
}

private const val MAX_COLUMNS = 3

private val StatCardGridMeasurePolicy = MeasurePolicy { measurables, constraints ->
    val spacing = AppSpacing.Small.roundToPx()
    val widestTile = measurables.maxOfOrNull { it.maxIntrinsicWidth(Constraints.Infinity) } ?: 0
    val columns =
        (minOf(MAX_COLUMNS, measurables.size) downTo 2).firstOrNull { candidate ->
            (constraints.maxWidth - spacing * (candidate - 1)) / candidate >= widestTile
        } ?: 1
    val columnWidths =
        if (constraints.hasBoundedWidth) {
            // Leading columns take the remainder pixels so the row ends flush with its container.
            val tilesWidth = (constraints.maxWidth - spacing * (columns - 1)).coerceAtLeast(0)
            List(columns) { column -> tilesWidth / columns + if (column < tilesWidth % columns) 1 else 0 }
        } else {
            List(columns) { widestTile }
        }
    val columnOffsets = columnWidths.runningFold(0) { offset, width -> offset + width + spacing }
    val rows =
        measurables.chunked(columns).map { row ->
            row.mapIndexed { column, tile -> tile.measure(Constraints.fixedWidth(columnWidths[column])) }
        }
    val rowHeights = rows.map { row -> row.maxOf { it.height } }
    val contentWidth = columnWidths.sum() + spacing * (columns - 1)
    val contentHeight = rowHeights.sum() + spacing * (rows.size - 1).coerceAtLeast(0)

    layout(constraints.constrainWidth(contentWidth), constraints.constrainHeight(contentHeight)) {
        var y = 0
        rows.forEachIndexed { rowIndex, row ->
            row.forEachIndexed { column, tile -> tile.placeRelative(x = columnOffsets[column], y = y) }
            y += rowHeights[rowIndex] + spacing
        }
    }
}
