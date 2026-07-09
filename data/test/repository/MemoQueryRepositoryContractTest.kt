package com.lomo.data.repository

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.lomo.data.local.dao.DefaultMainListMemoRow
import com.lomo.data.local.entity.MemoEntity
import com.lomo.data.testing.DataFunSpec
import com.lomo.data.testing.fakes.FakeDefaultMainListDao
import com.lomo.data.testing.fakes.FakeMemoBrowseDao
import com.lomo.data.testing.fakes.FakeMemoDao
import com.lomo.data.testing.fakes.FakeMemoPinDao
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoListFilter
import com.lomo.domain.model.MemoQuerySpec
import com.lomo.domain.model.MemoSortOption
import com.lomo.domain.repository.MemoQueryRepository
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import java.time.LocalDate

/*
 * Behavior Contract:
 * - Unit under test: MemoQueryRepository and MemoQueryRepositoryImpl main-list query contract.
 * - Owning layer: domain contract with data repository implementation.
 * - Priority tier: P0
 * - Capability: memo query implementations must provide explicit bounded query behavior without
 *   interface or implementation full-list fallbacks.
 *
 * Scenarios:
 * - Given a memo query implementation, when callers request a page, then no interface full-list
 *   fallback can satisfy the call.
 * - Given a memo query implementation, when callers request a main-list index, then no interface
 *   full-list fallback can satisfy the call.
 * - Given a memo query implementation, when callers request one memo by id, then no interface
 *   full-list fallback can satisfy the call.
 * - Given future test fakes or production repositories omit these query methods, when the code
 *   compiles, then the omission is rejected by the interface contract.
 * - Given a nonblank main-list search query has no searchable FTS tokens, when paging and count
 *   are requested, then the repository returns empty page/count without delegating a blank query
 *   that would return the full main list.
 *
 * Observable outcomes:
 * - Compiled interface default-method metadata does not contain getMemosPage,
 *   getDefaultMainListIndex, or getMemoById fallback methods.
 * - Main-list paging data, count emission, and recorded DAO query calls for empty-token search.
 *
 * TDD proof:
 * - RED: fails before the fix because MemoQueryRepository.DefaultImpls still contains full-list fallback methods.
 * - RED: fails before the empty-token query fix because `!!!` is normalized to a blank DAO query,
 *   so the fake DAO returns a full-list row/count.
 *
 * Excludes:
 * - Room SQL execution, tokenizer internals beyond no-token classification, UI rendering, and
 *   repository facade split work.
 */
class MemoQueryRepositoryContractTest : DataFunSpec() {
    init {
        test("given explicit memo query contract when compiled then interface exposes no full-list fallback defaults") {
            val defaultMethodNames =
                runCatching {
                    Class
                        .forName("${MemoQueryRepository::class.java.name}\$DefaultImpls")
                        .declaredMethods
                        .map { method -> method.name }
                }.getOrDefault(emptyList())

            defaultMethodNames shouldNotContain "getMemosPage"
            defaultMethodNames shouldNotContain "getDefaultMainListIndex"
            defaultMethodNames shouldNotContain "getMemoById"
        }

        test("given nonblank query has no searchable tokens when main list is queried then full-list fallback is impossible") {
            val defaultMainListDao = RecordingDefaultMainListDao()
            val repository =
                MemoQueryRepositoryImpl(
                    memoDao = FakeMemoDao(),
                    memoBrowseDao = FakeMemoBrowseDao(),
                    defaultMainListDao = defaultMainListDao,
                    memoPinDao = FakeMemoPinDao(),
                    synchronizer = mockk(),
                )
            defaultMainListDao.getPagingSourceResult =
                object : PagingSource<Int, DefaultMainListMemoRow>() {
                    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, DefaultMainListMemoRow> =
                        LoadResult.Page(
                            data = listOf(defaultMainListRow(id = "full-list-row", timestamp = 100L, isPinned = false)),
                            prevKey = null,
                            nextKey = null,
                        )

                    override fun getRefreshKey(state: PagingState<Int, DefaultMainListMemoRow>): Int? = null
                }
            defaultMainListDao.getCountFlowResult = flowOf(99)

            runTest {
                val page =
                    repository
                        .getMainListPagingSource(spec = MemoQuerySpec.fromFilter(queryText = "!!!", filter = MemoListFilter()))
                        .load(
                            PagingSource.LoadParams.Refresh(
                                key = null,
                                loadSize = 10,
                                placeholdersEnabled = false,
                            ),
                        )
                val count =
                    repository
                        .getMainListCountFlow(spec = MemoQuerySpec.fromFilter(queryText = "!!!", filter = MemoListFilter()))
                        .first()

                page.ids().shouldBeEmpty()
                count shouldBe 0
                defaultMainListDao.mainListPagingCalls.shouldBeEmpty()
                defaultMainListDao.mainListCountCalls.shouldBeEmpty()
            }
        }

        test("given canonical query spec when main list is queried then DAO receives projected-column filters") {
            val defaultMainListDao = RecordingDefaultMainListDao()
            val repository =
                MemoQueryRepositoryImpl(
                    memoDao = FakeMemoDao(),
                    memoBrowseDao = FakeMemoBrowseDao(),
                    defaultMainListDao = defaultMainListDao,
                    memoPinDao = FakeMemoPinDao(),
                    synchronizer = mockk(),
                )
            val spec =
                MemoQuerySpec.fromFilter(
                    queryText = " alpha ",
                    filter =
                        MemoListFilter(
                            sortOption = MemoSortOption.UPDATED_TIME,
                            sortAscending = true,
                            startDate = LocalDate.of(2026, 1, 4),
                            endDate = LocalDate.of(2026, 1, 2),
                            hasTodo = true,
                            hasAttachment = false,
                            hasUrl = true,
                        ),
                )

            runTest {
                repository.getMainListPagingSource(spec)
                repository.getMainListCountFlow(spec).first()
            }

            val expectedCall =
                MainListDaoCall(
                    query = "\"alpha\"*",
                    startDate = "2026_01_02",
                    endDate = "2026_01_04",
                    sortOption = "UPDATED_TIME",
                    sortAscending = true,
                    hasTodo = true,
                    hasAttachment = false,
                    hasUrl = true,
                )
            defaultMainListDao.mainListPagingCalls shouldBe listOf(expectedCall)
            defaultMainListDao.mainListCountCalls shouldBe listOf(expectedCall)
        }
    }
}

