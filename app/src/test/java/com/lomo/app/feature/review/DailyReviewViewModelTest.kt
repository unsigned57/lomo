package com.lomo.app.feature.review

import com.lomo.app.feature.common.AppConfigUiCoordinator
import com.lomo.app.feature.common.MemoUiCoordinator
import com.lomo.app.feature.common.UiState
import com.lomo.app.feature.main.MemoUiMapper
import com.lomo.app.provider.ImageMapProvider
import com.lomo.app.provider.emptyImageMapProvider
import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.MainDispatcherExtension
import com.lomo.domain.model.DailyReviewSession
import com.lomo.domain.model.Memo
import com.lomo.domain.model.StorageArea
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.model.ThemeMode
import com.lomo.domain.repository.AppConfigRepository
import com.lomo.domain.repository.MemoRepository
import com.lomo.domain.usecase.DailyReviewQueryUseCase
import com.lomo.domain.usecase.DailyReviewSessionUseCase
import com.lomo.domain.usecase.DeleteMemoUseCase
import com.lomo.domain.usecase.SaveImageResult
import com.lomo.domain.usecase.SaveImageUseCase
import com.lomo.domain.usecase.UpdateMemoContentUseCase
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/*
 * Test Contract:
 * - Unit under test: DailyReviewViewModel
 * - Behavior focus: initial load state transitions, mutation failure error mapping, and save-image result handling.
 * - Observable outcomes: uiState values, surfaced errorMessage text, and success/error callback invocation.
 * - Red phase: Fails before behavior changes or migration are applied.
 * - Excludes: Compose rendering details, MemoUiMapper internals, and repository/use-case implementation internals.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DailyReviewViewModelTest : AppFunSpec() {
    private val testDispatcher = StandardTestDispatcher()

    init {
        extension(MainDispatcherExtension(testDispatcher))
    }

    private lateinit var memoRepository: MemoRepository
    private lateinit var appConfigRepository: AppConfigRepository
    private lateinit var imageMapProvider: ImageMapProvider
    private lateinit var deleteMemoUseCase: DeleteMemoUseCase
    private lateinit var updateMemoContentUseCase: UpdateMemoContentUseCase
    private lateinit var saveImageUseCase: SaveImageUseCase
    private lateinit var dailyReviewQueryUseCase: DailyReviewQueryUseCase
    private lateinit var dailyReviewSessionUseCase: DailyReviewSessionUseCase

    init {
        beforeTest {
memoRepository = mockk(relaxed = true)
            appConfigRepository = mockk(relaxed = true)
            imageMapProvider = emptyImageMapProvider()
            deleteMemoUseCase = mockk(relaxed = true)
            updateMemoContentUseCase = mockk(relaxed = true)
            saveImageUseCase = mockk(relaxed = true)
            dailyReviewQueryUseCase = mockk(relaxed = true)
            dailyReviewSessionUseCase = mockk(relaxed = true)

            every { memoRepository.getActiveDayCount() } returns flowOf(0)

            every { appConfigRepository.observeLocation(StorageArea.ROOT) } returns flowOf(null)
            every { appConfigRepository.observeLocation(StorageArea.IMAGE) } returns flowOf(null)
            every { appConfigRepository.getDateFormat() } returns flowOf("yyyy-MM-dd")
            every { appConfigRepository.getTimeFormat() } returns flowOf("HH:mm")
            every { appConfigRepository.getThemeMode() } returns flowOf(ThemeMode.SYSTEM)
            every { appConfigRepository.isHapticFeedbackEnabled() } returns flowOf(true)
            every { appConfigRepository.isShowInputHintsEnabled() } returns flowOf(true)
            every { appConfigRepository.isDoubleTapEditEnabled() } returns flowOf(true)
            every { appConfigRepository.isFreeTextCopyEnabled() } returns flowOf(false)
            every { appConfigRepository.isMemoActionAutoReorderEnabled() } returns flowOf(true)
            every { appConfigRepository.getMemoActionOrder() } returns flowOf(emptyList())
            every { appConfigRepository.getMemoActionOrdersByScope() } returns flowOf(emptyMap())
            every { appConfigRepository.getInputToolbarToolOrder() } returns flowOf(emptyList())
            every { appConfigRepository.isQuickSaveOnBackEnabled() } returns flowOf(false)
            every { appConfigRepository.isShareCardShowTimeEnabled() } returns flowOf(true)
            every { appConfigRepository.isShareCardShowBrandEnabled() } returns flowOf(true)

            coEvery {
                dailyReviewSessionUseCase.prepareSession()
            } returns DailyReviewSession(LocalDate.of(2026, 4, 16), seed = 1L, pageIndex = 0)
            coEvery { dailyReviewQueryUseCase(1L) } returns emptyList()
            coEvery { deleteMemoUseCase(any()) } returns Unit
            coEvery { updateMemoContentUseCase(any(), any()) } returns Unit
            coEvery { saveImageUseCase.saveWithCacheSyncStatus(any()) } returns
                SaveImageResult.SavedAndCacheSynced(StorageLocation("images/default.jpg"))
        }
    }

    init {
        test("initial load with empty result publishes success with empty list") {
            runTest {
                val viewModel = createViewModel()
                advanceUntilIdle()

                val state = viewModel.uiState.value
                ((state is UiState.Success)) shouldBe true
                ((state as UiState.Success).data) shouldBe (emptyList<com.lomo.app.feature.main.MemoUiModel>())
            }
        }
    }

    init {
        test("initial load failure publishes ui error") {
            runTest {
                coEvery { dailyReviewQueryUseCase(1L) } throws IllegalStateException("query failed")

                val viewModel = createViewModel()
                advanceUntilIdle()

                val state = viewModel.uiState.value
                ((state is UiState.Error)) shouldBe true
                state as UiState.Error
                (state.message) shouldBe ("Failed to load daily review")
                ((state.throwable is IllegalStateException)) shouldBe true
            }
        }
    }

    init {
        test("updateMemo failure maps to user-facing error") {
            runTest {
                val memo = sampleMemo(id = "memo-update")
                val viewModel = createViewModel()
                coEvery { updateMemoContentUseCase(memo, "updated") } throws IllegalStateException("update failed")

                viewModel.updateMemo(memo, "updated")
                advanceUntilIdle()

                (viewModel.errorMessage.value) shouldBe ("Failed to update memo: update failed")
            }
        }
    }

    init {
        test("updateMemo success keeps current list without triggering full reload") {
            runTest {
                val memo = sampleMemo(id = "memo-update-success", content = "before")
                val keep = sampleMemo(id = "memo-keep", content = "stable")
                coEvery { dailyReviewQueryUseCase(1L) } returns listOf(memo, keep)
                val viewModel = createViewModel()
                advanceUntilIdle()

                viewModel.updateMemo(memo, "updated")
                advanceUntilIdle()

                val reloadedState = viewModel.uiState.value as UiState.Success
                (reloadedState.data.map { it.memo.id }) shouldBe (listOf(memo.id, keep.id))
                (viewModel.errorMessage.value) shouldBe null
                coVerify(exactly = 1) { dailyReviewQueryUseCase(1L) }
            }
        }
    }

    init {
        test("deleteMemo failure maps to user-facing error") {
            runTest {
                val memo = sampleMemo(id = "memo-delete")
                val viewModel = createViewModel()
                coEvery { deleteMemoUseCase(memo) } throws IllegalStateException("delete failed")

                viewModel.deleteMemo(memo)
                advanceUntilIdle()

                (viewModel.errorMessage.value) shouldBe ("Failed to delete memo: delete failed")
            }
        }
    }

    init {
        test("deleteMemo success removes memo in place without full reload") {
            runTest {
                val memo = sampleMemo(id = "memo-delete-success")
                coEvery { dailyReviewQueryUseCase(1L) } returns listOf(memo, sampleMemo("memo-keep"))
                val viewModel = createViewModel()
                advanceUntilIdle()

                viewModel.deleteMemo(memo)
                advanceUntilIdle()

                val state = viewModel.uiState.value as UiState.Success
                (state.data.map { it.memo.id }) shouldBe (listOf("memo-keep"))
                (viewModel.errorMessage.value) shouldBe null
                coVerify(exactly = 1) { dailyReviewQueryUseCase(1L) }
            }
        }
    }

    init {
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

                (resultPath) shouldBe ("images/review-1.jpg")
                (viewModel.errorMessage.value) shouldBe null
                (errorCallbackCalled) shouldBe (false)
            }
        }
    }

    init {
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

                (resultPath) shouldBe null
                (viewModel.errorMessage.value) shouldBe ("Failed to save image: cache sync failed")
                (errorCallbackCalled) shouldBe (true)
            }
        }
    }

    init {
        test("clearError clears existing error message") {
            runTest {
                val viewModel = createViewModel()
                val memo = sampleMemo("memo-clear-error")
                advanceUntilIdle()
                coEvery { updateMemoContentUseCase(memo, "updated") } throws IllegalStateException("update failed")

                viewModel.updateMemo(memo, "updated")
                advanceUntilIdle()
                (viewModel.errorMessage.value) shouldBe ("Failed to update memo: update failed")

                viewModel.clearError()

                (viewModel.errorMessage.value) shouldBe null
            }
        }
    }

    init {
        test("loadMore appends new memos to the current random walk list") {
            runTest {
                val firstBatch = listOf(sampleMemo("memo-1", "first"), sampleMemo("memo-2", "second"))
                val secondBatch = listOf(sampleMemo("memo-3", "third"))
                coEvery { dailyReviewQueryUseCase(1L) } returns firstBatch
                coEvery {
                    dailyReviewQueryUseCase.loadMore(
                        excludeIds = setOf("memo-1", "memo-2"),
                        batchSize = any(),
                        seed = 1L,
                    )
                } returns secondBatch

                val viewModel = createViewModel()
                advanceUntilIdle()

                viewModel.loadMore()
                advanceUntilIdle()

                val state = viewModel.uiState.value as UiState.Success
                (state.data.map { it.memo.id }) shouldBe (listOf("memo-1", "memo-2", "memo-3"))
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
