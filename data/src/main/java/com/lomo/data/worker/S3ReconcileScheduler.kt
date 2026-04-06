package com.lomo.data.worker

import com.lomo.data.repository.S3RemoteShardScheduleTelemetry
import com.lomo.data.repository.S3RemoteShardStateStore
import com.lomo.data.repository.S3_CHANGE_PRESSURE_THRESHOLD
import com.lomo.data.repository.S3_MIN_UNCERTAINTY_ATTEMPTS
import com.lomo.data.repository.S3_MIN_UNCERTAINTY_FAILURES
import com.lomo.data.repository.S3_RECENT_CHANGE_WINDOW_DIVISOR
import com.lomo.data.repository.S3_VERIFICATION_FAILURE_THRESHOLD
import com.lomo.data.repository.S3SyncProtocolState
import com.lomo.data.repository.S3SyncProtocolStateStore
import com.lomo.domain.model.S3SyncScanPolicy
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class S3ReconcileScheduler
    @Inject
    constructor(
        private val protocolStateStore: S3SyncProtocolStateStore,
        private val remoteShardStateStore: S3RemoteShardStateStore,
    ) {
        suspend fun buildSchedulePlan(interval: String): S3SyncSchedulePlan {
            val fastInterval = parseS3AutoSyncInterval(interval)
            val reconcileInterval = fastInterval.coerceAtLeast(MIN_S3_RECONCILE_INTERVAL)
            val protocolState = protocolStateStore.readIfIncrementalEnabled()
            val telemetry = remoteShardStateStore.readScheduleTelemetryIfEnabled(reconcileInterval)
            return S3SyncSchedulePlan(
                fastInterval = fastInterval,
                reconcileInterval = reconcileInterval,
                catchUpPolicy =
                    resolveCatchUpPolicy(
                        protocolState = protocolState,
                        reconcileInterval = reconcileInterval,
                        incrementalEnabled = protocolStateStore.incrementalSyncEnabled,
                        telemetry = telemetry,
                    ),
            )
        }

        suspend fun buildRefreshPlan(
            reconcileInterval: Duration,
        ): S3RefreshSyncPlan {
            val protocolState = protocolStateStore.readIfIncrementalEnabled()
            val telemetry = remoteShardStateStore.readScheduleTelemetryIfEnabled(reconcileInterval)
            return buildS3RefreshSyncPlan(
                protocolState = protocolState,
                reconcileInterval = reconcileInterval,
                incrementalEnabled = protocolStateStore.incrementalSyncEnabled,
                telemetry = telemetry,
            )
        }
    }

private suspend fun S3SyncProtocolStateStore.readIfIncrementalEnabled(): S3SyncProtocolState? =
    if (incrementalSyncEnabled) {
        read()
    } else {
        null
    }

private suspend fun S3RemoteShardStateStore.readScheduleTelemetryIfEnabled(
    reconcileInterval: Duration,
    now: Long = System.currentTimeMillis(),
): S3RemoteShardScheduleTelemetry? =
    if (remoteShardStateEnabled) {
        readScheduleTelemetry(now = now, reconcileInterval = reconcileInterval)
    } else {
        null
    }

internal fun resolveCatchUpPolicy(
    protocolState: S3SyncProtocolState?,
    reconcileInterval: Duration,
    now: Long = System.currentTimeMillis(),
    incrementalEnabled: Boolean,
    telemetry: S3RemoteShardScheduleTelemetry? = null,
    shardStates: List<com.lomo.data.repository.S3RemoteShardState>? = null,
): S3SyncScanPolicy? {
    if (!incrementalEnabled) {
        return null
    }
    val effectiveTelemetry = telemetry ?: shardStates.orEmpty().toScheduleTelemetry(now, reconcileInterval)
    return when {
        protocolState?.lastFullRemoteScanAt == null -> S3SyncScanPolicy.FULL_RECONCILE
        telemetry != null && telemetry.shardCount == 0 -> S3SyncScanPolicy.FULL_RECONCILE
        shardStates != null && shardStates.isEmpty() -> S3SyncScanPolicy.FULL_RECONCILE
        effectiveTelemetry.hasHighVerificationUncertainty -> S3SyncScanPolicy.FULL_RECONCILE
        protocolState.remoteScanCursor != null -> S3SyncScanPolicy.FAST_THEN_RECONCILE
        effectiveTelemetry.hasElevatedChangePressure -> S3SyncScanPolicy.FAST_THEN_RECONCILE
        effectiveTelemetry.oldestScanAgeMillis(now)?.let { age -> age >= reconcileInterval.toMillis() } == true ->
            S3SyncScanPolicy.FAST_THEN_RECONCILE
        now - protocolState.lastFullRemoteScanAt >= reconcileInterval.toMillis() * FULL_RECONCILE_STALE_MULTIPLIER ->
            S3SyncScanPolicy.FULL_RECONCILE

        else -> null
    }
}

