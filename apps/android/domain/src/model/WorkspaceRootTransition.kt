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
    val mediaChange: MediaDirectoryChange? = null,
) {
    private companion object {
        const val MAX_ID_LENGTH = 128
    }

    init {
        require(id.isNotBlank() && id.length <= MAX_ID_LENGTH) { "Workspace transition id is invalid" }
        require(candidate.raw.isNotBlank()) { "Workspace transition candidate is blank" }
        require(previous == null || previous.raw.isNotBlank()) { "Workspace transition previous root is blank" }
        require(previous != candidate || mediaChange != null) { "Workspace transition must change a directory" }
    }
}

/** An attachment rebind is prepared with the workspace; it is never saved before indexing succeeds. */
data class MediaDirectoryChange(
    val area: StorageArea,
    val previous: StorageLocation?,
    /** A missing override follows the mounted notes tree. */
    val candidate: StorageLocation?,
) {
    init {
        require(area == StorageArea.IMAGE || area == StorageArea.VOICE) { "Only attachment roots can be rebound" }
        require(previous != candidate) { "Media directory candidate must differ from previous selection" }
        require((candidate == null || candidate.raw.isNotBlank()) && (previous == null || previous.raw.isNotBlank())) {
            "Media directory locations must not be blank"
        }
    }
}

class WorkspaceRootTransitionCorruptionException(message: String) : IllegalStateException(message)
