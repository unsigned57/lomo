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



import com.lomo.data.worker.S3RefreshSyncPlan
import com.lomo.domain.model.S3SyncScanPolicy
import com.lomo.domain.model.S3SyncResult
import com.lomo.domain.model.S3SyncStatus
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncConflictFile
import com.lomo.domain.model.SyncConflictSet
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue

/*
 * Behavior Contract:
 * - Unit under test: S3SyncOperationRepositoryImpl
 * - Behavior focus: sync guard short-circuiting, failure recovery, and status/test-connection delegation.
 * - Observable outcomes: returned S3SyncResult/S3SyncStatus values and executor invocation counts.
 * - TDD proof: Fails before the fix because S3 sync still uses a placeholder repository implementation without executor/status-tester orchestration.
 * - Excludes: AWS transport behavior, file-bridge planning, conflict modeling internals, and UI rendering.
 */
class S3SyncOperationRepositoryImplTest : DataFunSpec() {
    init {
        beforeTest {
            setUp()
        }

        test("sync propagates not-configured result from executor") { `sync propagates not-configured result from executor`() }

        test("sync short-circuits when another s3 sync is in progress") { `sync short-circuits when another s3 sync is in progress`() }

        test("sync releases guard after failure so a later sync can run") { `sync releases guard after failure so a later sync can run`() }

        test("sync forwards explicit scan policy to executor") { `sync forwards explicit scan policy to executor`() }

        test("sync restores pending s3 conflicts from store before invoking executor") { `sync restores pending s3 conflicts from store before invoking executor`() }

        test("getStatus delegates to status tester") { `getStatus delegates to status tester`() }

        test("testConnection delegates to status tester") { `testConnection delegates to status tester`() }

        test("syncForRefresh runs resolved fast policy and schedules catch-up") { `syncForRefresh runs resolved fast policy and schedules catch-up`() }
    }


    /*
     * Test Change Justification:
     * - Reason category: mechanical reshaping after adding pending-conflict persistence.
     * - Replaced assertion/setup: implicit relaxed mock behavior for PendingSyncConflictStore.
     * - Previous setup is no longer correct because sync now always checks the store before invoking the executor.
     * - Retained coverage: existing tests still prove executor delegation, sync-guard behavior, and explicit pending-conflict restoration.
     * - This is not changing the test to fit the implementation; it makes the pre-existing "no pending conflicts" baseline explicit.
     */
    @MockK(relaxed = true)
    private lateinit var syncExecutor: S3SyncExecutor

    @MockK(relaxed = true)
    private lateinit var statusTester: S3SyncStatusTester

    @MockK(relaxed = true)
    private lateinit var refreshPlanner: S3RefreshSyncPlanner

    @MockK(relaxed = true)
    private lateinit var refreshScheduler: S3RefreshCatchUpScheduler

    @MockK(relaxed = true)
    private lateinit var pendingConflictStore: PendingSyncConflictStore

    private lateinit var stateHolder: S3SyncStateHolder

    private lateinit var repository: S3SyncOperationRepositoryImpl

    private fun setUp() {
        MockKAnnotations.init(this)
        stateHolder = S3SyncStateHolder()
        coEvery { pendingConflictStore.read(SyncBackendType.S3) } returns null
        repository =
            S3SyncOperationRepositoryImpl(
                syncExecutor = syncExecutor,
                statusTester = statusTester,
                refreshPlanner = refreshPlanner,
                refreshScheduler = refreshScheduler,
                pendingConflictStore = pendingConflictStore,
                stateHolder = stateHolder,
            )
    }

    private fun `sync propagates not-configured result from executor`() =
        runTest {
            coEvery { syncExecutor.performSync(S3SyncScanPolicy.FAST_THEN_RECONCILE) } returns
                S3SyncResult.NotConfigured

            val result = repository.sync()

            result shouldBe S3SyncResult.NotConfigured
            coVerify(exactly = 1) { syncExecutor.performSync(S3SyncScanPolicy.FAST_THEN_RECONCILE) }
        }

    private fun `sync short-circuits when another s3 sync is in progress`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            coEvery { syncExecutor.performSync(S3SyncScanPolicy.FAST_THEN_RECONCILE) } coAnswers {
                gate.await()
                S3SyncResult.Success("sync done")
            }

