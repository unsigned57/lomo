package com.lomo.app.feature.review

import com.lomo.app.feature.common.AppConfigUiCoordinator
import com.lomo.app.feature.common.MemoUiCoordinator
import com.lomo.app.feature.common.UiState
import com.lomo.app.feature.main.MemoUiMapper
import com.lomo.app.provider.ImageMapProvider
import com.lomo.app.provider.emptyImageMapProvider
import com.lomo.domain.model.Memo
import com.lomo.domain.model.StorageArea
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.model.ThemeMode
import com.lomo.domain.repository.AppConfigRepository
import com.lomo.domain.repository.MemoRepository
import com.lomo.domain.usecase.DailyReviewQueryUseCase
import com.lomo.domain.usecase.DeleteMemoUseCase
import com.lomo.domain.usecase.SaveImageResult
import com.lomo.domain.usecase.SaveImageUseCase
import com.lomo.domain.usecase.UpdateMemoContentUseCase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: DailyReviewViewModel
 * - Behavior focus: initial load state transitions, mutation failure error mapping, and save-image result handling.
 * - Observable outcomes: uiState values, surfaced errorMessage text, and success/error callback invocation.
 * - Red phase: Not applicable - test-only metadata alignment; no production change.
 * - Excludes: Compose rendering details, MemoUiMapper internals, and repository/use-case implementation internals.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DailyReviewViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var memoRepository: MemoRepository
    private lateinit var appConfigRepository: AppConfigRepository
    private lateinit var imageMapProvider: ImageMapProvider
    private lateinit var deleteMemoUseCase: DeleteMemoUseCase
    private lateinit var updateMemoContentUseCase: UpdateMemoContentUseCase
    private lateinit var saveImageUseCase: SaveImageUseCase
    private lateinit var dailyReviewQueryUseCase: DailyReviewQueryUseCase

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        memoRepository = mockk(relaxed = true)
        appConfigRepository = mockk(relaxed = true)
        imageMapProvider = emptyImageMapProvider()
        deleteMemoUseCase = mockk(relaxed = true)
        updateMemoContentUseCase = mockk(relaxed = true)
        saveImageUseCase = mockk(relaxed = true)
        dailyReviewQueryUseCase = mockk(relaxed = true)

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
        every { appConfigRepository.isQuickSaveOnBackEnabled() } returns flowOf(false)
        every { appConfigRepository.isShareCardShowTimeEnabled() } returns flowOf(true)
        every { appConfigRepository.isShareCardShowBrandEnabled() } returns flowOf(true)

        coEvery { dailyReviewQueryUseCase() } returns emptyList()
        coEvery { deleteMemoUseCase(any()) } returns Unit
        coEvery { updateMemoContentUseCase(any(), any()) } returns Unit
        coEvery { saveImageUseCase.saveWithCacheSyncStatus(any()) } returns
            SaveImageResult.SavedAndCacheSynced(StorageLocation("images/default.jpg"))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial load with empty result publishes success with empty list`() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state is UiState.Success)
            assertEquals(emptyList<com.lomo.app.feature.main.MemoUiModel>(), (state as UiState.Success).data)
        }

    @Test
    fun `initial load failure publishes ui error`() =
        runTest {
            coEvery { dailyReviewQueryUseCase() } throws IllegalStateException("query failed")

            val viewModel = createViewModel()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state is UiState.Error)
            state as UiState.Error
            assertEquals("Failed to load daily review", state.message)
            assertTrue(state.throwable is IllegalStateException)
        }

    @Test
    fun `updateMemo failure maps to user-facing error`() =
        runTest {
            val memo = sampleMemo(id = "memo-update")
            val viewModel = createViewModel()
            coEvery { updateMemoContentUseCase(memo, "updated") } throws IllegalStateException("update failed")

            viewModel.updateMemo(memo, "updated")
            advanceUntilIdle()

            assertEquals("Failed to update memo: update failed", viewModel.errorMessage.value)
        }

    @Test
    fun `updateMemo success reloads daily review`() =
        runTest {
            val memo = sampleMemo(id = "memo-update-success")
            coEvery { dailyReviewQueryUseCase() } throws IllegalStateException("initial failed") andThen emptyList()
            val viewModel = createViewModel()
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value is UiState.Error)

            viewModel.updateMemo(memo, "updated")
            advanceUntilIdle()

            val reloadedState = viewModel.uiState.value as UiState.Success
            assertTrue(reloadedState.data.isEmpty())
            assertNull(viewModel.errorMessage.value)
        }

    @Test
    fun `deleteMemo failure maps to user-facing error`() =
        runTest {
            val memo = sampleMemo(id = "memo-delete")
            val viewModel = createViewModel()
            coEvery { deleteMemoUseCase(memo) } throws IllegalStateException("delete failed")

            viewModel.deleteMemo(memo)
            advanceUntilIdle()

            assertEquals("Failed to delete memo: delete failed", viewModel.errorMessage.value)
        }

    @Test
    fun `deleteMemo success reloads daily review`() =
        runTest {
            val memo = sampleMemo(id = "memo-delete-success")
            coEvery { dailyReviewQueryUseCase() } throws IllegalStateException("initial failed") andThen emptyList()
            val viewModel = createViewModel()
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value is UiState.Error)

            viewModel.deleteMemo(memo)
            advanceUntilIdle()

            val state = viewModel.uiState.value as UiState.Success
            assertTrue(state.data.isEmpty())
            assertNull(viewModel.errorMessage.value)
        }

    @Test
    fun `saveImage success returns saved path and keeps error null`() =
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

            assertEquals("images/review-1.jpg", resultPath)
            assertNull(viewModel.errorMessage.value)
            assertEquals(false, errorCallbackCalled)
        }

    @Test
    fun `saveImage cache sync failure maps error and triggers onError callback`() =
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

            assertNull(resultPath)
            assertEquals("Failed to save image: cache sync failed", viewModel.errorMessage.value)
            assertEquals(true, errorCallbackCalled)
        }

    @Test
    fun `clearError clears existing error message`() =
        runTest {
            val viewModel = createViewModel()
            val memo = sampleMemo("memo-clear-error")
            advanceUntilIdle()
            coEvery { updateMemoContentUseCase(memo, "updated") } throws IllegalStateException("update failed")

            viewModel.updateMemo(memo, "updated")
            advanceUntilIdle()
            assertEquals("Failed to update memo: update failed", viewModel.errorMessage.value)

            viewModel.clearError()

            assertNull(viewModel.errorMessage.value)
        }

    private fun createViewModel(): DailyReviewViewModel =
        DailyReviewViewModel(
            memoUiCoordinator = MemoUiCoordinator(memoRepository),
            appConfigUiCoordinator = AppConfigUiCoordinator(appConfigRepository),
            imageMapProvider = imageMapProvider,
            memoUiMapper = MemoUiMapper(),
            deleteMemoUseCase = deleteMemoUseCase,
            updateMemoContentUseCase = updateMemoContentUseCase,
            saveImageUseCase = saveImageUseCase,
            dailyReviewQueryUseCase = dailyReviewQueryUseCase,
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
