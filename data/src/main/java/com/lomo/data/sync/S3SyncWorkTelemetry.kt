package com.lomo.data.sync

import com.lomo.data.repository.S3EndpointProfile
import com.lomo.data.repository.S3RemoteShardScheduleTelemetry
import com.lomo.data.repository.S3RemoteShardState
import com.lomo.data.repository.S3_RECENT_CHANGE_WINDOW_DIVISOR
import java.time.Duration

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
                    state.verificationFailureRate() >= endpointProfile.verificationFailureThreshold
            },
    )

private fun S3RemoteShardState.scanAgeMillis(now: Long): Long =
    (now - lastScannedAt).coerceAtLeast(0L)

private fun S3RemoteShardState.changeRate(): Double =
    lastChangeCount.toDouble() / lastObjectCount.coerceAtLeast(1).toDouble()

private fun S3RemoteShardState.verificationFailureRate(): Double =
    lastVerificationFailureCount.toDouble() /
        lastVerificationAttemptCount.coerceAtLeast(1).toDouble()
