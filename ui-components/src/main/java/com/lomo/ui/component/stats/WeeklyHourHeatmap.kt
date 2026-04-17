package com.lomo.ui.component.stats

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.collections.immutable.ImmutableMap

private val CELL_SIZE = 10.dp
private val CELL_SPACING = 3.dp
private val CELL_CORNER_RADIUS = 2.dp
private val LABEL_FONT_SIZE = 9.sp
private const val HOURS_IN_DAY = 24
private const val DAYS_IN_WEEK = 7
private const val HOUR_LABEL_INTERVAL = 3
private const val LEVEL_ONE_MAX_RATIO = 0.25f
private const val LEVEL_TWO_MAX_RATIO = 0.50f
private const val LEVEL_THREE_MAX_RATIO = 0.75f
private const val EMPTY_ALPHA = 0.5f
private const val LEVEL_THREE_ALPHA = 0.7f

@Composable
fun WeeklyHourHeatmap(
    weeklyHourDistribution: ImmutableMap<DayOfWeek, ImmutableMap<Int, Int>>,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val cellSizePx = with(density) { CELL_SIZE.toPx() }
    val spacingPx = with(density) { CELL_SPACING.toPx() }
    val cornerRadiusPx = with(density) { CELL_CORNER_RADIUS.toPx() }
    val cellStep = cellSizePx + spacingPx

    val colors = rememberWeeklyHeatmapColors()
    val textPaint = rememberWeeklyHeatmapTextPaint(
        density = density,
        textColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb(),
    )

    val dayOrder = remember {
        listOf(
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY,
        )
    }
    val dayLabels = remember {
        dayOrder.map { it.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
    }

    val maxCount = remember(weeklyHourDistribution) {
        weeklyHourDistribution.values.flatMap { it.values }.maxOrNull() ?: 1
    }

    val labelWidthPx = remember(textPaint, dayLabels) {
        dayLabels.maxOf { textPaint.measureText(it) }
    }
    val leftMarginPx = labelWidthPx + spacingPx * 2

    val hourLabelHeightPx = with(density) { LABEL_FONT_SIZE.toPx() } + spacingPx
    val totalHeight = hourLabelHeightPx + DAYS_IN_WEEK * cellStep

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(with(density) { totalHeight.toDp() }),
    ) {
        // Hour labels along top
        for (hour in 0 until HOURS_IN_DAY) {
            if (hour % HOUR_LABEL_INTERVAL == 0) {
                val label = "$hour"
                val textWidth = textPaint.measureText(label)
                drawContext.canvas.nativeCanvas.drawText(
                    label,
                    leftMarginPx + hour * cellStep + cellSizePx / 2 - textWidth / 2,
                    hourLabelHeightPx - spacingPx,
                    textPaint,
                )
            }
        }

        // Day labels and cells
        for ((dayIndex, day) in dayOrder.withIndex()) {
            val rowY = hourLabelHeightPx + dayIndex * cellStep
            val textHeight = textPaint.descent() - textPaint.ascent()
            drawContext.canvas.nativeCanvas.drawText(
                dayLabels[dayIndex],
                0f,
                rowY + cellSizePx / 2 + textHeight / 2 - textPaint.descent(),
                textPaint,
            )

            val hourMap = weeklyHourDistribution[day] ?: emptyMap()
            for (hour in 0 until HOURS_IN_DAY) {
                val count = hourMap[hour] ?: 0
                val x = leftMarginPx + hour * cellStep
                drawRoundRect(
                    color = resolveWeeklyHeatmapColor(count, maxCount, colors),
                    topLeft = Offset(x, rowY),
                    size = Size(cellSizePx, cellSizePx),
                    cornerRadius = CornerRadius(cornerRadiusPx),
                )
            }
        }
    }
}

private data class WeeklyHeatmapColors(
    val empty: Color,
    val level1: Color,
    val level2: Color,
    val level3: Color,
    val level4: Color,
)

@Composable
private fun rememberWeeklyHeatmapColors(): WeeklyHeatmapColors =
    WeeklyHeatmapColors(
        empty = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = EMPTY_ALPHA),
        level1 = MaterialTheme.colorScheme.primaryContainer.copy(alpha = EMPTY_ALPHA),
        level2 = MaterialTheme.colorScheme.primaryContainer,
        level3 = MaterialTheme.colorScheme.primary.copy(alpha = LEVEL_THREE_ALPHA),
        level4 = MaterialTheme.colorScheme.primary,
    )

@Composable
private fun rememberWeeklyHeatmapTextPaint(
    density: androidx.compose.ui.unit.Density,
    textColor: Int,
): Paint {
    val densityScale = density.density
    val fontScale = density.fontScale
    return remember(textColor, densityScale, fontScale) {
        Paint().apply {
            color = textColor
            textSize = with(density) { LABEL_FONT_SIZE.toPx() }
            isAntiAlias = true
            textAlign = Paint.Align.LEFT
        }
    }
}

private fun resolveWeeklyHeatmapColor(
    count: Int,
    maxCount: Int,
    colors: WeeklyHeatmapColors,
): Color {
    if (maxCount == 0 || count == 0) return colors.empty
    val ratio = count.toFloat() / maxCount
    return when {
        ratio <= LEVEL_ONE_MAX_RATIO -> colors.level1
        ratio <= LEVEL_TWO_MAX_RATIO -> colors.level2
        ratio <= LEVEL_THREE_MAX_RATIO -> colors.level3
        else -> colors.level4
    }
}