            val firstCall = async { repository.sync() }
            kotlinx.coroutines.yield()
            val secondCall = repository.sync()

            secondCall shouldBe S3SyncResult.Success("S3 sync already in progress")

            gate.complete(Unit)
            firstCall.await() shouldBe S3SyncResult.Success("sync done")
            coVerify(exactly = 1) { syncExecutor.performSync(S3SyncScanPolicy.FAST_THEN_RECONCILE) }
        }

    private fun `sync releases guard after failure so a later sync can run`() =
        runTest {
            coEvery { syncExecutor.performSync(S3SyncScanPolicy.FAST_THEN_RECONCILE) } throws
                IllegalStateException("sync failed") andThen
                S3SyncResult.Success("recovered")

            val firstFailure =
                runCatching {
                    repository.sync()
                }.exceptionOrNull()
            val secondResult = repository.sync()

            (firstFailure is IllegalStateException).shouldBeTrue()
            firstFailure?.message shouldBe "sync failed"
            secondResult shouldBe S3SyncResult.Success("recovered")
            coVerify(exactly = 2) { syncExecutor.performSync(S3SyncScanPolicy.FAST_THEN_RECONCILE) }
        }

    private fun `sync forwards explicit scan policy to executor`() =
        runTest {
            coEvery { syncExecutor.performSync(S3SyncScanPolicy.FULL_RECONCILE) } returns
                S3SyncResult.Success("deep reconcile")

            val result = repository.sync(S3SyncScanPolicy.FULL_RECONCILE)

            result shouldBe S3SyncResult.Success("deep reconcile")
            coVerify(exactly = 1) { syncExecutor.performSync(S3SyncScanPolicy.FULL_RECONCILE) }
        }

    private fun `sync restores pending s3 conflicts from store before invoking executor`() =
        runTest {
            val pending =
                SyncConflictSet(
                    source = SyncBackendType.S3,
                    files =
                        listOf(
                            SyncConflictFile(
                                relativePath = "lomo/memo/note.md",
                                localContent = "local",
                                remoteContent = "remote",
                                isBinary = false,
                            ),
                        ),
                    timestamp = 123L,
                )
            coEvery { pendingConflictStore.read(SyncBackendType.S3) } returns pending

            val result = repository.sync()

            result shouldBe S3SyncResult.Conflict("Pending conflicts remain", pending)
            coVerify(exactly = 0) { syncExecutor.performSync(any()) }
        }

    private fun `getStatus delegates to status tester`() =
        runTest {
            val expected =
                S3SyncStatus(
                    remoteFileCount = 7,
                    localFileCount = 5,
                    pendingChanges = 2,
                    lastSyncTime = 456L,
                )
            coEvery { statusTester.getStatus() } returns expected

            val result = repository.getStatus()

            result shouldBe expected
            coVerify(exactly = 1) { statusTester.getStatus() }
        }

    private fun `testConnection delegates to status tester`() =
        runTest {
            val expected = S3SyncResult.Error("connection failed")
            coEvery { statusTester.testConnection() } returns expected

            val result = repository.testConnection()

            result shouldBe expected
            coVerify(exactly = 1) { statusTester.testConnection() }
        }

    private fun `syncForRefresh runs resolved fast policy and schedules catch-up`() =
        runTest {
            coEvery { refreshPlanner.planRefreshSync() } returns
                S3RefreshSyncPlan(
                    foregroundPolicy = S3SyncScanPolicy.FAST_ONLY,
                    catchUpPolicy = S3SyncScanPolicy.FAST_THEN_RECONCILE,
                )
            coEvery { syncExecutor.performSync(S3SyncScanPolicy.FAST_ONLY) } returns
                S3SyncResult.Success("fast refresh")

            val result = repository.syncForRefresh()

            result shouldBe S3SyncResult.Success("fast refresh")
            coVerify(exactly = 1) { syncExecutor.performSync(S3SyncScanPolicy.FAST_ONLY) }
            coVerify(exactly = 1) { refreshScheduler.scheduleCatchUp(S3SyncScanPolicy.FAST_THEN_RECONCILE) }
        }
}
