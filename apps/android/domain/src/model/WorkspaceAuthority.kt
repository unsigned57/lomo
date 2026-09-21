package com.lomo.domain.model

/**
 * Identity a workspace mutation is admitted against.
 *
 * [workspaceId] is the stable identity of the selected workspace, never a process-local access
 * capability. [generation] increments on every committed activation, so authority taken over one
 * workspace can never be mistaken for authority over the next one after a switch.
 * [projectionRevision] is the Rust store high-water revision installed with this generation. Zero
 * may represent a newly promoted workspace whose first derived projection is still Unavailable;
 * [ProjectionFreshness] decides whether that projection can admit writes.
 */
data class WorkspaceAuthority(
    val workspaceId: String,
    val generation: Long,
    val projectionRevision: ULong,
) {
    init {
        require(workspaceId.isNotBlank()) { "Workspace authority id must be non-blank" }
        require(generation >= 0) { "Workspace authority generation must be non-negative" }
    }
}
