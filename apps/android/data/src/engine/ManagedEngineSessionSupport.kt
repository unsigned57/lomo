package com.lomo.data.engine

import com.lomo.domain.model.EngineReadiness

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

/**
 * Soft workspace activation failure: candidate opened but never reached Ready.
 * Carries structured recovery so cold-restore can freeze authority without promoting the candidate.
 */
class WorkspaceActivationException(
    val recovery: EngineReadiness.ReadOnlyRecovery,
) : IllegalStateException(
    "Workspace activation did not reach Ready (${recovery.code}): ${recovery.diagnostic}",
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
        token = token,
        dueAtLocal = dueAtLocal,
        repeatCount = repeatCount,
        firedCount = firedCount,
        done = done,
        intervalMinutes = intervalMinutes,
        recurrenceCode = recurrenceCode,
    )
