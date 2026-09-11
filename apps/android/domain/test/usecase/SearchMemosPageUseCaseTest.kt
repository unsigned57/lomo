/*
 * Behavior Contract:
 * - Unit under test: SearchMemosPageUseCase
 * - Owning layer: domain
 * - Priority tier: P0
 * - Capability: load bounded search results through MemoSearchRepository so fulltext keeps
 *   list filters and fuzzy mode is a distinct retrieval contract.
 *
 * Scenarios:
 * - Given a content filter excludes many raw hits, when the first fulltext search page is requested,
 *   then the use case performs one bounded search page load with that filter and Fulltext mode.
 * - Given pinned or updated-time priority would move a later hit into the first app page,
 *   when the first fulltext search page is requested, then the returned ids match the repository's
 *   globally ordered filtered page.
 * - Given a non-blank query, when fuzzy mode is requested, then the search repository is called
 *   with Fuzzy and the same normalized text.
 * - Given a blank query, when a page is requested, then the repository is not called.
 *
 * Observable outcomes:
 * - Returned memo ids and recorded repository search calls (query, mode, filter, load).
 *
 * TDD proof:
 * - Target: ./kotlin test --include-module=domain --include-classes='com.lomo.domain.usecase.SearchMemosPageUseCaseTest'
 * - RED before this cutover because SearchMemosPageUseCase called MainListQueryRepository and had
 *   no search mode.
 *
 * Excludes:
 * - FTS tokenization, pinyin scoring, Room/SQLite, app ViewModel debounce/loading state,
 *   and Compose rendering.
 */
package com.lomo.domain.usecase

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoListFilter
import com.lomo.domain.model.MemoSearchMode
import com.lomo.domain.model.MemoSortOption
import com.lomo.domain.model.TagSelection
import com.lomo.domain.repository.MemoSearchRepository
import com.lomo.domain.testing.DomainFunSpec
import io.kotest.matchers.shouldBe

class SearchMemosPageUseCaseTest : DomainFunSpec() {
    init {
        test("given content filter excludes raw hits when page loads then one bounded fulltext page supplies results") {
            val repository =
                FakeSearchRepository(
                    searchPage = listOf(memo(id = "todo", content = "- [ ] alpha task")),
                )
            val useCase = SearchMemosPageUseCase(repository)

            val results =
                useCase
                    .getPagingSource(
                        query = "alpha",
                        filter = MemoListFilter(hasTodo = true),
                    ).loadPage(loadSize = 1)

            results.map(Memo::id) shouldBe listOf("todo")
            repository.searchCalls shouldBe
                listOf(
                    FakeSearchRepository.SearchCall(
                        query = "alpha",
                        mode = MemoSearchMode.Fulltext,
                        filter = MemoListFilter(hasTodo = true),
                    ),
                )
            repository.searchLoads shouldBe
                listOf(FakeSearchRepository.SearchLoad(key = null, loadSize = 1))
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
                FakeSearchRepository(
                    searchPage = listOf(pinnedOlderUpdated, unpinnedNewest),
                )
            val useCase = SearchMemosPageUseCase(repository)

            val results =
                useCase
                    .getPagingSource(
                        query = "alpha",
                        filter = MemoListFilter(sortOption = MemoSortOption.UPDATED_TIME),
                    ).loadPage(loadSize = 2)

            results.map(Memo::id) shouldBe listOf("pinned-old-updated", "unpinned-newest")
            repository.searchCalls shouldBe
                listOf(
                    FakeSearchRepository.SearchCall(
                        query = "alpha",
                        mode = MemoSearchMode.Fulltext,
                        filter = MemoListFilter(sortOption = MemoSortOption.UPDATED_TIME),
                    ),
                )
        }

        test("given pinyin fuzzy mode when page loads then search repository is called with Fuzzy") {
            val hit = memo(id = "great-wall", content = "八达岭长城")
            val repository = FakeSearchRepository(searchPage = listOf(hit))
            val useCase = SearchMemosPageUseCase(repository)

            val results =
                useCase
                    .getPagingSource(
                        query = "  bdlcc  ",
                        filter = MemoListFilter(),
                        mode = MemoSearchMode.Fuzzy,
                    ).loadPage(loadSize = 20)

            results.map(Memo::id) shouldBe listOf("great-wall")
            repository.searchCalls shouldBe
                listOf(
                    FakeSearchRepository.SearchCall(
                        query = "bdlcc",
                        mode = MemoSearchMode.Fuzzy,
                        filter = MemoListFilter(),
                    ),
                )
        }

        test("given blank query when page is requested then repository is not called") {
            val repository = FakeSearchRepository(searchPage = listOf(memo(id = "hidden", content = "x")))
            val useCase = SearchMemosPageUseCase(repository)

            val results = useCase.getPagingSource(query = "   ", filter = MemoListFilter()).loadPage(loadSize = 20)

            results shouldBe emptyList()
            repository.searchCalls shouldBe emptyList()
        }
    }

    private class FakeSearchRepository(
        private val searchPage: List<Memo>,
    ) : MemoSearchRepository {
        val searchCalls = mutableListOf<SearchCall>()
        val searchLoads = mutableListOf<SearchLoad>()

        override fun getMemosByTagPagingSource(selection: TagSelection): PagingSource<String, Memo> =
            error("tag paging is not under test")

        override fun searchPagingSource(
            query: String,
            mode: MemoSearchMode,
            filter: MemoListFilter,
        ): PagingSource<String, Memo> {
            searchCalls += SearchCall(query = query, mode = mode, filter = filter)
            return RecordingPagingSource(rows = searchPage, loads = searchLoads)
        }

        data class SearchCall(
            val query: String,
            val mode: MemoSearchMode,
            val filter: MemoListFilter,
        )

        data class SearchLoad(
            val key: String?,
            val loadSize: Int,
        )
    }

    private class RecordingPagingSource(
        private val rows: List<Memo>,
        private val loads: MutableList<FakeSearchRepository.SearchLoad>,
    ) : PagingSource<String, Memo>() {
        override fun getRefreshKey(state: PagingState<String, Memo>): String? = null

        override suspend fun load(params: LoadParams<String>): LoadResult<String, Memo> {
            loads += FakeSearchRepository.SearchLoad(key = params.key, loadSize = params.loadSize)
            val start = decodeCursor(params.key)
            val end = (start + params.loadSize).coerceAtMost(rows.size)
            val data = if (start >= rows.size) emptyList() else rows.subList(start, end)
            return LoadResult.Page(
                data = data,
                prevKey = null,
                nextKey = if (end >= rows.size) null else encodeCursor(end),
            )
        }
    }

    private suspend fun PagingSource<String, Memo>.loadPage(
        key: String? = null,
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

private fun encodeCursor(offset: Int): String = "fake-search-cursor:$offset"

private fun decodeCursor(cursor: String?): Int =
    cursor?.removePrefix("fake-search-cursor:")?.toIntOrNull()?.coerceAtLeast(0)
        ?: 0

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
