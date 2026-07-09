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



import com.lomo.data.source.FileMetadata
import com.lomo.data.local.dao.LocalFileStateDao
import com.lomo.data.parser.MarkdownParser
import com.lomo.data.source.FileDataSource
import com.lomo.data.source.MemoDirectoryType
import com.lomo.data.testing.projectedTrashMemoEntity
import com.lomo.data.util.MemoTextProcessor
import com.lomo.domain.model.Memo
import com.lomo.domain.usecase.MemoIdentityPolicy
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
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: MemoTrashMutationHandler
 * - Behavior focus: file-only trash/restore branching, DB restore gating,
 *   permanent-delete missing-block classification, and unsafe destructive rejection.
 * - Observable outcomes: returned Boolean success/failure, thrown unsafe mutation
 *   exceptions, file save/delete behavior, and LocalFileState updates.
 * - TDD proof: Direct permanent-delete handler cases were removed after the
 *   focused boundary test proved permanent delete must be owned by lifecycle
 *   outbox completion instead of this handler as a public success path. The
 *   missing-trash-block permanent-delete case fails before the repair because
 *   the handler returns generic success for a missing block.
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

        test("deleteFromTrashFileOnly returns missing trash block without mutating trash file state") {
            `deleteFromTrashFileOnly returns missing trash block without mutating trash file state`()
        }

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
                workspaceStore =
                    testMemoWorkspaceStore(
                        markdownStorageDataSource = fileDataSource,
                        localFileStateDao = localFileStateDao,
                    ),
                memoWriteDao = memoDao,
                memoTagDao = memoDao,
                memoImageDao = memoDao,
                memoTrashDao = memoDao,
                memoVersionRecorder = AsyncMemoVersionRecorder(memoVersionJournal, immediateTestBackgroundScope()),
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
                    rawContent = "- 11:00 source text #sync #restore ![img](img.png)",
                    content = "source text #sync #restore ![img](img.png)",
                    tags = listOf("stale"),
                    attachments = listOf("stale.png"),
                    deleted = true,
                )
            coEvery {
                memoDao.getTrashMemo(sourceMemo.id)
            } returns projectedTrashMemoEntity(sourceMemo)

            val result = handler.restoreFromTrashInDb(sourceMemo)

            (result).shouldBeTrue()
            coVerify(exactly = 1) {
                memoDao.insertMemo(
                    match {
                        it.id == sourceMemo.id &&
                            it.content == "source text #sync #restore ![img](img.png)" &&
                            it.tags == """["sync","restore"]""" &&
                            it.imageUrls == """["img.png"]"""
                    },
                )
            }
            coVerify(exactly = 1) { memoDao.replaceTagRefsForMemo(any()) }
            coVerify(exactly = 1) { memoDao.replaceImageRefsForMemo(any()) }
            coVerify(exactly = 0) { memoDao.rebuildFts() }
            coVerify(exactly = 1) { memoDao.deleteTrashMemoById(sourceMemo.id) }
        }

    private fun `deleteFromTrashFileOnly returns missing trash block without mutating trash file state`() =
        runTest {
            val memo = buildMemo(rawContent = "- 10:00 missing permanent delete", deleted = true)
            val command = MemoLifecycleCommand.permanentDelete(memo)
            val filename = "${memo.dateKey}.md"
            coEvery { fileDataSource.readFileIn(MemoDirectoryType.TRASH, filename) } returns "- 09:30 another memo"

            val result = handler.deleteFromTrashFileOnly(command)

            result shouldBe PermanentDeleteTrashFileCompletion.MissingTrashBlock
            coVerify(exactly = 0) { fileDataSource.deleteFileIn(MemoDirectoryType.TRASH, filename) }
            coVerify(exactly = 0) {
                fileDataSource.saveFileIn(
                    directory = MemoDirectoryType.TRASH,
                    filename = filename,
                    content = any(),
                    append = false,
                )
            }
            coVerify(exactly = 0) { localFileStateDao.deleteByFilename(filename, true) }
            coVerify(exactly = 0) { localFileStateDao.upsert(any()) }
        }

    private fun buildMemo(
        id: String? = null,
        rawContent: String = "- 10:00 memo with attachment",
        content: String? = null,
        tags: List<String> = emptyList(),
        attachments: List<String> = emptyList(),
        deleted: Boolean = false,
    ): Memo {
        val dateKey = "2026_02_27"
        val parsedMemo =
            MarkdownParser(MemoTextProcessor(), MemoIdentityPolicy())
                .parseContent(rawContent, dateKey)
                .singleOrNull()

        return Memo(
            id = id ?: requireNotNull(parsedMemo) { "Test memo raw content must parse to one memo block" }.id,
            timestamp = parsedMemo?.timestamp ?: 1L,
            content = content ?: parsedMemo?.content ?: rawContent,
            rawContent = rawContent,
            dateKey = dateKey,
            tags = tags,
            imageUrls = attachments,
            isDeleted = deleted,
        )
    }
}
