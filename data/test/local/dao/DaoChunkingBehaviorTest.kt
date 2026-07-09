/*
 * Behavior Contract:
 * - Unit under test: MemoDao, MemoImageDao, MemoTagDao default helpers.
 * - Owning layer: data
 * - Priority tier: P1
 * - Capability: keep DAO helpers bounded for large memo collections.
 *
 * Scenarios:
 * - Given more memo ids than SQLite allows in one bind list, when image refs
 *   are replaced, then delete calls are chunked under the bind limit.
 * - Given more memo ids than SQLite allows in one bind list, when tag refs are
 *   replaced, then delete calls are chunked under the bind limit.
 * - Given a random memo request above the memo count and plausible rowid bounds,
 *   when random memos are requested, then the helper caps the query limit to
 *   the memo count, samples an indexed rowid window, and wraps before the floor
 *   only for the remaining limit.
 * - Given a non-positive limit or positive limit with empty bounds, when random
 *   memos are requested, then the helper returns empty without DAO range queries.
 *
 * Observable outcomes:
 * - per-call chunk sizes, random rowid range-query arguments, and returned ids.
 *
 * TDD proof:
 * - RED before the random-query fix because `MemoDao.getRandomMemos` delegated
 *   to `getRandomMemosDirect`, an `ORDER BY RANDOM()` full-table sort query,
 *   instead of the rowid bounds/range contract.
 * - RED in review round 2 before the test-control seam because the tightened
 *   test could not override `nextRandomMemoRowIdFloor` to prove a plausible
 *   rowid wrap and cap-to-total contract deterministically.
 *
 * Excludes:
 * - Room SQL generation/runtime integration and randomness distribution quality.
 */
package com.lomo.data.local.dao

import com.lomo.data.local.entity.MemoEntity
import com.lomo.data.local.entity.MemoImageAttachmentEntity
import com.lomo.data.local.entity.MemoTagCrossRefEntity
import com.lomo.data.local.projection.ActiveMemoProjection
import com.lomo.data.local.projection.MemoProjectionProjector
import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.model.Memo
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

class DaoChunkingBehaviorTest : DataFunSpec() {
    init {
        test("replaceImageRefsForMemos chunks delete ids under sqlite bind limit") { `replaceImageRefsForMemos chunks delete ids under sqlite bind limit`() }

        test("replaceTagRefsForMemos chunks delete ids under sqlite bind limit") { `replaceTagRefsForMemos chunks delete ids under sqlite bind limit`() }

        test("getRandomMemos caps to total count and wraps rowid range from sampled floor") {
            `getRandomMemos caps to total count and wraps rowid range from sampled floor`()
        }

        test("getRandomMemos returns empty when limit is non-positive or bounds are empty") {
            `getRandomMemos returns empty when limit is non-positive or bounds are empty`()
        }
    }


    private fun `replaceImageRefsForMemos chunks delete ids under sqlite bind limit`() =
        runTest {
            val dao = RecordingMemoImageDao()
            val memos = (1..(ROOM_MAX_BIND_PARAMETER_COUNT + 1)).map { index -> memoProjection("memo-image-$index") }

            dao.replaceImageRefsForMemos(memos)

            dao.deletedIdChunks.map { chunk -> chunk.size } shouldBe listOf(ROOM_MAX_BIND_PARAMETER_COUNT, 1)
            (dao.deletedIdChunks.all { chunk -> chunk.size <= ROOM_MAX_BIND_PARAMETER_COUNT }).shouldBeTrue()
        }

    private fun `replaceTagRefsForMemos chunks delete ids under sqlite bind limit`() =
        runTest {
            val dao = RecordingMemoTagDao()
            val memos = (1..(ROOM_MAX_BIND_PARAMETER_COUNT + 1)).map { index -> memoProjection("memo-tag-$index") }

            dao.replaceTagRefsForMemos(memos)

            dao.deletedIdChunks.map { chunk -> chunk.size } shouldBe listOf(ROOM_MAX_BIND_PARAMETER_COUNT, 1)
            (dao.deletedIdChunks.all { chunk -> chunk.size <= ROOM_MAX_BIND_PARAMETER_COUNT }).shouldBeTrue()
        }

    private fun `getRandomMemos caps to total count and wraps rowid range from sampled floor`() =
        runTest {
            val dao =
                RecordingMemoDao(
                    bounds = MemoRowIdBounds(minRowId = 10L, maxRowId = 13L, totalCount = 4),
                    fixedRowIdFloor = 12L,
                    fromFloorRows = listOf(memoEntity("row-12"), memoEntity("row-13")),
                    beforeFloorRows = listOf(memoEntity("row-10"), memoEntity("row-11")),
                )

            val result = dao.getRandomMemos(limit = 6)

            result.map { it.id } shouldBe listOf("row-12", "row-13", "row-10", "row-11")
            dao.boundsCallCount shouldBe 1
            dao.floorCalls shouldBe listOf(RowIdFloorCall(minRowId = 10L, maxRowId = 13L))
            dao.fromFloorCalls shouldBe listOf(RowIdPageCall(rowIdFloor = 12L, limit = 4))
            dao.beforeFloorCalls shouldBe listOf(RowIdPageCall(rowIdFloor = 12L, limit = 2))
        }

