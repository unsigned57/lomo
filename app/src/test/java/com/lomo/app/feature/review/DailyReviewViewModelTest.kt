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
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.usecase.DailyReviewQueryUseCase
import com.lomo.domain.usecase.DailyReviewSessionUseCase
import com.lomo.domain.usecase.DeleteMemoUseCase
import com.lomo.domain.usecase.ResolveMemoUpdateActionUseCase
import com.lomo.domain.usecase.SaveImageResult
import com.lomo.domain.usecase.SaveImageUseCase
import com.lomo.domain.usecase.UpdateMemoContentUseCase
import com.lomo.domain.usecase.ValidateMemoContentUseCase
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class DailyReviewViewModelTest : AppFunSpec() {
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
            memoRepository.deleteMemoFailure = null
            memoRepository.updateMemoFailure = null

            dailyReviewSessionRepository.session = DailyReviewSession(
                date = LocalDate.of(2026, 4, 16),
                seed = 1L,
                pageIndex = 0,
            )
        }

        test("initial load with empty result publishes success with empty list") {
            runTest {
                val viewModel = createViewModel()
                advanceUntilIdle()

                val state = viewModel.uiState.value
                (state is UiState.Success) shouldBe true
                (state as UiState.Success).data shouldBe emptyList<com.lomo.app.feature.main.MemoUiModel>()
            }
        }

        test("initial load failure publishes ui error") {
            runTest {
                memoRepository.getMemoCountFailure = IllegalStateException("query failed")

                val viewModel = createViewModel()
                advanceUntilIdle()

                val state = viewModel.uiState.value
                (state is UiState.Error) shouldBe true
                state as UiState.Error
                state.message shouldBe "Failed to load daily review"
                (state.throwable is IllegalStateException) shouldBe true
            }
        }

        test("updateMemo failure maps to user-facing error") {
            runTest {
                val memo = sampleMemo(id = "memo-update")
                memoRepository.setActiveMemos(listOf(memo))
                memoRepository.updateMemoFailure = IllegalStateException("update failed")

                val viewModel = createViewModel()
                advanceUntilIdle()

                viewModel.updateMemo(memo, "updated")
                advanceUntilIdle()

                viewModel.errorMessage.value shouldBe "Failed to update memo: update failed"
            }
        }

        test("updateMemo success keeps current list without triggering full reload") {
            runTest {
                val memo = sampleMemo(id = "memo-update-success", content = "before")
                val keep = sampleMemo(id = "memo-keep", content = "stable")
                memoRepository.setActiveMemos(listOf(memo, keep))
                
                val viewModel = createViewModel()
                advanceUntilIdle()

                viewModel.updateMemo(memo, "updated")
                advanceUntilIdle()

                val reloadedState = viewModel.uiState.value as UiState.Success
                reloadedState.data.map { it.memo.id } shouldBe listOf(memo.id, keep.id)
                reloadedState.data.find { it.memo.id == memo.id }?.memo?.content shouldBe "updated"
                viewModel.errorMessage.value shouldBe null
                memoRepository.updateMemoCallCount shouldBe 1
            }
        }

        test("deleteMemo failure maps to user-facing error") {
            runTest {
                val memo = sampleMemo(id = "memo-delete")
                memoRepository.setActiveMemos(listOf(memo))
                memoRepository.deleteMemoFailure = IllegalStateException("delete failed")

                val viewModel = createViewModel()
                advanceUntilIdle()

                viewModel.deleteMemo(memo)
                advanceUntilIdle()

                viewModel.errorMessage.value shouldBe "Failed to delete memo: delete failed"
            }
        }

        test("deleteMemo success removes memo in place without full reload") {
            runTest {
                val memo = sampleMemo(id = "memo-delete-success")
                memoRepository.setActiveMemos(listOf(memo, sampleMemo("memo-keep")))
                
                val viewModel = createViewModel()
                advanceUntilIdle()

                viewModel.deleteMemo(memo)
                advanceUntilIdle()

                val state = viewModel.uiState.value as UiState.Success
                state.data.map { it.memo.id } shouldBe listOf("memo-keep")
                viewModel.errorMessage.value shouldBe null
                memoRepository.deleteMemoCallCount shouldBe 1
            }
        }

        test("saveImage success returns saved path and keeps error null") {
            runTest {
                val viewModel = createViewModel()
                val uri = mockk<android.net.Uri>()
                every { uri.toString() } returns "content://review/image-1"
                coEvery {
                    saveImageUseCase.saveWithCacheSyncStatus(StorageLocation("content://review/image-1"))
                } returns SaveImageResult.SavedAndCacheSynced(StorageLocation("images/review-1.jpg"))
                var resultPath: String? = null
                var errorCallbackCalled = false

                viewModel.saveImage(
                    uri = uri,
                    onResult = { path -> resultPath = path },
                    onError = { errorCallbackCalled = true },
                )
                advanceUntilIdle()

                resultPath shouldBe "images/review-1.jpg"
                viewModel.errorMessage.value shouldBe null
                errorCallbackCalled shouldBe false
            }
        }

        test("saveImage cache sync failure maps error and triggers onError callback") {
            runTest {
                val viewModel = createViewModel()
                val uri = mockk<android.net.Uri>()
                every { uri.toString() } returns "content://review/image-2"
                coEvery {
                    saveImageUseCase.saveWithCacheSyncStatus(StorageLocation("content://review/image-2"))
                } returns
                    SaveImageResult.SavedButCacheSyncFailed(
                        location = StorageLocation("images/review-2.jpg"),
                        cause = IllegalStateException("cache sync failed"),
                    )
                var resultPath: String? = null
                var errorCallbackCalled = false

                viewModel.saveImage(
                    uri = uri,
                    onResult = { path -> resultPath = path },
                    onError = { errorCallbackCalled = true },
                )
                advanceUntilIdle()

                resultPath shouldBe null
                viewModel.errorMessage.value shouldBe "Failed to save image: cache sync failed"
                errorCallbackCalled shouldBe true
            }
        }

        test("clearError clears existing error message") {
            runTest {
                val memo = sampleMemo("memo-clear-error")
                memoRepository.setActiveMemos(listOf(memo))
                memoRepository.updateMemoFailure = IllegalStateException("update failed")

                val viewModel = createViewModel()
                advanceUntilIdle()

                viewModel.updateMemo(memo, "updated")
                advanceUntilIdle()
                viewModel.errorMessage.value shouldBe "Failed to update memo: update failed"

                viewModel.clearError()

                viewModel.errorMessage.value shouldBe null
            }
        }

        test("loadMore appends new memos to the current random walk list") {
            runTest {
                val firstBatch = listOf(sampleMemo("memo-1", "first"), sampleMemo("memo-2", "second"))
                val secondBatch = listOf(sampleMemo("memo-3", "third"))
                
                // Real loadMore uses random walk over repository.getMemosPage
                memoRepository.setActiveMemos(firstBatch + secondBatch)

                val viewModel = createViewModel()
                advanceUntilIdle()

                // viewModel has loaded the first batch of 2 memos because they were the only ones retrieved initially.
                // Oh wait, because dailyReviewQueryUseCase seed starts, it walk through all 3.
                // Let's set initial repository state to firstBatch, then add secondBatch to repository before loadMore.
                memoRepository.setActiveMemos(firstBatch)
                val viewModelLoaded = createViewModel()
                advanceUntilIdle()
                
                memoRepository.setActiveMemos(firstBatch + secondBatch)
                viewModelLoaded.loadMore()
                advanceUntilIdle()

                val state = viewModelLoaded.uiState.value as UiState.Success
                state.data.map { it.memo.id }.toSet() shouldBe setOf("memo-1", "memo-2", "memo-3")
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

    private fun sampleMemo(
        id: String,
        content: String = "memo content",
    ): Memo =
        Memo(
            id = id,
            timestamp = 1L,
            content = content,
            rawContent = "- 10:00 $content",
            dateKey = "2026_03_24",
        )
}
