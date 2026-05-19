package com.lomo.app.feature.search

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
import com.lomo.app.feature.main.MemoUiMapper
import com.lomo.app.provider.ImageMapProvider
import com.lomo.app.provider.emptyImageMapProvider
import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.MainDispatcherExtension
import com.lomo.app.testing.fakes.FakeAppConfigRepository
import com.lomo.app.testing.fakes.FakeMemoRepository
import com.lomo.domain.model.Memo
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.usecase.DeleteMemoUseCase
import com.lomo.domain.usecase.SaveImageResult
import com.lomo.domain.usecase.SaveImageUseCase
import com.lomo.domain.usecase.UpdateMemoContentUseCase
import com.lomo.domain.usecase.ValidateMemoContentUseCase
import com.lomo.domain.usecase.ResolveMemoUpdateActionUseCase
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Capability: Debounced search query loading, search result filtering, mutation errors, and image storage flows.
 * - Scenarios:
 *   - Given search query input changes, debounce and show loading indicator appropriately.
 *   - Given delete/update memo calls, update search state flow and report exception mappings.
 *   - Given saveImage execution, manage save path output, caching outcomes, and error hooks.
 * - Observable outcomes:
 *   - isSearching, searchResults, showLoading, and deletingMemoIds StateFlow values.
 * - TDD proof: Confirms exact debounce, loading timer, and state machine updates during search actions.
 * - Excludes: actual Compose UI widgets and graphics components.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest : AppFunSpec() {
    private val testDispatcher = StandardTestDispatcher()

    private val memoRepository = FakeMemoRepository()
    private val appConfigRepository = FakeAppConfigRepository()
    private val imageMapProvider: ImageMapProvider = emptyImageMapProvider()

    // Real UseCases built on FakeMemoRepository for a true Fake-First integration
    private val deleteMemoUseCase = DeleteMemoUseCase(memoRepository)
    private val updateMemoContentUseCase = UpdateMemoContentUseCase(
        repository = memoRepository,
        validator = ValidateMemoContentUseCase(),
        resolveMemoUpdateActionUseCase = ResolveMemoUpdateActionUseCase(),
        deleteMemoUseCase = deleteMemoUseCase
    )

    private val saveImageUseCase: SaveImageUseCase = mockk()

    init {
        extension(MainDispatcherExtension(testDispatcher))

        beforeTest {
            clearMocks(saveImageUseCase)
            memoRepository.setActiveMemos(emptyList())
            memoRepository.setDeletedMemos(emptyList())
            memoRepository.deleteMemoFailure = null
            memoRepository.updateMemoFailure = null
            memoRepository.searchQueriesCalled.clear()
            memoRepository.searchMemosListFlowOverride = null
        }

        test("blank query keeps empty results and does not trigger repository search") {
            runTest {
                val viewModel = createViewModel()
                val collectJob = backgroundScope.launch(testDispatcher) { viewModel.searchResults.collect() }

                viewModel.onSearchQueryChanged("   ")
                testDispatcher.scheduler.advanceTimeBy(350)
                advanceUntilIdle()

                viewModel.searchResults.value.isEmpty() shouldBe true
                memoRepository.searchQueriesCalled.isEmpty() shouldBe true
                collectJob.cancel()
            }
        }

        test("non blank query propagates repository search results") {
            runTest {
                val expectedMemo = sampleMemo(id = "memo-1", content = "search-hit")
                memoRepository.setActiveMemos(listOf(expectedMemo))
                val viewModel = createViewModel()
                val collectJob = backgroundScope.launch(testDispatcher) { viewModel.searchResults.collect() }

                viewModel.onSearchQueryChanged("search-hit")
                testDispatcher.scheduler.advanceTimeBy(350)
                val results = viewModel.searchResults.first { it.isNotEmpty() }

                results shouldBe listOf(expectedMemo)
                memoRepository.searchQueriesCalled shouldContain "search-hit"
                collectJob.cancel()
            }
        }

        test("Chinese single and multi character prefixes both keep Socrates searchable") {
            runTest {
                val socratesMemo = sampleMemo(id = "memo-socrates", content = "苏格拉底 说未经审视的人生不值得过")
                memoRepository.setActiveMemos(listOf(socratesMemo))
                val viewModel = createViewModel()
                val collectJob = backgroundScope.launch(testDispatcher) { viewModel.searchResults.collect() }

                viewModel.onSearchQueryChanged("苏")
                testDispatcher.scheduler.advanceTimeBy(350)
                advanceUntilIdle()
                viewModel.searchResults.value shouldBe listOf(socratesMemo)

                viewModel.onSearchQueryChanged("苏格")
                testDispatcher.scheduler.advanceTimeBy(350)
                advanceUntilIdle()

                viewModel.searchResults.value shouldBe listOf(socratesMemo)
                memoRepository.searchQueriesCalled shouldContain "苏"
                memoRepository.searchQueriesCalled shouldContain "苏格"
                collectJob.cancel()
            }
        }

        test("non blank query stays idle through debounce window") {
            runTest {
                val viewModel = createViewModel()
                val searchingJob = backgroundScope.launch(testDispatcher) { viewModel.isSearching.collect() }
                val resultsJob = backgroundScope.launch(testDispatcher) { viewModel.searchResults.collect() }

                viewModel.onSearchQueryChanged("search-hit")
                runCurrent()

                viewModel.isSearching.value shouldBe false
                memoRepository.searchQueriesCalled.isEmpty() shouldBe true

                testDispatcher.scheduler.advanceTimeBy(299)
                runCurrent()

                viewModel.isSearching.value shouldBe false
                memoRepository.searchQueriesCalled.isEmpty() shouldBe true
                searchingJob.cancel()
                resultsJob.cancel()
            }
        }

        test("searching starts after debounce and clears when delayed repository result arrives") {
            runTest {
                val expectedMemo = sampleMemo(id = "memo-delayed", content = "delayed-result")
                memoRepository.searchMemosListFlowOverride = { _ ->
                    flow {
                        delay(500)
                        emit(listOf(expectedMemo))
                    }
                }
                val viewModel = createViewModel()
                val searchingJob = backgroundScope.launch(testDispatcher) { viewModel.isSearching.collect() }
                val resultsJob = backgroundScope.launch(testDispatcher) { viewModel.searchResults.collect() }

                viewModel.onSearchQueryChanged("delayed-result")
                runCurrent()
                viewModel.isSearching.value shouldBe false

                testDispatcher.scheduler.advanceTimeBy(299)
                runCurrent()

                viewModel.isSearching.value shouldBe false
                memoRepository.searchQueriesCalled.isEmpty() shouldBe true

                testDispatcher.scheduler.advanceTimeBy(1)
                runCurrent()

                viewModel.isSearching.value shouldBe true
                memoRepository.searchQueriesCalled shouldContain "delayed-result"

                testDispatcher.scheduler.advanceTimeBy(500)
                runCurrent()

                viewModel.isSearching.value shouldBe false
                viewModel.searchResults.value shouldBe listOf(expectedMemo)
                searchingJob.cancel()
                resultsJob.cancel()
            }
        }

        test("fast search result never exposes loading indicator") {
            runTest {
                val expectedMemo = sampleMemo(id = "memo-fast", content = "fast-result")
                memoRepository.searchMemosListFlowOverride = { _ ->
                    flow {
                        delay(80)
                        emit(listOf(expectedMemo))
                    }
                }
                val viewModel = createViewModel()
                val loadingJob = backgroundScope.launch(testDispatcher) { viewModel.showLoading.collect() }
                val resultsJob = backgroundScope.launch(testDispatcher) { viewModel.searchResults.collect() }

                viewModel.onSearchQueryChanged("fast-result")
                runCurrent()

                testDispatcher.scheduler.advanceTimeBy(300)
                runCurrent()

                viewModel.showLoading.value shouldBe false

                testDispatcher.scheduler.advanceTimeBy(80)
                runCurrent()

                viewModel.showLoading.value shouldBe false
                viewModel.searchResults.value shouldBe listOf(expectedMemo)
                loadingJob.cancel()
                resultsJob.cancel()
            }
        }

        test("loading indicator remains visible for minimum duration after delayed result") {
            runTest {
                val expectedMemo = sampleMemo(id = "memo-min-visible", content = "min-visible")
                memoRepository.searchMemosListFlowOverride = { _ ->
                    flow {
                        delay(250)
                        emit(listOf(expectedMemo))
                    }
                }
                val viewModel = createViewModel()
                val loadingJob = backgroundScope.launch(testDispatcher) { viewModel.showLoading.collect() }
                val resultsJob = backgroundScope.launch(testDispatcher) { viewModel.searchResults.collect() }

                viewModel.onSearchQueryChanged("min-visible")
                runCurrent()

                testDispatcher.scheduler.advanceTimeBy(300) // Debounce completes
                runCurrent()

                viewModel.showLoading.value shouldBe false

                testDispatcher.scheduler.advanceTimeBy(120) // loadingShowDelay(120ms) has elapsed -> showLoading = true
                runCurrent()

                viewModel.showLoading.value shouldBe true

                testDispatcher.scheduler.advanceTimeBy(130) // Search finishes (250ms elapsed since debounce completed), shown for 130ms < 280ms
                runCurrent()

                viewModel.showLoading.value shouldBe true

                testDispatcher.scheduler.advanceTimeBy(150) // Remaining 150ms of min show time passes
                runCurrent()

                viewModel.showLoading.value shouldBe false
                viewModel.searchResults.value shouldBe listOf(expectedMemo)
                loadingJob.cancel()
                resultsJob.cancel()
            }
        }

        test("loading indicator remains hidden when results arrive exactly at loadingShowDelay threshold") {
            runTest {
                val expectedMemo = sampleMemo(id = "memo-threshold", content = "threshold-result")
                memoRepository.searchMemosListFlowOverride = { _ ->
                    flow {
                        delay(119)
                        emit(listOf(expectedMemo))
                    }
                }
                val viewModel = createViewModel()
                val loadingJob = backgroundScope.launch(testDispatcher) { viewModel.showLoading.collect() }
                val resultsJob = backgroundScope.launch(testDispatcher) { viewModel.searchResults.collect() }

                viewModel.onSearchQueryChanged("threshold-result")
                runCurrent()

                testDispatcher.scheduler.advanceTimeBy(300) // Debounce finishes
                runCurrent()

                viewModel.showLoading.value shouldBe false

                testDispatcher.scheduler.advanceTimeBy(119) // Results arrive before the 120ms threshold
                runCurrent()

                viewModel.showLoading.value shouldBe false
                viewModel.searchResults.value shouldBe listOf(expectedMemo)
                loadingJob.cancel()
                resultsJob.cancel()
            }
        }

        test("subsequent fast search after delayed one handles loading state separately") {
            runTest {
                val expectedMemo1 = sampleMemo(id = "memo-delayed-1", content = "delayed-1")
                val expectedMemo2 = sampleMemo(id = "memo-fast-2", content = "fast-2")
                memoRepository.searchMemosListFlowOverride = { query ->
                    flow {
                        if (query == "delayed-1") {
                            delay(500)
                            emit(listOf(expectedMemo1))
                        } else {
                            delay(80)
                            emit(listOf(expectedMemo2))
                        }
                    }
                }
                val viewModel = createViewModel()
                val loadingJob = backgroundScope.launch(testDispatcher) { viewModel.showLoading.collect() }
                val resultsJob = backgroundScope.launch(testDispatcher) { viewModel.searchResults.collect() }

                // 1. First search (delayed)
                viewModel.onSearchQueryChanged("delayed-1")
                testDispatcher.scheduler.advanceTimeBy(920) // Debounce (300) + delay (500) + min show loading (120)
                runCurrent()

                viewModel.searchResults.value shouldBe listOf(expectedMemo1)
                viewModel.showLoading.value shouldBe false

                // 2. Second search (fast)
                viewModel.onSearchQueryChanged("fast-2")
                testDispatcher.scheduler.advanceTimeBy(380) // Debounce (300) + delay (80)
                runCurrent()

                viewModel.searchResults.value shouldBe listOf(expectedMemo2)
                viewModel.showLoading.value shouldBe false
                loadingJob.cancel()
                resultsJob.cancel()
            }
        }

        test("deleteMemo updates active state to deleting and triggers usecase") {
            runTest {
                val memo = sampleMemo(id = "memo-delete")
                memoRepository.setActiveMemos(listOf(memo))
                val viewModel = createViewModel()

                viewModel.deleteMemo(memo)
                runCurrent()

                viewModel.deletingMemoIds.value.contains(memo.id) shouldBe true

                viewModel.onDeleteAnimationSettled(memo.id)

                viewModel.deletingMemoIds.value.isEmpty() shouldBe true
                memoRepository.getDeletedMemosList().first() shouldBe listOf(memo.copy(isDeleted = true))
            }
        }

        test("deleteMemo exposes mapped error message on failure") {
            runTest {
                val memo = sampleMemo(id = "memo-delete-fail")
                memoRepository.setActiveMemos(listOf(memo))
                memoRepository.deleteMemoFailure = IllegalStateException("delete failed")
                val viewModel = createViewModel()

                viewModel.deleteMemo(memo)
                advanceUntilIdle()

                viewModel.errorMessage.value shouldBe "Failed to delete memo: delete failed"
            }
        }

        test("deleteMemo failure rolls back active deleting state immediately") {
            runTest {
                val memo = sampleMemo(id = "memo-delete-fail-2")
                memoRepository.setActiveMemos(listOf(memo))
                memoRepository.deleteMemoFailure = IllegalStateException("delete failed")
                val viewModel = createViewModel()

                viewModel.deleteMemo(memo)
                runCurrent()

                viewModel.deletingMemoIds.value.isEmpty() shouldBe true
            }
        }

        test("updateMemo exposes mapped error message on failure") {
            runTest {
                val memo = sampleMemo(id = "memo-update")
                memoRepository.setActiveMemos(listOf(memo))
                memoRepository.updateMemoFailure = IllegalStateException("update failed")
                val viewModel = createViewModel()

                viewModel.updateMemo(memo, "updated")
                advanceUntilIdle()

                viewModel.errorMessage.value shouldBe "Failed to update memo: update failed"
            }
        }

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

                savedPath shouldBe "images/photo-1.jpg"
                viewModel.errorMessage.value shouldBe null
                onErrorCalled shouldBe false
                coVerify(exactly = 1) {
                    saveImageUseCase.saveWithCacheSyncStatus(StorageLocation("content://images/photo-1"))
                }
            }
        }

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

                savedPath shouldBe null
                viewModel.errorMessage.value shouldBe "Failed to save image: cache sync failed"
                onErrorCalled shouldBe true
            }
        }

        test("clearError clears existing error message") {
            runTest {
                val memo = sampleMemo(id = "memo-clear")
                memoRepository.setActiveMemos(listOf(memo))
                memoRepository.deleteMemoFailure = IllegalStateException("delete failed")
                val viewModel = createViewModel()

                viewModel.deleteMemo(memo)
                advanceUntilIdle()
                viewModel.errorMessage.value shouldBe "Failed to delete memo: delete failed"

                viewModel.clearError()

                viewModel.errorMessage.value shouldBe null
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
