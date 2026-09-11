package com.lomo.data.repository

import java.io.IOException
import kotlinx.serialization.SerializationException

enum class PendingSyncRestoreErrorCategory {
    BUDGET_EXHAUSTED,
    CREDENTIAL_FAILED,
    REMOTE_IO_FAILED,
    LOCAL_IO_FAILED,
    METADATA_FAILED,
    SERIALIZATION_FAILED,
    CONTRACT_VIOLATION,
    UNKNOWN,
}

internal fun Throwable.toPendingSyncRestoreError(): PendingSyncRestoreError {
    val category = toPendingSyncRestoreErrorCategory()
    val msg = diagnosticMessages().firstOrNull()
        ?: (category.name + ": " + (this::class.qualifiedName ?: this::class.simpleName ?: "Throwable"))
    return PendingSyncRestoreError(message = msg, cause = this, category = category.name)
}

private fun Throwable.toPendingSyncRestoreErrorCategory(): PendingSyncRestoreErrorCategory =
    when {
        this is SerializationException -> PendingSyncRestoreErrorCategory.SERIALIZATION_FAILED
        diagnosticText().isCredentialFailureText() -> PendingSyncRestoreErrorCategory.CREDENTIAL_FAILED
        diagnosticText().isMetadataFailureText() -> PendingSyncRestoreErrorCategory.METADATA_FAILED
        diagnosticText().isRemoteIoFailureText() -> PendingSyncRestoreErrorCategory.REMOTE_IO_FAILED
        this is IOException -> PendingSyncRestoreErrorCategory.LOCAL_IO_FAILED
        this is IllegalArgumentException || this is IllegalStateException ->
            PendingSyncRestoreErrorCategory.CONTRACT_VIOLATION
        else -> PendingSyncRestoreErrorCategory.UNKNOWN
    }

private fun Throwable.diagnosticText(): String =
    diagnosticMessages().ifEmpty {
        generateSequence(this) { it.cause }
            .mapNotNull { it::class.qualifiedName ?: it::class.simpleName }
            .toList()
    }.joinToString(separator = "\n")

private fun Throwable.diagnosticMessages(): List<String> =
    generateSequence(this) { it.cause }
        .mapNotNull { it.message?.trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .toList()

private fun String.isCredentialFailureText(): Boolean =
    contains("403") ||
        contains("access denied", ignoreCase = true) ||
        contains("credential", ignoreCase = true) ||
        contains("forbidden", ignoreCase = true) ||
        contains("auth", ignoreCase = true)

private fun String.isMetadataFailureText(): Boolean =
    contains("metadata", ignoreCase = true) || contains("database", ignoreCase = true)

private fun String.isRemoteIoFailureText(): Boolean =
    contains("http", ignoreCase = true) ||
        contains("network", ignoreCase = true) ||
        contains("connection", ignoreCase = true) ||
        contains("timeout", ignoreCase = true) ||
        contains("timed out", ignoreCase = true)
