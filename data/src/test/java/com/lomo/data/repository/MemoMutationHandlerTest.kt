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
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.local.entity.LocalFileStateEntity
import com.lomo.data.local.entity.MemoFileOutboxEntity
import com.lomo.data.local.entity.MemoFileOutboxIdentityPolicy
import com.lomo.data.local.entity.MemoFileOutboxOp
import com.lomo.data.source.FileDataSource
import com.lomo.data.source.FileMetadata
import com.lomo.data.source.MemoDirectoryType
import com.lomo.data.testing.projectedMemoEntity
import com.lomo.data.util.MemoTextProcessor
import com.lomo.domain.model.Memo
import com.lomo.domain.usecase.MemoIdentityPolicy
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.just
import io.mockk.runs
import io.mockk.slot
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue

/*
 * Behavior Contract:
 * - Unit under test: MemoMutationHandler
 * - Behavior focus: metadata persistence, updatedAt refresh, storage format caching, unsafe rewrite visibility, and edit-time orphan attachment cleanup.
 * - Observable outcomes: false outbox flush result, LocalFileState writes, persisted updatedAt, cached flow collection counts, and voice/image deletion dispatch when references disappear.
 * - TDD proof: "updateMemo deletes unreferenced voice attachment removed by edit" fails before the fix because UpdateMemoMutationDelegate never ran orphan cleanup on the removed attachment set.
 * - Excludes: Room transaction internals, UI rendering, and retired capture storage mechanics.
 */
class MemoMutationHandlerTest : DataFunSpec() {
    init {
        beforeTest {
            setUp()
        }

        test("flushMemoFileOutbox create stores file metadata modified time in local file state") {
            `flushMemoFileOutbox create stores file metadata modified time in local file state`()
        }

        test("flushMemoFileOutbox create stores metadata modified time in local file state") { `flushMemoFileOutbox create stores metadata modified time in local file state`() }

        test("flushMemoFileOutbox create records memo upsert for s3 incremental journal") {
            `flushMemoFileOutbox create records memo upsert for s3 incremental journal`()
        }

        test("flushDeleteMemoToFile records memo delete when main file disappears") { `flushDeleteMemoToFile records memo delete when main file disappears`() }

        test("updateMemoInDb refreshes updatedAt") { `updateMemoInDb refreshes updatedAt`() }

        test("updateMemoInDb relies on trigger managed fts and avoids direct fts writes") { `updateMemoInDb relies on trigger managed fts and avoids direct fts writes`() }

        test("update outbox flush returns false when safe rewrite cannot locate target memo block") { `update outbox flush returns false when safe rewrite cannot locate target memo block`() }

        test("updateMemo deletes unreferenced voice attachment removed by edit") { `updateMemo deletes unreferenced voice attachment removed by edit`() }

        test("updateMemo keeps voice attachment that is still referenced by another memo") { `updateMemo keeps voice attachment that is still referenced by another memo`() }

        test("saveMemoInDb reuses cached storage formats across saves") { `saveMemoInDb reuses cached storage formats across saves`() }
    }


    @MockK(relaxed = true)
    private lateinit var fileDataSource: FileDataSource

    @MockK(relaxed = true)
    private lateinit var dao: TestMemoDaoSuite

    @MockK(relaxed = true)
    private lateinit var localFileStateDao: LocalFileStateDao

    @MockK(relaxed = true)
    private lateinit var savePlanFactory: MemoSavePlanFactory

    @MockK(relaxed = true)
    private lateinit var dataStore: LomoDataStore

    @MockK(relaxed = true)
    private lateinit var trashMutationHandler: MemoTrashMutationHandler

    @MockK(relaxed = true)
    private lateinit var memoVersionJournal: MemoVersionJournal

    @MockK(relaxed = true)
    private lateinit var s3LocalChangeRecorder: S3LocalChangeRecorder

    @MockK(relaxed = true)
    private lateinit var webDavLocalChangeRecorder: WebDavLocalChangeRecorder

    private lateinit var handler: MemoMutationHandler

