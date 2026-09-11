package com.lomo.data.sync.pendingreview

import kotlinx.serialization.Serializable

/**
 * One durable Sync Inbox pending-review record scoped to a (workspaceGeneration, backend) key.
 *
 * Property names are the on-disk JSON wire: they must stay stable for existing installs.
 */
@Serializable
data class PendingSyncReviewRecord(
    val workspaceGeneration: String = TRANSIENT_WORKSPACE_GENERATION,
    val backend: String,
    val reviewKind: String,
    val timestamp: Long,
    val payloadJson: String,
) {
    init {
        require(workspaceGeneration.isNotBlank()) { "Pending sync review must be scoped to a workspace generation" }
    }
}

/**
 * Non-persisted marker for records used as in-memory planner snapshots before a workspace-scoped
 * store stamps the active generation at the persistence boundary.
 */
const val TRANSIENT_WORKSPACE_GENERATION = "__transient_workspace_generation__"
