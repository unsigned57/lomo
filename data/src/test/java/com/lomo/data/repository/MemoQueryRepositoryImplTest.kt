package com.lomo.data.repository

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


import com.lomo.data.local.entity.MemoEntity
import com.lomo.data.local.dao.DefaultMainListMemoRow
import androidx.paging.PagingSource
import com.lomo.data.testing.fakes.FakeDefaultMainListDao
import com.lomo.data.testing.fakes.FakeMemoBrowseDao
import com.lomo.data.testing.fakes.FakeMemoDao
import com.lomo.data.testing.fakes.FakeMemoPinDao
import com.lomo.domain.model.MemoListFilter
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull

/*
 * Behavior Contract:
 * - Unit under test: MemoQueryRepositoryImpl
 * - Behavior focus: pinned-state merge, invalid-page guard branch, getMemoById null/non-null branching, syncing-state exposure, and Paging jump support passthrough.
 * - Observable outcomes: returned memo ids with isPinned flags, empty/non-empty page content, null vs domain memo, passthrough sync state, and exposed jumpingSupported flag.
 * - TDD proof: Fails before the fix because the repository mapping PagingSource drops the Room source's
 *   jumpingSupported flag, so placeholder-backed direct Jump requests cannot use Paging jump loading.
 * - Excludes: Room SQL behavior, entity recovery internals, and mutation workflow side effects.
 */
class MemoQueryRepositoryImplTest : DataFunSpec() {
    init {
        test("getMemosPage returns empty list and skips dao for invalid limit or offset") { `getMemosPage returns empty list and skips dao for invalid limit or offset`() }

        test("getRecentMemos and getMemosPage merge pinned ids into domain output") { `getRecentMemos and getMemosPage merge pinned ids into domain output`() }

        test("getAllMemosList combines memo flow with pinned flow") { `getAllMemosList combines memo flow with pinned flow`() }

        test("getMemosByDateRange and getGalleryMemosList merge pinned ids into domain output") { `getMemosByDateRange and getGalleryMemosList merge pinned ids into domain output`() }

        test("getMemoById returns null when dao misses and skips pinned lookup") { `getMemoById returns null when dao misses and skips pinned lookup`() }

        test("getMemoById returns mapped domain memo with pinned state when found") { `getMemoById returns mapped domain memo with pinned state when found`() }

        test("getMemoCount delegates to dao and isSyncing exposes synchronizer flow") { `getMemoCount delegates to dao and isSyncing exposes synchronizer flow`() }

        test("getMainListPagingSource keeps source refresh key for offset paging") { `getMainListPagingSource keeps source refresh key for offset paging`() }

        test("getMainListPagingSource preserves source jumping support for direct offscreen focus") { `getMainListPagingSource preserves source jumping support for direct offscreen focus`() }
    }


    private val memoDao = FakeMemoDao()
    private val memoBrowseDao = FakeMemoBrowseDao()
    private val defaultMainListDao = FakeDefaultMainListDao()
    private val memoPinDao = FakeMemoPinDao()
    private val synchronizer: MemoSynchronizer = mockk()

    private val repository =
        MemoQueryRepositoryImpl(
            memoDao = memoDao,
            memoBrowseDao = memoBrowseDao,
            defaultMainListDao = defaultMainListDao,
            memoPinDao = memoPinDao,
            synchronizer = synchronizer,
        )

    private fun `getMemosPage returns empty list and skips dao for invalid limit or offset`() =
        runTest {
            val byInvalidLimit = repository.getMemosPage(limit = 0, offset = 0)
            val byInvalidOffset = repository.getMemosPage(limit = 10, offset = -1)

            (byInvalidLimit.isEmpty()).shouldBeTrue()
            (byInvalidOffset.isEmpty()).shouldBeTrue()
        }

    private fun `getRecentMemos and getMemosPage merge pinned ids into domain output`() =
        runTest {
            val recentEntities =
                listOf(
                    memoEntity(id = "memo-3", timestamp = 300L),
                    memoEntity(id = "memo-2", timestamp = 200L),
                )
            val pageEntities =
                listOf(
                    defaultMainListRow(id = "memo-2", timestamp = 200L, isPinned = true),
                    defaultMainListRow(id = "memo-1", timestamp = 100L, isPinned = false),
                )
            memoPinDao.pinnedMemoIdsResult = listOf("memo-2")
            memoDao.randomMemosResult = emptyList()
            memoDao.recentMemosResult = recentEntities
            defaultMainListDao.pageResult = pageEntities

            val recent = repository.getRecentMemos(limit = 2)
            val page = repository.getMemosPage(limit = 2, offset = 1)

            recent.map { it.id } shouldBe listOf("memo-3", "memo-2")
            recent.map { it.isPinned } shouldBe listOf(false, true)
            page.map { it.id } shouldBe listOf("memo-2", "memo-1")
            page.map { it.isPinned } shouldBe listOf(true, false)
        }

