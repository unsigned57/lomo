package com.lomo.data.repository

import com.lomo.data.local.dao.LocalFileStateDao
import com.lomo.data.local.dao.MemoDao
import com.lomo.data.source.FileDataSource
import com.lomo.data.source.MemoDirectoryType
import com.lomo.data.util.MemoTextProcessor
import com.lomo.domain.model.Memo
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class MemoTrashMutationHandlerTest {
    @MockK(relaxed = true)
    private lateinit var fileDataSource: FileDataSource

    @MockK(relaxed = true)
    private lateinit var memoDao: MemoDao

    @MockK(relaxed = true)
    private lateinit var localFileStateDao: LocalFileStateDao

    private lateinit var handler: MemoTrashMutationHandler

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        handler =
            MemoTrashMutationHandler(
                fileDataSource = fileDataSource,
                dao = memoDao,
                localFileStateDao = localFileStateDao,
                textProcessor = MemoTextProcessor(),
            )
    }

    @Test
    fun `deleteFromTrashPermanently keeps attachment when referenced by another trash memo`() =
        runTest {
            val memo = buildTrashMemo(attachment = "shared.png")
            coEvery {
                fileDataSource.readFileIn(MemoDirectoryType.TRASH, "${memo.dateKey}.md")
            } returns memo.rawContent
            coEvery {
                memoDao.countMemosAndTrashWithImage("shared.png", memo.id)
            } returns 1

            handler.deleteFromTrashPermanently(memo)

            coVerify(exactly = 1) {
                memoDao.countMemosAndTrashWithImage("shared.png", memo.id)
            }
            coVerify(exactly = 0) { fileDataSource.deleteImage("shared.png") }
            coVerify(exactly = 1) { memoDao.deleteTrashMemoById(memo.id) }
        }

    @Test
    fun `deleteFromTrashPermanently deletes attachment when no memo references remain`() =
        runTest {
            val memo = buildTrashMemo(attachment = "orphan.png")
            coEvery {
                fileDataSource.readFileIn(MemoDirectoryType.TRASH, "${memo.dateKey}.md")
            } returns memo.rawContent
            coEvery {
                memoDao.countMemosAndTrashWithImage("orphan.png", memo.id)
            } returns 0

            handler.deleteFromTrashPermanently(memo)

            coVerify(exactly = 1) {
                memoDao.countMemosAndTrashWithImage("orphan.png", memo.id)
            }
            coVerify(exactly = 1) { fileDataSource.deleteImage("orphan.png") }
            coVerify(exactly = 1) { memoDao.deleteTrashMemoById(memo.id) }
        }

    private fun buildTrashMemo(attachment: String): Memo =
        Memo(
            id = "memo_1",
            timestamp = 1L,
            content = "memo with attachment",
            rawContent = "- 10:00 memo with attachment",
            dateKey = "2026_02_27",
            imageUrls = listOf(attachment),
            isDeleted = true,
        )
}
