package com.lomo.data.worker

import com.lomo.data.repository.S3EndpointProfile
import com.lomo.data.repository.S3RemoteShardState
import com.lomo.data.repository.S3SyncProtocolState
import com.lomo.domain.model.S3SyncScanPolicy
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: S3 scheduler catch-up policy
 * - Behavior focus: scheduler should enqueue an immediate catch-up reconcile when incremental shard rotation is mid-flight, when remote coverage is stale, or when shard telemetry shows elevated external-change / verification-failure risk.
 * - Observable outcomes: selected catch-up S3SyncScanPolicy for representative protocol-state inputs.
 * - Red phase: Fails before the fix because scheduling only emits two fixed periodic works and never upgrades catch-up policy from shard telemetry such as elevated change rate or verification-failure risk.
 * - Excludes: WorkManager request wiring, Android context access, and repository sync execution.
 */
class S3SyncSchedulerPolicyTest {
    @Test
    fun `catch-up policy requests full reconcile when no full remote scan exists`() {
        val policy =
            resolveCatchUpPolicy(
                protocolState = S3SyncProtocolState(lastSuccessfulSyncAt = 10L),
                reconcileInterval = Duration.ofHours(6),
                now = 1_000L,
                incrementalEnabled = true,
            )

        assertEquals(S3SyncScanPolicy.FULL_RECONCILE, policy)
    }

    @Test
    fun `effective reconcile interval uses endpoint profile floor`() {
        assertEquals(
            Duration.ofHours(4),
            effectiveS3ReconcileInterval(
                requestedInterval = Duration.ofHours(1),
                endpointProfile = com.lomo.data.repository.S3EndpointProfile.AWS_S3,
            ),
        )
        assertEquals(
            Duration.ofHours(12),
            effectiveS3ReconcileInterval(
                requestedInterval = Duration.ofHours(6),
                endpointProfile = com.lomo.data.repository.S3EndpointProfile.MINIO_COMPAT,
            ),
        )
    }

    @Test
    fun `catch-up policy treats moderate change pressure differently by endpoint profile`() {
        val shardState =
            S3RemoteShardState(
                bucketId = "memo",
                relativePrefix = "lomo/memo",
                lastScannedAt = Duration.ofHours(23).toMillis(),
                lastObjectCount = 10,
                lastDurationMs = 50L,
                lastChangeCount = 4,
                idleScanStreak = 0,
                lastVerificationAttemptCount = 2,
                lastVerificationFailureCount = 0,
            )

        val awsPolicy =
            resolveCatchUpPolicy(
                protocolState =
                    S3SyncProtocolState(
                        lastSuccessfulSyncAt = 10L,
                        lastReconcileAt = 1L,
                        lastFullRemoteScanAt = Duration.ofHours(23).toMillis(),
                    ),
                reconcileInterval = Duration.ofHours(6),
                now = Duration.ofHours(24).toMillis(),
                incrementalEnabled = true,
                shardStates = listOf(shardState),
                endpointProfile = S3EndpointProfile.AWS_S3,
            )
        val minioPolicy =
            resolveCatchUpPolicy(
                protocolState =
                    S3SyncProtocolState(
                        lastSuccessfulSyncAt = 10L,
                        lastReconcileAt = 1L,
                        lastFullRemoteScanAt = Duration.ofHours(23).toMillis(),
                    ),
                reconcileInterval = Duration.ofHours(6),
                now = Duration.ofHours(24).toMillis(),
                incrementalEnabled = true,
                shardStates = listOf(shardState),
                endpointProfile = S3EndpointProfile.MINIO_COMPAT,
            )

        assertEquals(S3SyncScanPolicy.FAST_THEN_RECONCILE, awsPolicy)
        assertNull(minioPolicy)
    }

    @Test
    fun `catch-up policy continues rolling reconcile when shard cursor is pending`() {
        val policy =
            resolveCatchUpPolicy(
                protocolState =
                    S3SyncProtocolState(
                        lastSuccessfulSyncAt = 10L,
                        lastFullRemoteScanAt = 500L,
                        remoteScanCursor = "memo:a",
                    ),
                reconcileInterval = Duration.ofHours(6),
                now = 1_000L,
                incrementalEnabled = true,
            )

        assertEquals(S3SyncScanPolicy.FAST_THEN_RECONCILE, policy)
    }

