package com.lomo.app.feature.statistics

import com.lomo.app.feature.common.UiState
import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.MainDispatcherExtension
import com.lomo.domain.model.MemoStatistics
import com.lomo.domain.usecase.MemoStatisticsUseCase
import com.lomo.domain.usecase.PersistShareImageUseCase
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: StatisticsViewModel
 * - Owning layer: app
 * - Priority tier: P1
 * - Capability: statistics screenshot sharing persists rendered PNG data through the streaming share-image contract.
 *
 * Scenarios:
 * - Given a screenshot source, when sharing statistics succeeds, then the source output is passed
 *   to share-image persistence with the stats prefix and a share event is emitted.
 * - Given the streaming persistence fails before consuming the source, when sharing statistics,
 *   then the source is still closed and a user-visible error is exposed.
 * - Given statistics are requested, when loading completes, then memo statistics state is exposed.
 *
 * Observable outcomes:
 * - UiState, share image event path/id, error message state, persistence prefix, streamed bytes,
 *   writer calls, and source close calls.
 *
 * TDD proof:
 * - RED: the streaming share scenario failed to compile before the first fix because
 *   StatisticsViewModel.shareStatisticsImage accepted a ByteArray instead of a writer.
 * - RED: the source-close scenario fails before this follow-up because shareStatisticsImage
 *   accepts a plain writer with no closeable source ownership.
 *
 * Excludes:
 * - Compose screenshot capture, Android FileProvider URI creation, share sheet dispatch, and
 *   MemoStatisticsUseCase calculations.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatisticsViewModelTest : AppFunSpec() {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var memoStatisticsUseCase: MemoStatisticsUseCase
    private lateinit var persistShareImageUseCase: PersistShareImageUseCase

    init {
        extension(MainDispatcherExtension(testDispatcher))

        beforeTest {
            memoStatisticsUseCase = mockk()
            persistShareImageUseCase = mockk()
            coEvery { memoStatisticsUseCase() } returns sampleStatistics()
        }

        test("given screenshot source when share succeeds then streamed bytes are persisted with stats prefix") {
            runTest {
                val expectedBytes = byteArrayOf(1, 2, 3)
                val source = TestStatisticsPngSource(expectedBytes)
                var persistedBytes = byteArrayOf()
                coEvery {
                    persistShareImageUseCase(fileNamePrefix = "stats_share", writer = any())
                } coAnswers {
                    val writer = secondArg<suspend (OutputStream) -> Unit>()
                    val output = ByteArrayOutputStream()
                    writer(output)
                    persistedBytes = output.toByteArray()
                    "/tmp/stats_share_1.png"
                }
                val viewModel = createViewModel()
                advanceUntilIdle()

                viewModel.shareStatisticsImage(source)
                advanceUntilIdle()

                (viewModel.shareImageEvent.value) shouldBe (StatisticsShareImageEvent(id = 1L, filePath = "/tmp/stats_share_1.png"))
                (viewModel.shareErrorMessage.value) shouldBe null
                persistedBytes.toList() shouldBe expectedBytes.toList()
                source.writeCallCount shouldBe 1
                source.closeCallCount shouldBe 1
                coVerify(exactly = 1) {
                    persistShareImageUseCase(fileNamePrefix = "stats_share", writer = any())
                }

                viewModel.consumeShareImageEvent(1L)

                (viewModel.shareImageEvent.value) shouldBe null
            }
        }

        test("given persistence fails before consuming source when sharing statistics then source is closed and error is exposed") {
            runTest {
                val source = TestStatisticsPngSource(byteArrayOf(9, 8, 7))
                coEvery {
                    persistShareImageUseCase(fileNamePrefix = "stats_share", writer = any())
                } throws IllegalStateException("disk full")
                val viewModel = createViewModel()
                advanceUntilIdle()

                viewModel.shareStatisticsImage(source)
                advanceUntilIdle()

                (viewModel.shareErrorMessage.value) shouldBe ("Failed to share statistics: disk full")
                (viewModel.shareImageEvent.value) shouldBe null
                source.writeCallCount shouldBe 0
                source.closeCallCount shouldBe 1

                viewModel.clearShareError()

                (viewModel.shareErrorMessage.value) shouldBe null
            }
        }

        test("loadStatistics still exposes memo statistics state") {
            runTest {
                val viewModel = createViewModel()
                advanceUntilIdle()
                coVerify(exactly = 0) { memoStatisticsUseCase() }

                viewModel.ensureLoaded()
                advanceUntilIdle()

                val state = viewModel.uiState.value.shouldBeInstanceOf<UiState.Success<MemoStatistics>>()
                (state.data.earliestDailyMemoTime) shouldBe (LocalTime.of(8, 15))
                coVerify(exactly = 1) { memoStatisticsUseCase() }
            }
        }
    }

    private fun createViewModel(): StatisticsViewModel =
        StatisticsViewModel(
            memoStatisticsUseCase = memoStatisticsUseCase,
            persistShareImageUseCase = persistShareImageUseCase,
        )

    private class TestStatisticsPngSource(
        private val bytes: ByteArray,
    ) : StatisticsPngSource {
        var writeCallCount = 0
            private set
        var closeCallCount = 0
            private set

        override suspend fun writeTo(output: OutputStream) {
            writeCallCount += 1
            output.write(bytes)
        }

        override fun close() {
            closeCallCount += 1
        }
    }

    private fun sampleStatistics(): MemoStatistics =
        MemoStatistics(
            asOfDate = LocalDate.of(2026, 5, 8),
            totalMemos = 2,
            totalWords = 4,
            totalCharacters = 20,
            averageWordsPerMemo = 2.0,
            totalTags = 0,
            activeDays = 1,
            currentStreak = 1,
            longestStreak = 1,
            memoCountByDate = emptyMap(),
            hourlyDistribution = emptyMap(),
            weeklyHourDistribution = emptyMap(),
            earliestDailyMemoTime = LocalTime.of(8, 15),
            latestDailyMemoTime = LocalTime.of(23, 40),
            thisWeekCount = 1,
            lastWeekCount = 0,
            thisMonthCount = 1,
            lastMonthCount = 0,
            thisYearCount = 2,
            lastYearCount = 0,
            tagCounts = emptyList(),
        )
}
