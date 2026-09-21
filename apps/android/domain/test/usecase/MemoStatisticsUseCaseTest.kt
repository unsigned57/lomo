package com.lomo.domain.usecase

import com.lomo.domain.model.MemoStatistics
import com.lomo.domain.model.MemoSidebarStatistics
import com.lomo.domain.model.MemoTagCount
import com.lomo.domain.repository.MemoStatisticsRepository
import com.lomo.domain.testing.DomainFunSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: MemoStatisticsUseCase
 * - Owning layer: domain
 * - Priority tier: P0
 * - Capability: sample one date snapshot and pass that zone/as-of pair to the statistics
 *   repository; aggregation itself is owned by the application session.
 *
 * Scenarios:
 * - Given an injected date snapshot, when invoke runs, then the repository is called once with
 *   that zone and as-of date.
 * - Given the repository returns a statistics value, when invoke runs, then that value is returned
 *   unchanged.
 *
 * Observable outcomes:
 * - Repository call arguments and returned MemoStatistics.
 *
 * TDD proof:
 * - Target: ./kotlin test --include-module=domain --include-classes='com.lomo.domain.usecase.MemoStatisticsUseCaseTest'
 * - RED before this cutover because the use case tests asserted Kotlin-side streak/word aggregation
 *   through MemoStatisticsCalculator.
 *
 * Excludes:
 * - Session FFI mapping, heatmap rendering, and calendar timezone classification owned by
 *   lomo-application.
 * Test Change Justification:
 * - Reason category: new coverage for an added repository-facing contract.
 * - Old behavior/assertion being replaced: none; observe() with an explicit
 *   MemoStatisticsDateSnapshot did not exist.
 * - Why old assertion is no longer correct: the use case gained observe() so statistics are
 *   sampled against a snapshot-provided zone/date instead of a repository-internal clock.
 * - Coverage preserved by: existing single-shot statistics scenarios are unchanged.
 * - Why this is not fitting the test to the implementation: assertions pin the observable
 *   (zone, date) pair sent to the repository and the emitted statistics, not internals.
 */
class MemoStatisticsUseCaseTest : DomainFunSpec() {
    init {
        test("invoke passes the sampled zone and as-of date to the repository") {
            runTest {
                val zone = ZoneId.of("Asia/Tokyo")
                val asOfDate = LocalDate.of(2027, 1, 1)
                val expected = MemoStatistics.empty(asOfDate).copy(totalMemos = 4)
                val repository = RecordingMemoStatisticsRepository(result = expected)
                val useCase =
                    MemoStatisticsUseCase(
                        memoStatisticsRepository = repository,
                        dateSnapshotProvider = {
                            MemoStatisticsDateSnapshot(zone = zone, asOfDate = asOfDate)
                        },
                    )

                val stats = useCase()

                stats shouldBe expected
                repository.calls shouldBe listOf(zone to asOfDate)
            }
        }

        test("invoke returns repository statistics without recomputing aggregates") {
            runTest {
                val today = LocalDate.of(2027, 1, 2)
                val expected =
                    MemoStatistics.empty(today).copy(
                        thisYearCount = 9,
                        lastYearCount = 3,
                        currentStreak = 2,
                    )
                val repository = RecordingMemoStatisticsRepository(result = expected)
                val useCase =
                    MemoStatisticsUseCase(
                        memoStatisticsRepository = repository,
                        dateSnapshotProvider = {
                            MemoStatisticsDateSnapshot(zone = ZoneId.of("UTC"), asOfDate = today)
                        },
                    )

                useCase() shouldBe expected
            }
        }

        test("observe samples the date snapshot and emits repository statistics") {
            runTest {
                val zone = ZoneId.of("Asia/Tokyo")
                val asOfDate = LocalDate.of(2027, 1, 3)
                val expected = MemoStatistics.empty(asOfDate).copy(totalMemos = 6)
                val repository = RecordingMemoStatisticsRepository(result = expected)
                val useCase =
                    MemoStatisticsUseCase(
                        memoStatisticsRepository = repository,
                        dateSnapshotProvider = {
                            MemoStatisticsDateSnapshot(zone = zone, asOfDate = asOfDate)
                        },
                    )

                useCase.observe().first() shouldBe expected
                repository.calls shouldBe listOf(zone to asOfDate)
            }
        }
    }

    private class RecordingMemoStatisticsRepository(
        private val result: MemoStatistics,
    ) : MemoStatisticsRepository {
        val calls = mutableListOf<Pair<ZoneId, LocalDate>>()

        override suspend fun getMemoStatistics(
            zone: ZoneId,
            today: LocalDate,
        ): MemoStatistics {
            calls += zone to today
            return result
        }

        override fun observeMemoStatistics(
            zone: ZoneId,
            today: LocalDate,
        ): Flow<MemoStatistics> {
            calls += zone to today
            return flowOf(result)
        }

        override fun getMemoCountFlow(): Flow<Int> = flowOf(0)

        override fun getSidebarStatisticsFlow(): Flow<MemoSidebarStatistics> =
            flowOf(MemoSidebarStatistics(0, emptyMap(), emptyList()))

        override fun getMemoCountByDateFlow(): Flow<Map<String, Int>> = flowOf(emptyMap())

        override fun getTagCountsFlow(): Flow<List<MemoTagCount>> = flowOf(emptyList())

        override fun getActiveDayCount(): Flow<Int> = flowOf(0)
    }
}
