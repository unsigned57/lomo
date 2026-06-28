package com.lomo.ui.component.stats

import android.graphics.Paint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.res.pluralStringResource
import com.lomo.ui.R
import com.lomo.ui.theme.MotionTokens
import kotlinx.collections.immutable.ImmutableMap

private const val HOURS_IN_DAY = 24
private const val HOUR_LABEL_INTERVAL = 6
private const val LEVEL_ONE_MAX_RATIO = 0.25f
private const val LEVEL_TWO_MAX_RATIO = 0.50f
private const val LEVEL_THREE_MAX_RATIO = 0.75f

private data class HourlyBarHit(
    val hour: Int,
    val count: Int,
)

@Composable
fun HourlyActivityChart(
    hourlyDistribution: ImmutableMap<Int, Int>,
    modifier: Modifier = Modifier,
) {
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

    val maxCount = remember(hourlyDistribution) {
        hourlyDistribution.values.maxOrNull() ?: 1
    }

    val totalHeight = chartHeightPx + labelAreaHeight

    var selectedHit by remember { mutableStateOf<HourlyBarHit?>(null) }
    var popupOffset by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(with(density) { totalHeight.toDp() })
                .pointerInput(hourlyDistribution) {
                    detectTapGestures(
                        onTap = { offset ->
                            val barStep = size.width.toFloat() / HOURS_IN_DAY
                            val hour = (offset.x / barStep).toInt()
                            if (hour in 0 until HOURS_IN_DAY && offset.y <= chartHeightPx) {
                                val count = hourlyDistribution[hour] ?: 0
                                if (selectedHit?.hour == hour) {
                                    selectedHit = null
                                } else {
                                    selectedHit = HourlyBarHit(hour = hour, count = count)
                                    popupOffset = Offset(
                                        hour * barStep + barStep / 2,
                                        0f,
                                    )
                                }
                            } else {
                                selectedHit = null
                            }
                        },
                    )
                },
        ) {
            val barStep = size.width / HOURS_IN_DAY
            val barWidth = barStep - spacingPx

            for (hour in 0 until HOURS_IN_DAY) {
                val count = hourlyDistribution[hour] ?: 0
                val x = hour * barStep + spacingPx / 2

                drawRoundRect(
                    color = colors.empty,
                    topLeft = Offset(x, 0f),
                    size = Size(barWidth, chartHeightPx),
                    cornerRadius = CornerRadius(cornerRadiusPx),
                )

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

                if (selectedHit?.hour == hour) {
                    drawRoundRect(
                        color = colors.level4,
                        topLeft = Offset(x, 0f),
                        size = Size(barWidth, chartHeightPx),
                        cornerRadius = CornerRadius(cornerRadiusPx),
                        style = Stroke(width = StatsChartTokens.SelectionStrokeWidth.toPx()),
                    )
                }

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

        HourlyBarSelectionPopup(
            selectedHit = selectedHit,
            popupOffset = popupOffset,
            density = density,
            onDismiss = { selectedHit = null },
        )
    }
}

@Composable
private fun HourlyBarSelectionPopup(
    selectedHit: HourlyBarHit?,
    popupOffset: Offset,
    density: androidx.compose.ui.unit.Density,
    onDismiss: () -> Unit,
) {
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

    val hourLabel = "%d:00".format(data.hour)
    val countLabel = pluralStringResource(R.plurals.calendar_heatmap_memo_count, data.count, data.count)

    val positionProvider = remember(popupOffset, density) {
        HeatmapPopupPositionProvider(popupOffset, density)
    }

    androidx.compose.ui.window.Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
    ) {
        transition.AnimatedVisibility(
            visible = { it },
            enter = fadeIn(animationSpec = tween(durationMillis = MotionTokens.DurationShort4)) +
                scaleIn(initialScale = 0.8f, animationSpec = tween(durationMillis = MotionTokens.DurationShort4)),
            exit = fadeOut(animationSpec = tween(durationMillis = MotionTokens.DurationShort4)) +
                scaleOut(targetScale = 0.8f, animationSpec = tween(durationMillis = MotionTokens.DurationShort4)),
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
private fun rememberBarChartColors(): BarChartColors =
    StatsChartTokens.barChartColors(MaterialTheme.colorScheme)

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
            textSize = with(density) { StatsChartTokens.LabelFontSize.toPx() }
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
