package com.lomo.ui.component.markdown

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.lomo.domain.model.markdown.MarkdownTableAlignment
import com.lomo.ui.theme.memoBodyTextStyle

private val MIN_COLUMN_WIDTH = 64.dp
private val MAX_COLUMN_WIDTH = 220.dp

@Composable
internal fun MarkdownTableBlock(
    alignments: List<MarkdownTableAlignment>,
    header: List<AnnotatedString>,
    rows: List<List<AnnotatedString>>,
    modifier: Modifier = Modifier,
) {
    val grid = remember(alignments, header, rows) { normalizeTableGrid(alignments, header, rows) }
    if (grid.columnCount == 0 || grid.allRows.isEmpty()) return

    val colorScheme = MaterialTheme.colorScheme
    val baseBodyStyle = MaterialTheme.typography.memoBodyTextStyle().copy(color = colorScheme.onSurface)
    val headerStyle = baseBodyStyle.copy(fontWeight = FontWeight.SemiBold)

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val availableWidthPx = if (constraints.hasBoundedWidth) constraints.maxWidth else 0
        Surface(
            shape = MaterialTheme.shapes.small,
            color = colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, colorScheme.outlineVariant),
        ) {
            Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                MarkdownTableGridLayout(
                    grid = grid,
                    availableWidthPx = availableWidthPx,
                    headerStyle = headerStyle,
                    cellStyle = baseBodyStyle,
                )
            }
        }
    }
}

@Composable
private fun MarkdownTableGridLayout(
    grid: NormalizedTableGrid,
    availableWidthPx: Int,
    headerStyle: TextStyle,
    cellStyle: TextStyle,
) {
    val colorScheme = MaterialTheme.colorScheme
    val rowCount = grid.allRows.size
    val columnCount = grid.columnCount
    val cellCount = rowCount * columnCount
    val hDividerCount = (rowCount - 1).coerceAtLeast(0)

    Layout(
        content = {
            for (rowIndex in 0 until rowCount) {
                val bgColor =
                    when {
                        rowIndex == 0 -> colorScheme.surfaceContainerHighest
                        rowIndex % 2 == 0 -> MarkdownRenderTokens.tableZebraRowColor(colorScheme)
                        else -> colorScheme.surfaceContainerLow
                    }
                Box(modifier = Modifier.background(bgColor))
            }
            for (rowIndex in 0 until rowCount) {
                val row = grid.allRows[rowIndex]
                val style = if (rowIndex == 0) headerStyle else cellStyle
                for (colIndex in 0 until columnCount) {
                    val alignment = grid.alignments[colIndex]
                    MarkdownTableCell(
                        text = row[colIndex],
                        alignment = alignment,
                        style = style,
                    )
                }
            }
            repeat(hDividerCount) {
                Box(modifier = Modifier.background(colorScheme.outlineVariant))
            }
            repeat((columnCount - 1).coerceAtLeast(0)) {
                Box(modifier = Modifier.background(colorScheme.outlineVariant))
            }
        },
    ) { measurables, constraints ->
        val metrics =
            TableGridMeasureMetrics(
                rowCount = rowCount,
                columnCount = columnCount,
                cellCount = cellCount,
                dividerPx = 1.dp.roundToPx().coerceAtLeast(1),
                minColPx = MIN_COLUMN_WIDTH.roundToPx(),
                maxColPx = MAX_COLUMN_WIDTH.roundToPx(),
                availableWidthPx = availableWidthPx,
            )
        measureAndPlaceTableGrid(
            measurables = measurables,
            constraints = constraints,
            metrics = metrics,
        )
    }
}

@Composable
private fun MarkdownTableCell(text: AnnotatedString, alignment: MarkdownTableAlignment, style: TextStyle) {
    val themedText = rememberThemedMarkdownText(text = text, styleLinks = true)
    Box(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = alignment.toBoxAlignment(),
    ) {
        Text(
            text = themedText,
            style = style,
            textAlign = alignment.toTextAlign(),
        )
    }
}

