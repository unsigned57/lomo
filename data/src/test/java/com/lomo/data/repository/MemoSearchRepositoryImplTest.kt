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
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe

/*
 * Test Contract:
 * - Unit under test: MemoSearchRepositoryImpl
 * - Behavior focus: tokenized FTS routing for Latin and CJK input, supported MATCH query generation, tag-query parameterization,
 *   pinned-state merge, count/tag row mapping, and LIKE wildcard escaping.
 * - Observable outcomes: returned memo ids with pinned flags, selected query path outputs, mapped date-count or tag-count domain
 *   structures, and correctly escaped LIKE query strings.
 * - Red phase: Fails before the fix because search can drift away from the tokenized FTS5 query contract used
 *   by the persisted index and break Latin or CJK prefix search behavior.
 * - Excludes: Room SQL execution correctness, SearchTokenizer internals, and dispatcher/threading behavior.
 */
class MemoSearchRepositoryImplTest : DataFunSpec() {
    init {
        test("searchMemosList uses FTS for tokenizable query and truncates to five tokens") { `searchMemosList uses FTS for tokenizable query and truncates to five tokens`() }

        test("searchMemosList returns empty when query has no searchable tokens") { `searchMemosList returns empty when query has no searchable tokens`() }

        test("searchMemosList lowercases reserved words before building two-term match query") { `searchMemosList lowercases reserved words before building two-term match query`() }

        test("searchMemosList builds CJK bigram match query on the indexed search path") { `searchMemosList builds CJK bigram match query on the indexed search path`() }

        test("getMemosByTagList passes tag and prefix while merging pinned ids") { `getMemosByTagList passes tag and prefix while merging pinned ids`() }

        test("getMemoCountByDateFlow maps rows to date keyed map") { `getMemoCountByDateFlow maps rows to date keyed map`() }

        test("getTagCountsFlow maps dao rows to domain tag counts") { `getTagCountsFlow maps dao rows to domain tag counts`() }

        test("count timestamps and active day flows pass through dao outputs") { `count timestamps and active day flows pass through dao outputs`() }

        test("searchMemosList routes single CJK character query to FTS with unigram wildcard") { `searchMemosList routes single CJK character query to FTS with unigram wildcard`() }

        test("searchMemosList lowercases uppercase reserved operator words before issuing FTS query") { `searchMemosList lowercases uppercase reserved operator words before issuing FTS query`() }

        test("escapeLikeQuery escapes percent sign so it is treated as literal") { `escapeLikeQuery escapes percent sign so it is treated as literal`() }

        test("escapeLikeQuery escapes underscore so it is treated as literal") { `escapeLikeQuery escapes underscore so it is treated as literal`() }

        test("escapeLikeQuery escapes backslash before percent and underscore") { `escapeLikeQuery escapes backslash before percent and underscore`() }

        test("escapeLikeQuery leaves ordinary characters unchanged") { `escapeLikeQuery leaves ordinary characters unchanged`() }
    }


    private val memoSearchDao: MemoSearchDao = mockk()
    private val memoPinDao: MemoPinDao = mockk()

    private val repository =
        MemoSearchRepositoryImpl(
            memoSearchDao = memoSearchDao,
            memoPinDao = memoPinDao,
        )

    private fun `searchMemosList uses FTS for tokenizable query and truncates to five tokens`() =
        runTest {
            val entities =
                listOf(
                    memoEntity(id = "memo-1", timestamp = 200L, content = "alpha note"),
                    memoEntity(id = "memo-2", timestamp = 100L, content = "beta note"),
                )
            every { memoPinDao.getPinnedMemoIdsFlow() } returns flowOf(listOf("memo-2", "ghost"))
            every { memoSearchDao.searchMemosByFtsFlow("\"alpha\"* \"beta\"* \"gamma\"* \"delta\"* \"epsilon\"*") } returns flowOf(entities)

            val result =
                repository
                    .searchMemosList("  alpha beta gamma delta epsilon zeta  ")
                    .first()

            result.map { it.id } shouldBe listOf("memo-1", "memo-2")
            result.map { it.isPinned } shouldBe listOf(false, true)
            verify(exactly = 1) {
                memoSearchDao.searchMemosByFtsFlow("\"alpha\"* \"beta\"* \"gamma\"* \"delta\"* \"epsilon\"*")
            }
            verify(exactly = 0) { memoSearchDao.searchMemosFlow(any()) }
        }

    private fun `searchMemosList returns empty when query has no searchable tokens`() =
        runTest {
            every { memoPinDao.getPinnedMemoIdsFlow() } returns flowOf(emptyList())

            val result = repository.searchMemosList("  !!!  ").first()

            result.map { it.id } shouldBe emptyList<String>()
            verify(exactly = 0) { memoSearchDao.searchMemosFlow(any()) }
            verify(exactly = 0) { memoSearchDao.searchMemosByFtsFlow(any()) }
        }

    private fun `searchMemosList lowercases reserved words before building two-term match query`() =
        runTest {
            val entities =
                listOf(
                    memoEntity(id = "memo-4", timestamp = 400L, content = "OR note"),
                )
            every { memoPinDao.getPinnedMemoIdsFlow() } returns flowOf(emptyList())
            every { memoSearchDao.searchMemosByFtsFlow("or* and*") } returns flowOf(entities)

            val result = repository.searchMemosList("OR AND").first()

            result.map { it.id } shouldBe listOf("memo-4")
            verify(exactly = 1) { memoSearchDao.searchMemosByFtsFlow("or* and*") }
            verify(exactly = 0) { memoSearchDao.searchMemosFlow(any()) }
        }

