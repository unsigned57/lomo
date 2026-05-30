package com.lomo.data.sync

import com.lomo.data.repository.S3EndpointProfile
import com.lomo.data.repository.S3RemoteShardScheduleTelemetry
import com.lomo.data.repository.S3RemoteShardState
import com.lomo.data.repository.S3RemoteShardStateStore
import com.lomo.data.repository.S3_SYNC_WORK_INTENT_PARAMETER
import com.lomo.data.repository.S3SyncProtocolState
import com.lomo.data.repository.S3SyncProtocolStateStore
import com.lomo.data.repository.S3SyncRepositorySupport
import com.lomo.data.repository.S3_RECENT_CHANGE_WINDOW_DIVISOR
import com.lomo.data.worker.S3SyncWorker
import com.lomo.data.repository.S3SyncWorkIntent
import com.lomo.domain.model.SyncBackendType
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class S3SyncWorkPolicyPlanner
    @Inject
    constructor(
        private val support: S3SyncRepositorySupport,
        private val protocolStateStore: S3SyncProtocolStateStore,
        private val remoteShardStateStore: S3RemoteShardStateStore,
    ) {
        private val policy = S3SyncWorkPolicy()

        suspend fun planAutoSchedule(interval: String): SyncWorkDecision {
            val fastInterval = parseS3AutoSyncInterval(interval)
            val endpointProfile = resolveEndpointProfile()
            val reconcileInterval =
                effectiveS3ReconcileInterval(
                    requestedInterval = fastInterval,
                    endpointProfile = endpointProfile,
                )
            return policy.plan(
                S3SyncWorkPolicyInput.AutoSchedule(
                    fastInterval = fastInterval,
                    state =
                        loadState(
                            reconcileInterval = reconcileInterval,
                            endpointProfile = endpointProfile,
                        ),
                ),
            )
        }

        suspend fun planRefresh(
            reconcileInterval: Duration,
            signal: SyncRefreshSignal = SyncRefreshSignal.NORMAL,
        ): SyncWorkDecision {
            val endpointProfile = resolveEndpointProfile()
            val effectiveReconcileInterval =
                effectiveS3ReconcileInterval(
                    requestedInterval = reconcileInterval,
                    endpointProfile = endpointProfile,
                )
            return policy.plan(
                S3SyncWorkPolicyInput.Refresh(
                    signal = signal,
                    state =
                        loadState(
                            reconcileInterval = effectiveReconcileInterval,
                            endpointProfile = endpointProfile,
                        ),
                ),
            )
        }

        fun planCatchUp(policy: S3SyncWorkIntent): SyncWorkDecision =
            this.policy.plan(S3SyncWorkPolicyInput.CatchUp(policy))

        private suspend fun loadState(
            reconcileInterval: Duration,
            endpointProfile: S3EndpointProfile,
        ): S3SyncWorkState =
            S3SyncWorkState(
                reconcileInterval = reconcileInterval,
                endpointProfile = endpointProfile,
                incrementalEnabled = protocolStateStore.incrementalSyncEnabled,
                protocolState = protocolStateStore.readIfIncrementalEnabled(),
                telemetry =
                    remoteShardStateStore.readScheduleTelemetryIfEnabled(
                        reconcileInterval = reconcileInterval,
                        endpointProfile = endpointProfile,
                    ),
            )

        private suspend fun resolveEndpointProfile(): S3EndpointProfile =
            support.resolveConfig()?.endpointProfile ?: S3EndpointProfile.GENERIC_S3
    }

class S3SyncWorkPolicy : SyncWorkPolicy<S3SyncWorkPolicyInput> {
    override fun plan(input: S3SyncWorkPolicyInput): SyncWorkDecision =
        when (input) {
            is S3SyncWorkPolicyInput.AutoSchedule -> planAutoSchedule(input)
            is S3SyncWorkPolicyInput.Refresh -> planRefresh(input)
            is S3SyncWorkPolicyInput.CatchUp -> planCatchUp(input.policy)
        }

    private fun planAutoSchedule(input: S3SyncWorkPolicyInput.AutoSchedule): SyncWorkDecision =
        SyncWorkDecision(
            scheduledWork =
                buildList {
                    add(
                        SyncScheduledWork(
                            backend = SyncBackendType.S3,
                            trigger = SyncWorkTrigger.PERIODIC_AUTO_SYNC,
                            uniqueWorkName = S3SyncWorker.WORK_NAME,
                            cadence = SyncWorkCadence.Periodic(input.fastInterval),
                            networkRequirement = SyncWorkNetworkRequirement.Connected,
                            existingWorkPolicy = SyncExistingWorkPolicy.Replace,
                            payload = S3SyncWorkIntent.FAST_ONLY.toSyncWorkPayload(),
                        ),
                    )
                    add(
                        SyncScheduledWork(
                            backend = SyncBackendType.S3,
                            trigger = SyncWorkTrigger.PERIODIC_AUTO_SYNC,
                            uniqueWorkName = S3SyncWorker.RECONCILE_WORK_NAME,
                            cadence = SyncWorkCadence.Periodic(input.state.reconcileInterval),
                            networkRequirement = SyncWorkNetworkRequirement.UnmeteredCharging,
                            existingWorkPolicy = SyncExistingWorkPolicy.Replace,
                            payload = S3SyncWorkIntent.FULL_RECONCILE.toSyncWorkPayload(),
                        ),
                    )
                    resolveCatchUpPolicy(input.state)?.let { catchUpPolicy ->
                        add(catchUpWork(catchUpPolicy))
                    }
                },
        )

