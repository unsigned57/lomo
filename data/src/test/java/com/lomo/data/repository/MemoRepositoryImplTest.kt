package com.lomo.data.repository


import com.lomo.domain.model.Memo
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.just
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue

/*
 * Test Contract:
 * - Unit under test: MemoRepositoryImpl
 * - Behavior focus: query-to-search-source routing, pinned memo mapping, and mutation delegation onto the
 *   synchronous safety-tracked synchronizer path.
 * - Observable outcomes: constructed FTS query text, fallback search path choice, mapped pin state, and
 *   synchronizer calls.
 * - Red phase: qualityCheck failed before this test update because these assertions still expected
 *   saveMemoAsync/updateMemoAsync/deleteMemoAsync after the repository switched to synchronous tracked
 *   mutation APIs.
 * - Excludes: Room SQL execution details, FTS engine internals, and UI rendering.
 */
class MemoRepositoryImplTest : DataFunSpec() {
    init {
        beforeTest {
            setUp()
        }

        test("saveMemo delegates to synchronizer") { `saveMemo delegates to synchronizer`() }

        test("saveMemo propagates synchronizer exception") { `saveMemo propagates synchronizer exception`() }

        test("updateMemo delegates blank content to synchronizer update path") { `updateMemo delegates blank content to synchronizer update path`() }

        test("getMemoById maps pinned memo without loading whole list") { `getMemoById maps pinned memo without loading whole list`() }

        test("searchMemosList CJK phrase uses space separated bigram match query") { `searchMemosList CJK phrase uses space separated bigram match query`() }

        test("searchMemosList single CJK char keeps unigram query") { `searchMemosList single CJK char keeps unigram query`() }

        test("searchMemosList two char CJK prefix keeps exact bigram query") { `searchMemosList two char CJK prefix keeps exact bigram query`() }

        test("searchMemosList latin query uses FTS match query") { `searchMemosList latin query uses FTS match query`() }

        test("searchMemosList returns empty when query has no searchable tokens") { `searchMemosList returns empty when query has no searchable tokens`() }

        test("setMemoPinned true inserts pin row") { `setMemoPinned true inserts pin row`() }

        test("setMemoPinned false deletes pin row") { `setMemoPinned false deletes pin row`() }
    }


    @MockK(relaxed = true)
    private lateinit var dao: TestMemoDaoSuite

    @MockK(relaxed = true)
    private lateinit var synchronizer: MemoSynchronizer

    private lateinit var repository: MemoRepositoryImpl

    private fun setUp() {
        MockKAnnotations.init(this)
        repository =
            MemoRepositoryImpl(
                queryRepository =
                    MemoQueryRepositoryImpl(
                        memoDao = dao,
                        memoBrowseDao = dao,
                        defaultMainListDao = dao,
                        memoPinDao = dao,
                        synchronizer = synchronizer,
                    ),
                mutationRepository =
                    MemoMutationRepositoryImpl(
                        memoPinDao = dao,
                        synchronizer = synchronizer,
                    ),
                searchRepository =
                    MemoSearchRepositoryImpl(
                        memoSearchDao = dao,
                        memoPinDao = dao,
                    ),
                trashRepository =
                    MemoTrashRepositoryImpl(
                        memoTrashDao = dao,
                        synchronizer = synchronizer,
                    ),
            )
        every { dao.getPinnedMemoIdsFlow() } returns flowOf(emptyList())
        coEvery { dao.getPinnedMemoIds() } returns emptyList()
    }

    private fun `saveMemo delegates to synchronizer`() =
        runTest {
            coEvery { synchronizer.saveMemo(any(), any()) } just runs
            repository.saveMemo("content", timestamp = 123L)
            coVerify(exactly = 1) { synchronizer.saveMemo("content", 123L) }
        }

    private fun `saveMemo propagates synchronizer exception`() =
        runTest {
            coEvery {
                synchronizer.saveMemo(any(), any())
            } throws IllegalStateException("sync failed")

            val thrown =
                runCatching {
                    repository.saveMemo("content", timestamp = 456L)
                }.exceptionOrNull()
            (thrown is IllegalStateException).shouldBeTrue()
        }

