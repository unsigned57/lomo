package com.lomo.ui.component.stats

import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

private const val HEATMAP_MONTH_LABEL_TRAILING_WEEKS = 2
private const val HEATMAP_MIN_LABEL_WEEKS = 3

internal fun calculateWeeksToCoverEarliestDate(
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

internal fun buildMonthLabels(
    startDay: LocalDate,
    totalWeeks: Int,
): List<MonthLabel> {
    var currentMonth = -1
    val labels = mutableListOf<MonthLabel>()
    for (week in 0 until totalWeeks) {
        val dateOfWeekStart = startDay.plusWeeks(week.toLong())
        val month = dateOfWeekStart.monthValue
        if (month != currentMonth) {
            if (shouldShowHeatmapMonthLabel(week, totalWeeks)) {
                labels +=
                    MonthLabel(
                        week = week,
                        text = formatHeatmapMonthLabel(dateOfWeekStart),
                        year = dateOfWeekStart.year,
                    )
            }
            currentMonth = month
        }
    }
    return labels
}

private fun shouldShowHeatmapMonthLabel(
    week: Int,
    totalWeeks: Int,
): Boolean =
    week < totalWeeks - HEATMAP_MONTH_LABEL_TRAILING_WEEKS ||
        totalWeeks < HEATMAP_MIN_LABEL_WEEKS

internal fun buildHeatmapCells(
    startDay: LocalDate,
    today: LocalDate,
    totalWeeks: Int,
    memoCountByDate: Map<LocalDate, Int>,
): List<HeatmapCell> {
    val cells = ArrayList<HeatmapCell>(totalWeeks * DAYS_PER_WEEK)
    for (week in 0 until totalWeeks) {
        for (day in 0 until DAYS_PER_WEEK) {
            val date = startDay.plusWeeks(week.toLong()).plusDays(day.toLong())
            if (!date.isAfter(today)) {
                cells +=
                    HeatmapCell(
                        week = week,
                        day = day,
                        date = date,
                        count = memoCountByDate[date] ?: 0,
                    )
            }
        }
    }
    return cells
}

private fun formatHeatmapMonthLabel(
    date: LocalDate,
): String {
    return date.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
}
