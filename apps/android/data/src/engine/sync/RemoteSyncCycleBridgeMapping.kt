package com.lomo.data.engine.sync

import com.lomo.nativebridge.SyncBackendConfigDto as BridgeBackendConfig
import com.lomo.nativebridge.SyncBackendProbeDto as BridgeBackendProbe
import com.lomo.nativebridge.SyncCyclePlanSummaryDto as BridgeCyclePlan
import com.lomo.nativebridge.SyncCycleStatusDto as BridgeCycleStatus

/**
 * Durable cycle / probe / plan wire DTOs → host fact mapping (conversion only — Kotlin never
 * re-plans or fabricates counts).
 */

internal fun RemoteSyncCycleRequest.toBridgeConfig(): BridgeBackendConfig =
    BridgeBackendConfig(
        backendKind = backendKind.trim(),
        endpointUrl = endpointUrl,
        identity = identity,
        s3Bucket = s3Bucket,
        s3Prefix = s3Prefix,
        s3Region = s3Region,
        gitBranch = gitBranch,
        gitAuthorName = gitAuthorName,
        gitAuthorEmail = gitAuthorEmail,
        remoteDatasetId = remoteDatasetId,
    )

internal fun BridgeCycleStatus.toFacts(): RemoteSyncCycleStatus =
    RemoteSyncCycleStatus(
        hasRecord = hasRecord,
        cycleSeq = cycleSeq.toLong(),
        cycleId = cycleId,
        fenceKey = fenceKey,
        backendKind = backendKind,
        sessionId = sessionId,
        applyRemote = applyRemote,
        phase = phase,
        stage = stage,
        ensurePresentCount = ensurePresentCount.toInt(),
        ensureAbsentCount = ensureAbsentCount.toInt(),
        pullPresentCount = pullPresentCount.toInt(),
        openConflictCount = openConflictCount.toInt(),
        holdCount = holdCount.toInt(),
        localEntryCount = localEntryCount.toInt(),
        remoteListedCount = remoteListedCount.toInt(),
        baselineEntryCount = baselineEntryCount.toInt(),
        pagesApplied = pagesApplied.toInt(),
        baselineAdvanced = baselineAdvanced,
        retryDisposition = retryDisposition,
        failureCode = failureCode,
        failureMessage = failureMessage,
        cancelRequested = cancelRequested,
        startedAtMs = startedAtMs,
        updatedAtMs = updatedAtMs,
        finishedAtMs = finishedAtMs,
        lastSuccessfulAtMs = lastSuccessfulAtMs,
        stateStamp = stateStamp.toLong(),
    )

internal fun BridgeBackendProbe.toFacts(): RemoteSyncBackendProbe =
    RemoteSyncBackendProbe(
        backendKind = backendKind,
        listedEntryCount = listedEntryCount.toInt(),
        snapshotRevisionPresent = snapshotRevisionPresent,
        conditionalWrite = conditionalWrite,
        conditionalDelete = conditionalDelete,
        probedAtMs = probedAtMs,
    )

internal fun BridgeCyclePlan.toFacts(): RemoteSyncCyclePlanSummary =
    RemoteSyncCyclePlanSummary(
        sessionId = sessionId,
        sessionKind = sessionKind,
        sessionRevision = sessionRevision.toLong(),
        baselineEstablished = baselineEstablished,
        ensurePresentCount = ensurePresentCount.toInt(),
        ensureAbsentCount = ensureAbsentCount.toInt(),
        pullPresentCount = pullPresentCount.toInt(),
        openConflictCount = openConflictCount.toInt(),
        holdCount = holdCount.toInt(),
        openConflictPaths = openConflictPaths.toInt(),
        conflictRevision = conflictRevision?.toLong(),
        retryDisposition = retryDisposition,
    )
