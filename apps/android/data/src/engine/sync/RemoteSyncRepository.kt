package com.lomo.data.engine.sync

/**
 * Stage-5 production remote sync repository (post P5-13).
 *
 * Coarse APIs only: conflict list/resolve + secret lease lifecycle + composed owner cycle.
 * Not a DAO; not provider-specific planner. Production DI binds BoltFFI conversion adapters.
 */
interface RemoteSyncRepository {
    fun listConflicts(
        workspaceRoot: String,
        cursor: Int,
        limit: Int,
    ): RemoteSyncConflictPage

    fun resolveConflicts(
        workspaceRoot: String,
        expectedRevision: Long,
        resolutions: List<RemoteSyncConflictResolution>,
    ): RemoteSyncConflictResolveResult

    fun issueSecretLease(
        secretBytes: ByteArray,
        ttlMillis: Long,
    ): RemoteSyncSecretLease

    /**
     * Probes a lease id; returns secret **length only** (never secret bytes).
     */
    fun probeSecretLease(leaseId: String): Int

    fun revokeSecretLease(leaseId: String)

    /**
     * Runs one production-shaped owner cycle with real local/remote port composition.
     *
     * Conversion-only: maps `sync_run_cycle`. Secrets are process-local lease ids only.
     * Kotlin must not re-plan or construct protocol adapters.
     */
    fun runCycle(request: RemoteSyncCycleRequest): RemoteSyncCyclePlanSummary

    /**
     * Loads the durable workspace generation fence (read-only; never mints).
     *
     * Conversion-only: maps `sync_workspace_generation`.
     */
    fun loadWorkspaceGeneration(workspaceRoot: String): String

    /**
     * Clears durable `.lomo/sync/v1` control records for the workspace (not user Markdown).
     *
     * Conversion-only: maps `sync_reset_control_tree`.
     */
    fun resetControlTree(workspaceRoot: String)

    /**
     * Reads the durable cycle record — the sole authority for sync status.
     *
     * Conversion-only: maps `sync_cycle_status`. Read-only; `hasRecord=false`/`phase=idle`
     * when the workspace never ran a cycle.
     */
    fun cycleStatus(workspaceRoot: String): RemoteSyncCycleStatus

    /**
     * Persists a cancellation request bound to the running cycle's identity fence.
     *
     * Conversion-only: maps `sync_request_cancel`. Rejects (boundary failure) when no
     * matching cycle is running; returns the post-write authoritative record on success.
     */
    fun requestCancel(workspaceRoot: String): RemoteSyncCycleStatus

    /**
     * Probes the configured backend with the same adapter construction a real cycle uses.
     *
     * Conversion-only: maps `sync_probe_backend`. Real capabilities + listing round-trip —
     * never an enqueue acceptance. Takes the workspace cycle lock in Rust.
     */
    fun probeBackend(request: RemoteSyncCycleRequest): RemoteSyncBackendProbe
}

/**
 * Non-secret backend config + optional lease for one production cycle.
 *
 * [backendKind]: `hermetic_fake` | `webdav` | `s3` | `git`. Per-kind fields are explicit:
 * WebDAV uses [endpointUrl] + [identity] (username); S3 uses [endpointUrl] + [identity]
 * (access key id) + `s3*`; Git uses [endpointUrl] (remote URL; userinfo/SSH rejected in Rust) +
 * [identity] (HTTPS username, default `git` when token present) + `git*`. Fields outside the
 * selected kind must stay empty — the FFI boundary rejects mixed shapes. Secrets never appear
 * here — only [secretLeaseId].
 */
data class RemoteSyncCycleRequest(
    val workspaceRoot: String,
    val backendKind: String,
    val endpointUrl: String = "",
    val identity: String = "",
    val s3Bucket: String = "",
    val s3Prefix: String = "",
    val s3Region: String = "",
    val gitBranch: String = "",
    val gitAuthorName: String = "",
    val gitAuthorEmail: String = "",
    val remoteDatasetId: String = "",
    val secretLeaseId: String? = null,
    val applyRemote: Boolean = true,
)