private data class MainListDaoCall(
    val query: String,
    val startDate: String?,
    val endDate: String?,
    val sortOption: String,
    val sortAscending: Boolean,
    val hasTodo: Boolean?,
    val hasAttachment: Boolean?,
    val hasUrl: Boolean?,
)

private class RecordingDefaultMainListDao : FakeDefaultMainListDao() {
    val mainListPagingCalls = mutableListOf<MainListDaoCall>()
    val mainListCountCalls = mutableListOf<MainListDaoCall>()

    override fun getPagingSource(
        query: String,
        startDate: String?,
        endDate: String?,
        sortOption: String,
        sortAscending: Boolean,
        hasTodo: Boolean?,
        hasAttachment: Boolean?,
        hasUrl: Boolean?,
    ): PagingSource<Int, DefaultMainListMemoRow> {
        mainListPagingCalls +=
            MainListDaoCall(
                query = query,
                startDate = startDate,
                endDate = endDate,
                sortOption = sortOption,
                sortAscending = sortAscending,
                hasTodo = hasTodo,
                hasAttachment = hasAttachment,
                hasUrl = hasUrl,
            )
        return super.getPagingSource(query, startDate, endDate, sortOption, sortAscending, hasTodo, hasAttachment, hasUrl)
    }

    override fun getCountFlow(
        query: String,
        startDate: String?,
        endDate: String?,
        sortOption: String,
        sortAscending: Boolean,
        hasTodo: Boolean?,
        hasAttachment: Boolean?,
        hasUrl: Boolean?,
    ): Flow<Int> {
        mainListCountCalls +=
            MainListDaoCall(
                query = query,
                startDate = startDate,
                endDate = endDate,
                sortOption = sortOption,
                sortAscending = sortAscending,
                hasTodo = hasTodo,
                hasAttachment = hasAttachment,
                hasUrl = hasUrl,
            )
        return super.getCountFlow(query, startDate, endDate, sortOption, sortAscending, hasTodo, hasAttachment, hasUrl)
    }
}

private fun defaultMainListRow(
    id: String,
    timestamp: Long,
    isPinned: Boolean,
): DefaultMainListMemoRow =
    DefaultMainListMemoRow(
        memo =
            MemoEntity(
                id = id,
                timestamp = timestamp,
                updatedAt = timestamp,
                content = "content-$id",
                searchContent = "content-$id",
                rawContent = "- 10:00 content-$id",
                date = "2026_03_27",
                tags = "",
                imageUrls = "",
            ),
        isPinned = isPinned,
    )

private fun PagingSource.LoadResult<Int, Memo>.ids(): List<String> =
    when (this) {
        is PagingSource.LoadResult.Page -> data.map { memo -> memo.id }
        is PagingSource.LoadResult.Error -> throw throwable
        is PagingSource.LoadResult.Invalid -> error("Unexpected invalid paging result")
    }
