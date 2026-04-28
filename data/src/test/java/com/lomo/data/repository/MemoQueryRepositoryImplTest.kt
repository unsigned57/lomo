package com.lomo.data.repository

import com.lomo.data.local.dao.DefaultMainListDao
import com.lomo.data.local.dao.MemoBrowseDao
import com.lomo.data.local.dao.MemoDao
import com.lomo.data.local.dao.MemoPinDao
import com.lomo.data.local.dao.DefaultMainListMemoRow
import com.lomo.data.local.entity.MemoEntity
import androidx.paging.PagingSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/*
 * Test Contract:
 * - Unit under test: MemoQueryRepositoryImpl
 * - Behavior focus: pinned-state merge, invalid-page guard branch, getMemoById null/non-null branching, and syncing-state exposure.
 * - Observable outcomes: returned memo ids with isPinned flags, empty/non-empty page content, null vs domain memo, and passthrough sync state.
 * - Red phase: Not applicable - test-only coverage addition; no production change.
 * - Excludes: Room SQL behavior, entity recovery internals, and mutation workflow side effects.
 */
class MemoQueryRepositoryImplTest {
    private val memoDao: MemoDao = mockk()
    private val memoBrowseDao: MemoBrowseDao = mockk()
    private val defaultMainListDao: DefaultMainListDao = mockk()
    private val memoPinDao: MemoPinDao = mockk()
    private val synchronizer: MemoSynchronizer = mockk(relaxed = true)

    private val repository =
        MemoQueryRepositoryImpl(
            memoDao = memoDao,
            memoBrowseDao = memoBrowseDao,
            defaultMainListDao = defaultMainListDao,
            memoPinDao = memoPinDao,
            synchronizer = synchronizer,
        )

    @Test
    fun `getMemosPage returns empty list and skips dao for invalid limit or offset`() =
        runTest {
            val byInvalidLimit = repository.getMemosPage(limit = 0, offset = 0)
            val byInvalidOffset = repository.getMemosPage(limit = 10, offset = -1)

            assertTrue(byInvalidLimit.isEmpty())
            assertTrue(byInvalidOffset.isEmpty())
            coVerify(exactly = 0) { defaultMainListDao.getPage(any(), any()) }
            coVerify(exactly = 0) { memoPinDao.getPinnedMemoIds() }
        }

    @Test
    fun `getRecentMemos and getMemosPage merge pinned ids into domain output`() =
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
            coEvery { memoPinDao.getPinnedMemoIds() } returns listOf("memo-2")
            coEvery { memoDao.getRecentMemos(2) } returns recentEntities
            coEvery { defaultMainListDao.getPage(limit = 2, offset = 1) } returns pageEntities

            val recent = repository.getRecentMemos(limit = 2)
            val page = repository.getMemosPage(limit = 2, offset = 1)

            assertEquals(listOf("memo-3", "memo-2"), recent.map { it.id })
            assertEquals(listOf(false, true), recent.map { it.isPinned })
            assertEquals(listOf("memo-2", "memo-1"), page.map { it.id })
            assertEquals(listOf(true, false), page.map { it.isPinned })
        }

    @Test
    fun `getAllMemosList combines memo flow with pinned flow`() =
        runTest {
            val entities =
                listOf(
                    memoEntity(id = "memo-a", timestamp = 20L),
                    memoEntity(id = "memo-b", timestamp = 10L),
                )
            every { memoDao.getAllMemosFlow() } returns flowOf(entities)
            every { memoPinDao.getPinnedMemoIdsFlow() } returns flowOf(listOf("memo-b"))

            val all = repository.getAllMemosList().first()

            assertEquals(listOf("memo-a", "memo-b"), all.map { it.id })
            assertEquals(listOf(false, true), all.map { it.isPinned })
        }

    @Test
    fun `getMemosByDateRange and getGalleryMemosList merge pinned ids into domain output`() =
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
            every { memoBrowseDao.getMemosByTimestampRangeFlow(any(), any()) } returns flowOf(rangeEntities)
            every { memoBrowseDao.getGalleryMemosFlow() } returns flowOf(galleryEntities)
            every { memoPinDao.getPinnedMemoIdsFlow() } returns flowOf(listOf("memo-range-1", "memo-gallery-2"))

            val range =
                repository
                    .getMemosByDateRange(
                        startDate = LocalDate.of(2026, 3, 1),
                        endDate = LocalDate.of(2026, 3, 5),
                    ).first()
            val gallery = repository.getGalleryMemosList().first()

            assertEquals(listOf("memo-range-2", "memo-range-1"), range.map { it.id })
            assertEquals(listOf(false, true), range.map { it.isPinned })
            assertEquals(listOf("memo-gallery-2", "memo-gallery-1"), gallery.map { it.id })
            assertEquals(listOf(true, false), gallery.map { it.isPinned })
        }

    @Test
    fun `getMemoById returns null when dao misses and skips pinned lookup`() =
        runTest {
            coEvery { memoDao.getMemo("missing") } returns null

            val memo = repository.getMemoById("missing")

            assertNull(memo)
            coVerify(exactly = 0) { memoPinDao.getPinnedMemoIds() }
        }

    @Test
    fun `getMemoById returns mapped domain memo with pinned state when found`() =
        runTest {
            coEvery { memoDao.getMemo("memo-1") } returns memoEntity(id = "memo-1", timestamp = 123L)
            coEvery { memoPinDao.getPinnedMemoIds() } returns listOf("memo-1")

            val memo = repository.getMemoById("memo-1")

            requireNotNull(memo)
            assertEquals("memo-1", memo.id)
            assertTrue(memo.isPinned)
        }

    @Test
    fun `getMemoCount delegates to dao and isSyncing exposes synchronizer flow`() =
        runTest {
            val syncing = MutableStateFlow(false)
            coEvery { memoDao.getMemoCountSync() } returns 42
            every { synchronizer.isSyncing } returns syncing

            assertEquals(42, repository.getMemoCount())
            assertEquals(false, repository.isSyncing().first())

            syncing.value = true
            assertEquals(true, repository.isSyncing().first())
        }

    @Test
    fun `getDefaultMainListPagingSource keeps source refresh key for offset paging`() =
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
            coEvery { defaultMainListDao.getPagingSource() } returns source

            val pagingSource = repository.getDefaultMainListPagingSource()
            val refreshKey =
                pagingSource.getRefreshKey(
                    androidx.paging.PagingState(
                        pages = emptyList(),
                        anchorPosition = 90,
                        config = androidx.paging.PagingConfig(pageSize = 30),
                        leadingPlaceholderCount = 0,
                    ),
                )

            assertEquals(42, refreshKey)
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