private data class TableGridMeasureMetrics(
    val rowCount: Int,
    val columnCount: Int,
    val cellCount: Int,
    val dividerPx: Int,
    val minColPx: Int,
    val maxColPx: Int,
    val availableWidthPx: Int,
)

private fun androidx.compose.ui.layout.MeasureScope.measureAndPlaceTableGrid(
    measurables: List<Measurable>,
    constraints: Constraints,
    metrics: TableGridMeasureMetrics,
): androidx.compose.ui.layout.MeasureResult {
    val rowCount = metrics.rowCount
    val columnCount = metrics.columnCount
    val cellCount = metrics.cellCount
    val dividerPx = metrics.dividerPx
    val cellStartIndex = rowCount
    val hDividerStartIndex = cellStartIndex + cellCount
    val vDividerStartIndex = hDividerStartIndex + (rowCount - 1).coerceAtLeast(0)

    val colWidths = computeColumnWidths(measurables, cellStartIndex, metrics)

    val cellPlaceables = ArrayList<Placeable>(cellCount)
    val rowHeights = IntArray(rowCount)
    for (rowIndex in 0 until rowCount) {
        var rowMaxHeight = 0
        for (colIndex in 0 until columnCount) {
            val idx = cellStartIndex + rowIndex * columnCount + colIndex
            val placeable = measurables[idx].measure(Constraints.fixedWidth(colWidths[colIndex]))
            cellPlaceables += placeable
            if (placeable.height > rowMaxHeight) {
                rowMaxHeight = placeable.height
            }
        }
        rowHeights[rowIndex] = rowMaxHeight
    }

    val tableWidth =
        (colWidths.sum() + (columnCount - 1).coerceAtLeast(0) * dividerPx)
            .coerceIn(constraints.minWidth, constraints.maxWidth)
    val tableHeight =
        (rowHeights.sum() + (rowCount - 1).coerceAtLeast(0) * dividerPx)
            .coerceIn(constraints.minHeight, constraints.maxHeight)

    val rowBgPlaceables =
        Array(rowCount) { rowIndex ->
            measurables[rowIndex].measure(Constraints.fixed(tableWidth, rowHeights[rowIndex]))
        }
    val hDividerPlaceables =
        Array((rowCount - 1).coerceAtLeast(0)) { idx ->
            measurables[hDividerStartIndex + idx].measure(Constraints.fixed(tableWidth, dividerPx))
        }
    val vDividerPlaceables =
        Array((columnCount - 1).coerceAtLeast(0)) { idx ->
            measurables[vDividerStartIndex + idx].measure(Constraints.fixed(dividerPx, tableHeight))
        }

    val gridPlaceables =
        TableGridPlaceables(
            rowBgs = rowBgPlaceables,
            cells = cellPlaceables,
            hDividers = hDividerPlaceables,
            vDividers = vDividerPlaceables,
            colWidths = colWidths,
            rowHeights = rowHeights,
            metrics = metrics,
        )
    return layout(tableWidth, tableHeight) {
        placeTableGrid(gridPlaceables)
    }
}

private class TableGridPlaceables(
    val rowBgs: Array<Placeable>,
    val cells: List<Placeable>,
    val hDividers: Array<Placeable>,
    val vDividers: Array<Placeable>,
    val colWidths: IntArray,
    val rowHeights: IntArray,
    val metrics: TableGridMeasureMetrics,
)

private fun Placeable.PlacementScope.placeTableGrid(grid: TableGridPlaceables) {
    val rowCount = grid.metrics.rowCount
    val columnCount = grid.metrics.columnCount
    val dividerPx = grid.metrics.dividerPx

    var bgY = 0
    for (rowIndex in 0 until rowCount) {
        grid.rowBgs[rowIndex].placeRelative(0, bgY)
        bgY += grid.rowHeights[rowIndex] + dividerPx
    }

    var rowY = 0
    for (rowIndex in 0 until rowCount) {
        var colX = 0
        for (colIndex in 0 until columnCount) {
            grid.cells[rowIndex * columnCount + colIndex].placeRelative(colX, rowY)
            colX += grid.colWidths[colIndex] + dividerPx
        }
        rowY += grid.rowHeights[rowIndex] + dividerPx
    }

    var hDividerY = 0
    for (rowIndex in 0 until rowCount - 1) {
        hDividerY += grid.rowHeights[rowIndex]
        grid.hDividers[rowIndex].placeRelative(0, hDividerY)
        hDividerY += dividerPx
    }

    var vDividerX = 0
    for (colIndex in 0 until columnCount - 1) {
        vDividerX += grid.colWidths[colIndex]
        grid.vDividers[colIndex].placeRelative(vDividerX, 0)
        vDividerX += dividerPx
    }
}

