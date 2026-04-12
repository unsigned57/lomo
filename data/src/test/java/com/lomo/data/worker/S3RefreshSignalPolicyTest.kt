package com.lomo.data.worker

import com.lomo.data.repository.S3RemoteShardState
import com.lomo.data.repository.S3SyncProtocolState
import com.lomo.domain.model.S3SyncScanPolicy
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: S3 refresh planner policy
 * - Behavior focus: strong refresh signals from repeated user refreshes should raise the foreground S3 scan intensity without weakening existing full-reconcile safeguards or catch-up scheduling.
 * - Observable outcomes: selected S3RefreshSyncPlan foreground and catch-up policies for representative protocol-state inputs.
 * - Red phase: Fails before the fix because refresh planning ignores repeated-refresh signal strength and always keeps the same foreground policy for normal and strong refresh requests.
 * - Excludes: WorkManager request wiring, repository execution, and UI gesture handling.
 */
class S3RefreshSignalPolicyTest {
    @Test
    fun `strong refresh signal upgrades otherwise fast foreground sync to fast then reconcile`() {
        val plan =
            buildS3RefreshSyncPlan(
                protocolState =
                    S3SyncProtocolState(
                        lastSuccessfulSyncAt = 10L,
                        lastFullRemoteScanAt = Duration.ofHours(23).toMillis(),
                    ),
                reconcileInterval = Duration.ofHours(6),
                now = Duration.ofHours(24).toMillis(),
                incrementalEnabled = true,
                signal = S3RefreshSignal.STRONG_REMOTE_HINT,
            )

        assertEquals(S3SyncScanPolicy.FAST_THEN_RECONCILE, plan.foregroundPolicy)
        assertNull(plan.catchUpPolicy)
    }

    @Test
    fun `strong refresh signal preserves existing full reconcile escalation`() {
        val plan =
            buildS3RefreshSyncPlan(
                protocolState =
                    S3SyncProtocolState(
                        lastSuccessfulSyncAt = 10L,
                        lastReconcileAt = Duration.ofHours(23).toMillis(),
                        lastFullRemoteScanAt = Duration.ofHours(23).toMillis(),
                    ),
                reconcileInterval = Duration.ofHours(6),
                now = Duration.ofHours(24).toMillis(),
                incrementalEnabled = true,
                signal = S3RefreshSignal.STRONG_REMOTE_HINT,
                shardStates =
                    listOf(
                        S3RemoteShardState(
                            bucketId = "memo",
                            relativePrefix = "lomo/memo",
                            lastScannedAt = Duration.ofHours(23).toMillis(),
                            lastObjectCount = 10,
                            lastDurationMs = 50L,
                            lastChangeCount = 1,
                            idleScanStreak = 0,
                            lastVerificationAttemptCount = 4,
                            lastVerificationFailureCount = 3,
                        ),
                    ),
            )

        assertEquals(S3SyncScanPolicy.FULL_RECONCILE, plan.foregroundPolicy)
        assertNull(plan.catchUpPolicy)
    }

    @Test
    fun `strong refresh signal keeps catch-up scheduling while upgrading foreground sync`() {
        val plan =
            buildS3RefreshSyncPlan(
                protocolState =
                    S3SyncProtocolState(
                        lastSuccessfulSyncAt = 10L,
                        lastFullRemoteScanAt = Duration.ofHours(23).toMillis(),
                    ),
                reconcileInterval = Duration.ofHours(6),
                now = Duration.ofHours(24).toMillis(),
                incrementalEnabled = true,
                signal = S3RefreshSignal.STRONG_REMOTE_HINT,
                shardStates =
                    listOf(
                        S3RemoteShardState(
                            bucketId = "memo",
                            relativePrefix = "lomo/memo",
                            lastScannedAt = 1L,
                            lastObjectCount = 10,
                            lastDurationMs = 50L,
                            lastChangeCount = 0,
                        ),
                    ),
            )

        assertEquals(S3SyncScanPolicy.FAST_THEN_RECONCILE, plan.foregroundPolicy)
        assertEquals(S3SyncScanPolicy.FAST_THEN_RECONCILE, plan.catchUpPolicy)
    }
}
