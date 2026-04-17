package com.lomo.domain.model

enum class UnifiedSyncOperation {
    MANUAL_SYNC,
    REFRESH_SYNC,
    PROCESS_PENDING_CHANGES,
}

enum class UnifiedSyncPhase {
    INITIALIZING,
    CONNECTING,
    LISTING,
    PULLING,
    COMMITTING,
    PUSHING,
    UPLOADING,
    DOWNLOADING,
    DELETING,
}

data class UnifiedSyncError(
    val provider: SyncBackendType,
    val message: String,
    val cause: Throwable? = null,
    val providerCode: String? = null,
)

sealed interface UnifiedSyncState {
    data object Idle : UnifiedSyncState

    data class Running(
        val provider: SyncBackendType,
        val phase: UnifiedSyncPhase,
    ) : UnifiedSyncState

    data class Success(
        val provider: SyncBackendType,
        val timestamp: Long,
        val summary: String,
    ) : UnifiedSyncState

    data class Error(
        val error: UnifiedSyncError,
        val timestamp: Long,
    ) : UnifiedSyncState

    data class NotConfigured(
        val provider: SyncBackendType,
    ) : UnifiedSyncState

    data class ConflictDetected(
        val provider: SyncBackendType,
        val conflicts: SyncConflictSet,
        val isPreview: Boolean = false,
    ) : UnifiedSyncState
}

sealed interface UnifiedSyncResult {
    val provider: SyncBackendType

    data class Success(
        override val provider: SyncBackendType,
        val message: String,
    ) : UnifiedSyncResult

    data class Error(
        override val provider: SyncBackendType,
        val error: UnifiedSyncError,
    ) : UnifiedSyncResult

    data class NotConfigured(
        override val provider: SyncBackendType,
        val error: UnifiedSyncError,
    ) : UnifiedSyncResult

    data class Conflict(
        override val provider: SyncBackendType,
        val message: String,
        val conflicts: SyncConflictSet,
    ) : UnifiedSyncResult
}
