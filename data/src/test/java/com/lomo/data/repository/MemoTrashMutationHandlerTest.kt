package com.lomo.data.repository

/**
 * Behavior Contract:
 * Capability: Kotest Migration
 * Scenarios: Given standard test execution, when tests run, then assertions hold.
 * Observable outcomes: Green tests
 * TDD proof: Compilation failure on Kotest transition
 * Excludes: none
 * 
 * Test Change Justification:
 * Reason category: Migration
 * Old behavior/assertion being replaced: JUnit4 assertions
 * Why old assertion is no longer correct: Transitioning to Kotest
 * Coverage preserved by: Kotest functional matching
 * Why this is not fitting the test to the implementation: Syntax translation
 */



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
import io.mockk.coVerifyOrder
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.test.runTest
import com.lomo.data.testing.DataFunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.booleans.shouldBeFalse

/*
 * Behavior Contract:
 * - Unit under test: MemoTrashMutationHandler
 * - Behavior focus: file-only trash/restore branching, DB restore gating, unsafe destructive rejection, and attachment cleanup routing.
 * - Observable outcomes: returned Boolean success/failure, thrown unsafe mutation exceptions, file save/delete behavior, LocalFileState updates, and media delete branch selection.
 * - TDD proof: Fails before the fix because top-level trash restore and permanent delete paths only log missing blocks instead of rejecting the unsafe mutation.
 * - Excludes: MemoTextProcessor internals, storage backend implementation details, and UI rendering behavior.
 */
class MemoTrashMutationHandlerTest : DataFunSpec() {
    init {
        beforeTest {
            setup()
        }

        test("moveToTrashFileOnly deletes main file and main local state when removed block empties file") { `moveToTrashFileOnly deletes main file and main local state when removed block empties file`() }

        test("moveToTrashFileOnly rewrites main file and upserts main slash trash states when content remains") { `moveToTrashFileOnly rewrites main file and upserts main slash trash states when content remains`() }

        test("restoreFromTrashFileOnly returns false and skips side effects when block is absent") { `restoreFromTrashFileOnly returns false and skips side effects when block is absent`() }

        test("restoreFromTrashFileOnly deletes trash file and updates main state when trash becomes empty") { `restoreFromTrashFileOnly deletes trash file and updates main state when trash becomes empty`() }

        test("restoreFromTrashFileOnly rewrites trash before appending back to main when trash content remains") { `restoreFromTrashFileOnly rewrites trash before appending back to main when trash content remains`() }

        test("restoreFromTrashInDb returns false when source memo is missing") { `restoreFromTrashInDb returns false when source memo is missing`() }

        test("moveToTrashInDb avoids direct fts deletion because triggers maintain index") { `moveToTrashInDb avoids direct fts deletion because triggers maintain index`() }

        test("restoreFromTrash throws when target block cannot be matched safely") { shouldThrow<UnsafeWorkspaceMutationException> { `restoreFromTrash throws when target block cannot be matched safely`() } }

        test("restoreFromTrashInDb persists main memo and removes trash row when source exists") { `restoreFromTrashInDb persists main memo and removes trash row when source exists`() }

        test("deleteFromTrashPermanently keeps attachment when referenced by another trash memo") { `deleteFromTrashPermanently keeps attachment when referenced by another trash memo`() }

        test("deleteFromTrashPermanently deletes attachment when no memo references remain") { `deleteFromTrashPermanently deletes attachment when no memo references remain`() }

        test("deleteFromTrashPermanently throws when target block cannot be matched safely") { shouldThrow<UnsafeWorkspaceMutationException> { `deleteFromTrashPermanently throws when target block cannot be matched safely`() } }

        test("deleteFromTrashPermanently routes unreferenced voice attachment to voice deletion API") { `deleteFromTrashPermanently routes unreferenced voice attachment to voice deletion API`() }

        test("clearAllTrashPermanently batches by date with one file and DB wipe instead of per memo") { `clearAllTrashPermanently batches by date with one file and DB wipe instead of per memo`() }

        test("clearAllTrashPermanently returns without touching storage when given empty list") { `clearAllTrashPermanently returns without touching storage when given empty list`() }
    }


    @MockK(relaxed = true)
    private lateinit var fileDataSource: FileDataSource

    @MockK(relaxed = true)
    private lateinit var memoDao: TestMemoDaoSuite

    @MockK(relaxed = true)
    private lateinit var localFileStateDao: LocalFileStateDao

    @MockK(relaxed = true)
    private lateinit var memoVersionJournal: MemoVersionJournal

