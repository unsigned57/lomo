package com.lomo.data.engine.sync

import com.lomo.nativebridge.SyncBackendConfigDto as BridgeBackendConfig
import com.lomo.nativebridge.SyncBackendProbeDto as BridgeBackendProbe
import com.lomo.nativebridge.SyncConflictPageDto as BridgeConflictPage
import com.lomo.nativebridge.SyncConflictResolutionDto as BridgeResolution
import com.lomo.nativebridge.SyncConflictResolveResultDto as BridgeResolveResult
import com.lomo.nativebridge.SyncCyclePlanSummaryDto as BridgeCyclePlan
import com.lomo.nativebridge.SyncCycleStatusDto as BridgeCycleStatus
import com.lomo.nativebridge.SyncSecretLeaseDto as BridgeSecretLease

/**
 * True FFI edge for the `com.lomo.nativebridge.sync*` free-functions.
 *
 * Host tests inject fakes so [BoltFfiRemoteSyncRepository] / [RustSyncRetryDispositionMapper]
 * mapping is exercised without JNI.
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

    fun readConflictArtifact(
        workspaceRoot: String,
        artifactRef: String,
    ): ByteArray

    fun runCycle(
        workspaceRoot: String,
        config: BridgeBackendConfig,
        secretLeaseId: String = "",
        applyRemote: Boolean = false,
    ): BridgeCyclePlan

    fun loadWorkspaceGeneration(workspaceRoot: String): String

    fun resetControlTree(workspaceRoot: String)

    /** Durable cycle record read (`cycle_state.rec`) — sole authority for sync status. */
    fun cycleStatus(workspaceRoot: String): BridgeCycleStatus

    /** Durable cancel request bound to the running cycle's fence; returns post-write record. */
    fun requestCancel(workspaceRoot: String): BridgeCycleStatus

    /** Real adapter construction + capabilities + listing round-trip (`testConnection`). */
    fun probeBackend(
        workspaceRoot: String,
        config: BridgeBackendConfig,
        secretLeaseId: String = "",
    ): BridgeBackendProbe
}
