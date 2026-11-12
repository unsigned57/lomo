package com.lomo.ui.component.stats

import android.graphics.Paint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import org.jetbrains.compose.resources.pluralStringResource
import java.time.DayOfWeek
import java.time.format.TextStyle

private const val DAYS_IN_WEEK = 7
private const val LEVEL_ONE_MAX_RATIO = 0.25f
private const val LEVEL_TWO_MAX_RATIO = 0.50f
private const val LEVEL_THREE_MAX_RATIO = 0.75f

private data class WeeklyHeatmapCellHit(val dayIndex: Int, val hour: Int, val day: DayOfWeek, val count: Int)

@Composable
fun WeeklyHourHeatmap(
    weeklyHourDistribution: ImmutableMap<DayOfWeek, ImmutableMap<Int, Int>>,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val spacingPx = with(density) { StatsChartTokens.WeeklyCellSpacing.toPx() }
    val cornerRadiusPx = with(density) { StatsChartTokens.CellCornerRadius.toPx() }

    val colors = rememberWeeklyHeatmapColors()
    val textPaint = rememberWeeklyHeatmapTextPaint(
        density = density,
        textColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb(),
    )
    val dayLabels = rememberHourAxisDayLabels()
    val gutterPx = rememberHourAxisGutterPx(textPaint = textPaint, spacingPx = spacingPx)

    val maxCount = remember(weeklyHourDistribution) {
        weeklyHourDistribution.values.flatMap { it.values }.maxOrNull() ?: 1
    }

    val hourLabelHeightPx = with(density) { StatsChartTokens.LabelFontSize.toPx() } + spacingPx

    var selectedHit by remember { mutableStateOf<WeeklyHeatmapCellHit?>(null) }
    var popupOffset by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            // The grid fills the width to the right of the shared day-name gutter, so this chart
            // spans the same width as the hourly bar chart below it.
            val cellStep =
                HourAxis.stepPx(
                    widthPx = with(density) { maxWidth.toPx() },
                    gutterPx = gutterPx,
                )
            val cellSizePx = (cellStep - spacingPx).coerceAtLeast(1f)
            val totalHeight = hourLabelHeightPx + DAYS_IN_WEEK * cellStep
            WeeklyHeatmapCanvas(
                weeklyHourDistribution = weeklyHourDistribution,
                dayOrder = HourAxisDayOrder,
                dayLabels = dayLabels,
                maxCount = maxCount,
                colors = colors,
                textPaint = textPaint,
                cellSizePx = cellSizePx,
                spacingPx = spacingPx,
                cornerRadiusPx = cornerRadiusPx,
                cellStep = cellStep,
                gutterPx = gutterPx,
                hourLabelHeightPx = hourLabelHeightPx,
                totalHeight = totalHeight,
                selectedHit = selectedHit,
                density = density,
                onTap = { hit, offset ->
                    if (hit == null) {
                        selectedHit = null
                    } else if (selectedHit?.dayIndex == hit.dayIndex && selectedHit?.hour == hit.hour) {
                        selectedHit = null
                    } else {
                        selectedHit = hit
                        popupOffset = offset
                    }
                },
            )
        }

        WeeklyHeatmapSelectionPopup(
            selectedHit = selectedHit,
            popupOffset = popupOffset,
            density = density,
            onDismiss = { selectedHit = null },
        )
    }
}

