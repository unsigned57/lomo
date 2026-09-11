package com.lomo.app.feature.synccenter

import com.lomo.domain.model.RemoteSyncConflictResolution
import kotlinx.collections.immutable.toImmutableMap

fun reduceSyncCenter(
    state: SyncCenterUiState,
    intent: SyncCenterIntent,
): SyncCenterReduceResult =
    when (intent) {
        is SyncCenterIntent.Open -> reduceOpen(state, intent)
        SyncCenterIntent.Refresh -> reduceRefresh(state)
        SyncCenterIntent.LoadMoreConflicts -> reduceLoadMore(state)
        is SyncCenterIntent.SelectConflict -> reduceSelectConflict(state, intent)
        SyncCenterIntent.ClearSelection -> reduceClearSelection(state)
        is SyncCenterIntent.SetResolutionKind -> reduceSetResolutionKind(state, intent)
        is SyncCenterIntent.SetMergedDraft -> reduceSetMergedDraft(state, intent)
        SyncCenterIntent.ApplyResolutions -> reduceApplyResolutions(state)
        SyncCenterIntent.NavigateOverview ->
            SyncCenterReduceResult(state.copy(pane = SyncCenterPane.Overview))
        SyncCenterIntent.NavigateConflicts ->
            SyncCenterReduceResult(state.copy(pane = SyncCenterPane.Conflicts))
        SyncCenterIntent.NavigateRecovery ->
            SyncCenterReduceResult(state.copy(pane = SyncCenterPane.Recovery))
        is SyncCenterIntent.SetListDetail -> reduceSetListDetail(state, intent)
        SyncCenterIntent.CancelSession ->
            SyncCenterReduceResult(
                state = state,
                effects = listOf(SyncCenterEffect.RequestCancel),
            )
    }

private fun reduceOpen(
    state: SyncCenterUiState,
    intent: SyncCenterIntent.Open,
): SyncCenterReduceResult {
    val opened =
        state.copy(
            workspaceRoot = intent.workspaceRoot,
            layout = SyncCenterLayoutMode(isListDetail = intent.isListDetail),
            pane = SyncCenterPane.Overview,
            load = SyncCenterLoadState.Loading,
        )
    return SyncCenterReduceResult(
        state = opened,
        effects = listOf(SyncCenterEffect.LoadInitial(intent.workspaceRoot)),
    )
}

private fun reduceRefresh(state: SyncCenterUiState): SyncCenterReduceResult {
    if (state.workspaceRoot.isEmpty()) {
        return SyncCenterReduceResult(state)
    }
    return SyncCenterReduceResult(
        state = state.copy(load = SyncCenterLoadState.Loading),
        effects = listOf(SyncCenterEffect.LoadInitial(state.workspaceRoot)),
    )
}

private fun reduceLoadMore(state: SyncCenterUiState): SyncCenterReduceResult {
    val ready = state.load as? SyncCenterLoadState.Ready
    val cursor = ready?.conflictPage?.nextCursor
    if (ready == null || cursor == null || ready.isResolving) {
        return SyncCenterReduceResult(state)
    }
    return SyncCenterReduceResult(
        state = state,
        effects =
            listOf(
                SyncCenterEffect.LoadMore(
                    workspaceRoot = state.workspaceRoot,
                    cursor = cursor,
                    limit = SYNC_CENTER_CONFLICT_PAGE_LIMIT,
                ),
            ),
    )
}

private fun reduceSelectConflict(
    state: SyncCenterUiState,
    intent: SyncCenterIntent.SelectConflict,
): SyncCenterReduceResult {
    val ready = state.load as? SyncCenterLoadState.Ready ?: return SyncCenterReduceResult(state)
    val selected = ready.items.firstOrNull { it.path == intent.path }
        ?: return SyncCenterReduceResult(state)
    val nextPane =
        if (state.layout.isListDetail) {
            SyncCenterPane.Conflicts
        } else {
            SyncCenterPane.ConflictDetail
        }
    val needsDetail = selected.isMarkdown || selected.isBinary
    return SyncCenterReduceResult(
        state =
            state.copy(
                pane = nextPane,
                load =
                    ready.copy(
                        selectedPath = intent.path,
                        isLoadingDetail = needsDetail,
                        lastError = null,
                    ),
            ),
        effects =
            if (needsDetail) {
                listOf(
                    SyncCenterEffect.LoadConflictDetail(
                        workspaceRoot = state.workspaceRoot,
                        path = selected,
                        mergedDraft = ready.mergedDrafts[intent.path],
                    ),
                )
            } else {
                emptyList()
            },
    )
}

