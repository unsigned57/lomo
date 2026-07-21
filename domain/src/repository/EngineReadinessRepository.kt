package com.lomo.domain.repository

import com.lomo.domain.model.EngineReadiness
import com.lomo.domain.model.StorageLocation
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
     * Location of the engine currently installed at Ready, or null when no workspace engine is
     * active. Observe-root rebuild must match this identity to the persisted DataStore selection so
     * index rebuild never runs against new VFS + old engine mid-switch.
     */
    val activeWorkspaceLocation: StateFlow<StorageLocation?>

    /** Reloads the authoritative snapshot after foreground resume or suspected notification loss. */
    fun resnapshot()

    /**
     * Opens or reopens the engine for [location].
     *
     * The previous engine remains authoritative until the candidate reaches Ready. On Ready success
     * the new engine becomes the sole readiness publisher and the previous engine is closed. On hard
     * open failure or soft non-Ready open the previous engine (if any) stays active and the error is
     * rethrown.
     */
    suspend fun activateWorkspace(location: StorageLocation)

    /**
     * Releases the active engine and publishes [EngineReadiness.AwaitingWorkspaceSelection].
     * Serialized with [activateWorkspace]. Idempotent when no workspace is active.
     */
    suspend fun clearWorkspace()
}