@Composable
private fun WeeklyHeatmapCanvas(
    weeklyHourDistribution: ImmutableMap<DayOfWeek, ImmutableMap<Int, Int>>,
    dayOrder: ImmutableList<DayOfWeek>,
    dayLabels: ImmutableList<String>,
    maxCount: Int,
    colors: WeeklyHeatmapColors,
    textPaint: Paint,
    cellSizePx: Float,
    spacingPx: Float,
    cornerRadiusPx: Float,
    cellStep: Float,
    gutterPx: Float,
    hourLabelHeightPx: Float,
    totalHeight: Float,
    selectedHit: WeeklyHeatmapCellHit?,
    density: androidx.compose.ui.unit.Density,
    onTap: (WeeklyHeatmapCellHit?, Offset) -> Unit,
) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(with(density) { totalHeight.toDp() })
            .pointerInput(weeklyHourDistribution, gutterPx, cellStep, hourLabelHeightPx) {
                detectTapGestures(
                    onTap = { offset ->
                        val hit = resolveWeeklyHeatmapHit(
                            offset = offset,
                            gutterPx = gutterPx,
                            cellStep = cellStep,
                            hourLabelHeightPx = hourLabelHeightPx,
                            dayOrder = dayOrder,
                            weeklyHourDistribution = weeklyHourDistribution,
                        )
                        val popupPos = if (hit != null) {
                            Offset(
                                gutterPx + hit.hour * cellStep + cellSizePx / 2,
                                hourLabelHeightPx + hit.dayIndex * cellStep,
                            )
                        } else {
                            Offset.Zero
                        }
                        onTap(hit, popupPos)
                    },
                )
            },
    ) {
        for (hour in 0 until HourAxis.HoursInDay) {
            if (hour % HourAxis.LabelInterval == 0) {
                val label = "${hour}h"
                val textWidth = textPaint.measureText(label)
                drawContext.canvas.nativeCanvas.drawText(
                    label,
                    gutterPx + hour * cellStep + cellSizePx / 2 - textWidth / 2,
                    hourLabelHeightPx - spacingPx,
                    textPaint,
                )
            }
        }

        for ((dayIndex, day) in dayOrder.withIndex()) {
            val rowY = hourLabelHeightPx + dayIndex * cellStep
            val textHeight = textPaint.descent() - textPaint.ascent()
            drawContext.canvas.nativeCanvas.drawText(
                dayLabels[dayIndex],
                0f,
                rowY + cellSizePx / 2 + textHeight / 2 - textPaint.descent(),
                textPaint,
            )

            val hourMap = weeklyHourDistribution[day].orEmpty()
            for (hour in 0 until HourAxis.HoursInDay) {
                val count = hourMap[hour] ?: 0
                val x = gutterPx + hour * cellStep
                drawRoundRect(
                    color = resolveWeeklyHeatmapColor(count, maxCount, colors),
                    topLeft = Offset(x, rowY),
                    size = Size(cellSizePx, cellSizePx),
                    cornerRadius = CornerRadius(cornerRadiusPx),
                )

                val current = selectedHit
                if (current != null && current.dayIndex == dayIndex && current.hour == hour) {
                    drawRoundRect(
                        color = colors.level4,
                        topLeft = Offset(x, rowY),
                        size = Size(cellSizePx, cellSizePx),
                        cornerRadius = CornerRadius(cornerRadiusPx),
                        style = Stroke(width = StatsChartTokens.SelectionStrokeWidth.toPx()),
                    )
                }
            }
        }
    }
}

@Composable
private fun WeeklyHeatmapSelectionPopup(
    selectedHit: WeeklyHeatmapCellHit?,
    popupOffset: Offset,
    density: androidx.compose.ui.unit.Density,
    onDismiss: () -> Unit,
) {
    val motionScheme = LocalLomoMotion.current.component
    var activeData by remember { mutableStateOf<WeeklyHeatmapCellHit?>(null) }
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
    val transition = updateTransition(targetState = isVisible, label = "WeeklyPopupVisibility")

    LaunchedEffect(transition.currentState, transition.targetState) {
        if (!transition.currentState && !transition.targetState) {
            activeData = null
        }
    }
    if (!transition.currentState && !transition.targetState) return

    val locale = LocalLocale.current.platformLocale
    val dayLabel = remember(data.day, locale) {
        data.day.getDisplayName(TextStyle.SHORT, locale)
    }
    val hourLabel = "%d:00".format(locale, data.hour)
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
                        text = "$dayLabel $hourLabel",
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

private fun resolveWeeklyHeatmapHit(
    offset: Offset,
    gutterPx: Float,
    cellStep: Float,
    hourLabelHeightPx: Float,
    dayOrder: List<DayOfWeek>,
    weeklyHourDistribution: ImmutableMap<DayOfWeek, ImmutableMap<Int, Int>>,
): WeeklyHeatmapCellHit? {
    val x = offset.x - gutterPx
    val y = offset.y - hourLabelHeightPx
    if (x < 0 || y < 0) return null

    val hour = (x / cellStep).toInt()
    val dayIndex = (y / cellStep).toInt()
    if (hour !in 0 until HourAxis.HoursInDay || dayIndex !in 0 until DAYS_IN_WEEK) return null

    val day = dayOrder[dayIndex]
    val count = weeklyHourDistribution[day]?.get(hour) ?: 0
    return WeeklyHeatmapCellHit(dayIndex = dayIndex, hour = hour, day = day, count = count)
}

internal data class WeeklyHeatmapColors(
    val empty: Color,
    val level1: Color,
    val level2: Color,
    val level3: Color,
    val level4: Color,
)

@Composable
private fun rememberWeeklyHeatmapColors(): WeeklyHeatmapColors =
    StatsChartTokens.weeklyHeatmapColors(MaterialTheme.colorScheme)

@Composable
private fun rememberWeeklyHeatmapTextPaint(density: androidx.compose.ui.unit.Density, textColor: Int): Paint {
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

private fun resolveWeeklyHeatmapColor(count: Int, maxCount: Int, colors: WeeklyHeatmapColors): Color {
    if (maxCount == 0 || count == 0) return colors.empty
    val ratio = count.toFloat() / maxCount
    return when {
        ratio <= LEVEL_ONE_MAX_RATIO -> colors.level1
        ratio <= LEVEL_TWO_MAX_RATIO -> colors.level2
        ratio <= LEVEL_THREE_MAX_RATIO -> colors.level3
        else -> colors.level4
    }
}
