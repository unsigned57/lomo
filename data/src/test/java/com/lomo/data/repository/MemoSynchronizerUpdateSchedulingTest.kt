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
import com.lomo.data.local.entity.MemoFileOutboxOp
import com.lomo.data.source.MemoDirectoryType
import com.lomo.domain.model.Memo
import com.lomo.data.testing.fakes.FakeFileDataSource
import com.lomo.data.testing.projectedTrashMemoEntity
import com.lomo.data.util.MemoTextProcessor
import com.lomo.domain.usecase.MemoIdentityPolicy
import io.kotest.matchers.shouldBe
import io.mockk.MockKAnnotations
import io.mockk.coJustRun
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import com.lomo.data.testing.DataFunSpec

/*
 * Behavior Contract:
 * - Unit under test: MemoSynchronizer
 * - Behavior focus: save/create, existing memo edits, and trash lifecycle mutations must return
 *   after the DB-first mutation path is queued, without waiting for the synchronous file path.
 *   Permanent delete must persist PERMANENT_DELETE outbox intent without directly destroying
 *   trash/file/DB completion state. Clear trash must snapshot current trash rows and enqueue the
 *   same per-memo PERMANENT_DELETE lifecycle commands, leaving destructive completion to outbox
 *   drain.
 * - Observable outcomes: saveMemo/updateMemo/delete/restore delegate to the DB-first enqueue method
 *   exactly once and return before outbox file drain completion; permanent-delete persists an outbox
 *   row and leaves trash file, trash row, and attachment refs untouched; clearTrash persists one
 *   PERMANENT_DELETE outbox row per current trash memo and leaves trash file, trash rows, and
 *   attachment refs untouched.
 * - TDD proof: Fails before the fix because MemoSynchronizer.saveMemo still calls
 *   MemoMutationHandler.saveMemo, which blocks on synchronous file append before the UI-visible save
 *   can complete. The permanent-delete outbox state test fails before the production DB-first
 *   boundary because direct delete completion removes file/DB state instead of queueing outbox work.
 *   Clear-trash lifecycle enqueue fails before the fix because clearTrash directly wipes trash
 *   file/DB/media state without inserting command-owned outbox rows.
 * - Excludes: outbox drain completion timing, widget refresh, and Room/file persistence internals.
 */
class MemoSynchronizerUpdateSchedulingTest : DataFunSpec() {
    init {
        beforeTest {
            setUp()
        }

        test("updateMemo returns after db-first update is enqueued without waiting for synchronous file rewrite") { `updateMemo returns after db-first update is enqueued without waiting for synchronous file rewrite`() }

        test("saveMemo returns after db-first create is enqueued without waiting for synchronous file append") { `saveMemo returns after db-first create is enqueued without waiting for synchronous file append`() }

        test("deleteMemo returns after db-first trash enqueue without waiting for synchronous file rewrite") { `deleteMemo returns after db-first trash enqueue without waiting for synchronous file rewrite`() }

        test("restoreMemo returns after db-first restore enqueue without waiting for synchronous file rewrite") { `restoreMemo returns after db-first restore enqueue without waiting for synchronous file rewrite`() }

        test("deletePermanently returns after db-first permanent delete enqueue without waiting for synchronous file mutation") {
            `deletePermanently returns after db-first permanent delete enqueue without waiting for synchronous file mutation`()
        }

        test("deletePermanently enqueues permanent delete outbox without directly destroying trash state") {
            `deletePermanently enqueues permanent delete outbox without directly destroying trash state`()
        }

        test("clearTrash enqueues a shard-clear row per date and a permanent delete row per memo without direct destruction") {
            `clearTrash enqueues a shard-clear row per date and a permanent delete row per memo without direct destruction`()
        }
    }


    @MockK
    private lateinit var refreshEngine: MemoRefreshEngine

    @MockK
    private lateinit var mutationHandler: MemoMutationHandler

    @MockK
    private lateinit var dao: TestMemoDaoSuite

    @MockK
    private lateinit var savePlanFactory: MemoSavePlanFactory

    @MockK
    private lateinit var memoVersionJournal: MemoVersionJournal

    @MockK
    private lateinit var dataStore: LomoDataStore

