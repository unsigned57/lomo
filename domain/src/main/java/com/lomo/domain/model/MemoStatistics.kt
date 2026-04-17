package com.lomo.domain.model

import java.time.DayOfWeek
import java.time.LocalDate

data class MemoStatistics(
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
    val thisWeekCount: Int,
    val lastWeekCount: Int,
    val thisMonthCount: Int,
    val lastMonthCount: Int,
    val thisYearCount: Int,
    val lastYearCount: Int,
    val tagCounts: List<MemoTagCount>,
)