private fun reduceClearSelection(state: SyncCenterUiState): SyncCenterReduceResult {
    val ready = state.load as? SyncCenterLoadState.Ready ?: return SyncCenterReduceResult(state)
    return SyncCenterReduceResult(
        state =
            state.copy(
                pane = SyncCenterPane.Conflicts,
                load =
                    ready.copy(
                        selectedPath = null,
                        isLoadingDetail = false,
                    ),
            ),
    )
}

private fun reduceSetResolutionKind(
    state: SyncCenterUiState,
    intent: SyncCenterIntent.SetResolutionKind,
): SyncCenterReduceResult {
    val ready = state.load as? SyncCenterLoadState.Ready ?: return SyncCenterReduceResult(state)
    if (ready.isResolving) return SyncCenterReduceResult(state)
    val nextKinds =
        ready.perPathResolutionKind
            .toMutableMap()
            .apply { put(intent.path, intent.kind) }
            .toImmutableMap()
    val nextDrafts =
        if (intent.kind == RemoteSyncConflictResolution.KIND_MERGED_BODY) {
            ready.mergedDrafts
        } else {
            ready.mergedDrafts
                .toMutableMap()
                .apply { remove(intent.path) }
                .toImmutableMap()
        }
    return SyncCenterReduceResult(
        state =
            state.copy(
                load =
                    ready.copy(
                        perPathResolutionKind = nextKinds,
                        mergedDrafts = nextDrafts,
                        lastError = null,
                    ),
            ),
    )
}

private fun reduceSetMergedDraft(
    state: SyncCenterUiState,
    intent: SyncCenterIntent.SetMergedDraft,
): SyncCenterReduceResult {
    val ready = state.load as? SyncCenterLoadState.Ready ?: return SyncCenterReduceResult(state)
    if (ready.isResolving) return SyncCenterReduceResult(state)
    val nextDrafts =
        ready.mergedDrafts
            .toMutableMap()
            .apply { put(intent.path, intent.draft) }
            .toImmutableMap()
    val nextKinds =
        ready.perPathResolutionKind
            .toMutableMap()
            .apply {
                putIfAbsent(intent.path, RemoteSyncConflictResolution.KIND_MERGED_BODY)
            }.toImmutableMap()
    return SyncCenterReduceResult(
        state =
            state.copy(
                load =
                    ready.copy(
                        mergedDrafts = nextDrafts,
                        perPathResolutionKind = nextKinds,
                    ),
            ),
    )
}

private fun reduceApplyResolutions(state: SyncCenterUiState): SyncCenterReduceResult {
    val ready = state.load as? SyncCenterLoadState.Ready ?: return SyncCenterReduceResult(state)
    if (ready.isResolving) return SyncCenterReduceResult(state)
    val resolutions = buildResolutions(ready)
    if (resolutions.isEmpty()) {
        return SyncCenterReduceResult(
            state =
                state.copy(
                    load = ready.copy(lastError = "no_resolutions_selected"),
                ),
        )
    }
    return SyncCenterReduceResult(
        state = state.copy(load = ready.copy(isResolving = true, lastError = null)),
        effects =
            listOf(
                SyncCenterEffect.Resolve(
                    workspaceRoot = state.workspaceRoot,
                    expectedRevision = ready.conflictPage.conflictRevision,
                    resolutions = resolutions,
                ),
            ),
    )
}

private fun reduceSetListDetail(
    state: SyncCenterUiState,
    intent: SyncCenterIntent.SetListDetail,
): SyncCenterReduceResult =
    SyncCenterReduceResult(
        state =
            state.copy(
                layout = SyncCenterLayoutMode(isListDetail = intent.isListDetail),
                pane =
                    when {
                        intent.isListDetail && state.pane == SyncCenterPane.ConflictDetail ->
                            SyncCenterPane.Conflicts
                        !intent.isListDetail &&
                            state.pane == SyncCenterPane.Conflicts &&
                            (state.load as? SyncCenterLoadState.Ready)?.selectedPath != null ->
                            SyncCenterPane.ConflictDetail
                        else -> state.pane
                    },
            ),
    )
