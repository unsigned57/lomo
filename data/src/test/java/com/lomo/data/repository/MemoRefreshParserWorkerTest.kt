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



import com.lomo.data.local.dao.MemoDao
import com.lomo.data.local.entity.LocalFileStateEntity
import com.lomo.data.local.entity.MemoEntity
import com.lomo.data.local.entity.TrashMemoEntity
import com.lomo.data.parser.MarkdownParser
import com.lomo.data.source.FileMetadataWithId
import com.lomo.data.source.MarkdownStorageDataSource
import com.lomo.data.source.MemoDirectoryType
import com.lomo.domain.model.Memo
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: MemoRefreshParserWorker
 * - Behavior focus: main/trash parse routing, stable memo-id preservation across refresh reparse, metadata replacement
 *   output, null-content skipping, and filtering trash memos already active in the main DB.
 * - Observable outcomes: produced MemoEntity/TrashMemoEntity lists, LocalFileStateEntity metadata, date-replacement
 *   sets, and collaborator inputs.
 * - TDD proof: Fails before the fix when refresh reparses an edited memo with a content-derived parser id and replaces
 *   the existing stable memo id, which splits version history across different memo ids after refresh.
 * - Excludes: MarkdownParser parsing internals, Room implementation details, and file-storage backend transport behavior.
 */
class MemoRefreshParserWorkerTest : DataFunSpec() {
    init {
        beforeTest {
            setUp()
        }

        test("parse maps main and trash files into entities metadata and replacement dates") { `parse maps main and trash files into entities metadata and replacement dates`() }

        test("parse skips files whose contents cannot be read") { `parse skips files whose contents cannot be read`() }

        test("parse reuses existing main memo id for refreshed content with the same timestamp") { `parse reuses existing main memo id for refreshed content with the same timestamp`() }

        test("parse prefers same-content id when multiple existing memos share timestamp") { `parse prefers same-content id when multiple existing memos share timestamp`() }

        test("parse filters trash memos that already exist in main db while keeping metadata") { `parse filters trash memos that already exist in main db while keeping metadata`() }

        test("default file parse batch size stays within supported processor bounds") { `default file parse batch size stays within supported processor bounds`() }
    }


    @MockK(relaxed = true)
    private lateinit var markdownStorageDataSource: MarkdownStorageDataSource

    @MockK(relaxed = true)
    private lateinit var dao: MemoDao

    @MockK
    private lateinit var parser: MarkdownParser

    private lateinit var worker: MemoRefreshParserWorker

    private fun setUp() {
        MockKAnnotations.init(this)
        worker =
            MemoRefreshParserWorker(
                markdownStorageDataSource = markdownStorageDataSource,
                dao = dao,
                parser = parser,
            )
    }

    private fun `parse maps main and trash files into entities metadata and replacement dates`() =
        runTest {
            val mainMeta = fileMeta("2026_03_01.md", 101L, "main-doc", "content://lomo/main-doc")
            val trashMeta = fileMeta("2026_03_02.md", 202L, "trash-doc", "content://lomo/trash-doc")
            val mainDomainMemo = memo(id = "main_1", dateKey = "2026_03_01", content = "main content")
            val trashDomainMemo = memo(id = "trash_1", dateKey = "2026_03_02", content = "trash content")

            coEvery {
                markdownStorageDataSource.readFileByDocumentIdIn(MemoDirectoryType.MAIN, mainMeta.documentId)
            } returns "- 10:00 main content"
            coEvery {
                markdownStorageDataSource.readFileByDocumentIdIn(MemoDirectoryType.TRASH, trashMeta.documentId)
            } returns "- 11:00 trash content"
            every {
                parser.parseContent("- 10:00 main content", "2026_03_01", 101L)
            } returns listOf(mainDomainMemo)
            every {
                parser.parseContent("- 11:00 trash content", "2026_03_02", 202L)
            } returns listOf(trashDomainMemo)
            coEvery { dao.getMemosByIds(listOf("trash_1")) } returns emptyList()

            val result = worker.parse(listOf(mainMeta), listOf(trashMeta))

            result.mainMemos shouldBe listOf(MemoEntity.fromDomain(mainDomainMemo).copy(updatedAt = 101L))
            result.trashMemos shouldBe listOf(TrashMemoEntity.fromDomain(trashDomainMemo.copy(isDeleted = true)).copy(updatedAt = 202L))
            result.metadataToUpdate shouldBe listOf(
                    LocalFileStateEntity(
                        filename = "2026_03_01.md",
                        isTrash = false,
                        safUri = "content://lomo/main-doc",
                        lastKnownModifiedTime = 101L,
                    ),
                    LocalFileStateEntity(
                        filename = "2026_03_02.md",
                        isTrash = true,
                        safUri = null,
                        lastKnownModifiedTime = 202L,
                    ),
                )
            result.mainDatesToReplace shouldBe setOf("2026_03_01")
            result.trashDatesToReplace shouldBe setOf("2026_03_02")
            verify(exactly = 1) {
                parser.parseContent("- 10:00 main content", "2026_03_01", 101L)
            }
            verify(exactly = 1) {
                parser.parseContent("- 11:00 trash content", "2026_03_02", 202L)
            }
        }