    private lateinit var handler: MemoTrashMutationHandler

    private fun setup() {
        MockKAnnotations.init(this)
        handler =
            MemoTrashMutationHandler(
                markdownStorageDataSource = fileDataSource,
                mediaStorageDataSource = fileDataSource,
                memoWriteDao = memoDao,
                memoTagDao = memoDao,
                memoImageDao = memoDao,
                memoTrashDao = memoDao,
                memoSearchDao = memoDao,
                localFileStateDao = localFileStateDao,
                textProcessor = MemoTextProcessor(),
                memoVersionRecorder = AsyncMemoVersionRecorder(memoVersionJournal),
            )
    }

    private fun `moveToTrashFileOnly deletes main file and main local state when removed block empties file`() =
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

            (result).shouldBeTrue()
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

    private fun `moveToTrashFileOnly rewrites main file and upserts main slash trash states when content remains`() =
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

            (result).shouldBeTrue()
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

    private fun `restoreFromTrashFileOnly returns false and skips side effects when block is absent`() =
        runTest {
            val memo = buildMemo(rawContent = "- 10:00 missing memo")
            val filename = "${memo.dateKey}.md"

            coEvery {
                fileDataSource.readFileIn(MemoDirectoryType.TRASH, filename)
            } returns "- 09:30 another memo"

            val result = handler.restoreFromTrashFileOnly(memo)

            (result).shouldBeFalse()
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

    private fun `restoreFromTrashFileOnly deletes trash file and updates main state when trash becomes empty`() =
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

            (result).shouldBeTrue()
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

    private fun `restoreFromTrashFileOnly rewrites trash before appending back to main when trash content remains`() =
        runTest {
            val memo = buildMemo(rawContent = "- 10:00 restore me")
            val filename = "${memo.dateKey}.md"
            val remainingTrashContent = "- 09:30 keep me"

            coEvery {
                fileDataSource.readFileIn(MemoDirectoryType.TRASH, filename)
            } returns "$remainingTrashContent\n${memo.rawContent}"
            coEvery {
                fileDataSource.getFileMetadataIn(MemoDirectoryType.TRASH, filename)
            } returns FileMetadata(filename, 610L)
            coEvery {
                fileDataSource.getFileMetadataIn(MemoDirectoryType.MAIN, filename)
            } returns FileMetadata(filename, 620L)

            val result = handler.restoreFromTrashFileOnly(memo)

            (result).shouldBeTrue()
            coVerify(exactly = 1) {
                fileDataSource.saveFileIn(
                    directory = MemoDirectoryType.TRASH,
                    filename = filename,
                    content = remainingTrashContent,
                    append = false,
                )
            }
            coVerify(exactly = 1) {
                fileDataSource.saveFileIn(
                    directory = MemoDirectoryType.MAIN,
                    filename = filename,
                    content = any(),
                    append = true,
                )
            }
            coVerifyOrder {
                fileDataSource.saveFileIn(
                    directory = MemoDirectoryType.TRASH,
                    filename = filename,
                    content = remainingTrashContent,
                    append = false,
                )
                fileDataSource.saveFileIn(
                    directory = MemoDirectoryType.MAIN,
                    filename = filename,
                    content = any(),
                    append = true,
                )
            }
            coVerify(exactly = 1) {
                localFileStateDao.upsert(
                    match {
                        it.filename == filename &&
                            it.isTrash &&
                            it.lastKnownModifiedTime == 610L
                    },
                )
            }
            coVerify(exactly = 1) {
                localFileStateDao.upsert(
                    match {
                        it.filename == filename &&
                            !it.isTrash &&
                            it.lastKnownModifiedTime == 620L
                    },
                )
            }
            coVerify(exactly = 0) { fileDataSource.deleteFileIn(MemoDirectoryType.TRASH, filename) }
            coVerify(exactly = 0) { localFileStateDao.deleteByFilename(filename, true) }
        }

    private fun `restoreFromTrashInDb returns false when source memo is missing`() =
        runTest {
            val memo = buildMemo(rawContent = "- 10:00 source absent")
            coEvery { memoDao.getTrashMemo(memo.id) } returns null

            val result = handler.restoreFromTrashInDb(memo)

            (result).shouldBeFalse()
            coVerify(exactly = 0) { memoDao.insertMemo(any()) }
            coVerify(exactly = 0) { memoDao.deleteTrashMemoById(any()) }
        }

    private fun `moveToTrashInDb avoids direct fts deletion because triggers maintain index`() =
        runTest {
            val memo = buildMemo(id = "memo_trash_db")

            handler.moveToTrashInDb(memo)

            coVerify(exactly = 1) { memoDao.deleteMemoById(memo.id) }
            coVerify(exactly = 1) { memoDao.deleteTagRefsByMemoId(memo.id) }
            coVerify(exactly = 0) { memoDao.rebuildFts() }
            coVerify(exactly = 1) {
                memoDao.insertTrashMemo(
                    match { trash -> trash.id == memo.id && trash.content == memo.content },
                )
            }
        }

    fun `restoreFromTrash throws when target block cannot be matched safely`() =
        runTest {
            val memo = buildMemo(rawContent = "- 10:00 restore me")
            val filename = "${memo.dateKey}.md"
            coEvery { fileDataSource.readFileIn(MemoDirectoryType.TRASH, filename) } returns "- 09:30 another memo"

            handler.restoreFromTrash(memo)
        }

    private fun `restoreFromTrashInDb persists main memo and removes trash row when source exists`() =
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

            (result).shouldBeTrue()
            coVerify(exactly = 1) {
                memoDao.insertMemo(
                    match {
                        it.id == sourceMemo.id &&
                            it.content == "source text" &&
                            it.tags == """["sync","restore"]"""
                    },
                )
            }
            coVerify(exactly = 1) { memoDao.replaceTagRefsForMemo(any()) }
            coVerify(exactly = 1) { memoDao.replaceImageRefsForMemo(any()) }
            coVerify(exactly = 0) { memoDao.rebuildFts() }
            coVerify(exactly = 1) { memoDao.deleteTrashMemoById(sourceMemo.id) }
        }

