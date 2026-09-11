package com.lomo.domain.repository

/**
 * Rebuilds all workspace-derived state from the currently configured root.
 *
 * Domain decides when a rebuild is required; data owns the concrete Room, file, and media
 * reconciliation needed to make the current workspace visible.
 */
interface WorkspaceStateResolver {
    suspend fun rebuildFromCurrentWorkspace()
}
