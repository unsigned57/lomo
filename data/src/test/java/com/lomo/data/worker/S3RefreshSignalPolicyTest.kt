package com.lomo.data.worker


import com.lomo.data.repository.S3RemoteShardState
import com.lomo.data.repository.S3SyncProtocolState
import com.lomo.domain.model.S3SyncScanPolicy
import java.time.Duration
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull

/*
 * Test Contract:
 * - Unit under test: S3 refresh planner policy
 * - Behavior focus: strong refresh signals from repeated user refreshes should raise the foreground S3 scan intensity without weakening existing full-reconcile safeguards or catch-up scheduling.
 * - Observable outcomes: selected S3RefreshSyncPlan foreground and catch-up policies for representative protocol-state inputs.
 * - Red phase: Fails before the fix because refresh planning ignores repeated-refresh signal strength and always keeps the same foreground policy for normal and strong refresh requests.
 * - Excludes: WorkManager request wiring, repository execution, and UI gesture handling.
 */
class S3RefreshSignalPolicyTest : DataFunSpec() {
    init {
        test("strong refresh signal upgrades otherwise fast foreground sync to fast then reconcile") { `strong refresh signal upgrades otherwise fast foreground sync to fast then reconcile`() }

        test("strong refresh signal preserves existing full reconcile escalation") { `strong refresh signal preserves existing full reconcile escalation`() }

        test("strong refresh signal keeps catch-up scheduling while upgrading foreground sync") { `strong refresh signal keeps catch-up scheduling while upgrading foreground sync`() }
    }


    private fun `strong refresh signal upgrades otherwise fast foreground sync to fast then reconcile`() {
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

        plan.foregroundPolicy shouldBe S3SyncScanPolicy.FAST_THEN_RECONCILE
        plan.catchUpPolicy.shouldBeNull()
    }

    private fun `strong refresh signal preserves existing full reconcile escalation`() {
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

        plan.foregroundPolicy shouldBe S3SyncScanPolicy.FULL_RECONCILE
        plan.catchUpPolicy.shouldBeNull()
    }

    private fun `strong refresh signal keeps catch-up scheduling while upgrading foreground sync`() {
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

        plan.foregroundPolicy shouldBe S3SyncScanPolicy.FAST_THEN_RECONCILE
        plan.catchUpPolicy shouldBe S3SyncScanPolicy.FAST_THEN_RECONCILE
    }
}