    private fun `updateMemo delegates blank content to synchronizer update path`() =
        runTest {
            val memo =
                Memo(
                    id = "memo-1",
                    timestamp = 1L,
                    content = "old",
                    rawContent = "- 10:00 old",
                    dateKey = "2026_02_01",
                )

            repository.updateMemo(memo, "   ")

            coVerify(exactly = 1) { synchronizer.updateMemo(memo, "   ") }
            coVerify(exactly = 0) { synchronizer.deleteMemo(any()) }
        }

    private fun `getMemoById maps pinned memo without loading whole list`() =
        runTest {
            coEvery {
                dao.getMemo("memo-1")
            } returns
                com.lomo.data.local.entity.MemoEntity(
                    id = "memo-1",
                    timestamp = 1L,
                    updatedAt = 2L,
                    content = "memo-content",
                    rawContent = "- 10:00 memo-content",
                    date = "2026_03_08",
                    tags = "",
                    imageUrls = "",
                )
            coEvery { dao.getPinnedMemoIds() } returns listOf("memo-1")

            val memo = repository.getMemoById("memo-1")

            memo?.id shouldBe "memo-1"
            (memo?.isPinned == true).shouldBeTrue()
            verify(exactly = 0) { dao.getAllMemosFlow() }
        }

    private fun `searchMemosList CJK phrase uses space separated bigram match query`() =
        runTest {
            val captured = slot<String>()
            every { dao.searchMemosByFtsFlow(capture(captured)) } returns flowOf(emptyList())
            every { dao.searchMemosFlow(any()) } returns flowOf(emptyList())

            repository.searchMemosList("苏格拉底").first()

            captured.captured shouldBe "\"苏格\"* \"格拉\"* \"拉底\"*"
            verify(exactly = 1) { dao.searchMemosByFtsFlow(any()) }
            verify(exactly = 0) { dao.searchMemosFlow(any()) }
        }

    private fun `searchMemosList single CJK char keeps unigram query`() =
        runTest {
            val captured = slot<String>()
            every { dao.searchMemosByFtsFlow(capture(captured)) } returns flowOf(emptyList())

            repository.searchMemosList("苏").first()

            captured.captured shouldBe "苏*"
            verify(exactly = 1) { dao.searchMemosByFtsFlow(any()) }
        }

    private fun `searchMemosList two char CJK prefix keeps exact bigram query`() =
        runTest {
            val captured = slot<String>()
            every { dao.searchMemosByFtsFlow(capture(captured)) } returns flowOf(emptyList())
            every { dao.searchMemosFlow(any()) } returns flowOf(emptyList())

            repository.searchMemosList("苏格").first()

            captured.captured shouldBe "\"苏格\"*"
            verify(exactly = 1) { dao.searchMemosByFtsFlow(any()) }
            verify(exactly = 0) { dao.searchMemosFlow(any()) }
        }

    private fun `searchMemosList latin query uses FTS match query`() =
        runTest {
            val captured = slot<String>()
            every { dao.searchMemosByFtsFlow(capture(captured)) } returns flowOf(emptyList())
            every { dao.searchMemosFlow(any()) } returns flowOf(emptyList())

            repository.searchMemosList("Socrates 123").first()

            captured.captured shouldBe "\"Socrates\"* \"123\"*"
            verify(exactly = 1) { dao.searchMemosByFtsFlow(any()) }
            verify(exactly = 0) { dao.searchMemosFlow(any()) }
        }

    private fun `searchMemosList returns empty when query has no searchable tokens`() =
        runTest {
            every { dao.searchMemosByFtsFlow(any()) } returns flowOf(emptyList())

            repository.searchMemosList("###").first()

            verify(exactly = 0) { dao.searchMemosByFtsFlow(any()) }
            verify(exactly = 0) { dao.searchMemosFlow(any()) }
        }

    private fun `setMemoPinned true inserts pin row`() =
        runTest {
            repository.setMemoPinned("memo-1", pinned = true)

            coVerify(exactly = 1) { dao.upsertMemoPin(any()) }
            coVerify(exactly = 0) { dao.deleteMemoPin(any()) }
        }

    private fun `setMemoPinned false deletes pin row`() =
        runTest {
            repository.setMemoPinned("memo-1", pinned = false)

            coVerify(exactly = 1) { dao.deleteMemoPin("memo-1") }
            coVerify(exactly = 0) { dao.upsertMemoPin(any()) }
        }
}
