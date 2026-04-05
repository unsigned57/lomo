package com.lomo.data.repository

import com.lomo.data.local.entity.TrashMemoEntity
import com.lomo.data.source.FileMetadata
import com.lomo.data.local.dao.LocalFileStateDao
import com.lomo.data.source.FileDataSource
import com.lomo.data.source.MemoDirectoryType
import com.lomo.data.util.MemoTextProcessor
import com.lomo.domain.model.Memo
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: MemoTrashMutationHandler
 * - Behavior focus: file-only trash/restore branching, DB restore gating, unsafe destructive rejection, and attachment cleanup routing.
 * - Observable outcomes: returned Boolean success/failure, thrown unsafe mutation exceptions, file save/delete behavior, LocalFileState updates, and media delete branch selection.
 * - Red phase: Fails before the fix because top-level trash restore and permanent delete paths only log missing blocks instead of rejecting the unsafe mutation.
 * - Excludes: MemoTextProcessor internals, storage backend implementation details, and UI rendering behavior.
 */
class MemoTrashMutationHandlerTest {
    @MockK(relaxed = true)
    private lateinit var fileDataSource: FileDataSource

    @MockK(relaxed = true)
    private lateinit var memoDao: TestMemoDaoSuite

    @MockK(relaxed = true)
    private lateinit var localFileStateDao: LocalFileStateDao

    @MockK(relaxed = true)
    private lateinit var memoVersionJournal: MemoVersionJournal

