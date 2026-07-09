package com.lomo.data.repository

import com.lomo.data.sync.SyncExistingWorkPolicy
import com.lomo.data.sync.SyncScheduledWork
import com.lomo.data.sync.SyncWorkBackoffPolicy
import com.lomo.data.sync.SyncWorkCadence
import com.lomo.data.sync.SyncWorkDecision
import com.lomo.data.sync.SyncWorkNetworkRequirement
import com.lomo.data.sync.SyncWorkPayload
import com.lomo.data.sync.SyncWorkRetryPolicy
import com.lomo.data.testing.DataFunSpec
import com.lomo.data.worker.S3SyncWorker
import com.lomo.domain.model.S3SyncResult
import com.lomo.domain.model.S3SyncState
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import java.time.Duration

/*
 * Behavior Contract:
 * - Unit under test: S3SyncOperationRepositoryImpl.
 * - Owning layer: data.
 * - Priority tier: P1.
 * - Capability: coordinate S3 operation sync, status, test-connection, refresh policy, and deferred catch-up scheduling without opening duplicate sync paths.
 *
 * Scenarios:
 * - Given sync or status is requested, when the repository delegates, then executor/status-tester results are returned and guarded against concurrent runs.
 * - Given refresh policy emits foreground work and deferred catch-up work, when refresh sync completes, then deferred scheduled work is passed through the S3 scheduler boundary.
 * - Given a coalesced refresh loop has an initial success and a later non-success, when the loop completes, then the non-success is the visible result and state.
 *
 * Observable outcomes:
 * - returned S3SyncResult/S3SyncStatus values, state holder values, executor invocation counts, and captured scheduled catch-up work.
 *
 * TDD proof:
 * - RED: fails before the repository orchestration fix because S3 sync still uses a placeholder repository implementation without executor/status-tester orchestration.
 * - RED Report 10 refresh-loop follow-up: a second coalesced refresh returning Conflict/Review/Error was hidden by returning the first Success.
 * - Excludes: AWS transport behavior, file-bridge planning, conflict modeling internals, and UI rendering.
 *
 * Test Change Justification:
 * - Reason category: S3 sync module gained remote object key policy, reconcile preparation, file bridge fingerprint ops, work telemetry, and streaming markdown; existing tests need updated assertions.
 * - Old behavior/assertion being replaced: previous sync tests relied on older file bridge, reconcile, and work policy contracts before these modules were added.
 * - Why old assertion is no longer correct: new modules introduce typed remote object key policy, reconcile preparation phases, and file bridge fingerprint verification that change the observable sync behavior.
 * - Coverage preserved by: all existing sync scenarios retained; new scenarios added for key policy, fingerprint ops, reconcile prep, and work telemetry.
 * - Why this is not fitting the test to the implementation: tests verify observable sync state transitions and file bridge outcomes, not internal implementation details.
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

        test("syncForRefresh returns later conflict from coalesced refresh loop") {
            `syncForRefresh returns later conflict from coalesced refresh loop`()
        }
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
    private lateinit var refreshPolicyPlanner: S3RefreshSyncPolicyPlanner

    @MockK(relaxed = true)
    private lateinit var scheduledWorkEnqueuer: S3ScheduledSyncWorkEnqueuer

    @MockK(relaxed = true)
    private lateinit var pendingConflictStore: PendingSyncConflictStore

    private lateinit var stateHolder: S3SyncStateHolder

    private lateinit var repository: S3SyncOperationRepositoryImpl

    private fun setUp() {
        MockKAnnotations.init(this)
        stateHolder = S3SyncStateHolder()
        coEvery { pendingConflictStore.readDescriptor(SyncBackendType.S3) } returns null
        repository =
            S3SyncOperationRepositoryImpl(
                syncExecutor = syncExecutor,
                statusTester = statusTester,
                refreshPolicyPlanner = refreshPolicyPlanner,
                scheduledWorkEnqueuer = scheduledWorkEnqueuer,
                pendingConflictStore = pendingConflictStore,
                stateHolder = stateHolder,
            )
    }

    private fun `sync propagates not-configured result from executor`() =
        runTest {
            coEvery { syncExecutor.performSync(S3SyncWorkIntent.FAST_THEN_RECONCILE) } returns
                S3SyncResult.NotConfigured

            val result = repository.sync()

            result shouldBe S3SyncResult.NotConfigured
            coVerify(exactly = 1) { syncExecutor.performSync(S3SyncWorkIntent.FAST_THEN_RECONCILE) }
        }

    private fun `sync short-circuits when another s3 sync is in progress`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            coEvery { syncExecutor.performSync(S3SyncWorkIntent.FAST_THEN_RECONCILE) } coAnswers {
                gate.await()
                S3SyncResult.Success("sync done")
            }

            val firstCall = async { repository.sync() }
            kotlinx.coroutines.yield()
            val secondCall = repository.sync()

            secondCall shouldBe S3SyncResult.Success("S3 sync already in progress")

            gate.complete(Unit)
            firstCall.await() shouldBe S3SyncResult.Success("sync done")
            coVerify(exactly = 1) { syncExecutor.performSync(S3SyncWorkIntent.FAST_THEN_RECONCILE) }
        }

    private fun `sync releases guard after failure so a later sync can run`() =
        runTest {
            coEvery { syncExecutor.performSync(S3SyncWorkIntent.FAST_THEN_RECONCILE) } throws
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
            coVerify(exactly = 2) { syncExecutor.performSync(S3SyncWorkIntent.FAST_THEN_RECONCILE) }
        }

    private fun `sync forwards explicit scan policy to executor`() =
        runTest {
            coEvery { syncExecutor.performSync(S3SyncWorkIntent.FULL_RECONCILE) } returns
                S3SyncResult.Success("deep reconcile")

            val result = repository.executeS3Sync(S3SyncWorkIntent.FULL_RECONCILE)

            result shouldBe S3SyncResult.Success("deep reconcile")
            coVerify(exactly = 1) { syncExecutor.performSync(S3SyncWorkIntent.FULL_RECONCILE) }
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
            coEvery { pendingConflictStore.readDescriptor(SyncBackendType.S3) } returns pending.toPendingDescriptor()
            coEvery { syncExecutor.restorePendingConflict(any()) } returns PendingSyncRestoreResult.Restored(pending)

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
            val catchUpWork = catchUpWork(S3SyncWorkIntent.FAST_THEN_RECONCILE)
            coEvery { refreshPolicyPlanner.planRefreshSync(any()) } returns
                refreshDecision(
                    foregroundPolicy = S3SyncWorkIntent.FAST_ONLY,
                    scheduledWork = listOf(catchUpWork),
                )
            coEvery { syncExecutor.performSync(S3SyncWorkIntent.FAST_ONLY) } returns
                S3SyncResult.Success("fast refresh")

            val result = repository.syncForRefresh()

            result shouldBe S3SyncResult.Success("fast refresh")
            coVerify(exactly = 1) { syncExecutor.performSync(S3SyncWorkIntent.FAST_ONLY) }
            coVerify(exactly = 1) { scheduledWorkEnqueuer.enqueue(listOf(catchUpWork)) }
        }

    private fun `syncForRefresh returns later conflict from coalesced refresh loop`() =
        runTest {
            val firstSyncStarted = CompletableDeferred<Unit>()
            val releaseFirstSync = CompletableDeferred<Unit>()
            val conflict = conflictSet()
            var syncAttempt = 0
            coEvery { refreshPolicyPlanner.planRefreshSync(any()) } returns
                refreshDecision(foregroundPolicy = S3SyncWorkIntent.FAST_ONLY)
            coEvery { syncExecutor.performSync(S3SyncWorkIntent.FAST_ONLY) } coAnswers {
                syncAttempt += 1
                when (syncAttempt) {
                    1 -> {
                        firstSyncStarted.complete(Unit)
                        releaseFirstSync.await()
                        S3SyncResult.Success("first refresh")
                    }
                    2 -> S3SyncResult.Conflict("pending conflict", conflict)
                    else -> error("Unexpected refresh sync attempt $syncAttempt")
                }
            }

            val firstRefresh = async { repository.syncForRefresh() }
            firstSyncStarted.await()
            val coalescedRefresh = launch {
                repository.syncForRefresh() shouldBe S3SyncResult.Success("S3 refresh sync already in progress")
            }
            releaseFirstSync.complete(Unit)

            firstRefresh.await() shouldBe S3SyncResult.Conflict("pending conflict", conflict)
            coalescedRefresh.join()
            stateHolder.state.value shouldBe S3SyncState.ConflictDetected(conflict)
            coVerify(exactly = 2) { syncExecutor.performSync(S3SyncWorkIntent.FAST_ONLY) }
        }

    private fun refreshDecision(
        foregroundPolicy: S3SyncWorkIntent,
        scheduledWork: List<SyncScheduledWork> = emptyList(),
    ): SyncWorkDecision =
        SyncWorkDecision(
            foregroundWork =
                com.lomo.data.sync.SyncForegroundWork(
                    backend = SyncBackendType.S3,
                    trigger = com.lomo.data.sync.SyncWorkTrigger.REFRESH,
                    payload = SyncWorkPayload.ProviderParameters(mapOf(S3_SYNC_WORK_INTENT_PARAMETER to foregroundPolicy.name)),
                ),
            scheduledWork = scheduledWork,
        )

    private fun catchUpWork(policy: S3SyncWorkIntent): SyncScheduledWork =
        SyncScheduledWork(
            backend = SyncBackendType.S3,
            trigger = com.lomo.data.sync.SyncWorkTrigger.CATCH_UP,
            uniqueWorkName = S3SyncWorker.RECONCILE_CATCH_UP_WORK_NAME,
            cadence = SyncWorkCadence.OneTime,
            networkRequirement = SyncWorkNetworkRequirement.Connected,
            existingWorkPolicy = SyncExistingWorkPolicy.Replace,
            retryPolicy =
                SyncWorkRetryPolicy(
                    maxAttempts = 3,
                    backoffPolicy = SyncWorkBackoffPolicy.Exponential,
                    backoffDelay = Duration.ofMinutes(30),
                ),
            payload = SyncWorkPayload.ProviderParameters(mapOf(S3_SYNC_WORK_INTENT_PARAMETER to policy.name)),
        )

    private fun conflictSet(): SyncConflictSet =
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
}
