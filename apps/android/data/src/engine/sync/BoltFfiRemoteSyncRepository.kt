package com.lomo.data.engine.sync

import com.lomo.data.engine.SessionNativeBridge
import com.lomo.nativebridge.EngineError
import com.lomo.nativebridge.SyncBackendConfigDto as BridgeBackendConfig
import com.lomo.nativebridge.SyncConflictPageDto as BridgeConflictPage
import com.lomo.nativebridge.SyncConflictPathDto as BridgeConflictPath
import com.lomo.nativebridge.SyncConflictPathStatusDto as BridgePathStatus
import com.lomo.nativebridge.SyncConflictResolutionDto as BridgeResolution
import com.lomo.nativebridge.SyncConflictResolveResultDto as BridgeResolveResult
import com.lomo.nativebridge.SyncConflictSessionStateDto as BridgeConflictSession
import com.lomo.nativebridge.SyncCyclePlanSummaryDto as BridgeCyclePlan
import com.lomo.nativebridge.SyncCycleStatusDto as BridgeCycleStatus
import com.lomo.nativebridge.SyncBackendProbeDto as BridgeBackendProbe
import com.lomo.nativebridge.SyncSecretLeaseDto as BridgeSecretLease

/**
 * Production [RemoteSyncRepository] over [SyncNativeBridge].
 *
 * Mapping only — conflict revision fences, path budgets, composed owner cycle, session local pull,
 * and secret vault rules stay in Rust. Production-wired at P5-13.
 */
class BoltFfiRemoteSyncRepository(
    private val bridge: SyncNativeBridge,
) : RemoteSyncRepository {
    override fun listConflicts(
        workspaceRoot: String,
        cursor: Int,
        limit: Int,
    ): RemoteSyncConflictPage {
        require(cursor >= 0) { "conflict list cursor must be non-negative" }
        require(limit > 0) { "conflict list limit must be positive" }
        return mapBoundary {
            bridge
                .listConflicts(
                    workspaceRoot = workspaceRoot,
                    cursor = cursor.toUInt(),
                    limit = limit.toUInt(),
                ).toFacts()
        }
    }

    override fun resolveConflicts(
        workspaceRoot: String,
        expectedRevision: Long,
        resolutions: List<RemoteSyncConflictResolution>,
    ): RemoteSyncConflictResolveResult {
        require(expectedRevision >= 0) { "expected conflict revision must be non-negative" }
        require(resolutions.isNotEmpty()) { "resolution batch must be non-empty" }
        return mapBoundary {
            bridge
                .resolveConflicts(
                    workspaceRoot = workspaceRoot,
                    expectedRevision = expectedRevision.toULong(),
                    resolutions = resolutions.map { it.toBridge() },
                ).toFacts()
        }
    }

    override fun issueSecretLease(
        secretBytes: ByteArray,
        ttlMillis: Long,
    ): RemoteSyncSecretLease {
        require(secretBytes.isNotEmpty()) { "secret material must be non-empty" }
        require(ttlMillis > 0) { "secret lease TTL must be positive" }
        return mapBoundary {
            bridge
                .issueSecretLease(
                    secretBytes = secretBytes,
                    ttlMillis = ttlMillis.toULong(),
                ).toFacts()
        }
    }

    override fun probeSecretLease(leaseId: String): Int {
        require(leaseId.isNotBlank()) { "lease id must be non-blank" }
        return mapBoundary {
            bridge.probeSecretLease(leaseId).toInt()
        }
    }

    override fun revokeSecretLease(leaseId: String) {
        require(leaseId.isNotBlank()) { "lease id must be non-blank" }
        mapBoundary {
            bridge.revokeSecretLease(leaseId)
        }
    }

    override fun runCycle(request: RemoteSyncCycleRequest): RemoteSyncCyclePlanSummary {
        require(request.workspaceRoot.isNotBlank()) { "workspace root must be non-blank" }
        require(request.backendKind.isNotBlank()) { "backend kind must be non-blank" }
        return mapBoundary {
            bridge
                .runCycle(
                    workspaceRoot = request.workspaceRoot.trim(),
                    config = request.toBridgeConfig(),
                    secretLeaseId = request.secretLeaseId.orEmpty(),
                    applyRemote = request.applyRemote,
                ).toFacts()
        }
    }

    override fun loadWorkspaceGeneration(workspaceRoot: String): String {
        require(workspaceRoot.isNotBlank()) { "workspace root must be non-blank" }
        return mapBoundary {
            bridge.loadWorkspaceGeneration(workspaceRoot = workspaceRoot.trim())
        }
    }

    override fun resetControlTree(workspaceRoot: String) {
        require(workspaceRoot.isNotBlank()) { "workspace root must be non-blank" }
        mapBoundary {
            bridge.resetControlTree(workspaceRoot = workspaceRoot.trim())
        }
    }

    override fun cycleStatus(workspaceRoot: String): RemoteSyncCycleStatus {
        require(workspaceRoot.isNotBlank()) { "workspace root must be non-blank" }
        return mapBoundary {
            bridge.cycleStatus(workspaceRoot = workspaceRoot.trim()).toFacts()
        }
    }

    override fun requestCancel(workspaceRoot: String): RemoteSyncCycleStatus {
        require(workspaceRoot.isNotBlank()) { "workspace root must be non-blank" }
        return mapBoundary {
            bridge.requestCancel(workspaceRoot = workspaceRoot.trim()).toFacts()
        }
    }

    override fun probeBackend(request: RemoteSyncCycleRequest): RemoteSyncBackendProbe {
        require(request.workspaceRoot.isNotBlank()) { "workspace root must be non-blank" }
        require(request.backendKind.isNotBlank()) { "backend kind must be non-blank" }
        return mapBoundary {
            bridge
                .probeBackend(
                    workspaceRoot = request.workspaceRoot.trim(),
                    config = request.toBridgeConfig(),
                    secretLeaseId = request.secretLeaseId.orEmpty(),
                ).toFacts()
        }
    }
}

