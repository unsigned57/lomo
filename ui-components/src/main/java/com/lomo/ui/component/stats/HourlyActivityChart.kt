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
import kotlinx.collections.immutable.ImmutableMap

private const val HOURS_IN_DAY = 24
private const val HOUR_LABEL_INTERVAL = 6
private const val LEVEL_ONE_MAX_RATIO = 0.25f
private const val LEVEL_TWO_MAX_RATIO = 0.50f
private const val LEVEL_THREE_MAX_RATIO = 0.75f
private val BAR_CORNER_RADIUS = 2.dp
private val BAR_SPACING = 3.dp
private val LABEL_FONT_SIZE = 9.sp
private val CHART_AREA_HEIGHT = 100.dp
private const val EMPTY_ALPHA = 0.5f
private const val LEVEL_THREE_ALPHA = 0.7f

@Composable
fun HourlyActivityChart(
    hourlyDistribution: ImmutableMap<Int, Int>,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val spacingPx = with(density) { BAR_SPACING.toPx() }
    val cornerRadiusPx = with(density) { BAR_CORNER_RADIUS.toPx() }
    val chartHeightPx = with(density) { CHART_AREA_HEIGHT.toPx() }
    val labelFontSizePx = with(density) { LABEL_FONT_SIZE.toPx() }
    val labelAreaHeight = labelFontSizePx + spacingPx * 2

    val colors = rememberBarChartColors()
    val textPaint = rememberBarChartTextPaint(
        density = density,
        textColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb(),
    )

    val maxCount = remember(hourlyDistribution) {
        hourlyDistribution.values.maxOrNull() ?: 1
    }

    val totalHeight = chartHeightPx + labelAreaHeight

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(with(density) { totalHeight.toDp() }),
    ) {
        val barStep = size.width / HOURS_IN_DAY
        val barWidth = barStep - spacingPx

        for (hour in 0 until HOURS_IN_DAY) {
            val count = hourlyDistribution[hour] ?: 0
            val x = hour * barStep + spacingPx / 2

            // Empty bar background
            drawRoundRect(
                color = colors.empty,
                topLeft = Offset(x, 0f),
                size = Size(barWidth, chartHeightPx),
                cornerRadius = CornerRadius(cornerRadiusPx),
            )

            // Filled bar
            if (count > 0 && maxCount > 0) {
                val fillHeight = (count.toFloat() / maxCount) * chartHeightPx
                val fillColor = resolveBarColor(count, maxCount, colors)
                drawRoundRect(
                    color = fillColor,
                    topLeft = Offset(x, chartHeightPx - fillHeight),
                    size = Size(barWidth, fillHeight),
                    cornerRadius = CornerRadius(cornerRadiusPx),
                )
            }

            // Hour labels
            if (hour % HOUR_LABEL_INTERVAL == 0) {
                val label = "${hour}h"
                val textWidth = textPaint.measureText(label)
                drawContext.canvas.nativeCanvas.drawText(
                    label,
                    x + barWidth / 2 - textWidth / 2,
                    chartHeightPx + labelAreaHeight - spacingPx,
                    textPaint,
                )
            }
        }
    }
}

private data class BarChartColors(
    val empty: Color,
    val level1: Color,
    val level2: Color,
    val level3: Color,
    val level4: Color,
)

@Composable
private fun rememberBarChartColors(): BarChartColors =
    BarChartColors(
        empty = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = EMPTY_ALPHA),
        level1 = MaterialTheme.colorScheme.primaryContainer.copy(alpha = EMPTY_ALPHA),
        level2 = MaterialTheme.colorScheme.primaryContainer,
        level3 = MaterialTheme.colorScheme.primary.copy(alpha = LEVEL_THREE_ALPHA),
        level4 = MaterialTheme.colorScheme.primary,
    )

@Composable
private fun rememberBarChartTextPaint(
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

private fun resolveBarColor(
    count: Int,
    maxCount: Int,
    colors: BarChartColors,
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
