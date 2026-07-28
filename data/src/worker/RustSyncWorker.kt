package com.lomo.data.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.lomo.data.engine.sync.RemoteSyncBoundaryFailure
import com.lomo.data.engine.sync.RemoteSyncRetryDisposition
import com.lomo.data.engine.sync.RemoteSyncRetryHint
import com.lomo.data.engine.sync.RustSyncSecretSupplier
import timber.log.Timber

/**
 * Stage-5 dark WorkManager-shaped runner policy (P5-09).
 *
 * Maps Rust [RemoteSyncRetryDisposition] / optional `retryAfter` into WorkManager result types.
 * **No** fixed three-retry business logic (unlike legacy [errorWorkResult] default).
 *
 * Production WorkManager runner policy (post P5-13). Registered via `workerOf(::RustSyncWorker)`.
 */
object RustSyncRetryPolicy {
    /**
     * Maps a Rust-owned retry hint into a WorkManager [ListenableWorker.Result].
     *
     * - [RemoteSyncRetryDisposition.Never] → failure (do not retry)
     * - [RemoteSyncRetryDisposition.AfterUserAction] → success (stop automatic retry; UI owns next step)
     * - [RemoteSyncRetryDisposition.Transient] → retry (scheduler backoff owns delay; optional
     *   [RemoteSyncRetryHint.retryAfterMillis] is retained for enqueue-time policy only)
     */
    fun workResult(hint: RemoteSyncRetryHint): ListenableWorker.Result =
        when (hint.disposition) {
            RemoteSyncRetryDisposition.Never -> ListenableWorker.Result.failure()
            RemoteSyncRetryDisposition.AfterUserAction -> ListenableWorker.Result.success()
            RemoteSyncRetryDisposition.Transient -> ListenableWorker.Result.retry()
        }

    /**
     * Optional delay from the hint for enqueue-time / one-shot backoff composition.
     * Transient-only; Never / AfterUserAction always return null.
     */
    fun retryAfterMillis(hint: RemoteSyncRetryHint): Long? =
        when (hint.disposition) {
            RemoteSyncRetryDisposition.Transient -> hint.retryAfterMillis?.takeIf { it > 0 }
            RemoteSyncRetryDisposition.Never,
            RemoteSyncRetryDisposition.AfterUserAction,
            -> null
        }

    /**
     * Maps a structured boundary failure's disposition **name** into a hint.
     * Unknown / blank names fail closed as [RemoteSyncRetryDisposition.Never].
     */
    fun hintFromBoundaryFailure(failure: RemoteSyncBoundaryFailure): RemoteSyncRetryHint {
        val disposition =
            when (failure.retryDisposition.trim().lowercase()) {
                "never" -> RemoteSyncRetryDisposition.Never
                "after_user_action" -> RemoteSyncRetryDisposition.AfterUserAction
                "transient" -> RemoteSyncRetryDisposition.Transient
                else -> RemoteSyncRetryDisposition.Never
            }
        return RemoteSyncRetryHint(disposition = disposition)
    }
}

/**
 * Dark WorkManager runner body (unregistered).
 *
 * Orchestrates process-local secret lease issue/revoke around a [RustSyncWorkExecutor] work unit and
 * maps the resulting [RemoteSyncRetryHint] (or boundary failure disposition) into WorkManager results.
 * Host tests exercise the body with fakes. Full scheduler enqueue + Koin `workerOf` lands at P5-13.
 */
class RustSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
    private val secretSupplier: RustSyncSecretSupplier,
    private val workExecutor: RustSyncWorkExecutor,
    /**
     * Host-test stop probe. Production uses WorkManager [isStopped] only; tests inject true to
     * exercise cancel/stale without subclassing final [ListenableWorker.isStopped].
     */
    private val stopProbe: () -> Boolean = { false },
) : CoroutineWorker(appContext, workerParams) {
    private fun workIsStopped(): Boolean = isStopped || stopProbe()

    override suspend fun doWork(): Result {
        Timber.d("%s started", WORKER_NAME)
        val request = resolveWorkRequest(inputData)
        val invalid = validateInputs(request)
        if (invalid != null) {
            return invalid
        }

        var issuedLeaseId: String? = null
        return try {
            runLeasedWork(request) { leaseId -> issuedLeaseId = leaseId }
        } catch (failure: RemoteSyncBoundaryFailure) {
            Timber.e(
                "%s boundary failure category=%s code=%s disposition=%s",
                WORKER_NAME,
                failure.category,
                failure.code,
                failure.retryDisposition,
            )
            RustSyncRetryPolicy.workResult(RustSyncRetryPolicy.hintFromBoundaryFailure(failure))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Unexpected host failures are transient for scheduler backoff — never embed maxAttempts=3.
            Timber.e(error, "%s unexpected host failure", WORKER_NAME)
            RustSyncRetryPolicy.workResult(
                RemoteSyncRetryHint(disposition = RemoteSyncRetryDisposition.Transient),
            )
        } finally {
            revokeLeaseQuietly(issuedLeaseId)
        }
    }

    private suspend fun runLeasedWork(
        request: RustSyncWorkRequest,
        onLeaseIssued: (String?) -> Unit,
    ): Result {
        if (workIsStopped()) {
            return Result.success()
        }
        val leasedRequest = issueLeaseIfNeeded(request)
        if (leasedRequest == null) {
            return neverResult()
        }
        onLeaseIssued(leasedRequest.secretLeaseId)
        if (workIsStopped()) {
            return Result.success()
        }
        val hint = workExecutor.run(leasedRequest)
        return if (workIsStopped()) {
            Result.success()
        } else {
            RustSyncRetryPolicy.workResult(hint)
        }
    }

    private fun validateInputs(request: RustSyncWorkRequest): Result? {
        if (request.workspaceRoot.isBlank()) {
            Timber.e("%s missing workspace root", WORKER_NAME)
            return neverResult()
        }
        if (request.backendKind.isBlank()) {
            Timber.e("%s missing backend kind", WORKER_NAME)
            return neverResult()
        }
        return null
    }

    private fun issueLeaseIfNeeded(request: RustSyncWorkRequest): RustSyncWorkRequest? {
        val secretFieldKey = request.secretFieldKey
        if (secretFieldKey.isNullOrBlank()) {
            return request
        }
        val lease =
            secretSupplier.issueLease(
                fieldKey = secretFieldKey,
                ttlMillis = request.leaseTtlMillis,
            )
        if (lease == null) {
            // Fail closed: required credential field is unset / empty.
            Timber.e("%s missing secret lease for field", WORKER_NAME)
            return null
        }
        return request.copy(secretLeaseId = lease.leaseId)
    }

    private fun revokeLeaseQuietly(leaseId: String?) {
        if (leaseId == null) {
            return
        }
        runCatching { secretSupplier.revokeLease(leaseId) }
            // behavior-contract: silent-result-ok: revoke best-effort; process death drops leases
            .onFailure { err ->
                Timber.w(err, "%s lease revoke failed for id=%s", WORKER_NAME, leaseId)
            }
    }

    private fun neverResult(): Result =
        RustSyncRetryPolicy.workResult(
            RemoteSyncRetryHint(disposition = RemoteSyncRetryDisposition.Never),
        )

    companion object {
        private const val WORKER_NAME: String = "RustSyncWorker"
        const val WORK_NAME: String = "com.lomo.data.worker.RustSyncWorker"

        fun mapRetryHint(hint: RemoteSyncRetryHint): ListenableWorker.Result =
            RustSyncRetryPolicy.workResult(hint)

        fun inputData(
            workspaceRoot: String,
            backendKind: String,
            endpointUrl: String = "",
            usernameOrAccessKey: String = "",
            bucket: String = "",
            prefix: String = "",
            region: String = "",
            remoteDatasetId: String = "",
            secretFieldKey: String? = null,
            leaseTtlMillis: Long = RustSyncWorkRequest.DEFAULT_LEASE_TTL_MILLIS,
            applyRemote: Boolean = true,
        ): Data {
            val builder =
                Data
                    .Builder()
                    .putString(RustSyncWorkRequest.INPUT_WORKSPACE_ROOT, workspaceRoot)
                    .putString(RustSyncWorkRequest.INPUT_BACKEND_KIND, backendKind)
                    .putString(RustSyncWorkRequest.INPUT_ENDPOINT_URL, endpointUrl)
                    .putString(RustSyncWorkRequest.INPUT_USERNAME_OR_ACCESS_KEY, usernameOrAccessKey)
                    .putString(RustSyncWorkRequest.INPUT_BUCKET, bucket)
                    .putString(RustSyncWorkRequest.INPUT_PREFIX, prefix)
                    .putString(RustSyncWorkRequest.INPUT_REGION, region)
                    .putString(RustSyncWorkRequest.INPUT_REMOTE_DATASET_ID, remoteDatasetId)
                    .putLong(RustSyncWorkRequest.INPUT_LEASE_TTL_MILLIS, leaseTtlMillis)
                    .putBoolean(RustSyncWorkRequest.INPUT_APPLY_REMOTE, applyRemote)
            if (!secretFieldKey.isNullOrBlank()) {
                builder.putString(RustSyncWorkRequest.INPUT_SECRET_FIELD_KEY, secretFieldKey)
            }
            return builder.build()
        }

        fun resolveWorkRequest(inputData: Data): RustSyncWorkRequest {
            val workspaceRoot =
                inputData.getString(RustSyncWorkRequest.INPUT_WORKSPACE_ROOT).orEmpty()
            val backendKind =
                inputData.getString(RustSyncWorkRequest.INPUT_BACKEND_KIND).orEmpty()
            val endpointUrl =
                inputData.getString(RustSyncWorkRequest.INPUT_ENDPOINT_URL).orEmpty()
            val usernameOrAccessKey =
                inputData.getString(RustSyncWorkRequest.INPUT_USERNAME_OR_ACCESS_KEY).orEmpty()
            val bucket = inputData.getString(RustSyncWorkRequest.INPUT_BUCKET).orEmpty()
            val prefix = inputData.getString(RustSyncWorkRequest.INPUT_PREFIX).orEmpty()
            val region = inputData.getString(RustSyncWorkRequest.INPUT_REGION).orEmpty()
            val remoteDatasetId =
                inputData.getString(RustSyncWorkRequest.INPUT_REMOTE_DATASET_ID).orEmpty()
            val secretFieldKey =
                inputData
                    .getString(RustSyncWorkRequest.INPUT_SECRET_FIELD_KEY)
                    ?.takeIf { it.isNotBlank() }
            val leaseTtlMillis =
                inputData
                    .getLong(
                        RustSyncWorkRequest.INPUT_LEASE_TTL_MILLIS,
                        RustSyncWorkRequest.DEFAULT_LEASE_TTL_MILLIS,
                    ).takeIf { it > 0 } ?: RustSyncWorkRequest.DEFAULT_LEASE_TTL_MILLIS
            val applyRemote =
                inputData.getBoolean(RustSyncWorkRequest.INPUT_APPLY_REMOTE, true)
            return RustSyncWorkRequest(
                workspaceRoot = workspaceRoot,
                backendKind = backendKind,
                endpointUrl = endpointUrl,
                usernameOrAccessKey = usernameOrAccessKey,
                bucket = bucket,
                prefix = prefix,
                region = region,
                remoteDatasetId = remoteDatasetId,
                secretFieldKey = secretFieldKey,
                leaseTtlMillis = leaseTtlMillis,
                applyRemote = applyRemote,
            )
        }
    }
}
