package com.lomo.data.repository

import com.lomo.data.worker.S3RefreshSignal
import com.lomo.data.worker.S3RefreshSyncPlan
import com.lomo.domain.model.S3SyncScanPolicy
import com.lomo.domain.model.S3SyncResult
import com.lomo.domain.model.SyncBackendType
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: S3SyncOperationRepositoryImpl
 * - Behavior focus: refresh-triggered S3 sync should coalesce overlapping refresh requests into at most one follow-up run and upgrade rapid repeated refreshes into a stronger foreground sync signal.
 * - Observable outcomes: returned S3SyncResult, refresh planner signal selection, and executor invocation ordering/count.
 * - Red phase: Fails before the fix because overlapping refresh requests only short-circuit with "already in progress" and never schedule exactly one follow-up refresh or upgrade that follow-up to a strong signal.
 * - Excludes: AWS transport behavior, WorkManager scheduling, and UI refresh indicator rendering.
 */
class S3SyncOperationRepositoryRefreshAggregationTest {
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

    private var nowMillis: Long = 10_000L

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
                nowProvider = { nowMillis },
            )
    }

    @Test
    fun `overlapping rapid refresh upgrades single follow-up run to strong foreground signal`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            coEvery { refreshPlanner.planRefreshSync() } returns
                S3RefreshSyncPlan(
                    foregroundPolicy = S3SyncScanPolicy.FAST_ONLY,
                    catchUpPolicy = null,
                )
            coEvery { refreshPlanner.planRefreshSync(S3RefreshSignal.STRONG_REMOTE_HINT) } returns
                S3RefreshSyncPlan(
                    foregroundPolicy = S3SyncScanPolicy.FAST_THEN_RECONCILE,
                    catchUpPolicy = null,
                )
            coEvery { syncExecutor.performSync(S3SyncScanPolicy.FAST_ONLY) } coAnswers {
                gate.await()
                S3SyncResult.Success("initial refresh")
            }
            coEvery { syncExecutor.performSync(S3SyncScanPolicy.FAST_THEN_RECONCILE) } returns
                S3SyncResult.Success("strong refresh")

            val firstCall = async { repository.syncForRefresh() }
            kotlinx.coroutines.yield()
            nowMillis += 500L

            val secondCall = repository.syncForRefresh()

            assertEquals(S3SyncResult.Success("S3 refresh sync already in progress"), secondCall)
            gate.complete(Unit)
            assertEquals(S3SyncResult.Success("initial refresh"), firstCall.await())
            coVerifyOrder {
                refreshPlanner.planRefreshSync()
                syncExecutor.performSync(S3SyncScanPolicy.FAST_ONLY)
                refreshPlanner.planRefreshSync(S3RefreshSignal.STRONG_REMOTE_HINT)
                syncExecutor.performSync(S3SyncScanPolicy.FAST_THEN_RECONCILE)
            }
            coVerify(exactly = 1) { syncExecutor.performSync(S3SyncScanPolicy.FAST_ONLY) }
            coVerify(exactly = 1) { syncExecutor.performSync(S3SyncScanPolicy.FAST_THEN_RECONCILE) }
        }

    @Test
    fun `multiple overlapping refresh requests still collapse into one follow-up run`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            coEvery { refreshPlanner.planRefreshSync() } returns
                S3RefreshSyncPlan(
                    foregroundPolicy = S3SyncScanPolicy.FAST_ONLY,
                    catchUpPolicy = null,
                )
            coEvery { refreshPlanner.planRefreshSync(S3RefreshSignal.STRONG_REMOTE_HINT) } returns
                S3RefreshSyncPlan(
                    foregroundPolicy = S3SyncScanPolicy.FAST_THEN_RECONCILE,
                    catchUpPolicy = null,
                )
            coEvery { syncExecutor.performSync(S3SyncScanPolicy.FAST_ONLY) } coAnswers {
                gate.await()
                S3SyncResult.Success("initial refresh")
            }
            coEvery { syncExecutor.performSync(S3SyncScanPolicy.FAST_THEN_RECONCILE) } returns
                S3SyncResult.Success("strong refresh")

            val firstCall = async { repository.syncForRefresh() }
            kotlinx.coroutines.yield()
            nowMillis += 300L
            repository.syncForRefresh()
            nowMillis += 300L
            repository.syncForRefresh()

            gate.complete(Unit)
            assertEquals(S3SyncResult.Success("initial refresh"), firstCall.await())
            coVerify(exactly = 1) { refreshPlanner.planRefreshSync(S3RefreshSignal.STRONG_REMOTE_HINT) }
            coVerify(exactly = 2) { syncExecutor.performSync(any()) }
        }

    @Test
    fun `overlapping slow refresh keeps follow-up on normal foreground signal`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            coEvery { refreshPlanner.planRefreshSync() } returns
                S3RefreshSyncPlan(
                    foregroundPolicy = S3SyncScanPolicy.FAST_ONLY,
                    catchUpPolicy = null,
                )
            coEvery { syncExecutor.performSync(S3SyncScanPolicy.FAST_ONLY) } coAnswers {
                if (!gate.isCompleted) {
                    gate.await()
                    S3SyncResult.Success("initial refresh")
                } else {
                    S3SyncResult.Success("follow-up refresh")
                }
            }

            val firstCall = async { repository.syncForRefresh() }
            kotlinx.coroutines.yield()
            nowMillis += 3_000L

            val secondCall = repository.syncForRefresh()

            assertEquals(S3SyncResult.Success("S3 refresh sync already in progress"), secondCall)
            gate.complete(Unit)
            assertEquals(S3SyncResult.Success("initial refresh"), firstCall.await())
            coVerify(exactly = 2) { refreshPlanner.planRefreshSync() }
            coVerify(exactly = 0) { refreshPlanner.planRefreshSync(S3RefreshSignal.STRONG_REMOTE_HINT) }
            coVerify(exactly = 2) { syncExecutor.performSync(S3SyncScanPolicy.FAST_ONLY) }
        }
}
