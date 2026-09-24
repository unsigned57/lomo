package com.lomo.domain.model

enum class GitSyncErrorCode {
    NOT_CONFIGURED,
    PAT_REQUIRED,
    CREDENTIAL_UNREADABLE,
    CREDENTIAL_UNAUTHORIZED,
    DIRECT_PATH_REQUIRED,
    REMOTE_URL_NOT_CONFIGURED,
    MEMO_DIRECTORY_NOT_CONFIGURED,
    NOT_A_GIT_REPOSITORY,
    CONFLICT,
    UNKNOWN,
}

data class GitSyncStatus(
    val hasLocalChanges: Boolean,
    val aheadCount: Int,
    val behindCount: Int,
    val lastSyncTime: Long?,
)

sealed interface GitSyncResult {
    data class Success(
        val message: String,
    ) : GitSyncResult

    /**
     * WorkManager accepted the enqueue — proves admission only.
     * The durable Rust cycle record owns the terminal outcome.
     */
    data class Accepted(
        val message: String,
    ) : GitSyncResult

    data class Error(
        val code: GitSyncErrorCode,
        val message: String,
        val exception: Throwable? = null,
    ) : GitSyncResult

    data object NotConfigured : GitSyncResult

    data object DirectPathRequired : GitSyncResult

    data class Conflict(
        val message: String,
        val conflicts: SyncConflictSet,
    ) : GitSyncResult
}

class GitSyncFailureException(
    val code: GitSyncErrorCode,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
