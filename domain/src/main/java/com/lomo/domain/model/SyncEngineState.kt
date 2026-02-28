package com.lomo.domain.model

sealed interface SyncEngineState {
    data object Idle : SyncEngineState
    sealed interface Syncing : SyncEngineState {
        data object Pulling : Syncing
        data object Committing : Syncing
        data object Pushing : Syncing
    }
    data class Success(val timestamp: Long, val summary: String) : SyncEngineState
    data class Error(val message: String, val timestamp: Long) : SyncEngineState
    data object NotConfigured : SyncEngineState
    data object Initializing : SyncEngineState
}

data class GitSyncStatus(
    val hasLocalChanges: Boolean,
    val aheadCount: Int,
    val behindCount: Int,
    val lastSyncTime: Long?,
)

sealed interface GitSyncResult {
    data class Success(val message: String) : GitSyncResult
    data class Error(val message: String, val exception: Throwable? = null) : GitSyncResult
    data object NotConfigured : GitSyncResult
    data object DirectPathRequired : GitSyncResult
}