    private fun setUp() {
        MockKAnnotations.init(this)
        every { dataStore.storageFilenameFormat } returns flowOf("yyyy_MM_dd")
        every { dataStore.storageTimestampFormat } returns flowOf("HH:mm:ss")
        val workspaceStore =
            testMemoWorkspaceStore(
                markdownStorageDataSource = fileDataSource,
                localFileStateDao = localFileStateDao,
            )
        handler =
            MemoMutationHandler(
                markdownStorageDataSource = fileDataSource,
                mediaStorageDataSource = fileDataSource,
                daoBundle = testMemoMutationDaoBundle(dao),
                memoStatisticsDao = dao,
                localFileStateDao = localFileStateDao,
                workspaceStore = workspaceStore,
                workspaceMediaAccess = ThrowingWorkspaceMediaAccess,
                savePlanFactory = savePlanFactory,
                textProcessor = MemoTextProcessor(),
                dataStore = dataStore,
                trashMutationHandler = trashMutationHandler,
                memoIdentityPolicy = MemoIdentityPolicy(),
                memoVersionJournal = memoVersionJournal,
                mediaRepository = ThrowingMediaRepository,
                s3LocalChangeRecorder = s3LocalChangeRecorder,
                webDavLocalChangeRecorder = webDavLocalChangeRecorder,
                backgroundScope = immediateTestBackgroundScope(),
            )
    }

    private fun `flushMemoFileOutbox create stores file metadata modified time in local file state`() =
        runTest {
            val filename = "2024_01_15.md"
            val savePlan =
                MemoSavePlan(
                    filename = filename,
                    dateKey = "2024_01_15",
                    timestamp = 1_700_000_000_000L,
                    rawContent = "- 10:00 test",
                    memo =
                        Memo(
                            id = "memo_1",
                            timestamp = 1_700_000_000_000L,
                            content = "test",
                            rawContent = "- 10:00 test",
                            dateKey = "2024_01_15",
                        ),
                )
            coEvery { dao.getMemo(savePlan.memo.id) } returns projectedMemoEntity(savePlan.memo)
            coEvery { localFileStateDao.getByFilename(filename, false) } returns null
            coEvery {
                fileDataSource.saveFileIn(
                    directory = MemoDirectoryType.MAIN,
                    filename = filename,
                    content = any(),
                    append = true,
                    uri = null,
                )
            } returns null
            coEvery {
                fileDataSource.getFileMetadataIn(MemoDirectoryType.MAIN, filename)
            } returns FileMetadata(filename, 456_789L)

            handler.flushMemoFileOutbox(buildCreateOutbox(savePlan))

            val captured = slot<LocalFileStateEntity>()
            coVerify(exactly = 1) {
                localFileStateDao.upsert(capture(captured))
            }
            captured.captured.lastKnownModifiedTime shouldBe 456_789L
        }

    private fun `flushMemoFileOutbox create stores metadata modified time in local file state`() =
        runTest {
            val filename = "2024_01_16.md"
            val createRawContent = "- 11:00 from outbox"
            val identity =
                MemoFileOutboxIdentityPolicy.forCreate(
                    memoId = "memo_2",
                    memoDate = "2024_01_16",
                    createRawContent = createRawContent,
                )
            val outbox =
                MemoFileOutboxEntity(
                    operation = MemoFileOutboxOp.CREATE,
                    operationId = identity.operationId,
                    idempotencyKey = identity.idempotencyKey,
                    memoId = "memo_2",
                    memoDate = "2024_01_16",
                    memoTimestamp = 1_700_100_000_000L,
                    memoRawContent = createRawContent,
                    newContent = "from outbox",
                    createRawContent = createRawContent,
                )
            val memo =
                Memo(
                    id = "memo_2",
                    timestamp = 1_700_100_000_000L,
                    content = "from outbox",
                    rawContent = createRawContent,
                    dateKey = "2024_01_16",
                )

            coEvery { dao.getMemo(memo.id) } returns projectedMemoEntity(memo)
            coEvery { localFileStateDao.getByFilename(filename, false) } returns null
            coEvery {
                fileDataSource.saveFileIn(
                    directory = MemoDirectoryType.MAIN,
                    filename = filename,
                    content = any(),
                    append = true,
                    uri = null,
                )
            } returns "content://saved/memo_2"
            coEvery {
                fileDataSource.getFileMetadataIn(MemoDirectoryType.MAIN, filename)
            } returns FileMetadata(filename, 987_654L)

            val result = handler.flushMemoFileOutbox(outbox)

            (result).shouldBeTrue()
            val captured = slot<LocalFileStateEntity>()
            coVerify(exactly = 1) {
                localFileStateDao.upsert(capture(captured))
            }
            captured.captured.lastKnownModifiedTime shouldBe 987_654L
            captured.captured.safUri shouldBe "content://saved/memo_2"
        }

