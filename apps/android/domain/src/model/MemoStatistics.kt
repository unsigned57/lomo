package com.lomo.domain.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

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
) {
    companion object {
        fun empty(asOfDate: LocalDate): MemoStatistics =
            MemoStatistics(
                asOfDate = asOfDate,
                totalMemos = 0,
                totalWords = 0,
                totalCharacters = 0,
                averageWordsPerMemo = 0.0,
                totalTags = 0,
                activeDays = 0,
                currentStreak = 0,
                longestStreak = 0,
                memoCountByDate = emptyMap(),
                hourlyDistribution = emptyMap(),
                weeklyHourDistribution = emptyMap(),
                earliestDailyMemoTime = null,
                latestDailyMemoTime = null,
                thisWeekCount = 0,
                lastWeekCount = 0,
                thisMonthCount = 0,
                lastMonthCount = 0,
                thisYearCount = 0,
                lastYearCount = 0,
                tagCounts = emptyList(),
            )
    }
}
