package com.lomo.data.repository

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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: S3SyncOperationRepositoryImpl
 * - Behavior focus: sync guard short-circuiting, failure recovery, and status/test-connection delegation.
 * - Observable outcomes: returned S3SyncResult/S3SyncStatus values and executor invocation counts.
 * - Red phase: Fails before the fix because S3 sync still uses a placeholder repository implementation without executor/status-tester orchestration.
 * - Excludes: AWS transport behavior, file-bridge planning, conflict modeling internals, and UI rendering.
 */
class S3SyncOperationRepositoryImplTest {
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

    @Before
    fun setUp() {
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

    @Test
    fun `sync propagates not-configured result from executor`() =
        runTest {
            coEvery { syncExecutor.performSync(S3SyncScanPolicy.FAST_THEN_RECONCILE) } returns
                S3SyncResult.NotConfigured

            val result = repository.sync()

            assertEquals(S3SyncResult.NotConfigured, result)
            coVerify(exactly = 1) { syncExecutor.performSync(S3SyncScanPolicy.FAST_THEN_RECONCILE) }
        }

    @Test
    fun `sync short-circuits when another s3 sync is in progress`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            coEvery { syncExecutor.performSync(S3SyncScanPolicy.FAST_THEN_RECONCILE) } coAnswers {
                gate.await()
                S3SyncResult.Success("sync done")
            }

            val firstCall = async { repository.sync() }
            kotlinx.coroutines.yield()
            val secondCall = repository.sync()

            assertEquals(S3SyncResult.Success("S3 sync already in progress"), secondCall)

            gate.complete(Unit)
            assertEquals(S3SyncResult.Success("sync done"), firstCall.await())
            coVerify(exactly = 1) { syncExecutor.performSync(S3SyncScanPolicy.FAST_THEN_RECONCILE) }
        }

    @Test
    fun `sync releases guard after failure so a later sync can run`() =
        runTest {
            coEvery { syncExecutor.performSync(S3SyncScanPolicy.FAST_THEN_RECONCILE) } throws
                IllegalStateException("sync failed") andThen
                S3SyncResult.Success("recovered")

            val firstFailure =
                runCatching {
                    repository.sync()
                }.exceptionOrNull()
            val secondResult = repository.sync()

            assertTrue(firstFailure is IllegalStateException)
            assertEquals("sync failed", firstFailure?.message)
            assertEquals(S3SyncResult.Success("recovered"), secondResult)
            coVerify(exactly = 2) { syncExecutor.performSync(S3SyncScanPolicy.FAST_THEN_RECONCILE) }
        }

    @Test
    fun `sync forwards explicit scan policy to executor`() =
        runTest {
            coEvery { syncExecutor.performSync(S3SyncScanPolicy.FULL_RECONCILE) } returns
                S3SyncResult.Success("deep reconcile")

            val result = repository.sync(S3SyncScanPolicy.FULL_RECONCILE)

            assertEquals(S3SyncResult.Success("deep reconcile"), result)
            coVerify(exactly = 1) { syncExecutor.performSync(S3SyncScanPolicy.FULL_RECONCILE) }
        }

    @Test
    fun `sync restores pending s3 conflicts from store before invoking executor`() =
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

            assertEquals(S3SyncResult.Conflict("Pending conflicts remain", pending), result)
            coVerify(exactly = 0) { syncExecutor.performSync(any()) }
        }

    @Test
    fun `getStatus delegates to status tester`() =
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

            assertEquals(expected, result)
            coVerify(exactly = 1) { statusTester.getStatus() }
        }

    @Test
    fun `testConnection delegates to status tester`() =
        runTest {
            val expected = S3SyncResult.Error("connection failed")
            coEvery { statusTester.testConnection() } returns expected

            val result = repository.testConnection()

            assertEquals(expected, result)
            coVerify(exactly = 1) { statusTester.testConnection() }
        }

    @Test
    fun `syncForRefresh runs resolved fast policy and schedules catch-up`() =
        runTest {
            coEvery { refreshPlanner.planRefreshSync() } returns
                S3RefreshSyncPlan(
                    foregroundPolicy = S3SyncScanPolicy.FAST_ONLY,
                    catchUpPolicy = S3SyncScanPolicy.FAST_THEN_RECONCILE,
                )
            coEvery { syncExecutor.performSync(S3SyncScanPolicy.FAST_ONLY) } returns
                S3SyncResult.Success("fast refresh")

            val result = repository.syncForRefresh()

            assertEquals(S3SyncResult.Success("fast refresh"), result)
            coVerify(exactly = 1) { syncExecutor.performSync(S3SyncScanPolicy.FAST_ONLY) }
            coVerify(exactly = 1) { refreshScheduler.scheduleCatchUp(S3SyncScanPolicy.FAST_THEN_RECONCILE) }
        }
}
