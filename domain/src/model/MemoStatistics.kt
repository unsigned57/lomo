package com.lomo.domain.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields

data class MemoStatistics(
    val asOfDate: LocalDate,
    val totalMemos: Int,
    val totalWords: Int,
    val totalCharacters: Int,
    val averageWordsPerMemo: Double,
    val totalTags: Int,
    val activeDays: Int,
    val currentStreak: Int,
    val longestStreak: Int,
    val memoCountByDate: Map<LocalDate, Int>,
    val hourlyDistribution: Map<Int, Int>,
    val weeklyHourDistribution: Map<DayOfWeek, Map<Int, Int>>,
    val earliestDailyMemoTime: LocalTime?,
    val latestDailyMemoTime: LocalTime?,
    val thisWeekCount: Int,
    val lastWeekCount: Int,
    val thisMonthCount: Int,
    val lastMonthCount: Int,
    val thisYearCount: Int,
    val lastYearCount: Int,
    val tagCounts: List<MemoTagCount>,
)

data class MemoStatisticsMemoProjection(
    val timestamp: Long,
    val wordCount: Int,
    val characterCount: Int,
)

object MemoStatisticsCalculator {
    fun projectMemo(
        timestamp: Long,
        content: String,
    ): MemoStatisticsMemoProjection =
        MemoStatisticsMemoProjection(
            timestamp = timestamp,
            wordCount = countWords(content),
            characterCount = content.length,
        )

    fun compute(
        memos: List<MemoStatisticsMemoProjection>,
        tagCounts: List<MemoTagCount>,
        zone: ZoneId,
        today: LocalDate,
    ): MemoStatistics {
        var totalWords = 0
        var totalCharacters = 0
        val memoCountByDate = mutableMapOf<LocalDate, Int>()
        val hourlyDistribution = mutableMapOf<Int, Int>()
        val weeklyHourDistribution = mutableMapOf<DayOfWeek, MutableMap<Int, Int>>()

        val weekStart = today.with(TemporalAdjusters.previousOrSame(WeekFields.ISO.firstDayOfWeek))
        val lastWeekStart = weekStart.minusWeeks(1)
        val monthStart = today.withDayOfMonth(1)
        val lastMonthStart = monthStart.minusMonths(1)
        val yearStart = today.withDayOfYear(1)
        val nextYearStart = yearStart.plusYears(1)
        val lastYearStart = yearStart.minusYears(1)

        var thisWeekCount = 0
        var lastWeekCount = 0
        var thisMonthCount = 0
        var lastMonthCount = 0
        var thisYearCount = 0
        var lastYearCount = 0
        var dailyMemoTimeBounds = DailyMemoTimeBounds()

        for (memo in memos) {
            totalWords += memo.wordCount
            totalCharacters += memo.characterCount

            val dateTime = Instant.ofEpochMilli(memo.timestamp).atZone(zone)
            val date = dateTime.toLocalDate()
            val hour = dateTime.hour
            val localTime = dateTime.toLocalTime()
            val dayOfWeek = dateTime.dayOfWeek

            memoCountByDate[date] = (memoCountByDate[date] ?: 0) + 1
            hourlyDistribution[hour] = (hourlyDistribution[hour] ?: 0) + 1
            weeklyHourDistribution
                .getOrPut(dayOfWeek) { mutableMapOf() }
                .let { it[hour] = (it[hour] ?: 0) + 1 }

            when {
                !date.isBefore(weekStart) -> thisWeekCount++
                !date.isBefore(lastWeekStart) -> lastWeekCount++
            }
            when {
                !date.isBefore(monthStart) -> thisMonthCount++
                !date.isBefore(lastMonthStart) -> lastMonthCount++
            }
            when {
                !date.isBefore(yearStart) -> thisYearCount++
                !date.isBefore(lastYearStart) -> lastYearCount++
            }
            if (!date.isBefore(yearStart) && date.isBefore(nextYearStart)) {
                dailyMemoTimeBounds = dailyMemoTimeBounds.including(localTime)
            }
        }

        val sortedDates = memoCountByDate.keys.sorted()
        val (currentStreak, longestStreak) = computeStreaks(sortedDates, today)

        return MemoStatistics(
            asOfDate = today,
            totalMemos = memos.size,
            totalWords = totalWords,
            totalCharacters = totalCharacters,
            averageWordsPerMemo = if (memos.isEmpty()) 0.0 else totalWords.toDouble() / memos.size,
            totalTags = tagCounts.size,
            activeDays = memoCountByDate.size,
            currentStreak = currentStreak,
            longestStreak = longestStreak,
            memoCountByDate = memoCountByDate,
            hourlyDistribution = hourlyDistribution,
            weeklyHourDistribution = weeklyHourDistribution,
            earliestDailyMemoTime = dailyMemoTimeBounds.earliest,
            latestDailyMemoTime = dailyMemoTimeBounds.latest,
            thisWeekCount = thisWeekCount,
            lastWeekCount = lastWeekCount,
            thisMonthCount = thisMonthCount,
            lastMonthCount = lastMonthCount,
            thisYearCount = thisYearCount,
            lastYearCount = lastYearCount,
            tagCounts = tagCounts,
        )
    }

    private data class DailyMemoTimeBounds(
        val earliest: LocalTime? = null,
        val latest: LocalTime? = null,
    ) {
        fun including(time: LocalTime): DailyMemoTimeBounds =
            DailyMemoTimeBounds(
                earliest = earliest?.takeIf { current -> !time.isBefore(current) } ?: time,
                latest = latest?.takeIf { current -> !time.isAfter(current) } ?: time,
            )
    }

    private fun countWords(text: String): Int {
        if (text.isBlank()) return 0
        var count = 0
        var inWord = false
        for (ch in text) {
            if (ch.isWhitespace()) {
                inWord = false
            } else if (!inWord) {
                inWord = true
                count++
            }
        }
        return count
    }

    private fun computeStreaks(
        sortedDates: List<LocalDate>,
        today: LocalDate,
    ): Pair<Int, Int> {
        if (sortedDates.isEmpty()) return 0 to 0

        var longestStreak = 1
        var currentRun = 1
        var currentStreak = 0

        for (i in 1 until sortedDates.size) {
            if (sortedDates[i].minusDays(1) == sortedDates[i - 1]) {
                currentRun++
            } else {
                if (currentRun > longestStreak) longestStreak = currentRun
                currentRun = 1
            }
        }
        if (currentRun > longestStreak) longestStreak = currentRun

        val lastDate = sortedDates.last()
        if (lastDate == today || lastDate == today.minusDays(1)) {
            currentStreak = 1
            var idx = sortedDates.lastIndex
            while (idx > 0 && sortedDates[idx].minusDays(1) == sortedDates[idx - 1]) {
                currentStreak++
                idx--
            }
        }

        return currentStreak to longestStreak
    }
}
