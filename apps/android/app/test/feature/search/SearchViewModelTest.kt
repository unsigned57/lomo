package com.lomo.app.feature.search

import androidx.lifecycle.ViewModel
import androidx.paging.PagingData
import androidx.paging.PagingDataEvent
import androidx.paging.PagingDataPresenter
import com.lomo.app.feature.common.AppConfigUiCoordinator
import com.lomo.app.feature.common.MemoCollectionProjectionMapper
import com.lomo.app.feature.main.MainWorkspaceCoordinator
import com.lomo.app.feature.main.MemoUiModel
import com.lomo.app.provider.ImageMapProvider
import com.lomo.app.provider.emptyImageMapProvider
import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.MainDispatcherExtension
import com.lomo.app.testing.collectWhileSubscribed
import com.lomo.app.testing.fakes.FakeAppConfigRepository
import com.lomo.app.testing.fakes.FakeEngineDiagnosticsRecorder
import com.lomo.app.testing.fakes.FakeMemoStore
import com.lomo.app.testing.fakes.storeBackedToggleMemoCheckboxUseCase
import com.lomo.app.testing.fakes.testMemoUiMapper
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoListFilter
import com.lomo.domain.model.MemoQuerySpec
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.usecase.DeleteMemoUseCase
import com.lomo.domain.usecase.ObserveActiveDayCountUseCase
import com.lomo.domain.usecase.SaveImageResult
import com.lomo.domain.usecase.SearchMemosPageUseCase
import com.lomo.domain.usecase.UpdateMemoContentUseCase
import com.lomo.domain.usecase.ValidateMemoContentUseCase
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * Capability: Normalized search inputs avoid duplicate queries; clearing cancels the search generation immediately, while mutations and errors remain observable.
 * Owning layer / priority: SearchViewModel; app; P1.
 * Scenarios:
 * - Given the same trimmed query, when only outer whitespace changes, then the existing paging generation is reused.
 * - Given a nonblank search, when cleared, then an empty generation arrives without the 300ms debounce.
 * - Given a non-blank search query, when in the debounce window before results load, then isSearching remains true until results are emitted.
 * - Given query/mode/filter changes, when searched, then the requested result page and mode are delivered.
 * - Given mutation or paging failures, when encountered, then the user error state is emitted.
 * Observable outcomes: returned locations, emitted paging/map state, retained identities and surfaced errors.
 * TDD proof: target/lomo/reports/manual/media-toolbar-search/search-red.log: whitespace created 2 generations instead of 1; clear emitted no immediate generation; GREEN search-green.log: 21/21.
 * Excludes: physical device timing, Compose pixel styling and remote provider behavior.
 * Test Change Justification:
 * Reason category: regression coverage and platform fixture correction.
 * Old behavior/assertion being replaced: no existing result assertions are removed or weakened.
 * Why old assertion is no longer correct: Existing assertions are retained; the two new scenarios lock query work and clear latency.
 * Coverage preserved by: every existing scenario plus the explicit directory/query regressions.
 * Why this is not fitting the test to the implementation: expected results follow selected-directory identity and normalized-query behavior.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest : AppFunSpec() {
    private val testDispatcher = StandardTestDispatcher()

    private val memoRepository = FakeMemoStore()
    private val searchRepository = com.lomo.app.testing.fakes.FakeMemoSearchRepository(memoRepository)
    private val appConfigRepository = FakeAppConfigRepository()
    private val mediaRepository = com.lomo.app.testing.fakes.FakeMediaRepository()
    private val imageMapProvider: ImageMapProvider = emptyImageMapProvider()
    private val engineReadinessRepository = com.lomo.app.testing.fakes.FakeEngineReadinessRepository()
    private val createdViewModels = mutableListOf<SearchViewModel>()

    private val updateMemoContentUseCase = UpdateMemoContentUseCase(
        repository = com.lomo.app.testing.fakes.FakeMemoMutationRepository(memoRepository),
        validator = ValidateMemoContentUseCase(),
    )
    private val deleteMemoUseCase = DeleteMemoUseCase(
        com.lomo.app.testing.fakes.FakeMemoMutationRepository(memoRepository),
    )
    private val toggleMemoCheckboxUseCase = storeBackedToggleMemoCheckboxUseCase(memoRepository)

    private val saveImageUseCase = com.lomo.domain.usecase.FakeSaveImageUseCase(mediaRepository)
    private val loadEditableMemoUseCase =
        com.lomo.domain.usecase.LoadEditableMemoUseCase(
            com.lomo.app.testing.fakes.FakeMemoQueryRepository(memoRepository),
        )

    private fun CoroutineScope.collectPagedData(flow: kotlinx.coroutines.flow.Flow<PagingData<MemoUiModel>>): Job =
        launch(testDispatcher) {
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

    init {
        extension(MainDispatcherExtension(testDispatcher))

        beforeTest {
            saveImageUseCase.saveResult = null
            saveImageUseCase.saveException = null
            memoRepository.resetRecordedCalls()
            searchRepository.searchPagingCalls.clear()
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

        test("given the same normalized query when whitespace changes then the active search page is reused") {
            runTest {
                memoRepository.setActiveMemos(listOf(sampleMemo(id = "same-query", content = "search-hit")))
                val viewModel = createViewModel()
                var generations = 0
                backgroundScope.collectPagedData(viewModel.pagedUiMemos.onEach { generations++ })
                viewModel.onSearchQueryChanged("search-hit")
                testDispatcher.scheduler.advanceTimeBy(350)
                advanceUntilIdle()
                val before = generations
                viewModel.onSearchQueryChanged(" search-hit ")
                testDispatcher.scheduler.advanceTimeBy(350)
                advanceUntilIdle()
                generations shouldBe before
                memoRepository.mainListCalls.last().query shouldBe "search-hit"
            }
        }

        test("given visible search results when query is cleared then the empty generation arrives without debounce") {
            runTest {
                memoRepository.setActiveMemos(listOf(sampleMemo(id = "clear-query", content = "search-hit")))
                val viewModel = createViewModel()
                var generations = 0
                backgroundScope.collectPagedData(viewModel.pagedUiMemos.onEach { generations++ })
                viewModel.onSearchQueryChanged("search-hit")
                testDispatcher.scheduler.advanceTimeBy(350)
                advanceUntilIdle()
                val before = generations
                viewModel.onSearchQueryChanged("")
                runCurrent()
                generations shouldBe before + 1
            }
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

        test("search presents the first 60 results and preserves the remaining placeholder index") {
            runTest {
                val matches = List(61) { sampleMemo(id = "memo-$it", content = "search-hit $it") }
                memoRepository.mainListPageProvider = { matches }
                val viewModel = createViewModel()
                val presenter = object : PagingDataPresenter<MemoUiModel>(testDispatcher) {
                    override suspend fun presentPagingDataEvent(event: PagingDataEvent<MemoUiModel>) = Unit
                }
                backgroundScope.launch { viewModel.pagedUiMemos.collectLatest(presenter::collectFrom) }

                viewModel.onSearchQueryChanged("search-hit")
                testDispatcher.scheduler.advanceTimeBy(350)
                advanceUntilIdle()

                memoRepository.mainListCalls shouldContain
                    FakeMemoStore.MainListCall(
                        query = "search-hit",
                        filter = MemoListFilter(),
                    )
                memoRepository.mainListPageLoads shouldBe
                    listOf(FakeMemoStore.MainListPageLoad(key = null, loadSize = 60))
                presenter.snapshot().items.map { it.memo.id } shouldBe matches.take(60).map { it.id }
                presenter.snapshot().placeholdersAfter shouldBe 1
            }
        }

        test("search paging waits for mount admission instead of bare authority") {
            runTest {
                memoRepository.setActiveMemos(listOf(sampleMemo(id = "memo-1", content = "search-hit")))
                engineReadinessRepository.publishProjectionFreshness(
                    com.lomo.domain.model.ProjectionFreshness.Unavailable,
                )
                val viewModel = createViewModel()
                val differ = backgroundScope.collectPagedData(viewModel.pagedUiMemos)

                viewModel.onSearchQueryChanged("search-hit")
                testDispatcher.scheduler.advanceTimeBy(350)
                advanceUntilIdle()

                memoRepository.mainListCalls.isEmpty() shouldBe true

                engineReadinessRepository.publishProjectionFreshness(
                    com.lomo.domain.model.ProjectionFreshness.Verified(0uL),
                )
                advanceUntilIdle()

                memoRepository.mainListCalls shouldContain
                    FakeMemoStore.MainListCall(
                        query = "search-hit",
                        filter = MemoListFilter(),
                    )
                differ.cancel()
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

        test("toggling search mode requests fuzzy paging for the same query") {
            runTest {
                val hit = sampleMemo(id = "great-wall", content = "八达岭长城")
                memoRepository.setActiveMemos(listOf(hit))
                val viewModel = createViewModel()
                val differ = backgroundScope.collectPagedData(viewModel.pagedUiMemos)

                viewModel.onSearchQueryChanged("bdlcc")
                testDispatcher.scheduler.advanceTimeBy(350)
                advanceUntilIdle()

                viewModel.searchMode.value shouldBe com.lomo.domain.model.MemoSearchMode.Fulltext
                viewModel.onToggleSearchMode()
                advanceUntilIdle()

                viewModel.searchMode.value shouldBe com.lomo.domain.model.MemoSearchMode.Fuzzy
                searchRepository.searchPagingCalls.map { it.mode } shouldContain
                    com.lomo.domain.model.MemoSearchMode.Fuzzy
                searchRepository.searchPagingCalls.last() shouldBe
                    com.lomo.app.testing.fakes.FakeMemoSearchRepository.SearchPagingCall(
                        query = "bdlcc",
                        mode = com.lomo.domain.model.MemoSearchMode.Fuzzy,
                        filter = MemoListFilter(),
                    )
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

        test("non blank query marks isSearching true through debounce window until results load") {
            runTest {
                val expectedMemo = sampleMemo(id = "memo-1", content = "search-hit")
                memoRepository.setActiveMemos(listOf(expectedMemo))
                val viewModel = createViewModel()
                val differ = backgroundScope.collectPagedData(viewModel.pagedUiMemos)

                viewModel.isSearching.value shouldBe false

                viewModel.onSearchQueryChanged("search-hit")
                runCurrent()

                viewModel.isSearching.value shouldBe true

                testDispatcher.scheduler.advanceTimeBy(350)
                advanceUntilIdle()

                viewModel.isSearching.value shouldBe false
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
                val memo =
                    sampleMemo(id = "memo-update")
                        .copy(contentRevision = 1L, fileFingerprint = "fingerprint-1")
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
                    draftId = viewModel.ownerDraftId,
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
                saveImageUseCase.saveException = IllegalStateException("cache sync failed")
                var savedPath: String? = null
                var onErrorCalled = false

                viewModel.saveImage(
                    uri = inputUri,
                    draftId = viewModel.ownerDraftId,
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

                viewModel.toggleTodo(memo, com.lomo.domain.model.markdown.MarkdownSourceSpan(0u, 3u))
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

                viewModel.toggleTodo(memo, com.lomo.domain.model.markdown.MarkdownSourceSpan(0u, 3u))
                advanceUntilIdle()

                viewModel.errorMessage.value shouldBe "Failed to update todo: database lock"
            }
        }
    }

    private fun TestScope.createViewModel(): SearchViewModel = SearchViewModel(
        SearchViewModelDependencies(
            diagnostics = FakeEngineDiagnosticsRecorder(),
            observeActiveDayCountUseCase = observeActiveDayCountUseCase(),
            appConfigStateProvider =
            com.lomo.app.feature.common.AppConfigStateProvider(
                workspaceSession = com.lomo.domain.usecase.ObserveWorkspaceSessionUseCase(engineReadinessRepository),
                appConfigUiCoordinator = AppConfigUiCoordinator(appConfigRepository),
                appPreferencesSnapshotRepository = appConfigRepository,
                customFontStore = com.lomo.app.testing.fakes.FakeCustomFontStore(),
                customFontHost = com.lomo.app.testing.fakes.testCustomFontHost(
                    com.lomo.app.testing.fakes.FakeCustomFontStore(),
                ),
                preferencesHealthRepository = com.lomo.app.testing.fakes.FakePreferencesHealthRepository(),
                appScope = CoroutineScope(SupervisorJob() + testDispatcher),
            ),
            appConfigUiCoordinator = AppConfigUiCoordinator(appConfigRepository),
            imageMapProvider = imageMapProvider,
            projectionMapper = MemoCollectionProjectionMapper(
                testMemoUiMapper(
                    dispatcherProvider = com.lomo.domain.usecase.SingleDispatcherProvider(testDispatcher),
                ),
            ),
            searchMemosPageUseCase = SearchMemosPageUseCase(searchRepository),
            deleteMemoUseCase = deleteMemoUseCase,
            updateMemoContentUseCase = updateMemoContentUseCase,
            saveImageUseCase = saveImageUseCase,
            toggleMemoCheckboxUseCase = toggleMemoCheckboxUseCase,
            loadEditableMemoUseCase = loadEditableMemoUseCase,
            workspaceCoordinator = mockk<MainWorkspaceCoordinator> {
                every { mount } returns engineReadinessRepository.mount
            },
        ),
    ).also { viewModel ->
        createdViewModels.add(viewModel)
        collectWhileSubscribed(viewModel.deletingMemoIds)
        collectWhileSubscribed(viewModel.isSearching)
    }

    private fun clearViewModel(viewModel: SearchViewModel) {
        ViewModel::class.java.getDeclaredMethod("clear\$lifecycle_viewmodel").invoke(viewModel)
        testDispatcher.scheduler.advanceUntilIdle()
    }

    private fun observeActiveDayCountUseCase(): ObserveActiveDayCountUseCase = ObserveActiveDayCountUseCase(
        com.lomo.app.testing.fakes.FakeMemoStatisticsRepository(memoRepository),
    )

    private fun sampleMemo(id: String = "memo-0", content: String = "content"): Memo = Memo(
        id = id,
        timestamp = 1L,
        content = content,
        rawContent = "- 10:00 $content",
        dateKey = "2026_03_24",
        contentRevision = 1L,
        fileFingerprint = "fp-$id",
        projectedCharCount = "- 10:00 $content".length.toLong(),
    )
}
