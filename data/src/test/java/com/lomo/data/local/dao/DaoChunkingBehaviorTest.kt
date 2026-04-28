package com.lomo.data.local.dao

import com.lomo.data.local.entity.MemoEntity
import com.lomo.data.local.entity.MemoImageAttachmentEntity
import com.lomo.data.local.entity.MemoTagCrossRefEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: MemoDao, MemoImageDao, MemoTagDao default batching helpers.
 * - Behavior focus: guarding SQLite bind-parameter limits by chunking large memo-id collections; and that
 *   getRandomMemos delegates to a single direct SQL query rather than loading all IDs.
 * - Observable outcomes: per-call chunk sizes for DAO delete/fetch methods; direct-query delegation for random memos.
 * - Red phase: Fails before the fix because large id lists are issued as a single IN-clause call and can exceed SQLite limits.
 * - Excludes: Room SQL generation/runtime integration and randomness distribution quality.
 */
class DaoChunkingBehaviorTest {
    @Test
    fun `replaceImageRefsForMemos chunks delete ids under sqlite bind limit`() =
        runTest {
            val dao = RecordingMemoImageDao()
            val memos = (1..(ROOM_MAX_BIND_PARAMETER_COUNT + 1)).map { index -> memoEntity("memo-image-$index") }

            dao.replaceImageRefsForMemos(memos)

            assertEquals(
                listOf(ROOM_MAX_BIND_PARAMETER_COUNT, 1),
                dao.deletedIdChunks.map { chunk -> chunk.size },
            )
            assertTrue(dao.deletedIdChunks.all { chunk -> chunk.size <= ROOM_MAX_BIND_PARAMETER_COUNT })
        }

    @Test
    fun `replaceTagRefsForMemos chunks delete ids under sqlite bind limit`() =
        runTest {
            val dao = RecordingMemoTagDao()
            val memos = (1..(ROOM_MAX_BIND_PARAMETER_COUNT + 1)).map { index -> memoEntity("memo-tag-$index") }

            dao.replaceTagRefsForMemos(memos)

            assertEquals(
                listOf(ROOM_MAX_BIND_PARAMETER_COUNT, 1),
                dao.deletedIdChunks.map { chunk -> chunk.size },
            )
            assertTrue(dao.deletedIdChunks.all { chunk -> chunk.size <= ROOM_MAX_BIND_PARAMETER_COUNT })
        }

    @Test
    fun `getRandomMemos delegates to direct RANDOM query with given limit`() =
        runTest {
            val dao = RecordingMemoDao()

            val result = dao.getRandomMemos(limit = 3)

            assertEquals(listOf("direct-0", "direct-1", "direct-2"), result.map { it.id })
            assertEquals(listOf(3), dao.directQueryLimits)
        }

    @Test
    fun `getRandomMemos returns empty when limit is non-positive without direct query`() =
        runTest {
            val dao = RecordingMemoDao()

            val result = dao.getRandomMemos(limit = 0)

            assertEquals(emptyList<MemoEntity>(), result)
            assertEquals(emptyList<Int>(), dao.directQueryLimits)
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

    private class RecordingMemoDao : MemoDao {
        val directQueryLimits = mutableListOf<Int>()

        override fun getAllMemosFlow(): Flow<List<MemoEntity>> = flowOf(emptyList())

        override suspend fun getRandomMemosDirect(limit: Int): List<MemoEntity> {
            directQueryLimits += limit
            return (0 until limit).map { index -> memoEntity("direct-$index") }
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
        rawContent = "- 10:00 content-$id",
        date = "2026_04_27",
        tags = "",
        imageUrls = "",
    )
