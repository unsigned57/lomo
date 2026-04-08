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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lomo.ui.R
import com.lomo.ui.theme.LomoTheme
import com.lomo.ui.theme.MotionTokens
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.toImmutableMap
import kotlin.math.roundToInt

private val HEATMAP_CELL_SIZE = 10.dp
private val HEATMAP_CELL_SPACING = 3.dp
private val HEATMAP_MONTH_LABEL_HEIGHT = 14.dp
private val HEATMAP_CELL_CORNER_RADIUS = 2.dp
private const val HEATMAP_EMPTY_ALPHA = 0.5f
private const val HEATMAP_LEVEL_THREE_ALPHA = 0.7f
internal val HEATMAP_POPUP_SHAPE = 8.dp
internal val HEATMAP_POPUP_ELEVATION = 3.dp
internal val HEATMAP_POPUP_MARGIN = 4.dp
internal val HEATMAP_POPUP_CONTENT_HORIZONTAL_PADDING = 12.dp
internal val HEATMAP_POPUP_CONTENT_VERTICAL_PADDING = 8.dp
internal val HEATMAP_POPUP_TEXT_SPACING = 2.dp
private const val HEATMAP_MONTH_LABEL_TRAILING_WEEKS = 2
private const val HEATMAP_MIN_LABEL_WEEKS = 3
internal const val HEATMAP_LEVEL_ONE_MAX = 1
internal const val HEATMAP_LEVEL_TWO_MAX = 3
internal const val HEATMAP_LEVEL_THREE_MAX = 6
private const val PREVIEW_DAY_RANGE = 120
private const val PREVIEW_LEVEL_FOUR_INTERVAL = 17
private const val PREVIEW_LEVEL_THREE_INTERVAL = 9
private const val PREVIEW_LEVEL_TWO_INTERVAL = 4
private const val PREVIEW_LEVEL_ONE_INTERVAL = 2
private const val PREVIEW_LEVEL_FOUR_COUNT = 8
private const val PREVIEW_LEVEL_THREE_COUNT = 5
private const val PREVIEW_LEVEL_TWO_COUNT = 2
private const val PREVIEW_LEVEL_ONE_COUNT = 1

@Composable
fun CalendarHeatmap(
    memoCountByDate: ImmutableMap<LocalDate, Int>,
    modifier: Modifier = Modifier,
    onDateLongPress: (LocalDate) -> Unit = {},
) {
    val today = LocalDate.now()
    val datePattern = stringResource(R.string.calendar_heatmap_date_format)
    val dateFormatter = remember(datePattern) { DateTimeFormatter.ofPattern(datePattern, Locale.getDefault()) }
    val density = LocalDensity.current
    val layout = rememberHeatmapLayout(density, today, memoCountByDate)
    val colors = rememberHeatmapColors()
    val textPaint =
        rememberHeatmapTextPaint(
            density = density,
            textColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb(),
        )
    var selectedDate by remember { mutableStateOf<LocalDate?>(null) }
    var popupOffset by remember { mutableStateOf(Offset.Zero) }
    val latestOnDateLongPress by rememberUpdatedState(onDateLongPress)
    val haptic = com.lomo.ui.util.LocalAppHapticFeedback.current
    val horizontalScrollState = rememberScrollState()

    LaunchedEffect(horizontalScrollState.maxValue, layout.totalWeeks) {
        if (horizontalScrollState.maxValue > 0 && horizontalScrollState.value < horizontalScrollState.maxValue) {
            horizontalScrollState.scrollTo(horizontalScrollState.maxValue)
        }
    }

    HeatmapInteractiveContent(
        modifier = modifier,
        layout = layout,
        colors = colors,
        textPaint = textPaint,
        density = density,
        horizontalScrollState = horizontalScrollState,
        selectedDate = selectedDate,
        popupOffset = popupOffset,
        memoCountByDate = memoCountByDate,
        dateFormatter = dateFormatter,
        onSelect = { hit ->
            if (selectedDate == hit.date) {
                selectedDate = null
            } else {
                selectedDate = hit.date
                popupOffset =
                    heatmapPopupOffset(
                        layout = layout,
                        hit = hit,
                        horizontalScrollOffsetPx = horizontalScrollState.value.toFloat(),
                    )
            }
        },
        onClearSelection = { selectedDate = null },
        onLongPress = { date ->
            haptic.longPress()
            latestOnDateLongPress(date)
            selectedDate = null
        },
        onDismissPopup = { selectedDate = null },
    )
}

internal data class HeatmapLayout(
    val cellSizePx: Float,
    val spacingPx: Float,
    val monthLabelHeightPx: Float,
    val cornerRadiusPx: Float,
    val weekWidth: Float,
    val totalWeeks: Int,
    val totalWidth: Float,
    val totalHeight: Float,
    val startDay: LocalDate,
    val today: LocalDate,
    val monthLabels: List<MonthLabel>,
    val heatmapCells: List<HeatmapCell>,
)