    private lateinit var handler: MemoTrashMutationHandler

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        handler =
            MemoTrashMutationHandler(
                markdownStorageDataSource = fileDataSource,
                mediaStorageDataSource = fileDataSource,
                memoWriteDao = memoDao,
                memoTagDao = memoDao,
                memoFtsDao = memoDao,
                memoTrashDao = memoDao,
                memoSearchDao = memoDao,
                localFileStateDao = localFileStateDao,
                textProcessor = MemoTextProcessor(),
                memoVersionRecorder = AsyncMemoVersionRecorder(memoVersionJournal),
            )
    }

    @Test
    fun `moveToTrashFileOnly deletes main file and main local state when removed block empties file`() =
        runTest {
            val memo = buildMemo(rawContent = "- 10:00 memo to trash")
            val filename = "${memo.dateKey}.md"

            coEvery {
                localFileStateDao.getByFilename(filename, false)
            } returns null
            coEvery {
                fileDataSource.readFileIn(MemoDirectoryType.MAIN, filename)
            } returns memo.rawContent
            coEvery {
                fileDataSource.getFileMetadataIn(MemoDirectoryType.TRASH, filename)
            } returns FileMetadata(filename, 200L)

            val result = handler.moveToTrashFileOnly(memo)

            assertTrue(result)
            coVerify(exactly = 1) {
                fileDataSource.saveFileIn(
                    directory = MemoDirectoryType.TRASH,
                    filename = filename,
                    content = any(),
                    append = true,
                )
            }
            coVerify(exactly = 1) {
                fileDataSource.deleteFileIn(
                    MemoDirectoryType.MAIN,
                    filename,
                    any(),
                )
            }
            coVerify(exactly = 1) { localFileStateDao.deleteByFilename(filename, false) }
            coVerify(exactly = 0) {
                fileDataSource.saveFileIn(
                    directory = MemoDirectoryType.MAIN,
                    filename = filename,
                    content = any(),
                    append = false,
                    uri = any(),
                )
            }
            coVerify(exactly = 1) {
                localFileStateDao.upsert(
                    match {
                        it.filename == filename &&
                            it.isTrash &&
                            it.lastKnownModifiedTime == 200L
                    },
                )
            }
        }

    @Test
    fun `moveToTrashFileOnly rewrites main file and upserts main slash trash states when content remains`() =
        runTest {
            val memo = buildMemo(rawContent = "- 10:00 target memo")
            val filename = "${memo.dateKey}.md"

            coEvery {
                localFileStateDao.getByFilename(filename, false)
            } returns null
            coEvery {
                fileDataSource.readFileIn(MemoDirectoryType.MAIN, filename)
            } returns "- 09:59 keep this\n${memo.rawContent}"
            coEvery {
                fileDataSource.saveFileIn(
                    directory = MemoDirectoryType.MAIN,
                    filename = filename,
                    content = any(),
                    append = false,
                    uri = any(),
                )
            } returns "content://lomo/main/rewritten/$filename"
            coEvery {
                fileDataSource.getFileMetadataIn(MemoDirectoryType.MAIN, filename)
            } returns FileMetadata(filename, 300L)
            coEvery {
                fileDataSource.getFileMetadataIn(MemoDirectoryType.TRASH, filename)
            } returns FileMetadata(filename, 400L)

            val result = handler.moveToTrashFileOnly(memo)

            assertTrue(result)
            coVerify(exactly = 0) { fileDataSource.deleteFileIn(MemoDirectoryType.MAIN, filename, any()) }
            coVerify(exactly = 0) { localFileStateDao.deleteByFilename(filename, false) }
            coVerify(exactly = 1) {
                fileDataSource.saveFileIn(
                    directory = MemoDirectoryType.MAIN,
                    filename = filename,
                    content = "- 09:59 keep this",
                    append = false,
                    uri = any(),
                )
            }
            coVerify(exactly = 1) {
                localFileStateDao.upsert(
                    match {
                        it.filename == filename &&
                            !it.isTrash &&
                            it.safUri == "content://lomo/main/rewritten/$filename" &&
                            it.lastKnownModifiedTime == 300L
                    },
                )
            }
            coVerify(exactly = 1) {
                localFileStateDao.upsert(
                    match {
                        it.filename == filename &&
                            it.isTrash &&
                            it.lastKnownModifiedTime == 400L
                    },
                )
            }
        }

    @Test
    fun `restoreFromTrashFileOnly returns false and skips side effects when block is absent`() =
        runTest {
            val memo = buildMemo(rawContent = "- 10:00 missing memo")
            val filename = "${memo.dateKey}.md"

            coEvery {
                fileDataSource.readFileIn(MemoDirectoryType.TRASH, filename)
            } returns "- 09:30 another memo"

            val result = handler.restoreFromTrashFileOnly(memo)

            assertFalse(result)
            coVerify(exactly = 0) {
                fileDataSource.saveFileIn(
                    directory = MemoDirectoryType.MAIN,
                    filename = filename,
                    content = any(),
                    append = true,
                )
            }
            coVerify(exactly = 0) { fileDataSource.deleteFileIn(MemoDirectoryType.TRASH, filename) }
            coVerify(exactly = 0) { localFileStateDao.upsert(any()) }
            coVerify(exactly = 0) { localFileStateDao.deleteByFilename(any(), any()) }
        }

    @Test
    fun `restoreFromTrashFileOnly deletes trash file and updates main state when trash becomes empty`() =
        runTest {
            val memo = buildMemo(rawContent = "- 10:00 restore me")
            val filename = "${memo.dateKey}.md"

            coEvery {
                fileDataSource.readFileIn(MemoDirectoryType.TRASH, filename)
            } returns memo.rawContent
            coEvery {
                fileDataSource.getFileMetadataIn(MemoDirectoryType.MAIN, filename)
            } returns FileMetadata(filename, 520L)

            val result = handler.restoreFromTrashFileOnly(memo)

            assertTrue(result)
            coVerify(exactly = 1) {
                fileDataSource.saveFileIn(
                    directory = MemoDirectoryType.MAIN,
                    filename = filename,
                    content = any(),
                    append = true,
                )
            }
            coVerify(exactly = 1) { fileDataSource.deleteFileIn(MemoDirectoryType.TRASH, filename) }
            coVerify(exactly = 1) { localFileStateDao.deleteByFilename(filename, true) }
            coVerify(exactly = 0) {
                fileDataSource.saveFileIn(
                    directory = MemoDirectoryType.TRASH,
                    filename = filename,
                    content = any(),
                    append = false,
                )
            }
            coVerify(exactly = 1) {
                localFileStateDao.upsert(
                    match {
                        it.filename == filename &&
                            !it.isTrash &&
                            it.lastKnownModifiedTime == 520L
                    },
                )
            }
        }

    @Test
    fun `restoreFromTrashInDb returns false when source memo is missing`() =
        runTest {
            val memo = buildMemo(rawContent = "- 10:00 source absent")
            coEvery { memoDao.getTrashMemo(memo.id) } returns null

            val result = handler.restoreFromTrashInDb(memo)

            assertFalse(result)
            coVerify(exactly = 0) { memoDao.insertMemo(any()) }
            coVerify(exactly = 0) { memoDao.insertMemoFts(any()) }
            coVerify(exactly = 0) { memoDao.deleteTrashMemoById(any()) }
        }

    @Test(expected = UnsafeWorkspaceMutationException::class)
    fun `restoreFromTrash throws when target block cannot be matched safely`() =
        runTest {
            val memo = buildMemo(rawContent = "- 10:00 restore me")
            val filename = "${memo.dateKey}.md"
            coEvery { fileDataSource.readFileIn(MemoDirectoryType.TRASH, filename) } returns "- 09:30 another memo"

            handler.restoreFromTrash(memo)
        }

    @Test
    fun `restoreFromTrashInDb persists main memo and removes trash row when source exists`() =
        runTest {
            val sourceMemo =
                buildMemo(
                    id = "memo_restore",
                    rawContent = "- 11:00 source text",
                    content = "source text",
                    tags = listOf("sync", "restore"),
                    attachments = listOf("img.png"),
                    deleted = true,
                )
            coEvery {
                memoDao.getTrashMemo(sourceMemo.id)
            } returns TrashMemoEntity.fromDomain(sourceMemo)

            val result = handler.restoreFromTrashInDb(sourceMemo)

            assertTrue(result)
            coVerify(exactly = 1) {
                memoDao.insertMemo(
                    match {
                        it.id == sourceMemo.id &&
                            it.content == "source text" &&
                            it.tags == "sync,restore"
                    },
                )
            }
            coVerify(exactly = 1) {
                memoDao.insertMemoFts(
                    match {
                        it.memoId == sourceMemo.id && it.content.contains("source")
                    },
                )
            }
            coVerify(exactly = 1) { memoDao.replaceTagRefsForMemo(any()) }
            coVerify(exactly = 1) { memoDao.deleteTrashMemoById(sourceMemo.id) }
        }

    @Test
    fun `deleteFromTrashPermanently keeps attachment when referenced by another trash memo`() =
        runTest {
            val memo = buildMemo(attachments = listOf("shared.png"), deleted = true)
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
            val memo = buildMemo(attachments = listOf("orphan.png"), deleted = true)
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

    @Test(expected = UnsafeWorkspaceMutationException::class)
    fun `deleteFromTrashPermanently throws when target block cannot be matched safely`() =
        runTest {
            val memo = buildMemo(attachments = listOf("orphan.png"), deleted = true)
            coEvery {
                fileDataSource.readFileIn(MemoDirectoryType.TRASH, "${memo.dateKey}.md")
            } returns "- 09:30 another memo"

            handler.deleteFromTrashPermanently(memo)
        }

    @Test
    fun `deleteFromTrashPermanently routes unreferenced voice attachment to voice deletion API`() =
        runTest {
            val memo = buildMemo(attachments = listOf("voice_123.m4a"), deleted = true)
            coEvery {
                fileDataSource.readFileIn(MemoDirectoryType.TRASH, "${memo.dateKey}.md")
            } returns memo.rawContent
            coEvery {
                memoDao.countMemosAndTrashWithImage("voice_123.m4a", memo.id)
            } returns 0

            handler.deleteFromTrashPermanently(memo)

            coVerify(exactly = 1) { fileDataSource.deleteVoiceFile("voice_123.m4a") }
            coVerify(exactly = 0) { fileDataSource.deleteImage("voice_123.m4a") }
        }

    private fun buildMemo(
        id: String = "memo_1",
        rawContent: String = "- 10:00 memo with attachment",
        content: String = "memo with attachment",
        tags: List<String> = emptyList(),
        attachments: List<String> = emptyList(),
        deleted: Boolean = false,
    ): Memo =
        Memo(
            id = id,
            timestamp = 1L,
            content = content,
            rawContent = rawContent,
            dateKey = "2026_02_27",
            tags = tags,
            imageUrls = attachments,
            isDeleted = deleted,
        )
}