    private fun `searchMemosList builds CJK bigram match query on the indexed search path`() =
        runTest {
            every { memoPinDao.getPinnedMemoIdsFlow() } returns flowOf(emptyList())
            every { memoSearchDao.searchMemosByFtsFlow("\"苏格\"* \"格拉\"* \"拉底\"*") } returns flowOf(emptyList())

            val result = repository.searchMemosList("苏格拉底").first()

            result.map { it.id } shouldBe emptyList<String>()
            verify(exactly = 1) { memoSearchDao.searchMemosByFtsFlow("\"苏格\"* \"格拉\"* \"拉底\"*") }
            verify(exactly = 0) { memoSearchDao.searchMemosFlow(any()) }
        }

    private fun `getMemosByTagList passes tag and prefix while merging pinned ids`() =
        runTest {
            val entities =
                listOf(
                    memoEntity(id = "memo-10", timestamp = 500L, content = "project root"),
                    memoEntity(id = "memo-11", timestamp = 400L, content = "project child"),
                )
            every { memoPinDao.getPinnedMemoIdsFlow() } returns flowOf(listOf("memo-10"))
            every { memoSearchDao.getMemosByTagFlow("project", "project/%") } returns flowOf(entities)

            val result = repository.getMemosByTagList("project").first()

            result.map { it.id } shouldBe listOf("memo-10", "memo-11")
            result.map { it.isPinned } shouldBe listOf(true, false)
            verify(exactly = 1) { memoSearchDao.getMemosByTagFlow("project", "project/%") }
        }

    private fun `getMemoCountByDateFlow maps rows to date keyed map`() =
        runTest {
            every { memoSearchDao.getMemoCountByDateFlow() } returns
                flowOf(
                    listOf(
                        DateCountRow(date = "2026_03_27", count = 2),
                        DateCountRow(date = "2026_03_28", count = 1),
                    ),
                )

            val result = repository.getMemoCountByDateFlow().first()

            result shouldBe mapOf("2026_03_27" to 2, "2026_03_28" to 1)
        }

    private fun `getTagCountsFlow maps dao rows to domain tag counts`() =
        runTest {
            every { memoSearchDao.getTagCountsFlow() } returns
                flowOf(
                    listOf(
                        TagCountRow(name = "work", count = 3),
                        TagCountRow(name = "life", count = 1),
                    ),
                )

            val result = repository.getTagCountsFlow().first()

            result shouldBe listOf(
                    MemoTagCount(name = "work", count = 3),
                    MemoTagCount(name = "life", count = 1),
                )
        }

    private fun `count timestamps and active day flows pass through dao outputs`() =
        runTest {
            every { memoSearchDao.getMemoCount() } returns flowOf(7)
            every { memoSearchDao.getAllTimestamps() } returns flowOf(listOf(30L, 20L, 10L))
            every { memoSearchDao.getActiveDayCount() } returns flowOf(2)

            repository.getMemoCountFlow().first() shouldBe 7
            repository.getMemoTimestampsFlow().first() shouldBe listOf(30L, 20L, 10L)
            repository.getActiveDayCount().first() shouldBe 2
        }

    private fun `searchMemosList routes single CJK character query to FTS with unigram wildcard`() =
        runTest {
            // A single CJK char is a valid unigram token; the tokenizer returns it as-is so the
            // repository must send "苏*" to the FTS DAO rather than falling back to plain search.
            val entities =
                listOf(
                    memoEntity(id = "memo-cjk-1", timestamp = 500L, content = "苏州园林"),
                )
            every { memoPinDao.getPinnedMemoIdsFlow() } returns flowOf(emptyList())
            every { memoSearchDao.searchMemosByFtsFlow("苏*") } returns flowOf(entities)

            val result = repository.searchMemosList("苏").first()

            result.map { it.id } shouldBe listOf("memo-cjk-1")
            verify(exactly = 1) { memoSearchDao.searchMemosByFtsFlow("苏*") }
            verify(exactly = 0) { memoSearchDao.searchMemosFlow(any()) }
        }

    private fun `searchMemosList lowercases uppercase reserved operator words before issuing FTS query`() =
        runTest {
            val entities =
                listOf(
                    memoEntity(id = "memo-or", timestamp = 600L, content = "option OR another option"),
                )
            every { memoPinDao.getPinnedMemoIdsFlow() } returns flowOf(emptyList())
            every { memoSearchDao.searchMemosByFtsFlow("or* and* not*") } returns flowOf(entities)

            val result = repository.searchMemosList("OR AND NOT").first()

            result.map { it.id } shouldBe listOf("memo-or")
            verify(exactly = 1) { memoSearchDao.searchMemosByFtsFlow("or* and* not*") }
            verify(exactly = 0) { memoSearchDao.searchMemosFlow(any()) }
        }

    private fun `escapeLikeQuery escapes percent sign so it is treated as literal`() {
        repository.escapeLikeQuery("%") shouldBe "\\%"
    }

    private fun `escapeLikeQuery escapes underscore so it is treated as literal`() {
        repository.escapeLikeQuery("_") shouldBe "\\_"
    }

    private fun `escapeLikeQuery escapes backslash before percent and underscore`() {
        repository.escapeLikeQuery("50%") shouldBe "50\\%"
        repository.escapeLikeQuery("file_name") shouldBe "file\\_name"
    }

    private fun `escapeLikeQuery leaves ordinary characters unchanged`() {
        repository.escapeLikeQuery("hello world") shouldBe "hello world"
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
