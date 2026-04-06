package com.lomo.data.repository

import com.lomo.data.local.dao.LocalFileStateDao
import com.lomo.data.local.entity.MemoEntity
import com.lomo.domain.model.MemoRevisionOrigin
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: MemoRefreshDbApplier
 * - Behavior focus: refresh replacement cleanup, deduplicated insertion, and transaction execution boundaries.
 * - Observable outcomes: DAO calls, inserted memo content, and transaction invocation count.
 * - Red phase: Fails if refresh replacement stops cleaning stale rows, inserts duplicate memo revisions, or bypasses
 *   the configured transaction runner.
 * - Excludes: Room integration wiring, filesystem refresh parsing, and removed day-file snapshot side effects.
 */
class MemoRefreshDbApplierTest {
    @MockK(relaxed = true)
    private lateinit var dao: TestMemoDaoSuite

    @MockK(relaxed = true)
    private lateinit var localFileStateDao: LocalFileStateDao

    @MockK(relaxed = true)
    private lateinit var memoVersionJournal: MemoVersionJournal

    private lateinit var applier: MemoRefreshDbApplier

    @Before
    fun setUp() {
        MockKAnnotations.init(this)
        coEvery { dao.getMemosByDate(any()) } returns emptyList()
        coEvery { dao.getTrashMemosByDate(any()) } returns emptyList()
        coEvery { localFileStateDao.getByFilename(any(), any()) } returns null
        applier = createApplier()
    }

    @Test
    fun `apply removes stale fts rows when replacing main date`() =
        runTest {
            val existingMemoIds = listOf("memo_1", "memo_2")
            coEvery { dao.getMemosByDate("2024_01_15") } returns
                existingMemoIds.map {
                    memoEntity(
                        id = it,
                        date = "2024_01_15",
                        content = "content-$it",
                    )
                }

            val parseResult =
                MemoRefreshParseResult(
                    mainMemos = emptyList(),
                    trashMemos = emptyList(),
                    metadataToUpdate = emptyList(),
                    mainDatesToReplace = setOf("2024_01_15"),
                    trashDatesToReplace = emptySet(),
                )

            applier.apply(parseResult, filesToDeleteInDb = emptySet())

            coVerify(exactly = 1) { dao.deleteTagRefsByMemoIds(existingMemoIds) }
            coVerify(exactly = 1) { dao.deleteMemoFtsByIds(existingMemoIds) }
            coVerify(exactly = 1) { dao.deleteMemosByDate("2024_01_15") }
        }

    @Test
    fun `apply deduplicates main memos before inserting fts`() =
        runTest {
            coEvery { dao.getMemosByDate("2024_01_16") } returns emptyList()

            val duplicatedMemoId = "memo_duplicated"
            val parseResult =
                MemoRefreshParseResult(
                    mainMemos =
                        listOf(
                            memoEntity(
                                id = duplicatedMemoId,
                                date = "2024_01_16",
                                content = "old-content",
                            ),
                            memoEntity(
                                id = duplicatedMemoId,
                                date = "2024_01_16",
                                content = "latest-content",
                            ),
                        ),
                    trashMemos = emptyList(),
                    metadataToUpdate = emptyList(),
                    mainDatesToReplace = setOf("2024_01_16"),
                    trashDatesToReplace = emptySet(),
                )

            applier.apply(parseResult, filesToDeleteInDb = emptySet())

            coVerify(exactly = 1) {
                dao.insertMemos(
                    match { memos ->
                        memos.size == 1 &&
                            memos.first().id == duplicatedMemoId &&
                            memos.first().content == "latest-content"
                    },
                )
            }
            coVerify(exactly = 1) {
                dao.replaceMemoFtsBatch(
                    match { entries ->
                        entries.size == 1 &&
                            entries.first().memoId == duplicatedMemoId &&
                            entries.first().content == "latest content"
                    },
                )
            }
        }

    @Test
    fun `apply executes through configured transaction runner`() =
        runTest {
            var transactionCalls = 0
            val transactionApplier =
                createApplier(
                    runInTransaction = { block ->
                        transactionCalls += 1
                        block()
                    },
                )
            coEvery { dao.getMemosByDate("2024_01_17") } returns emptyList()

            transactionApplier.apply(
                parseResult =
                    MemoRefreshParseResult(
                        mainMemos = emptyList(),
                        trashMemos = emptyList(),
                        metadataToUpdate = emptyList(),
                        mainDatesToReplace = setOf("2024_01_17"),
                        trashDatesToReplace = emptySet(),
                    ),
                filesToDeleteInDb = emptySet(),
            )

            assertEquals(1, transactionCalls)
        }

    @Test
    fun `apply forwards explicit import sync origin to memo version journal`() =
        runTest {
            val parseResult =
                MemoRefreshParseResult(
                    mainMemos =
                        listOf(
                            memoEntity(
                                id = "memo-imported",
                                date = "2024_01_18",
                                content = "imported-content",
                            ),
                        ),
                    trashMemos = emptyList(),
                    metadataToUpdate = emptyList(),
                    mainDatesToReplace = setOf("2024_01_18"),
                    trashDatesToReplace = emptySet(),
                )

            applier.apply(
                parseResult = parseResult,
                filesToDeleteInDb = emptySet(),
                origin = MemoRevisionOrigin.IMPORT_SYNC,
            )

            coVerify(exactly = 1) {
                memoVersionJournal.appendImportedRefreshRevisions(
                    any(),
                    MemoRevisionOrigin.IMPORT_SYNC,
                )
            }
        }

    private fun createApplier(
        runInTransaction: suspend (suspend () -> Unit) -> Unit = { block -> block() },
    ): MemoRefreshDbApplier =
        MemoRefreshDbApplier(
            memoDao = dao,
            memoWriteDao = dao,
            memoTagDao = dao,
            memoFtsDao = dao,
            memoTrashDao = dao,
            localFileStateDao = localFileStateDao,
            memoVersionJournal = memoVersionJournal,
            runInTransaction = runInTransaction,
        )

    private fun memoEntity(
        id: String,
        date: String,
        content: String,
    ): MemoEntity =
        MemoEntity(
            id = id,
            timestamp = 1_700_000_000_000,
            content = content,
            rawContent = "- 10:00:00 $content",
            date = date,
            tags = "",
            imageUrls = "",
        )
}
