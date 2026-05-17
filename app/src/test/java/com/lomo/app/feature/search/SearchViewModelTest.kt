package com.lomo.app.feature.search

import com.lomo.app.feature.common.AppConfigUiCoordinator
import com.lomo.app.feature.common.MemoUiCoordinator
import com.lomo.app.feature.main.MemoUiMapper
import com.lomo.app.provider.ImageMapProvider
import com.lomo.app.provider.emptyImageMapProvider
import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.MainDispatcherExtension
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
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/*
 * Test Contract:
 * - Unit under test: SearchViewModel
 * - Behavior focus: debounced search loading timing, search result propagation, error mapping for memo mutation actions, and image save outcomes.
 * - Observable outcomes: searching and search-result state flow values, surfaced error messages, callback invocation, and collaborator calls.
 * - Red phase: Fails before the fix because SearchViewModel enters searching during the debounce window instead of waiting until the debounced repository search actually starts.
 * - Excludes: Compose rendering details, MemoUiMapper internals, and repository implementation internals.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest : AppFunSpec() {
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

    init {
        beforeTest {
memoRepository = mockk(relaxed = true)
            appConfigRepository = mockk(relaxed = true)
            imageMapProvider = emptyImageMapProvider()
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
            every { appConfigRepository.getMemoActionOrdersByScope() } returns flowOf(emptyMap())
            every { appConfigRepository.getInputToolbarToolOrder() } returns flowOf(emptyList())
            every { appConfigRepository.isQuickSaveOnBackEnabled() } returns flowOf(false)
            every { appConfigRepository.isShareCardShowTimeEnabled() } returns flowOf(true)
            every { appConfigRepository.isShareCardShowBrandEnabled() } returns flowOf(true)

            coEvery { deleteMemoUseCase(any()) } returns Unit
            coEvery { updateMemoContentUseCase(any(), any()) } returns Unit
            coEvery { saveImageUseCase.saveWithCacheSyncStatus(any()) } returns
                SaveImageResult.SavedAndCacheSynced(StorageLocation("images/default.jpg"))
        }
    }

    init {
        test("blank query keeps empty results and does not trigger repository search") {
            runTest {
                val viewModel = createViewModel()
                val collectJob = backgroundScope.launch(testDispatcher) { viewModel.searchResults.collect() }

                viewModel.onSearchQueryChanged("   ")
                testDispatcher.scheduler.advanceTimeBy(350)
                advanceUntilIdle()

                ((viewModel.searchResults.value.isEmpty())) shouldBe true
                verify(exactly = 0) { memoRepository.searchMemosList(any()) }
                collectJob.cancel()
            }
        }
    }

    init {
        test("non blank query propagates repository search results") {
            runTest {
                val expectedMemo = sampleMemo(id = "memo-1", content = "search-hit")
                every { memoRepository.searchMemosList("search-hit") } returns flowOf(listOf(expectedMemo))
                val viewModel = createViewModel()
                val collectJob = backgroundScope.launch(testDispatcher) { viewModel.searchResults.collect() }

                viewModel.onSearchQueryChanged("search-hit")
                testDispatcher.scheduler.advanceTimeBy(350)
                val results = viewModel.searchResults.first { it.isNotEmpty() }

                (results) shouldBe (listOf(expectedMemo))
                verify(exactly = 1) { memoRepository.searchMemosList("search-hit") }
                collectJob.cancel()
            }
        }
    }

    init {
        test("Chinese single and multi character prefixes both keep Socrates searchable") {
            runTest {
                val socratesMemo = sampleMemo(id = "memo-socrates", content = "苏格拉底 说未经审视的人生不值得过")
                every { memoRepository.searchMemosList("苏") } returns flowOf(listOf(socratesMemo))
                every { memoRepository.searchMemosList("苏格") } returns flowOf(listOf(socratesMemo))
                val viewModel = createViewModel()
                val collectJob = backgroundScope.launch(testDispatcher) { viewModel.searchResults.collect() }

                viewModel.onSearchQueryChanged("苏")
                testDispatcher.scheduler.advanceTimeBy(350)
                advanceUntilIdle()
                (viewModel.searchResults.value) shouldBe (listOf(socratesMemo))

                viewModel.onSearchQueryChanged("苏格")
                testDispatcher.scheduler.advanceTimeBy(350)
                advanceUntilIdle()

                (viewModel.searchResults.value) shouldBe (listOf(socratesMemo))
                verify(exactly = 1) { memoRepository.searchMemosList("苏") }
                verify(exactly = 1) { memoRepository.searchMemosList("苏格") }
                collectJob.cancel()
            }
        }
    }

    init {
        test("non blank query stays idle through debounce window") {
            runTest {
                val viewModel = createViewModel()
                val searchingJob = backgroundScope.launch(testDispatcher) { viewModel.isSearching.collect() }
                val resultsJob = backgroundScope.launch(testDispatcher) { viewModel.searchResults.collect() }

                viewModel.onSearchQueryChanged("search-hit")
                runCurrent()

                ((viewModel.isSearching.value)) shouldBe false
                verify(exactly = 0) { memoRepository.searchMemosList(any()) }

                testDispatcher.scheduler.advanceTimeBy(299)
                runCurrent()

                ((viewModel.isSearching.value)) shouldBe false
                verify(exactly = 0) { memoRepository.searchMemosList(any()) }
                searchingJob.cancel()
                resultsJob.cancel()
            }
        }
    }

    init {
        test("searching starts after debounce and clears when delayed repository result arrives") {
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
                ((viewModel.isSearching.value)) shouldBe false

                testDispatcher.scheduler.advanceTimeBy(299)
                runCurrent()

                ((viewModel.isSearching.value)) shouldBe false
                verify(exactly = 0) { memoRepository.searchMemosList(any()) }

                testDispatcher.scheduler.advanceTimeBy(1)
                runCurrent()

                ((viewModel.isSearching.value)) shouldBe true
                verify(exactly = 1) { memoRepository.searchMemosList("delayed-result") }

                testDispatcher.scheduler.advanceTimeBy(500)
                runCurrent()

                ((viewModel.isSearching.value)) shouldBe false
                (viewModel.searchResults.value) shouldBe (listOf(expectedMemo))
                searchingJob.cancel()
                resultsJob.cancel()
            }
        }
    }

    init {
        test("fast search result never exposes loading indicator") {
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

                ((viewModel.showLoading.value)) shouldBe false

                testDispatcher.scheduler.advanceTimeBy(80)
                runCurrent()

                ((viewModel.showLoading.value)) shouldBe false
                (viewModel.searchResults.value) shouldBe (listOf(expectedMemo))
                loadingJob.cancel()
                resultsJob.cancel()
            }
        }
    }

    init {
        test("loading indicator remains visible for minimum duration after delayed result") {
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
                ((viewModel.showLoading.value)) shouldBe false

                testDispatcher.scheduler.advanceTimeBy(120)
                runCurrent()
                ((viewModel.showLoading.value)) shouldBe true

                testDispatcher.scheduler.advanceTimeBy(1)
                runCurrent()
                ((viewModel.showLoading.value)) shouldBe true
                (viewModel.searchResults.value) shouldBe (listOf(expectedMemo))

                testDispatcher.scheduler.advanceTimeBy(278)
                runCurrent()
                ((viewModel.showLoading.value)) shouldBe true

                testDispatcher.scheduler.advanceTimeBy(2)
                runCurrent()
                ((viewModel.showLoading.value)) shouldBe false
                loadingJob.cancel()
                resultsJob.cancel()
            }
        }
    }

    init {
        test("query replacement cancels prior loading timer before it becomes visible") {
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
                ((viewModel.showLoading.value)) shouldBe false

                testDispatcher.scheduler.advanceTimeBy(60)
                runCurrent()
                ((viewModel.showLoading.value)) shouldBe false

                viewModel.onSearchQueryChanged("replacement")
                runCurrent()

                testDispatcher.scheduler.advanceTimeBy(300)
                runCurrent()
                ((viewModel.showLoading.value)) shouldBe false

                testDispatcher.scheduler.advanceTimeBy(10)
                runCurrent()

                ((viewModel.showLoading.value)) shouldBe false
                (viewModel.searchResults.value) shouldBe (listOf(replacementMemo))
                verify(exactly = 1) { memoRepository.searchMemosList("slow-first") }
                verify(exactly = 1) { memoRepository.searchMemosList("replacement") }
                loadingJob.cancel()
                resultsJob.cancel()
            }
        }
    }

    init {
        test("search failure clears searching and falls back to empty results") {
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
                ((viewModel.isSearching.value)) shouldBe false

                testDispatcher.scheduler.advanceTimeBy(300)
                runCurrent()

                ((viewModel.isSearching.value)) shouldBe true

                testDispatcher.scheduler.advanceTimeBy(800)
                runCurrent()

                ((viewModel.isSearching.value)) shouldBe false
                ((viewModel.searchResults.value.isEmpty())) shouldBe true
                searchingJob.cancel()
                resultsJob.cancel()
            }
        }
    }

    init {
        test("blank query stays out of searching state") {
            runTest {
                val viewModel = createViewModel()
                val searchingJob = backgroundScope.launch(testDispatcher) { viewModel.isSearching.collect() }
                val resultsJob = backgroundScope.launch(testDispatcher) { viewModel.searchResults.collect() }

                viewModel.onSearchQueryChanged("   ")
                runCurrent()
                testDispatcher.scheduler.advanceTimeBy(350)
                runCurrent()

                ((viewModel.isSearching.value)) shouldBe false
                ((viewModel.searchResults.value.isEmpty())) shouldBe true
                verify(exactly = 0) { memoRepository.searchMemosList(any()) }
                searchingJob.cancel()
                resultsJob.cancel()
            }
        }
    }

    init {
        test("deleteMemo exposes mapped error message on failure") {
            runTest {
                val viewModel = createViewModel()
                coEvery { deleteMemoUseCase(any()) } throws IllegalStateException("delete failed")

                viewModel.deleteMemo(sampleMemo())
                advanceUntilIdle()

                (viewModel.errorMessage.value) shouldBe ("Failed to delete memo: delete failed")
            }
        }
    }

    init {
        test("deleteMemo keeps deleting id until search list animation settles") {
            runTest {
                val viewModel = createViewModel()
                val memo = sampleMemo(id = "memo-delete")

                viewModel.deleteMemo(memo)
                runCurrent()

                ((viewModel.deletingMemoIds.value.contains(memo.id))) shouldBe true

                viewModel.onDeleteAnimationSettled(memo.id)

                ((viewModel.deletingMemoIds.value.isEmpty())) shouldBe true
            }
        }
    }

    init {
        test("updateMemo exposes mapped error message on failure") {
            runTest {
                val viewModel = createViewModel()
                val memo = sampleMemo(id = "memo-update")
                coEvery { updateMemoContentUseCase(memo, "updated") } throws IllegalStateException("update failed")

                viewModel.updateMemo(memo, "updated")
                advanceUntilIdle()

                (viewModel.errorMessage.value) shouldBe ("Failed to update memo: update failed")
            }
        }
    }

    init {
        test("saveImage success returns saved path and does not set error") {
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

                (savedPath) shouldBe ("images/photo-1.jpg")
                (viewModel.errorMessage.value) shouldBe null
                (onErrorCalled) shouldBe (false)
                coVerify(exactly = 1) {
                    saveImageUseCase.saveWithCacheSyncStatus(StorageLocation("content://images/photo-1"))
                }
            }
        }
    }

    init {
        test("saveImage cache sync failure reports error and invokes onError") {
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

                (savedPath) shouldBe null
                (viewModel.errorMessage.value) shouldBe ("Failed to save image: cache sync failed")
                (onErrorCalled) shouldBe (true)
            }
        }
    }

    init {
        test("clearError clears existing error message") {
            runTest {
                val viewModel = createViewModel()
                coEvery { deleteMemoUseCase(any()) } throws IllegalStateException("delete failed")

                viewModel.deleteMemo(sampleMemo())
                advanceUntilIdle()
                (viewModel.errorMessage.value) shouldBe ("Failed to delete memo: delete failed")

                viewModel.clearError()

                (viewModel.errorMessage.value) shouldBe null
            }
        }
    }

    private fun createViewModel(): SearchViewModel =
        SearchViewModel(
            memoUiCoordinator = MemoUiCoordinator(memoRepository),
            appConfigStateProvider =
                com.lomo.app.feature.common.AppConfigStateProvider(
                    AppConfigUiCoordinator(appConfigRepository),
                    CoroutineScope(SupervisorJob() + testDispatcher),
                ),
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