/**
 * Production free-function bridge for inspect/list/secret (no session writes).
 *
 * Apply cycles are not this type: [EngineOwnedSyncNativeBridge] routes `runCycle` through the
 * open workspace session. Host tests still inject fakes for mapping without JNI.
 */
class FreeFunctionSyncNativeBridge : SyncNativeBridge {
    override fun listConflicts(
        workspaceRoot: String,
        cursor: UInt,
        limit: UInt,
    ): BridgeConflictPage = com.lomo.nativebridge.syncListConflicts(workspaceRoot, cursor, limit)

    override fun resolveConflicts(
        workspaceRoot: String,
        expectedRevision: ULong,
        resolutions: List<BridgeResolution>,
    ): BridgeResolveResult =
        com.lomo.nativebridge.syncResolveConflicts(workspaceRoot, expectedRevision, resolutions)

    override fun issueSecretLease(
        secretBytes: ByteArray,
        ttlMillis: ULong,
    ): BridgeSecretLease = com.lomo.nativebridge.syncIssueSecretLease(secretBytes, ttlMillis)

    override fun probeSecretLease(leaseId: String): UInt =
        com.lomo.nativebridge.syncProbeSecretLease(leaseId)

    override fun revokeSecretLease(leaseId: String) {
        com.lomo.nativebridge.syncRevokeSecretLease(leaseId)
    }

    override fun readConflictArtifact(
        workspaceRoot: String,
        artifactRef: String,
    ): ByteArray = com.lomo.nativebridge.syncReadConflictArtifact(workspaceRoot, artifactRef)

    override fun runCycle(
        workspaceRoot: String,
        config: BridgeBackendConfig,
        secretLeaseId: String,
        applyRemote: Boolean,
    ): BridgeCyclePlan =
        com.lomo.nativebridge.syncRunCycle(
            workspaceRoot,
            config,
            secretLeaseId,
            applyRemote,
        )

    override fun loadWorkspaceGeneration(workspaceRoot: String): String =
        com.lomo.nativebridge.syncWorkspaceGeneration(workspaceRoot)

    override fun resetControlTree(workspaceRoot: String) {
        com.lomo.nativebridge.syncResetControlTree(workspaceRoot)
    }

    override fun cycleStatus(workspaceRoot: String): BridgeCycleStatus =
        com.lomo.nativebridge.syncCycleStatus(workspaceRoot)

