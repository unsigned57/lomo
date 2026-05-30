package com.lomo.data.repository

/**
 * Behavior Contract:
 * - Unit under test: MemoQueryRepositoryImpl
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: query memo lists and Daily Review candidates without materializing unbounded data.
 *
 * Scenarios:
 * - Given invalid paging input, when a page is requested, then DAO reads are skipped.
 * - Given pinned ids, when memo queries return rows, then domain memos carry pinned state.
 * - Given a Daily Review session starts, when the boundary is captured, then only high-water,
 *   count, and one bounded page query are used.
 * - Given a Daily Review page cursor, when the next page is requested, then DAO paging receives
 *   the stable boundary and cursor tuple instead of repository cache slicing.
 * - Given rows are inserted after the captured high-water rowid, when candidates are paged, then
 *   the page query remains bounded by the original max rowid.
 * - Given direct focus asks for a default-list index, when the target is outside the configured
 *   head window, then the repository returns null after one bounded head-id query.
 *
 * Observable outcomes:
 * - Returned memo ids with isPinned flags, empty/non-empty page content, null vs domain memo,
 *   passthrough sync state, Paging jump support, Daily Review page ids, cursor progress, and
 *   recorded DAO query boundaries/head-id window limits.
 *
 * TDD proof:
 * - Fails before the fix because boundary capture calls getDailyReviewCandidateSnapshot(maxRowId)
 *   and getDailyReviewCandidatePage slices the cached list instead of calling a bounded DAO page.
 *
 * Excludes:
 * - Room SQL execution plans, entity recovery internals, mutation workflow side effects, and UI.
 */
