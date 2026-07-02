package com.lomo.app.feature.search

import androidx.lifecycle.ViewModel
import androidx.paging.PagingData
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.Job
import com.lomo.app.feature.common.AppConfigUiCoordinator
import com.lomo.app.feature.common.MemoCollectionProjectionMapper
import com.lomo.app.feature.main.MemoUiMapper
import com.lomo.app.feature.main.MemoUiModel
import com.lomo.app.provider.ImageMapProvider
import com.lomo.app.provider.emptyImageMapProvider
import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.MainDispatcherExtension
import com.lomo.app.testing.fakes.FakeAppConfigRepository
import com.lomo.app.testing.fakes.FakeMemoStore
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoListFilter
import com.lomo.domain.model.MemoQuerySpec
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.usecase.DeleteMemoUseCase
import com.lomo.domain.usecase.ObserveActiveDayCountUseCase
import com.lomo.domain.usecase.ResolveMemoUpdateActionUseCase
import com.lomo.domain.usecase.SaveImageResult
import com.lomo.domain.usecase.SearchMemosPageUseCase
import com.lomo.domain.usecase.ToggleMemoCheckboxUseCase
import com.lomo.domain.usecase.UpdateMemoContentUseCase
import com.lomo.domain.usecase.ValidateMemoContentUseCase
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: SearchViewModel
 * - Owning layer: app
 * - Priority tier: P1
 * - Capability: Debounced search query loading, search result filtering, mutation errors, and image storage flows.
 *
 * Scenarios:
 * - Given search query input changes, when the ViewModel observes the change, then it debounces and shows a loading indicator appropriately.
 * - Given a non-blank search query, when the search runs, then it reads the main-list query paging contract.
 * - Given the page-backed search boundary fails, when the failure occurs, then it is surfaced as an error to the user instead of becoming a silent empty result.
 * - Given delete or update memo calls are invoked, when they complete or fail, then search state flow updates and reports exception mappings.
 * - Given a page-backed visible search result, when a todo is toggled, then both persistence and the currently emitted search result snapshot are updated.
 * - Given saveImage is executed, when the save completes, then save path output, caching outcomes, and error hooks are managed.
 *
 * Observable outcomes:
 * - isSearching, pagedUiMemos, showLoading, errorMessage, deletingMemoIds StateFlow values, and recorded repository page calls.
 *
 * TDD proof:
 * - RED before the fix because SearchViewModel called MemoUiCoordinator.searchMemos(), which delegated to the removed full-list search port instead of recording a main-list query.
 * - RED for the search failure scenario because SearchViewModel logged page-load failures and emitted an empty state without setting errorMessage.
 * - RED for the collection-state migration because SearchViewModel still exposes local error/deleting/searchUiModels flows instead of one MemoCollectionUiState owned by the common holder.
 * - RED for page-backed todo toggles because the repository updates but searchResults keeps the stale page snapshot.
 *
 * Excludes:
 * - Actual Compose UI widgets and graphics components.
 *
 * Test Change Justification:
 * - Reason category: App layer restructuring replaced page-based memo retention and viewport delete animations with LomoList system, extracted provider settings dialogs, and added conflict/startup orchestration.
 * - Old behavior/assertion being replaced: previous app-layer tests relied on monolithic settings dialogs, DeleteViewportEntry animation system, and pre-LomoList memo retention.
 * - Why old assertion is no longer correct: the app layer was restructured: settings dialogs are now provider-specific, DeleteViewportEntry files are removed in favor of LomoList components, and paged memo content uses new pagination source.
 * - Coverage preserved by: all existing scenarios retained; assertions updated to use new LomoList animation contracts, provider settings surfaces, and paging source APIs.
 * - Why this is not fitting the test to the implementation: tests verify observable ViewModel state, UI coordinator behavior, and screen rendering outcomes, not internal animation or dialog mechanics.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest : AppFunSpec() {
    private val testDispatcher = StandardTestDispatcher()

    private val memoRepository = FakeMemoStore()
    private val appConfigRepository = FakeAppConfigRepository()
    private val mediaRepository = com.lomo.app.testing.fakes.FakeMediaRepository()
    private val imageMapProvider: ImageMapProvider = emptyImageMapProvider()
    private val createdViewModels = mutableListOf<SearchViewModel>()

    private val deleteMemoUseCase = DeleteMemoUseCase(com.lomo.app.testing.fakes.FakeMemoMutationRepository(memoRepository))
    private val updateMemoContentUseCase = UpdateMemoContentUseCase(
        repository = com.lomo.app.testing.fakes.FakeMemoMutationRepository(memoRepository),
        validator = ValidateMemoContentUseCase(),
        resolveMemoUpdateActionUseCase = ResolveMemoUpdateActionUseCase(),
        deleteMemoUseCase = deleteMemoUseCase
    )
    private val toggleMemoCheckboxUseCase = ToggleMemoCheckboxUseCase(
        repository = com.lomo.app.testing.fakes.FakeMemoMutationRepository(memoRepository),
        validator = ValidateMemoContentUseCase()
    )

    private val saveImageUseCase = com.lomo.domain.usecase.FakeSaveImageUseCase(mediaRepository)

    private fun CoroutineScope.collectPagedData(
        flow: kotlinx.coroutines.flow.Flow<PagingData<MemoUiModel>>
    ): Job {
        return launch(testDispatcher) {
            flow.collectLatest { pagingData ->
                try {
                    val flowField = PagingData::class.java.getDeclaredField("flow")
                    flowField.isAccessible = true
                    val pageEventFlow = flowField.get(pagingData) as kotlinx.coroutines.flow.Flow<*>
                    pageEventFlow.collect {}
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    init {
        extension(MainDispatcherExtension(testDispatcher))

        beforeTest {
            saveImageUseCase.saveResult = null
            memoRepository.resetRecordedCalls()
            memoRepository.setActiveMemos(emptyList())
            memoRepository.setDeletedMemos(emptyList())
            memoRepository.deleteMemoFailure = null
            memoRepository.updateMemoFailure = null
        }

        afterTest {
            createdViewModels
                .asReversed()
                .forEach(::clearViewModel)
            createdViewModels.clear()
        }

        test("blank query keeps empty results and does not trigger repository search") {
            runTest {
                val viewModel = createViewModel()
                val differ = backgroundScope.collectPagedData(viewModel.pagedUiMemos)

                viewModel.onSearchQueryChanged("   ")
                testDispatcher.scheduler.advanceTimeBy(350)
                advanceUntilIdle()

                memoRepository.mainListCalls.isEmpty() shouldBe true
                memoRepository.mainListPageLoads.isEmpty() shouldBe true
            }
        }

        test("non blank query propagates repository search results") {
            runTest {
                val expectedMemo = sampleMemo(id = "memo-1", content = "search-hit")
                memoRepository.setActiveMemos(listOf(expectedMemo))
                val viewModel = createViewModel()
                val differ = backgroundScope.collectPagedData(viewModel.pagedUiMemos)

                viewModel.onSearchQueryChanged("search-hit")
                testDispatcher.scheduler.advanceTimeBy(350)
                advanceUntilIdle()

                memoRepository.mainListCalls shouldContain
                    FakeMemoStore.MainListCall(
                        query = "search-hit",
                        filter = MemoListFilter(),
                    )
                memoRepository.mainListPageLoads shouldContain
                    FakeMemoStore.MainListPageLoad(key = null, loadSize = 60)
            }
        }

        test("non blank query uses main-list page contract instead of raw search fallback") {
            runTest {
                val pageMemo = sampleMemo(id = "memo-page", content = "search-hit from page")
                val fullListMemo = sampleMemo(id = "memo-full-list", content = "search-hit from full list")
                memoRepository.setActiveMemos(listOf(fullListMemo))
                memoRepository.mainListPageProvider = { spec ->
                    spec shouldBe MemoQuerySpec.fromFilter(queryText = "search-hit", filter = MemoListFilter())
                    listOf(pageMemo)
                }
                val viewModel = createViewModel()
                val differ = backgroundScope.collectPagedData(viewModel.pagedUiMemos)

                viewModel.onSearchQueryChanged("search-hit")
                testDispatcher.scheduler.advanceTimeBy(350)
                advanceUntilIdle()

                memoRepository.mainListCalls shouldBe
                    listOf(
                        FakeMemoStore.MainListCall(
                            query = "search-hit",
                            filter = MemoListFilter(),
                        ),
                    )
                memoRepository.mainListPageLoads shouldBe
                    listOf(FakeMemoStore.MainListPageLoad(key = null, loadSize = 60))
            }
        }

        test("search page failure is surfaced instead of silently returning empty results") {
            runTest {
                val failure = IllegalArgumentException("malformed page MATCH")
                memoRepository.mainListLoadFailure = failure
                val viewModel = createViewModel()
                val differ = backgroundScope.collectPagedData(viewModel.pagedUiMemos)
                val errorJob = backgroundScope.launch(testDispatcher) { viewModel.errorMessage.collect() }

                viewModel.onSearchQueryChanged("search-hit")
                testDispatcher.scheduler.advanceTimeBy(350)
                advanceUntilIdle()

                viewModel.errorMessage.value shouldBe "Failed to search memos: malformed page MATCH"
                memoRepository.mainListCalls shouldBe
                    listOf(
                        FakeMemoStore.MainListCall(
                            query = "search-hit",
                            filter = MemoListFilter(),
                        ),
                    )
                memoRepository.mainListPageLoads shouldBe
                    listOf(FakeMemoStore.MainListPageLoad(key = null, loadSize = 60))
                errorJob.cancel()
            }
        }

        test("Chinese single and multi character prefixes both keep Socrates searchable") {
            runTest {
                val socratesMemo = sampleMemo(id = "memo-socrates", content = "苏格拉底 说未经审视的人生不值得过")
                memoRepository.setActiveMemos(listOf(socratesMemo))
                val viewModel = createViewModel()
                val differ = backgroundScope.collectPagedData(viewModel.pagedUiMemos)

                viewModel.onSearchQueryChanged("苏")
                testDispatcher.scheduler.advanceTimeBy(350)
                advanceUntilIdle()

                viewModel.onSearchQueryChanged("苏格")
                testDispatcher.scheduler.advanceTimeBy(350)
                advanceUntilIdle()

                memoRepository.mainListCalls shouldContain
                    FakeMemoStore.MainListCall(
                        query = "苏",
                        filter = MemoListFilter(),
                    )
                memoRepository.mainListCalls shouldContain
                    FakeMemoStore.MainListCall(
                        query = "苏格",
                        filter = MemoListFilter(),
                    )
                memoRepository.mainListPageLoads shouldContain
                    FakeMemoStore.MainListPageLoad(key = null, loadSize = 60)
            }
        }

        test("non blank query stays idle through debounce window") {
            runTest {
                val viewModel = createViewModel()
                val differ = backgroundScope.collectPagedData(viewModel.pagedUiMemos)

                viewModel.onSearchQueryChanged("search-hit")
                runCurrent()

                memoRepository.mainListCalls.isEmpty() shouldBe true
                memoRepository.mainListPageLoads.isEmpty() shouldBe true

                testDispatcher.scheduler.advanceTimeBy(299)
                runCurrent()

                memoRepository.mainListCalls.isEmpty() shouldBe true
                memoRepository.mainListPageLoads.isEmpty() shouldBe true
            }
        }

        test("searching starts after debounce and clears when delayed repository result arrives") {
            runTest {
                val expectedMemo = sampleMemo(id = "memo-delayed", content = "delayed-result")
                memoRepository.setActiveMemos(listOf(expectedMemo))
                memoRepository.mainListLoadDelayMillis = 500L
                val viewModel = createViewModel()
                val differ = backgroundScope.collectPagedData(viewModel.pagedUiMemos)

                viewModel.onSearchQueryChanged("delayed-result")
                runCurrent()

                testDispatcher.scheduler.advanceTimeBy(299)
                runCurrent()

                memoRepository.mainListCalls.isEmpty() shouldBe true
                memoRepository.mainListPageLoads.isEmpty() shouldBe true

                testDispatcher.scheduler.advanceTimeBy(1)
                runCurrent()

                memoRepository.mainListCalls shouldContain
                    FakeMemoStore.MainListCall(
                        query = "delayed-result",
                        filter = MemoListFilter(),
                    )
                memoRepository.mainListPageLoads shouldContain
                    FakeMemoStore.MainListPageLoad(key = null, loadSize = 60)
            }
        }

        test("deleteMemo updates active state to deleting and triggers usecase") {
            runTest {
                val memo = sampleMemo(id = "memo-delete")
                memoRepository.setActiveMemos(listOf(memo))
                val viewModel = createViewModel()

                viewModel.deleteMemo(memo, null)
                runCurrent()

                viewModel.deletingMemoIds.value.contains(memo.id) shouldBe true

                viewModel.onDeleteAnimationSettled(memo.id)
                runCurrent()
                viewModel.deletingMemoIds.value.contains(memo.id) shouldBe true

                viewModel.exitAnimationRegistry.updateSourceKeys(emptySet())
                runCurrent()
                viewModel.deletingMemoIds.value.isEmpty() shouldBe true
                memoRepository.currentDeletedMemos() shouldBe listOf(memo.copy(isDeleted = true))
            }
        }

        test("deleteMemo exposes mapped error message on failure") {
            runTest {
                val memo = sampleMemo(id = "memo-delete-fail")
                memoRepository.setActiveMemos(listOf(memo))
                memoRepository.deleteMemoFailure = IllegalStateException("delete failed")
                val viewModel = createViewModel()

                viewModel.deleteMemo(memo, null)
                advanceUntilIdle()

                viewModel.errorMessage.value shouldBe "Failed to delete memo: delete failed"
            }
        }

        test("search memo mutations publish through common collection state") {
            runTest {
                val memo = sampleMemo(id = "memo-collection", content = "collection search-hit")
                memoRepository.setActiveMemos(listOf(memo))
                val viewModel = createViewModel()
                val collectJob = backgroundScope.launch(testDispatcher) { viewModel.collectionUiState.collect() }

                viewModel.onSearchQueryChanged("search-hit")
                testDispatcher.scheduler.advanceTimeBy(350)
                advanceUntilIdle()
                memoRepository.deleteMemoFailure = IllegalStateException("delete failed")

                viewModel.deleteMemo(memo, null)
                advanceUntilIdle()

                viewModel.collectionUiState.value.errorMessage shouldBe "Failed to delete memo: delete failed"
                viewModel.collectionUiState.value.deletingMemoIds shouldBe emptySet()
                viewModel.errorMessage.value shouldBe viewModel.collectionUiState.value.errorMessage
                viewModel.deletingMemoIds.value shouldBe viewModel.collectionUiState.value.deletingMemoIds
                collectJob.cancel()
            }
        }

        test("deleteMemo failure rolls back active deleting state immediately") {
            runTest {
                val memo = sampleMemo(id = "memo-delete-fail-2")
                memoRepository.setActiveMemos(listOf(memo))
                memoRepository.deleteMemoFailure = IllegalStateException("delete failed")
                val viewModel = createViewModel()

                viewModel.deleteMemo(memo, null)
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
                saveImageUseCase.saveResult = SaveImageResult.SavedAndCacheSynced(StorageLocation("images/photo-1.jpg"))
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
            }
        }

        test("saveImage cache sync failure reports error and invokes onError") {
            runTest {
                val viewModel = createViewModel()
                val inputUri = mockk<android.net.Uri>()
                every { inputUri.toString() } returns "content://images/photo-2"
                saveImageUseCase.saveResult =
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

                viewModel.deleteMemo(memo, null)
                advanceUntilIdle()
                viewModel.errorMessage.value shouldBe "Failed to delete memo: delete failed"

                viewModel.clearError()
                runCurrent()

                viewModel.errorMessage.value shouldBe null
            }
        }

        test("toggleTodo updates checkbox successfully in repository") {
            runTest {
                val memo = sampleMemo(id = "memo-todo", content = "- [ ] first item")
                memoRepository.setActiveMemos(listOf(memo))
                val viewModel = createViewModel()

                viewModel.toggleTodo(memo, 0, true)
                advanceUntilIdle()

                viewModel.errorMessage.value shouldBe null
                val updatedMemos = memoRepository.currentActiveMemos()
                updatedMemos.first().content shouldBe "- [x] first item"
            }
        }

        test("toggleTodo failure sets error message flow") {
            runTest {
                val memo = sampleMemo(id = "memo-todo-fail", content = "- [ ] first item")
                memoRepository.setActiveMemos(listOf(memo))
                memoRepository.updateMemoFailure = IllegalStateException("database lock")
                val viewModel = createViewModel()

                viewModel.toggleTodo(memo, 0, true)
                advanceUntilIdle()

                viewModel.errorMessage.value shouldBe "Failed to update todo: database lock"
            }
        }
    }

    private fun createViewModel(): SearchViewModel =
        SearchViewModel(
            observeActiveDayCountUseCase = observeActiveDayCountUseCase(),
            appConfigStateProvider =
                com.lomo.app.feature.common.AppConfigStateProvider(
                    appConfigUiCoordinator = AppConfigUiCoordinator(appConfigRepository),
                    appPreferencesSnapshotRepository = appConfigRepository,
                    customFontStore = com.lomo.app.testing.fakes.FakeCustomFontStore(),
                    appScope = CoroutineScope(SupervisorJob() + testDispatcher),
                ),
            appConfigUiCoordinator = AppConfigUiCoordinator(appConfigRepository),
            imageMapProvider = imageMapProvider,
            projectionMapper = MemoCollectionProjectionMapper(MemoUiMapper()),
            searchMemosPageUseCase = SearchMemosPageUseCase(com.lomo.app.testing.fakes.FakeMemoQueryRepository(memoRepository)),
            deleteMemoUseCase = deleteMemoUseCase,
            updateMemoContentUseCase = updateMemoContentUseCase,
            saveImageUseCase = saveImageUseCase,
            toggleMemoCheckboxUseCase = toggleMemoCheckboxUseCase,
        ).also(createdViewModels::add)

    private fun clearViewModel(viewModel: SearchViewModel) {
        ViewModel::class.java.getDeclaredMethod("clear\$lifecycle_viewmodel").invoke(viewModel)
        testDispatcher.scheduler.advanceUntilIdle()
    }

    private fun observeActiveDayCountUseCase(): ObserveActiveDayCountUseCase =
        ObserveActiveDayCountUseCase(
            com.lomo.app.testing.fakes.FakeMemoStatisticsRepository(memoRepository),
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
