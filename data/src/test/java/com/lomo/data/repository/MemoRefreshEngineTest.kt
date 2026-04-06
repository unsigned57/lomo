package com.lomo.data.repository

import com.lomo.data.local.dao.LocalFileStateDao
import com.lomo.data.local.entity.LocalFileStateEntity
import com.lomo.data.local.entity.MemoEntity
import com.lomo.data.source.FileContent
import com.lomo.data.source.FileMetadata
import com.lomo.data.source.MarkdownStorageDataSource
import com.lomo.data.source.MemoDirectoryType
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.impl.annotations.MockK
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: MemoRefreshEngine
 * - Behavior focus: refresh deletion planning when file listings temporarily miss previously known memo files.
 * - Observable outcomes: filesToDeleteInDb forwarded to MemoRefreshDbApplier, pending-missing metadata updates,
 *   and missing-state reset when files reappear.
 * - Red phase: Fails before the fix when a file is deleted from the DB on the first confirmed-missing refresh
 *   instead of entering a pending-deletion confirmation state.
 * - Excludes: Room transaction behavior, parser internals, and storage backend implementation details.
 */
class MemoRefreshEngineTest {
    @MockK
    private lateinit var markdownStorageDataSource: MarkdownStorageDataSource

    @MockK(relaxed = true)
    private lateinit var localFileStateDao: LocalFileStateDao

    @MockK
    private lateinit var refreshParserWorker: MemoRefreshParserWorker

    @MockK(relaxed = true)
    private lateinit var refreshDbApplier: MemoRefreshDbApplier

    private lateinit var engine: MemoRefreshEngine

    @Before
    fun setUp() {
        MockKAnnotations.init(this)
        engine =
            MemoRefreshEngine(
                markdownStorageDataSource = markdownStorageDataSource,
                localFileStateDao = localFileStateDao,
                refreshPlanner = MemoRefreshPlanner,
                refreshParserWorker = refreshParserWorker,
                refreshDbApplier = refreshDbApplier,
            )
    }

    @Test
    fun `refresh does not delete db records when missing main file is still resolvable`() =
        runTest {
            val knownState =
                LocalFileStateEntity(
                    filename = "2026_03_25.md",
                    isTrash = false,
                    safUri = "content://lomo/2026_03_25",
                    lastKnownModifiedTime = 1_000L,
                )
            val parseResult = emptyParseResult()
            val capturedDeleteSet = slot<Set<Pair<String, Boolean>>>()

            coEvery { localFileStateDao.getAll() } returns listOf(knownState)
            coEvery { markdownStorageDataSource.listMetadataWithIdsIn(MemoDirectoryType.MAIN) } returns emptyList()
            coEvery { markdownStorageDataSource.listMetadataWithIdsIn(MemoDirectoryType.TRASH) } returns emptyList()
            coEvery {
                markdownStorageDataSource.getFileMetadataIn(MemoDirectoryType.MAIN, "2026_03_25.md")
            } returns FileMetadata(filename = "2026_03_25.md", lastModified = 2_000L)
            coEvery { refreshParserWorker.parse(emptyList(), emptyList()) } returns parseResult

            engine.refresh()

            coVerify(exactly = 1) {
                markdownStorageDataSource.getFileMetadataIn(MemoDirectoryType.MAIN, "2026_03_25.md")
            }
            coVerify(exactly = 1) { refreshDbApplier.apply(parseResult, capture(capturedDeleteSet)) }
            assertEquals(emptySet<Pair<String, Boolean>>(), capturedDeleteSet.captured)
        }

    @Test
    fun `refresh keeps first confirmed missing main file pending instead of deleting immediately`() =
        runTest {
            val knownState =
                LocalFileStateEntity(
                    filename = "2026_03_25.md",
                    isTrash = false,
                    safUri = null,
                    lastKnownModifiedTime = 1_000L,
                )
            val parseResult = emptyParseResult()
            val capturedDeleteSet = slot<Set<Pair<String, Boolean>>>()
            val capturedStateUpdates = slot<List<LocalFileStateEntity>>()

            coEvery { localFileStateDao.getAll() } returns listOf(knownState)
            coEvery { markdownStorageDataSource.listMetadataWithIdsIn(MemoDirectoryType.MAIN) } returns emptyList()
            coEvery { markdownStorageDataSource.listMetadataWithIdsIn(MemoDirectoryType.TRASH) } returns emptyList()
            coEvery {
                markdownStorageDataSource.getFileMetadataIn(MemoDirectoryType.MAIN, "2026_03_25.md")
            } returns null
            coEvery { refreshParserWorker.parse(emptyList(), emptyList()) } returns parseResult

            engine.refresh()

            coVerify(exactly = 1) { localFileStateDao.upsertAll(capture(capturedStateUpdates)) }
            coVerify(exactly = 1) { refreshDbApplier.apply(parseResult, capture(capturedDeleteSet)) }
            assertEquals(emptySet<Pair<String, Boolean>>(), capturedDeleteSet.captured)
            assertEquals(1, capturedStateUpdates.captured.size)
            assertEquals(1, capturedStateUpdates.captured.single().missingCount)
            assertNotNull(capturedStateUpdates.captured.single().missingSince)
        }

