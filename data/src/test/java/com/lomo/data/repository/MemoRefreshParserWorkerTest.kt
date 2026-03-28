package com.lomo.data.repository

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
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/*
 * Test Contract:
 * - Unit under test: MemoRefreshParserWorker
 * - Behavior focus: main/trash parse routing, metadata replacement output, null-content skipping, and filtering trash memos already active in the main DB.
 * - Observable outcomes: produced MemoEntity/TrashMemoEntity lists, LocalFileStateEntity metadata, date-replacement sets, and collaborator inputs.
 * - Red phase: Not applicable - test-only coverage addition; no production change.
 * - Excludes: MarkdownParser parsing internals, Room implementation details, and file-storage backend transport behavior.
 */
class MemoRefreshParserWorkerTest {
    @MockK(relaxed = true)
    private lateinit var markdownStorageDataSource: MarkdownStorageDataSource

    @MockK(relaxed = true)
    private lateinit var dao: MemoDao

    @MockK
    private lateinit var parser: MarkdownParser

    private lateinit var worker: MemoRefreshParserWorker

    @Before
    fun setUp() {
        MockKAnnotations.init(this)
        worker =
            MemoRefreshParserWorker(
                markdownStorageDataSource = markdownStorageDataSource,
                dao = dao,
                parser = parser,
            )
    }

    @Test
    fun `parse maps main and trash files into entities metadata and replacement dates`() =
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

            assertEquals(
                listOf(MemoEntity.fromDomain(mainDomainMemo).copy(updatedAt = 101L)),
                result.mainMemos,
            )
            assertEquals(
                listOf(TrashMemoEntity.fromDomain(trashDomainMemo.copy(isDeleted = true)).copy(updatedAt = 202L)),
                result.trashMemos,
            )
            assertEquals(
                listOf(
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
                ),
                result.metadataToUpdate,
            )
            assertEquals(setOf("2026_03_01"), result.mainDatesToReplace)
            assertEquals(setOf("2026_03_02"), result.trashDatesToReplace)
            verify(exactly = 1) {
                parser.parseContent("- 10:00 main content", "2026_03_01", 101L)
            }
            verify(exactly = 1) {
                parser.parseContent("- 11:00 trash content", "2026_03_02", 202L)
            }
        }

    @Test
    fun `parse skips files whose contents cannot be read`() =
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

            assertEquals(emptyList<MemoEntity>(), result.mainMemos)
            assertEquals(emptyList<TrashMemoEntity>(), result.trashMemos)
            assertEquals(emptyList<LocalFileStateEntity>(), result.metadataToUpdate)
            assertEquals(emptySet<String>(), result.mainDatesToReplace)
            assertEquals(emptySet<String>(), result.trashDatesToReplace)
            verify(exactly = 0) { parser.parseContent(any(), any(), any()) }
            coVerify(exactly = 0) { dao.getMemosByIds(any()) }
        }

    @Test
    fun `parse filters trash memos that already exist in main db while keeping metadata`() =
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

            assertEquals(emptyList<MemoEntity>(), result.mainMemos)
            assertEquals(
                listOf(TrashMemoEntity.fromDomain(archivedMemo.copy(isDeleted = true)).copy(updatedAt = 505L)),
                result.trashMemos,
            )
            assertEquals(
                listOf(
                    LocalFileStateEntity(
                        filename = "2026_03_05.md",
                        isTrash = true,
                        lastKnownModifiedTime = 505L,
                    ),
                ),
                result.metadataToUpdate,
            )
            assertEquals(emptySet<String>(), result.mainDatesToReplace)
            assertEquals(setOf("2026_03_05"), result.trashDatesToReplace)
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
