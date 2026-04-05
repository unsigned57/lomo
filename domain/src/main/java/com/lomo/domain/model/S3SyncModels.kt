package com.lomo.domain.model

import java.time.Instant

enum class S3SyncErrorCode {
    NOT_CONFIGURED,
    CONNECTION_FAILED,
    AUTH_FAILED,
    BUCKET_ACCESS_FAILED,
    ENCRYPTION_FAILED,
    UNKNOWN,
}

enum class S3EncryptionMode {
    NONE,
    RCLONE_CRYPT,
}

enum class S3RcloneFilenameEncryption {
    STANDARD,
    OBFUSCATE,
    OFF,
}

enum class S3RcloneFilenameEncoding {
    BASE32,
    BASE64,
    BASE32768,
}

data class S3RcloneCryptConfig(
    val filenameEncryption: S3RcloneFilenameEncryption = S3RcloneFilenameEncryption.STANDARD,
    val directoryNameEncryption: Boolean = true,
    val filenameEncoding: S3RcloneFilenameEncoding = S3RcloneFilenameEncoding.BASE64,
    val dataEncryptionEnabled: Boolean = true,
    val encryptedSuffix: String = ".bin",
)

enum class S3PathStyle {
    AUTO,
    PATH_STYLE,
    VIRTUAL_HOSTED,
}

enum class S3SyncDirection {
    NONE,
    UPLOAD,
    DOWNLOAD,
    DELETE_LOCAL,
    DELETE_REMOTE,
    CONFLICT,
}

enum class S3SyncReason {
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

data class S3SyncOutcome(
    val path: String,
    val direction: S3SyncDirection,
    val reason: S3SyncReason,
)

data class S3SyncStatus(
    val remoteFileCount: Int,
    val localFileCount: Int,
    val pendingChanges: Int,
    val lastSyncTime: Long?,
)

sealed interface S3SyncResult {
    data class Success(
        val message: String,
        val outcomes: List<S3SyncOutcome> = emptyList(),
    ) : S3SyncResult

    data class Error(
        val code: S3SyncErrorCode,
        val message: String,
        val exception: Throwable? = null,
        val outcomes: List<S3SyncOutcome> = emptyList(),
    ) : S3SyncResult {
        constructor(
            message: String,
            exception: Throwable? = null,
            outcomes: List<S3SyncOutcome> = emptyList(),
        ) : this(
            code = s3SyncErrorCodeFromMessage(message),
            message = message,
            exception = exception,
            outcomes = outcomes,
        )
    }

    data object NotConfigured : S3SyncResult

    data class Conflict(
        val message: String,
        val conflicts: SyncConflictSet,
    ) : S3SyncResult
}

sealed interface S3SyncState {
    data object Idle : S3SyncState

    data object Initializing : S3SyncState

    data object Connecting : S3SyncState

    data object Listing : S3SyncState

    data object Uploading : S3SyncState

    data object Downloading : S3SyncState

    data object Deleting : S3SyncState

    data class Success(
        val timestamp: Long,
        val summary: String,
    ) : S3SyncState

    data class Error(
        val code: S3SyncErrorCode,
        val message: String,
        val timestamp: Long,
    ) : S3SyncState {
        constructor(
            message: String,
            timestamp: Long,
        ) : this(
            code = s3SyncErrorCodeFromMessage(message),
            message = message,
            timestamp = timestamp,
        )
    }

    data object NotConfigured : S3SyncState

    data class ConflictDetected(
        val conflicts: SyncConflictSet,
    ) : S3SyncState
}

class S3SyncFailureException(
    val code: S3SyncErrorCode,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

fun S3SyncStatus.observeLastSyncInstantOrNull(): Instant? = lastSyncTime?.let(Instant::ofEpochMilli)

private fun s3SyncErrorCodeFromMessage(rawMessage: String?): S3SyncErrorCode {
    val normalized = rawMessage?.trim().orEmpty()
    return when {
        normalized.isBlank() -> S3SyncErrorCode.UNKNOWN
        normalized.contains("not configured", ignoreCase = true) -> S3SyncErrorCode.NOT_CONFIGURED
        normalized.contains("credential", ignoreCase = true) -> S3SyncErrorCode.AUTH_FAILED
        normalized.contains("auth", ignoreCase = true) -> S3SyncErrorCode.AUTH_FAILED
        normalized.contains("bucket", ignoreCase = true) -> S3SyncErrorCode.BUCKET_ACCESS_FAILED
        normalized.contains("encrypt", ignoreCase = true) -> S3SyncErrorCode.ENCRYPTION_FAILED
        normalized.contains("decrypt", ignoreCase = true) -> S3SyncErrorCode.ENCRYPTION_FAILED
        normalized.contains("connection", ignoreCase = true) -> S3SyncErrorCode.CONNECTION_FAILED
        else -> S3SyncErrorCode.UNKNOWN
    }
}
