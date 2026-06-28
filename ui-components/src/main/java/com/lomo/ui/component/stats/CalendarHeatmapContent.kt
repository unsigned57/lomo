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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import com.lomo.domain.model.CalendarHeatmapThresholds
import com.lomo.ui.R
import com.lomo.ui.theme.MotionTokens
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.collections.immutable.ImmutableMap

@Composable
internal fun HeatmapInteractiveContent(
    layout: HeatmapLayout,
    colors: HeatmapColors,
    textPaint: Paint,
    density: androidx.compose.ui.unit.Density,
    horizontalScrollState: androidx.compose.foundation.ScrollState,
    selectedDate: LocalDate?,
    popupOffset: Offset,
    thresholds: CalendarHeatmapThresholds,
    memoCountByDate: ImmutableMap<LocalDate, Int>,
    dateFormatter: DateTimeFormatter,
    onSelect: (HeatmapCellHit) -> Unit,
    onClearSelection: () -> Unit,
    onLongPress: (LocalDate) -> Unit,
    onDismissPopup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val visibleYear by remember(layout, density, horizontalScrollState) {
        androidx.compose.runtime.derivedStateOf {
            val weekWidthPx = layout.weekWidth
            val scrollValue = horizontalScrollState.value
            val visibleCol = if (weekWidthPx > 0f) (scrollValue / weekWidthPx).toInt().coerceAtLeast(0) else 0
            val visibleDate = layout.startDay.plusWeeks(visibleCol.toLong())
            visibleDate.year
        }
    }

    Column(
        modifier = modifier.padding(vertical = StatsChartTokens.PopupMargin),
    ) {
        Text(
            text = "${visibleYear}年",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = StatsChartTokens.PopupMargin),
        )
        Box(
            modifier = Modifier.fillMaxWidth(),
        ) {
            HeatmapScrollableCanvas(
                layout = layout,
                colors = colors,
                textPaint = textPaint,
                density = density,
                horizontalScrollState = horizontalScrollState,
                selectedDate = selectedDate,
                thresholds = thresholds,
                onSelect = onSelect,
                onClearSelection = onClearSelection,
                onLongPress = onLongPress,
            )
            HeatmapSelectionPopup(
                selectedDate = selectedDate,
                memoCountByDate = memoCountByDate,
                popupOffset = popupOffset,
                dateFormatter = dateFormatter,
                density = density,
                onDismiss = onDismissPopup,
            )
        }
    }
}

@Composable
private fun HeatmapScrollableCanvas(
    layout: HeatmapLayout,
    colors: HeatmapColors,
    textPaint: Paint,
    density: androidx.compose.ui.unit.Density,
    horizontalScrollState: androidx.compose.foundation.ScrollState,
    selectedDate: LocalDate?,
    thresholds: CalendarHeatmapThresholds,
    onSelect: (HeatmapCellHit) -> Unit,
    onClearSelection: () -> Unit,
    onLongPress: (LocalDate) -> Unit,
) {
    val gestureModifier =
        Modifier.pointerInput(layout) {
            detectTapGestures(
                onTap = { offset ->
                    val hit = resolveTappedCell(offset, layout)
                    if (hit == null) onClearSelection() else onSelect(hit)
                },
                onLongPress = { offset ->
                    resolveTappedCell(offset, layout)?.let { hit -> onLongPress(hit.date) }
                },
            )
        }

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(horizontalScrollState),
    ) {
        Box(
            modifier =
                Modifier
                    .width(with(density) { layout.totalWidth.toDp() })
                    .height(with(density) { layout.totalHeight.toDp() }),
        ) {
            Canvas(modifier = Modifier.fillMaxSize().then(gestureModifier)) {
                drawHeatmapMonthLabels(layout, textPaint)
                drawHeatmapCells(layout, colors, selectedDate, thresholds)
            }
        }
    }
}

