package com.lomo.data.repository

import java.net.URI

enum class S3EndpointProfile(
    val remoteIndexFreshnessIntervalMs: Long,
    val incrementalReconcileIntervalMs: Long,
    val scheduledReconcileIntervalFloorMs: Long,
    val changePressureThreshold: Double,
    val verificationFailureThreshold: Double,
    val minUncertaintyAttempts: Int,
    val minUncertaintyFailures: Int,
    val reconcilePageSize: Int,
    val reconcileHeadLimit: Int,
    val reconcileHeadConcurrency: Int,
    val cursorPageBonus: Int,
) {
    AWS_S3(
        remoteIndexFreshnessIntervalMs = 20 * 60_000L,
        incrementalReconcileIntervalMs = 90_000L,
        scheduledReconcileIntervalFloorMs = 4 * 60 * 60_000L,
        changePressureThreshold = 0.35,
        verificationFailureThreshold = 0.4,
        minUncertaintyAttempts = 2,
        minUncertaintyFailures = 2,
        reconcilePageSize = 320,
        reconcileHeadLimit = 18,
        reconcileHeadConcurrency = 5,
        cursorPageBonus = 96,
    ),
    CLOUDFLARE_R2(
        remoteIndexFreshnessIntervalMs = 12 * 60_000L,
        incrementalReconcileIntervalMs = S3_INCREMENTAL_RECONCILE_INTERVAL_MS,
        scheduledReconcileIntervalFloorMs = 6 * 60 * 60_000L,
        changePressureThreshold = 0.45,
        verificationFailureThreshold = 0.5,
        minUncertaintyAttempts = 2,
        minUncertaintyFailures = 2,
        reconcilePageSize = 256,
        reconcileHeadLimit = 14,
        reconcileHeadConcurrency = 4,
        cursorPageBonus = 64,
    ),
    MINIO_COMPAT(
        remoteIndexFreshnessIntervalMs = 5 * 60_000L,
        incrementalReconcileIntervalMs = 4 * 60_000L,
        scheduledReconcileIntervalFloorMs = 12 * 60 * 60_000L,
        changePressureThreshold = 0.6,
        verificationFailureThreshold = 0.7,
        minUncertaintyAttempts = 3,
        minUncertaintyFailures = 3,
        reconcilePageSize = 192,
        reconcileHeadLimit = 10,
        reconcileHeadConcurrency = 2,
        cursorPageBonus = 32,
    ),
    GENERIC_S3(
        remoteIndexFreshnessIntervalMs = S3_REMOTE_INDEX_FRESHNESS_INTERVAL_MS,
        incrementalReconcileIntervalMs = S3_INCREMENTAL_RECONCILE_INTERVAL_MS,
        scheduledReconcileIntervalFloorMs = 6 * 60 * 60_000L,
        changePressureThreshold = 0.5,
        verificationFailureThreshold = 0.5,
        minUncertaintyAttempts = 2,
        minUncertaintyFailures = 2,
        reconcilePageSize = 256,
        reconcileHeadLimit = 16,
        reconcileHeadConcurrency = 4,
        cursorPageBonus = 64,
    ),
}

internal fun inferS3EndpointProfile(endpointUrl: String): S3EndpointProfile {
    val uri = runCatching { URI(endpointUrl) }.getOrNull() ?: return S3EndpointProfile.GENERIC_S3
    val host = uri.host?.lowercase().orEmpty()
    if (host.isBlank()) {
        return S3EndpointProfile.GENERIC_S3
    }
    return when {
        host == "s3.amazonaws.com" ||
            host.endsWith(".amazonaws.com") ||
            host.endsWith(".amazonaws.com.cn") -> S3EndpointProfile.AWS_S3
        host.endsWith(".r2.cloudflarestorage.com") -> S3EndpointProfile.CLOUDFLARE_R2
        host.contains("minio") || host.isPrivateOrLoopbackHost() -> S3EndpointProfile.MINIO_COMPAT
        else -> S3EndpointProfile.GENERIC_S3
    }
}

private fun String.isPrivateOrLoopbackHost(): Boolean {
    if (this == "localhost" || this == "127.0.0.1" || this == "::1") {
        return true
    }
    if (startsWith("10.") || startsWith("192.168.")) {
        return true
    }
    if (startsWith("172.")) {
        val secondOctet = split('.').getOrNull(1)?.toIntOrNull()
        if (secondOctet != null && secondOctet in PRIVATE_172_RANGE_START..PRIVATE_172_RANGE_END) {
            return true
        }
    }
    return false
}

private const val PRIVATE_172_RANGE_START = 16
private const val PRIVATE_172_RANGE_END = 31
