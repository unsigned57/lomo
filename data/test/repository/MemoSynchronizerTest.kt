package com.lomo.data.repository

import com.lomo.data.local.dao.LocalFileStateDao
import com.lomo.data.local.entity.LocalFileStateEntity
import com.lomo.data.local.entity.MemoFileOutboxEntity
import com.lomo.data.local.entity.MemoFileOutboxOp
import com.lomo.data.parser.MarkdownParser
import com.lomo.data.source.FileDataSource
import com.lomo.data.source.FileMetadata
import com.lomo.data.source.FileMetadataWithId
import com.lomo.data.source.MemoDirectoryType
import com.lomo.data.util.MemoTextProcessor
import com.lomo.domain.usecase.MemoIdentityPolicy
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import java.time.LocalDateTime
import java.time.ZoneId
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: MemoSynchronizer
 * - Owning layer: data repository orchestration.
 * - Priority tier: P0.
 * - Capability: refresh workspace shards into DB state and queue DB-first save lifecycle commands
 *   for workspace-store file completion.
 *
 * Scenarios:
 * - Given changed markdown shard metadata, when refresh runs, then parsed memo projections are
 *   applied to the DAO.
 * - Given no changed shard metadata, when refresh runs, then memo rows are not rewritten.
 * - Given a new memo save, when saveMemo completes, then a CREATE lifecycle outbox command carries
 *   the canonical raw content for later workspace-store append.
 * - Given minute-granularity storage format, when saveMemo completes, then the persisted DB memo
 *   timestamp matches the canonical storage timestamp.
 *
 * Observable outcomes:
 * - DAO writes, persisted file-state metadata, captured lifecycle outbox rows, and canonicalized
 *   stored memo entity content.
 *
 * TDD proof:
 * - Save command assertions fail before the DB-first lifecycle boundary because saveMemo writes
 *   markdown directly instead of persisting a CREATE outbox command for workspace-store completion.
 *
 * Excludes:
 * - Room integration, filesystem backend implementation, background outbox drain timing, and
 *   UI-facing rendering.
 */
/**
 * Unit tests for MemoSynchronizer. These tests verify the synchronization logic between file system
 * and database.
 */
class MemoSynchronizerTest : DataFunSpec() {
    init {
        beforeTest {
            setup()
        }

        test("refresh syncs new memos from files to database") { `refresh syncs new memos from files to database`() }

        test("refresh handles empty file list") { `refresh handles empty file list`() }

        test("refresh with target filename uses listFiles path") { `refresh with target filename uses listFiles path`() }

        test("refresh does not clear when files exist but unchanged") { `refresh does not clear when files exist but unchanged`() }

        test("saveMemo queues create lifecycle command for new memo") { `saveMemo queues create lifecycle command for new memo`() }

        test("saveMemo queues create lifecycle command with canonical raw content") {
            `saveMemo queues create lifecycle command with canonical raw content`()
        }

        test("saveMemo stores canonical timestamp for HH_mm format") { `saveMemo stores canonical timestamp for HH_mm format`() }
    }


    @MockK private lateinit var fileDataSource: FileDataSource

    @MockK private lateinit var memoDao: TestMemoDaoSuite

    @MockK private lateinit var localFileStateDao: LocalFileStateDao

    @MockK private lateinit var dataStore: com.lomo.data.local.datastore.LomoDataStore

    @MockK private lateinit var memoVersionJournal: MemoVersionJournal

    private lateinit var processor: MemoTextProcessor
    private lateinit var parser: MarkdownParser
    private lateinit var memoIdentityPolicy: MemoIdentityPolicy
    private lateinit var synchronizer: MemoSynchronizer