    private fun `parse skips files whose contents cannot be read`() =
        runTest {
            val mainMeta = fileMeta("2026_03_03.md", 303L, "main-empty", "content://lomo/main-empty")
            val trashMeta = fileMeta("2026_03_04.md", 404L, "trash-empty", "content://lomo/trash-empty")

            coEvery {
                markdownStorageDataSource.readFileByDocumentIdIn(MemoDirectoryType.MAIN, mainMeta.documentId)
            } returns null
            coEvery {
                markdownStorageDataSource.readFileByDocumentIdIn(MemoDirectoryType.TRASH, trashMeta.documentId)
            } returns null

            val result = worker.parse(listOf(mainMeta), listOf(trashMeta))

            result.mainMemos shouldBe emptyList<MemoEntity>()
            result.trashMemos shouldBe emptyList<TrashMemoEntity>()
            result.metadataToUpdate shouldBe emptyList<LocalFileStateEntity>()
            result.mainDatesToReplace shouldBe emptySet<String>()
            result.trashDatesToReplace shouldBe emptySet<String>()
            verify(exactly = 0) { parser.parseContent(any(), any(), any()) }
            coVerify(exactly = 0) { dao.getMemosByIds(any()) }
        }

    private fun `parse reuses existing main memo id for refreshed content with the same timestamp`() =
        runTest {
            val mainMeta = fileMeta("2026_03_06.md", 606L, "main-stable-id", "content://lomo/main-stable-id")
            val existingMemo = memo(id = "memo_original", dateKey = "2026_03_06", content = "alpha")
            val reparsedMemo =
                existingMemo.copy(
                    id = "memo_reparsed",
                    content = "beta",
                    rawContent = "- 10:00 beta",
                )

            coEvery {
                markdownStorageDataSource.readFileByDocumentIdIn(MemoDirectoryType.MAIN, mainMeta.documentId)
            } returns "- 10:00 beta"
            every {
                parser.parseContent("- 10:00 beta", "2026_03_06", 606L)
            } returns listOf(reparsedMemo)
            coEvery { dao.getMemosByDates(listOf("2026_03_06")) } returns listOf(MemoEntity.fromDomain(existingMemo))

            val result = worker.parse(mainFilesToUpdate = listOf(mainMeta), trashFilesToUpdate = emptyList())

            result.mainMemos shouldBe listOf(MemoEntity.fromDomain(reparsedMemo.copy(id = existingMemo.id)).copy(updatedAt = 606L))
            coVerify(exactly = 1) { dao.getMemosByDates(listOf("2026_03_06")) }
            coVerify(exactly = 0) { dao.getMemosByDate("2026_03_06") }
        }