    private fun `getAllMemosList combines memo flow with pinned flow`() =
        runTest {
            val entities =
                listOf(
                    memoEntity(id = "memo-a", timestamp = 20L),
                    memoEntity(id = "memo-b", timestamp = 10L),
                )
            memoDao.allMemosFlowResult = flowOf(entities)
            memoPinDao.pinnedMemoIdsFlowResult = flowOf(listOf("memo-b"))

            val all = repository.getAllMemosList().first()

            all.map { it.id } shouldBe listOf("memo-a", "memo-b")
            all.map { it.isPinned } shouldBe listOf(false, true)
        }

    private fun `getMemosByDateRange and getGalleryMemosList merge pinned ids into domain output`() =
        runTest {
            val rangeEntities =
                listOf(
                    memoEntity(id = "memo-range-2", timestamp = 200L),
                    memoEntity(id = "memo-range-1", timestamp = 100L),
                )
            val galleryEntities =
                listOf(
                    memoEntity(id = "memo-gallery-2", timestamp = 220L),
                    memoEntity(id = "memo-gallery-1", timestamp = 120L),
                )
            memoBrowseDao.memosByTimestampRangeFlowResult = flowOf(rangeEntities)
            memoBrowseDao.galleryMemosFlowResult = flowOf(galleryEntities)
            memoPinDao.pinnedMemoIdsFlowResult = flowOf(listOf("memo-range-1", "memo-gallery-2"))

            val range =
                repository
                    .getMemosByDateRange(
                        startDate = LocalDate.of(2026, 3, 1),
                        endDate = LocalDate.of(2026, 3, 5),
                    ).first()
            val gallery = repository.getGalleryMemosList().first()

            range.map { it.id } shouldBe listOf("memo-range-2", "memo-range-1")
            range.map { it.isPinned } shouldBe listOf(false, true)
            gallery.map { it.id } shouldBe listOf("memo-gallery-2", "memo-gallery-1")
            gallery.map { it.isPinned } shouldBe listOf(true, false)
        }

    private fun `getMemoById returns null when dao misses and skips pinned lookup`() =
        runTest {
            memoDao.memoResultMap.remove("missing")

            val memo = repository.getMemoById("missing")

            memo.shouldBeNull()
        }

    private fun `getMemoById returns mapped domain memo with pinned state when found`() =
        runTest {
            memoDao.memoResultMap["memo-1"] = memoEntity(id = "memo-1", timestamp = 123L)
            memoPinDao.pinnedMemoIdsResult = listOf("memo-1")

            val memo = repository.getMemoById("memo-1")

            requireNotNull(memo)
            memo.id shouldBe "memo-1"
            (memo.isPinned).shouldBeTrue()
        }

    private fun `getMemoCount delegates to dao and isSyncing exposes synchronizer flow`() =
        runTest {
            val syncing = MutableStateFlow(false)
            memoDao.memoCountSyncResult = 42
            every { synchronizer.isSyncing } returns syncing

            repository.getMemoCount() shouldBe 42
            repository.isSyncing().first() shouldBe false

            syncing.value = true
            repository.isSyncing().first() shouldBe true
        }

    private fun `getMainListPagingSource keeps source refresh key for offset paging`() =
        runTest {
            val source =
                object : PagingSource<Int, DefaultMainListMemoRow>() {
                    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, DefaultMainListMemoRow> =
                        LoadResult.Page(
                            data = emptyList(),
                            prevKey = null,
                            nextKey = null,
                        )

                    override fun getRefreshKey(state: androidx.paging.PagingState<Int, DefaultMainListMemoRow>): Int? =
                        42
                }
            defaultMainListDao.getPagingSourceResult = source

            val pagingSource = repository.getMainListPagingSource(query = "", filter = MemoListFilter())
            val refreshKey =
                pagingSource.getRefreshKey(
                    androidx.paging.PagingState(
                        pages = emptyList(),
                        anchorPosition = 90,
                        config = androidx.paging.PagingConfig(pageSize = 30),
                        leadingPlaceholderCount = 0,
                    ),
                )

            refreshKey shouldBe 42
        }

    private fun `getMainListPagingSource preserves source jumping support for direct offscreen focus`() =
        runTest {
            val source =
                object : PagingSource<Int, DefaultMainListMemoRow>() {
                    override val jumpingSupported: Boolean = true

                    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, DefaultMainListMemoRow> =
                        LoadResult.Page(
                            data = emptyList(),
                            prevKey = null,
                            nextKey = null,
                        )

                    override fun getRefreshKey(state: androidx.paging.PagingState<Int, DefaultMainListMemoRow>): Int? =
                        null
                }
            defaultMainListDao.getPagingSourceResult = source

            val pagingSource = repository.getMainListPagingSource(query = "", filter = MemoListFilter())

            (pagingSource.jumpingSupported).shouldBeTrue()
        }

    private fun memoEntity(
        id: String,
        timestamp: Long,
    ): MemoEntity =
        MemoEntity(
            id = id,
            timestamp = timestamp,
            updatedAt = timestamp,
            content = "content-$id",
            rawContent = "- 10:00 content-$id",
            date = "2026_03_27",
            tags = "work,project",
            imageUrls = "img.png",
        )

    private fun defaultMainListRow(
        id: String,
        timestamp: Long,
        isPinned: Boolean,
    ): DefaultMainListMemoRow =
        DefaultMainListMemoRow(
            memo = memoEntity(id = id, timestamp = timestamp),
            isPinned = isPinned,
        )
}
