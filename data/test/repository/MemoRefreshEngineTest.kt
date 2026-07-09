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
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.slot
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import com.lomo.data.testing.DataFunSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldNotBeNull
import com.lomo.data.source.FileMetadataWithId

/*
 * Behavior Contract:
 * - Unit under test: MemoRefreshEngine
 * - Behavior focus: refresh deletion planning when file listings temporarily miss previously known memo files.
 * - Observable outcomes: filesToDeleteInDb forwarded to MemoRefreshDbApplier, pending-missing metadata updates,
 *   missing-state reset when files reappear, and streamed metadata access through the workspace reader.
 * - TDD proof: Fails before the fix when a file is deleted from the DB on the first confirmed-missing refresh
 *   instead of entering a pending-deletion confirmation state.
 * - Excludes: Room transaction behavior, parser internals, and storage backend implementation details.
 */
class MemoRefreshEngineTest : DataFunSpec() {
    init {
        beforeTest {
            setUp()
        }

        test("refresh uses directory-scoped local file state queries without per-file probes") { `refresh uses directory-scoped local file state queries without per-file probes`() }

        test("refresh keeps first confirmed missing main file pending instead of deleting immediately") { `refresh keeps first confirmed missing main file pending instead of deleting immediately`() }

        test("refresh keeps confirmed missing main file in delete set after second confirmation") { `refresh keeps confirmed missing main file in delete set after second confirmation`() }

        test("refresh clears pending missing state when file reappears unchanged") { `refresh clears pending missing state when file reappears unchanged`() }

        test("refresh target routes imported file updates through shared applier instead of direct inserts") { `refresh target routes imported file updates through shared applier instead of direct inserts`() }

        test("refresh target routes missing imported file through shared applier delete set") { `refresh target routes missing imported file through shared applier delete set`() }
    }


    @MockK
    private lateinit var markdownStorageDataSource: MarkdownStorageDataSource

    @MockK(relaxed = true)
    private lateinit var localFileStateDao: LocalFileStateDao

    @MockK
    private lateinit var refreshParserWorker: MemoRefreshParserWorker

    @MockK(relaxed = true)
    private lateinit var refreshDbApplier: MemoRefreshDbApplier

    private lateinit var engine: MemoRefreshEngine

    private fun setUp() {
        MockKAnnotations.init(this)
        engine =
            MemoRefreshEngine(
                workspaceReader =
                    testMemoWorkspaceReader(
                        markdownStorageDataSource = markdownStorageDataSource,
                        localFileStateDao = localFileStateDao,
                    ),
                localFileStateDao = localFileStateDao,
                refreshPlanner = MemoRefreshPlanner,
                refreshParserWorker = refreshParserWorker,
                refreshDbApplier = refreshDbApplier,
            )
    }

    private fun `refresh uses directory-scoped local file state queries without per-file probes`() =
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

            coEvery { localFileStateDao.getAllByTrashStatus(false) } returns listOf(knownState)
            coEvery { localFileStateDao.getAllByTrashStatus(true) } returns emptyList()
            stubMetadataStream(MemoDirectoryType.MAIN)
            stubMetadataStream(MemoDirectoryType.TRASH)
            coEvery { refreshParserWorker.parse(emptyList(), emptyList()) } returns parseResult

            engine.refresh()