internal data class HeatmapColors(
    val empty: Color,
    val level1: Color,
    val level2: Color,
    val level3: Color,
    val level4: Color,
)

internal data class HeatmapPopupData(
    val date: LocalDate,
    val count: Int,
    val offset: Offset,
)

@Composable
private fun rememberHeatmapLayout(
    density: androidx.compose.ui.unit.Density,
    today: LocalDate,
    memoCountByDate: ImmutableMap<LocalDate, Int>,
): HeatmapLayout {
    val cellSizePx = with(density) { HEATMAP_CELL_SIZE.toPx() }
    val spacingPx = with(density) { HEATMAP_CELL_SPACING.toPx() }
    val monthLabelHeightPx = with(density) { HEATMAP_MONTH_LABEL_HEIGHT.toPx() }
    val cornerRadiusPx = with(density) { HEATMAP_CELL_CORNER_RADIUS.toPx() }
    val earliestMemoDate = memoCountByDate.keys.minOrNull() ?: today
    val totalWeeks =
        calculateWeeksToCoverEarliestDate(
            today = today,
            earliestDate = earliestMemoDate,
        ).coerceAtLeast(MIN_WEEKS)
    val weekWidth = cellSizePx + spacingPx
    val totalWidth = totalWeeks * weekWidth
    val totalHeight = monthLabelHeightPx + DAYS_PER_WEEK * weekWidth
    val daysToSubtract = (totalWeeks - 1) * DAYS_PER_WEEK + today.dayOfWeek.value % DAYS_PER_WEEK
    val startDay = today.minusDays(daysToSubtract.toLong())
    val monthLabels = remember(startDay, totalWeeks) { buildMonthLabels(startDay, totalWeeks) }
    val heatmapCells =
        remember(startDay, today, totalWeeks, memoCountByDate) {
            buildHeatmapCells(startDay, today, totalWeeks, memoCountByDate)
        }

    return remember(
        cellSizePx,
        spacingPx,
        monthLabelHeightPx,
        cornerRadiusPx,
        weekWidth,
        totalWeeks,
        totalWidth,
        totalHeight,
        startDay,
        today,
        monthLabels,
        heatmapCells,
    ) {
        HeatmapLayout(
            cellSizePx = cellSizePx,
            spacingPx = spacingPx,
            monthLabelHeightPx = monthLabelHeightPx,
            cornerRadiusPx = cornerRadiusPx,
            weekWidth = weekWidth,
            totalWeeks = totalWeeks,
            totalWidth = totalWidth,
            totalHeight = totalHeight,
            startDay = startDay,
            today = today,
            monthLabels = monthLabels,
            heatmapCells = heatmapCells,
        )
    }
}

@Composable
private fun rememberHeatmapColors(): HeatmapColors =
    HeatmapColors(
        empty = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = HEATMAP_EMPTY_ALPHA),
        level1 = MaterialTheme.colorScheme.primaryContainer.copy(alpha = HEATMAP_EMPTY_ALPHA),
        level2 = MaterialTheme.colorScheme.primaryContainer,
        level3 = MaterialTheme.colorScheme.primary.copy(alpha = HEATMAP_LEVEL_THREE_ALPHA),
        level4 = MaterialTheme.colorScheme.primary,
    )

@Composable
private fun rememberHeatmapTextPaint(
    density: androidx.compose.ui.unit.Density,
    textColor: Int,
): Paint {
    val densityScale = density.density
    val fontScale = density.fontScale
    return remember(textColor, densityScale, fontScale) {
        Paint().apply {
            color = textColor
            textSize = with(density) { 9.sp.toPx() }
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.LEFT
        }
    }
}

internal fun heatmapPopupOffset(
    layout: HeatmapLayout,
    hit: HeatmapCellHit,
    horizontalScrollOffsetPx: Float = 0f,
): Offset =
    Offset(
        hit.col * layout.weekWidth + layout.cellSizePx / 2 - horizontalScrollOffsetPx,
        layout.monthLabelHeightPx + hit.row * layout.weekWidth,
    )

internal fun resolveTappedCell(
    offset: Offset,
    layout: HeatmapLayout,
): HeatmapCellHit? {
    val y = offset.y - layout.monthLabelHeightPx
    val hasValidRowOffset = y in 0f..(DAYS_PER_WEEK * layout.weekWidth)
    val col = (offset.x / layout.weekWidth).toInt()
    val row = (y / layout.weekWidth).toInt()
    val isWithinHeatmapBounds = col in 0 until layout.totalWeeks && row in 0..LAST_WEEKDAY_INDEX

    if (!hasValidRowOffset || !isWithinHeatmapBounds) {
        return null
    }

    val date = layout.startDay.plusWeeks(col.toLong()).plusDays(row.toLong())
    return if (date.isAfter(layout.today)) null else HeatmapCellHit(col = col, row = row, date = date)
}

