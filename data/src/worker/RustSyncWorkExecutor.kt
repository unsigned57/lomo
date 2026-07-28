package com.lomo.data.worker

import com.lomo.data.engine.sync.RemoteSyncRetryDisposition
import com.lomo.data.engine.sync.RemoteSyncRetryHint

/**
 * Production Stage-5 work unit port (post P5-13).
 *
 * Production impl: [RemoteSyncRustWorkExecutor] over [com.lomo.data.engine.sync.RemoteSyncRepository]
 * (opaque lease probe + Rust-owned `runCycle` composed owner cycle). Full plan/apply stays in Rust.
 * The [RustSyncWorker] body owns lease issue/revoke lifecycle + disposition→WM mapping around this
 * unit.
 */
fun interface RustSyncWorkExecutor {
    /**
     * Runs one remote-sync work unit under an optional process-local secret lease id.
     *
     * Implementations must not journal plaintext secrets. Failures that are still disposition-bearing
     * should return a [RemoteSyncRetryHint] rather than inventing fixed three-retry budgets.
     */
    suspend fun run(request: RustSyncWorkRequest): RemoteSyncRetryHint
}

/**
 * WorkManager input facts for a production [RustSyncWorker] run.
 *
 * [secretFieldKey] null means no credential lease is required (public / hermetic / already-auth).
 * When non-null, missing material / issue failure is fail-closed ([RemoteSyncRetryDisposition.Never]).
 * Non-secret backend fields construct the remote port in Rust; secrets only via [secretLeaseId].
 */
data class RustSyncWorkRequest(
    val workspaceRoot: String,
    val backendKind: String,
    val endpointUrl: String = "",
    val usernameOrAccessKey: String = "",
    val bucket: String = "",
    val prefix: String = "",
    val region: String = "",
    val remoteDatasetId: String = "",
    val secretFieldKey: String? = null,
    val leaseTtlMillis: Long = DEFAULT_LEASE_TTL_MILLIS,
    /** Populated by the worker after a successful lease issue; null when no secret field. */
    val secretLeaseId: String? = null,
    val applyRemote: Boolean = true,
) {
    companion object {
        const val INPUT_WORKSPACE_ROOT: String = "rust_sync_workspace_root"
        const val INPUT_BACKEND_KIND: String = "rust_sync_backend_kind"
        const val INPUT_ENDPOINT_URL: String = "rust_sync_endpoint_url"
        const val INPUT_USERNAME_OR_ACCESS_KEY: String = "rust_sync_username_or_access_key"
        const val INPUT_BUCKET: String = "rust_sync_bucket"
        const val INPUT_PREFIX: String = "rust_sync_prefix"
        const val INPUT_REGION: String = "rust_sync_region"
        const val INPUT_REMOTE_DATASET_ID: String = "rust_sync_remote_dataset_id"
        const val INPUT_SECRET_FIELD_KEY: String = "rust_sync_secret_field_key"
        const val INPUT_LEASE_TTL_MILLIS: String = "rust_sync_lease_ttl_millis"
        const val INPUT_APPLY_REMOTE: String = "rust_sync_apply_remote"
        const val DEFAULT_LEASE_TTL_MILLIS: Long = 60_000L
    }
}
