package com.lomo.data.repository

import com.lomo.domain.model.S3SyncErrorCode
import com.lomo.domain.model.S3SyncFailureException
import com.lomo.domain.model.WebDavSyncErrorCode
import com.lomo.domain.model.WebDavSyncFailureException
import java.io.IOException
import kotlinx.serialization.SerializationException

internal fun Throwable.toPendingSyncRestoreError(): PendingSyncRestoreError {
    val category = toPendingSyncRestoreErrorCategory()
    return PendingSyncRestoreError(
        category = category,
        message = pendingSyncRestoreErrorMessage(category),
        cause = this,
    )
}

private fun Throwable.toPendingSyncRestoreErrorCategory(): PendingSyncRestoreErrorCategory =
    when {
        this is RemoteSyncBudgetExceededException -> PendingSyncRestoreErrorCategory.BUDGET_EXHAUSTED
        this is S3SyncFailureException && code == S3SyncErrorCode.AUTH_FAILED ->
            PendingSyncRestoreErrorCategory.CREDENTIAL_FAILED
        this is WebDavSyncFailureException && code == WebDavSyncErrorCode.CONNECTION_FAILED ->
            PendingSyncRestoreErrorCategory.REMOTE_IO_FAILED
        this is SerializationException -> PendingSyncRestoreErrorCategory.SERIALIZATION_FAILED
        diagnosticText().isCredentialFailureText() -> PendingSyncRestoreErrorCategory.CREDENTIAL_FAILED
        diagnosticText().isMetadataFailureText() -> PendingSyncRestoreErrorCategory.METADATA_FAILED
        diagnosticText().isRemoteIoFailureText() -> PendingSyncRestoreErrorCategory.REMOTE_IO_FAILED
        this is IOException -> PendingSyncRestoreErrorCategory.LOCAL_IO_FAILED
        this is IllegalArgumentException || this is IllegalStateException ->
            PendingSyncRestoreErrorCategory.CONTRACT_VIOLATION
        else -> PendingSyncRestoreErrorCategory.UNKNOWN
    }

private fun Throwable.pendingSyncRestoreErrorMessage(category: PendingSyncRestoreErrorCategory): String =
    diagnosticMessages().firstOrNull()
        ?: "${category.name}: ${this::class.qualifiedName ?: this::class.simpleName ?: "Throwable"}"

private fun Throwable.diagnosticText(): String =
    diagnosticMessages()
        .ifEmpty {
            generateSequence(this) { throwable -> throwable.cause }
                .mapNotNull { throwable -> throwable::class.qualifiedName ?: throwable::class.simpleName }
                .toList()
        }.joinToString(separator = "\n")

private fun Throwable.diagnosticMessages(): List<String> =
    generateSequence(this) { throwable -> throwable.cause }
        .mapNotNull { throwable -> throwable.message?.trim() }
        .filter(String::isNotBlank)
        .distinct()
        .toList()

private fun String.isCredentialFailureText(): Boolean =
    contains("403") ||
        contains("access denied", ignoreCase = true) ||
        contains("invalidaccesskeyid", ignoreCase = true) ||
        contains("signaturedoesnotmatch", ignoreCase = true) ||
        contains("credential", ignoreCase = true) ||
        contains("forbidden", ignoreCase = true) ||
        contains("auth", ignoreCase = true)

private fun String.isMetadataFailureText(): Boolean =
    contains("metadata", ignoreCase = true) ||
        contains("database", ignoreCase = true) ||
        contains("sqlite", ignoreCase = true) ||
        contains("room", ignoreCase = true)

private fun String.isRemoteIoFailureText(): Boolean =
    contains("http", ignoreCase = true) ||
        contains("network", ignoreCase = true) ||
        contains("connection", ignoreCase = true) ||
        contains("timeout", ignoreCase = true) ||
        contains("timed out", ignoreCase = true)
