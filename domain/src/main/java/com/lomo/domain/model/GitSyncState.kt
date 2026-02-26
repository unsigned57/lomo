package com.lomo.domain.model

sealed interface GitSyncState {
    data object Idle : GitSyncState
    data object Syncing : GitSyncState
    data class Success(val timestamp: Long, val summary: String) : GitSyncState
    data class Error(val message: String, val timestamp: Long) : GitSyncState
    data object NotConfigured : GitSyncState
    data object Initializing : GitSyncState
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