    private fun createSynchronizer(): MemoSynchronizer {
        val workspaceStore =
            testMemoWorkspaceStore(
                markdownStorageDataSource = fileDataSource,
                localFileStateDao = localFileStateDao,
                textProcessor = processor,
                memoIdentityPolicy = memoIdentityPolicy,
                parser = parser,
            )
        val workspaceReader =
            testMemoWorkspaceReader(
                markdownStorageDataSource = fileDataSource,
                localFileStateDao = localFileStateDao,
            )
        val workspaceProjector =
            testMemoWorkspaceProjector(
                markdownStorageDataSource = fileDataSource,
                localFileStateDao = localFileStateDao,
                textProcessor = processor,
                memoIdentityPolicy = memoIdentityPolicy,
                parser = parser,
            )
        val mutationHandler =
            MemoMutationHandler(
                markdownStorageDataSource = fileDataSource,
                mediaStorageDataSource = fileDataSource,
                daoBundle = testMemoMutationDaoBundle(memoDao),
                memoStatisticsDao = memoDao,
                localFileStateDao = localFileStateDao,
                workspaceStore = workspaceStore,
                workspaceMediaAccess = ThrowingWorkspaceMediaAccess,
                savePlanFactory = MemoSavePlanFactory(parser, processor, memoIdentityPolicy),
                textProcessor = processor,
                dataStore = dataStore,
                trashMutationHandler =
                    MemoTrashMutationHandler(
                        workspaceStore = workspaceStore,
                        memoWriteDao = memoDao,
                        memoTagDao = memoDao,
                        memoImageDao = memoDao,
                        memoTrashDao = memoDao,
                        memoVersionRecorder = AsyncMemoVersionRecorder(memoVersionJournal, immediateTestBackgroundScope()),
                ),
                memoIdentityPolicy = memoIdentityPolicy,
                memoVersionJournal = memoVersionJournal,
                mediaRepository = ThrowingMediaRepository,
                s3LocalChangeRecorder = NoOpS3LocalChangeRecorder,
                webDavLocalChangeRecorder = TestNoOpWebDavLocalChangeRecorder,
                backgroundScope = immediateTestBackgroundScope(),
            )
        val refreshEngine =
            MemoRefreshEngine(
                workspaceReader = workspaceReader,
                localFileStateDao = localFileStateDao,
                refreshPlanner = MemoRefreshPlanner,
                refreshParserWorker =
                    MemoRefreshParserWorker(
                        workspaceProjector = workspaceProjector,
                        dao = memoDao,
                    ),
                refreshDbApplier =
                    MemoRefreshDbApplier(
                        memoDao = memoDao,
                        memoWriteDao = memoDao,
                        memoTagDao = memoDao,
                        memoImageDao = memoDao,
                        memoTrashDao = memoDao,
                        localFileStateDao = localFileStateDao,
                        memoVersionJournal = memoVersionJournal,
                    ) { block -> block() },
            )

        return MemoSynchronizer(
            refreshEngine = refreshEngine,
            mutationHandler = mutationHandler,
            outboxScope = immediateTestBackgroundScope(),
            startOutboxCoordinator = false,
        )
    }

    private fun setup() {
        MockKAnnotations.init(this, relaxed = true)
        processor = MemoTextProcessor()
        memoIdentityPolicy = MemoIdentityPolicy()
        parser = MarkdownParser(processor, memoIdentityPolicy)

        // Mock default formats
        coEvery { dataStore.storageFilenameFormat } returns
            kotlinx.coroutines.flow.flowOf("yyyy_MM_dd")
        coEvery { dataStore.storageTimestampFormat } returns
            kotlinx.coroutines.flow.flowOf("HH:mm:ss")
        synchronizer = createSynchronizer()
    }

    private fun `refresh syncs new memos from files to database`() =
        runTest {
            // MemoSynchronizer.refresh() uses incremental sync with metadata + document ID
            val metadata =
                FileMetadataWithId(
                    filename = "2024_01_15.md",
                    lastModified = System.currentTimeMillis(),
                    documentId = "doc123",
                )
            val fileContent = "- 10:30:00 Test memo content"

            // Mock the incremental sync path
            every { fileDataSource.streamMetadataWithIdsIn(MemoDirectoryType.MAIN) } returns flowOf(metadata)
            every { fileDataSource.streamMetadataWithIdsIn(MemoDirectoryType.TRASH) } returns flowOf()
            every {
                fileDataSource.streamFileByDocumentIdIn(MemoDirectoryType.MAIN, "doc123")
            } returns flowOf(fileContent)
            coEvery { fileDataSource.readFileByDocumentIdIn(MemoDirectoryType.MAIN, "doc123") } returns fileContent
            coEvery { localFileStateDao.getAllByTrashStatus(false) } returns emptyList()
            coEvery { localFileStateDao.getAllByTrashStatus(true) } returns emptyList()

            synchronizer.refresh()

            // Should insert the parsed memo
            coVerify { memoDao.insertMemos(any()) }
        }

    private fun `refresh handles empty file list`() =
        runTest {
            every { fileDataSource.streamMetadataWithIdsIn(MemoDirectoryType.MAIN) } returns flowOf()
            every { fileDataSource.streamMetadataWithIdsIn(MemoDirectoryType.TRASH) } returns flowOf()
            coEvery { localFileStateDao.getAllByTrashStatus(false) } returns emptyList()
            coEvery { localFileStateDao.getAllByTrashStatus(true) } returns emptyList()

            // Should not crash
            synchronizer.refresh()
        }

    private fun `refresh with target filename uses listFiles path`() =
        runTest {
            // When targetFilename is specified, refresh reads just that file via metadata + readFileIn.
            val content = "- 10:30:00 Test memo content"
            val lastModified = System.currentTimeMillis()

            coEvery {
                fileDataSource.getFileMetadataIn(MemoDirectoryType.MAIN, "2024_01_15.md")
            } returns FileMetadata(filename = "2024_01_15.md", lastModified = lastModified)
            coEvery {
                fileDataSource.readFileIn(MemoDirectoryType.MAIN, "2024_01_15.md")
            } returns content
            coEvery { localFileStateDao.getByFilename("2024_01_15.md", false) } returns null

            synchronizer.refresh(targetFilename = "2024_01_15.md")

            // Should insert the parsed memo via syncFiles
            coVerify { memoDao.insertMemos(any()) }
        }

