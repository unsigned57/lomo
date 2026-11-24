package com.lomo.app.feature.gallery

import androidx.paging.PagingDataEvent
import androidx.paging.PagingDataPresenter
import com.lomo.app.feature.main.MainMemoListStateHolder
import com.lomo.app.feature.main.MainMemoListStateHolderDependencies
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
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * Capability: mosaic access drives the gallery pager across old pages;
 * owning layer: app presentation; priority: P1.
 * Scenarios:
 * - Given 100 image memos and a band reordered by geometry, when displayed, then demand reaches
 *   the greatest source index and the next page becomes available.
 * - Given repeated access through all five pages, when the last band displays, then the oldest memo loads.
 * Observable outcomes: actual PagingDataPresenter snapshots and oldest memo identity.
 * TDD proof: reported-bugs/paging-red.log fails with only 20 of the expected 40 memos;
 * paging-green.log verifies all 100 memos through real Paging access hints.
 * Excludes: Compose layout execution and Android storage throughput.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GalleryPagingContinuationTest : AppFunSpec() {
    private val mainDispatcher = MainDispatcherExtension()

    init {
        extensions(mainDispatcher)

        test("given reordered mosaic bands when scrolling through pages then the oldest image memo is reachable") {
            runTest(mainDispatcher.testDispatcher) {
                val memos = (0 until 100).map { index ->
                    Memo(
                        id = "memo-$index",
                        timestamp = index.toLong(),
                        content = "memo $index",
                        rawContent = "memo $index",
                        dateKey = "2026_10_24",
                        imageUrls = listOf("/workspace/images/photo-$index.jpg"),
                    )
                }
                val store = FakeMemoStore(initialActive = memos)
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
                val presenter = object : PagingDataPresenter<com.lomo.app.feature.gallery.GalleryUiItem>(
                    mainDispatcher.testDispatcher,
                ) {
                    override suspend fun presentPagingDataEvent(
                        event: PagingDataEvent<com.lomo.app.feature.gallery.GalleryUiItem>,
                    ) = Unit
                }
                val collection = launch { holder.galleryPagedUiItems.collectLatest(presenter::collectFrom) }
                try {
                    advanceUntilIdle()

                    presenter.snapshot().items.map { it.memoId } shouldBe memos.take(20).map { it.id }
                    for (expectedCount in listOf(40, 60, 80, 100)) {
                        val loaded = presenter.snapshot().items
                        val indices = loaded.withIndex().associate { it.value.memoId to it.index }
                        // The most recent source index need not be the last tile geometrically.
                        val band = GalleryMosaicLayout(
                            tiles = listOf(loaded.last(), loaded.first()).map { ui ->
                                GalleryMosaicTile(
                                    ui.memoId,
                                    ui.imageUrls.first(),
                                    GalleryAspectKind.Square,
                                    1f,
                                    0,
                                    0,
                                    4,
                                    4,
                                )
                            }.toImmutableList(),
                            rowCount = 4,
                        )
                        presenter[galleryBandPagingIndex(band, indices)]
                        advanceUntilIdle()
                        presenter.snapshot().items.map { it.memoId } shouldBe memos.take(expectedCount).map { it.id }
                    }
                    presenter.snapshot().items.last().memoId shouldBe "memo-99"
                } finally {
                    collection.cancel()
                }
            }
        }

        test("collecting gallery pages resolves image URLs with zero Markdown render calls") {
            runTest(mainDispatcher.testDispatcher) {
                val memos = (0 until 40).map { index ->
                    Memo(
                        id = "gallery-$index",
                        timestamp = index.toLong(),
                        content = "#tag **markdown $index**",
                        rawContent = "#tag **markdown $index**",
                        dateKey = "2026_10_24",
                        imageUrls = listOf("images/photo-$index.jpg", "https://cdn.example.com/$index.jpg"),
                    )
                }
                val store = FakeMemoStore(initialActive = memos)
                val query = FakeMemoQueryRepository(store)
                var renderCalls = 0
                val workspace = com.lomo.app.testing.fakes.FakeMarkdownWorkspaceRepository(
                    onRender = { renderCalls += 1 },
                )
                val mapper = testMemoUiMapper(
                    workspace = workspace,
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
                val presenter = object : PagingDataPresenter<GalleryUiItem>(mainDispatcher.testDispatcher) {
                    override suspend fun presentPagingDataEvent(event: PagingDataEvent<GalleryUiItem>) = Unit
                }
                val collection = launch { holder.galleryPagedUiItems.collectLatest(presenter::collectFrom) }
                try {
                    advanceUntilIdle()
                    presenter.snapshot().items.first().imageUrls shouldBe listOf(
                        "/workspace/images/photo-0.jpg",
                        "https://cdn.example.com/0.jpg",
                    )
                    presenter[35]
                    advanceUntilIdle()
                    presenter.snapshot().items.size shouldBe 40
                    renderCalls shouldBe 0
                    mapper.cacheStats().size shouldBe 0
                } finally {
                    collection.cancel()
                }
            }
        }
    }
}
