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
import com.lomo.domain.model.CredentialReadAuthorization
import com.lomo.domain.model.EngineReadiness
import com.lomo.domain.repository.EngineReadinessRepository
import com.lomo.domain.repository.SecuritySessionPolicy
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
     * - [RemoteSyncRetryDisposition.Transient] → retry while [runAttemptCount] is below [maxAttempts];
     *   at/over the ceiling → failure (terminal). Missing/non-positive ceiling fails closed as
     *   failure rather than unbounded retry.
     */
    fun workResult(
        hint: RemoteSyncRetryHint,
        runAttemptCount: Int = 0,
        maxAttempts: Int? = null,
    ): ListenableWorker.Result =
        when (hint.disposition) {
            RemoteSyncRetryDisposition.Never -> ListenableWorker.Result.failure()
            RemoteSyncRetryDisposition.AfterUserAction -> ListenableWorker.Result.success()
            RemoteSyncRetryDisposition.Transient ->
                if (maxAttempts != null && maxAttempts > 0 && runAttemptCount < maxAttempts) {
                    ListenableWorker.Result.retry()
                } else {
                    ListenableWorker.Result.failure()
                }
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
    fun hintFromBoundaryFailure(failure: RemoteSyncBoundaryFailure): RemoteSyncRetryHint =
        RemoteSyncRetryHint(disposition = RemoteSyncRetryDisposition.fromWire(failure.retryDisposition))
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
    private val securitySessionPolicy: SecuritySessionPolicy,
    private val engineReadiness: EngineReadinessRepository,
    private val deferredLockStore: DeferredLockWorkStore,
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
        when (val authorization = securitySessionPolicy.authorizeCredentialRead()) {
            is CredentialReadAuthorization.Denied -> {
                Timber.i(
                    "%s deferred for security session reason=%s",
                    WORKER_NAME,
                    authorization.reason,
                )
                deferredLockStore.save(inputData)
                return Result.success()
            }
            CredentialReadAuthorization.Authorized -> Unit
        }

        val settled = engineReadiness.requestEngineStart()
        if (settled !is EngineReadiness.Ready) {
            Timber.i("%s deferred: engine settled at %s", WORKER_NAME, settled::class.simpleName)
            return resultFor(RemoteSyncRetryHint(disposition = RemoteSyncRetryDisposition.Transient))
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
            resultFor(RustSyncRetryPolicy.hintFromBoundaryFailure(failure))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            Timber.e(error, "%s unexpected host failure", WORKER_NAME)
            resultFor(RemoteSyncRetryHint(disposition = RemoteSyncRetryDisposition.Transient))
        } finally {
            issuedLeaseId?.let(::revokeLeaseQuietly)
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
            resultFor(hint)
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
        var next = request
        val identityFieldKey = request.identityFieldKey
        if (!identityFieldKey.isNullOrBlank()) {
            val identity = secretSupplier.identityUtf8(identityFieldKey)
            if (identity.isNullOrEmpty()) {
                Timber.e("%s missing identity material for field", WORKER_NAME)
                return null
            }
            next = next.copy(identity = identity)
        }
        val secretFieldKey = next.secretFieldKey
        if (secretFieldKey.isNullOrBlank()) {
            return next
        }
        val lease =
            secretSupplier.issueLease(
                fieldKey = secretFieldKey,
                ttlMillis = next.leaseTtlMillis,
            )
        if (lease == null) {
            Timber.e("%s missing secret lease for field", WORKER_NAME)
            return null
        }
        return next.copy(secretLeaseId = lease.leaseId)
    }

    private fun revokeLeaseQuietly(leaseId: String) {
        runCatching { secretSupplier.revokeLease(leaseId) }
            // behavior-contract: silent-result-ok: revoke best-effort; process death drops leases
            .onFailure { err ->
                Timber.w(err, "%s lease revoke failed for id=%s", WORKER_NAME, leaseId)
            }
    }

    private fun neverResult(): Result =
        resultFor(RemoteSyncRetryHint(disposition = RemoteSyncRetryDisposition.Never))

    private fun resultFor(hint: RemoteSyncRetryHint): Result {
        val maxAttempts =
            inputData.getInt(SYNC_WORK_MAX_RETRY_ATTEMPTS_INPUT_KEY, 0).takeIf { it > 0 }
        return RustSyncRetryPolicy.workResult(
            hint = hint,
            runAttemptCount = runAttemptCount,
            maxAttempts = maxAttempts,
        )
    }

    companion object {
        private const val WORKER_NAME: String = "RustSyncWorker"
        const val WORK_NAME: String = "com.lomo.data.worker.RustSyncWorker"
        const val ONESHOT_WORK_NAME: String = "$WORK_NAME:oneshot"
        const val DEFERRED_WORK_NAME: String = "$WORK_NAME:deferred"

        fun cancelTargets(): List<String> = listOf(WORK_NAME, ONESHOT_WORK_NAME, DEFERRED_WORK_NAME)

        fun mapRetryHint(
            hint: RemoteSyncRetryHint,
            runAttemptCount: Int = 0,
            maxAttempts: Int = com.lomo.data.sync.REMOTE_AUTO_SYNC_RETRY_POLICY.maxAttempts,
        ): ListenableWorker.Result =
            RustSyncRetryPolicy.workResult(
                hint = hint,
                runAttemptCount = runAttemptCount,
                maxAttempts = maxAttempts,
            )

        fun inputData(
            workspaceRoot: String,
            backendKind: String,
            endpointUrl: String = "",
            s3Bucket: String = "",
            s3Prefix: String = "",
            s3Region: String = "",
            gitBranch: String = "",
            gitAuthorName: String = "",
            gitAuthorEmail: String = "",
            remoteDatasetId: String = "",
            identityFieldKey: String? = null,
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
                    .putString(RustSyncWorkRequest.INPUT_S3_BUCKET, s3Bucket)
                    .putString(RustSyncWorkRequest.INPUT_S3_PREFIX, s3Prefix)
                    .putString(RustSyncWorkRequest.INPUT_S3_REGION, s3Region)
                    .putString(RustSyncWorkRequest.INPUT_GIT_BRANCH, gitBranch)
                    .putString(RustSyncWorkRequest.INPUT_GIT_AUTHOR_NAME, gitAuthorName)
                    .putString(RustSyncWorkRequest.INPUT_GIT_AUTHOR_EMAIL, gitAuthorEmail)
                    .putString(RustSyncWorkRequest.INPUT_REMOTE_DATASET_ID, remoteDatasetId)
                    .putLong(RustSyncWorkRequest.INPUT_LEASE_TTL_MILLIS, leaseTtlMillis)
                    .putBoolean(RustSyncWorkRequest.INPUT_APPLY_REMOTE, applyRemote)
                    .putInt(
                        SYNC_WORK_MAX_RETRY_ATTEMPTS_INPUT_KEY,
                        com.lomo.data.sync.REMOTE_AUTO_SYNC_RETRY_POLICY.maxAttempts,
                    )
            if (!identityFieldKey.isNullOrBlank()) {
                builder.putString(RustSyncWorkRequest.INPUT_IDENTITY_FIELD_KEY, identityFieldKey)
            }
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
            val s3Bucket = inputData.getString(RustSyncWorkRequest.INPUT_S3_BUCKET).orEmpty()
            val s3Prefix = inputData.getString(RustSyncWorkRequest.INPUT_S3_PREFIX).orEmpty()
            val s3Region = inputData.getString(RustSyncWorkRequest.INPUT_S3_REGION).orEmpty()
            val gitBranch = inputData.getString(RustSyncWorkRequest.INPUT_GIT_BRANCH).orEmpty()
            val gitAuthorName =
                inputData.getString(RustSyncWorkRequest.INPUT_GIT_AUTHOR_NAME).orEmpty()
            val gitAuthorEmail =
                inputData.getString(RustSyncWorkRequest.INPUT_GIT_AUTHOR_EMAIL).orEmpty()
            val remoteDatasetId =
                inputData.getString(RustSyncWorkRequest.INPUT_REMOTE_DATASET_ID).orEmpty()
            val identityFieldKey =
                inputData
                    .getString(RustSyncWorkRequest.INPUT_IDENTITY_FIELD_KEY)
                    ?.takeIf { it.isNotBlank() }
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
                s3Bucket = s3Bucket,
                s3Prefix = s3Prefix,
                s3Region = s3Region,
                gitBranch = gitBranch,
                gitAuthorName = gitAuthorName,
                gitAuthorEmail = gitAuthorEmail,
                remoteDatasetId = remoteDatasetId,
                identityFieldKey = identityFieldKey,
                secretFieldKey = secretFieldKey,
                leaseTtlMillis = leaseTtlMillis,
                applyRemote = applyRemote,
            )
        }
    }
}
