package com.lomo.ui.component.stats

import android.graphics.Paint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocale
import com.lomo.ui.generated.resources.Res
import com.lomo.ui.generated.resources.calendar_heatmap_memo_count
import com.lomo.ui.theme.LocalLomoMotion
import kotlinx.collections.immutable.ImmutableMap
import org.jetbrains.compose.resources.pluralStringResource

private const val LEVEL_ONE_MAX_RATIO = 0.25f
private const val LEVEL_TWO_MAX_RATIO = 0.50f
private const val LEVEL_THREE_MAX_RATIO = 0.75f

private data class HourlyBarHit(val hour: Int, val count: Int)

@Composable
fun HourlyActivityChart(hourlyDistribution: ImmutableMap<Int, Int>, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val spacingPx = with(density) { StatsChartTokens.HourlyBarSpacing.toPx() }
    val cornerRadiusPx = with(density) { StatsChartTokens.CellCornerRadius.toPx() }
    val chartHeightPx = with(density) { StatsChartTokens.HourlyChartAreaHeight.toPx() }
    val labelFontSizePx = with(density) { StatsChartTokens.LabelFontSize.toPx() }
    val labelAreaHeight = labelFontSizePx + spacingPx * 2

    val colors = rememberBarChartColors()
    val textPaint = rememberBarChartTextPaint(
        density = density,
        textColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb(),
    )
    // Same day-name gutter as the weekly heatmap, so the 24 hour columns of both charts line up.
    val gutterPx = rememberHourAxisGutterPx(textPaint = textPaint, spacingPx = spacingPx)

    val maxCount = remember(hourlyDistribution) {
        (hourlyDistribution.values.maxOrNull() ?: 1).coerceAtLeast(1)
    }

    val totalHeight = chartHeightPx + labelAreaHeight
    val motionScheme = LocalLomoMotion.current.component
    val entrance = remember { Animatable(0f) }
    LaunchedEffect(hourlyDistribution) {
        entrance.snapTo(0f)
        entrance.animateTo(
            targetValue = 1f,
            animationSpec = motionScheme.defaultSpatialSpec(),
        )
    }

    var selectedHit by remember { mutableStateOf<HourlyBarHit?>(null) }
    var popupOffset by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        HourlyActivityCanvas(
            hourlyDistribution = hourlyDistribution,
            maxCount = maxCount,
            gutterPx = gutterPx,
            spacingPx = spacingPx,
            cornerRadiusPx = cornerRadiusPx,
            chartHeightPx = chartHeightPx,
            labelAreaHeight = labelAreaHeight,
            totalHeight = totalHeight,
            entranceProgress = entrance.value,
            colors = colors,
            textPaint = textPaint,
            selectedHit = selectedHit,
            density = density,
            onSelect = { hit, offset ->
                selectedHit = hit
                popupOffset = offset
            },
        )

        HourlyBarSelectionPopup(
            selectedHit = selectedHit,
            popupOffset = popupOffset,
            density = density,
            onDismiss = { selectedHit = null },
        )
    }
}