            coVerify(exactly = 1) { localFileStateDao.getAllByTrashStatus(false) }
            coVerify(exactly = 1) { localFileStateDao.getAllByTrashStatus(true) }
            coVerify(exactly = 0) {
                markdownStorageDataSource.getFileMetadataIn(MemoDirectoryType.MAIN, any())
            }
            coVerify(exactly = 0) { markdownStorageDataSource.listMetadataWithIdsIn(any()) }
            coVerify(exactly = 1) { refreshDbApplier.apply(parseResult, capture(capturedDeleteSet)) }
            capturedDeleteSet.captured shouldBe emptySet<Pair<String, Boolean>>()
        }

    private fun `refresh keeps first confirmed missing main file pending instead of deleting immediately`() =
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

            coEvery { localFileStateDao.getAllByTrashStatus(false) } returns listOf(knownState)
            coEvery { localFileStateDao.getAllByTrashStatus(true) } returns emptyList()
            stubMetadataStream(MemoDirectoryType.MAIN)
            stubMetadataStream(MemoDirectoryType.TRASH)
            coEvery { refreshParserWorker.parse(emptyList(), emptyList()) } returns parseResult

            engine.refresh()

            coVerify(exactly = 1) { localFileStateDao.upsertAll(capture(capturedStateUpdates)) }
            coVerify(exactly = 1) { refreshDbApplier.apply(parseResult, capture(capturedDeleteSet)) }
            capturedDeleteSet.captured shouldBe emptySet<Pair<String, Boolean>>()
            capturedStateUpdates.captured.size shouldBe 1
            capturedStateUpdates.captured.single().missingCount shouldBe 1
            capturedStateUpdates.captured.single().missingSince.shouldNotBeNull()
        }

    private fun `refresh keeps confirmed missing main file in delete set after second confirmation`() =
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

            coEvery { localFileStateDao.getAllByTrashStatus(false) } returns listOf(knownState)
            coEvery { localFileStateDao.getAllByTrashStatus(true) } returns emptyList()
            stubMetadataStream(MemoDirectoryType.MAIN)
            stubMetadataStream(MemoDirectoryType.TRASH)
            coEvery { refreshParserWorker.parse(emptyList(), emptyList()) } returns parseResult

            engine.refresh()

            coVerify(exactly = 1) { refreshDbApplier.apply(parseResult, capture(capturedDeleteSet)) }
            capturedDeleteSet.captured shouldBe setOf("2026_03_25.md" to false)
        }

    private fun `refresh clears pending missing state when file reappears unchanged`() =
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

            coEvery { localFileStateDao.getAllByTrashStatus(false) } returns listOf(knownState)
            coEvery { localFileStateDao.getAllByTrashStatus(true) } returns emptyList()
            stubMetadataStream(
                MemoDirectoryType.MAIN,
                FileMetadataWithId(
                        filename = "2026_03_25.md",
                        lastModified = 1_000L,
                        documentId = "doc-1",
                ),
            )
            stubMetadataStream(MemoDirectoryType.TRASH)
            coEvery { refreshParserWorker.parse(emptyList(), emptyList()) } returns emptyParseResult()

            engine.refresh()

            coVerify(exactly = 1) { localFileStateDao.upsertAll(capture(capturedStateUpdates)) }
            capturedStateUpdates.captured.size shouldBe 1
            capturedStateUpdates.captured.single().missingCount shouldBe 0
            capturedStateUpdates.captured.single().missingSince shouldBe null
        }

    private fun `refresh target routes imported file updates through shared applier instead of direct inserts`() =
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
                                searchContent = "imported from s3",
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
                markdownStorageDataSource.getFileMetadataIn(MemoDirectoryType.MAIN, targetFilename)
            } returns FileMetadata(filename = targetFilename, lastModified = refreshedFile.lastModified)
            coEvery {
                markdownStorageDataSource.readFileIn(MemoDirectoryType.MAIN, targetFilename)
            } returns refreshedFile.content
            coEvery { refreshParserWorker.parseMainFileContents(listOf(refreshedFile)) } returns parsedParseResult

            engine.refresh(targetFilename)

            coVerify(exactly = 1) { refreshDbApplier.apply(capture(capturedParseResult), emptySet()) }
            coVerify(exactly = 0) { localFileStateDao.upsert(any()) }
            capturedParseResult.captured.mainDatesToReplace shouldBe setOf("2026_03_25")
            capturedParseResult.captured.mainMemos.map { it.id } shouldBe listOf("memo-imported")
            capturedParseResult.captured.metadataToUpdate.map { it.lastKnownModifiedTime } shouldBe listOf(refreshedFile.lastModified)
        }

    private fun `refresh target routes missing imported file through shared applier delete set`() =
        runTest {
            val targetFilename = "2026_03_25.md"
            val capturedParseResult = slot<MemoRefreshParseResult>()
            val capturedDeleteSet = slot<Set<Pair<String, Boolean>>>()

            coEvery {
                markdownStorageDataSource.getFileMetadataIn(MemoDirectoryType.MAIN, targetFilename)
            } returns null

            engine.refresh(targetFilename)

            coVerify(exactly = 1) {
                refreshDbApplier.apply(capture(capturedParseResult), capture(capturedDeleteSet))
            }
            capturedParseResult.captured.mainMemos shouldBe emptyList<MemoEntity>()
            capturedDeleteSet.captured shouldBe setOf(targetFilename to false)
        }

    private fun emptyParseResult() =
        MemoRefreshParseResult(
            mainMemos = emptyList(),
            trashMemos = emptyList(),
            metadataToUpdate = emptyList(),
            mainDatesToReplace = emptySet(),
            trashDatesToReplace = emptySet(),
        )

    private fun stubMetadataStream(
        directory: MemoDirectoryType,
        vararg metadata: FileMetadataWithId,
    ) {
        every { markdownStorageDataSource.streamMetadataWithIdsIn(directory) } returns
            if (metadata.isEmpty()) {
                emptyFlow()
            } else {
                flowOf(*metadata)
            }
    }
}
