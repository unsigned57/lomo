package com.lomo.domain.repository

/**
 * Atomic data-side cleanup primitives required after switching workspace root.
 *
 * Domain decides when to trigger these operations (policy), data only executes them.
 */
interface WorkspaceTransitionRepository {
    suspend fun clearMemoStateAfterWorkspaceTransition()
}
