package com.lomo.data.local.entity

/**
 * Non-persisted marker for sync state entities used as in-memory planner snapshots before a workspace-scoped
 * store stamps the active generation at the persistence boundary.
 */
const val TRANSIENT_WORKSPACE_GENERATION = "__transient_workspace_generation__"
