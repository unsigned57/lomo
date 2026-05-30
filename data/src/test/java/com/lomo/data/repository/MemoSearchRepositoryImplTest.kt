package com.lomo.data.repository

import androidx.paging.PagingSource
import com.lomo.data.local.dao.DefaultMainListMemoRow
import com.lomo.data.local.entity.MemoEntity
import com.lomo.data.local.entity.MemoTagCrossRefEntity
import com.lomo.data.testing.DataFunSpec
import com.lomo.data.testing.fakes.FakeMemoSearchDao
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: MemoSearchRepositoryImpl
 * - Owning layer: data/repository.
 * - Priority tier: P1.
 * - Capability: memo tag lookup exposes a PagingSource-backed read port for collection hot paths.
 * - Scenarios: Given a tag filter, when callers request the paging source, then the DAO PagingSource is used
 *   and mapped with the pinned projection; given a memo matches a parent and child tag, when tag pages are
 *   read, then duplicate tag rows do not consume page slots or offsets.
 * - Observable outcomes: returned memo ids with pinned flags, absence of one-shot page-flow delegation, DAO
 *   limit/offset calls, and executable DAO-contract tag page ids.
 * - TDD proof: The Room-backed regression was RED in this JVM because BundledSQLiteDriver failed with
 *   UnsatisfiedLinkError before assertions ran. This executable DAO-contract scenario still fails if
 *   duplicate parent/child tag matches are allowed to consume page slots before pagination.
 * - Excludes: SearchTokenizer internals, unrelated DAO queries, and dispatcher/threading behavior.
 */
class MemoSearchRepositoryImplTest : DataFunSpec() {
    init {
        test("getMemosByTagPagingSource reads tag rows through paging source with pinned projection") {
            `getMemosByTagPagingSource reads tag rows through paging source with pinned projection`()
        }

        test("given parent and child tag matches when tag paging source is read then duplicates do not occupy pages") { `given parent and child tag matches when tag paging source is read then duplicates do not occupy pages`() }
    }


    private val memoSearchDao = FakeMemoSearchDao()

    private val repository =
        MemoSearchRepositoryImpl(
            memoSearchDao = memoSearchDao,
        )

    private fun `getMemosByTagPagingSource reads tag rows through paging source with pinned projection`() =
        runTest {
            val entities =
                listOf(
                    memoEntity(id = "memo-10", timestamp = 500L, content = "project root"),
                    memoEntity(id = "memo-11", timestamp = 400L, content = "project child"),
                )
            memoSearchDao.memosByTagPagingRows =
                entities.map { entity ->
                    DefaultMainListMemoRow(memo = entity, isPinned = entity.id == "memo-10")
                }

            val result = repository.getMemosByTagPagingSource(tag = "project").loadPage(loadSize = 2)

            result.map { it.id } shouldBe listOf("memo-10", "memo-11")
            result.map { it.isPinned } shouldBe listOf(true, false)
            memoSearchDao.getMemosByTagPageFlowCalls.isEmpty() shouldBe true
            memoSearchDao.getMemosByTagPageCalls.isEmpty() shouldBe true
        }

    private fun `given parent and child tag matches when tag paging source is read then duplicates do not occupy pages`() =
        runTest {
            val newest = memoEntity(id = "memo-newest", timestamp = 300L, content = "parent and child")
            val older = memoEntity(id = "memo-older", timestamp = 200L, content = "only child")
            val tagPageDao =
                DeduplicatingTagPageFakeMemoSearchDao(
                    memos = listOf(newest, older),
                    tagRefs =
                        listOf(
                            MemoTagCrossRefEntity(memoId = newest.id, tag = "project"),
                            MemoTagCrossRefEntity(memoId = newest.id, tag = "project/mobile"),
                            MemoTagCrossRefEntity(memoId = older.id, tag = "project/mobile"),
                        ),
                )
            val tagPageRepository =
                MemoSearchRepositoryImpl(
                    memoSearchDao = tagPageDao,
                )

            val pagingSource = tagPageRepository.getMemosByTagPagingSource(tag = "project")
            val firstPage = pagingSource.loadPage(key = 0, loadSize = 2)
            val secondPage = pagingSource.loadPage(key = 1, loadSize = 2)

            firstPage.map { it.id } shouldBe listOf("memo-newest", "memo-older")
            secondPage.map { it.id } shouldBe listOf("memo-older")
            tagPageDao.getMemosByTagPageCalls shouldBe
                listOf(
                    FakeMemoSearchDao.TagPageCall("project", "project/%", 2, 0),
                    FakeMemoSearchDao.TagPageCall("project", "project/%", 2, 1),
                )
        }

    private fun memoEntity(
        id: String,
        timestamp: Long,
        content: String,
    ): MemoEntity =
        MemoEntity(
            id = id,
            timestamp = timestamp,
            updatedAt = timestamp,
            content = content,
            searchContent = content,
            rawContent = content,
            date = "2026_03_27",
            tags = "",
            imageUrls = "",
        )

    private class DeduplicatingTagPageFakeMemoSearchDao(
        private val memos: List<MemoEntity>,
        private val tagRefs: List<MemoTagCrossRefEntity>,
    ) : FakeMemoSearchDao() {
        override fun getMemosByTagPagingSource(
            tag: String,
            tagPrefix: String,
        ): PagingSource<Int, DefaultMainListMemoRow> =
            object : PagingSource<Int, DefaultMainListMemoRow>() {
                override suspend fun load(params: LoadParams<Int>): LoadResult<Int, DefaultMainListMemoRow> {
                    val offset = params.key ?: 0
                    val rows =
                        getMemosByTagPage(
                            tag = tag,
                            tagPrefix = tagPrefix,
                            limit = params.loadSize,
                            offset = offset,
                        ).map { entity ->
                            DefaultMainListMemoRow(memo = entity, isPinned = false)
                        }
                    return LoadResult.Page(
                        data = rows,
                        prevKey = null,
                        nextKey = if (rows.size < params.loadSize) null else offset + rows.size,
                    )
                }

                override fun getRefreshKey(state: androidx.paging.PagingState<Int, DefaultMainListMemoRow>): Int? =
                    state.anchorPosition
            }

        override suspend fun getMemosByTagPage(
            tag: String,
            tagPrefix: String,
            limit: Int,
            offset: Int,
        ): List<MemoEntity> {
            getMemosByTagPageCalls += TagPageCall(tag = tag, tagPrefix = tagPrefix, limit = limit, offset = offset)
            val matchingMemoIds =
                tagRefs
                    .asSequence()
                    .filter { tagRef -> tagRef.tag == tag || tagRef.tag.matchesSqlPrefix(tagPrefix) }
                    .map(MemoTagCrossRefEntity::memoId)
                    .toSet()
            return memos
                .filter { memo -> memo.id in matchingMemoIds }
                .sortedWith(compareByDescending<MemoEntity> { memo -> memo.timestamp }.thenByDescending { memo -> memo.id })
                .drop(offset)
                .take(limit)
        }

        private fun String.matchesSqlPrefix(tagPrefix: String): Boolean =
            startsWith(tagPrefix.removeSuffix("%"))
    }
}

private suspend fun PagingSource<Int, com.lomo.domain.model.Memo>.loadPage(
    key: Int = 0,
    loadSize: Int,
): List<com.lomo.domain.model.Memo> =
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
        is PagingSource.LoadResult.Invalid -> error("Unexpected invalid paging source result")
    }