    private fun `parse prefers same-content id when multiple existing memos share timestamp`() =
        runTest {
            val mainMeta = fileMeta("2026_03_07.md", 707L, "main-stable-collision", "content://lomo/main-stable-collision")
            val existingAlpha = memo(id = "memo-existing-alpha", dateKey = "2026_03_07", content = "alpha")
            val existingBeta = memo(id = "memo-existing-beta", dateKey = "2026_03_07", content = "beta")
            val reparsedMemo =
                existingBeta.copy(
                    id = "memo-reparsed-random",
                    rawContent = "- 10:00 beta",
                )

            coEvery {
                markdownStorageDataSource.readFileByDocumentIdIn(MemoDirectoryType.MAIN, mainMeta.documentId)
            } returns "- 10:00 beta"
            every {
                parser.parseContent("- 10:00 beta", "2026_03_07", 707L)
            } returns listOf(reparsedMemo)
            coEvery { dao.getMemosByDates(listOf("2026_03_07")) } returns
                listOf(
                    MemoEntity.fromDomain(existingAlpha),
                    MemoEntity.fromDomain(existingBeta),
                )

            val result = worker.parse(mainFilesToUpdate = listOf(mainMeta), trashFilesToUpdate = emptyList())

            result.mainMemos.size shouldBe 1
            result.mainMemos.single().id shouldBe "memo-existing-beta"
            coVerify(exactly = 1) { dao.getMemosByDates(listOf("2026_03_07")) }
        }

    private fun `parse filters trash memos that already exist in main db while keeping metadata`() =
        runTest {
            val trashMeta = fileMeta("2026_03_05.md", 505L, "trash-filter", "content://lomo/trash-filter")
            val activeMemo = memo(id = "memo_active", dateKey = "2026_03_05", content = "active content")
            val archivedMemo = memo(id = "memo_archived", dateKey = "2026_03_05", content = "archived content")

            coEvery {
                markdownStorageDataSource.readFileByDocumentIdIn(MemoDirectoryType.TRASH, trashMeta.documentId)
            } returns "- 09:00 active\n- 09:30 archived"
            every {
                parser.parseContent("- 09:00 active\n- 09:30 archived", "2026_03_05", 505L)
            } returns listOf(activeMemo, archivedMemo)
            coEvery { dao.getMemosByIds(listOf("memo_active", "memo_archived")) } returns
                listOf(MemoEntity.fromDomain(activeMemo))

            val result = worker.parse(mainFilesToUpdate = emptyList(), trashFilesToUpdate = listOf(trashMeta))

            result.mainMemos shouldBe emptyList<MemoEntity>()
            result.trashMemos shouldBe listOf(TrashMemoEntity.fromDomain(archivedMemo.copy(isDeleted = true)).copy(updatedAt = 505L))
            result.metadataToUpdate shouldBe listOf(
                    LocalFileStateEntity(
                        filename = "2026_03_05.md",
                        isTrash = true,
                        lastKnownModifiedTime = 505L,
                    ),
                )
            result.mainDatesToReplace shouldBe emptySet<String>()
            result.trashDatesToReplace shouldBe setOf("2026_03_05")
        }

    private fun `default file parse batch size stays within supported processor bounds`() {
        defaultFileParseBatchSize(1) shouldBe 2
        defaultFileParseBatchSize(4) shouldBe 4
        defaultFileParseBatchSize(64) shouldBe 8
    }

    private fun fileMeta(
        filename: String,
        lastModified: Long,
        documentId: String,
        uriString: String?,
    ): FileMetadataWithId =
        FileMetadataWithId(
            filename = filename,
            lastModified = lastModified,
            documentId = documentId,
            uriString = uriString,
        )

    private fun memo(
        id: String,
        dateKey: String,
        content: String,
    ): Memo =
        Memo(
            id = id,
            timestamp = 1_700_000_000_000L,
            content = content,
            rawContent = "- 10:00 $content",
            dateKey = dateKey,
            localDate = LocalDate.of(2026, 3, 1),
            tags = listOf("sync"),
            imageUrls = listOf("cover.png"),
        )
}
