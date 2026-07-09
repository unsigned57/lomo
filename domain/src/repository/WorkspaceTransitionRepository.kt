package com.lomo.domain.repository

/**
 * Atomic data-side cleanup primitives required after switching workspace root.
 *
 * Domain decides when to trigger these operations (policy), data only executes them.
 */
interface WorkspaceTransitionRepository {
    suspend fun clearMemoStateAfterWorkspaceTransition()
}

/**
 * Rebuilds all workspace-derived state from the currently configured root.
 *
 * Domain decides when a rebuild is required; data owns the concrete Room, file, and media
 * reconciliation needed to make the current workspace visible.
 */
interface WorkspaceStateResolver {
    suspend fun rebuildFromCurrentWorkspace()
}