    private fun setUp() {
        MockKAnnotations.init(this)
        every { dataStore.storageFilenameFormat } returns flowOf("yyyy_MM_dd")
        every { dataStore.storageTimestampFormat } returns flowOf("HH:mm")
    }

    private fun `saveMemo returns after db-first create is enqueued without waiting for synchronous file append`() {
        runBlocking {
            val saveResult =
                SaveDbResult(
                    savePlan =
                        MemoSavePlan(
                            filename = "2024_01_15.md",
                            dateKey = "2024_01_15",
                            timestamp = 1_700_000_000_000L,
                            rawContent = "- 10:00 created",
                            memo = testMemo().copy(content = "created", rawContent = "- 10:00 created"),
                        ),
                    outboxId = 31L,
                )
            coEvery { mutationHandler.saveMemoInDb("created", 1_700_000_000_000L, null) } returns saveResult

            val synchronizer =
                MemoSynchronizer(
                    refreshEngine = refreshEngine,
                    mutationHandler = mutationHandler,
                    outboxScope = immediateTestBackgroundScope(),
                    startOutboxCoordinator = false,
                )
            val executor = Executors.newSingleThreadExecutor()

            try {
                val future = executor.submit<Unit> {
                    runBlocking { synchronizer.saveMemo("created", 1_700_000_000_000L) }
                }
                future.get(200, TimeUnit.MILLISECONDS)
            } finally {
                executor.shutdownNow()
            }

            coVerify(exactly = 1) { mutationHandler.saveMemoInDb("created", 1_700_000_000_000L, null) }
        }
    }

    private fun `updateMemo returns after db-first update is enqueued without waiting for synchronous file rewrite`() {
        runBlocking {
            val memo = testMemo()
            coEvery { mutationHandler.updateMemoInDb(memo, "after") } returns 41L

            val synchronizer =
                MemoSynchronizer(
                    refreshEngine = refreshEngine,
                    mutationHandler = mutationHandler,
                    outboxScope = immediateTestBackgroundScope(),
                    startOutboxCoordinator = false,
                )
            val executor = Executors.newSingleThreadExecutor()

            try {
                val future = executor.submit<Unit> { runBlocking { synchronizer.updateMemo(memo, "after") } }
                future.get(200, TimeUnit.MILLISECONDS)
            } finally {
                executor.shutdownNow()
            }

            coVerify(exactly = 1) { mutationHandler.updateMemoInDb(memo, "after") }
        }
    }

    private fun `deleteMemo returns after db-first trash enqueue without waiting for synchronous file rewrite`() {
        runBlocking {
            val memo = testMemo()
            val blockingDeleteGate = CompletableDeferred<Unit>()
            coEvery { mutationHandler.deleteMemo(memo) } coAnswers {
                blockingDeleteGate.await()
            }
            coEvery { mutationHandler.deleteMemoInDb(memo) } returns 52L

            val synchronizer =
                MemoSynchronizer(
                    refreshEngine = refreshEngine,
                    mutationHandler = mutationHandler,
                    outboxScope = immediateTestBackgroundScope(),
                    startOutboxCoordinator = false,
                )
            val executor = Executors.newSingleThreadExecutor()

            try {
                val future = executor.submit<Unit> { runBlocking { synchronizer.deleteMemo(memo) } }
                future.get(200, TimeUnit.MILLISECONDS)
            } finally {
                executor.shutdownNow()
            }

            coVerify(exactly = 1) { mutationHandler.deleteMemoInDb(memo) }
            coVerify(exactly = 0) { mutationHandler.deleteMemo(memo) }
            blockingDeleteGate.complete(Unit)
        }
    }

    private fun `restoreMemo returns after db-first restore enqueue without waiting for synchronous file rewrite`() {
        runBlocking {
            val memo = testMemo().copy(isDeleted = true)
            val blockingRestoreGate = CompletableDeferred<Unit>()
            coEvery { mutationHandler.restoreMemo(memo) } coAnswers {
                blockingRestoreGate.await()
            }
            coEvery { mutationHandler.restoreMemoInDb(memo) } returns 63L

            val synchronizer =
                MemoSynchronizer(
                    refreshEngine = refreshEngine,
                    mutationHandler = mutationHandler,
                    outboxScope = immediateTestBackgroundScope(),
                    startOutboxCoordinator = false,
                )
            val executor = Executors.newSingleThreadExecutor()

            try {
                val future = executor.submit<Unit> { runBlocking { synchronizer.restoreMemo(memo) } }
                future.get(200, TimeUnit.MILLISECONDS)
            } finally {
                executor.shutdownNow()
            }

            coVerify(exactly = 1) { mutationHandler.restoreMemoInDb(memo) }
            coVerify(exactly = 0) { mutationHandler.restoreMemo(memo) }
            blockingRestoreGate.complete(Unit)
        }
    }

