package com.lomo.app.feature.review

/**
 * Behavior Contract:
 * Capability: Kotest Migration
 * Scenarios: Given standard test execution, when tests run, then assertions hold.
 * Observable outcomes: Green tests
 * TDD proof: Compilation failure on Kotest transition
 * Excludes: none
 * 
 * Test Change Justification:
 * Reason category: Migration
 * Old behavior/assertion being replaced: JUnit4 assertions
 * Why old assertion is no longer correct: Transitioning to Kotest
 * Coverage preserved by: Kotest functional matching
 * Why this is not fitting the test to the implementation: Syntax translation
 */


import com.lomo.app.feature.common.AppConfigUiCoordinator
import com.lomo.app.feature.common.MemoUiCoordinator
import com.lomo.app.feature.common.UiState
import com.lomo.app.feature.main.MemoUiMapper
import com.lomo.app.provider.ImageMapProvider
import com.lomo.app.provider.emptyImageMapProvider
import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.MainDispatcherExtension
import com.lomo.app.testing.fakes.FakeAppConfigRepository
import com.lomo.app.testing.fakes.FakeDailyReviewSessionRepository
import com.lomo.app.testing.fakes.FakeMemoRepository
import com.lomo.domain.model.DailyReviewSession
import com.lomo.domain.model.Memo
import com.lomo.domain.usecase.DailyReviewQueryUseCase
import com.lomo.domain.usecase.DailyReviewSessionUseCase
import com.lomo.domain.usecase.DeleteMemoUseCase
import com.lomo.domain.usecase.ResolveMemoUpdateActionUseCase
import com.lomo.domain.usecase.SaveImageUseCase
import com.lomo.domain.usecase.UpdateMemoContentUseCase
import com.lomo.domain.usecase.ValidateMemoContentUseCase
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.mockk
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class DailyReviewViewModelSessionTest : AppFunSpec() {
    private val testDispatcher = StandardTestDispatcher()

    private val memoRepository = FakeMemoRepository()
    private val appConfigRepository = FakeAppConfigRepository()
    private val imageMapProvider: ImageMapProvider = emptyImageMapProvider()
    
    private val deleteMemoUseCase = DeleteMemoUseCase(memoRepository)
    private val updateMemoContentUseCase = UpdateMemoContentUseCase(
        repository = memoRepository,
        validator = ValidateMemoContentUseCase(),
        resolveMemoUpdateActionUseCase = ResolveMemoUpdateActionUseCase(),
        deleteMemoUseCase = deleteMemoUseCase,
    )
    private val dailyReviewSessionRepository = FakeDailyReviewSessionRepository()
    private val dailyReviewSessionUseCase = DailyReviewSessionUseCase(dailyReviewSessionRepository)
    private val dailyReviewQueryUseCase = DailyReviewQueryUseCase(memoRepository)
    private val saveImageUseCase: SaveImageUseCase = mockk()

    init {
        extension(MainDispatcherExtension(testDispatcher))

        beforeTest {
            clearMocks(saveImageUseCase)
            memoRepository.setActiveMemos(emptyList())
            memoRepository.setDeletedMemos(emptyList())
            memoRepository.resetCallCounts()
            memoRepository.getMemoCountFailure = null

            dailyReviewSessionRepository.session = null
        }

        test("initial load uses the restored same-day seed and page") {
            runTest {
                val memos = listOf(sampleMemo("memo-1"), sampleMemo("memo-2"), sampleMemo("memo-3"), sampleMemo("memo-4"))
                memoRepository.setActiveMemos(memos)
                dailyReviewSessionRepository.session = DailyReviewSession(
                    date = LocalDate.now(),
                    seed = 77L,
                    pageIndex = 3,
                )

                val viewModel = createViewModel()
                advanceUntilIdle()

                viewModel.restoredPageIndex.value shouldBe 3
                (viewModel.uiState.value is UiState.Success) shouldBe true
            }
        }

        test("initial load clamps an out-of-range restored page and persists the correction") {
            runTest {
                val memos = listOf(sampleMemo("memo-1"), sampleMemo("memo-2"))
                memoRepository.setActiveMemos(memos)
                dailyReviewSessionRepository.session = DailyReviewSession(
                    date = LocalDate.now(),
                    seed = 77L,
                    pageIndex = 9,
                )

                val viewModel = createViewModel()
                advanceUntilIdle()

                viewModel.restoredPageIndex.value shouldBe 1
                dailyReviewSessionRepository.session?.pageIndex shouldBe 1
            }
        }

        test("onPageChanged persists the latest page for the active seed") {
            runTest {
                val memos = listOf(sampleMemo("memo-1"), sampleMemo("memo-2"))
                memoRepository.setActiveMemos(memos)
                dailyReviewSessionRepository.session = DailyReviewSession(
                    date = LocalDate.now(),
                    seed = 77L,
                    pageIndex = 0,
                )

                val viewModel = createViewModel()
                advanceUntilIdle()
                
                viewModel.onPageChanged(1)
                advanceUntilIdle()

                viewModel.restoredPageIndex.value shouldBe 1
                dailyReviewSessionRepository.session?.pageIndex shouldBe 1
            }
        }
    }

    private fun createViewModel(): DailyReviewViewModel =
        DailyReviewViewModel(
            memoUiCoordinator = MemoUiCoordinator(memoRepository),
            appConfigStateProvider =
                com.lomo.app.feature.common.AppConfigStateProvider(
                    AppConfigUiCoordinator(appConfigRepository),
                    CoroutineScope(SupervisorJob() + testDispatcher),
                ),
            appConfigUiCoordinator = AppConfigUiCoordinator(appConfigRepository),
            imageMapProvider = imageMapProvider,
            memoUiMapper = MemoUiMapper(testDispatcher),
            deleteMemoUseCase = deleteMemoUseCase,
            updateMemoContentUseCase = updateMemoContentUseCase,
            saveImageUseCase = saveImageUseCase,
            dailyReviewQueryUseCase = dailyReviewQueryUseCase,
            dailyReviewSessionUseCase = dailyReviewSessionUseCase,
        )

    private fun sampleMemo(id: String): Memo =
        Memo(
            id = id,
            timestamp = 1L,
            content = id,
            rawContent = "- 10:00 $id",
            dateKey = "2026_04_16",
        )
}
