package com.lomo.data.engine

import com.lomo.domain.model.EngineReadiness
import com.lomo.domain.model.WorkspaceFailureStage

/** Releases a candidate that will never be published, reporting its close failure on [primary]. */
internal fun releaseCandidate(
    candidate: RustEngineAdapter,
    candidateToken: String?,
    primary: Throwable,
    capabilityRegistry: CapabilityRegistry,
) {
    runCatching(candidate::close).exceptionOrNull()?.let(primary::addSuppressed)
    // Capability revoke is never skipped by a failing candidate close.
    candidateToken?.let(capabilityRegistry::revoke)
}

/** Releases an unpublished candidate before propagating its terminal activation failure. */
internal fun rejectWorkspaceCandidate(
    candidate: ManagedWorkspaceCandidate,
    failure: Throwable,
    capabilityRegistry: CapabilityRegistry,
): Nothing {
    releaseCandidate(candidate.adapter, candidate.capabilityToken, failure, capabilityRegistry)
    throw failure
}

internal fun EngineReadiness.ReadOnlyRecovery.toActivationFailure() = engineCommandFailure(
    category = category,
    code = code,
    retryDisposition = retryDisposition,
    diagnostic = diagnostic,
    stage = workspaceStage ?: WorkspaceFailureStage.OPEN,
)

internal fun SafMemoProjectionSnapshot.toBridge(): com.lomo.nativebridge.StoreSafMemoProjection =
    com.lomo.nativebridge.StoreSafMemoProjection(
        memoId = memoId,
        sourcePath = sourcePath,
        fileFingerprint = fileFingerprint,
        chronologyEpochMs = chronologyEpochMs,
        body = body,
        tags = tags,
        attachmentPaths = attachmentPaths,
        hasTodo = hasTodo,
        hasUrl = hasUrl,
        reminders = reminders.map(WorkspaceReminderReferenceSnapshot::toBridge),
        trashedAtMs = trashedAtMs,
    )

private fun WorkspaceReminderReferenceSnapshot.toBridge(): com.lomo.nativebridge.WorkspaceReminderReference =
    com.lomo.nativebridge.WorkspaceReminderReference(
        opaqueId = opaqueId,
        revision = revision,
        memoIdentity = memoIdentity,
        sourceStart = sourceStart,
        sourceEnd = sourceEnd,
        tokenFingerprint = tokenFingerprint,
        fingerprintOrdinal = fingerprintOrdinal,
        embeddedId = embeddedId,
        token = token,
        dueAtLocal = dueAtLocal,
        repeatCount = repeatCount,
        firedCount = firedCount,
        done = done,
        intervalMinutes = intervalMinutes,
        recurrence = recurrence,
    )
