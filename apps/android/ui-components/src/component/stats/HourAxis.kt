package com.lomo.ui.component.stats

import android.graphics.Paint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/**
 * One shared 24-hour axis for every hour-based chart in the statistics screen.
 *
 * The weekly heatmap and the hourly bar chart plot the same hour domain, so they must agree on the
 * column geometry: the same day-name gutter on the left and the same column step across the
 * remaining width. Deriving both from [stepPx] and [dayNameGutterPx] is what keeps the two charts
 * the same width and their hours aligned column for column.
 */
internal object HourAxis {
    const val HoursInDay = 24

    /** Axis labels every six hours ("0h", "6h", "12h", "18h") on every hour chart. */
    const val LabelInterval = 6

    /** Column step for a 24-hour axis that fills [widthPx] to the right of [gutterPx]. */
    fun stepPx(widthPx: Float, gutterPx: Float): Float = ((widthPx - gutterPx) / HoursInDay).coerceAtLeast(1f)
}

internal val HourAxisDayOrder: ImmutableList<DayOfWeek> =
    listOf(
        DayOfWeek.MONDAY,
        DayOfWeek.TUESDAY,
        DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY,
        DayOfWeek.FRIDAY,
        DayOfWeek.SATURDAY,
        DayOfWeek.SUNDAY,
    ).toImmutableList()

@Composable
internal fun rememberHourAxisDayLabels(): ImmutableList<String> = remember {
    HourAxisDayOrder.map { it.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }.toImmutableList()
}

/**
 * Width of the left day-name column shared by the hour charts, measured from the localized short
 * day names so every locale keeps its labels inside the same gutter.
 */
@Composable
internal fun rememberHourAxisGutterPx(textPaint: Paint, spacingPx: Float): Float {
    val dayLabels = rememberHourAxisDayLabels()
    return remember(textPaint, spacingPx, dayLabels) {
        dayLabels.maxOf { textPaint.measureText(it) } + spacingPx * 2
    }
}
