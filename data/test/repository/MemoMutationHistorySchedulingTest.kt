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
import com.lomo.data.source.FileDataSource
import com.lomo.data.testing.projectedMemoEntity
import com.lomo.data.util.MemoTextProcessor
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoRevisionLifecycleState
import com.lomo.domain.model.MemoRevisionOrigin
import com.lomo.domain.usecase.MemoIdentityPolicy
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: MemoMutationHandler history scheduling path
 * - Behavior focus: local edit DB mutation must persist only durable DB intent/outbox work, while
 *   existing trash DB mutation keeps its historical async snapshot behavior outside Slice 2.
 * - Observable outcomes: update DB mutation returns the outbox id and does not call the in-memory
 *   version recorder path; delete DB mutation still returns before its existing async history append completes.
 * - TDD proof: Fails before the Slice 2 repair because updateMemoInDb enqueues LOCAL_EDIT through
 *   AsyncMemoVersionRecorder immediately after DB/outbox persistence.
 * - Excludes: blob persistence, snapshot prune policy, and file-flush behavior.
 */
class MemoMutationHistorySchedulingTest : DataFunSpec() {
    init {
        beforeTest {
            setUp()
        }

        test("updateMemoInDb persists durable outbox without in memory history scheduling") {
            `updateMemoInDb persists durable outbox without in memory history scheduling`()
        }

        test("deleteMemoInDb returns before trashed snapshot append completes") { `deleteMemoInDb returns before trashed snapshot append completes`() }
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

    private lateinit var handler: MemoMutationHandler

    private fun setUp() {
        MockKAnnotations.init(this)
        every { dataStore.storageFilenameFormat } returns flowOf("yyyy_MM_dd")
        every { dataStore.storageTimestampFormat } returns flowOf("HH:mm")
        handler =
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
                s3LocalChangeRecorder = NoOpS3LocalChangeRecorder,
                webDavLocalChangeRecorder = TestNoOpWebDavLocalChangeRecorder,
                backgroundScope = immediateTestBackgroundScope(),
            )
    }

    private fun `updateMemoInDb persists durable outbox without in memory history scheduling`() =
        runTest {
            val sourceMemo = testMemo(id = "memo-update", updatedAt = 10L, content = "before")
            coEvery { dao.getMemo(sourceMemo.id) } returns projectedMemoEntity(sourceMemo)
            coEvery { dao.insertMemoFileOutbox(any()) } returns 11L

            val outboxId =
                withTimeout(200) {
                    handler.updateMemoInDb(sourceMemo, "after")
                }

            outboxId shouldBe 11L
            coVerify(exactly = 0) { memoVersionJournal.appendLocalRevision(any(), any(), any()) }
        }

    private fun `deleteMemoInDb returns before trashed snapshot append completes`() =
        runTest {
            val sourceMemo = testMemo(id = "memo-delete", updatedAt = 20L, content = "trash me")
            val historyGate = CompletableDeferred<Unit>()
            coEvery { dao.getMemo(sourceMemo.id) } returns projectedMemoEntity(sourceMemo)
            coEvery { dao.insertMemoFileOutbox(any()) } returns 21L
            coEvery { memoVersionJournal.appendLocalRevision(any(), any(), any()) } coAnswers {
                historyGate.await()
            }

            val outboxId =
                withTimeout(200) {
                    handler.deleteMemoInDb(sourceMemo)
                }

            outboxId shouldBe 21L
            historyGate.complete(Unit)
            coVerify(timeout = 1_000, exactly = 1) {
                memoVersionJournal.appendLocalRevision(
                    match { it.id == sourceMemo.id && it.isDeleted },
                    MemoRevisionLifecycleState.TRASHED,
                    MemoRevisionOrigin.LOCAL_TRASH,
                )
            }
            coVerify(exactly = 0) { dao.rebuildFts() }
        }
}

private fun testMemo(
    id: String,
    updatedAt: Long,
    content: String,
): Memo =
    Memo(
        id = id,
        timestamp = 1_700_000_000_000L,
        updatedAt = updatedAt,
        content = content,
        rawContent = "- 10:00 $content",
        dateKey = "2024_01_15",
    )
