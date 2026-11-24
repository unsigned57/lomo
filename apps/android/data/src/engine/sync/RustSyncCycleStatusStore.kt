package com.lomo.data.engine.sync

import com.lomo.data.engine.media.WorkspaceFilesystemRoot
import com.lomo.domain.model.WorkspaceMount
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive

/**
 * Durable sync cycle status read surface (post P5-13).
 *
 * `cycle_state.rec` is the sole authority for remote sync state; this store performs real
 * [RemoteSyncRepository.cycleStatus] reads — there is no in-memory `Idle` seed. `null` means the
 * workspace has no Direct filesystem root (SAF), so no durable sync authority exists; that is a
 * real state, not a default.
 *
 * [observe] tracks [workspaceMounts] (when supplied) so non-Direct or unmounted workspaces stay
 * idle without 2s polling, and polls Direct workspaces while active so running-cycle transitions
 * surface without dual write paths. Concurrent workspace transitions between [workspaceRootPath]
 * and [RemoteSyncRepository.cycleStatus] are retried only when the active root actually changed.
 */
class RustSyncCycleStatusStore(
    private val remoteSync: RemoteSyncRepository,
    private val workspaceRoot: WorkspaceFilesystemRoot,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val workspaceMounts: Flow<WorkspaceMount>? = null,
) {
    /** Direct workspace root backing the durable record; `null` when SAF-hosted or blank. */
    fun workspaceRootPath(): String? = workspaceRoot.absolutePathOrNull()?.takeIf { it.isNotBlank() }

    /** Single durable read; `null` when no Direct workspace root exists. */
    fun refresh(): RemoteSyncCycleStatus? {
        var attempt = 0
        while (true) {
            val root = workspaceRootPath() ?: return null
            val failure =
                try {
                    return remoteSync.cycleStatus(root)
                } catch (error: RemoteSyncBoundaryFailure) {
                    if (error.code in CONCURRENT_SWITCH_CODES) error else throw error
                } catch (error: IllegalStateException) {
                    error
                }
            if (attempt >= MAX_CONCURRENT_SWITCH_RETRIES || workspaceRootPath() == root) {
                throw failure
            }
            attempt += 1
        }
    }

    /**
     * Continuous durable observation at [pollIntervalMillis] cadence.
     *
     * Emits the real record on collection and re-reads while a Direct workspace is mounted so
     * running-cycle transitions (cancel requests, terminal records) surface without fabricated state.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(pollIntervalMillis: Long = DEFAULT_POLL_INTERVAL_MS): Flow<RemoteSyncCycleStatus?> {
        val mounts = workspaceMounts
        val source =
            if (mounts == null) {
                pollLoop(pollIntervalMillis)
            } else {
                mounts
                    .map { workspaceRootPath() }
                    .distinctUntilChanged()
                    .flatMapLatest { mountedRoot ->
                        if (mountedRoot == null) {
                            flowOf(null)
                        } else {
                            pollLoop(pollIntervalMillis)
                        }
                    }
            }
        return source.distinctUntilChanged().flowOn(dispatcher)
    }

    private fun pollLoop(pollIntervalMillis: Long): Flow<RemoteSyncCycleStatus?> = flow {
        while (currentCoroutineContext().isActive) {
            emit(refresh())
            delay(pollIntervalMillis)
        }
    }

    companion object {
        const val DEFAULT_POLL_INTERVAL_MS: Long = 2_000
        private const val MAX_CONCURRENT_SWITCH_RETRIES: Int = 2
        private val CONCURRENT_SWITCH_CODES = setOf(
            "sync_local_pull_requires_direct_workspace",
            "sync_cycle_workspace_mismatch",
            "workspace_session_unavailable",
        )
    }
}