    private fun `flushMemoFileOutbox create records memo upsert for s3 incremental journal`() =
        runTest {
            val filename = "2024_01_15.md"
            val savePlan =
                MemoSavePlan(
                    filename = filename,
                    dateKey = "2024_01_15",
                    timestamp = 1_700_000_000_000L,
                    rawContent = "- 10:00 test",
                    memo =
                        Memo(
                            id = "memo_1",
                            timestamp = 1_700_000_000_000L,
                            content = "test",
                            rawContent = "- 10:00 test",
                            dateKey = "2024_01_15",
                        ),
                )
            coEvery { dao.getMemo(savePlan.memo.id) } returns projectedMemoEntity(savePlan.memo)
            coEvery { localFileStateDao.getByFilename(filename, false) } returns null
            coEvery {
                fileDataSource.saveFileIn(
                    directory = MemoDirectoryType.MAIN,
                    filename = filename,
                    content = any(),
                    append = true,
                    uri = null,
                )
            } returns null
            coEvery {
                fileDataSource.getFileMetadataIn(MemoDirectoryType.MAIN, filename)
            } returns FileMetadata(filename, 456_789L)

            handler.flushMemoFileOutbox(buildCreateOutbox(savePlan))

            coVerify(exactly = 1) { s3LocalChangeRecorder.recordMemoUpsert(filename) }
        }

    private fun `flushDeleteMemoToFile records memo delete when main file disappears`() =
        runTest {
            val memo =
                Memo(
                    id = "memo_delete",
                    timestamp = 1_700_000_000_000L,
                    content = "before",
                    rawContent = "- 10:00 before",
                    dateKey = "2024_01_15",
                )
            coEvery { trashMutationHandler.moveToTrashFileOnly(memo) } returns true
            coEvery { localFileStateDao.getByFilename("${memo.dateKey}.md", false) } returns null

            val result = handler.flushDeleteMemoToFile(memo)

            (result).shouldBeTrue()
            coVerify(exactly = 1) { s3LocalChangeRecorder.recordMemoDelete("${memo.dateKey}.md") }
        }

    private fun `updateMemoInDb refreshes updatedAt`() =
        runTest {
            val sourceMemo =
                Memo(
                    id = "memo_update",
                    timestamp = 1_700_000_000_000L,
                    updatedAt = 1_700_000_000_000L,
                    content = "before",
                    rawContent = "- 10:00 before",
                    dateKey = "2024_01_15",
                )
            every { dataStore.storageFilenameFormat } returns flowOf("yyyy_MM_dd")
            every { dataStore.storageTimestampFormat } returns flowOf("HH:mm")
            coEvery { dao.getMemo(sourceMemo.id) } returns projectedMemoEntity(sourceMemo)

            val persistedMemo = slot<com.lomo.data.local.entity.MemoEntity>()
            coEvery {
                dao.insertMemo(capture(persistedMemo))
            } just runs
            coEvery { dao.insertMemoFileOutbox(any()) } returns 1L

            val outboxId = handler.updateMemoInDb(sourceMemo, "after")

            outboxId shouldBe 1L
            (persistedMemo.captured.updatedAt > sourceMemo.updatedAt).shouldBeTrue()
            coVerify(exactly = 1) {
                dao.replaceImageRefsForMemo(
                    match { projection ->
                        projection.entity.id == sourceMemo.id && projection.entity.content == "after"
                    },
                )
            }
        }

