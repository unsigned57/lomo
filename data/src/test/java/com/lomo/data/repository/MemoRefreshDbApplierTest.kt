package com.lomo.data.repository

import com.lomo.data.local.dao.LocalFileStateDao
import com.lomo.data.local.dao.ROOM_MAX_BIND_PARAMETER_COUNT
import com.lomo.data.local.entity.MemoEntity
import com.lomo.data.local.entity.TrashMemoEntity
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
    fun `apply removes stale main rows when replacing main date`() =
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
            coVerify(exactly = 0) { dao.rebuildFts() }
            coVerify(exactly = 1) { dao.deleteMemosByIds(existingMemoIds) }
            coVerify(exactly = 0) { dao.deleteMemosByDate(any()) }
        }

    @Test
    fun `apply deduplicates main memos before inserting rows`() =
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
        }

    @Test
    fun `apply relies on trigger managed fts updates for persisted main memos`() =
        runTest {
            coEvery { dao.getMemosByDate("2024_01_21") } returns emptyList()

            val parseResult =
                MemoRefreshParseResult(
                    mainMemos =
                        listOf(
                            memoEntity(
                                id = "memo-searchable",
                                date = "2024_01_21",
                                content = "tokenized searchable content",
                            ),
                        ),
                    trashMemos = emptyList(),
                    metadataToUpdate = emptyList(),
                    mainDatesToReplace = setOf("2024_01_21"),
                    trashDatesToReplace = emptySet(),
            )

            applier.apply(parseResult, filesToDeleteInDb = emptySet())

            coVerify(exactly = 0) { dao.rebuildFts() }
        }

    @Test
    fun `apply refreshes exact image refs for main and trash memos`() =
        runTest {
            val parseResult =
                MemoRefreshParseResult(
                    mainMemos =
                        listOf(
                            memoEntity(
                                id = "memo-main",
                                date = "2024_01_20",
                                content = "main",
                                imageUrls = "a.png,a.png,aaa.png",
                            ),
                        ),
                    trashMemos =
                        listOf(
                            trashMemoEntity(
                                id = "memo-trash",
                                date = "2024_01_20",
                                content = "trash",
                                imageUrls = "trash.png",
                            ),
                        ),
                    metadataToUpdate = emptyList(),
                    mainDatesToReplace = setOf("2024_01_20"),
                    trashDatesToReplace = setOf("2024_01_20"),
                )

            applier.apply(parseResult, filesToDeleteInDb = emptySet())

            coVerify(exactly = 1) {
                dao.replaceImageRefsForMemos(
                    match { memos ->
                        memos.size == 1 &&
                            memos.first().id == "memo-main" &&
                            memos.first().imageUrls == "a.png,a.png,aaa.png"
                    },
                )
            }
            coVerify(exactly = 1) {
                dao.replaceImageRefsForTrashMemos(
                    match { memos ->
                        memos.size == 1 &&
                            memos.first().id == "memo-trash" &&
                            memos.first().imageUrls == "trash.png"
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

    fun `apply keeps memos present in both db and parse result untouched`() =
        runTest {
            val stableId = "memo_stable"
            val staleId = "memo_stale"
            coEvery { dao.getMemosByDate("2024_01_19") } returns
                listOf(
                    memoEntity(id = stableId, date = "2024_01_19", content = "content-stable"),
                    memoEntity(id = staleId, date = "2024_01_19", content = "content-stale"),
                )

            val parseResult =
                MemoRefreshParseResult(
                    mainMemos =
                        listOf(
                            memoEntity(id = stableId, date = "2024_01_19", content = "content-stable"),
                        ),
                    trashMemos = emptyList(),
                    metadataToUpdate = emptyList(),
                    mainDatesToReplace = setOf("2024_01_19"),
                    trashDatesToReplace = emptySet(),
                )

            applier.apply(parseResult, filesToDeleteInDb = emptySet())

            coVerify(exactly = 1) { dao.deleteTagRefsByMemoIds(listOf(staleId)) }
            coVerify(exactly = 1) { dao.deleteMemosByIds(listOf(staleId)) }
            coVerify(exactly = 0) { dao.deleteMemosByDate(any()) }
        }

    @Test
    fun `apply chunks stale main memo deletions to stay under sqlite bind limit`() =
        runTest {
            val date = "2024_02_01"
            val staleMemos =
                (1..(ROOM_MAX_BIND_PARAMETER_COUNT + 1)).map { index ->
                    memoEntity(
                        id = "memo-stale-$index",
                        date = date,
                        content = "stale-$index",
                    )
                }
            coEvery { dao.getMemosByDate(date) } returns staleMemos

            val parseResult =
                MemoRefreshParseResult(
                    mainMemos = emptyList(),
                    trashMemos = emptyList(),
                    metadataToUpdate = emptyList(),
                    mainDatesToReplace = setOf(date),
                    trashDatesToReplace = emptySet(),
                )

            applier.apply(parseResult, filesToDeleteInDb = emptySet())

            coVerify(exactly = 1) {
                dao.deleteTagRefsByMemoIds(match { ids -> ids.size == ROOM_MAX_BIND_PARAMETER_COUNT })
            }
            coVerify(exactly = 1) {
                dao.deleteTagRefsByMemoIds(match { ids -> ids.size == 1 })
            }
            coVerify(exactly = 1) {
                dao.deleteImageRefsByMemoIds(match { ids -> ids.size == ROOM_MAX_BIND_PARAMETER_COUNT })
            }
            coVerify(exactly = 1) {
                dao.deleteImageRefsByMemoIds(match { ids -> ids.size == 1 })
            }
            coVerify(exactly = 1) {
                dao.deleteMemosByIds(match { ids -> ids.size == ROOM_MAX_BIND_PARAMETER_COUNT })
            }
            coVerify(exactly = 1) {
                dao.deleteMemosByIds(match { ids -> ids.size == 1 })
            }
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
            memoImageDao = dao,
            memoTrashDao = dao,
            localFileStateDao = localFileStateDao,
            memoVersionJournal = memoVersionJournal,
            runInTransaction = runInTransaction,
        )

    private fun memoEntity(
        id: String,
        date: String,
        content: String,
        imageUrls: String = "",
    ): MemoEntity =
        MemoEntity(
            id = id,
            timestamp = 1_700_000_000_000,
            content = content,
            rawContent = "- 10:00:00 $content",
            date = date,
            tags = "",
            imageUrls = imageUrls,
        )

    private fun trashMemoEntity(
        id: String,
        date: String,
        content: String,
        imageUrls: String = "",
    ): TrashMemoEntity =
        TrashMemoEntity(
            id = id,
            timestamp = 1_700_000_000_000,
            content = content,
            rawContent = "- 10:00:00 $content",
            date = date,
            tags = "",
            imageUrls = imageUrls,
        )
}