    override fun requestCancel(workspaceRoot: String): BridgeCycleStatus =
        com.lomo.nativebridge.syncRequestCancel(workspaceRoot)

    override fun probeBackend(
        workspaceRoot: String,
        config: BridgeBackendConfig,
        secretLeaseId: String,
    ): BridgeBackendProbe =
        com.lomo.nativebridge.syncProbeBackend(workspaceRoot, config, secretLeaseId)
}

/**
 * Production [SyncNativeBridge]: listing/secret/inspect stay free-functions; apply cycles use the
 * open [SessionNativeBridge] so KeepRemote/Merged local pulls go through `WorkspaceSession`.
 */
internal class EngineOwnedSyncNativeBridge(
    private val engine: SessionNativeBridge,
    private val freeFunctions: SyncNativeBridge = FreeFunctionSyncNativeBridge(),
) : SyncNativeBridge by freeFunctions {
    override fun runCycle(
        workspaceRoot: String,
        config: BridgeBackendConfig,
        secretLeaseId: String,
        applyRemote: Boolean,
    ): BridgeCyclePlan =
        engine.syncRunCycle(
            workspaceRoot = workspaceRoot,
            config = config,
            secretLeaseId = secretLeaseId,
            applyRemote = applyRemote,
        )
}

private inline fun <T> mapBoundary(block: () -> T): T =
    try {
        block()
    } catch (error: EngineError.Failure) {
        val failure = error.failure
        throw RemoteSyncBoundaryFailure(
            category = failure.category,
            code = failure.code,
            retryDisposition = failure.retryDisposition,
            diagnostic = failure.diagnostic,
            operationId = failure.operationId,
            jobId = failure.jobId,
        ).also { mapped -> mapped.initCause(error) }
    }

private fun BridgeConflictPage.toFacts(): RemoteSyncConflictPage =
    RemoteSyncConflictPage(
        session = session.toFacts(),
        sessionId = sessionId,
        conflictRevision = conflictRevision.toLong(),
        items = items.map { it.toFacts() },
        nextCursor = nextCursor?.toInt(),
    )

private fun BridgeConflictSession.toFacts(): RemoteSyncConflictSessionState =
    when (this) {
        BridgeConflictSession.ABSENT -> RemoteSyncConflictSessionState.Absent
        BridgeConflictSession.PRESENT -> RemoteSyncConflictSessionState.Present
    }

private fun BridgeConflictPath.toFacts(): RemoteSyncConflictPath =
    RemoteSyncConflictPath(
        path = path,
        kind = kind,
        localDigest = localDigest,
        remoteDigest = remoteDigest,
        baselineDigest = baselineDigest,
        remoteTokenPresent = remoteTokenPresent,
        localArtifactRef = localArtifactRef,
        remoteArtifactRef = remoteArtifactRef,
        baselineArtifactRef = baselineArtifactRef,
        status = status.toFacts(),
    )

private fun BridgePathStatus.toFacts(): RemoteSyncConflictPathStatus =
    when (this) {
        BridgePathStatus.OPEN -> RemoteSyncConflictPathStatus.Open
        BridgePathStatus.RESOLVED_KEEP_LOCAL -> RemoteSyncConflictPathStatus.ResolvedKeepLocal
        BridgePathStatus.RESOLVED_KEEP_REMOTE -> RemoteSyncConflictPathStatus.ResolvedKeepRemote
        BridgePathStatus.RESOLVED_MERGED -> RemoteSyncConflictPathStatus.ResolvedMerged
        BridgePathStatus.SKIPPED_FOR_NOW -> RemoteSyncConflictPathStatus.SkippedForNow
    }

private fun RemoteSyncConflictResolution.toBridge(): BridgeResolution =
    BridgeResolution(
        path = path,
        kind = kind,
        mergedBody = mergedBody,
    )

private fun BridgeResolveResult.toFacts(): RemoteSyncConflictResolveResult =
    RemoteSyncConflictResolveResult(
        sessionId = sessionId,
        conflictRevision = conflictRevision.toLong(),
        appliedPaths = appliedPaths,
    )

private fun BridgeSecretLease.toFacts(): RemoteSyncSecretLease =
    RemoteSyncSecretLease(leaseId = leaseId)