    private fun `getRandomMemos returns empty when limit is non-positive or bounds are empty`() =
        runTest {
            val invalidLimitDao = RecordingMemoDao()

            val invalidLimitResult = invalidLimitDao.getRandomMemos(limit = 0)

            invalidLimitResult shouldBe emptyList<MemoEntity>()
            invalidLimitDao.boundsCallCount shouldBe 0
            invalidLimitDao.floorCalls shouldBe emptyList<RowIdFloorCall>()
            invalidLimitDao.fromFloorCalls shouldBe emptyList<RowIdPageCall>()
            invalidLimitDao.beforeFloorCalls shouldBe emptyList<RowIdPageCall>()

            val emptyBoundsDao =
                RecordingMemoDao(
                    bounds = MemoRowIdBounds(minRowId = null, maxRowId = null, totalCount = 0),
                )

            val emptyBoundsResult = emptyBoundsDao.getRandomMemos(limit = 3)

            emptyBoundsResult shouldBe emptyList<MemoEntity>()
            emptyBoundsDao.boundsCallCount shouldBe 1
            emptyBoundsDao.floorCalls shouldBe emptyList<RowIdFloorCall>()
            emptyBoundsDao.fromFloorCalls shouldBe emptyList<RowIdPageCall>()
            emptyBoundsDao.beforeFloorCalls shouldBe emptyList<RowIdPageCall>()
        }

    private class RecordingMemoImageDao : MemoImageDao {
        val deletedIdChunks = mutableListOf<List<String>>()

        override suspend fun insertImageRefs(refs: List<MemoImageAttachmentEntity>) = Unit

        override suspend fun deleteImageRefsByMemoId(memoId: String) = Unit

        override suspend fun deleteImageRefsByMemoIds(memoIds: List<String>) {
            deletedIdChunks += memoIds
        }

        override suspend fun clearImageRefs() = Unit
    }

    private class RecordingMemoTagDao : MemoTagDao {
        val deletedIdChunks = mutableListOf<List<String>>()

        override suspend fun insertTagRefs(refs: List<MemoTagCrossRefEntity>) = Unit

        override suspend fun deleteTagRefsByMemoId(memoId: String) = Unit

        override suspend fun deleteTagRefsByMemoIds(memoIds: List<String>) {
            deletedIdChunks += memoIds
        }

        override suspend fun clearTagRefs() = Unit
    }

    private class RecordingMemoDao(
        private val bounds: MemoRowIdBounds = MemoRowIdBounds(minRowId = null, maxRowId = null, totalCount = 0),
        private val fixedRowIdFloor: Long? = null,
        private val fromFloorRows: List<MemoEntity> = emptyList(),
        private val beforeFloorRows: List<MemoEntity> = emptyList(),
    ) : MemoDao {
        var boundsCallCount = 0
        val floorCalls = mutableListOf<RowIdFloorCall>()
        val fromFloorCalls = mutableListOf<RowIdPageCall>()
        val beforeFloorCalls = mutableListOf<RowIdPageCall>()

        override fun getAllMemosFlow(): Flow<List<MemoEntity>> = flowOf(emptyList())

        override suspend fun getRandomMemoRowIdBounds(): MemoRowIdBounds {
            boundsCallCount += 1
            return bounds
        }

        override fun nextRandomMemoRowIdFloor(
            minRowId: Long,
            maxRowId: Long,
        ): Long {
            floorCalls += RowIdFloorCall(minRowId = minRowId, maxRowId = maxRowId)
            return fixedRowIdFloor ?: minRowId
        }

        override suspend fun getRandomMemosFromRowIdFloor(
            rowIdFloor: Long,
            limit: Int,
        ): List<MemoEntity> {
            fromFloorCalls += RowIdPageCall(rowIdFloor = rowIdFloor, limit = limit)
            return fromFloorRows.take(limit)
        }

        override suspend fun getRandomMemosBeforeRowIdFloor(
            rowIdFloor: Long,
            limit: Int,
        ): List<MemoEntity> {
            beforeFloorCalls += RowIdPageCall(rowIdFloor = rowIdFloor, limit = limit)
            return beforeFloorRows.take(limit)
        }

        override suspend fun getRecentMemos(limit: Int): List<MemoEntity> =
            error("Unused in DaoChunkingBehaviorTest")

        override suspend fun getAllMemoIds(): List<String> =
            error("Unused in DaoChunkingBehaviorTest")

        override suspend fun getMemosByIds(ids: List<String>): List<MemoEntity> =
            error("Unused in DaoChunkingBehaviorTest")

        override suspend fun getMemoCountSync(): Int =
            error("Unused in DaoChunkingBehaviorTest")

        override suspend fun getMemo(id: String): MemoEntity? =
            error("Unused in DaoChunkingBehaviorTest")

        override suspend fun getAllMemosSync(): List<MemoEntity> =
            error("Unused in DaoChunkingBehaviorTest")

        override suspend fun getMemosByDate(date: String): List<MemoEntity> =
            error("Unused in DaoChunkingBehaviorTest")

        override suspend fun getMemosByDates(dates: List<String>): List<MemoEntity> =
            error("Unused in DaoChunkingBehaviorTest")
    }
}

private fun memoEntity(id: String): MemoEntity =
    MemoEntity(
        id = id,
        timestamp = 1_700_000_000_000L,
        content = "content-$id",
        searchContent = "content-$id",
        rawContent = "- 10:00 content-$id",
        date = "2026_04_27",
        tags = "",
        imageUrls = "",
    )

private fun memoProjection(id: String): ActiveMemoProjection =
    MemoProjectionProjector.projectActive(
        Memo(
            id = id,
            timestamp = 1_700_000_000_000L,
            content = "content-$id",
            rawContent = "- 10:00 content-$id",
            dateKey = "2026_04_27",
        ),
    )

private data class RowIdPageCall(
    val rowIdFloor: Long,
    val limit: Int,
)

private data class RowIdFloorCall(
    val minRowId: Long,
    val maxRowId: Long,
)
