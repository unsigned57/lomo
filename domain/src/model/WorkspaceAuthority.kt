package com.lomo.domain.model

/**
 * Identity a workspace mutation is admitted against.
 *
 * [workspaceId] is the stable identity of the selected workspace, never a process-local access
 * capability. [generation] increments on every committed activation, so authority taken over one
 * workspace can never be mistaken for authority over the next one after a switch.
 */
data class WorkspaceAuthority(
    val workspaceId: String,
    val generation: Long,
) {
    init {
        require(workspaceId.isNotBlank()) { "Workspace authority id must be non-blank" }
        require(generation >= 0) { "Workspace authority generation must be non-negative" }
    }
}
