package com.lomo.data.engine.sync

import com.lomo.data.engine.media.WorkspaceFilesystemRoot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

/**
 * Durable sync cycle status read surface (post P5-13).
 *
 * `cycle_state.rec` is the sole authority for remote sync state; this store performs real
 * [RemoteSyncRepository.cycleStatus] reads — there is no in-memory `Idle` seed. `null` means the
 * workspace has no Direct filesystem root (SAF), so no durable sync authority exists; that is a
 * real state, not a default.
 *
 * [observe] polls the durable record while collected: same-process writers (worker, cancel,
 * probe) update the record inside Rust, so polling is the honest refresh — no dual write path.
 */
class RustSyncCycleStatusStore(
    private val remoteSync: RemoteSyncRepository,
    private val workspaceRoot: WorkspaceFilesystemRoot,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Direct workspace root backing the durable record; `null` when SAF-hosted or blank. */
    fun workspaceRootPath(): String? =
        workspaceRoot.absolutePathOrNull()?.takeIf { it.isNotBlank() }

    /** Single durable read; `null` when no Direct workspace root exists. */
    fun refresh(): RemoteSyncCycleStatus? {
        val root = workspaceRootPath() ?: return null
        return remoteSync.cycleStatus(root)
    }

    /**
     * Continuous durable observation at [pollIntervalMillis] cadence.
     *
     * Emits the real record on collection and re-reads while active so running-cycle
     * transitions (cancel requests, terminal records) surface without fabricated state.
     */
    fun observe(pollIntervalMillis: Long = DEFAULT_POLL_INTERVAL_MS): Flow<RemoteSyncCycleStatus?> =
        flow {
            while (currentCoroutineContext().isActive) {
                emit(refresh())
                delay(pollIntervalMillis)
            }
        }.distinctUntilChanged().flowOn(dispatcher)

    companion object {
        const val DEFAULT_POLL_INTERVAL_MS: Long = 2_000
    }
}