    private fun `refresh does not clear when files exist but unchanged`() =
        runTest {
            // When files exist but are unchanged (in sync metadata), nothing should be deleted
            val metadata =
                FileMetadataWithId(
                    filename = "2024_01_15.md",
                    lastModified = 1000L,
                    documentId = "doc123",
                )
            val syncEntity =
                LocalFileStateEntity(
                    filename = "2024_01_15.md",
                    lastKnownModifiedTime = 1000L, // Same as metadata - file unchanged
                )

            every { fileDataSource.streamMetadataWithIdsIn(MemoDirectoryType.MAIN) } returns flowOf(metadata)
            every { fileDataSource.streamMetadataWithIdsIn(MemoDirectoryType.TRASH) } returns flowOf()
            coEvery { localFileStateDao.getAllByTrashStatus(false) } returns listOf(syncEntity)
            coEvery { localFileStateDao.getAllByTrashStatus(true) } returns emptyList()

            synchronizer.refresh()

            // Should NOT insert or delete anything (file unchanged)
            coVerify(exactly = 0) { memoDao.insertMemos(any()) }
            coVerify(exactly = 0) { memoDao.clearAll() }
        }

    private fun `saveMemo queues create lifecycle command for new memo`() =
        runTest {
            val timestamp = System.currentTimeMillis()
            val content = "New memo content"
            val outboxSlot = slot<MemoFileOutboxEntity>()

            // Mock that memo doesn't exist (for unique ID check)
            coEvery { memoDao.getMemo(any()) } returns null
            coEvery { memoDao.insertMemoFileOutbox(capture(outboxSlot)) } returns 1L

            synchronizer.saveMemo(content, timestamp)

            outboxSlot.captured.operation shouldBe MemoFileOutboxOp.CREATE
            outboxSlot.captured.newContent shouldBe content
            outboxSlot.captured.createRawContent shouldBe outboxSlot.captured.memoRawContent
            coVerify(exactly = 0) {
                fileDataSource.saveFileIn(
                    MemoDirectoryType.MAIN,
                    any(),
                    any(),
                    any(),
                    any(),
                )
            }
        }

    private fun `saveMemo queues create lifecycle command with canonical raw content`() =
        runTest {
            val timestamp = System.currentTimeMillis()
            val content = "Another memo"
            val outboxSlot = slot<MemoFileOutboxEntity>()

            // Mock that memo doesn't exist (for unique ID check)
            coEvery { memoDao.getMemo(any()) } returns null
            coEvery { memoDao.insertMemoFileOutbox(capture(outboxSlot)) } returns 2L

            synchronizer.saveMemo(content, timestamp)

            outboxSlot.captured.operation shouldBe MemoFileOutboxOp.CREATE
            outboxSlot.captured.createRawContent shouldBe outboxSlot.captured.memoRawContent
            outboxSlot.captured.createRawContent?.contains(content) shouldBe true
            coVerify(exactly = 0) {
                fileDataSource.saveFileIn(
                    MemoDirectoryType.MAIN,
                    any(),
                    any(),
                    any(),
                    any(),
                )
            }
        }

    private fun `saveMemo stores canonical timestamp for HH_mm format`() =
        runTest {
            val zone = ZoneId.systemDefault()
            val inputTimestamp =
                LocalDateTime
                    .of(2024, 1, 15, 12, 30, 45, 123_000_000)
                    .atZone(zone)
                    .toInstant()
                    .toEpochMilli()
            val expectedTimestamp =
                LocalDateTime
                    .of(2024, 1, 15, 12, 30, 0, 0)
                    .atZone(zone)
                    .toInstant()
                    .toEpochMilli()

            coEvery { dataStore.storageFilenameFormat } returns kotlinx.coroutines.flow.flowOf("yyyy_MM_dd")
            coEvery { dataStore.storageTimestampFormat } returns kotlinx.coroutines.flow.flowOf("HH:mm")
            synchronizer = createSynchronizer()
            coEvery { memoDao.getMemo(any()) } returns null
            coEvery { fileDataSource.readFileIn(MemoDirectoryType.MAIN, "2024_01_15.md") } returns null
            coEvery {
                fileDataSource.saveFileIn(
                    MemoDirectoryType.MAIN,
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns "uri"
            coEvery {
                fileDataSource.getFileMetadataIn(
                    MemoDirectoryType.MAIN,
                    "2024_01_15.md",
                )
            } returns FileMetadata("2024_01_15.md", 1000L)
            coEvery { localFileStateDao.getByFilename("2024_01_15.md", false) } returns null
            coEvery { memoDao.insertMemoFileOutbox(any()) } returns 3L

            val memoEntitySlot = slot<com.lomo.data.local.entity.MemoEntity>()
            coEvery { memoDao.insertMemo(capture(memoEntitySlot)) } returns Unit

            synchronizer.saveMemo("canonical time", inputTimestamp)

            memoEntitySlot.captured.timestamp shouldBe expectedTimestamp
        }
}