    private fun `deletePermanently returns after db-first permanent delete enqueue without waiting for synchronous file mutation`() {
        runBlocking {
            val memo = testMemo().copy(isDeleted = true)
            coEvery { mutationHandler.deletePermanentlyInDb(memo) } returns 74L

            val synchronizer =
                MemoSynchronizer(
                    refreshEngine = refreshEngine,
                    mutationHandler = mutationHandler,
                    outboxScope = immediateTestBackgroundScope(),
                    startOutboxCoordinator = false,
                )
            val executor = Executors.newSingleThreadExecutor()

            try {
                val future = executor.submit<Unit> { runBlocking { synchronizer.deletePermanently(memo) } }
                future.get(200, TimeUnit.MILLISECONDS)
            } finally {
                executor.shutdownNow()
            }

            coVerify(exactly = 1) { mutationHandler.deletePermanentlyInDb(memo) }
        }
    }

    private fun `deletePermanently enqueues permanent delete outbox without directly destroying trash state`() {
        runBlocking {
            val memo =
                testMemo().copy(
                    id = "memo-permanent-delete-outbox",
                    content = "trash memo ![image](keep.png)",
                    rawContent = "- 10:00 trash memo ![image](keep.png)",
                    imageUrls = listOf("keep.png"),
                    isDeleted = true,
                )
            val fileDataSource = FakeFileDataSource()
            fileDataSource.files[MemoDirectoryType.TRASH to "${memo.dateKey}.md"] = "${memo.rawContent}\n"
            val capturedOutbox = slot<MemoFileOutboxEntity>()
            val realMutationHandler =
                realMutationHandler(
                    fileDataSource = fileDataSource,
                    outboxCapture = capturedOutbox,
                    outboxId = 74L,
                )
            coEvery { dao.getTrashMemo(memo.id) } returns projectedTrashMemoEntity(memo)

            val synchronizer =
                MemoSynchronizer(
                    refreshEngine = refreshEngine,
                    mutationHandler = realMutationHandler,
                    outboxScope = immediateTestBackgroundScope(),
                    startOutboxCoordinator = false,
                )
            val executor = Executors.newSingleThreadExecutor()

            try {
                val future = executor.submit<Unit> { runBlocking { synchronizer.deletePermanently(memo) } }
                future.get(200, TimeUnit.MILLISECONDS)
            } finally {
                executor.shutdownNow()
            }

            capturedOutbox.captured.operation shouldBe MemoFileOutboxOp.PERMANENT_DELETE
            capturedOutbox.captured.memoId shouldBe memo.id
            capturedOutbox.captured.memoRawContent shouldBe memo.rawContent
            fileDataSource.files[MemoDirectoryType.TRASH to "${memo.dateKey}.md"] shouldBe "${memo.rawContent}\n"
            fileDataSource.deleteFileInCalls shouldBe emptyList()
            fileDataSource.deletedImages shouldBe emptyList()
            coVerify(exactly = 0) { dao.deleteImageRefsByMemoId(memo.id) }
            coVerify(exactly = 0) { dao.deleteTrashMemoById(memo.id) }
        }
    }

