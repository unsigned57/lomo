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
     * Inspects one Rust-owned plan/readiness cycle for [workspaceRoot] (empty-port readiness).
     *
     * Conversion-only: maps `sync_inspect_cycle_plan`. Not the production work unit.
     */
    fun inspectCyclePlan(workspaceRoot: String): RemoteSyncCyclePlanSummary

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
}

/**
 * Non-secret backend config + optional lease for one production cycle.
 *
 * [backendKind]: `hermetic_fake` | `webdav` | `s3` | `git`.
 * Git wire reuse: [endpointUrl]=remote, [usernameOrAccessKey]=HTTPS user (default `git` when token
 * present), [bucket]=branch (default `main`), [prefix]=author name, [region]=author email.
 * Secrets never appear here — only [secretLeaseId].
 */
data class RemoteSyncCycleRequest(
    val workspaceRoot: String,
    val backendKind: String,
    val endpointUrl: String = "",
    val usernameOrAccessKey: String = "",
    val bucket: String = "",
    val prefix: String = "",
    val region: String = "",
    val remoteDatasetId: String = "",
    val secretLeaseId: String? = null,
    val applyRemote: Boolean = true,
)
