package com.lomo.data.repository

import aws.smithy.kotlin.runtime.ServiceException
import aws.smithy.kotlin.runtime.http.middleware.HttpResponseException
import com.lomo.domain.model.S3SyncErrorCode
import com.lomo.domain.model.S3SyncFailureException

internal fun Throwable.toS3Failure(fallbackMessage: String): S3SyncFailureException =
    when (this) {
        is S3SyncFailureException -> {
            val classifiedCode =
                code.takeUnless { it == S3SyncErrorCode.UNKNOWN }
                    ?: classifyS3ErrorCode()
            val publicMessage =
                message
                    ?.trim()
                    ?.takeIf(::isSafePublicS3DiagnosticMessage)
                    ?: safeS3DiagnosticMessage(classifiedCode, fallbackMessage)
            S3SyncFailureException(
                code = classifiedCode,
                message = publicMessage,
                cause = cause,
            )
        }
        is IllegalArgumentException ->
            S3SyncFailureException(
                code = S3SyncErrorCode.ENCRYPTION_FAILED,
                message = safeS3DiagnosticMessage(S3SyncErrorCode.ENCRYPTION_FAILED, "S3 encryption failed"),
                cause = this,
            )
        else -> {
            val classifiedCode = classifyS3ErrorCode()
            S3SyncFailureException(
                code = classifiedCode,
                message = safeS3DiagnosticMessage(classifiedCode, fallbackMessage),
                cause = this,
            )
        }
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

private fun safeS3DiagnosticMessage(
    code: S3SyncErrorCode,
    fallbackMessage: String,
): String =
    when (code) {
        S3SyncErrorCode.CONNECTION_FAILED ->
            "S3 connection failed or timed out. Check the endpoint URL, region, addressing style, and network/TLS connectivity."
        S3SyncErrorCode.AUTH_FAILED ->
            "S3 credential or permission check failed. Check credentials and bucket permissions."
        S3SyncErrorCode.BUCKET_ACCESS_FAILED ->
            "S3 bucket access failed. Check the bucket name, region, endpoint, and permissions."
        S3SyncErrorCode.REMOTE_LAYOUT_VIOLATION ->
            "S3 remote layout is incompatible with the configured sync scope. Check the configured prefix and rebuild the pending sync session."
        S3SyncErrorCode.ENCRYPTION_FAILED ->
            "S3 encryption compatibility failed. Check the S3 prefix, encryption mode, and encryption password."
        S3SyncErrorCode.NOT_CONFIGURED ->
            "S3 sync is not configured. Configure endpoint, region, bucket, and credentials before syncing."
        S3SyncErrorCode.UNKNOWN ->
            safeUnknownS3DiagnosticMessage(fallbackMessage)
    }

private fun safeUnknownS3DiagnosticMessage(fallbackMessage: String): String =
    when {
        fallbackMessage.contains("connection", ignoreCase = true) ->
            "S3 connection could not be verified. Check endpoint, credentials, bucket access, and encryption settings."
        fallbackMessage.contains("sync", ignoreCase = true) ->
            "S3 sync failed. Check endpoint, credentials, bucket access, and encryption settings."
        else ->
            "S3 operation failed. Check endpoint, credentials, bucket access, and encryption settings."
    }

private fun isSafePublicS3DiagnosticMessage(message: String): Boolean {
    val normalized = message.trim()
    if (normalized.isBlank()) return false
    val lower = normalized.lowercase()
    val rawMarkers =
        listOf(
            "http://",
            "https://",
            "request url",
            "request id",
            "sample key",
            "decoder detail",
            "for prefix '",
            "under prefix '",
            "ignored samples",
            "key '",
            "prefix/",
            "httpresponseexception",
        )
    return rawMarkers.none(lower::contains) &&
        !RAW_PROVIDER_NAME_PATTERN.containsMatchIn(normalized) &&
        !RAW_HTTP_STATUS_PATTERN.containsMatchIn(normalized)
}

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
    val parts =
        listOfNotNull(
            status?.let { "HTTP $it" },
        )
    return parts.takeIf(List<String>::isNotEmpty)?.joinToString(". ")
}

private val RAW_PROVIDER_NAME_PATTERN = Regex("""\b(?:AWS|MinIO|Wasabi|Backblaze|Cloudflare R2)\b""")
private val RAW_HTTP_STATUS_PATTERN = Regex("""\b[45]\d{2}\b""")