internal fun buildS3RefreshSyncPlan(
    protocolState: S3SyncProtocolState?,
    reconcileInterval: Duration,
    now: Long = System.currentTimeMillis(),
    incrementalEnabled: Boolean,
    telemetry: S3RemoteShardScheduleTelemetry? = null,
    shardStates: List<com.lomo.data.repository.S3RemoteShardState>? = null,
): S3RefreshSyncPlan {
    if (!incrementalEnabled) {
        return S3RefreshSyncPlan(
            foregroundPolicy = S3SyncScanPolicy.FAST_THEN_RECONCILE,
            catchUpPolicy = null,
        )
    }
    val effectiveTelemetry = telemetry ?: shardStates.orEmpty().toScheduleTelemetry(now, reconcileInterval)
    return when {
        protocolState?.lastFullRemoteScanAt == null ->
            S3RefreshSyncPlan(
                foregroundPolicy = S3SyncScanPolicy.FAST_ONLY,
                catchUpPolicy = S3SyncScanPolicy.FULL_RECONCILE,
            )

        telemetry != null && telemetry.shardCount == 0 ->
            S3RefreshSyncPlan(
                foregroundPolicy = S3SyncScanPolicy.FAST_ONLY,
                catchUpPolicy = S3SyncScanPolicy.FULL_RECONCILE,
            )

        shardStates != null && shardStates.isEmpty() ->
            S3RefreshSyncPlan(
                foregroundPolicy = S3SyncScanPolicy.FAST_ONLY,
                catchUpPolicy = S3SyncScanPolicy.FULL_RECONCILE,
            )

        effectiveTelemetry.hasHighVerificationUncertainty ->
            S3RefreshSyncPlan(
                foregroundPolicy = S3SyncScanPolicy.FULL_RECONCILE,
                catchUpPolicy = null,
            )

        protocolState.remoteScanCursor != null ||
            effectiveTelemetry.hasElevatedChangePressure ||
            effectiveTelemetry.oldestScanAgeMillis(now)?.let { age -> age >= reconcileInterval.toMillis() } == true ->
            S3RefreshSyncPlan(
                foregroundPolicy = S3SyncScanPolicy.FAST_ONLY,
                catchUpPolicy = S3SyncScanPolicy.FAST_THEN_RECONCILE,
            )

        now - protocolState.lastFullRemoteScanAt >= reconcileInterval.toMillis() * FULL_RECONCILE_STALE_MULTIPLIER ->
            S3RefreshSyncPlan(
                foregroundPolicy = S3SyncScanPolicy.FAST_ONLY,
                catchUpPolicy = S3SyncScanPolicy.FULL_RECONCILE,
            )

        else ->
            S3RefreshSyncPlan(
                foregroundPolicy = S3SyncScanPolicy.FAST_ONLY,
                catchUpPolicy = null,
            )
    }
}

internal fun List<com.lomo.data.repository.S3RemoteShardState>.toScheduleTelemetry(
    now: Long,
    reconcileInterval: Duration,
): S3RemoteShardScheduleTelemetry =
    S3RemoteShardScheduleTelemetry(
        shardCount = size,
        oldestScanAt = minOfOrNull(com.lomo.data.repository.S3RemoteShardState::lastScannedAt),
        hasElevatedChangePressure =
            any { state ->
                state.idleScanStreak == 0 &&
                    state.scanAgeMillis(now) <= reconcileInterval.toMillis() / S3_RECENT_CHANGE_WINDOW_DIVISOR &&
                    state.changeRate() >= S3_CHANGE_PRESSURE_THRESHOLD
            },
        hasHighVerificationUncertainty =
            any { state ->
                state.scanAgeMillis(now) <= reconcileInterval.toMillis() &&
                    state.lastVerificationAttemptCount >= S3_MIN_UNCERTAINTY_ATTEMPTS &&
                    state.lastVerificationFailureCount >= S3_MIN_UNCERTAINTY_FAILURES &&
                    state.verificationFailureRate() >= S3_VERIFICATION_FAILURE_THRESHOLD
            },
    )

private fun S3RemoteShardScheduleTelemetry.oldestScanAgeMillis(now: Long): Long? {
    val oldestScanAtValue = oldestScanAt ?: return null
    return (now - oldestScanAtValue).coerceAtLeast(0L)
}

private fun com.lomo.data.repository.S3RemoteShardState.scanAgeMillis(now: Long): Long =
    (now - lastScannedAt).coerceAtLeast(0L)

private fun com.lomo.data.repository.S3RemoteShardState.changeRate(): Double =
    lastChangeCount.toDouble() / lastObjectCount.coerceAtLeast(1).toDouble()

private fun com.lomo.data.repository.S3RemoteShardState.verificationFailureRate(): Double =
    lastVerificationFailureCount.toDouble() / lastVerificationAttemptCount.coerceAtLeast(1).toDouble()

internal val DEFAULT_S3_AUTO_SYNC_INTERVAL: Duration = Duration.ofHours(S3_AUTO_SYNC_HOURS_1)
internal val MIN_S3_RECONCILE_INTERVAL: Duration = Duration.ofHours(S3_AUTO_SYNC_HOURS_6)
internal const val FULL_RECONCILE_STALE_MULTIPLIER = 2L
private const val S3_AUTO_SYNC_MINUTES_30 = 30L
private const val S3_AUTO_SYNC_HOURS_1 = 1L
private const val S3_AUTO_SYNC_HOURS_6 = 6L
private const val S3_AUTO_SYNC_HOURS_12 = 12L
private const val S3_AUTO_SYNC_HOURS_24 = 24L

internal fun parseS3AutoSyncInterval(interval: String): Duration =
    S3_AUTO_SYNC_INTERVALS[interval] ?: DEFAULT_S3_AUTO_SYNC_INTERVAL

private val S3_AUTO_SYNC_INTERVALS =
    mapOf(
        "30min" to Duration.ofMinutes(S3_AUTO_SYNC_MINUTES_30),
        "1h" to Duration.ofHours(S3_AUTO_SYNC_HOURS_1),
        "6h" to Duration.ofHours(S3_AUTO_SYNC_HOURS_6),
        "12h" to Duration.ofHours(S3_AUTO_SYNC_HOURS_12),
        "24h" to Duration.ofHours(S3_AUTO_SYNC_HOURS_24),
    )
