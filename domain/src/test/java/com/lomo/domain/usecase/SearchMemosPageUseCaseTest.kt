/*
 * Behavior Contract:
 * - Unit under test: SearchMemosPageUseCase
 * - Owning layer: domain
 * - Priority tier: P0
 * - Capability: load bounded search results through the main-list query contract so search
 *   filtering, pinned priority, and selected sort order are applied before pagination.
 *
 * Scenarios:
 * - Given a content filter excludes many raw FTS hits, when the first search page is requested,
 *   then the use case performs one bounded main-list page load with that filter and never walks raw
 *   search pages.
 * - Given pinned or updated-time priority would move a later raw FTS hit into the first app page,
 *   when the first search page is requested, then the returned ids match the repository's globally
 *   ordered filtered page.
 *
 * Observable outcomes:
 * - Returned memo ids and recorded repository page/load calls.
 *
 * TDD proof:
 * - RED before this fix because SearchMemosPageUseCase looped over raw search pages,
 *   applied filters page-locally, and never called `getMainListPagingSource`.
 *
 * Excludes:
 * - FTS tokenization, Room SQL execution, app ViewModel debounce/loading state,
 *   and Compose rendering.
 */
package com.lomo.domain.usecase

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoListFilter
import com.lomo.domain.model.MemoQuerySpec
import com.lomo.domain.model.MemoSortOption
import com.lomo.domain.repository.MainListQueryRepository
import com.lomo.domain.testing.DomainFunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class SearchMemosPageUseCaseTest : DomainFunSpec() {
    init {
        test("given content filter excludes raw hits when page loads then one bounded main-list page supplies results") {
            val repository =
                FakeMainListQueryRepository(
                    mainListPage = listOf(memo(id = "todo", content = "- [ ] alpha task")),
                )
            val useCase = SearchMemosPageUseCase(repository)

            val results =
                useCase
                    .getPagingSource(
                        query = "alpha",
                        filter = MemoListFilter(hasTodo = true),
                    ).loadPage(loadSize = 1)

            results.map(Memo::id) shouldBe listOf("todo")
            repository.mainListCalls shouldBe
                listOf(FakeMainListQueryRepository.MainListCall(query = "alpha", filter = MemoListFilter(hasTodo = true)))
            repository.mainListLoads shouldBe
                listOf(FakeMainListQueryRepository.MainListLoad(key = 0, loadSize = 1))
        }

        test("given pinned updated result has global priority when page loads then global page order is returned") {
            val pinnedOlderUpdated =
                memo(
                    id = "pinned-old-updated",
                    content = "alpha pinned",
                    timestamp = 100L,
                    updatedAt = 900L,
                    isPinned = true,
                )
            val unpinnedNewest =
                memo(
                    id = "unpinned-newest",
                    content = "alpha new",
                    timestamp = 800L,
                    updatedAt = 800L,
                )
            val repository =
                FakeMainListQueryRepository(
                    mainListPage = listOf(pinnedOlderUpdated, unpinnedNewest),
                )
            val useCase = SearchMemosPageUseCase(repository)

            val results =
                useCase
                    .getPagingSource(
                        query = "alpha",
                        filter = MemoListFilter(sortOption = MemoSortOption.UPDATED_TIME),
                    ).loadPage(loadSize = 2)

            results.map(Memo::id) shouldBe listOf("pinned-old-updated", "unpinned-newest")
            repository.mainListCalls shouldBe
                listOf(
                    FakeMainListQueryRepository.MainListCall(
                        query = "alpha",
                        filter = MemoListFilter(sortOption = MemoSortOption.UPDATED_TIME),
                    ),
                )
            repository.mainListLoads shouldBe
                listOf(FakeMainListQueryRepository.MainListLoad(key = 0, loadSize = 2))
        }
    }

    private class FakeMainListQueryRepository(
        private val mainListPage: List<Memo>,
    ) : MainListQueryRepository {
        val mainListCalls = mutableListOf<MainListCall>()
        val mainListLoads = mutableListOf<MainListLoad>()

        override fun getMainListPagingSource(spec: MemoQuerySpec): PagingSource<Int, Memo> {
            mainListCalls += MainListCall(spec = spec)
            return RecordingPagingSource(rows = mainListPage, loads = mainListLoads)
        }

        override fun getMainListCountFlow(spec: MemoQuerySpec): Flow<Int> = flowOf(mainListPage.size)

        override suspend fun getDefaultMainListIndexInWindow(
            id: String,
            limit: Int,
        ): Int? =
            mainListPage.indexOfFirst { memo -> memo.id == id }.takeIf { index -> index >= 0 }

        override suspend fun getMemoById(id: String): Memo? = mainListPage.firstOrNull { memo -> memo.id == id }

        override fun isSyncing(): Flow<Boolean> = flowOf(false)

        data class MainListCall(
            val spec: MemoQuerySpec,
        ) {
            constructor(query: String, filter: MemoListFilter) : this(
                spec = MemoQuerySpec.fromFilter(queryText = query, filter = filter),
            )
        }

        data class MainListLoad(
            val key: Int?,
            val loadSize: Int,
        )
    }

    private class RecordingPagingSource(
        private val rows: List<Memo>,
        private val loads: MutableList<FakeMainListQueryRepository.MainListLoad>,
    ) : PagingSource<Int, Memo>() {
        override fun getRefreshKey(state: PagingState<Int, Memo>): Int? = state.anchorPosition

        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Memo> {
            loads += FakeMainListQueryRepository.MainListLoad(key = params.key, loadSize = params.loadSize)
            val start = (params.key ?: 0).coerceAtLeast(0)
            val end = (start + params.loadSize).coerceAtMost(rows.size)
            val data = if (start >= rows.size) emptyList() else rows.subList(start, end)
            return LoadResult.Page(
                data = data,
                prevKey = null,
                nextKey = if (end >= rows.size) null else end,
            )
        }
    }

    private suspend fun PagingSource<Int, Memo>.loadPage(
        key: Int? = 0,
        loadSize: Int,
    ): List<Memo> =
        when (
            val result =
                load(
                    PagingSource.LoadParams.Refresh(
                        key = key,
                        loadSize = loadSize,
                        placeholdersEnabled = false,
                    ),
                )
        ) {
            is PagingSource.LoadResult.Page -> result.data
            is PagingSource.LoadResult.Error -> throw result.throwable
            is PagingSource.LoadResult.Invalid -> error("PagingSource returned invalid result")
        }
}

private fun memo(
    id: String,
    content: String,
    timestamp: Long = 1L,
    updatedAt: Long = timestamp,
    isPinned: Boolean = false,
): Memo =
    Memo(
        id = id,
        timestamp = timestamp,
        updatedAt = updatedAt,
        content = content,
        rawContent = content,
        dateKey = "2026_03_24",
        isPinned = isPinned,
    )
