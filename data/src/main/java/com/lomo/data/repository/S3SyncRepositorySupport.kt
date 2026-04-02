package com.lomo.data.repository

import com.lomo.data.s3.LomoS3Client
import com.lomo.domain.model.S3SyncErrorCode
import com.lomo.domain.model.S3SyncFailureException
import com.lomo.domain.model.S3SyncResult
import com.lomo.domain.model.S3SyncState
import aws.smithy.kotlin.runtime.ServiceException
import aws.smithy.kotlin.runtime.http.middleware.HttpResponseException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class S3SyncRepositorySupport
    @Inject
    constructor(
        private val runtime: S3SyncRepositoryContext,
    ) {
        suspend fun <T> runS3Io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

        suspend fun resolveConfig(): S3ResolvedConfig? {
            val endpointUrl = runtime.dataStore.s3EndpointUrl.first()?.trim().orEmpty()
            val region = runtime.dataStore.s3Region.first()?.trim().orEmpty()
            val bucket = runtime.dataStore.s3Bucket.first()?.trim().orEmpty()
            val prefix = runtime.dataStore.s3Prefix.first()?.trim().orEmpty()
            val accessKeyId = runtime.credentialStore.getAccessKeyId()?.trim().orEmpty()
            val secretAccessKey = runtime.credentialStore.getSecretAccessKey()?.trim().orEmpty()
            val sessionToken = runtime.credentialStore.getSessionToken()?.trim()?.takeIf(String::isNotBlank)
            val encryptionPassword = runtime.credentialStore.getEncryptionPassword()?.takeIf(String::isNotBlank)
            val enabled = runtime.dataStore.s3SyncEnabled.first()
            if (!enabled || !hasRequiredFields(endpointUrl, region, bucket, accessKeyId, secretAccessKey)) {
                return null
            }
            return S3ResolvedConfig(
                endpointUrl = endpointUrl,
                region = region,
                bucket = bucket,
                prefix = prefix,
                accessKeyId = accessKeyId,
                secretAccessKey = secretAccessKey,
                sessionToken = sessionToken,
                pathStyle = s3PathStyleFromPreference(runtime.dataStore.s3PathStyle.first()),
                encryptionMode = s3EncryptionModeFromPreference(runtime.dataStore.s3EncryptionMode.first()),
                encryptionPassword = encryptionPassword,
            )
        }

        suspend fun <T> withClient(
            config: S3ResolvedConfig,
            block: suspend (LomoS3Client) -> T,
        ): T =
            runS3Io {
                validateEncryptionSupport(config)
                val client = runtime.clientFactory.create(config)
                try {
                    block(client)
                } finally {
                    client.close()
                }
            }

        fun notConfiguredResult(): S3SyncResult {
            runtime.stateHolder.state.value = S3SyncState.NotConfigured
            return S3SyncResult.NotConfigured
        }

        fun mapError(error: Throwable): S3SyncResult.Error {
            val failure = toFailure(error, fallbackMessage = "S3 sync failed")
            runtime.stateHolder.state.value =
                S3SyncState.Error(
                    failure.message ?: "S3 sync failed",
                    System.currentTimeMillis(),
                )
            return S3SyncResult.Error(
                code = failure.code,
                message = failure.message ?: "S3 sync failed",
                exception = error,
            )
        }

        fun mapConnectionTestError(error: Throwable): S3SyncResult.Error {
            val failure = toFailure(error, fallbackMessage = "S3 connection failed")
            return S3SyncResult.Error(
                code = failure.code,
                message = failure.message ?: "S3 connection failed",
                exception = error,
            )
        }

        private fun toFailure(
            error: Throwable,
            fallbackMessage: String,
        ): S3SyncFailureException =
            when (error) {
                is S3SyncFailureException ->
                    S3SyncFailureException(
                        code =
                            error.code.takeUnless { it == S3SyncErrorCode.UNKNOWN }
                                ?: classifyErrorCode(error),
                        message = error.diagnosticMessage(fallbackMessage),
                        cause = error.cause,
                    )
                is IllegalArgumentException ->
                    S3SyncFailureException(
                        code = S3SyncErrorCode.ENCRYPTION_FAILED,
                        message = error.diagnosticMessage("S3 encryption failed"),
                        cause = error,
                    )

                else ->
                    S3SyncFailureException(
                        code = classifyErrorCode(error),
                        message = error.diagnosticMessage(fallbackMessage),
                        cause = error,
                    )
            }

        private fun classifyErrorCode(error: Throwable): S3SyncErrorCode {
            val message = error.diagnosticText()
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

        private fun hasRequiredFields(vararg values: String): Boolean = values.all(String::isNotBlank)

        private fun validateEncryptionSupport(config: S3ResolvedConfig) {
            if (config.encryptionMode != com.lomo.domain.model.S3EncryptionMode.NONE) {
                config.requireEncryptionPassword()
            }
        }
    }

internal fun S3ResolvedConfig.requireEncryptionPassword(): String =
    encryptionPassword?.takeIf(String::isNotBlank)
        ?: throw S3SyncFailureException(
            code = S3SyncErrorCode.ENCRYPTION_FAILED,
            message = "S3 encryption password is not configured",
        )

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