private fun computeColumnWidths(
    measurables: List<Measurable>,
    cellStartIndex: Int,
    metrics: TableGridMeasureMetrics,
): IntArray {
    val colWidths = IntArray(metrics.columnCount)
    for (colIndex in 0 until metrics.columnCount) {
        var widest = metrics.minColPx
        for (rowIndex in 0 until metrics.rowCount) {
            val idx = cellStartIndex + rowIndex * metrics.columnCount + colIndex
            val natural = measurables[idx].maxIntrinsicWidth(Constraints.Infinity)
            if (natural > widest) {
                widest = natural
            }
        }
        colWidths[colIndex] = widest.coerceIn(metrics.minColPx, metrics.maxColPx)
    }
    val totalDividerWidth = (metrics.columnCount - 1).coerceAtLeast(0) * metrics.dividerPx
    val totalNaturalWidth = colWidths.sum() + totalDividerWidth
    if (metrics.availableWidthPx > totalNaturalWidth && metrics.columnCount > 0) {
        val extra = metrics.availableWidthPx - totalNaturalWidth
        val perColumn = extra / metrics.columnCount
        val remainder = extra % metrics.columnCount
        for (colIndex in 0 until metrics.columnCount) {
            colWidths[colIndex] += perColumn + if (colIndex < remainder) 1 else 0
        }
    }
    return colWidths
}

private data class NormalizedTableGrid(
    val columnCount: Int,
    val alignments: List<MarkdownTableAlignment>,
    val allRows: List<List<AnnotatedString>>,
)

private fun normalizeTableGrid(
    alignments: List<MarkdownTableAlignment>,
    header: List<AnnotatedString>,
    rows: List<List<AnnotatedString>>,
): NormalizedTableGrid {
    var columnCount = header.size
    for (row in rows) {
        if (row.size > columnCount) {
            columnCount = row.size
        }
    }
    if (columnCount == 0) {
        return NormalizedTableGrid(0, emptyList(), emptyList())
    }
    val normalizedAlignments =
        List(columnCount) { idx -> alignments.getOrNull(idx) ?: MarkdownTableAlignment.NONE }
    val normalizedRows = ArrayList<List<AnnotatedString>>(1 + rows.size)
    normalizedRows += padRowCells(header, columnCount)
    for (row in rows) {
        normalizedRows += padRowCells(row, columnCount)
    }
    return NormalizedTableGrid(columnCount, normalizedAlignments, normalizedRows)
}

private fun padRowCells(row: List<AnnotatedString>, columnCount: Int): List<AnnotatedString> =
    if (row.size == columnCount) row else List(columnCount) { idx -> row.getOrElse(idx) { AnnotatedString("") } }

private fun MarkdownTableAlignment.toTextAlign(): TextAlign = when (this) {
    MarkdownTableAlignment.NONE,
    MarkdownTableAlignment.LEFT,
    -> TextAlign.Start

    MarkdownTableAlignment.CENTER -> TextAlign.Center

    MarkdownTableAlignment.RIGHT -> TextAlign.End
}

private fun MarkdownTableAlignment.toBoxAlignment(): Alignment = when (this) {
    MarkdownTableAlignment.NONE,
    MarkdownTableAlignment.LEFT,
    -> Alignment.TopStart

    MarkdownTableAlignment.CENTER -> Alignment.TopCenter

    MarkdownTableAlignment.RIGHT -> Alignment.TopEnd
}