    @Test
    fun `catch-up policy stays idle when recent deep scan is still fresh`() {
        val policy =
            resolveCatchUpPolicy(
                protocolState =
                    S3SyncProtocolState(
                        lastSuccessfulSyncAt = 10L,
                        lastFullRemoteScanAt = Duration.ofHours(23).toMillis(),
                    ),
                reconcileInterval = Duration.ofHours(6),
                now = Duration.ofHours(24).toMillis(),
                incrementalEnabled = true,
            )

        assertEquals(null, policy)
    }

    @Test
    fun `catch-up policy requests rolling reconcile when a shard is stale even if full scan is recent`() {
        val policy =
            resolveCatchUpPolicy(
                protocolState =
                    S3SyncProtocolState(
                        lastSuccessfulSyncAt = 10L,
                        lastFullRemoteScanAt = Duration.ofHours(23).toMillis(),
                    ),
                reconcileInterval = Duration.ofHours(6),
                now = Duration.ofHours(24).toMillis(),
                incrementalEnabled = true,
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

        assertEquals(S3SyncScanPolicy.FAST_THEN_RECONCILE, policy)
    }

    @Test
    fun `catch-up policy requests full reconcile when shard state is missing`() {
        val policy =
            resolveCatchUpPolicy(
                protocolState =
                    S3SyncProtocolState(
                        lastSuccessfulSyncAt = 10L,
                        lastFullRemoteScanAt = Duration.ofHours(23).toMillis(),
                    ),
                reconcileInterval = Duration.ofHours(6),
                now = Duration.ofHours(24).toMillis(),
                incrementalEnabled = true,
                shardStates = emptyList(),
            )

        assertEquals(S3SyncScanPolicy.FULL_RECONCILE, policy)
    }

    @Test
    fun `catch-up policy requests rolling reconcile when shard change rate spikes despite recent deep scan`() {
        val policy =
            resolveCatchUpPolicy(
                protocolState =
                    S3SyncProtocolState(
                        lastSuccessfulSyncAt = 10L,
                        lastReconcileAt = 1L,
                        lastFullRemoteScanAt = Duration.ofHours(23).toMillis(),
                    ),
                reconcileInterval = Duration.ofHours(6),
                now = Duration.ofHours(24).toMillis(),
                incrementalEnabled = true,
                shardStates =
                    listOf(
                        S3RemoteShardState(
                            bucketId = "memo",
                            relativePrefix = "lomo/memo",
                            lastScannedAt = Duration.ofHours(23).toMillis(),
                            lastObjectCount = 10,
                            lastDurationMs = 50L,
                            lastChangeCount = 6,
                            idleScanStreak = 0,
                            lastVerificationAttemptCount = 2,
                            lastVerificationFailureCount = 0,
                        ),
                    ),
            )

        assertEquals(S3SyncScanPolicy.FAST_THEN_RECONCILE, policy)
    }

    @Test
    fun `catch-up policy escalates to full reconcile when shard verification failures imply high uncertainty`() {
        val policy =
            resolveCatchUpPolicy(
                protocolState =
                    S3SyncProtocolState(
                        lastSuccessfulSyncAt = 10L,
                        lastReconcileAt = Duration.ofHours(23).toMillis(),
                        lastFullRemoteScanAt = Duration.ofHours(23).toMillis(),
                    ),
                reconcileInterval = Duration.ofHours(6),
                now = Duration.ofHours(24).toMillis(),
                incrementalEnabled = true,
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

        assertEquals(S3SyncScanPolicy.FULL_RECONCILE, policy)
    }

    @Test
    fun `refresh plan prefers fast foreground sync and full reconcile catch-up on first opening`() {
        val plan =
            buildS3RefreshSyncPlan(
                protocolState = S3SyncProtocolState(lastSuccessfulSyncAt = 10L),
                reconcileInterval = Duration.ofHours(6),
                now = 1_000L,
                incrementalEnabled = true,
            )

        assertEquals(S3SyncScanPolicy.FAST_ONLY, plan.foregroundPolicy)
        assertEquals(S3SyncScanPolicy.FULL_RECONCILE, plan.catchUpPolicy)
    }

    @Test
    fun `refresh plan escalates foreground sync to full reconcile under high verification uncertainty`() {
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
    fun `refresh plan keeps foreground fast and schedules rolling catch-up when cold shards need coverage`() {
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

        assertEquals(S3SyncScanPolicy.FAST_ONLY, plan.foregroundPolicy)
        assertEquals(S3SyncScanPolicy.FAST_THEN_RECONCILE, plan.catchUpPolicy)
    }
}