    private fun `updateMemoInDb relies on trigger managed fts and avoids direct fts writes`() =
        runTest {
            val sourceMemo =
                Memo(
                    id = "memo_update_search",
                    timestamp = 1_700_000_000_000L,
                    updatedAt = 1_700_000_000_000L,
                    content = "before",
                    rawContent = "- 10:00 before",
                    dateKey = "2024_01_15",
                )
            coEvery { dao.getMemo(sourceMemo.id) } returns projectedMemoEntity(sourceMemo)
            coEvery { dao.insertMemo(any()) } just runs
            coEvery { dao.insertMemoFileOutbox(any()) } returns 1L

            handler.updateMemoInDb(sourceMemo, "after search")

            coVerify(exactly = 0) { dao.rebuildFts() }
        }

    private fun `update outbox flush returns false when safe rewrite cannot locate target memo block`() =
        runTest {
            val sourceMemo =
                Memo(
                    id = "2026_03_26_10:00_deadbeef",
                    timestamp = 1_711_418_400_000L,
                    updatedAt = 1_711_418_400_000L,
                    content = "before",
                    rawContent = "- 10:00 before",
                    dateKey = "2026_03_26",
                )
            coEvery { dao.getMemo(sourceMemo.id) } returns projectedMemoEntity(sourceMemo)
            coEvery { localFileStateDao.getByFilename("${sourceMemo.dateKey}.md", false) } returns null
            coEvery { fileDataSource.readFileIn(MemoDirectoryType.MAIN, "${sourceMemo.dateKey}.md") } returns "- 10:00 another memo"

            val result = handler.flushMemoFileOutbox(buildUpdateOutbox(sourceMemo, "after"))

            result shouldBe false
        }

    private fun `updateMemo deletes unreferenced voice attachment removed by edit`() =
        runTest {
            val filename = "2026_03_26.md"
            val voicePath = "voice_1711418400000.m4a"
            val sourceMemo =
                Memo(
                    id = "2026_03_26_10:00_deadbeef",
                    timestamp = 1_711_418_400_000L,
                    updatedAt = 1_711_418_400_000L,
                    content = "before [voice]($voicePath)",
                    rawContent = "- 10:00 before [voice]($voicePath)",
                    dateKey = "2026_03_26",
                    imageUrls = listOf(voicePath),
                )
            coEvery { dao.getMemo(sourceMemo.id) } returns projectedMemoEntity(sourceMemo)
            coEvery { localFileStateDao.getByFilename(filename, false) } returns null
            coEvery {
                fileDataSource.readFileIn(MemoDirectoryType.MAIN, filename)
            } returns "${sourceMemo.rawContent}\n"
            coEvery {
                fileDataSource.saveFileIn(
                    directory = MemoDirectoryType.MAIN,
                    filename = filename,
                    content = any(),
                    append = false,
                    uri = null,
                )
            } returns null
            coEvery {
                fileDataSource.getFileMetadataIn(MemoDirectoryType.MAIN, filename)
            } returns FileMetadata(filename, 1L)
            coEvery { dao.countMemosAndTrashWithImage(voicePath, sourceMemo.id) } returns 0

            coEvery { dao.insertMemoFileOutbox(any()) } returns 1L

            handler.updateMemoInDb(sourceMemo, "after")

            coVerify(exactly = 1) { fileDataSource.deleteVoiceFile(voicePath) }
        }