    @Test
    fun `refresh keeps confirmed missing main file in delete set after second confirmation`() =
        runTest {
            val knownState =
                LocalFileStateEntity(
                    filename = "2026_03_25.md",
                    isTrash = false,
                    safUri = null,
                    lastKnownModifiedTime = 1_000L,
                    missingSince = 500L,
                    missingCount = 1,
                )
            val parseResult = emptyParseResult()
            val capturedDeleteSet = slot<Set<Pair<String, Boolean>>>()

            coEvery { localFileStateDao.getAll() } returns listOf(knownState)
            coEvery { markdownStorageDataSource.listMetadataWithIdsIn(MemoDirectoryType.MAIN) } returns emptyList()
            coEvery { markdownStorageDataSource.listMetadataWithIdsIn(MemoDirectoryType.TRASH) } returns emptyList()
            coEvery {
                markdownStorageDataSource.getFileMetadataIn(MemoDirectoryType.MAIN, "2026_03_25.md")
            } returns null
            coEvery { refreshParserWorker.parse(emptyList(), emptyList()) } returns parseResult

            engine.refresh()

            coVerify(exactly = 1) { refreshDbApplier.apply(parseResult, capture(capturedDeleteSet)) }
            assertEquals(setOf("2026_03_25.md" to false), capturedDeleteSet.captured)
        }

    @Test
    fun `refresh clears pending missing state when file reappears unchanged`() =
        runTest {
            val knownState =
                LocalFileStateEntity(
                    filename = "2026_03_25.md",
                    isTrash = false,
                    safUri = null,
                    lastKnownModifiedTime = 1_000L,
                    missingSince = 500L,
                    missingCount = 1,
                )
            val capturedStateUpdates = slot<List<LocalFileStateEntity>>()

            coEvery { localFileStateDao.getAll() } returns listOf(knownState)
            coEvery {
                markdownStorageDataSource.listMetadataWithIdsIn(MemoDirectoryType.MAIN)
            } returns
                listOf(
                    com.lomo.data.source.FileMetadataWithId(
                        filename = "2026_03_25.md",
                        lastModified = 1_000L,
                        documentId = "doc-1",
                    ),
                )
            coEvery { markdownStorageDataSource.listMetadataWithIdsIn(MemoDirectoryType.TRASH) } returns emptyList()
            coEvery { refreshParserWorker.parse(emptyList(), emptyList()) } returns emptyParseResult()

            engine.refresh()

            coVerify(exactly = 1) { localFileStateDao.upsertAll(capture(capturedStateUpdates)) }
            assertEquals(1, capturedStateUpdates.captured.size)
            assertEquals(0, capturedStateUpdates.captured.single().missingCount)
            assertEquals(null, capturedStateUpdates.captured.single().missingSince)
        }

    @Test
    fun `refresh target routes imported file updates through shared applier instead of direct inserts`() =
        runTest {
            val targetFilename = "2026_03_25.md"
            val refreshedFile =
                FileContent(
                    filename = targetFilename,
                    content = "- 09:00 imported from s3",
                    lastModified = 2_000L,
                )
            val parsedParseResult =
                MemoRefreshParseResult(
                    mainMemos =
                        listOf(
                            MemoEntity(
                                id = "memo-imported",
                                timestamp = 1_700_000_000_000,
                                updatedAt = refreshedFile.lastModified,
                                content = "imported from s3",
                                rawContent = refreshedFile.content,
                                date = "2026_03_25",
                                tags = "",
                                imageUrls = "",
                            ),
                        ),
                    trashMemos = emptyList(),
                    metadataToUpdate =
                        listOf(
                            LocalFileStateEntity(
                                filename = targetFilename,
                                isTrash = false,
                                lastKnownModifiedTime = refreshedFile.lastModified,
                            ),
                        ),
                    mainDatesToReplace = setOf("2026_03_25"),
                    trashDatesToReplace = emptySet(),
                )
            val capturedParseResult = slot<MemoRefreshParseResult>()

            coEvery {
                markdownStorageDataSource.listFilesIn(MemoDirectoryType.MAIN, targetFilename)
            } returns listOf(refreshedFile)
            coEvery { refreshParserWorker.parseMainFileContents(listOf(refreshedFile)) } returns parsedParseResult

            engine.refresh(targetFilename)

            coVerify(exactly = 1) { refreshDbApplier.apply(capture(capturedParseResult), emptySet()) }
            coVerify(exactly = 0) { localFileStateDao.upsert(any()) }
            assertEquals(setOf("2026_03_25"), capturedParseResult.captured.mainDatesToReplace)
            assertEquals(listOf("memo-imported"), capturedParseResult.captured.mainMemos.map { it.id })
            assertEquals(
                listOf(refreshedFile.lastModified),
                capturedParseResult.captured.metadataToUpdate.map { it.lastKnownModifiedTime },
            )
        }

    @Test
    fun `refresh target routes missing imported file through shared applier delete set`() =
        runTest {
            val targetFilename = "2026_03_25.md"
            val capturedParseResult = slot<MemoRefreshParseResult>()
            val capturedDeleteSet = slot<Set<Pair<String, Boolean>>>()

            coEvery {
                markdownStorageDataSource.listFilesIn(MemoDirectoryType.MAIN, targetFilename)
            } returns emptyList()

            engine.refresh(targetFilename)

            coVerify(exactly = 1) {
                refreshDbApplier.apply(capture(capturedParseResult), capture(capturedDeleteSet))
            }
            assertEquals(emptyList<MemoEntity>(), capturedParseResult.captured.mainMemos)
            assertEquals(setOf(targetFilename to false), capturedDeleteSet.captured)
        }

    private fun emptyParseResult() =
        MemoRefreshParseResult(
            mainMemos = emptyList(),
            trashMemos = emptyList(),
            metadataToUpdate = emptyList(),
            mainDatesToReplace = emptySet(),
            trashDatesToReplace = emptySet(),
        )
}
