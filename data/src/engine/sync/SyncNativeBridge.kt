package com.lomo.data.engine.sync

import com.lomo.nativebridge.SyncConflictPageDto as BridgeConflictPage
import com.lomo.nativebridge.SyncConflictResolutionDto as BridgeResolution
import com.lomo.nativebridge.SyncConflictResolveResultDto as BridgeResolveResult
import com.lomo.nativebridge.SyncCyclePlanSummaryDto as BridgeCyclePlan
import com.lomo.nativebridge.SyncRetryHintDto as BridgeRetryHint
import com.lomo.nativebridge.SyncSecretLeaseDto as BridgeSecretLease

/**
 * True FFI edge for Stage-5 dark sync free-functions (P5-09).
 *
 * Production (post P5-13): top-level `com.lomo.nativebridge.sync*` free-functions.
 * Host tests inject fakes so [BoltFfiRemoteSyncRepository] / [RustSyncRetryDispositionMapper]
 * mapping is exercised without JNI.
 *
 * Dual-stack Kotlin sync business owners remain production until P5-13; this bridge must not be
 * registered in [com.lomo.data.di.SyncDataModule] before cutover.
 */
interface SyncNativeBridge {
    fun listConflicts(
        workspaceRoot: String,
        cursor: UInt,
        limit: UInt,
    ): BridgeConflictPage

    fun resolveConflicts(
        workspaceRoot: String,
        expectedRevision: ULong,
        resolutions: List<BridgeResolution>,
    ): BridgeResolveResult

    fun issueSecretLease(
        secretBytes: ByteArray,
        ttlMillis: ULong,
    ): BridgeSecretLease

    fun probeSecretLease(leaseId: String): UInt

    fun revokeSecretLease(leaseId: String)

    fun retryDispositionFromName(name: String): BridgeRetryHint

    fun readConflictArtifact(
        workspaceRoot: String,
        artifactRef: String,
    ): ByteArray

    fun inspectCyclePlan(workspaceRoot: String): BridgeCyclePlan

    fun runCycle(
        workspaceRoot: String,
        backendKind: String,
        endpointUrl: String,
        usernameOrAccessKey: String,
        bucket: String,
        prefix: String,
        region: String,
        remoteDatasetId: String,
        secretLeaseId: String,
        applyRemote: Boolean,
    ): BridgeCyclePlan
}
