package com.lomo.domain.model

enum class WebDavSyncErrorCode {
    NOT_CONFIGURED,
    CONNECTION_FAILED,
    UNKNOWN,
}

enum class WebDavProvider {
    NUTSTORE,
    NEXTCLOUD,
    CUSTOM,
}

enum class WebDavSyncDirection {
    NONE,
    UPLOAD,
    DOWNLOAD,
    DELETE_LOCAL,
    DELETE_REMOTE,
    CONFLICT,
}

enum class WebDavSyncReason {
    UNCHANGED,
    LOCAL_ONLY,
    REMOTE_ONLY,
    LOCAL_NEWER,
    REMOTE_NEWER,
    LOCAL_DELETED,
    REMOTE_DELETED,
    SAME_TIMESTAMP,
    CONFLICT,
}

data class WebDavSyncOutcome(
    val path: String,
    val direction: WebDavSyncDirection,
    val reason: WebDavSyncReason,
)

data class WebDavSyncStatus(
    val remoteFileCount: Int,
    val localFileCount: Int,
    val pendingChanges: Int,
    val lastSyncTime: Long?,
)

sealed interface WebDavSyncResult {
    data class Success(
        val message: String,
        val outcomes: List<WebDavSyncOutcome> = emptyList(),
    ) : WebDavSyncResult

    data class Error(
        val code: WebDavSyncErrorCode,
        val message: String,
        val exception: Throwable? = null,
        val outcomes: List<WebDavSyncOutcome> = emptyList(),
    ) : WebDavSyncResult {
        constructor(
            message: String,
            exception: Throwable? = null,
            outcomes: List<WebDavSyncOutcome> = emptyList(),
        ) : this(
            code = webDavSyncErrorCodeFromMessage(message),
            message = message,
            exception = exception,
            outcomes = outcomes,
        )
    }

    data object NotConfigured : WebDavSyncResult

    data class Conflict(
        val message: String,
        val conflicts: SyncConflictSet,
    ) : WebDavSyncResult
}

sealed interface WebDavSyncState {
    data object Idle : WebDavSyncState

    data object Initializing : WebDavSyncState

    data object Connecting : WebDavSyncState

    data object Listing : WebDavSyncState

    data object Uploading : WebDavSyncState

    data object Downloading : WebDavSyncState

    data object Deleting : WebDavSyncState

    data class Success(
        val timestamp: Long,
        val summary: String,
    ) : WebDavSyncState

    data class Error(
        val code: WebDavSyncErrorCode,
        val message: String,
        val timestamp: Long,
    ) : WebDavSyncState {
        constructor(
            message: String,
            timestamp: Long,
        ) : this(
            code = webDavSyncErrorCodeFromMessage(message),
            message = message,
            timestamp = timestamp,
        )
    }

    data object NotConfigured : WebDavSyncState

    data class ConflictDetected(
        val conflicts: SyncConflictSet,
    ) : WebDavSyncState
}

class WebDavSyncFailureException(
    val code: WebDavSyncErrorCode,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

private fun webDavSyncErrorCodeFromMessage(rawMessage: String?): WebDavSyncErrorCode {
    val normalized = rawMessage?.trim().orEmpty()
    return when {
        normalized.isBlank() -> WebDavSyncErrorCode.UNKNOWN
        normalized.contains("not configured", ignoreCase = true) -> WebDavSyncErrorCode.NOT_CONFIGURED
        normalized.contains("connection", ignoreCase = true) -> WebDavSyncErrorCode.CONNECTION_FAILED
        else -> WebDavSyncErrorCode.UNKNOWN
    }
}
