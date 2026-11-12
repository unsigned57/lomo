package com.lomo.app.feature.gallery

import android.net.Uri
import androidx.paging.PagingDataEvent
import androidx.paging.PagingDataPresenter
import com.lomo.app.feature.main.MainMemoListStateHolder
import com.lomo.app.feature.main.MainMemoListStateHolderDependencies
import com.lomo.app.feature.main.MemoUiModel
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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * Capability: loaded gallery rows follow media locations without restarting; owner: app; P1.
 * Scenarios:
 * - Given a presented image memo, when inventory resolves its location and promotion replaces
 *   its stage URI, then the same presenter shows each new URI without recreating the source.
 * - Given a second presenter after navigation, when it collects, then it receives the final URI.
 * - Given a remote image in the same memo, when local locations change, then its URL stays present.
 * Observable outcomes: real PagingDataPresenter snapshots and paging source creation count.
 * TDD proof: target/lomo/reports/manual/gallery-media-refresh/paging-red.log and paging-green.log.
 * Excludes: Coil decoding, network availability and physical Android 11 startup.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GalleryMediaRefreshTest : AppFunSpec() {
    private val mainDispatcher = MainDispatcherExtension()

    init {
        extensions(mainDispatcher)

        test("given presented images when media locations change then gallery remaps and can be reopened") {
            runTest(mainDispatcher.testDispatcher) {
                val destination = "images/photo.jpg"
                val remote = "https://images.example/recent.jpg"
                val memo = Memo(
                    id = "photo-memo",
                    timestamp = 1L,
                    content = "photo",
                    rawContent = "photo",
                    dateKey = "2026_11_09",
                    imageUrls = listOf(destination, remote),
                )
                val store = FakeMemoStore(initialActive = listOf(memo))
                val query = FakeMemoQueryRepository(store)
                val locations = MutableStateFlow<Map<String, Uri>>(emptyMap())
                val holder = MainMemoListStateHolder(
                    MainMemoListStateHolderDependencies(
                        scope = backgroundScope,
                        mainMemoListQueryUseCase = MainMemoListQueryUseCase(query, query),
                        memoUiMapper = testMemoUiMapper(
                            dispatcherProvider = SingleDispatcherProvider(mainDispatcher.testDispatcher),
                        ),
                        searchQuery = MutableStateFlow(""),
                        memoListFilter = MutableStateFlow(MemoListFilter()),
                        mount = FakeEngineReadinessRepository().mount,
                        rootDirectory = MutableStateFlow(null),
                        imageDirectory = MutableStateFlow(null),
                        imageMap = locations,
                    ),
                )
                val presenter = object : PagingDataPresenter<MemoUiModel>(mainDispatcher.testDispatcher) {
                    override suspend fun presentPagingDataEvent(event: PagingDataEvent<MemoUiModel>) = Unit
                }
                var collection = backgroundScope.launch {
                    holder.galleryPagedUiMemos.collectLatest(presenter::collectFrom)
                }
                advanceUntilIdle()
                presenter.snapshot().items.single().imageUrls shouldBe listOf(destination, remote)

                for (uri in listOf("file:///stage/photo.jpg", "content://provider/final-photo.jpg")) {
                    val location = mockk<Uri>()
                    every { location.toString() } returns uri
                    locations.value = mapOf(destination to location)
                    advanceUntilIdle()
                    presenter.snapshot().items.single().imageUrls shouldBe listOf(uri, remote)
                    store.galleryPagingSourceCallCount shouldBe 1
                }
                collection.cancel()
                val reopened = object : PagingDataPresenter<MemoUiModel>(mainDispatcher.testDispatcher) {
                    override suspend fun presentPagingDataEvent(event: PagingDataEvent<MemoUiModel>) = Unit
                }
                collection = backgroundScope.launch {
                    holder.galleryPagedUiMemos.collectLatest(reopened::collectFrom)
                }
                advanceUntilIdle()
                reopened.snapshot().items.single().imageUrls shouldBe listOf(
                    "content://provider/final-photo.jpg",
                    remote,
                )
                collection.cancel()
            }
        }
    }
}
