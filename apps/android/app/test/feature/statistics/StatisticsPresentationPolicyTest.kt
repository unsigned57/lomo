/*
 * Behavior Contract:
 * - Unit under test: statistics presentation date policy.
 * - Owning layer: app
 * - Priority tier: P1
 * - Capability: render statistics activity and period windows from the domain
 *   snapshot date rather than the host UI clock.
 *
 * Scenarios:
 * - Given statistics were computed for a business date, when presentation dates
 *   are resolved, then today equals the snapshot date and week period days are
 *   derived from that same date.
 *
 * Observable outcomes:
 * - resolved today date and weekDays value.
 *
 * TDD proof:
 * - RED before the fix because statistics presentation date resolution is
 *   embedded in StatisticsContent as `LocalDate.now()` and has no snapshot-date
 *   policy function.
 *
 * Excludes:
 * - Compose rendering, chart drawing, repository calculations, and PNG sharing.
 */
package com.lomo.app.feature.statistics

import com.lomo.app.testing.AppFunSpec
import com.lomo.domain.model.MemoStatistics
import io.kotest.matchers.shouldBe
import java.time.LocalDate

class StatisticsPresentationPolicyTest : AppFunSpec() {
    init {
        test("given statistics snapshot date when resolving presentation dates then snapshot date drives reports") {
            val stats = sampleStatistics(asOfDate = LocalDate.of(2026, 5, 8))

            val dates = resolveStatisticsSnapshotPresentationDates(stats)

            dates.today shouldBe LocalDate.of(2026, 5, 8)
            dates.weekDays shouldBe 6
        }
    }
}

private fun sampleStatistics(asOfDate: LocalDate): MemoStatistics =
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
