package com.lomo.data.engine.sync

import com.lomo.nativebridge.EngineError
import com.lomo.nativebridge.SyncConflictPageDto as BridgeConflictPage
import com.lomo.nativebridge.SyncConflictPathDto as BridgeConflictPath
import com.lomo.nativebridge.SyncConflictPathStatusDto as BridgePathStatus
import com.lomo.nativebridge.SyncConflictResolutionDto as BridgeResolution
import com.lomo.nativebridge.SyncConflictResolveResultDto as BridgeResolveResult
import com.lomo.nativebridge.SyncCyclePlanSummaryDto as BridgeCyclePlan
import com.lomo.nativebridge.SyncRetryDispositionDto as BridgeRetryDisposition
import com.lomo.nativebridge.SyncRetryHintDto as BridgeRetryHint
import com.lomo.nativebridge.SyncSecretLeaseDto as BridgeSecretLease

/**
 * Production [RemoteSyncRepository] over [SyncNativeBridge] (BoltFFI free-function conversion).
 *
 * Mapping only — conflict revision fences, path budgets, composed owner cycle, and secret vault rules
 * stay in Rust. Production-wired at P5-13.
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

    override fun retryHintFromDispositionName(name: String): RemoteSyncRetryHint {
        require(name.isNotBlank()) { "retry disposition name must be non-blank" }
        return mapBoundary {
            bridge.retryDispositionFromName(name).toFacts()
        }
    }

    override fun inspectCyclePlan(workspaceRoot: String): RemoteSyncCyclePlanSummary {
        require(workspaceRoot.isNotBlank()) { "workspace root must be non-blank" }
        return mapBoundary {
            bridge.inspectCyclePlan(workspaceRoot = workspaceRoot).toFacts()
        }
    }

    override fun runCycle(request: RemoteSyncCycleRequest): RemoteSyncCyclePlanSummary {
        require(request.workspaceRoot.isNotBlank()) { "workspace root must be non-blank" }
        require(request.backendKind.isNotBlank()) { "backend kind must be non-blank" }
        return mapBoundary {
            bridge
                .runCycle(
                    workspaceRoot = request.workspaceRoot.trim(),
                    backendKind = request.backendKind.trim(),
                    endpointUrl = request.endpointUrl,
                    usernameOrAccessKey = request.usernameOrAccessKey,
                    bucket = request.bucket,
                    prefix = request.prefix,
                    region = request.region,
                    remoteDatasetId = request.remoteDatasetId,
                    secretLeaseId = request.secretLeaseId.orEmpty(),
                    applyRemote = request.applyRemote,
                ).toFacts()
        }
    }
}

/**
 * Production free-function bridge (post P5-13).
 *
 * Host tests prefer injectable fakes; this type is registered in [com.lomo.data.di.SyncDataModule].
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

    override fun retryDispositionFromName(name: String): BridgeRetryHint =
        com.lomo.nativebridge.syncRetryDispositionFromName(name)

    override fun readConflictArtifact(
        workspaceRoot: String,
        artifactRef: String,
    ): ByteArray = com.lomo.nativebridge.syncReadConflictArtifact(workspaceRoot, artifactRef)

    override fun inspectCyclePlan(workspaceRoot: String): BridgeCyclePlan =
        com.lomo.nativebridge.syncInspectCyclePlan(workspaceRoot)

    override fun runCycle(
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
    ): BridgeCyclePlan =
        com.lomo.nativebridge.syncRunCycle(
            workspaceRoot,
            backendKind,
            endpointUrl,
            usernameOrAccessKey,
            bucket,
            prefix,
            region,
            remoteDatasetId,
            secretLeaseId,
            applyRemote,
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
        sessionId = sessionId,
        conflictRevision = conflictRevision.toLong(),
        items = items.map { it.toFacts() },
        nextCursor = nextCursor?.toInt(),
    )

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

private fun BridgeRetryHint.toFacts(): RemoteSyncRetryHint =
    RemoteSyncRetryHint(
        disposition = disposition.toFacts(),
        retryAfterMillis = retryAfterMillis?.toLong(),
    )

private fun BridgeRetryDisposition.toFacts(): RemoteSyncRetryDisposition =
    when (this) {
        BridgeRetryDisposition.NEVER -> RemoteSyncRetryDisposition.Never
        BridgeRetryDisposition.AFTER_USER_ACTION -> RemoteSyncRetryDisposition.AfterUserAction
        BridgeRetryDisposition.TRANSIENT -> RemoteSyncRetryDisposition.Transient
    }

private fun BridgeCyclePlan.toFacts(): RemoteSyncCyclePlanSummary =
    RemoteSyncCyclePlanSummary(
        sessionId = sessionId,
        sessionKind = sessionKind,
        sessionRevision = sessionRevision.toLong(),
        baselineEstablished = baselineEstablished,
        ensurePresentCount = ensurePresentCount.toInt(),
        ensureAbsentCount = ensureAbsentCount.toInt(),
        pullPresentCount = pullPresentCount.toInt(),
        openConflictCount = openConflictCount.toInt(),
        openConflictPaths = openConflictPaths.toInt(),
        conflictRevision = conflictRevision?.toLong(),
        retryDisposition = retryDisposition,
    )
