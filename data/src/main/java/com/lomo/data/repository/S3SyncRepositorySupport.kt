package com.lomo.data.repository

import com.lomo.data.s3.LomoS3Client
import com.lomo.domain.model.CredentialField
import com.lomo.domain.model.S3SyncErrorCode
import com.lomo.domain.model.S3SyncFailureException
import com.lomo.domain.model.S3SyncResult
import com.lomo.domain.model.S3SyncState
import com.lomo.domain.model.S3RcloneCryptConfig
import com.lomo.domain.repository.CredentialRepository
import com.lomo.domain.model.CredentialSecretReadResult
import com.lomo.domain.repository.SecuritySessionPolicy
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

@Singleton
class S3SyncRepositorySupport
    @Inject
    constructor(
        private val runtime: S3SyncRepositoryContext,
        private val credentialRepository: CredentialRepository,
        private val securitySessionPolicy: SecuritySessionPolicy,
    ) {
        suspend fun <T> runS3Io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

        suspend fun resolveConfig(): S3ResolvedConfig? {
            val enabled = runtime.dataStore.s3SyncEnabled.first()
            val endpointUrl = runtime.dataStore.s3EndpointUrl.first()?.trim().orEmpty()
            val region = runtime.dataStore.s3Region.first()?.trim().orEmpty()
            val bucket = runtime.dataStore.s3Bucket.first()?.trim().orEmpty()
            val prefix = runtime.dataStore.s3Prefix.first()?.trim().orEmpty()
            if (!enabled || !s3ConfigHasRequiredFields(endpointUrl, region, bucket)) {
                return null
            }
            val accessKeyId =
                credentialRepository.readS3RequiredCredential(CredentialField.S3_ACCESS_KEY_ID)
            val secretAccessKey =
                credentialRepository.readS3RequiredCredential(CredentialField.S3_SECRET_ACCESS_KEY)
            val sessionToken =
                credentialRepository.readS3OptionalCredential(CredentialField.S3_SESSION_TOKEN, trim = true)
            val encryptionPassword =
                credentialRepository.readS3OptionalCredential(CredentialField.S3_ENCRYPTION_PASSWORD, trim = false)
            val encryptionPassword2 =
                credentialRepository.readS3OptionalCredential(CredentialField.S3_ENCRYPTION_PASSWORD2, trim = false)
            return S3ResolvedConfig(
                endpointUrl = endpointUrl,
                region = region,
                bucket = bucket,
                prefix = prefix,
                accessKeyId = accessKeyId.value,
                secretAccessKey = secretAccessKey.value,
                sessionToken = sessionToken.valueOrNull(),
                pathStyle = s3PathStyleFromPreference(runtime.dataStore.s3PathStyle.first()),
                encryptionMode = s3EncryptionModeFromPreference(runtime.dataStore.s3EncryptionMode.first()),
                encryptionPassword = encryptionPassword.valueOrNull(),
                endpointProfile = inferS3EndpointProfile(endpointUrl),
                encryptionPassword2 = encryptionPassword2.valueOrNull(),
                rcloneCryptConfig =
                    S3RcloneCryptConfig(
                        filenameEncryption =
                            s3RcloneFilenameEncryptionFromPreference(
                                runtime.dataStore.s3RcloneFilenameEncryption.first(),
                            ),
                        directoryNameEncryption = runtime.dataStore.s3RcloneDirectoryNameEncryption.first(),
                        filenameEncoding =
                            s3RcloneFilenameEncodingFromPreference(
                                runtime.dataStore.s3RcloneFilenameEncoding.first(),
                            ),
                        dataEncryptionEnabled = runtime.dataStore.s3RcloneDataEncryptionEnabled.first(),
                        encryptedSuffix =
                            s3RcloneEncryptedSuffixFromPreference(
                                runtime.dataStore.s3RcloneEncryptedSuffix.first(),
                            ),
                    ),
            )
        }

        suspend fun <T> withClient(
            config: S3ResolvedConfig,
            block: suspend (LomoS3Client) -> T,
        ): T =
            runS3Io {
                val client = createClient(config)
                try {
                    block(client)
                } finally {
                    client.close()
                }
            }

        internal fun createClient(config: S3ResolvedConfig): LomoS3Client {
            validateS3EndpointSecurity(config)
            validateS3EncryptionSupport(config)
            return runtime.clientFactory.create(config)
        }

        fun notConfiguredResult(): S3SyncResult {
            runtime.stateHolder.state.value = S3SyncState.NotConfigured
            return S3SyncResult.NotConfigured
        }

        fun mapError(error: Throwable): S3SyncResult.Error {
            val failure = error.toS3Failure(fallbackMessage = "S3 sync failed")
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
            val failure = error.toS3Failure(fallbackMessage = "S3 connection failed")
            return S3SyncResult.Error(
                code = failure.code,
                message = failure.message ?: "S3 connection failed",
                exception = error,
            )
        }

        private suspend fun CredentialRepository.readS3RequiredCredential(
            field: CredentialField,
        ): RequiredCredentialRead =
            readRequiredCredential(
                field = field,
                securitySessionPolicy = securitySessionPolicy,
                onMissing = {
                    throw S3SyncFailureException(
                        code = S3SyncErrorCode.AUTH_FAILED,
                        message = "S3 credential ${field.name} is missing",
                    )
                },
                onUnreadable = {
                    throw S3SyncFailureException(
                        code = S3SyncErrorCode.AUTH_FAILED,
                        message = "S3 credential ${field.name} is unreadable",
                    )
                },
                onUnauthorized = { denied ->
                    throw S3SyncFailureException(
                        code = S3SyncErrorCode.AUTH_FAILED,
                        message = s3CredentialDeniedMessage(denied),
                    )
                },
            )

        private suspend fun CredentialRepository.readS3OptionalCredential(
            field: CredentialField,
            trim: Boolean,
        ): OptionalCredentialRead =
            readOptionalCredential(
                field = field,
                securitySessionPolicy = securitySessionPolicy,
                trim = trim,
                onUnreadable = {
                    throw S3SyncFailureException(
                        code = S3SyncErrorCode.AUTH_FAILED,
                        message = "S3 credential ${field.name} is unreadable",
                    )
                },
                onUnauthorized = { denied ->
                    throw S3SyncFailureException(
                        code = S3SyncErrorCode.AUTH_FAILED,
                        message = s3CredentialDeniedMessage(denied),
                    )
                },
            )

    }

