package com.lomo.domain.model

enum class GitSyncErrorCode {
    NOT_CONFIGURED,
    PAT_REQUIRED,
    DIRECT_PATH_REQUIRED,
    REMOTE_URL_NOT_CONFIGURED,
    MEMO_DIRECTORY_NOT_CONFIGURED,
    NOT_A_GIT_REPOSITORY,
    CONFLICT,
    UNKNOWN,
}

sealed interface SyncEngineState {
    data object Idle : SyncEngineState

    sealed interface Syncing : SyncEngineState {
        data object Pulling : Syncing

        data object Committing : Syncing

        data object Pushing : Syncing
    }

    data class Success(
        val timestamp: Long,
        val summary: String,
    ) : SyncEngineState

    data class Error(
        val code: GitSyncErrorCode,
        val message: String,
        val timestamp: Long,
    ) : SyncEngineState {
        constructor(
            message: String,
            timestamp: Long,
        ) : this(
            code = gitSyncErrorCodeFromMessage(message),
            message = message,
            timestamp = timestamp,
        )
    }

    data object NotConfigured : SyncEngineState

    data object Initializing : SyncEngineState

    data class ConflictDetected(
        val conflicts: SyncConflictSet,
    ) : SyncEngineState
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

    data class Error(
        val code: GitSyncErrorCode,
        val message: String,
        val exception: Throwable? = null,
    ) : GitSyncResult {
        constructor(
            message: String,
            exception: Throwable? = null,
        ) : this(
            code = gitSyncErrorCodeFromMessage(message),
            message = message,
            exception = exception,
        )
    }

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

private fun gitSyncErrorCodeFromMessage(rawMessage: String?): GitSyncErrorCode {
    val normalized = rawMessage?.trim().orEmpty()
    return when {
        normalized.isBlank() -> GitSyncErrorCode.UNKNOWN
        normalized.equals("Git sync is not configured", ignoreCase = true) ->
            GitSyncErrorCode.NOT_CONFIGURED
        normalized.equals("No Personal Access Token configured", ignoreCase = true) ->
            GitSyncErrorCode.PAT_REQUIRED
        normalized.startsWith("Git sync requires direct path mode", ignoreCase = true) ||
            normalized.startsWith("Git sync requires a direct local directory path", ignoreCase = true) ->
            GitSyncErrorCode.DIRECT_PATH_REQUIRED
        normalized.equals("Repository URL is not configured", ignoreCase = true) ->
            GitSyncErrorCode.REMOTE_URL_NOT_CONFIGURED
        normalized.equals("Memo directory is not configured", ignoreCase = true) ->
            GitSyncErrorCode.MEMO_DIRECTORY_NOT_CONFIGURED
        normalized.startsWith("Not a git repository", ignoreCase = true) ->
            GitSyncErrorCode.NOT_A_GIT_REPOSITORY
        gitSyncLooksLikeConflict(normalized) -> GitSyncErrorCode.CONFLICT
        else -> GitSyncErrorCode.UNKNOWN
    }
}

private fun gitSyncLooksLikeConflict(message: String): Boolean =
    message.contains("rebase STOPPED", ignoreCase = true) ||
        message.contains("resolve conflicts manually", ignoreCase = true) ||
        (
            message.contains("rebase", ignoreCase = true) &&
                message.contains("preserved", ignoreCase = true)
        )