private fun calculateWeeksToCoverEarliestDate(
    today: LocalDate,
    earliestDate: LocalDate,
): Int {
    val normalizedEarliestDate = earliestDate.coerceAtMost(today)
    val todayOffsetInWeek = today.dayOfWeek.value % DAYS_PER_WEEK
    val daysBetween =
        ChronoUnit.DAYS
            .between(normalizedEarliestDate, today)
            .toInt()
            .coerceAtLeast(0)
    val daysOutsideCurrentWeek = (daysBetween - todayOffsetInWeek).coerceAtLeast(0)
    return (daysOutsideCurrentWeek + DAYS_PER_WEEK - 1) / DAYS_PER_WEEK + 1
}

internal data class HeatmapCellHit(
    val col: Int,
    val row: Int,
    val date: LocalDate,
)

internal data class MonthLabel(
    val week: Int,
    val text: String,
)

internal data class HeatmapCell(
    val week: Int,
    val day: Int,
    val date: LocalDate,
    val count: Int,
)

private fun buildMonthLabels(
    startDay: LocalDate,
    totalWeeks: Int,
): List<MonthLabel> {
    var currentMonth = -1
    val labels = mutableListOf<MonthLabel>()
    for (week in 0 until totalWeeks) {
        val dateOfWeekStart = startDay.plusWeeks(week.toLong())
        val month = dateOfWeekStart.monthValue
        if (month != currentMonth) {
            if (
                week < totalWeeks - HEATMAP_MONTH_LABEL_TRAILING_WEEKS ||
                totalWeeks < HEATMAP_MIN_LABEL_WEEKS
            ) {
                labels += MonthLabel(
                    week = week,
                    text = dateOfWeekStart.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                )
            }
            currentMonth = month
        }
    }
    return labels
}

private fun buildHeatmapCells(
    startDay: LocalDate,
    today: LocalDate,
    totalWeeks: Int,
    memoCountByDate: Map<LocalDate, Int>,
): List<HeatmapCell> {
    val cells = ArrayList<HeatmapCell>(totalWeeks * DAYS_PER_WEEK)
    for (week in 0 until totalWeeks) {
        for (day in 0 until DAYS_PER_WEEK) {
            val date = startDay.plusWeeks(week.toLong()).plusDays(day.toLong())
            if (date.isAfter(today)) {
                continue
            }
            cells += HeatmapCell(
                week = week,
                day = day,
                date = date,
                count = memoCountByDate[date] ?: 0,
            )
        }
    }
    return cells
}

internal class HeatmapPopupPositionProvider(
    private val contentOffset: Offset,
    private val density: androidx.compose.ui.unit.Density,
) : androidx.compose.ui.window.PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: androidx.compose.ui.unit.IntRect,
        windowSize: androidx.compose.ui.unit.IntSize,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        popupContentSize: androidx.compose.ui.unit.IntSize,
    ): androidx.compose.ui.unit.IntOffset {
        val targetX = anchorBounds.left + contentOffset.x
        val targetY = anchorBounds.top + contentOffset.y

        // Center the popup horizontally relative to the target point
        val centeredPopupX = targetX.roundToInt() - (popupContentSize.width / 2)
        val minPopupX = anchorBounds.left
        val maxPopupX = (anchorBounds.right - popupContentSize.width).coerceAtLeast(minPopupX)
        val popupX = centeredPopupX.coerceIn(minPopupX, maxPopupX)

        // Place popup above the target point
        val margin = with(density) { 8.dp.roundToPx() }
        val popupY = targetY.toInt() - popupContentSize.height - margin

        return androidx.compose.ui.unit
            .IntOffset(popupX, popupY)
    }
}

private const val MIN_WEEKS = 52
private const val DAYS_PER_WEEK = 7
private const val LAST_WEEKDAY_INDEX = DAYS_PER_WEEK - 1

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun CalendarHeatmapPreview() {
    val today = LocalDate.now()
    val sampleMemoCountByDate =
        remember(today) {
            buildMap {
                for (dayOffset in 0..PREVIEW_DAY_RANGE) {
                    val date = today.minusDays(dayOffset.toLong())
                    val count =
                        when {
                            dayOffset % PREVIEW_LEVEL_FOUR_INTERVAL == 0 -> PREVIEW_LEVEL_FOUR_COUNT
                            dayOffset % PREVIEW_LEVEL_THREE_INTERVAL == 0 -> PREVIEW_LEVEL_THREE_COUNT
                            dayOffset % PREVIEW_LEVEL_TWO_INTERVAL == 0 -> PREVIEW_LEVEL_TWO_COUNT
                            dayOffset % PREVIEW_LEVEL_ONE_INTERVAL == 0 -> PREVIEW_LEVEL_ONE_COUNT
                            else -> 0
                        }
                    if (count > 0) put(date, count)
                }
            }
        }

    LomoTheme {
        Surface(modifier = Modifier.padding(16.dp)) {
            CalendarHeatmap(
                memoCountByDate = sampleMemoCountByDate.toImmutableMap(),
                modifier = Modifier.width(320.dp),
            )
        }
    }
}
