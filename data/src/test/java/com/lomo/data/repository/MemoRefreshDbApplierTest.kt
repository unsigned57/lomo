package com.lomo.data.repository

import com.lomo.data.local.dao.LocalFileStateDao
import com.lomo.data.local.dao.MemoDao
import com.lomo.data.local.entity.MemoEntity
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class MemoRefreshDbApplierTest {
    @MockK(relaxed = true)
    private lateinit var dao: MemoDao

    @MockK(relaxed = true)
    private lateinit var localFileStateDao: LocalFileStateDao

    private lateinit var applier: MemoRefreshDbApplier

    @Before
    fun setUp() {
        MockKAnnotations.init(this)
        applier = MemoRefreshDbApplier(dao, localFileStateDao)
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
            coVerify(exactly = 1) { dao.insertMemoFts(match { it.memoId == duplicatedMemoId }) }
        }

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
