package com.lomo.data.worker

import com.lomo.data.engine.sync.RemoteSyncBoundaryFailure
import com.lomo.data.engine.sync.RemoteSyncCycleRequest
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

    private fun executeCycle(request: RustSyncWorkRequest): RemoteSyncRetryHint =
        try {
            val summary =
                remoteSync.runCycle(
                    RemoteSyncCycleRequest(
                        workspaceRoot = request.workspaceRoot.trim(),
                        backendKind = request.backendKind.trim(),
                        endpointUrl = request.endpointUrl,
                        usernameOrAccessKey = request.usernameOrAccessKey,
                        bucket = request.bucket,
                        prefix = request.prefix,
                        region = request.region,
                        remoteDatasetId = request.remoteDatasetId,
                        secretLeaseId = request.secretLeaseId?.trim()?.takeIf { it.isNotEmpty() },
                        applyRemote = request.applyRemote,
                    ),
                )
            hintFromDispositionName(summary.retryDisposition)
        } catch (failure: RemoteSyncBoundaryFailure) {
            Timber.e(
                "%s runCycle boundary category=%s code=%s disposition=%s",
                WORKER_UNIT,
                failure.category,
                failure.code,
                failure.retryDisposition,
            )
            hintFromBoundaryFailure(failure)
        }

    /**
     * Maps a structured boundary failure's disposition **name** into a hint.
     * Unknown / blank names fail closed as [RemoteSyncRetryDisposition.Never].
     *
     * Same policy as [RustSyncRetryPolicy.hintFromBoundaryFailure] so worker body and work unit
     * agree without inventing a second retry budget.
     */
    private fun hintFromBoundaryFailure(failure: RemoteSyncBoundaryFailure): RemoteSyncRetryHint =
        hintFromDispositionName(failure.retryDisposition)

    private fun hintFromDispositionName(name: String): RemoteSyncRetryHint {
        val disposition =
            when (name.trim().lowercase()) {
                "never" -> RemoteSyncRetryDisposition.Never
                "after_user_action" -> RemoteSyncRetryDisposition.AfterUserAction
                "transient" -> RemoteSyncRetryDisposition.Transient
                else -> RemoteSyncRetryDisposition.Never
            }
        return RemoteSyncRetryHint(disposition = disposition)
    }

    private fun neverHint(): RemoteSyncRetryHint =
        RemoteSyncRetryHint(disposition = RemoteSyncRetryDisposition.Never)

    companion object {
        private const val WORKER_UNIT: String = "RemoteSyncRustWorkExecutor"
    }
}
