package com.lomo.data.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.repository.S3RemoteShardState
import com.lomo.data.repository.S3RemoteShardStateStore
import com.lomo.data.repository.S3SyncProtocolState
import com.lomo.data.repository.S3SyncProtocolStateStore
import com.lomo.domain.model.S3SyncScanPolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class S3SyncScheduler
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val dataStore: LomoDataStore,
        private val protocolStateStore: S3SyncProtocolStateStore,
        private val remoteShardStateStore: S3RemoteShardStateStore,
    ) {
        suspend fun reschedule() {
            val enabled = dataStore.s3SyncEnabled.first()
            val autoSyncEnabled = dataStore.s3AutoSyncEnabled.first()
            val workManager = WorkManager.getInstance(context)
            if (!enabled || !autoSyncEnabled) {
                cancel()
                return
            }

            val interval = dataStore.s3AutoSyncInterval.first()
            val protocolState =
                if (protocolStateStore.incrementalSyncEnabled) {
                    protocolStateStore.read()
                } else {
                    null
                }
            val shardStates =
                if (remoteShardStateStore.remoteShardStateEnabled) {
                    remoteShardStateStore.readAll()
                } else {
                    null
                }
            val schedulePlan =
                buildS3SyncSchedulePlan(
                    interval = interval,
                    protocolState = protocolState,
                    incrementalEnabled = protocolStateStore.incrementalSyncEnabled,
                    shardStates = shardStates,
                )
            val fastRequest =
                PeriodicWorkRequestBuilder<S3SyncWorker>(schedulePlan.fastInterval)
                    .setInputData(S3SyncWorker.inputData(S3SyncScanPolicy.FAST_ONLY))
                    .setConstraints(
                        Constraints
                            .Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build(),
                    ).build()
            val reconcileRequest =
                PeriodicWorkRequestBuilder<S3SyncWorker>(schedulePlan.reconcileInterval)
                    .setInputData(S3SyncWorker.inputData(S3SyncScanPolicy.FULL_RECONCILE))
                    .setConstraints(
                        Constraints
                            .Builder()
                            .setRequiredNetworkType(NetworkType.UNMETERED)
                            .setRequiresCharging(true)
                            .build(),
                    ).build()

            workManager.enqueueUniquePeriodicWork(
                S3SyncWorker.WORK_NAME,
                ExistingPeriodicWorkPolicy.REPLACE,
                fastRequest,
            )
            workManager.enqueueUniquePeriodicWork(
                S3SyncWorker.RECONCILE_WORK_NAME,
                ExistingPeriodicWorkPolicy.REPLACE,
                reconcileRequest,
            )
            schedulePlan.catchUpPolicy?.let { policy ->
                workManager.enqueueUniqueWork(
                    S3SyncWorker.RECONCILE_CATCH_UP_WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<S3SyncWorker>()
                        .setInputData(S3SyncWorker.inputData(policy))
                        .setConstraints(catchUpConstraints(policy))
                        .build(),
                )
            }
            Timber.d("S3 auto-sync scheduled with interval: %s", interval)
        }

        fun cancel() {
            WorkManager.getInstance(context).cancelUniqueWork(S3SyncWorker.WORK_NAME)
            WorkManager.getInstance(context).cancelUniqueWork(S3SyncWorker.RECONCILE_WORK_NAME)
            WorkManager.getInstance(context).cancelUniqueWork(S3SyncWorker.RECONCILE_CATCH_UP_WORK_NAME)
            Timber.d("S3 auto-sync cancelled")
        }

        private fun catchUpConstraints(policy: S3SyncScanPolicy): Constraints =
            when (policy) {
                S3SyncScanPolicy.FULL_RECONCILE ->
                    Constraints
                        .Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresCharging(true)
                        .build()

                S3SyncScanPolicy.FAST_ONLY,
                S3SyncScanPolicy.FAST_THEN_RECONCILE,
                ->
                    Constraints
                        .Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
            }

    }

internal data class S3SyncSchedulePlan(
    val fastInterval: Duration,
    val reconcileInterval: Duration,
    val catchUpPolicy: S3SyncScanPolicy?,
)

internal fun buildS3SyncSchedulePlan(
    interval: String,
    protocolState: S3SyncProtocolState?,
    incrementalEnabled: Boolean,
    shardStates: List<S3RemoteShardState>? = null,
    now: Long = System.currentTimeMillis(),
): S3SyncSchedulePlan {
    val fastInterval = parseS3AutoSyncInterval(interval)
    val reconcileInterval = fastInterval.coerceAtLeast(MIN_S3_RECONCILE_INTERVAL)
    return S3SyncSchedulePlan(
        fastInterval = fastInterval,
        reconcileInterval = reconcileInterval,
        catchUpPolicy =
            resolveCatchUpPolicy(
                protocolState = protocolState,
                reconcileInterval = reconcileInterval,
                now = now,
                incrementalEnabled = incrementalEnabled,
                shardStates = shardStates,
            ),
    )
}