    private fun planRefresh(input: S3SyncWorkPolicyInput.Refresh): SyncWorkDecision {
        val foregroundPolicy = resolveRefreshForegroundPolicy(input)
        val catchUpPolicy = resolveRefreshCatchUpPolicy(input.state, foregroundPolicy)
        return SyncWorkDecision(
            foregroundWork =
                SyncForegroundWork(
                    backend = SyncBackendType.S3,
                    trigger = SyncWorkTrigger.REFRESH,
                    payload = foregroundPolicy.toSyncWorkPayload(),
                ),
            scheduledWork =
                catchUpPolicy
                    ?.let { listOf(catchUpWork(it)) }
                    .orEmpty(),
        )
    }

    private fun resolveRefreshForegroundPolicy(input: S3SyncWorkPolicyInput.Refresh): S3SyncWorkIntent {
        val basePolicy =
            when {
                !input.state.incrementalEnabled -> S3SyncWorkIntent.FAST_THEN_RECONCILE
                input.state.hasHighVerificationUncertainty -> S3SyncWorkIntent.FULL_RECONCILE
                else -> S3SyncWorkIntent.FAST_ONLY
            }
        return when (input.signal) {
            SyncRefreshSignal.NORMAL -> basePolicy
            SyncRefreshSignal.STRONG_REMOTE_HINT -> basePolicy.upgradeForStrongRefreshSignal()
        }
    }

    private fun resolveRefreshCatchUpPolicy(
        state: S3SyncWorkState,
        foregroundPolicy: S3SyncWorkIntent,
    ): S3SyncWorkIntent? =
        when {
            !state.incrementalEnabled -> null
            foregroundPolicy == S3SyncWorkIntent.FULL_RECONCILE -> null
            state.requiresFullReconcileCatchUp -> S3SyncWorkIntent.FULL_RECONCILE
            state.requiresRollingReconcileCatchUp -> S3SyncWorkIntent.FAST_THEN_RECONCILE
            else -> null
        }

    private fun planCatchUp(policy: S3SyncWorkIntent): SyncWorkDecision =
        SyncWorkDecision(scheduledWork = listOf(catchUpWork(policy)))

    private fun catchUpWork(policy: S3SyncWorkIntent): SyncScheduledWork =
        SyncScheduledWork(
            backend = SyncBackendType.S3,
            trigger = SyncWorkTrigger.CATCH_UP,
            uniqueWorkName = S3SyncWorker.RECONCILE_CATCH_UP_WORK_NAME,
            cadence = SyncWorkCadence.OneTime,
            networkRequirement = policy.catchUpNetworkRequirement(),
            existingWorkPolicy = SyncExistingWorkPolicy.Replace,
            payload = policy.toSyncWorkPayload(),
        )
}

private fun S3SyncWorkIntent.toSyncWorkPayload(): SyncWorkPayload =
    SyncWorkPayload.ProviderParameters(mapOf(S3_SYNC_WORK_INTENT_PARAMETER to name))

sealed interface S3SyncWorkPolicyInput {
    data class AutoSchedule(
        val fastInterval: Duration,
        val state: S3SyncWorkState,
    ) : S3SyncWorkPolicyInput

    data class Refresh(
        val signal: SyncRefreshSignal = SyncRefreshSignal.NORMAL,
        val state: S3SyncWorkState,
    ) : S3SyncWorkPolicyInput

    data class CatchUp(
        val policy: S3SyncWorkIntent,
    ) : S3SyncWorkPolicyInput
}

data class S3SyncWorkState(
    val reconcileInterval: Duration,
    val endpointProfile: S3EndpointProfile = S3EndpointProfile.GENERIC_S3,
    val incrementalEnabled: Boolean,
    val protocolState: S3SyncProtocolState?,
    val telemetry: S3RemoteShardScheduleTelemetry? = null,
    val shardStates: List<S3RemoteShardState>? = null,
    val now: Long = System.currentTimeMillis(),
)

internal fun resolveCatchUpPolicy(state: S3SyncWorkState): S3SyncWorkIntent? =
    when {
        !state.incrementalEnabled -> null
        state.requiresFullReconcileCatchUp -> S3SyncWorkIntent.FULL_RECONCILE
        state.requiresRollingReconcileCatchUp -> S3SyncWorkIntent.FAST_THEN_RECONCILE
        else -> null
    }

internal fun effectiveS3ReconcileInterval(
    requestedInterval: Duration,
    endpointProfile: S3EndpointProfile,
): Duration =
    requestedInterval.coerceAtLeast(Duration.ofMillis(endpointProfile.scheduledReconcileIntervalFloorMs))

