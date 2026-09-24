package com.lomo.data.engine.sync

import com.lomo.domain.model.GitSyncErrorCode
import com.lomo.domain.model.RemoteSyncSessionPhase
import com.lomo.domain.model.RemoteSyncSessionProgress
import com.lomo.domain.model.S3SyncErrorCode
import com.lomo.domain.model.S3SyncState
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.UnifiedSyncError
import com.lomo.domain.model.UnifiedSyncPhase
import com.lomo.domain.model.UnifiedSyncState
import com.lomo.domain.model.WebDavSyncErrorCode
import com.lomo.domain.model.WebDavSyncState

/**
 * Durable cycle record → provider sync state mapping (post P5-13).
 *
 * The Rust `cycle_state.rec` record is the sole authority — Kotlin never invents bodies,
 * timestamps or completion. `null`/`hasRecord=false` is the real "never synced" state (Idle).
 * A `cancelled` terminal is surfaced as a non-success Error with the `sync_cycle_cancelled`
 * code — the sealed provider states have no dedicated Cancelled variant.
 */

internal fun RemoteSyncCycleStatus?.toUnifiedSyncState(
    provider: SyncBackendType,
): UnifiedSyncState =
    when {
        this == null || !hasRecord -> UnifiedSyncState.Idle
        phase == RemoteSyncCycleStatus.PHASE_RUNNING ->
            UnifiedSyncState.Running(
                provider = provider,
                phase =
                    if (stage == RemoteSyncCycleStatus.STAGE_APPLYING) {
                        UnifiedSyncPhase.COMMITTING
                    } else {
                        UnifiedSyncPhase.LISTING
                    },
            )
        phase == RemoteSyncCycleStatus.PHASE_COMPLETED ->
            UnifiedSyncState.Success(
                provider = provider,
                timestamp = finishedAtMs ?: updatedAtMs,
                summary = outcomeSummary(),
            )
        else ->
            UnifiedSyncState.Error(
                error =
                    UnifiedSyncError(
                        provider = provider,
                        message = failureMessage ?: terminalMessage(),
                        providerCode = failureCode ?: terminalCode(),
                    ),
                timestamp = finishedAtMs ?: updatedAtMs,
            )
    }

internal fun RemoteSyncCycleStatus?.toWebDavSyncState(): WebDavSyncState =
    when {
        this == null || !hasRecord -> WebDavSyncState.Idle
        phase == RemoteSyncCycleStatus.PHASE_RUNNING ->
            if (stage == RemoteSyncCycleStatus.STAGE_APPLYING) {
                WebDavSyncState.Uploading
            } else {
                WebDavSyncState.Listing
            }
        phase == RemoteSyncCycleStatus.PHASE_COMPLETED ->
            WebDavSyncState.Success(
                timestamp = finishedAtMs ?: updatedAtMs,
                summary = outcomeSummary(),
            )
        else ->
            WebDavSyncState.Error(
                code = WebDavSyncErrorCode.UNKNOWN,
                message = failureMessage ?: terminalMessage(),
                timestamp = finishedAtMs ?: updatedAtMs,
            )
    }

internal fun RemoteSyncCycleStatus?.toS3SyncState(): S3SyncState =
    when {
        this == null || !hasRecord -> S3SyncState.Idle
        phase == RemoteSyncCycleStatus.PHASE_RUNNING ->
            if (stage == RemoteSyncCycleStatus.STAGE_APPLYING) {
                S3SyncState.Uploading
            } else {
                S3SyncState.Listing
            }
        phase == RemoteSyncCycleStatus.PHASE_COMPLETED ->
            S3SyncState.Success(
                timestamp = finishedAtMs ?: updatedAtMs,
                summary = outcomeSummary(),
            )
        else ->
            S3SyncState.Error(
                code = S3SyncErrorCode.UNKNOWN,
                message = failureMessage ?: terminalMessage(),
                timestamp = finishedAtMs ?: updatedAtMs,
            )
    }

/**
 * Durable cycle record → Sync Center session progress.
 *
 * `Cancelling` marks a `running` record whose durable cancel request is already persisted;
 * a terminal `cancelled` projects as [RemoteSyncSessionPhase.Cancelled]. `completed` with
 * open conflicts surfaces as `ConflictOpen` so the UI routes to the conflict pane.
 */
internal fun RemoteSyncCycleStatus?.toSessionProgress(): RemoteSyncSessionProgress =
    when {
        this == null || !hasRecord ->
            RemoteSyncSessionProgress(
                phase = RemoteSyncSessionPhase.Idle,
                completedActions = 0,
                totalActions = null,
                canCancel = false,
            )
        phase == RemoteSyncCycleStatus.PHASE_RUNNING ->
            RemoteSyncSessionProgress(
                phase =
                    if (cancelRequested) {
                        RemoteSyncSessionPhase.Cancelling
                    } else if (stage == RemoteSyncCycleStatus.STAGE_APPLYING) {
                        RemoteSyncSessionPhase.Apply
                    } else {
                        RemoteSyncSessionPhase.Plan
                    },
                completedActions = pagesApplied,
                totalActions = null,
                canCancel = !cancelRequested,
            )
        phase == RemoteSyncCycleStatus.PHASE_COMPLETED ->
            RemoteSyncSessionProgress(
                phase =
                    if (openConflictCount > 0) {
                        RemoteSyncSessionPhase.ConflictOpen
                    } else {
                        RemoteSyncSessionPhase.Completed
                    },
                completedActions = pagesApplied,
                totalActions = null,
                canCancel = false,
            )
        phase == RemoteSyncCycleStatus.PHASE_CANCELLED ->
            RemoteSyncSessionProgress(
                phase = RemoteSyncSessionPhase.Cancelled,
                completedActions = pagesApplied,
                totalActions = null,
                canCancel = false,
            )
        else ->
            RemoteSyncSessionProgress(
                phase = RemoteSyncSessionPhase.Failed,
                completedActions = pagesApplied,
                totalActions = null,
                canCancel = false,
            )
    }

/** Human-readable cycle summary built from real plan/apply counts (no invented text). */
private fun RemoteSyncCycleStatus.outcomeSummary(): String =
    "ensure=${ensurePresentCount + ensureAbsentCount} pull=$pullPresentCount " +
        "conflicts=$openConflictCount pages=$pagesApplied"

private fun RemoteSyncCycleStatus.terminalMessage(): String =
    if (phase == RemoteSyncCycleStatus.PHASE_CANCELLED) {
        "sync cycle $cycleId cancelled"
    } else {
        "sync cycle $cycleId $stage"
    }

private fun RemoteSyncCycleStatus.terminalCode(): String =
    if (phase == RemoteSyncCycleStatus.PHASE_CANCELLED) {
        "sync_cycle_cancelled"
    } else {
        GitSyncErrorCode.UNKNOWN.name
    }
