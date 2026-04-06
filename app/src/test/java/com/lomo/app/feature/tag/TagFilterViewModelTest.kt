package com.lomo.app.feature.tag

import androidx.lifecycle.SavedStateHandle
import com.lomo.app.feature.common.AppConfigUiCoordinator
import com.lomo.app.feature.common.MemoUiCoordinator
import com.lomo.app.feature.main.MemoUiMapper
import com.lomo.app.provider.ImageMapProvider
import com.lomo.app.provider.emptyImageMapProvider
import com.lomo.domain.model.Memo
import com.lomo.domain.model.StorageArea
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.model.ThemeMode
import com.lomo.domain.repository.AppConfigRepository
import com.lomo.domain.repository.MemoRepository
import com.lomo.domain.usecase.DeleteMemoUseCase
import com.lomo.domain.usecase.SaveImageResult
import com.lomo.domain.usecase.SaveImageUseCase
import com.lomo.domain.usecase.UpdateMemoContentUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
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
 * - Unit under test: TagFilterViewModel
 * - Behavior focus: tag-scoped memo propagation and mutation/save error handling.
 * - Observable outcomes: tagName usage, memos state values, error message state, and callback side effects.
 * - Red phase: Not applicable - test-only metadata alignment; no production change.
 * - Excludes: Compose rendering, mapper internals, and repository implementation details.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TagFilterViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var memoRepository: MemoRepository
    private lateinit var appConfigRepository: AppConfigRepository
    private lateinit var imageMapProvider: ImageMapProvider
    private lateinit var deleteMemoUseCase: DeleteMemoUseCase
    private lateinit var updateMemoContentUseCase: UpdateMemoContentUseCase
    private lateinit var saveImageUseCase: SaveImageUseCase

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        memoRepository = mockk(relaxed = true)
        appConfigRepository = mockk(relaxed = true)
        imageMapProvider = emptyImageMapProvider()
        deleteMemoUseCase = mockk(relaxed = true)
        updateMemoContentUseCase = mockk(relaxed = true)
        saveImageUseCase = mockk(relaxed = true)

        every { memoRepository.getMemosByTagList(any()) } returns flowOf(emptyList())
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
    fun `memos are loaded for tagName and exposed through state`() =
        runTest {
            val expected = sampleMemo(id = "memo-tag")
            every { memoRepository.getMemosByTagList("work") } returns flowOf(listOf(expected))
            val viewModel = createViewModel(tagName = "work")
            val collectJob = backgroundScope.launch(testDispatcher) { viewModel.memos.collect() }

            val memos = viewModel.memos.first { it.isNotEmpty() }

            assertEquals("work", viewModel.tagName)
            assertEquals(listOf(expected), memos)
            verify(exactly = 1) { memoRepository.getMemosByTagList("work") }
            collectJob.cancel()
        }

    @Test
    fun `deleteMemo exposes mapped error message on failure`() =
        runTest {
            val viewModel = createViewModel(tagName = "work")
            coEvery { deleteMemoUseCase(any()) } throws IllegalStateException("delete failed")

            viewModel.deleteMemo(sampleMemo())
            advanceUntilIdle()

            assertEquals("Failed to delete memo: delete failed", viewModel.errorMessage.value)
        }

    @Test
    fun `updateMemo exposes mapped error message on failure`() =
        runTest {
            val viewModel = createViewModel(tagName = "work")
            val memo = sampleMemo(id = "memo-update")
            coEvery { updateMemoContentUseCase(memo, "updated") } throws IllegalStateException("update failed")

            viewModel.updateMemo(memo, "updated")
            advanceUntilIdle()

            assertEquals("Failed to update memo: update failed", viewModel.errorMessage.value)
        }

    @Test
    fun `saveImage cache sync failure reports error and invokes onError`() =
        runTest {
            val viewModel = createViewModel(tagName = "work")
            val inputUri = mockk<android.net.Uri>()
            every { inputUri.toString() } returns "content://images/photo-2"
            coEvery {
                saveImageUseCase.saveWithCacheSyncStatus(StorageLocation("content://images/photo-2"))
            } returns
                SaveImageResult.SavedButCacheSyncFailed(
                    location = StorageLocation("images/photo-2.jpg"),
                    cause = IllegalStateException("cache sync failed"),
                )
            var savedPath: String? = null
            var onErrorCalled = false

            viewModel.saveImage(
                uri = inputUri,
                onResult = { path -> savedPath = path },
                onError = { onErrorCalled = true },
            )
            advanceUntilIdle()

            assertNull(savedPath)
            assertEquals("Failed to save image: cache sync failed", viewModel.errorMessage.value)
            assertTrue(onErrorCalled)
        }

    @Test
    fun `clearError clears existing error message`() =
        runTest {
            val viewModel = createViewModel(tagName = "work")
            coEvery { deleteMemoUseCase(any()) } throws IllegalStateException("delete failed")

            viewModel.deleteMemo(sampleMemo())
            advanceUntilIdle()
            assertEquals("Failed to delete memo: delete failed", viewModel.errorMessage.value)

            viewModel.clearError()

            assertNull(viewModel.errorMessage.value)
        }

    private fun createViewModel(tagName: String): TagFilterViewModel =
        TagFilterViewModel(
            savedStateHandle = SavedStateHandle(mapOf("tagName" to tagName)),
            memoUiCoordinator = MemoUiCoordinator(memoRepository),
            appConfigUiCoordinator = AppConfigUiCoordinator(appConfigRepository),
            imageMapProvider = imageMapProvider,
            memoUiMapper = MemoUiMapper(),
            deleteMemoUseCase = deleteMemoUseCase,
            updateMemoContentUseCase = updateMemoContentUseCase,
            saveImageUseCase = saveImageUseCase,
        )

    private fun sampleMemo(id: String = "memo-0"): Memo =
        Memo(
            id = id,
            timestamp = 1L,
            content = "content",
            rawContent = "- 10:00 content",
            dateKey = "2026_03_24",
        )
}
