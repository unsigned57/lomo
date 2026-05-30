package com.lomo.data.repository

import aws.smithy.kotlin.runtime.ServiceException
import aws.smithy.kotlin.runtime.http.middleware.HttpResponseException
import com.lomo.domain.model.S3SyncErrorCode
import com.lomo.domain.model.S3SyncFailureException

internal fun Throwable.toS3Failure(fallbackMessage: String): S3SyncFailureException =
    when (this) {
        is S3SyncFailureException ->
            S3SyncFailureException(
                code =
                    code.takeUnless { it == S3SyncErrorCode.UNKNOWN }
                        ?: classifyS3ErrorCode(),
                message = diagnosticMessage(fallbackMessage),
                cause = cause,
            )
        is IllegalArgumentException ->
            S3SyncFailureException(
                code = S3SyncErrorCode.ENCRYPTION_FAILED,
                message = diagnosticMessage("S3 encryption failed"),
                cause = this,
            )
        else ->
            S3SyncFailureException(
                code = classifyS3ErrorCode(),
                message = diagnosticMessage(fallbackMessage),
                cause = this,
            )
    }

private fun Throwable.classifyS3ErrorCode(): S3SyncErrorCode {
    val message = diagnosticText()
    return when {
        message.contains("403") ||
            message.contains("access denied", ignoreCase = true) ||
            message.contains("invalidaccesskeyid", ignoreCase = true) ||
            message.contains("signaturedoesnotmatch", ignoreCase = true) ||
            message.contains("accesskey", ignoreCase = true) ||
            message.contains("credential", ignoreCase = true) ||
            message.contains("forbidden", ignoreCase = true) -> S3SyncErrorCode.AUTH_FAILED
        message.contains("404") ||
            message.contains("bucket", ignoreCase = true) ||
            message.contains("nosuchbucket", ignoreCase = true) ||
            message.contains("notfound", ignoreCase = true) -> S3SyncErrorCode.BUCKET_ACCESS_FAILED
        message.contains("timeout", ignoreCase = true) ||
            message.contains("timed out", ignoreCase = true) ||
            message.contains("connection", ignoreCase = true) ||
            message.contains("network", ignoreCase = true) ||
            message.contains("tls", ignoreCase = true) ||
            message.contains("ssl", ignoreCase = true) -> S3SyncErrorCode.CONNECTION_FAILED
        else -> S3SyncErrorCode.UNKNOWN
    }
}

private fun Throwable.diagnosticMessage(fallbackMessage: String): String =
    diagnosticMessages().firstOrNull(::isActionableS3DiagnosticMessage)
        ?: diagnosticMessages().firstOrNull()
        ?: diagnosticTypeSummary()
        ?: fallbackMessage

private fun Throwable.diagnosticText(): String =
    diagnosticMessages()
        .ifEmpty {
            generateSequence(this) { it.cause }
                .mapNotNull { throwable -> throwable::class.qualifiedName }
                .toList()
        }.joinToString(separator = "\n")

private fun Throwable.diagnosticMessages(): List<String> =
    generateSequence(this) { it.cause }
        .flatMap { throwable -> throwable.diagnosticCandidates().asSequence() }
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
        .toList()

private fun Throwable.diagnosticTypeSummary(): String? =
    generateSequence(this) { it.cause }
        .mapNotNull { throwable ->
            throwable::class.simpleName
                ?: throwable::class.qualifiedName?.substringAfterLast('.')
        }.filter(String::isNotBlank)
        .distinct()
        .toList()
        .takeIf(List<String>::isNotEmpty)
        ?.joinToString(separator = " <- ")

private fun Throwable.diagnosticCandidates(): List<String> =
    buildList {
        message?.let(::add)
        sdkServiceDiagnosticMessage()?.let(::add)
        httpResponseDiagnosticMessage()?.let(::add)
    }

private fun Throwable.sdkServiceDiagnosticMessage(): String? {
    val serviceException = this as? ServiceException ?: return null
    val metadata = serviceException.sdkErrorMetadata
    val parts =
        listOfNotNull(
            metadata.errorMessage?.trim()?.takeIf(String::isNotBlank),
            metadata.errorCode?.trim()?.takeIf(String::isNotBlank)?.let { "AWS error code: $it" },
            metadata.protocolResponse.summary.trim().takeIf(String::isNotBlank),
            metadata.requestId?.trim()?.takeIf(String::isNotBlank)?.let { "Request ID: $it" },
        )
    return parts.takeIf(List<String>::isNotEmpty)?.joinToString(". ")
}

private fun Throwable.httpResponseDiagnosticMessage(): String? {
    val responseException = this as? HttpResponseException ?: return null
    val status = responseException.statusCode?.toString()?.trim()?.takeIf(String::isNotBlank)
    val requestUrl = responseException.request?.url?.toString()?.trim()?.takeIf(String::isNotBlank)
    val parts =
        listOfNotNull(
            status?.let { "HTTP $it" },
            requestUrl?.let { "Request URL: $it" },
        )
    return parts.takeIf(List<String>::isNotEmpty)?.joinToString(". ")
}

private fun isActionableS3DiagnosticMessage(message: String): Boolean {
    val normalized = message.trim()
    return normalized.isNotBlank() &&
        !normalized.equals("S3 sync failed", ignoreCase = true) &&
        !normalized.equals("S3 connection failed", ignoreCase = true)
}