    private fun `clearTrash enqueues a shard-clear row per date and a permanent delete row per memo without direct destruction`() {
        runBlocking {
            val memoA =
                testMemo().copy(
                    id = "memo-clear-trash-a",
                    content = "trash a ![image](a.png)",
                    rawContent = "- 10:00 trash a ![image](a.png)",
                    imageUrls = listOf("a.png"),
                    isDeleted = true,
                )
            val memoB =
                testMemo().copy(
                    id = "memo-clear-trash-b",
                    content = "trash b",
                    rawContent = "- 11:00 trash b",
                    dateKey = "2024_01_16",
                    isDeleted = true,
                )
            val fileDataSource = FakeFileDataSource()
            fileDataSource.files[MemoDirectoryType.TRASH to "${memoA.dateKey}.md"] = "${memoA.rawContent}\n"
            fileDataSource.files[MemoDirectoryType.TRASH to "${memoB.dateKey}.md"] = "${memoB.rawContent}\n"
            val capturedOutbox = mutableListOf<MemoFileOutboxEntity>()
            val realMutationHandler =
                realMutationHandler(
                    fileDataSource = fileDataSource,
                    outboxCapture = capturedOutbox,
                    outboxId = 80L,
                )
            coEvery { dao.getDeletedMemos() } returns
                listOf(
                    projectedTrashMemoEntity(memoA),
                    projectedTrashMemoEntity(memoB),
                )
            coJustRun { dao.deleteImageRefsByMemoIds(any()) }
            coJustRun { dao.clearTrash() }

            val synchronizer =
                MemoSynchronizer(
                    refreshEngine = refreshEngine,
                    mutationHandler = realMutationHandler,
                    outboxScope = immediateTestBackgroundScope(),
                    startOutboxCoordinator = false,
                )
            val executor = Executors.newSingleThreadExecutor()

            try {
                val future = executor.submit<Unit> { runBlocking { synchronizer.clearTrash() } }
                future.get(200, TimeUnit.MILLISECONDS)
            } finally {
                executor.shutdownNow()
            }

            capturedOutbox.map { it.operation } shouldBe
                listOf(
                    MemoFileOutboxOp.CLEAR_TRASH_SHARD,
                    MemoFileOutboxOp.CLEAR_TRASH_SHARD,
                    MemoFileOutboxOp.PERMANENT_DELETE,
                    MemoFileOutboxOp.PERMANENT_DELETE,
                )
            val shardRows = capturedOutbox.filter { it.operation == MemoFileOutboxOp.CLEAR_TRASH_SHARD }
            val memoRows = capturedOutbox.filter { it.operation == MemoFileOutboxOp.PERMANENT_DELETE }
            // One shard-clear row per distinct trash date, drained before the per-memo rows.
            shardRows.map { it.memoDate } shouldBe listOf(memoA.dateKey, memoB.dateKey)
            memoRows.map { it.memoId } shouldBe listOf(memoA.id, memoB.id)
            memoRows.map { it.memoRawContent } shouldBe listOf(memoA.rawContent, memoB.rawContent)
            // clearTrash itself still performs no direct destruction: the shard files are only
            // deleted when the CLEAR_TRASH_SHARD rows drain, not synchronously here.
            fileDataSource.files[MemoDirectoryType.TRASH to "${memoA.dateKey}.md"] shouldBe "${memoA.rawContent}\n"
            fileDataSource.files[MemoDirectoryType.TRASH to "${memoB.dateKey}.md"] shouldBe "${memoB.rawContent}\n"
            fileDataSource.deleteFileInCalls shouldBe emptyList()
            fileDataSource.deletedImages shouldBe emptyList()
            coVerify(exactly = 0) { dao.deleteImageRefsByMemoIds(any()) }
            coVerify(exactly = 0) { dao.deleteTrashMemoById(any()) }
            coVerify(exactly = 0) { dao.clearTrash() }
        }
    }

    private fun realMutationHandler(
        fileDataSource: FakeFileDataSource,
        outboxCapture: io.mockk.CapturingSlot<MemoFileOutboxEntity>,
        outboxId: Long,
    ): MemoMutationHandler {
        val localFileStateDao = InMemorySchedulingLocalFileStateDao()
        val workspaceStore =
            testMemoWorkspaceStore(
                markdownStorageDataSource = fileDataSource,
                localFileStateDao = localFileStateDao,
            )
        coEvery { dao.insertMemoFileOutbox(capture(outboxCapture)) } returns outboxId
        coJustRun { dao.deleteImageRefsByMemoId(any()) }
        coJustRun { dao.deleteTrashMemoById(any()) }
        return MemoMutationHandler(
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
            trashMutationHandler =
                MemoTrashMutationHandler(
                    workspaceStore = workspaceStore,
                    memoWriteDao = dao,
                    memoTagDao = dao,
                    memoImageDao = dao,
                    memoTrashDao = dao,
                    memoVersionRecorder = AsyncMemoVersionRecorder(memoVersionJournal, immediateTestBackgroundScope()),
            ),
            memoIdentityPolicy = MemoIdentityPolicy(),
            memoVersionJournal = memoVersionJournal,
            mediaRepository = ThrowingMediaRepository,
            s3LocalChangeRecorder = NoRecordingS3LocalChangeRecorder,
            webDavLocalChangeRecorder = NoRecordingWebDavLocalChangeRecorder,
            backgroundScope = immediateTestBackgroundScope(),
        )
    }