    private fun `updateMemo keeps voice attachment that is still referenced by another memo`() =
        runTest {
            val filename = "2026_03_26.md"
            val voicePath = "voice_shared.m4a"
            val sourceMemo =
                Memo(
                    id = "2026_03_26_10:00_deadbeef",
                    timestamp = 1_711_418_400_000L,
                    updatedAt = 1_711_418_400_000L,
                    content = "before [voice]($voicePath)",
                    rawContent = "- 10:00 before [voice]($voicePath)",
                    dateKey = "2026_03_26",
                    imageUrls = listOf(voicePath),
                )
            coEvery { dao.getMemo(sourceMemo.id) } returns projectedMemoEntity(sourceMemo)
            coEvery { localFileStateDao.getByFilename(filename, false) } returns null
            coEvery {
                fileDataSource.readFileIn(MemoDirectoryType.MAIN, filename)
            } returns "${sourceMemo.rawContent}\n"
            coEvery {
                fileDataSource.saveFileIn(
                    directory = MemoDirectoryType.MAIN,
                    filename = filename,
                    content = any(),
                    append = false,
                    uri = null,
                )
            } returns null
            coEvery {
                fileDataSource.getFileMetadataIn(MemoDirectoryType.MAIN, filename)
            } returns FileMetadata(filename, 1L)
            coEvery { dao.countMemosAndTrashWithImage(voicePath, sourceMemo.id) } returns 1

            coEvery { dao.insertMemoFileOutbox(any()) } returns 1L

            handler.updateMemoInDb(sourceMemo, "after")

            coVerify(exactly = 0) { fileDataSource.deleteVoiceFile(any()) }
            coVerify(exactly = 0) { fileDataSource.deleteImage(any()) }
        }

    private fun `saveMemoInDb reuses cached storage formats across saves`() =
        runTest {
            val filenameCollections = AtomicInteger(0)
            val timestampCollections = AtomicInteger(0)
            every { dataStore.storageFilenameFormat } returns
                flow {
                    check(filenameCollections.incrementAndGet() == 1)
                    emit("yyyy_MM_dd")
                }
            every { dataStore.storageTimestampFormat } returns
                flow {
                    check(timestampCollections.incrementAndGet() == 1)
                    emit("HH:mm")
                }

            val localHandler =
                MemoMutationHandler(
                    markdownStorageDataSource = fileDataSource,
                    mediaStorageDataSource = fileDataSource,
                    daoBundle = testMemoMutationDaoBundle(dao),
                    memoStatisticsDao = dao,
                    localFileStateDao = localFileStateDao,
                    workspaceStore =
                        testMemoWorkspaceStore(
                            markdownStorageDataSource = fileDataSource,
                            localFileStateDao = localFileStateDao,
                    ),
                    workspaceMediaAccess = ThrowingWorkspaceMediaAccess,
                    savePlanFactory = savePlanFactory,
                    textProcessor = MemoTextProcessor(),
                    dataStore = dataStore,
                    trashMutationHandler = trashMutationHandler,
                    memoIdentityPolicy = MemoIdentityPolicy(),
                    memoVersionJournal = memoVersionJournal,
                    mediaRepository = ThrowingMediaRepository,
                    s3LocalChangeRecorder = s3LocalChangeRecorder,
                    webDavLocalChangeRecorder = webDavLocalChangeRecorder,
                    backgroundScope = immediateTestBackgroundScope(),
                )

            withTimeout(2_000) {
                while (filenameCollections.get() != 1 || timestampCollections.get() != 1) {
                    delay(10)
                }
            }

            coEvery { dao.countMemoIdCollisions(any(), any()) } returns 0
            coEvery { dao.countMemosByIdGlob(any()) } returns 0
            coEvery { dao.insertMemoFileOutbox(any()) } returnsMany listOf(1L, 2L)
            coEvery {
                savePlanFactory.create(
                    content = any(),
                    timestamp = any(),
                    filenameFormat = any(),
                    timestampFormat = any(),
                    existingFileContent = any(),
                    precomputedSameTimestampCount = any(),
                    precomputedCollisionCount = any(),
                )
            } answers {
                val timestamp = secondArg<Long>()
                MemoSavePlan(
                    filename = "2024_01_15.md",
                    dateKey = "2024_01_15",
                    timestamp = timestamp,
                    rawContent = "- 10:00 test",
                    memo =
                        Memo(
                            id = "memo_$timestamp",
                            timestamp = timestamp,
                            content = firstArg(),
                            rawContent = "- 10:00 test",
                            dateKey = "2024_01_15",
                        ),
                )
            }

            localHandler.saveMemoInDb("first", 1_700_000_000_000L)
            localHandler.saveMemoInDb("second", 1_700_000_100_000L)

            filenameCollections.get() shouldBe 1
            timestampCollections.get() shouldBe 1
        }
}