    private fun `deleteFromTrashPermanently keeps attachment when referenced by another trash memo`() =
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

    private fun `deleteFromTrashPermanently deletes attachment when no memo references remain`() =
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
            coVerify(exactly = 1) { memoDao.deleteImageRefsByMemoId(memo.id) }
            coVerify(exactly = 1) { memoDao.deleteTrashMemoById(memo.id) }
        }

    fun `deleteFromTrashPermanently throws when target block cannot be matched safely`() =
        runTest {
            val memo = buildMemo(attachments = listOf("orphan.png"), deleted = true)
            coEvery {
                fileDataSource.readFileIn(MemoDirectoryType.TRASH, "${memo.dateKey}.md")
            } returns "- 09:30 another memo"

            handler.deleteFromTrashPermanently(memo)
        }

    private fun `deleteFromTrashPermanently routes unreferenced voice attachment to voice deletion API`() =
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

    private fun `clearAllTrashPermanently batches by date with one file and DB wipe instead of per memo`() =
        runTest {
            val memoA1 = TrashMemoEntity.fromDomain(buildMemo(id = "a1", deleted = true).copy(dateKey = "2026_03_01"))
            val memoA2 = TrashMemoEntity.fromDomain(buildMemo(id = "a2", deleted = true).copy(dateKey = "2026_03_01"))
            val memoB1 = TrashMemoEntity.fromDomain(buildMemo(id = "b1", deleted = true).copy(dateKey = "2026_03_02"))

            handler.clearAllTrashPermanently(listOf(memoA1, memoA2, memoB1))

            coVerify(exactly = 1) { fileDataSource.deleteFileIn(MemoDirectoryType.TRASH, "2026_03_01.md") }
            coVerify(exactly = 1) { fileDataSource.deleteFileIn(MemoDirectoryType.TRASH, "2026_03_02.md") }
            coVerify(exactly = 1) { localFileStateDao.deleteByFilename("2026_03_01.md", true) }
            coVerify(exactly = 1) { localFileStateDao.deleteByFilename("2026_03_02.md", true) }
            coVerify(exactly = 1) { memoDao.deleteImageRefsByMemoIds(listOf("a1", "a2", "b1")) }
            coVerify(exactly = 1) { memoDao.clearTrash() }
            coVerify(exactly = 0) { memoDao.deleteTrashMemoById(any()) }
            coVerify(exactly = 0) { fileDataSource.saveFileIn(MemoDirectoryType.TRASH, any(), any(), any(), any()) }
        }

    private fun `clearAllTrashPermanently returns without touching storage when given empty list`() =
        runTest {
            handler.clearAllTrashPermanently(emptyList())

            coVerify(exactly = 0) { fileDataSource.deleteFileIn(any(), any(), any()) }
            coVerify(exactly = 0) { memoDao.clearTrash() }
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
