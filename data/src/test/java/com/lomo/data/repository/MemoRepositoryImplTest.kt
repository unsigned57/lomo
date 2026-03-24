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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: MemoRepositoryImpl
 * - Behavior focus: query-to-search-source routing, pinned memo mapping, and memo mutation delegation.
 * - Observable outcomes: constructed FTS query text, fallback search path choice, mapped pin state, and synchronizer calls.
 * - Excludes: Room SQL execution details, FTS engine internals, and UI rendering.
 */
class MemoRepositoryImplTest {
    @MockK(relaxed = true)
    private lateinit var dao: TestMemoDaoSuite

    @MockK(relaxed = true)
    private lateinit var synchronizer: MemoSynchronizer

    private lateinit var repository: MemoRepositoryImpl

    @Before
    fun setUp() {
        MockKAnnotations.init(this)
        repository =
            MemoRepositoryImpl(
                queryRepository =
                    MemoQueryRepositoryImpl(
                        memoDao = dao,
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

    @Test
    fun `saveMemo delegates to synchronizer`() =
        runTest {
            coEvery { synchronizer.saveMemoAsync(any(), any()) } just runs
            repository.saveMemo("content", timestamp = 123L)
            coVerify(exactly = 1) { synchronizer.saveMemoAsync("content", 123L) }
        }

    @Test
    fun `saveMemo propagates synchronizer exception`() =
        runTest {
            coEvery {
                synchronizer.saveMemoAsync(any(), any())
            } throws IllegalStateException("sync failed")

            val thrown =
                runCatching {
                    repository.saveMemo("content", timestamp = 456L)
                }.exceptionOrNull()
            assertTrue(thrown is IllegalStateException)
        }

    @Test
    fun `updateMemo delegates blank content to synchronizer update path`() =
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

            coVerify(exactly = 1) { synchronizer.updateMemoAsync(memo, "   ") }
            coVerify(exactly = 0) { synchronizer.deleteMemoAsync(any()) }
        }

    @Test
    fun `getMemoById maps pinned memo without loading whole list`() =
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

            assertEquals("memo-1", memo?.id)
            assertTrue(memo?.isPinned == true)
            verify(exactly = 0) { dao.getAllMemosFlow() }
        }

    @Test
    fun `searchMemosList CJK phrase uses space separated bigram match query`() =
        runTest {
            val captured = slot<String>()
            every { dao.searchMemosByFtsFlow(capture(captured)) } returns flowOf(emptyList())
            every { dao.searchMemosFlow(any()) } returns flowOf(emptyList())

            repository.searchMemosList("苏格拉底").first()

            assertEquals("苏格* 格拉* 拉底*", captured.captured)
            verify(exactly = 1) { dao.searchMemosByFtsFlow(any()) }
            verify(exactly = 0) { dao.searchMemosFlow(any()) }
        }

    @Test
    fun `searchMemosList single CJK char keeps unigram query`() =
        runTest {
            val captured = slot<String>()
            every { dao.searchMemosByFtsFlow(capture(captured)) } returns flowOf(emptyList())

            repository.searchMemosList("苏").first()

            assertEquals("苏*", captured.captured)
            verify(exactly = 1) { dao.searchMemosByFtsFlow(any()) }
        }

    @Test
    fun `searchMemosList two char CJK prefix keeps exact bigram query`() =
        runTest {
            val captured = slot<String>()
            every { dao.searchMemosByFtsFlow(capture(captured)) } returns flowOf(emptyList())
            every { dao.searchMemosFlow(any()) } returns flowOf(emptyList())

            repository.searchMemosList("苏格").first()

            assertEquals("苏格*", captured.captured)
            verify(exactly = 1) { dao.searchMemosByFtsFlow(any()) }
            verify(exactly = 0) { dao.searchMemosFlow(any()) }
        }

    @Test
    fun `searchMemosList latin query uses FTS match query`() =
        runTest {
            val captured = slot<String>()
            every { dao.searchMemosByFtsFlow(capture(captured)) } returns flowOf(emptyList())
            every { dao.searchMemosFlow(any()) } returns flowOf(emptyList())

            repository.searchMemosList("Socrates 123").first()

            assertEquals("Socrates* 123*", captured.captured)
            verify(exactly = 1) { dao.searchMemosByFtsFlow(any()) }
            verify(exactly = 0) { dao.searchMemosFlow(any()) }
        }

    @Test
    fun `searchMemosList falls back when query has no searchable tokens`() =
        runTest {
            every { dao.searchMemosByFtsFlow(any()) } returns flowOf(emptyList())
            every { dao.searchMemosFlow("###") } returns flowOf(emptyList())

            repository.searchMemosList("###").first()

            verify(exactly = 0) { dao.searchMemosByFtsFlow(any()) }
            verify(exactly = 1) { dao.searchMemosFlow("###") }
        }

    @Test
    fun `setMemoPinned true inserts pin row`() =
        runTest {
            repository.setMemoPinned("memo-1", pinned = true)

            coVerify(exactly = 1) { dao.upsertMemoPin(any()) }
            coVerify(exactly = 0) { dao.deleteMemoPin(any()) }
        }

    @Test
    fun `setMemoPinned false deletes pin row`() =
        runTest {
            repository.setMemoPinned("memo-1", pinned = false)

            coVerify(exactly = 1) { dao.deleteMemoPin("memo-1") }
            coVerify(exactly = 0) { dao.upsertMemoPin(any()) }
        }
}
