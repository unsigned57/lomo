package com.lomo.data.repository

import com.lomo.data.local.dao.DateCountRow
import com.lomo.data.local.dao.MemoPinDao
import com.lomo.data.local.dao.MemoSearchDao
import com.lomo.data.local.dao.TagCountRow
import com.lomo.data.local.entity.MemoEntity
import com.lomo.domain.model.MemoTagCount
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: MemoSearchRepositoryImpl
 * - Behavior focus: tokenized FTS routing vs fallback query routing, tag-query parameterization, pinned-state merge, and count/tag row mapping.
 * - Observable outcomes: returned memo ids with pinned flags, selected query path outputs, and mapped date-count or tag-count domain structures.
 * - Red phase: Not applicable - test-only coverage addition; no production change.
 * - Excludes: Room SQL execution correctness, SearchTokenizer internals, and dispatcher/threading behavior.
 */
class MemoSearchRepositoryImplTest {
    private val memoSearchDao: MemoSearchDao = mockk()
    private val memoPinDao: MemoPinDao = mockk()

    private val repository =
        MemoSearchRepositoryImpl(
            memoSearchDao = memoSearchDao,
            memoPinDao = memoPinDao,
        )

    @Test
    fun `searchMemosList uses FTS for tokenizable query and truncates to five tokens`() =
        runTest {
            val entities =
                listOf(
                    memoEntity(id = "memo-1", timestamp = 200L, content = "alpha note"),
                    memoEntity(id = "memo-2", timestamp = 100L, content = "beta note"),
                )
            every { memoPinDao.getPinnedMemoIdsFlow() } returns flowOf(listOf("memo-2", "ghost"))
            every { memoSearchDao.searchMemosByFtsFlow("alpha* beta* gamma* delta* epsilon*") } returns flowOf(entities)

            val result =
                repository
                    .searchMemosList("  alpha beta gamma delta epsilon zeta  ")
                    .first()

            assertEquals(listOf("memo-1", "memo-2"), result.map { it.id })
            assertEquals(listOf(false, true), result.map { it.isPinned })
            verify(exactly = 1) { memoSearchDao.searchMemosByFtsFlow("alpha* beta* gamma* delta* epsilon*") }
            verify(exactly = 0) { memoSearchDao.searchMemosFlow(any()) }
        }

    @Test
    fun `searchMemosList falls back to plain search when query has no searchable tokens`() =
        runTest {
            val entities =
                listOf(
                    memoEntity(id = "memo-3", timestamp = 300L, content = "symbols"),
                )
            every { memoPinDao.getPinnedMemoIdsFlow() } returns flowOf(emptyList())
            every { memoSearchDao.searchMemosFlow("!!!") } returns flowOf(entities)

            val result = repository.searchMemosList("  !!!  ").first()

            assertEquals(listOf("memo-3"), result.map { it.id })
            assertEquals(listOf(false), result.map { it.isPinned })
            verify(exactly = 1) { memoSearchDao.searchMemosFlow("!!!") }
            verify(exactly = 0) { memoSearchDao.searchMemosByFtsFlow(any()) }
        }

    @Test
    fun `getMemosByTagList passes tag and prefix while merging pinned ids`() =
        runTest {
            val entities =
                listOf(
                    memoEntity(id = "memo-10", timestamp = 500L, content = "project root"),
                    memoEntity(id = "memo-11", timestamp = 400L, content = "project child"),
                )
            every { memoPinDao.getPinnedMemoIdsFlow() } returns flowOf(listOf("memo-10"))
            every { memoSearchDao.getMemosByTagFlow("project", "project/%") } returns flowOf(entities)

            val result = repository.getMemosByTagList("project").first()

            assertEquals(listOf("memo-10", "memo-11"), result.map { it.id })
            assertEquals(listOf(true, false), result.map { it.isPinned })
            verify(exactly = 1) { memoSearchDao.getMemosByTagFlow("project", "project/%") }
        }

    @Test
    fun `getMemoCountByDateFlow maps rows to date keyed map`() =
        runTest {
            every { memoSearchDao.getMemoCountByDateFlow() } returns
                flowOf(
                    listOf(
                        DateCountRow(date = "2026_03_27", count = 2),
                        DateCountRow(date = "2026_03_28", count = 1),
                    ),
                )

            val result = repository.getMemoCountByDateFlow().first()

            assertEquals(mapOf("2026_03_27" to 2, "2026_03_28" to 1), result)
        }

    @Test
    fun `getTagCountsFlow maps dao rows to domain tag counts`() =
        runTest {
            every { memoSearchDao.getTagCountsFlow() } returns
                flowOf(
                    listOf(
                        TagCountRow(name = "work", count = 3),
                        TagCountRow(name = "life", count = 1),
                    ),
                )

            val result = repository.getTagCountsFlow().first()

            assertEquals(
                listOf(
                    MemoTagCount(name = "work", count = 3),
                    MemoTagCount(name = "life", count = 1),
                ),
                result,
            )
        }

    @Test
    fun `count timestamps and active day flows pass through dao outputs`() =
        runTest {
            every { memoSearchDao.getMemoCount() } returns flowOf(7)
            every { memoSearchDao.getAllTimestamps() } returns flowOf(listOf(30L, 20L, 10L))
            every { memoSearchDao.getActiveDayCount() } returns flowOf(2)

            assertEquals(7, repository.getMemoCountFlow().first())
            assertEquals(listOf(30L, 20L, 10L), repository.getMemoTimestampsFlow().first())
            assertEquals(2, repository.getActiveDayCount().first())
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
            rawContent = content,
            date = "2026_03_27",
            tags = "",
            imageUrls = "",
        )
}