internal fun resolveCatchUpPolicy(
    protocolState: S3SyncProtocolState?,
    reconcileInterval: Duration,
    now: Long = System.currentTimeMillis(),
    incrementalEnabled: Boolean,
    shardStates: List<S3RemoteShardState>? = null,
): S3SyncScanPolicy? {
    if (!incrementalEnabled) {
        return null
    }
    val telemetry = shardStates.orEmpty()
    return when {
        protocolState?.lastFullRemoteScanAt == null -> S3SyncScanPolicy.FULL_RECONCILE
        shardStates != null && shardStates.isEmpty() -> S3SyncScanPolicy.FULL_RECONCILE
        telemetry.hasHighVerificationUncertainty(now, reconcileInterval) -> S3SyncScanPolicy.FULL_RECONCILE
        protocolState.remoteScanCursor != null -> S3SyncScanPolicy.FAST_THEN_RECONCILE
        telemetry.hasElevatedChangePressure(now, reconcileInterval) -> S3SyncScanPolicy.FAST_THEN_RECONCILE
        shardStates.orEmpty().oldestScanAgeMillis(now)?.let { age -> age >= reconcileInterval.toMillis() } == true ->
            S3SyncScanPolicy.FAST_THEN_RECONCILE
        now - protocolState.lastFullRemoteScanAt >= reconcileInterval.toMillis() * FULL_RECONCILE_STALE_MULTIPLIER ->
            S3SyncScanPolicy.FULL_RECONCILE

        else -> null
    }
}

private fun List<S3RemoteShardState>.oldestScanAgeMillis(now: Long): Long? {
    val oldestScanAt = minOfOrNull(S3RemoteShardState::lastScannedAt) ?: return null
    return (now - oldestScanAt).coerceAtLeast(0L)
}

private fun List<S3RemoteShardState>.hasElevatedChangePressure(
    now: Long,
    reconcileInterval: Duration,
): Boolean {
    val recentWindowMillis = reconcileInterval.toMillis() / RECENT_CHANGE_WINDOW_DIVISOR
    return any { state ->
        state.idleScanStreak == 0 &&
            state.scanAgeMillis(now) <= recentWindowMillis &&
            state.changeRate() >= CHANGE_PRESSURE_THRESHOLD
    }
}

private fun List<S3RemoteShardState>.hasHighVerificationUncertainty(
    now: Long,
    reconcileInterval: Duration,
): Boolean {
    val recentWindowMillis = reconcileInterval.toMillis()
    return any { state ->
        state.scanAgeMillis(now) <= recentWindowMillis &&
            state.lastVerificationAttemptCount >= MIN_UNCERTAINTY_ATTEMPTS &&
            state.lastVerificationFailureCount >= MIN_UNCERTAINTY_FAILURES &&
            state.verificationFailureRate() >= VERIFICATION_FAILURE_THRESHOLD
    }
}

private fun S3RemoteShardState.scanAgeMillis(now: Long): Long = (now - lastScannedAt).coerceAtLeast(0L)

private fun S3RemoteShardState.changeRate(): Double =
    lastChangeCount.toDouble() / lastObjectCount.coerceAtLeast(1).toDouble()

private fun S3RemoteShardState.verificationFailureRate(): Double =
    lastVerificationFailureCount.toDouble() / lastVerificationAttemptCount.coerceAtLeast(1).toDouble()

private fun parseS3AutoSyncInterval(interval: String): Duration =
    S3_AUTO_SYNC_INTERVALS[interval] ?: DEFAULT_S3_AUTO_SYNC_INTERVAL

private val S3_AUTO_SYNC_INTERVALS =
    mapOf(
        "30min" to Duration.ofMinutes(S3_AUTO_SYNC_MINUTES_30),
        "1h" to Duration.ofHours(S3_AUTO_SYNC_HOURS_1),
        "6h" to Duration.ofHours(S3_AUTO_SYNC_HOURS_6),
        "12h" to Duration.ofHours(S3_AUTO_SYNC_HOURS_12),
        "24h" to Duration.ofHours(S3_AUTO_SYNC_HOURS_24),
    )

private val DEFAULT_S3_AUTO_SYNC_INTERVAL = Duration.ofHours(S3_AUTO_SYNC_HOURS_1)
private val MIN_S3_RECONCILE_INTERVAL = Duration.ofHours(S3_AUTO_SYNC_HOURS_6)
private const val FULL_RECONCILE_STALE_MULTIPLIER = 2L
private const val RECENT_CHANGE_WINDOW_DIVISOR = 2L
private const val CHANGE_PRESSURE_THRESHOLD = 0.5
private const val VERIFICATION_FAILURE_THRESHOLD = 0.5
private const val MIN_UNCERTAINTY_ATTEMPTS = 2
private const val MIN_UNCERTAINTY_FAILURES = 2
private const val S3_AUTO_SYNC_MINUTES_30 = 30L
private const val S3_AUTO_SYNC_HOURS_1 = 1L
private const val S3_AUTO_SYNC_HOURS_6 = 6L
private const val S3_AUTO_SYNC_HOURS_12 = 12L
private const val S3_AUTO_SYNC_HOURS_24 = 24L