    private fun realMutationHandler(
        fileDataSource: FakeFileDataSource,
        outboxCapture: MutableList<MemoFileOutboxEntity>,
        outboxId: Long,
    ): MemoMutationHandler {
        val localFileStateDao = InMemorySchedulingLocalFileStateDao()
        val workspaceStore =
            testMemoWorkspaceStore(
                markdownStorageDataSource = fileDataSource,
                localFileStateDao = localFileStateDao,
            )
        coEvery { dao.insertMemoFileOutbox(capture(outboxCapture)) } returns outboxId
        coJustRun { dao.deleteImageRefsByMemoId(any()) }
        coJustRun { dao.deleteTrashMemoById(any()) }
        return MemoMutationHandler(
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
            trashMutationHandler =
                MemoTrashMutationHandler(
                    workspaceStore = workspaceStore,
                    memoWriteDao = dao,
                    memoTagDao = dao,
                    memoImageDao = dao,
                    memoTrashDao = dao,
                    memoVersionRecorder = AsyncMemoVersionRecorder(memoVersionJournal, immediateTestBackgroundScope()),
            ),
            memoIdentityPolicy = MemoIdentityPolicy(),
            memoVersionJournal = memoVersionJournal,
            mediaRepository = ThrowingMediaRepository,
            s3LocalChangeRecorder = NoRecordingS3LocalChangeRecorder,
            webDavLocalChangeRecorder = NoRecordingWebDavLocalChangeRecorder,
            backgroundScope = immediateTestBackgroundScope(),
        )
    }

    private fun testMemo(): Memo =
        Memo(
            id = "memo-update",
            timestamp = 1_700_000_000_000L,
            updatedAt = 1_700_000_000_000L,
            content = "before",
            rawContent = "- 10:00 before",
            dateKey = "2024_01_15",
        )
}

private class InMemorySchedulingLocalFileStateDao : LocalFileStateDao {
    private val states = linkedMapOf<Pair<String, Boolean>, LocalFileStateEntity>()

    override suspend fun getByFilename(
        filename: String,
        isTrash: Boolean,
    ): LocalFileStateEntity? = states[filename to isTrash]

    override suspend fun getAll(): List<LocalFileStateEntity> = states.values.toList()

    override suspend fun getAllByTrashStatus(isTrash: Boolean): List<LocalFileStateEntity> =
        states.values.filter { state -> state.isTrash == isTrash }

    override suspend fun upsert(entity: LocalFileStateEntity) {
        states[entity.filename to entity.isTrash] = entity
    }

    override suspend fun upsertAll(entities: List<LocalFileStateEntity>) {
        entities.forEach { entity -> upsert(entity) }
    }

    override suspend fun deleteByFilename(
        filename: String,
        isTrash: Boolean,
    ) {
        states.remove(filename to isTrash)
    }

    override suspend fun clearAll() {
        states.clear()
    }
}

private object NoRecordingS3LocalChangeRecorder : S3LocalChangeRecorder {
    override suspend fun recordMemoUpsert(filename: String) = Unit

    override suspend fun recordMemoDelete(filename: String) = Unit

    override suspend fun recordImageUpsert(filename: String) = Unit

    override suspend fun recordImageDelete(filename: String) = Unit

    override suspend fun recordVoiceUpsert(filename: String) = Unit

    override suspend fun recordVoiceDelete(filename: String) = Unit
}

private object NoRecordingWebDavLocalChangeRecorder : WebDavLocalChangeRecorder {
    override suspend fun recordMemoUpsert(filename: String) = Unit

    override suspend fun recordMemoDelete(filename: String) = Unit

    override suspend fun recordImageUpsert(filename: String) = Unit

    override suspend fun recordImageDelete(filename: String) = Unit

    override suspend fun recordVoiceUpsert(filename: String) = Unit

    override suspend fun recordVoiceDelete(filename: String) = Unit
}