private suspend fun S3SyncProtocolStateStore.readIfIncrementalEnabled(): S3SyncProtocolState? =
    if (incrementalSyncEnabled) {
        read()
    } else {
        null
    }

private suspend fun S3RemoteShardStateStore.readScheduleTelemetryIfEnabled(
    reconcileInterval: Duration,
    endpointProfile: S3EndpointProfile,
    now: Long = System.currentTimeMillis(),
): S3RemoteShardScheduleTelemetry? =
    if (remoteShardStateEnabled) {
        readScheduleTelemetry(
            now = now,
            reconcileInterval = reconcileInterval,
            endpointProfile = endpointProfile,
        )
    } else {
        null
    }

private val S3SyncWorkState.effectiveTelemetry: S3RemoteShardScheduleTelemetry
    get() = telemetry ?: shardStates.orEmpty().toScheduleTelemetry(now, reconcileInterval, endpointProfile)

private val S3SyncWorkState.requiresFullReconcileCatchUp: Boolean
    get() =
        protocolState?.lastFullRemoteScanAt == null ||
            telemetry?.let { it.shardCount == 0 } == true ||
            shardStates?.isEmpty() == true ||
            effectiveTelemetry.hasHighVerificationUncertainty ||
            fullRemoteScanIsStale

private val S3SyncWorkState.requiresRollingReconcileCatchUp: Boolean
    get() =
        protocolState?.remoteScanCursor != null ||
            effectiveTelemetry.hasElevatedChangePressure ||
            effectiveTelemetry.oldestScanAgeMillis(now)?.let { age -> age >= reconcileInterval.toMillis() } == true

private val S3SyncWorkState.hasHighVerificationUncertainty: Boolean
    get() = effectiveTelemetry.hasHighVerificationUncertainty

private val S3SyncWorkState.fullRemoteScanIsStale: Boolean
    get() {
        val lastFullRemoteScanAt = protocolState?.lastFullRemoteScanAt ?: return false
        return now - lastFullRemoteScanAt >= reconcileInterval.toMillis() * FULL_RECONCILE_STALE_MULTIPLIER
    }

private fun S3SyncWorkIntent.upgradeForStrongRefreshSignal(): S3SyncWorkIntent =
    when (this) {
        S3SyncWorkIntent.FULL_RECONCILE -> this
        S3SyncWorkIntent.FAST_ONLY,
        S3SyncWorkIntent.FAST_THEN_RECONCILE,
        -> S3SyncWorkIntent.FAST_THEN_RECONCILE
    }

private fun S3SyncWorkIntent.catchUpNetworkRequirement(): SyncWorkNetworkRequirement =
    when (this) {
        S3SyncWorkIntent.FULL_RECONCILE -> SyncWorkNetworkRequirement.UnmeteredCharging
        S3SyncWorkIntent.FAST_ONLY,
        S3SyncWorkIntent.FAST_THEN_RECONCILE,
        -> SyncWorkNetworkRequirement.Connected
    }

internal fun List<S3RemoteShardState>.toScheduleTelemetry(
    now: Long,
    reconcileInterval: Duration,
    endpointProfile: S3EndpointProfile = S3EndpointProfile.GENERIC_S3,
): S3RemoteShardScheduleTelemetry =
    S3RemoteShardScheduleTelemetry(
        shardCount = size,
        oldestScanAt = minOfOrNull(S3RemoteShardState::lastScannedAt),
        hasElevatedChangePressure =
            any { state ->
                state.idleScanStreak == 0 &&
                    state.scanAgeMillis(now) <= reconcileInterval.toMillis() / S3_RECENT_CHANGE_WINDOW_DIVISOR &&
                    state.changeRate() >= endpointProfile.changePressureThreshold
            },
        hasHighVerificationUncertainty =
            any { state ->
                state.scanAgeMillis(now) <= reconcileInterval.toMillis() &&
                    state.lastVerificationAttemptCount >= endpointProfile.minUncertaintyAttempts &&
                    state.lastVerificationFailureCount >= endpointProfile.minUncertaintyFailures &&
                    state.lastVerificationFailureCount.toDouble() /
                    state.lastVerificationAttemptCount.coerceAtLeast(1).toDouble() >=
                    endpointProfile.verificationFailureThreshold
            },
    )

private fun S3RemoteShardScheduleTelemetry.oldestScanAgeMillis(now: Long): Long? {
    val oldestScanAtValue = oldestScanAt ?: return null
    return (now - oldestScanAtValue).coerceAtLeast(0L)
}

private fun S3RemoteShardState.scanAgeMillis(now: Long): Long =
    (now - lastScannedAt).coerceAtLeast(0L)

private fun S3RemoteShardState.changeRate(): Double =
    lastChangeCount.toDouble() / lastObjectCount.coerceAtLeast(1).toDouble()

internal const val FULL_RECONCILE_STALE_MULTIPLIER = 2L
