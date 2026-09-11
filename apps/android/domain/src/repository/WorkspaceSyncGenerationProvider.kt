package com.lomo.domain.repository

@JvmInline
value class WorkspaceSyncGeneration(val value: String) {
    init {
        require(value.isNotBlank()) { "Workspace sync generation must not be blank" }
    }
}

/**
 * Provides the identity boundary for sync state that is valid only for the active workspace root.
 *
 * Domain owns this lifecycle contract: sync state belongs to exactly one workspace generation, and data
 * repositories must read and write provider state only for the active generation.
 */
interface WorkspaceSyncGenerationProvider {
    suspend fun activeGeneration(): WorkspaceSyncGeneration
}
