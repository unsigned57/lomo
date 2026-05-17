package com.lomo.data.worker


import com.lomo.data.repository.S3EndpointProfile
import com.lomo.data.repository.S3RemoteShardState
import com.lomo.data.repository.S3SyncProtocolState
import com.lomo.domain.model.S3SyncScanPolicy
import java.time.Duration
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull

/*
 * Test Contract:
 * - Unit under test: S3 scheduler catch-up policy
 * - Behavior focus: scheduler should enqueue an immediate catch-up reconcile when incremental shard rotation is mid-flight, when remote coverage is stale, or when shard telemetry shows elevated external-change / verification-failure risk.
 * - Observable outcomes: selected catch-up S3SyncScanPolicy for representative protocol-state inputs.
 * - Red phase: Fails before the fix because scheduling only emits two fixed periodic works and never upgrades catch-up policy from shard telemetry such as elevated change rate or verification-failure risk.
 * - Excludes: WorkManager request wiring, Android context access, and repository sync execution.
 */
class S3SyncSchedulerPolicyTest : DataFunSpec() {
    init {
        test("catch-up policy requests full reconcile when no full remote scan exists") { `catch-up policy requests full reconcile when no full remote scan exists`() }

        test("effective reconcile interval uses endpoint profile floor") { `effective reconcile interval uses endpoint profile floor`() }

        test("catch-up policy treats moderate change pressure differently by endpoint profile") { `catch-up policy treats moderate change pressure differently by endpoint profile`() }

        test("catch-up policy continues rolling reconcile when shard cursor is pending") { `catch-up policy continues rolling reconcile when shard cursor is pending`() }

        test("catch-up policy stays idle when recent deep scan is still fresh") { `catch-up policy stays idle when recent deep scan is still fresh`() }

        test("catch-up policy requests rolling reconcile when a shard is stale even if full scan is recent") { `catch-up policy requests rolling reconcile when a shard is stale even if full scan is recent`() }

        test("catch-up policy requests full reconcile when shard state is missing") { `catch-up policy requests full reconcile when shard state is missing`() }

        test("catch-up policy requests rolling reconcile when shard change rate spikes despite recent deep scan") { `catch-up policy requests rolling reconcile when shard change rate spikes despite recent deep scan`() }

        test("catch-up policy escalates to full reconcile when shard verification failures imply high uncertainty") { `catch-up policy escalates to full reconcile when shard verification failures imply high uncertainty`() }

        test("refresh plan prefers fast foreground sync and full reconcile catch-up on first opening") { `refresh plan prefers fast foreground sync and full reconcile catch-up on first opening`() }

        test("refresh plan escalates foreground sync to full reconcile under high verification uncertainty") { `refresh plan escalates foreground sync to full reconcile under high verification uncertainty`() }

        test("refresh plan keeps foreground fast and schedules rolling catch-up when cold shards need coverage") { `refresh plan keeps foreground fast and schedules rolling catch-up when cold shards need coverage`() }
    }


    private fun `catch-up policy requests full reconcile when no full remote scan exists`() {
        val policy =
            resolveCatchUpPolicy(
                protocolState = S3SyncProtocolState(lastSuccessfulSyncAt = 10L),
                reconcileInterval = Duration.ofHours(6),
                now = 1_000L,
                incrementalEnabled = true,
            )

        policy shouldBe S3SyncScanPolicy.FULL_RECONCILE
    }

    private fun `effective reconcile interval uses endpoint profile floor`() {
        effectiveS3ReconcileInterval(
                requestedInterval = Duration.ofHours(1),
                endpointProfile = com.lomo.data.repository.S3EndpointProfile.AWS_S3,
            ) shouldBe Duration.ofHours(4)
        effectiveS3ReconcileInterval(
                requestedInterval = Duration.ofHours(6),
                endpointProfile = com.lomo.data.repository.S3EndpointProfile.MINIO_COMPAT,
            ) shouldBe Duration.ofHours(12)
    }

    private fun `catch-up policy treats moderate change pressure differently by endpoint profile`() {
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

        awsPolicy shouldBe S3SyncScanPolicy.FAST_THEN_RECONCILE
        minioPolicy.shouldBeNull()
    }

    private fun `catch-up policy continues rolling reconcile when shard cursor is pending`() {
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

        policy shouldBe S3SyncScanPolicy.FAST_THEN_RECONCILE
    }

    private fun `catch-up policy stays idle when recent deep scan is still fresh`() {
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

        policy shouldBe null
    }

    private fun `catch-up policy requests rolling reconcile when a shard is stale even if full scan is recent`() {
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

        policy shouldBe S3SyncScanPolicy.FAST_THEN_RECONCILE
    }

    private fun `catch-up policy requests full reconcile when shard state is missing`() {
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

        policy shouldBe S3SyncScanPolicy.FULL_RECONCILE
    }

    private fun `catch-up policy requests rolling reconcile when shard change rate spikes despite recent deep scan`() {
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

        policy shouldBe S3SyncScanPolicy.FAST_THEN_RECONCILE
    }

    private fun `catch-up policy escalates to full reconcile when shard verification failures imply high uncertainty`() {
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

        policy shouldBe S3SyncScanPolicy.FULL_RECONCILE
    }

    private fun `refresh plan prefers fast foreground sync and full reconcile catch-up on first opening`() {
        val plan =
            buildS3RefreshSyncPlan(
                protocolState = S3SyncProtocolState(lastSuccessfulSyncAt = 10L),
                reconcileInterval = Duration.ofHours(6),
                now = 1_000L,
                incrementalEnabled = true,
            )

        plan.foregroundPolicy shouldBe S3SyncScanPolicy.FAST_ONLY
        plan.catchUpPolicy shouldBe S3SyncScanPolicy.FULL_RECONCILE
    }

    private fun `refresh plan escalates foreground sync to full reconcile under high verification uncertainty`() {
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

        plan.foregroundPolicy shouldBe S3SyncScanPolicy.FULL_RECONCILE
        plan.catchUpPolicy.shouldBeNull()
    }

    private fun `refresh plan keeps foreground fast and schedules rolling catch-up when cold shards need coverage`() {
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

        plan.foregroundPolicy shouldBe S3SyncScanPolicy.FAST_ONLY
        plan.catchUpPolicy shouldBe S3SyncScanPolicy.FAST_THEN_RECONCILE
    }
}