@Composable
private fun HeatmapSelectionPopup(
    selectedDate: LocalDate?,
    memoCountByDate: ImmutableMap<LocalDate, Int>,
    popupOffset: Offset,
    dateFormatter: DateTimeFormatter,
    density: androidx.compose.ui.unit.Density,
    onDismiss: () -> Unit,
) {
    var activePopupData by remember { mutableStateOf<HeatmapPopupData?>(null) }
    var isPopupVisible by remember { mutableStateOf(false) }

    LaunchedEffect(selectedDate, memoCountByDate, popupOffset) {
        if (selectedDate != null) {
            activePopupData = HeatmapPopupData(selectedDate, memoCountByDate[selectedDate] ?: 0, popupOffset)
            isPopupVisible = true
        } else {
            isPopupVisible = false
        }
    }

    val popupData = activePopupData ?: return
    val transition = updateTransition(targetState = isPopupVisible, label = "PopupVisibility")

    LaunchedEffect(transition.currentState, transition.targetState) {
        if (!transition.currentState && !transition.targetState) {
            activePopupData = null
        }
    }
    if (!transition.currentState && !transition.targetState) return

    val countLabel = pluralStringResource(R.plurals.calendar_heatmap_memo_count, popupData.count, popupData.count)
    val popupPositionProvider =
        remember(popupData.offset, density) {
            HeatmapPopupPositionProvider(popupData.offset, density)
        }

    androidx.compose.ui.window.Popup(
        popupPositionProvider = popupPositionProvider,
        onDismissRequest = onDismiss,
    ) {
        transition.AnimatedVisibility(
            visible = { it },
            enter =
                fadeIn(animationSpec = tween(durationMillis = MotionTokens.DurationShort4)) +
                    scaleIn(
                        initialScale = 0.8f,
                        animationSpec = tween(durationMillis = MotionTokens.DurationShort4),
                    ),
            exit =
                fadeOut(animationSpec = tween(durationMillis = MotionTokens.DurationShort4)) +
                    scaleOut(
                        targetScale = 0.8f,
                        animationSpec = tween(durationMillis = MotionTokens.DurationShort4),
                    ),
        ) {
            HeatmapPopupCard(
                formattedDate = popupData.date.format(dateFormatter),
                countLabel = countLabel,
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHeatmapMonthLabels(
    layout: HeatmapLayout,
    textPaint: Paint,
) {
    resolveMonthLabelPlacements(
        labels = layout.monthLabels,
        weekWidth = layout.weekWidth,
        totalWidth = size.width,
        minimumSpacingPx = StatsChartTokens.MonthLabelMinimumSpacing.toPx(),
        textWidth = { text -> textPaint.measureText(text) },
    ).forEach { label ->
        drawContext.canvas.nativeCanvas.drawText(
            label.text,
            label.drawX,
            layout.monthLabelHeightPx - StatsChartTokens.MonthLabelBaselineInset.toPx(),
            textPaint,
        )
    }
}

internal data class ResolvedMonthLabelPlacement(
    val text: String,
    val drawX: Float,
    val widthPx: Float,
)

internal fun resolveMonthLabelPlacements(
    labels: List<MonthLabel>,
    weekWidth: Float,
    totalWidth: Float,
    minimumSpacingPx: Float,
    textWidth: (String) -> Float,
): List<ResolvedMonthLabelPlacement> {
    var previousRight = Float.NEGATIVE_INFINITY
    val placements = mutableListOf<ResolvedMonthLabelPlacement>()
    labels.forEach { label ->
        val widthPx = textWidth(label.text).coerceAtLeast(0f)
        val anchoredX = label.week * weekWidth
        val drawX = maxOf(anchoredX, previousRight + minimumSpacingPx)
        val drawRight = drawX + widthPx
        if (drawRight <= totalWidth) {
            placements +=
                ResolvedMonthLabelPlacement(
                    text = label.text,
                    drawX = drawX,
                    widthPx = widthPx,
                )
            previousRight = drawRight
        }
    }
    return placements
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHeatmapCells(
    layout: HeatmapLayout,
    colors: HeatmapColors,
    selectedDate: LocalDate?,
    thresholds: CalendarHeatmapThresholds,
) {
    layout.heatmapCells.forEach { cell ->
        val left = cell.week * layout.weekWidth
        val top = layout.monthLabelHeightPx + cell.day * layout.weekWidth

        drawRoundRect(
            color = resolveHeatmapColor(cell.count, colors, thresholds),
            topLeft = Offset(left, top),
            size = Size(layout.cellSizePx, layout.cellSizePx),
            cornerRadius = CornerRadius(layout.cornerRadiusPx),
        )

        if (cell.date == selectedDate) {
            drawRoundRect(
                color = colors.level4,
                topLeft = Offset(left, top),
                size = Size(layout.cellSizePx, layout.cellSizePx),
                cornerRadius = CornerRadius(layout.cornerRadiusPx),
                style =
                    androidx.compose.ui.graphics.drawscope.Stroke(
                        width = StatsChartTokens.SelectionStrokeWidth.toPx(),
                    ),
            )
        }
    }
}

private fun resolveHeatmapColor(
    count: Int,
    colors: HeatmapColors,
    thresholds: CalendarHeatmapThresholds,
): Color =
    when (resolveHeatmapIntensity(count, thresholds)) {
        HeatmapIntensity.Empty -> colors.empty
        HeatmapIntensity.Level1 -> colors.level1
        HeatmapIntensity.Level2 -> colors.level2
        HeatmapIntensity.Level3 -> colors.level3
        HeatmapIntensity.Level4 -> colors.level4
    }

@Composable
private fun HeatmapPopupCard(
    formattedDate: String,
    countLabel: String,
) {
    Surface(
        shape = StatsChartTokens.PopupShape,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = StatsChartTokens.PopupElevation,
        shadowElevation = StatsChartTokens.PopupElevation,
        modifier = Modifier.padding(StatsChartTokens.PopupMargin),
    ) {
        Column(
            modifier =
                Modifier.padding(
                    horizontal = StatsChartTokens.PopupHorizontalPadding,
                    vertical = StatsChartTokens.PopupVerticalPadding,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = formattedDate,
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
