package com.lomo.app.feature.main

import androidx.paging.PagingDataEvent
import androidx.paging.PagingDataPresenter
import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.MainDispatcherExtension
import com.lomo.app.testing.fakes.FakeEngineReadinessRepository
import com.lomo.app.testing.fakes.FakeMemoQueryRepository
import com.lomo.app.testing.fakes.FakeMemoStore
import com.lomo.app.testing.fakes.testMemoUiMapper
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoListFilter
import com.lomo.domain.usecase.MainMemoListQueryUseCase
import com.lomo.domain.usecase.SingleDispatcherProvider
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: MainMemoListStateHolder's live Paging pipeline; owning layer: app; P1.
 * - Capability: first-screen work is bounded to one page; continuation still fills absolute indexes.
 * Scenarios:
 * - Given 100 memos, when the main list first presents, then only 20 memos are queried and mapped,
 *   while placeholders retain the complete 100-row index space.
 * - Given access near the loaded page's end, when prefetch runs, then the next page arrives in order.
 * - Observable outcomes: real presenter snapshots, repository page sizes and continuation results.
 * - TDD proof: RED initially presents/maps 60 rows before the first item can be shown.
 * - Excludes: Compose frame timing and native database throughput (device benchmark owns those).
 * Test Change Justification:
 * - Reason category: realistic placeholder evidence in the shared fake source.
 * - Old behavior/assertion being replaced: the fake omitted known before/after counts.
 * - Why old assertion is no longer correct: production main-list pages supply those counts.
 * - Coverage preserved by: asserting the 100-row index space before and after continuation.
 * - Why this is not fitting the test to the implementation: the full index-space assertion
 *   exposed the fake's missing metadata; the expected result remains unchanged.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MainStartupPagingTest : AppFunSpec() {
    private val mainDispatcher = MainDispatcherExtension()

    init {
        extensions(mainDispatcher)

        test(
            "given a populated workspace when the first page presents then startup maps one page and scrolling continues",
        ) {
            runTest(mainDispatcher.testDispatcher) {
                val memos = (0 until 100).map { index ->
                    Memo(
                        id = "memo-$index",
                        timestamp = index.toLong(),
                        content = "memo $index",
                        rawContent = "memo $index",
                        dateKey = "2026_10_24",
                    )
                }
                val store = FakeMemoStore().apply { setMainListPagingSource(memos) }
                val query = FakeMemoQueryRepository(store)
                val mapper = testMemoUiMapper(
                    dispatcherProvider = SingleDispatcherProvider(mainDispatcher.testDispatcher),
                )
                val holder = MainMemoListStateHolder(
                    MainMemoListStateHolderDependencies(
                        scope = backgroundScope,
                        mainMemoListQueryUseCase = MainMemoListQueryUseCase(query, query),
                        memoUiMapper = mapper,
                        searchQuery = MutableStateFlow(""),
                        memoListFilter = MutableStateFlow(MemoListFilter()),
                        mount = FakeEngineReadinessRepository().mount,
                        rootDirectory = MutableStateFlow("/workspace"),
                        imageDirectory = MutableStateFlow(null),
                        imageMap = MutableStateFlow(emptyMap()),
                    ),
                )
                val presenter = object : PagingDataPresenter<MemoUiModel>(mainDispatcher.testDispatcher) {
                    override suspend fun presentPagingDataEvent(event: PagingDataEvent<MemoUiModel>) = Unit
                }
                val collection = launch { holder.pagedUiMemos.collectLatest(presenter::collectFrom) }
                try {
                    advanceUntilIdle()

                    presenter.snapshot().items.map { it.memo.id } shouldBe memos.take(20).map { it.id }
                    presenter.size shouldBe 100
                    store.mainListPageLoads.single().loadSize shouldBe 20
                    mapper.cacheStats().size shouldBe 20

                    presenter[19]?.memo?.id shouldBe "memo-19"
                    advanceUntilIdle()

                    presenter.snapshot().items.map { it.memo.id } shouldBe memos.take(40).map { it.id }
                    presenter.size shouldBe 100
                    store.mainListPageLoads.map { it.loadSize } shouldBe listOf(20, 20)
                } finally {
                    collection.cancel()
                }
            }
        }
    }
}
