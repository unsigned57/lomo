package com.lomo.app.feature.search

import com.lomo.app.feature.common.AppConfigUiCoordinator
import com.lomo.app.feature.common.MemoUiCoordinator
import com.lomo.app.feature.main.MemoUiMapper
import com.lomo.app.provider.ImageMapProvider
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: SearchViewModel
 * - Behavior focus: debounced search loading timing, search result propagation, error mapping for memo mutation actions, and image save outcomes.
 * - Observable outcomes: searching and search-result state flow values, surfaced error messages, callback invocation, and collaborator calls.
 * - Red phase: Fails before the fix because SearchViewModel enters searching during the debounce window instead of waiting until the debounced repository search actually starts.
 * - Excludes: Compose rendering details, MemoUiMapper internals, and repository implementation internals.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
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
        imageMapProvider = mockk(relaxed = true)
        deleteMemoUseCase = mockk(relaxed = true)
        updateMemoContentUseCase = mockk(relaxed = true)
        saveImageUseCase = mockk(relaxed = true)

        every { memoRepository.getActiveDayCount() } returns flowOf(0)
        every { memoRepository.searchMemosList(any()) } returns flowOf(emptyList())

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

        every { imageMapProvider.imageMap } returns MutableStateFlow(emptyMap())

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
    fun `blank query keeps empty results and does not trigger repository search`() =
        runTest {
            val viewModel = createViewModel()
            val collectJob = backgroundScope.launch(testDispatcher) { viewModel.searchResults.collect() }

            viewModel.onSearchQueryChanged("   ")
            testDispatcher.scheduler.advanceTimeBy(350)
            advanceUntilIdle()

            assertTrue(viewModel.searchResults.value.isEmpty())
            verify(exactly = 0) { memoRepository.searchMemosList(any()) }
            collectJob.cancel()
        }

    @Test
    fun `non blank query propagates repository search results`() =
        runTest {
            val expectedMemo = sampleMemo(id = "memo-1", content = "search-hit")
            every { memoRepository.searchMemosList("search-hit") } returns flowOf(listOf(expectedMemo))
            val viewModel = createViewModel()
            val collectJob = backgroundScope.launch(testDispatcher) { viewModel.searchResults.collect() }

            viewModel.onSearchQueryChanged("search-hit")
            testDispatcher.scheduler.advanceTimeBy(350)
            val results = viewModel.searchResults.first { it.isNotEmpty() }

            assertEquals(listOf(expectedMemo), results)
            verify(exactly = 1) { memoRepository.searchMemosList("search-hit") }
            collectJob.cancel()
        }

    @Test
    fun `Chinese single and multi character prefixes both keep Socrates searchable`() =
        runTest {
            val socratesMemo = sampleMemo(id = "memo-socrates", content = "苏格拉底 说未经审视的人生不值得过")
            every { memoRepository.searchMemosList("苏") } returns flowOf(listOf(socratesMemo))
            every { memoRepository.searchMemosList("苏格") } returns flowOf(listOf(socratesMemo))
            val viewModel = createViewModel()
            val collectJob = backgroundScope.launch(testDispatcher) { viewModel.searchResults.collect() }

            viewModel.onSearchQueryChanged("苏")
            testDispatcher.scheduler.advanceTimeBy(350)
            advanceUntilIdle()
            assertEquals(listOf(socratesMemo), viewModel.searchResults.value)

            viewModel.onSearchQueryChanged("苏格")
            testDispatcher.scheduler.advanceTimeBy(350)
            advanceUntilIdle()

            assertEquals(listOf(socratesMemo), viewModel.searchResults.value)
            verify(exactly = 1) { memoRepository.searchMemosList("苏") }
            verify(exactly = 1) { memoRepository.searchMemosList("苏格") }
            collectJob.cancel()
        }

    @Test
    fun `non blank query stays idle through debounce window`() =
        runTest {
            val viewModel = createViewModel()
            val searchingJob = backgroundScope.launch(testDispatcher) { viewModel.isSearching.collect() }
            val resultsJob = backgroundScope.launch(testDispatcher) { viewModel.searchResults.collect() }

            viewModel.onSearchQueryChanged("search-hit")
            runCurrent()

            assertFalse(viewModel.isSearching.value)
            verify(exactly = 0) { memoRepository.searchMemosList(any()) }

            testDispatcher.scheduler.advanceTimeBy(299)
            runCurrent()

            assertFalse(viewModel.isSearching.value)
            verify(exactly = 0) { memoRepository.searchMemosList(any()) }
            searchingJob.cancel()
            resultsJob.cancel()
        }

    @Test
    fun `searching starts after debounce and clears when delayed repository result arrives`() =
        runTest {
            val expectedMemo = sampleMemo(id = "memo-delayed", content = "delayed-result")
            every { memoRepository.searchMemosList("delayed-result") } returns
                flow {
                    delay(500)
                    emit(listOf(expectedMemo))
                }
            val viewModel = createViewModel()
            val searchingJob = backgroundScope.launch(testDispatcher) { viewModel.isSearching.collect() }
            val resultsJob = backgroundScope.launch(testDispatcher) { viewModel.searchResults.collect() }

            viewModel.onSearchQueryChanged("delayed-result")
            runCurrent()
            assertFalse(viewModel.isSearching.value)

            testDispatcher.scheduler.advanceTimeBy(299)
            runCurrent()

            assertFalse(viewModel.isSearching.value)
            verify(exactly = 0) { memoRepository.searchMemosList(any()) }

            testDispatcher.scheduler.advanceTimeBy(1)
            runCurrent()

            assertTrue(viewModel.isSearching.value)
            verify(exactly = 1) { memoRepository.searchMemosList("delayed-result") }

            testDispatcher.scheduler.advanceTimeBy(500)
            runCurrent()

            assertFalse(viewModel.isSearching.value)
            assertEquals(listOf(expectedMemo), viewModel.searchResults.value)
            searchingJob.cancel()
            resultsJob.cancel()
        }

    @Test
    fun `fast search result never exposes loading indicator`() =
        runTest {
            val expectedMemo = sampleMemo(id = "memo-fast", content = "fast-result")
            every { memoRepository.searchMemosList("fast-result") } returns
                flow {
                    delay(80)
                    emit(listOf(expectedMemo))
                }
            val viewModel = createViewModel()
            val loadingJob = backgroundScope.launch(testDispatcher) { viewModel.showLoading.collect() }
            val resultsJob = backgroundScope.launch(testDispatcher) { viewModel.searchResults.collect() }

            viewModel.onSearchQueryChanged("fast-result")
            runCurrent()

            testDispatcher.scheduler.advanceTimeBy(300)
            runCurrent()

            assertFalse(viewModel.showLoading.value)

            testDispatcher.scheduler.advanceTimeBy(80)
            runCurrent()

            assertFalse(viewModel.showLoading.value)
            assertEquals(listOf(expectedMemo), viewModel.searchResults.value)
            loadingJob.cancel()
            resultsJob.cancel()
        }

    @Test
    fun `loading indicator remains visible for minimum duration after delayed result`() =
        runTest {
            val expectedMemo = sampleMemo(id = "memo-min-visible", content = "min-visible")
            every { memoRepository.searchMemosList("min-visible") } returns
                flow {
                    delay(121)
                    emit(listOf(expectedMemo))
                }
            val viewModel = createViewModel()
            val loadingJob = backgroundScope.launch(testDispatcher) { viewModel.showLoading.collect() }
            val resultsJob = backgroundScope.launch(testDispatcher) { viewModel.searchResults.collect() }

            viewModel.onSearchQueryChanged("min-visible")
            runCurrent()

            testDispatcher.scheduler.advanceTimeBy(300)
            runCurrent()
            assertFalse(viewModel.showLoading.value)

            testDispatcher.scheduler.advanceTimeBy(120)
            runCurrent()
            assertTrue(viewModel.showLoading.value)

            testDispatcher.scheduler.advanceTimeBy(1)
            runCurrent()
            assertTrue(viewModel.showLoading.value)
            assertEquals(listOf(expectedMemo), viewModel.searchResults.value)

            testDispatcher.scheduler.advanceTimeBy(278)
            runCurrent()
            assertTrue(viewModel.showLoading.value)

            testDispatcher.scheduler.advanceTimeBy(2)
            runCurrent()
            assertFalse(viewModel.showLoading.value)
            loadingJob.cancel()
            resultsJob.cancel()
        }

    @Test
    fun `query replacement cancels prior loading timer before it becomes visible`() =
        runTest {
            val replacementMemo = sampleMemo(id = "memo-replacement", content = "replacement")
            every { memoRepository.searchMemosList("slow-first") } returns
                flow {
                    delay(500)
                    emit(emptyList())
                }
            every { memoRepository.searchMemosList("replacement") } returns
                flow {
                    delay(10)
                    emit(listOf(replacementMemo))
                }
            val viewModel = createViewModel()
            val loadingJob = backgroundScope.launch(testDispatcher) { viewModel.showLoading.collect() }
            val resultsJob = backgroundScope.launch(testDispatcher) { viewModel.searchResults.collect() }

            viewModel.onSearchQueryChanged("slow-first")
            runCurrent()

            testDispatcher.scheduler.advanceTimeBy(300)
            runCurrent()
            assertFalse(viewModel.showLoading.value)

            testDispatcher.scheduler.advanceTimeBy(60)
            runCurrent()
            assertFalse(viewModel.showLoading.value)

            viewModel.onSearchQueryChanged("replacement")
            runCurrent()

            testDispatcher.scheduler.advanceTimeBy(300)
            runCurrent()
            assertFalse(viewModel.showLoading.value)

            testDispatcher.scheduler.advanceTimeBy(10)
            runCurrent()

            assertFalse(viewModel.showLoading.value)
            assertEquals(listOf(replacementMemo), viewModel.searchResults.value)
            verify(exactly = 1) { memoRepository.searchMemosList("slow-first") }
            verify(exactly = 1) { memoRepository.searchMemosList("replacement") }
            loadingJob.cancel()
            resultsJob.cancel()
        }

    @Test
    fun `search failure clears searching and falls back to empty results`() =
        runTest {
            every { memoRepository.searchMemosList("boom") } returns
                flow {
                    delay(500)
                    throw IllegalStateException("search failed")
                }
            val viewModel = createViewModel()
            val searchingJob = backgroundScope.launch(testDispatcher) { viewModel.isSearching.collect() }
            val resultsJob = backgroundScope.launch(testDispatcher) { viewModel.searchResults.collect() }

            viewModel.onSearchQueryChanged("boom")
            runCurrent()
            assertFalse(viewModel.isSearching.value)

            testDispatcher.scheduler.advanceTimeBy(300)
            runCurrent()

            assertTrue(viewModel.isSearching.value)

            testDispatcher.scheduler.advanceTimeBy(800)
            runCurrent()

            assertFalse(viewModel.isSearching.value)
            assertTrue(viewModel.searchResults.value.isEmpty())
            searchingJob.cancel()
            resultsJob.cancel()
        }

    @Test
    fun `blank query stays out of searching state`() =
        runTest {
            val viewModel = createViewModel()
            val searchingJob = backgroundScope.launch(testDispatcher) { viewModel.isSearching.collect() }
            val resultsJob = backgroundScope.launch(testDispatcher) { viewModel.searchResults.collect() }

            viewModel.onSearchQueryChanged("   ")
            runCurrent()
            testDispatcher.scheduler.advanceTimeBy(350)
            runCurrent()

            assertFalse(viewModel.isSearching.value)
            assertTrue(viewModel.searchResults.value.isEmpty())
            verify(exactly = 0) { memoRepository.searchMemosList(any()) }
            searchingJob.cancel()
            resultsJob.cancel()
        }

    @Test
    fun `deleteMemo exposes mapped error message on failure`() =
        runTest {
            val viewModel = createViewModel()
            coEvery { deleteMemoUseCase(any()) } throws IllegalStateException("delete failed")

            viewModel.deleteMemo(sampleMemo())
            advanceUntilIdle()

            assertEquals("Failed to delete memo: delete failed", viewModel.errorMessage.value)
        }

    @Test
    fun `updateMemo exposes mapped error message on failure`() =
        runTest {
            val viewModel = createViewModel()
            val memo = sampleMemo(id = "memo-update")
            coEvery { updateMemoContentUseCase(memo, "updated") } throws IllegalStateException("update failed")

            viewModel.updateMemo(memo, "updated")
            advanceUntilIdle()

            assertEquals("Failed to update memo: update failed", viewModel.errorMessage.value)
        }

    @Test
    fun `saveImage success returns saved path and does not set error`() =
        runTest {
            val viewModel = createViewModel()
            val inputUri = mockk<android.net.Uri>()
            every { inputUri.toString() } returns "content://images/photo-1"
            coEvery {
                saveImageUseCase.saveWithCacheSyncStatus(StorageLocation("content://images/photo-1"))
            } returns SaveImageResult.SavedAndCacheSynced(StorageLocation("images/photo-1.jpg"))
            var savedPath: String? = null
            var onErrorCalled = false

            viewModel.saveImage(
                uri = inputUri,
                onResult = { path -> savedPath = path },
                onError = { onErrorCalled = true },
            )
            advanceUntilIdle()

            assertEquals("images/photo-1.jpg", savedPath)
            assertNull(viewModel.errorMessage.value)
            assertEquals(false, onErrorCalled)
            coVerify(exactly = 1) {
                saveImageUseCase.saveWithCacheSyncStatus(StorageLocation("content://images/photo-1"))
            }
        }

    @Test
    fun `saveImage cache sync failure reports error and invokes onError`() =
        runTest {
            val viewModel = createViewModel()
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
            assertEquals(true, onErrorCalled)
        }

    @Test
    fun `clearError clears existing error message`() =
        runTest {
            val viewModel = createViewModel()
            coEvery { deleteMemoUseCase(any()) } throws IllegalStateException("delete failed")

            viewModel.deleteMemo(sampleMemo())
            advanceUntilIdle()
            assertEquals("Failed to delete memo: delete failed", viewModel.errorMessage.value)

            viewModel.clearError()

            assertNull(viewModel.errorMessage.value)
        }

    private fun createViewModel(): SearchViewModel =
        SearchViewModel(
            memoUiCoordinator = MemoUiCoordinator(memoRepository),
            appConfigUiCoordinator = AppConfigUiCoordinator(appConfigRepository),
            imageMapProvider = imageMapProvider,
            memoUiMapper = MemoUiMapper(),
            deleteMemoUseCase = deleteMemoUseCase,
            updateMemoContentUseCase = updateMemoContentUseCase,
            saveImageUseCase = saveImageUseCase,
        )

    private fun sampleMemo(
        id: String = "memo-0",
        content: String = "content",
    ): Memo =
        Memo(
            id = id,
            timestamp = 1L,
            content = content,
            rawContent = "- 10:00 $content",
            dateKey = "2026_03_24",
        )
}
