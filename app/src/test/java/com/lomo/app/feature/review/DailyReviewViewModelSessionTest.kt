package com.lomo.app.feature.review

import com.lomo.app.feature.common.AppConfigUiCoordinator
import com.lomo.app.feature.common.MemoUiCoordinator
import com.lomo.app.feature.common.UiState
import com.lomo.app.feature.main.MemoUiMapper
import com.lomo.app.provider.ImageMapProvider
import com.lomo.app.provider.emptyImageMapProvider
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
import io.mockk.coEvery
import io.mockk.coVerify
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/*
 * Test Contract:
 * - Unit under test: DailyReviewViewModel
 * - Behavior focus: restoring the persisted same-day review page, clamping stale page indexes, and persisting page changes back through the session use case.
 * - Observable outcomes: seeded query invocation, exposed restoredPageIndex, and session-page save calls.
 * - Red phase: Fails before the fix because DailyReviewViewModel neither loads a persisted same-day random-review session nor writes page changes on swipe.
 * - Excludes: Compose pager animation, DataStore persistence internals, and memo-card rendering.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DailyReviewViewModelSessionTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var memoRepository: MemoRepository
    private lateinit var appConfigRepository: AppConfigRepository
    private lateinit var imageMapProvider: ImageMapProvider
    private lateinit var deleteMemoUseCase: DeleteMemoUseCase
    private lateinit var updateMemoContentUseCase: UpdateMemoContentUseCase
    private lateinit var saveImageUseCase: SaveImageUseCase
    private lateinit var dailyReviewQueryUseCase: DailyReviewQueryUseCase
    private lateinit var dailyReviewSessionUseCase: DailyReviewSessionUseCase

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
        every { appConfigRepository.isQuickSaveOnBackEnabled() } returns flowOf(false)
        every { appConfigRepository.isShareCardShowTimeEnabled() } returns flowOf(true)
        every { appConfigRepository.isShareCardShowBrandEnabled() } returns flowOf(true)

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
    fun `initial load uses the restored same-day seed and page`() =
        runTest {
            val memos = listOf(sampleMemo("memo-1"), sampleMemo("memo-2"), sampleMemo("memo-3"), sampleMemo("memo-4"))
            coEvery { dailyReviewSessionUseCase.prepareSession() } returns
                DailyReviewSession(
                    date = LocalDate.of(2026, 4, 16),
                    seed = 77L,
                    pageIndex = 3,
                )
            coEvery { dailyReviewQueryUseCase(77L) } returns memos

            val viewModel = createViewModel()
            advanceUntilIdle()

            assertEquals(3, viewModel.restoredPageIndex.value)
            assertTrue(viewModel.uiState.value is UiState.Success)
            coVerify(exactly = 1) { dailyReviewQueryUseCase(77L) }
        }

    @Test
    fun `initial load clamps an out-of-range restored page and persists the correction`() =
        runTest {
            coEvery { dailyReviewSessionUseCase.prepareSession() } returns
                DailyReviewSession(
                    date = LocalDate.of(2026, 4, 16),
                    seed = 77L,
                    pageIndex = 9,
                )
            coEvery { dailyReviewQueryUseCase(77L) } returns listOf(sampleMemo("memo-1"), sampleMemo("memo-2"))
            coEvery { dailyReviewSessionUseCase.updateCurrentPage(any(), any()) } returns Unit

            val viewModel = createViewModel()
            advanceUntilIdle()

            assertEquals(1, viewModel.restoredPageIndex.value)
            coVerify(exactly = 1) { dailyReviewSessionUseCase.updateCurrentPage(seed = 77L, pageIndex = 1) }
        }

    @Test
    fun `onPageChanged persists the latest page for the active seed`() =
        runTest {
            coEvery { dailyReviewSessionUseCase.prepareSession() } returns
                DailyReviewSession(
                    date = LocalDate.of(2026, 4, 16),
                    seed = 77L,
                    pageIndex = 0,
                )
            coEvery { dailyReviewQueryUseCase(77L) } returns listOf(sampleMemo("memo-1"), sampleMemo("memo-2"))
            coEvery { dailyReviewSessionUseCase.updateCurrentPage(any(), any()) } returns Unit

            val viewModel = createViewModel()
            advanceUntilIdle()
            viewModel.onPageChanged(1)
            advanceUntilIdle()

            assertEquals(1, viewModel.restoredPageIndex.value)
            coVerify(exactly = 1) { dailyReviewSessionUseCase.updateCurrentPage(seed = 77L, pageIndex = 1) }
        }

    private fun createViewModel(): DailyReviewViewModel =
        DailyReviewViewModel(
            memoUiCoordinator = MemoUiCoordinator(memoRepository),
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
