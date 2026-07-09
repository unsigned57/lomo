package com.lomo.domain.usecase

import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoStatisticsCalculator
import com.lomo.domain.model.MemoStatisticsMemoProjection
import com.lomo.domain.testing.DomainFunSpec
import com.lomo.domain.testing.fakes.FakeMemoStore
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: MemoStatisticsUseCase and MemoStatisticsCalculator
 * - Owning layer: domain
 * - Priority tier: P0
 * - Capability: compute memo statistics using one explicit business date
 *   snapshot so the as-of date and timestamp timezone are internally
 *   consistent rather than sampled independently from ambient host time.
 *
 * Scenarios:
 * - Given repository-backed invocation has an injected date snapshot, when a
 *   memo timestamp crosses a local date boundary, then the same snapshot
 *   supplies both the returned as-of date and the zone used for timestamp
 *   classification.
 * - Given repository-backed invocation has an injected date snapshot, when
 *   memo timestamps are classified into current year/week/month/streak
 *   windows, then the snapshot defines those boundaries and the returned
 *   statistics carry the same business date.
 * - Given raw timestamps are converted to local dates, when current-year daily
 *   time bounds are calculated, then the selected local timezone is used before
 *   deciding year membership.
 * - Given there are no current-year memos, when statistics are computed, then
 *   current-year daily time bounds are absent.
 *
 * Observable outcomes:
 * - MemoStatistics asOfDate, period counts, streaks, earliestDailyMemoTime,
 *   and latestDailyMemoTime.
 *
 * TDD proof:
 * - Date/zone snapshot consistency: RED before the fix because
 *   MemoStatisticsUseCase exposes only independent currentDateProvider and
 *   zoneProvider hooks and has no dateSnapshotProvider/MemoStatisticsDateSnapshot
 *   boundary for one sampled date+zone value.
 * - Public invoke time source: RED before the fix because MemoStatisticsUseCase
 *   has no injectable currentDateProvider or zoneProvider and reads host time.
 * - Snapshot date: RED before the fix because MemoStatistics has no asOfDate
 *   field for app renderers to reuse instead of host LocalDate.now().
 * - Repository boundary: RED before the fix because MemoStatisticsUseCase called
 *   MemoListQueryRepository.getAllMemosList().first() and looped domain memos.
 * - Current-year time bounds: retained calculator tests fail if timestamp-to-local-year
 *   classification regresses.
 *
 * Excludes:
 * - Repository persistence, UI formatting, chart rendering, and app-level
 *   statistics screen date filtering.
 */
class MemoStatisticsUseCaseTest : DomainFunSpec() {
    init {
        test("invoke derives as-of date and timestamp zone from one date snapshot") {
            runTest {
                val zone = ZoneId.of("Asia/Tokyo")
                val asOfDate = LocalDate.of(2027, 1, 1)
                var snapshotCalls = 0
                val repository =
                    FakeMemoStore(
                        initialMemos =
                            listOf(
                                memoAtInstant("tokyo-new-year", "2026-12-31T15:30:00Z"),
                            ),
                    )
                val useCase =
                    MemoStatisticsUseCase(
                        memoStatisticsRepository = com.lomo.domain.testing.fakes.FakeMemoStatisticsRepository(repository),
                        dateSnapshotProvider = {
                            snapshotCalls++
                            MemoStatisticsDateSnapshot(zone = zone, asOfDate = asOfDate)
                        },
                    )

                val stats = useCase()

                snapshotCalls shouldBe 1
                stats.asOfDate shouldBe asOfDate
                stats.memoCountByDate shouldBe mapOf(asOfDate to 1)
                stats.thisYearCount shouldBe 1
                stats.lastYearCount shouldBe 0
            }
        }

        test("invoke uses injected date and zone when calculating period counts and streaks") {
            runTest {
                val zone = ZoneId.of("UTC")
                val today = LocalDate.of(2027, 1, 2)
                val repository =
                    FakeMemoStore(
                        initialMemos =
                            listOf(
                                memo("current-year", ZonedDateTime.of(2027, 1, 1, 9, 0, 0, 0, zone)),
                                memo("previous-year", ZonedDateTime.of(2026, 12, 31, 9, 0, 0, 0, zone)),
                            ),
                    )
                val useCase =
                    MemoStatisticsUseCase(
                        memoStatisticsRepository = com.lomo.domain.testing.fakes.FakeMemoStatisticsRepository(repository),
                        dateSnapshotProvider = {
                            MemoStatisticsDateSnapshot(zone = zone, asOfDate = today)
                        },
                    )

                val stats = useCase()

                stats.asOfDate shouldBe today
                stats.thisYearCount shouldBe 1
                stats.lastYearCount shouldBe 1
                stats.currentStreak shouldBe 2
            }
        }

        test("computeStatistics reports earliest and latest memo times from current local year") {
            val zone = ZoneId.of("Asia/Shanghai")
            val stats =
                MemoStatisticsCalculator.compute(
                    memos =
                        listOf(
                            projection(ZonedDateTime.of(2025, 12, 31, 23, 59, 0, 0, zone)),
                            projection(ZonedDateTime.of(2026, 2, 1, 8, 15, 0, 0, zone)),
                            projection(ZonedDateTime.of(2026, 4, 3, 23, 40, 0, 0, zone)),
                            projection(ZonedDateTime.of(2026, 5, 8, 15, 30, 0, 0, zone)),
                        ),
                    tagCounts = emptyList(),
                    zone = zone,
                    today = LocalDate.of(2026, 5, 8),
                )

            stats.earliestDailyMemoTime shouldBe LocalTime.of(8, 15)
            stats.latestDailyMemoTime shouldBe LocalTime.of(23, 40)
        }

        test("computeStatistics leaves daily time bounds empty when current year has no memos") {
            val zone = ZoneId.of("Asia/Shanghai")
            val stats =
                MemoStatisticsCalculator.compute(
                    memos =
                        listOf(
                            projection(ZonedDateTime.of(2025, 12, 31, 22, 10, 0, 0, zone)),
                        ),
                    tagCounts = emptyList(),
                    zone = zone,
                    today = LocalDate.of(2026, 5, 8),
                )

            stats.earliestDailyMemoTime shouldBe null
            stats.latestDailyMemoTime shouldBe null
        }

        test("computeStatistics uses local timezone before deciding current year membership") {
            val zone = ZoneId.of("Asia/Shanghai")
            val stats =
                MemoStatisticsCalculator.compute(
                    memos =
                        listOf(
                            projectionAtInstant("2025-12-31T23:30:00Z"),
                            projectionAtInstant("2026-12-31T18:00:00Z"),
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

    private fun projectionAtInstant(instantText: String): MemoStatisticsMemoProjection =
        projection(Instant.parse(instantText).atZone(ZoneId.of("UTC")))

    private fun projection(dateTime: ZonedDateTime): MemoStatisticsMemoProjection =
        MemoStatisticsCalculator.projectMemo(
            timestamp = dateTime.toInstant().toEpochMilli(),
            content = "content",
        )

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
