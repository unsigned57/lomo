package com.lomo.app.feature.statistics

import com.lomo.domain.model.MemoStatistics
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private const val NUMBER_FORMAT_MILLION_THRESHOLD = 1_000_000
private const val NUMBER_FORMAT_TEN_THOUSAND_THRESHOLD = 10_000
private const val NUMBER_FORMAT_THOUSAND_THRESHOLD = 1_000
private const val NUMBER_FORMAT_THOUSAND_DIVISOR = 1_000.0
private const val NUMBER_FORMAT_MILLION_DIVISOR = 1_000_000.0
private const val STATISTICS_TIME_DISTRIBUTION_TWO_COLUMN_MIN_WIDTH_DP = 720
private val STATISTICS_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm")

internal enum class StatisticsTimeDistributionLayout {
    Stacked,
    TwoColumn,
}

internal fun resolveStatisticsTimeDistributionLayout(widthDp: Int): StatisticsTimeDistributionLayout =
    if (widthDp >= STATISTICS_TIME_DISTRIBUTION_TWO_COLUMN_MIN_WIDTH_DP) {
        StatisticsTimeDistributionLayout.TwoColumn
    } else {
        StatisticsTimeDistributionLayout.Stacked
    }

internal data class StatisticsSnapshotPresentationDates(
    val today: LocalDate,
    val weekDays: Int,
)

internal fun resolveStatisticsSnapshotPresentationDates(stats: MemoStatistics): StatisticsSnapshotPresentationDates {
    val today = stats.asOfDate
    val weekDays =
        ChronoUnit.DAYS
            .between(today.minusWeeks(1).plusDays(1), today)
            .toInt()
            .coerceAtLeast(1)
    return StatisticsSnapshotPresentationDates(today = today, weekDays = weekDays)
}

internal fun formatStatisticsNumber(n: Int): String =
    when {
        n >= NUMBER_FORMAT_MILLION_THRESHOLD -> "%.1fM".format(n / NUMBER_FORMAT_MILLION_DIVISOR)
        n >= NUMBER_FORMAT_TEN_THOUSAND_THRESHOLD -> "%.1fK".format(n / NUMBER_FORMAT_THOUSAND_DIVISOR)
        n >= NUMBER_FORMAT_THOUSAND_THRESHOLD -> "%,d".format(n)
        else -> n.toString()
    }

internal fun formatStatisticsDailyMemoTime(time: LocalTime?): String =
    time?.format(STATISTICS_TIME_FORMATTER) ?: "--:--"
