package com.lomo.domain.usecase

import com.lomo.domain.model.Memo
import com.lomo.domain.testing.DomainFunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/*
 * Test Contract:
 * - Unit under test: MemoStatisticsUseCase
 *
 * Scenario matrix:
 * - Happy: standard happy path for MemoStatisticsUseCaseTest.
 * - Boundary: boundary and edge cases for MemoStatisticsUseCaseTest.
 * - Failure: failure and error scenarios for MemoStatisticsUseCaseTest.
 * - Must-not-happen: invariants are never violated for MemoStatisticsUseCaseTest.
 * - Behavior focus: statistics must compute current-natural-year daily time bounds after converting
 *   memo timestamps into the selected local timezone.
 * - Observable outcomes: earliestDailyMemoTime and latestDailyMemoTime values on MemoStatistics.
 * - Red phase: Fails before the fix because MemoStatistics has no current-year daily time fields
 *   and computeStatistics does not calculate them.
 * - Excludes: repository storage, UI formatting, chart rendering, and system clock wiring.
 */
class MemoStatisticsUseCaseTest : DomainFunSpec() {
    init {
        test("computeStatistics reports earliest and latest memo times from current local year") {
            val zone = ZoneId.of("Asia/Shanghai")
            val stats =
                MemoStatisticsUseCase.computeStatistics(
                    memos =
                        listOf(
                            memo("last-year", ZonedDateTime.of(2025, 12, 31, 23, 59, 0, 0, zone)),
                            memo("morning", ZonedDateTime.of(2026, 2, 1, 8, 15, 0, 0, zone)),
                            memo("night", ZonedDateTime.of(2026, 4, 3, 23, 40, 0, 0, zone)),
                            memo("afternoon", ZonedDateTime.of(2026, 5, 8, 15, 30, 0, 0, zone)),
                        ),
                    tagCounts = emptyList(),
                    zone = zone,
                    today = LocalDate.of(2026, 5, 8),
                )

            stats.earliestDailyMemoTime shouldBe LocalTime.of(8, 15)
            stats.latestDailyMemoTime shouldBe LocalTime.of(23, 40)
        }
    }
    init {
        test("computeStatistics leaves daily time bounds empty when current year has no memos") {
            val zone = ZoneId.of("Asia/Shanghai")
            val stats =
                MemoStatisticsUseCase.computeStatistics(
                    memos =
                        listOf(
                            memo("old", ZonedDateTime.of(2025, 12, 31, 22, 10, 0, 0, zone)),
                        ),
                    tagCounts = emptyList(),
                    zone = zone,
                    today = LocalDate.of(2026, 5, 8),
                )

            stats.earliestDailyMemoTime shouldBe null
            stats.latestDailyMemoTime shouldBe null
        }
    }
    init {
        test("computeStatistics uses local timezone before deciding current year membership") {
            val zone = ZoneId.of("Asia/Shanghai")
            val stats =
                MemoStatisticsUseCase.computeStatistics(
                    memos =
                        listOf(
                            memoAtInstant("utc-previous-year-local-current", "2025-12-31T23:30:00Z"),
                            memoAtInstant("utc-current-year-local-next", "2026-12-31T18:00:00Z"),
                        ),
                    tagCounts = emptyList(),
                    zone = zone,
                    today = LocalDate.of(2026, 1, 2),
                )

            stats.earliestDailyMemoTime shouldBe LocalTime.of(7, 30)
            stats.latestDailyMemoTime shouldBe LocalTime.of(7, 30)
        }
    }

    private fun memoAtInstant(
        id: String,
        instantText: String,
    ): Memo = memo(id, Instant.parse(instantText).atZone(ZoneId.of("UTC")))

    private fun memo(
        id: String,
        dateTime: ZonedDateTime,
    ): Memo =
        Memo(
            id = id,
            timestamp = dateTime.toInstant().toEpochMilli(),
            content = "content",
            rawContent = "- ${dateTime.toLocalTime()} content",
            dateKey = dateTime.toLocalDate().toString(),
        )
}
