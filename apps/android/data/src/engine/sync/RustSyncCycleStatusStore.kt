package com.lomo.data.engine.sync

import com.lomo.data.engine.media.WorkspaceFilesystemRoot
import com.lomo.data.util.runNonFatalCatching
import com.lomo.domain.model.WorkspaceMount
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.isActive

/**
 * Durable sync cycle status read surface (post P5-13).
 *
 * `cycle_state.rec` is the sole authority for remote sync state; this store performs real
 * [RemoteSyncRepository.cycleStatus] reads — there is no in-memory `Idle` seed. `null` means the
 * workspace has no Direct filesystem root (SAF), so no durable sync authority exists; that is a
 * real state, not a default.
 *
 * [observe] shares one poller among subscribers while a Direct workspace is mounted. Concurrent
 * workspace transitions between [workspaceRootPath]
 * and [RemoteSyncRepository.cycleStatus] are retried only when the active root actually changed.
 */
class RustSyncCycleStatusStore(
    private val remoteSync: RemoteSyncRepository,
    private val workspaceRoot: WorkspaceFilesystemRoot,
    private val scope: CoroutineScope,
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

    private data class Observation(val root: String?, val status: Result<RemoteSyncCycleStatus?>)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val observations by lazy {
        val mounts = workspaceMounts
        val source =
            if (mounts == null) {
                pollLoop()
            } else {
                mounts
                    .map { workspaceRootPath() }
                    .distinctUntilChanged()
                    .flatMapLatest { mountedRoot ->
                        if (mountedRoot == null) {
                            flowOf(Observation(null, Result.success(null)))
                        } else {
                            pollLoop()
                        }
                    }
            }
        source
            .distinctUntilChanged()
            .flowOn(dispatcher)
            .shareIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(replayExpirationMillis = 0),
                replay = 1,
            )
    }

    /** A replayed observation must still belong to the current workspace. */
    fun observe(): Flow<RemoteSyncCycleStatus?> = observations
        .filter { it.root == workspaceRootPath() }
        .map { it.status.getOrThrow() }

    private fun pollLoop(): Flow<Observation> = flow {
        while (currentCoroutineContext().isActive) {
            emit(Observation(workspaceRootPath(), runNonFatalCatching { refresh() }))
            delay(POLL_INTERVAL_MS)
        }
    }

    companion object {
        private const val POLL_INTERVAL_MS: Long = 2_000
        private const val MAX_CONCURRENT_SWITCH_RETRIES: Int = 2
        private val CONCURRENT_SWITCH_CODES = setOf(
            "sync_local_pull_requires_direct_workspace",
            "sync_cycle_workspace_mismatch",
            "workspace_session_unavailable",
        )
    }
}
