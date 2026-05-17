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
import java.time.LocalTime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/*
 * Test Contract:
 * - Unit under test: StatisticsViewModel
 *
 * Scenario matrix:
 * - Happy: standard happy path for StatisticsViewModelTest.
 * - Boundary: boundary and edge cases for StatisticsViewModelTest.
 * - Failure: failure and error scenarios for StatisticsViewModelTest.
 * - Must-not-happen: invariants are never violated for StatisticsViewModelTest.
 * - Behavior focus: statistics loading and screenshot-share persistence must emit user-visible
 *   state without leaking Android file sharing into the domain layer.
 * - Observable outcomes: UiState, share image event path/id, error message state, and share-image
 *   persistence prefix.
 * - Red phase: Fails before the fix because StatisticsViewModel cannot persist stats screenshots,
 *   emits no share event, and has no share failure state.
 * - Excludes: Compose screenshot capture, Android FileProvider URI creation, share sheet dispatch,
 *   and MemoStatisticsUseCase calculations.
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

        test("shareStatisticsImage persists png bytes with stats prefix and emits event") {
            runTest {
                val pngBytes = byteArrayOf(1, 2, 3)
                coEvery {
                    persistShareImageUseCase(pngBytes = pngBytes, fileNamePrefix = "stats_share")
                } returns "/tmp/stats_share_1.png"
                val viewModel = createViewModel()
                advanceUntilIdle()

                viewModel.shareStatisticsImage(pngBytes)
                advanceUntilIdle()

                (viewModel.shareImageEvent.value) shouldBe (StatisticsShareImageEvent(id = 1L, filePath = "/tmp/stats_share_1.png"))
                (viewModel.shareErrorMessage.value) shouldBe null
                coVerify(exactly = 1) {
                    persistShareImageUseCase(pngBytes = pngBytes, fileNamePrefix = "stats_share")
                }

                viewModel.consumeShareImageEvent(1L)

                (viewModel.shareImageEvent.value) shouldBe null
            }
        }

        test("shareStatisticsImage exposes share error when persistence fails") {
            runTest {
                val pngBytes = byteArrayOf(9, 8, 7)
                coEvery {
                    persistShareImageUseCase(pngBytes = pngBytes, fileNamePrefix = "stats_share")
                } throws IllegalStateException("disk full")
                val viewModel = createViewModel()
                advanceUntilIdle()

                viewModel.shareStatisticsImage(pngBytes)
                advanceUntilIdle()

                (viewModel.shareErrorMessage.value) shouldBe ("Failed to share statistics: disk full")
                (viewModel.shareImageEvent.value) shouldBe null

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

    private fun sampleStatistics(): MemoStatistics =
        MemoStatistics(
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
