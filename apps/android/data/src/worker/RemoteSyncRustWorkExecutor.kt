package com.lomo.data.worker

import com.lomo.data.engine.sync.RemoteSyncBoundaryFailure
import com.lomo.data.engine.sync.RemoteSyncCycleRequest
import com.lomo.data.engine.sync.RemoteSyncCycleStatus
import com.lomo.data.engine.sync.RemoteSyncRepository
import com.lomo.data.engine.sync.RemoteSyncRetryDisposition
import com.lomo.data.engine.sync.RemoteSyncRetryHint
import timber.log.Timber

/**
 * Production [RustSyncWorkExecutor] over [RemoteSyncRepository] (P5-13 hollow-cycle close).
 *
 * Work unit (sole production surface — not empty-port inspect):
 * 1. Fail closed on blank workspace / blank backend kind / blank required lease id.
 * 2. When [RustSyncWorkRequest.secretLeaseId] is present, probe the opaque lease (never plaintext).
 * 3. Call the Rust-owned composed cycle surface (`runCycle` → `sync_run_cycle`) with non-secret
 *    backend config + lease id. Full plan/apply/publish remains in Rust.
 * 4. Read the durable cycle record (`cycleStatus`) after execution — the record is the
 *    authoritative outcome; a `cancelled` terminal overrides the in-flight return/failure.
 *
 * Disposition mapping has **no** fixed three-retry budget.
 */
class RemoteSyncRustWorkExecutor(
    private val remoteSync: RemoteSyncRepository,
) : RustSyncWorkExecutor {
    override suspend fun run(request: RustSyncWorkRequest): RemoteSyncRetryHint {
        validateRequest(request)?.let { return it }

        val leaseId = request.secretLeaseId
        if (leaseId != null) {
            probeLease(leaseId)?.let { return it }
        }

        return executeCycle(request)
    }

    private fun validateRequest(request: RustSyncWorkRequest): RemoteSyncRetryHint? {
        val workspaceRoot = request.workspaceRoot.trim()
        if (workspaceRoot.isEmpty()) {
            Timber.e("%s blank workspace root", WORKER_UNIT)
            return neverHint()
        }
        val backendKind = request.backendKind.trim()
        if (backendKind.isEmpty()) {
            Timber.e("%s blank backend kind", WORKER_UNIT)
            return neverHint()
        }
        return null
    }

    private fun probeLease(leaseId: String): RemoteSyncRetryHint? {
        val trimmedLease = leaseId.trim()
        if (trimmedLease.isEmpty()) {
            Timber.e("%s blank secret lease id", WORKER_UNIT)
            return neverHint()
        }
        return try {
            // Presence check only — probe returns length, never secret bytes.
            remoteSync.probeSecretLease(trimmedLease)
            null
        } catch (failure: RemoteSyncBoundaryFailure) {
            Timber.e(
                "%s lease probe failed category=%s code=%s disposition=%s",
                WORKER_UNIT,
                failure.category,
                failure.code,
                failure.retryDisposition,
            )
            hintFromBoundaryFailure(failure)
        }
    }

    private fun executeCycle(request: RustSyncWorkRequest): RemoteSyncRetryHint {
        val summary =
            try {
                remoteSync.runCycle(
                    RemoteSyncCycleRequest(
                        workspaceRoot = request.workspaceRoot.trim(),
                        backendKind = request.backendKind.trim(),
                        endpointUrl = request.endpointUrl,
                        identity = request.identity,
                        s3Bucket = request.s3Bucket,
                        s3Prefix = request.s3Prefix,
                        s3Region = request.s3Region,
                        gitBranch = request.gitBranch,
                        gitAuthorName = request.gitAuthorName,
                        gitAuthorEmail = request.gitAuthorEmail,
                        remoteDatasetId = request.remoteDatasetId,
                        secretLeaseId =
                            request.secretLeaseId?.run { trim().takeIf { it.isNotEmpty() } },
                        applyRemote = request.applyRemote,
                    ),
                )
            } catch (failure: RemoteSyncBoundaryFailure) {
                Timber.e(
                    "%s runCycle boundary category=%s code=%s disposition=%s",
                    WORKER_UNIT,
                    failure.category,
                    failure.code,
                    failure.retryDisposition,
                )
                // The durable record is authoritative: a cancelled terminal outranks the
                // in-flight failure surface (e.g. cancel observed between apply pages).
                return when (durableTerminal(request)?.phase) {
                    RemoteSyncCycleStatus.PHASE_CANCELLED -> neverHint()
                    else -> hintFromBoundaryFailure(failure)
                }
            }
        // Post-execution durable read: the terminal record owns the real outcome. Prefer its
        // disposition; fall back to the cycle summary only when the record is unreadable.
        val terminal = durableTerminal(request)
        val disposition =
            terminal?.run { retryDisposition.takeIf(String::isNotBlank) }
                ?: summary.retryDisposition
        return hintFromDisposition(RemoteSyncRetryDisposition.fromWire(disposition))
    }

    /** Terminal durable record read; `null` when unreadable or still running. */
    private fun durableTerminal(request: RustSyncWorkRequest): RemoteSyncCycleStatus? =
        try {
            remoteSync
                .cycleStatus(request.workspaceRoot.trim())
                .takeIf { it.hasRecord && it.phase != RemoteSyncCycleStatus.PHASE_RUNNING }
        } catch (failure: RemoteSyncBoundaryFailure) {
            Timber.w(
                "%s post-cycle status read failed category=%s code=%s",
                WORKER_UNIT,
                failure.category,
                failure.code,
            )
            // behavior-contract: silent-result-ok: the cycle's terminal record is already
            // durable in Rust; an unreadable projection degrades to the summary disposition.
            null
        }

    /**
     * Maps a structured boundary failure's disposition **name** into a hint.
     * Unknown / blank names fail closed as [RemoteSyncRetryDisposition.Never].
     *
     * Same policy as [RemoteSyncRetryDisposition.fromWire] so worker body and work unit
     * agree without inventing a second retry budget.
     */
    private fun hintFromBoundaryFailure(failure: RemoteSyncBoundaryFailure): RemoteSyncRetryHint =
        hintFromDisposition(RemoteSyncRetryDisposition.fromWire(failure.retryDisposition))

    private fun hintFromDisposition(disposition: RemoteSyncRetryDisposition): RemoteSyncRetryHint =
        RemoteSyncRetryHint(disposition = disposition)

    private fun neverHint(): RemoteSyncRetryHint =
        RemoteSyncRetryHint(disposition = RemoteSyncRetryDisposition.Never)

    companion object {
        private const val WORKER_UNIT: String = "RemoteSyncRustWorkExecutor"
    }
}