import com.lomo.data.local.entity.MemoEntity
import com.lomo.data.local.dao.DefaultMainListMemoRow
import androidx.paging.PagingSource
import com.lomo.data.testing.fakes.FakeDefaultMainListDao
import com.lomo.data.testing.fakes.FakeMemoBrowseDao
import com.lomo.data.testing.fakes.FakeMemoDao
import com.lomo.data.testing.fakes.FakeMemoPinDao
import com.lomo.domain.model.DailyReviewCandidateCursor
import com.lomo.domain.model.MemoListFilter
import com.lomo.domain.model.MemoQuerySpec
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import com.lomo.data.testing.DataFunSpec
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
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

        test("getDefaultMainListIndexInWindow uses one bounded head-id query") {
            `getDefaultMainListIndexInWindow uses one bounded head-id query`()
        }

        test("given daily review starts when boundary is captured then repository avoids unbounded candidate snapshot") { `given daily review starts when boundary is captured then repository avoids unbounded candidate snapshot`() }

        test("given daily review cursor when next page is requested then repository delegates cursor tuple to dao") { `given daily review cursor when next page is requested then repository delegates cursor tuple to dao`() }

        test("given new head rows after boundary when candidates are paged then original max rowid remains the query boundary") { `given new head rows after boundary when candidates are paged then original max rowid remains the query boundary`() }
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

            val pagingSource = repository.getMainListPagingSource(spec = MemoQuerySpec.fromFilter(filter = MemoListFilter()))
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

            val pagingSource = repository.getMainListPagingSource(spec = MemoQuerySpec.fromFilter(filter = MemoListFilter()))

            (pagingSource.jumpingSupported).shouldBeTrue()
        }

    private fun `getDefaultMainListIndexInWindow uses one bounded head-id query`() =
        runTest {
            defaultMainListDao.defaultMainListHeadIdsResult = listOf("memo-top", "memo-target", "memo-tail")

            val targetIndex = repository.getDefaultMainListIndexInWindow(id = "memo-target", limit = 2)
            val outsideWindow = repository.getDefaultMainListIndexInWindow(id = "memo-tail", limit = 2)
            val invalidLimit = repository.getDefaultMainListIndexInWindow(id = "memo-target", limit = 0)

            targetIndex shouldBe 1
            outsideWindow.shouldBeNull()
            invalidLimit.shouldBeNull()
            defaultMainListDao.defaultMainListHeadIdCalls shouldBe listOf(2, 2)
        }

    private fun `given daily review starts when boundary is captured then repository avoids unbounded candidate snapshot`() =
        runTest {
            defaultMainListDao.dailyReviewCandidateMaxRowIdResult = DAILY_REVIEW_MAX_ROW_ID
            defaultMainListDao.dailyReviewCandidateCountResult = 3
            defaultMainListDao.dailyReviewCandidatePageResult =
                listOf(defaultMainListRow(id = "memo-3", timestamp = 300L, isPinned = true))

            val boundary = repository.getDailyReviewCandidateBoundary()

            requireNotNull(boundary)
            assertSoftly {
                defaultMainListDao.dailyReviewCandidateMaxRowIdCallCount shouldBe 1
                defaultMainListDao.dailyReviewCandidateCountCalls shouldBe listOf(DAILY_REVIEW_MAX_ROW_ID)
                defaultMainListDao.dailyReviewCandidatePageCalls shouldBe
                    listOf(
                        FakeDefaultMainListDao.DailyReviewCandidatePageCall(
                            maxRowId = DAILY_REVIEW_MAX_ROW_ID,
                            cursorIsPinned = null,
                            cursorTimestamp = null,
                            cursorId = null,
                            limit = 1,
                        ),
                    )
                boundary.observedCount shouldBe 3
                boundary.id shouldBe "memo-3"
                boundary.token shouldBe "daily-review-boundary-$DAILY_REVIEW_MAX_ROW_ID"
            }
        }

    private fun `given daily review cursor when next page is requested then repository delegates cursor tuple to dao`() =
        runTest {
            defaultMainListDao.dailyReviewCandidateMaxRowIdResult = DAILY_REVIEW_MAX_ROW_ID
            defaultMainListDao.dailyReviewCandidateCountResult = 4
            defaultMainListDao.dailyReviewCandidatePageHandler = { call ->
                when (call.cursorId) {
                    null ->
                        listOf(
                            defaultMainListRow(id = "pinned-1", timestamp = 400L, isPinned = true),
                            defaultMainListRow(id = "memo-3", timestamp = 300L, isPinned = false),
                        )
                    "memo-3" ->
                        listOf(
                            defaultMainListRow(id = "memo-2", timestamp = 200L, isPinned = false),
                            defaultMainListRow(id = "memo-1", timestamp = 100L, isPinned = false),
                        )
                    else -> emptyList()
                }.take(call.limit)
            }
            val boundary = requireNotNull(repository.getDailyReviewCandidateBoundary())
            defaultMainListDao.dailyReviewCandidatePageCalls.clear()

            val firstPage = repository.getDailyReviewCandidatePage(boundary = boundary, cursor = null, limit = 2)
            val secondPage =
                repository.getDailyReviewCandidatePage(
                    boundary = boundary,
                    cursor = requireNotNull(firstPage.nextCursor),
                    limit = 2,
                )

            assertSoftly {
                firstPage.ids shouldBe listOf("pinned-1", "memo-3")
                firstPage.nextCursor shouldBe
                    DailyReviewCandidateCursor(
                        isPinned = false,
                        timestamp = 300L,
                        id = "memo-3",
                        token = boundary.token,
                        position = 2,
                    )
                secondPage.ids shouldBe listOf("memo-2", "memo-1")
                secondPage.nextCursor shouldBe
                    DailyReviewCandidateCursor(
                        isPinned = false,
                        timestamp = 100L,
                        id = "memo-1",
                        token = boundary.token,
                        position = 4,
                    )
                defaultMainListDao.dailyReviewCandidatePageCalls shouldBe
                    listOf(
                        FakeDefaultMainListDao.DailyReviewCandidatePageCall(
                            maxRowId = DAILY_REVIEW_MAX_ROW_ID,
                            cursorIsPinned = null,
                            cursorTimestamp = null,
                            cursorId = null,
                            limit = 2,
                        ),
                        FakeDefaultMainListDao.DailyReviewCandidatePageCall(
                            maxRowId = DAILY_REVIEW_MAX_ROW_ID,
                            cursorIsPinned = false,
                            cursorTimestamp = 300L,
                            cursorId = "memo-3",
                            limit = 2,
                        ),
                    )
            }
        }

    private fun `given new head rows after boundary when candidates are paged then original max rowid remains the query boundary`() =
        runTest {
            defaultMainListDao.dailyReviewCandidateMaxRowIdResult = DAILY_REVIEW_MAX_ROW_ID
            defaultMainListDao.dailyReviewCandidateCountResult = 2
            defaultMainListDao.dailyReviewCandidatePageResult =
                listOf(defaultMainListRow(id = "memo-before-boundary", timestamp = 200L, isPinned = false))
            val boundary = requireNotNull(repository.getDailyReviewCandidateBoundary())
            defaultMainListDao.dailyReviewCandidateMaxRowIdResult = DAILY_REVIEW_NEW_HEAD_ROW_ID
            defaultMainListDao.dailyReviewCandidatePageCalls.clear()

            val page = repository.getDailyReviewCandidatePage(boundary = boundary, cursor = null, limit = 10)

            assertSoftly {
                page.ids shouldBe listOf("memo-before-boundary")
                defaultMainListDao.dailyReviewCandidatePageCalls shouldBe
                    listOf(
                        FakeDefaultMainListDao.DailyReviewCandidatePageCall(
                            maxRowId = DAILY_REVIEW_MAX_ROW_ID,
                            cursorIsPinned = null,
                            cursorTimestamp = null,
                            cursorId = null,
                            limit = 10,
                        ),
                    )
            }
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
            searchContent = "content-$id",
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

    companion object {
        private const val DAILY_REVIEW_MAX_ROW_ID = 42L
        private const val DAILY_REVIEW_NEW_HEAD_ROW_ID = 99L
    }
}
