package com.lomo.ui.component.stats

import android.graphics.Paint
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.lomo.domain.model.CalendarHeatmapIntensity
import com.lomo.domain.model.CalendarHeatmapThresholds
import com.lomo.ui.R
import com.lomo.ui.theme.LomoTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.toImmutableMap
import kotlin.math.roundToInt

private const val PREVIEW_DAY_RANGE = 120
private const val PREVIEW_LEVEL_FOUR_INTERVAL = 17
private const val PREVIEW_LEVEL_THREE_INTERVAL = 9
private const val PREVIEW_LEVEL_TWO_INTERVAL = 4
private const val PREVIEW_LEVEL_ONE_INTERVAL = 2
private const val PREVIEW_LEVEL_FOUR_COUNT = 8
private const val PREVIEW_LEVEL_THREE_COUNT = 5
private const val PREVIEW_LEVEL_TWO_COUNT = 2
private const val PREVIEW_LEVEL_ONE_COUNT = 1
private val PREVIEW_TODAY: LocalDate = LocalDate.of(2026, 5, 22)

@Composable
fun CalendarHeatmap(
    memoCountByDate: ImmutableMap<LocalDate, Int>,
    today: LocalDate,
    thresholds: CalendarHeatmapThresholds,
    modifier: Modifier = Modifier,
    onDateLongPress: (LocalDate) -> Unit = {},
) {
    val datePattern = stringResource(R.string.calendar_heatmap_date_format)
    val dateFormatter = remember(datePattern) { DateTimeFormatter.ofPattern(datePattern, Locale.getDefault()) }
    val density = LocalDensity.current
    val window = remember(today, memoCountByDate) {
        resolveCalendarHeatmapWindow(
            today = today,
            memoCountByDate = memoCountByDate,
        )
    }
    val layout = rememberHeatmapLayout(density, window)
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
        thresholds = thresholds,
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

internal data class CalendarHeatmapWindow(
    val today: LocalDate,
    val startDay: LocalDate,
    val totalWeeks: Int,
    val monthLabels: List<MonthLabel>,
    val heatmapCells: List<HeatmapCell>,
)

internal enum class HeatmapIntensity {
    Empty,
    Level1,
    Level2,
    Level3,
    Level4,
}

internal fun resolveCalendarHeatmapWindow(
    today: LocalDate,
    memoCountByDate: Map<LocalDate, Int>,
): CalendarHeatmapWindow {
    val memoCountUntilToday = memoCountByDate.filterKeys { !it.isAfter(today) }
    val earliestMemoDate = memoCountUntilToday.keys.minOrNull() ?: today
    val totalWeeks =
        calculateWeeksToCoverEarliestDate(
            today = today,
            earliestDate = earliestMemoDate,
        ).coerceAtLeast(MIN_WEEKS)
    val daysToSubtract = (totalWeeks - 1) * DAYS_PER_WEEK + today.dayOfWeek.value % DAYS_PER_WEEK
    val startDay = today.minusDays(daysToSubtract.toLong())

    return CalendarHeatmapWindow(
        today = today,
        startDay = startDay,
        totalWeeks = totalWeeks,
        monthLabels = buildMonthLabels(startDay, totalWeeks),
        heatmapCells = buildHeatmapCells(startDay, today, totalWeeks, memoCountUntilToday),
    )
}

internal fun resolveHeatmapIntensity(
    count: Int,
    thresholds: CalendarHeatmapThresholds,
): HeatmapIntensity =
    when (thresholds.intensityForCount(count)) {
        CalendarHeatmapIntensity.Empty -> HeatmapIntensity.Empty
        CalendarHeatmapIntensity.Level1 -> HeatmapIntensity.Level1
        CalendarHeatmapIntensity.Level2 -> HeatmapIntensity.Level2
        CalendarHeatmapIntensity.Level3 -> HeatmapIntensity.Level3
        CalendarHeatmapIntensity.Level4 -> HeatmapIntensity.Level4
    }

@Composable
private fun rememberHeatmapLayout(
    density: androidx.compose.ui.unit.Density,
    window: CalendarHeatmapWindow,
): HeatmapLayout {
    val cellSizePx = with(density) { StatsChartTokens.CalendarCellSize.toPx() }
    val spacingPx = with(density) { StatsChartTokens.CalendarCellSpacing.toPx() }
    val monthLabelHeightPx = with(density) { StatsChartTokens.CalendarMonthLabelHeight.toPx() }
    val cornerRadiusPx = with(density) { StatsChartTokens.CellCornerRadius.toPx() }
    val weekWidth = cellSizePx + spacingPx
    val totalWidth = window.totalWeeks * weekWidth
    val totalHeight = monthLabelHeightPx + DAYS_PER_WEEK * weekWidth

    return remember(
        cellSizePx,
        spacingPx,
        monthLabelHeightPx,
        cornerRadiusPx,
        weekWidth,
        window.totalWeeks,
        totalWidth,
        totalHeight,
        window,
    ) {
        HeatmapLayout(
            cellSizePx = cellSizePx,
            spacingPx = spacingPx,
            monthLabelHeightPx = monthLabelHeightPx,
            cornerRadiusPx = cornerRadiusPx,
            weekWidth = weekWidth,
            totalWeeks = window.totalWeeks,
            totalWidth = totalWidth,
            totalHeight = totalHeight,
            startDay = window.startDay,
            today = window.today,
            monthLabels = window.monthLabels,
            heatmapCells = window.heatmapCells,
        )
    }
}

@Composable
private fun rememberHeatmapColors(): HeatmapColors =
    StatsChartTokens.heatmapColors(MaterialTheme.colorScheme)

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
            textSize = with(density) { StatsChartTokens.LabelFontSize.toPx() }
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

internal data class HeatmapCellHit(
    val col: Int,
    val row: Int,
    val date: LocalDate,
)

internal data class MonthLabel(
    val week: Int,
    val text: String,
    val year: Int,
)

internal data class HeatmapCell(
    val week: Int,
    val day: Int,
    val date: LocalDate,
    val count: Int,
)

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
        val margin = with(density) { StatsChartTokens.PopupAnchorMargin.roundToPx() }
        val popupY = targetY.toInt() - popupContentSize.height - margin

        return androidx.compose.ui.unit
            .IntOffset(popupX, popupY)
    }
}

internal const val MIN_WEEKS = 52
internal const val DAYS_PER_WEEK = 7
internal const val LAST_WEEKDAY_INDEX = DAYS_PER_WEEK - 1

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun CalendarHeatmapPreview() {
    val today = PREVIEW_TODAY
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
        Surface(modifier = Modifier.padding(com.lomo.ui.theme.AppSpacing.Medium)) {
            CalendarHeatmap(
                memoCountByDate = sampleMemoCountByDate.toImmutableMap(),
                today = today,
                thresholds = CalendarHeatmapThresholds.default(),
                modifier = Modifier.width(320.dp),
            )
        }
    }
}
