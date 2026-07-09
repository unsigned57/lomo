package com.lomo.data.repository

import com.lomo.data.local.dao.DateCountRow
import com.lomo.data.local.dao.MemoStatisticsProjectionRow
import com.lomo.data.local.dao.TagCountRow
import com.lomo.data.testing.DataFunSpec
import com.lomo.data.testing.fakes.FakeMemoSearchDao
import com.lomo.domain.model.MemoTagCount
import com.lomo.domain.usecase.FakeDispatcherProvider
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/*
 * Behavior Contract:
 * - Unit under test: MemoStatisticsRepositoryImpl
 * - Owning layer: data/repository.
 * - Priority tier: P1.
 * - Capability: expose memo statistics through the statistics repository boundary instead of the
 *   search repository boundary.
 *
 * Scenarios:
 * - Given persisted statistics projection rows and tag counts, when full statistics are requested,
 *   then the repository computes from data-owned word/character metrics without selecting content.
 * - Given date-count rows, when collected, then they are mapped to a date-keyed domain map.
 * - Given tag-count rows, when collected, then they are mapped to domain tag-count values.
 * - Given count/timestamp/day flows, when collected, then DAO outputs pass through unchanged.
 *
 * Observable outcomes:
 * - Full statistics values plus flow emissions for date counts, tag counts, total count,
 *   timestamps, and active day count.
 *
 * TDD proof:
 * - Fails before the boundary fix because MemoSearchRepositoryImpl still owned statistics methods
 *   while the domain contract moved them to MemoStatisticsRepository.
 *
 * Excludes:
 * - Room SQL execution, search query behavior, and UI statistics presentation.
 */
class MemoStatisticsRepositoryImplTest : DataFunSpec() {
    init {
        test("getMemoStatistics computes from dao projection rows and tag counts") {
            runTest {
                val zone = ZoneId.of("UTC")
                memoStatisticsDao.memoStatisticsProjectionResult =
                    listOf(
                        MemoStatisticsProjectionRow(
                            timestamp = LocalDate.of(2026, 5, 24).atTime(8, 15).atZone(zone).toInstant().toEpochMilli(),
                            statisticsWordCount = 2,
                            statisticsCharacterCount = 10,
                        ),
                        MemoStatisticsProjectionRow(
                            timestamp = LocalDate.of(2026, 5, 25).atTime(20, 30).atZone(zone).toInstant().toEpochMilli(),
                            statisticsWordCount = 1,
                            statisticsCharacterCount = 5,
                        ),
                    )
                memoStatisticsDao.tagCountsResult =
                    listOf(
                        TagCountRow(name = "work", count = 2),
                        TagCountRow(name = "life", count = 1),
                    )

                val stats = repository.getMemoStatistics(zone = zone, today = LocalDate.of(2026, 5, 25))

                stats.totalMemos shouldBe 2
                stats.totalWords shouldBe 3
                stats.totalCharacters shouldBe 15
                stats.memoCountByDate shouldBe
                    mapOf(
                        LocalDate.of(2026, 5, 24) to 1,
                        LocalDate.of(2026, 5, 25) to 1,
                    )
                stats.currentStreak shouldBe 2
                stats.earliestDailyMemoTime shouldBe LocalTime.of(8, 15)
                stats.latestDailyMemoTime shouldBe LocalTime.of(20, 30)
                stats.tagCounts shouldBe
                    listOf(
                        MemoTagCount(name = "work", count = 2),
                        MemoTagCount(name = "life", count = 1),
                    )
            }
        }

        test("getMemoCountByDateFlow maps rows to date keyed map") {
            runTest {
                memoStatisticsDao.memoCountByDateFlowResult =
                    flowOf(
                        listOf(
                            DateCountRow(date = "2026_03_27", count = 2),
                            DateCountRow(date = "2026_03_28", count = 1),
                        ),
                    )

                val result = repository.getMemoCountByDateFlow().first()

                result shouldBe mapOf("2026_03_27" to 2, "2026_03_28" to 1)
            }
        }

        test("getTagCountsFlow maps dao rows to domain tag counts") {
            runTest {
                memoStatisticsDao.tagCountsFlowResult =
                    flowOf(
                        listOf(
                            TagCountRow(name = "work", count = 3),
                            TagCountRow(name = "life", count = 1),
                        ),
                    )

                val result = repository.getTagCountsFlow().first()

                result shouldBe
                    listOf(
                        MemoTagCount(name = "work", count = 3),
                        MemoTagCount(name = "life", count = 1),
                    )
            }
        }

        test("count timestamps and active day flows pass through dao outputs") {
            runTest {
                memoStatisticsDao.memoCountResult = flowOf(7)
                memoStatisticsDao.allTimestampsResult = flowOf(listOf(30L, 20L, 10L))
                memoStatisticsDao.activeDayCountResult = flowOf(2)

                repository.getMemoCountFlow().first() shouldBe 7
                repository.getMemoTimestampsFlow().first() shouldBe listOf(30L, 20L, 10L)
                repository.getActiveDayCount().first() shouldBe 2
            }
        }
    }

    private val memoStatisticsDao = FakeMemoSearchDao()
    private val repository = MemoStatisticsRepositoryImpl(memoStatisticsDao, FakeDispatcherProvider())
}
