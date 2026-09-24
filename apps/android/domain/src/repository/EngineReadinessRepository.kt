package com.lomo.domain.repository

import com.lomo.domain.model.EngineReadiness
import com.lomo.domain.model.DerivedIndexRebuildSummary
import com.lomo.domain.model.RecoveryDiagnosticReport
import com.lomo.domain.model.ProjectionFreshness
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.model.WorkspaceAuthority
import com.lomo.domain.model.WorkspaceMount
import kotlinx.coroutines.flow.StateFlow

/**
 * Rust-owned application-kernel readiness without Rust, BoltFFI, or Android types.
 *
 * [activateWorkspace] is the only production path that can leave
 * [EngineReadiness.AwaitingWorkspaceSelection] after the user (or cold-start restore) supplies a root.
 * Success means the new engine published [EngineReadiness.Ready]; soft Recovery is a failure that
 * leaves the previous engine authoritative.
 */
interface EngineReadinessRepository {
    val readiness: StateFlow<EngineReadiness>

    /**
     * Single mount value: readiness, selected location, authority, and projection freshness.
     * Read admission is [WorkspaceMount.admitsProjectionReads]; do not recombine the sibling flows.
     */
    val mount: StateFlow<WorkspaceMount>

    /**
     * Location of the engine currently installed at Ready, or null when no workspace engine is
     * active. Observe-root rebuild must match this identity to the persisted DataStore selection so
     * index rebuild never runs against new VFS + old engine mid-switch.
     */
    val activeWorkspaceLocation: StateFlow<StorageLocation?>

    /**
     * Stable identity, activation generation, and installed store projection revision of the engine
     * currently installed at Ready, or null when no workspace engine is active.
     *
     * Generation increments on every committed activation so a mutation admitted over one workspace
     * can never be attributed to the next one.
     */
    val workspaceAuthority: StateFlow<WorkspaceAuthority?>

    /**
     * Freshness of the active disposable query projection. [ProjectionFreshness.Verified] admits
     * writes; [ProjectionFreshness.Revalidating] keeps the last verified reads while a later scan
     * runs. A first projection with no trusted cache stays [ProjectionFreshness.Unavailable].
     */
    val projectionFreshness: StateFlow<ProjectionFreshness>

    /** Reloads the authoritative snapshot after foreground resume or suspected notification loss. */
    fun resnapshot()

    /**
     * Explicitly requests that this process open the native engine, then resolves with the first
     * settled [EngineReadiness] (anything other than [EngineReadiness.Opening]).
     *
     * Constructing or injecting the session never opens native: engine acquisition only follows an
     * explicit request from a consumer that actually needs the workspace — an Activity, a
     * background worker, or a durable workspace mutation. Repeated requests are idempotent, so the
     * engine opens at most once per process. A projection-only process duty never opens native and
     * returns the current (still [EngineReadiness.Opening]) value immediately.
     */
    suspend fun requestEngineStart(): EngineReadiness

    /**
     * Builds a bounded, secret-free report from typed recovery facts only.
     *
     * Raw native diagnostics and workspace paths are intentionally excluded.
     */
    suspend fun createRecoveryDiagnosticReport(): RecoveryDiagnosticReport

    /**
     * Rebuilds only the disposable Rust SQLite projection for a known rebuildable SQLite failure,
     * then reopens the same workspace. Markdown, media and `.lomo` durable facts are never deleted.
     */
    suspend fun rebuildDerivedIndex(): DerivedIndexRebuildSummary

    /**
     * Opens or reopens the engine for [location].
     *
     * The previous engine remains authoritative until the candidate reaches Ready. On Ready success
     * the new engine becomes the sole readiness publisher and the previous engine is closed. A
     * still-verified previous mount publishes [ProjectionFreshness.Revalidating] for the duration of
     * prepare; install then publishes [ProjectionFreshness.Verified]. No trusted previous projection
     * stays Opening/[ProjectionFreshness.Unavailable]. On hard open failure or soft non-Ready open
     * the previous engine (if any) stays active, Verified is restored when that authority remains,
     * and the error is rethrown. Retry after Recovery is a new [activateWorkspace] generation.
     */
    suspend fun activateWorkspace(location: StorageLocation)

    /**
     * Releases the active engine and publishes [EngineReadiness.AwaitingWorkspaceSelection].
     * Serialized with [activateWorkspace]. Idempotent when no workspace is active.
     */
    suspend fun clearWorkspace()
}