private fun s3ConfigHasRequiredFields(vararg values: String): Boolean = values.all(String::isNotBlank)

private fun OptionalCredentialRead.valueOrNull(): String? =
    when (this) {
        OptionalCredentialRead.Missing -> null
        is OptionalCredentialRead.Present -> value
    }

private fun s3CredentialDeniedMessage(denied: CredentialSecretReadResult.Unauthorized): String =
    "S3 credential read denied: ${denied.reason}"

private fun validateS3EncryptionSupport(config: S3ResolvedConfig) {
    if (config.encryptionMode != com.lomo.domain.model.S3EncryptionMode.NONE) {
        config.requireEncryptionPassword()
    }
}

private fun validateS3EndpointSecurity(config: S3ResolvedConfig) {
    // behavior-contract: silent-result-ok: malformed URI -> null; caller surfaces user-visible error
    val uri = runCatching { URI(config.endpointUrl) }.getOrNull()
    val failureMessage = endpointSecurityFailureMessage(uri, config.allowInsecureHttp)
    if (failureMessage != null) {
        throw S3SyncFailureException(
            code = S3SyncErrorCode.CONNECTION_FAILED,
            message = failureMessage,
        )
    }
}

private fun endpointSecurityFailureMessage(
    uri: URI?,
    allowInsecureHttp: Boolean,
): String? {
    if (uri == null || !uri.isAbsolute || uri.host.isNullOrBlank()) {
        return "S3 endpoint URL must be a valid absolute HTTP or HTTPS URL"
    }
    return when (uri.scheme?.lowercase(java.util.Locale.ROOT)) {
        "https" -> null
        "http" ->
            if (allowInsecureHttp) {
                null
            } else {
                "S3 endpoint must use HTTPS unless insecure HTTP is explicitly allowed"
            }
        else -> "S3 endpoint URL must use HTTP or HTTPS"
    }
}

internal fun S3ResolvedConfig.requireEncryptionPassword(): String =
    encryptionPassword?.takeIf(String::isNotBlank)
        ?: throw S3SyncFailureException(
            code = S3SyncErrorCode.ENCRYPTION_FAILED,
            message = "S3 encryption password is not configured",
        )
