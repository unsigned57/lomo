package com.lomo.app.feature.synccenter

import com.lomo.domain.model.RemoteSyncBinaryConflictFacts
import com.lomo.domain.model.RemoteSyncConfigSummary
import com.lomo.domain.model.RemoteSyncConflictPage
import com.lomo.domain.model.RemoteSyncMarkdownConflictFacts
import com.lomo.domain.model.RemoteSyncSessionProgress
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap

/** Result of an initial or refresh load from the repository. */
fun applySyncCenterLoadSuccess(
    state: SyncCenterUiState,
    config: RemoteSyncConfigSummary,
    session: RemoteSyncSessionProgress,
    page: RemoteSyncConflictPage,
): SyncCenterUiState =
    state.copy(
        load =
            SyncCenterLoadState.Ready(
                config = config,
                session = session,
                conflictPage = page,
                items = page.items.toImmutableList(),
                selectedPath = null,
                perPathResolutionKind = persistentMapOf(),
                mergedDrafts = persistentMapOf(),
                markdownDetailByPath = persistentMapOf(),
                binaryDetailByPath = persistentMapOf(),
                isLoadingDetail = false,
                isResolving = false,
                lastError = null,
                appliedPaths = persistentListOf(),
            ),
    )

fun applySyncCenterLoadFailure(
    state: SyncCenterUiState,
    message: String,
): SyncCenterUiState = state.copy(load = SyncCenterLoadState.Failed(message = message))

fun applySyncCenterPageAppend(
    state: SyncCenterUiState,
    page: RemoteSyncConflictPage,
): SyncCenterUiState {
    val ready = state.load as? SyncCenterLoadState.Ready ?: return state
    val mergedItems = (ready.items + page.items).distinctBy { it.path }.toImmutableList()
    return state.copy(
        load =
            ready.copy(
                conflictPage = page.copy(items = mergedItems),
                items = mergedItems,
            ),
    )
}

fun applySyncCenterResolveSuccess(
    state: SyncCenterUiState,
    sessionId: String,
    conflictRevision: Long,
    appliedPaths: List<String>,
): SyncCenterUiState {
    val ready = state.load as? SyncCenterLoadState.Ready ?: return state
    val applied = appliedPaths.toSet()
    val remaining = ready.items.filterNot { it.path in applied }.toImmutableList()
    val nextKinds =
        ready.perPathResolutionKind
            .filterKeys { it !in applied }
            .toImmutableMap()
    val nextDrafts =
        ready.mergedDrafts
            .filterKeys { it !in applied }
            .toImmutableMap()
    val nextMarkdown =
        ready.markdownDetailByPath
            .filterKeys { it !in applied }
            .toImmutableMap()
    val nextBinary =
        ready.binaryDetailByPath
            .filterKeys { it !in applied }
            .toImmutableMap()
    val nextSelected =
        ready.selectedPath?.takeIf { path -> remaining.any { it.path == path } }
    return state.copy(
        load =
            ready.copy(
                conflictPage =
                    ready.conflictPage.copy(
                        sessionId = sessionId,
                        conflictRevision = conflictRevision,
                        items = remaining,
                    ),
                items = remaining,
                selectedPath = nextSelected,
                perPathResolutionKind = nextKinds,
                mergedDrafts = nextDrafts,
                markdownDetailByPath = nextMarkdown,
                binaryDetailByPath = nextBinary,
                isLoadingDetail = false,
                isResolving = false,
                lastError = null,
                appliedPaths = appliedPaths.toImmutableList(),
                config =
                    ready.config.copy(
                        attentionCount =
                            remaining.count {
                                it.status == com.lomo.domain.model.RemoteSyncConflictPathStatus.Open
                            },
                    ),
            ),
        pane =
            when {
                nextSelected == null && !state.layout.isListDetail -> SyncCenterPane.Conflicts
                else -> state.pane
            },
    )
}

/**
 * Apply a successful markdown detail load for [path].
 *
 * Ignores stale completions when [path] is no longer selected.
 */
fun applySyncCenterMarkdownDetailSuccess(
    state: SyncCenterUiState,
    path: String,
    facts: RemoteSyncMarkdownConflictFacts,
): SyncCenterUiState {
    val ready = state.load as? SyncCenterLoadState.Ready ?: return state
    if (ready.selectedPath != path) {
        return state.copy(load = ready.copy(isLoadingDetail = false))
    }
    val nextMap =
        ready.markdownDetailByPath
            .toMutableMap()
            .apply { put(path, facts) }
            .toImmutableMap()
    return state.copy(
        load =
            ready.copy(
                markdownDetailByPath = nextMap,
                isLoadingDetail = false,
                lastError = null,
            ),
    )
}

/**
 * Apply a successful binary detail load for [path].
 *
 * Ignores stale completions when [path] is no longer selected. Never invents text bodies.
 */
fun applySyncCenterBinaryDetailSuccess(
    state: SyncCenterUiState,
    path: String,
    facts: RemoteSyncBinaryConflictFacts,
): SyncCenterUiState {
    val ready = state.load as? SyncCenterLoadState.Ready ?: return state
    if (ready.selectedPath != path) {
        return state.copy(load = ready.copy(isLoadingDetail = false))
    }
    val nextMap =
        ready.binaryDetailByPath
            .toMutableMap()
            .apply { put(path, facts) }
            .toImmutableMap()
    return state.copy(
        load =
            ready.copy(
                binaryDetailByPath = nextMap,
                isLoadingDetail = false,
                lastError = null,
            ),
    )
}

/**
 * Detail load failed closed: clear loading flag and surface structured lastError.
 *
 * Stale failures for a deselected path only clear the loading flag.
 */
fun applySyncCenterDetailFailure(
    state: SyncCenterUiState,
    path: String,
    message: String,
): SyncCenterUiState {
    val ready = state.load as? SyncCenterLoadState.Ready ?: return state
    if (ready.selectedPath != path) {
        return state.copy(load = ready.copy(isLoadingDetail = false))
    }
    return state.copy(
        load =
            ready.copy(
                isLoadingDetail = false,
                lastError = message,
            ),
    )
}

fun applySyncCenterResolveFailure(
    state: SyncCenterUiState,
    message: String,
): SyncCenterUiState {
    val ready = state.load as? SyncCenterLoadState.Ready ?: return state
    return state.copy(
        load = ready.copy(isResolving = false, lastError = message),
    )
}
