package com.lomo.app.feature.common

import com.lomo.domain.model.EngineCommandFailure
import com.lomo.domain.model.EngineCommandFailureException
import com.lomo.domain.model.EngineDiagnosticEvent
import com.lomo.domain.model.EngineDiagnosticsRecorder
import com.lomo.domain.model.EngineFailureCategory
import com.lomo.domain.model.EngineRetryDisposition
import com.lomo.domain.model.GitSyncFailureException
import com.lomo.domain.model.S3SyncFailureException
import com.lomo.domain.model.WebDavSyncFailureException
import com.lomo.domain.model.markdown.MarkdownRenderContractException
import com.lomo.domain.model.sanitizeDiagnosticText
import com.lomo.domain.usecase.MigrationEnvelopeException
import com.lomo.domain.usecase.MigrationPasswordException
import kotlinx.coroutines.CancellationException
import java.io.FileNotFoundException
import java.io.IOException
import java.util.Locale

/**
 * Renders one failure for the user.
 *
 * A typed rejection is rendered by its stable code. Raw engine diagnostics can contain workspace
 * paths or memo text.
 */
internal fun Throwable.toUserMessage(
    prefix: String? = null,
    sanitizer: ((rawMessage: String?, fallbackMessage: String) -> String)? = null,
): String {
    val fallback = prefix?.trim().orEmpty().ifBlank { null }
    val classified = knownFailure()
    if (classified != null) {
        return if (fallback == null) classified.code else "$fallback: ${classified.code}"
    }
    val sanitized =
        if (fallback != null && sanitizer != null) {
            sanitizer(message, fallback).trim().ifBlank { fallback }
        } else {
            null
        }

    return sanitized
        ?: when {
            fallback == null && message.isNullOrBlank() -> "unexpected_error"
            fallback == null -> message.orEmpty()
            message.isNullOrBlank() -> "$fallback: unexpected_error"
            else -> "$fallback: $message"
        }
}

internal fun EngineDiagnosticsRecorder.recordApplicationFailure(
    operation: String,
    error: Throwable,
): EngineCommandFailure {
    if (error is CancellationException) throw error
    val traceSummary = buildSanitizedCauseChain(error)
    val failure = error.knownFailure(traceSummary) ?: diagnosticFailure(
        code = when (error) {
            is IllegalArgumentException -> "invalid_argument"
            is IllegalStateException -> "invalid_state"
            is UnsupportedOperationException -> "unsupported_operation"
            else -> "unexpected_error"
        },
        category = EngineFailureCategory.INTERNAL,
        rawText = traceSummary,
    )
    record(
        EngineDiagnosticEvent.Rejected(
            label = operation,
            durationMillis = null,
            failure = failure,
            causeSummary = traceSummary,
        ),
    )
    return failure
}

internal fun EngineDiagnosticsRecorder.reportUserFailure(
    operation: String,
    error: Throwable,
    prefix: String? = null,
    sanitizer: ((rawMessage: String?, fallbackMessage: String) -> String)? = null,
): String {
    recordApplicationFailure(operation, error)
    return error.toUserMessage(prefix, sanitizer)
}

private fun Throwable.knownFailure(fallbackRawText: String = ""): EngineCommandFailure? {
    val causes = generateSequence(this) { it.cause }.take(16)
    val nativeFailure = causes.filterIsInstance<EngineCommandFailureException>().firstOrNull()
    if (nativeFailure != null) return nativeFailure.failure
    return causes.firstNotNullOfOrNull { error ->
        when (error) {
            is MarkdownRenderContractException -> diagnosticFailure(
                code = error.code,
                category = EngineFailureCategory.VALIDATION,
                rawText = fallbackRawText,
            )

            is GitSyncFailureException -> diagnosticFailure(
                code = "git.${error.code.name.lowercase(Locale.ROOT)}",
                category = EngineFailureCategory.STORAGE,
                rawText = fallbackRawText,
            )

            is WebDavSyncFailureException -> diagnosticFailure(
                code = "webdav.${error.code.name.lowercase(Locale.ROOT)}",
                category = EngineFailureCategory.STORAGE,
                rawText = fallbackRawText,
            )

            is S3SyncFailureException -> diagnosticFailure(
                code = "s3.${error.code.name.lowercase(Locale.ROOT)}",
                category = EngineFailureCategory.STORAGE,
                rawText = fallbackRawText,
            )

            is MigrationPasswordException -> diagnosticFailure(
                code = "migration_password_invalid",
                category = EngineFailureCategory.AUTHENTICATION,
                rawText = fallbackRawText,
            )

            is MigrationEnvelopeException -> diagnosticFailure(
                code = "migration_envelope_invalid",
                category = EngineFailureCategory.VALIDATION,
                rawText = fallbackRawText,
            )

            is SecurityException -> diagnosticFailure(
                code = "access_denied",
                category = EngineFailureCategory.PERMISSION,
                rawText = fallbackRawText,
            )

            is FileNotFoundException -> diagnosticFailure(
                code = "file_unavailable",
                category = EngineFailureCategory.STORAGE,
                rawText = fallbackRawText,
            )

            is IOException -> diagnosticFailure(
                code = "io_failed",
                category = EngineFailureCategory.STORAGE,
                rawText = fallbackRawText,
            )

            else -> null
        }
    }
}

private fun buildSanitizedCauseChain(error: Throwable): String {
    val frames =
        generateSequence(error) { it.cause }
            .take(6)
            .map { throwable ->
                val simpleName = throwable.javaClass.simpleName.ifBlank { "Throwable" }
                val rawText = throwable.message?.trim().orEmpty()
                if (rawText.isEmpty()) simpleName else "$simpleName: $rawText"
            }
            .distinct()
            .toList()
    return sanitizeDiagnosticText(frames.joinToString(separator = " <- "))
}

private fun diagnosticFailure(
    code: String,
    category: EngineFailureCategory,
    rawText: String = "",
): EngineCommandFailure = EngineCommandFailure(
    category = category,
    code = code,
    retryDisposition = EngineRetryDisposition.AFTER_USER_ACTION,
    operationId = null,
    jobId = null,
    diagnostic = rawText,
)