@Composable
private fun HourlyActivityCanvas(
    hourlyDistribution: ImmutableMap<Int, Int>,
    maxCount: Int,
    gutterPx: Float,
    spacingPx: Float,
    cornerRadiusPx: Float,
    chartHeightPx: Float,
    labelAreaHeight: Float,
    totalHeight: Float,
    entranceProgress: Float,
    colors: BarChartColors,
    textPaint: Paint,
    selectedHit: HourlyBarHit?,
    density: androidx.compose.ui.unit.Density,
    onSelect: (HourlyBarHit?, Offset) -> Unit,
) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(with(density) { totalHeight.toDp() })
            .pointerInput(hourlyDistribution, gutterPx) {
                detectTapGestures(
                    onTap = { offset ->
                        val barStep = HourAxis.stepPx(widthPx = size.width.toFloat(), gutterPx = gutterPx)
                        val hour = ((offset.x - gutterPx) / barStep).toInt()
                        val inBounds =
                            offset.x >= gutterPx &&
                                hour in 0 until HourAxis.HoursInDay &&
                                offset.y <= chartHeightPx
                        if (inBounds) {
                            val count = hourlyDistribution[hour] ?: 0
                            if (selectedHit?.hour == hour) {
                                onSelect(null, Offset.Zero)
                            } else {
                                val barWidth = (barStep - spacingPx).coerceAtLeast(1f)
                                onSelect(
                                    HourlyBarHit(hour = hour, count = count),
                                    Offset(gutterPx + hour * barStep + barWidth / 2, 0f),
                                )
                            }
                        } else {
                            onSelect(null, Offset.Zero)
                        }
                    },
                )
            },
    ) {
        val barStep = HourAxis.stepPx(widthPx = size.width, gutterPx = gutterPx)
        val barWidth = (barStep - spacingPx).coerceAtLeast(1f)
        drawHourlyYAxisLabels(maxCount = maxCount, chartHeightPx = chartHeightPx, textPaint = textPaint)

        for (hour in 0 until HourAxis.HoursInDay) {
            val count = hourlyDistribution[hour] ?: 0
            val x = gutterPx + hour * barStep

            drawRoundRect(
                color = colors.empty,
                topLeft = Offset(x, 0f),
                size = Size(barWidth, chartHeightPx),
                cornerRadius = CornerRadius(cornerRadiusPx),
            )

            if (count > 0 && maxCount > 0) {
                val fillHeight = (count.toFloat() / maxCount) * chartHeightPx * entranceProgress
                val fillColor = resolveBarColor(count, maxCount, colors)
                drawRoundRect(
                    color = fillColor,
                    topLeft = Offset(x, chartHeightPx - fillHeight),
                    size = Size(barWidth, fillHeight),
                    cornerRadius = CornerRadius(cornerRadiusPx),
                )
            }

            if (selectedHit?.hour == hour) {
                drawRoundRect(
                    color = colors.level4,
                    topLeft = Offset(x, 0f),
                    size = Size(barWidth, chartHeightPx),
                    cornerRadius = CornerRadius(cornerRadiusPx),
                    style = Stroke(width = StatsChartTokens.SelectionStrokeWidth.toPx()),
                )
            }

            if (hour % HourAxis.LabelInterval == 0) {
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

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHourlyYAxisLabels(
    maxCount: Int,
    chartHeightPx: Float,
    textPaint: Paint,
) {
    val textHeight = textPaint.descent() - textPaint.ascent()
    drawContext.canvas.nativeCanvas.drawText(
        maxCount.toString(),
        0f,
        textHeight - textPaint.descent(),
        textPaint,
    )
    if (maxCount >= 2) {
        drawContext.canvas.nativeCanvas.drawText(
            (maxCount / 2).toString(),
            0f,
            chartHeightPx / 2f + textHeight / 2f - textPaint.descent(),
            textPaint,
        )
    }
    drawContext.canvas.nativeCanvas.drawText(
        "0",
        0f,
        chartHeightPx - textPaint.descent(),
        textPaint,
    )
}

@Composable
private fun HourlyBarSelectionPopup(
    selectedHit: HourlyBarHit?,
    popupOffset: Offset,
    density: androidx.compose.ui.unit.Density,
    onDismiss: () -> Unit,
) {
    val motionScheme = LocalLomoMotion.current.component
    var activeData by remember { mutableStateOf<HourlyBarHit?>(null) }
    var isVisible by remember { mutableStateOf(false) }

    LaunchedEffect(selectedHit, popupOffset) {
        if (selectedHit != null) {
            activeData = selectedHit
            isVisible = true
        } else {
            isVisible = false
        }
    }

    val data = activeData ?: return
    val transition = updateTransition(targetState = isVisible, label = "HourlyPopupVisibility")

    LaunchedEffect(transition.currentState, transition.targetState) {
        if (!transition.currentState && !transition.targetState) {
            activeData = null
        }
    }
    if (!transition.currentState && !transition.targetState) return

    val hourLabel = "%d:00".format(LocalLocale.current.platformLocale, data.hour)
    val countLabel = pluralStringResource(Res.plurals.calendar_heatmap_memo_count, data.count, data.count)

    val positionProvider = remember(popupOffset, density) {
        HeatmapPopupPositionProvider(popupOffset, density)
    }

    androidx.compose.ui.window.Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
    ) {
        transition.AnimatedVisibility(
            visible = { it },
            enter = fadeIn(animationSpec = motionScheme.fastEffectsSpec()) +
                scaleIn(initialScale = 0.8f, animationSpec = motionScheme.fastSpatialSpec()),
            exit = fadeOut(animationSpec = motionScheme.fastEffectsSpec()) +
                scaleOut(targetScale = 0.8f, animationSpec = motionScheme.fastSpatialSpec()),
        ) {
            Surface(
                shape = StatsChartTokens.PopupShape,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = StatsChartTokens.PopupElevation,
                shadowElevation = StatsChartTokens.PopupElevation,
                modifier = Modifier.padding(StatsChartTokens.PopupMargin),
            ) {
                Column(
                    modifier = Modifier.padding(
                        horizontal = StatsChartTokens.PopupHorizontalPadding,
                        vertical = StatsChartTokens.PopupVerticalPadding,
                    ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = hourLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(StatsChartTokens.PopupTextSpacing))
                    Text(
                        text = countLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

internal data class BarChartColors(
    val empty: Color,
    val level1: Color,
    val level2: Color,
    val level3: Color,
    val level4: Color,
)

@Composable
private fun rememberBarChartColors(): BarChartColors = StatsChartTokens.barChartColors(MaterialTheme.colorScheme)

@Composable
private fun rememberBarChartTextPaint(density: androidx.compose.ui.unit.Density, textColor: Int): Paint {
    val densityScale = density.density
    val fontScale = density.fontScale
    return remember(textColor, densityScale, fontScale) {
        val labelFontSizePx = with(density) { StatsChartTokens.LabelFontSize.toPx() }
        Paint().apply {
            color = textColor
            textSize = labelFontSizePx
            isAntiAlias = true
            textAlign = Paint.Align.LEFT
        }
    }
}

private fun resolveBarColor(count: Int, maxCount: Int, colors: BarChartColors): Color {
    if (maxCount == 0 || count == 0) return colors.empty
    val ratio = count.toFloat() / maxCount
    return when {
        ratio <= LEVEL_ONE_MAX_RATIO -> colors.level1
        ratio <= LEVEL_TWO_MAX_RATIO -> colors.level2
        ratio <= LEVEL_THREE_MAX_RATIO -> colors.level3
        else -> colors.level4
    }
}
