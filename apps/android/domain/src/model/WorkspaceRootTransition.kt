package com.lomo.domain.model

enum class WorkspaceRootTransitionPhase {
    PREPARED,
    ACTIVATED,
}

data class WorkspaceRootTransition(
    val id: String,
    val previous: StorageLocation?,
    val candidate: StorageLocation,
    val phase: WorkspaceRootTransitionPhase,
) {
    private companion object {
        const val MAX_ID_LENGTH = 128
    }

    init {
        require(id.isNotBlank() && id.length <= MAX_ID_LENGTH) { "Workspace transition id is invalid" }
        require(candidate.raw.isNotBlank()) { "Workspace transition candidate is blank" }
        require(previous?.raw?.isNotBlank() != false) { "Workspace transition previous root is blank" }
        require(previous != candidate) { "Workspace transition candidate must differ from previous root" }
    }
}

class WorkspaceRootTransitionCorruptionException(
    message: String,
) : IllegalStateException(message)
