package com.lomo.data.repository

import com.lomo.domain.model.S3SyncErrorCode
import com.lomo.domain.model.S3SyncFailureException
import javax.inject.Inject
import javax.inject.Singleton

@JvmInline
value class S3RemoteObjectKey private constructor(
    val value: String,
) {
    companion object {
        internal fun trusted(value: String): S3RemoteObjectKey = S3RemoteObjectKey(value)
    }
}

class ConfiguredS3Prefix internal constructor(
    val value: String,
) {
    fun contains(key: String): Boolean =
        value.isEmpty() || key.startsWith(value)
}

@Singleton
class S3RemoteObjectKeyPolicy
    @Inject
    constructor(
        private val encodingSupport: S3SyncEncodingSupport,
    ) {
        fun configuredPrefix(config: S3ResolvedConfig): ConfiguredS3Prefix =
            ConfiguredS3Prefix(encodingSupport.remoteKeyPrefix(config))

        fun validatedExistingKey(
            key: String,
            config: S3ResolvedConfig,
        ): S3RemoteObjectKey {
            val prefix = configuredPrefix(config)
            if (!prefix.contains(key)) {
                throw S3SyncFailureException(
                    code = S3SyncErrorCode.REMOTE_LAYOUT_VIOLATION,
                    message = "S3 remote layout key is outside the configured prefix",
                )
            }
            return S3RemoteObjectKey.trusted(key)
        }

        fun newKeyFor(
            relativePath: String,
            config: S3ResolvedConfig,
        ): S3RemoteObjectKey =
            S3RemoteObjectKey.trusted(encodingSupport.remotePathFor(relativePath, config))

        fun resolveOperationKey(
            relativePath: String,
            config: S3ResolvedConfig,
            remoteFile: RemoteS3File?,
            metadata: com.lomo.data.local.entity.S3SyncMetadataEntity?,
        ): S3RemoteObjectKey =
            when {
                remoteFile != null -> validatedExistingKey(remoteFile.remotePath, config)
                metadata != null -> validatedExistingKey(metadata.remotePath, config)
                else -> newKeyFor(relativePath, config)
            }

        fun resolveOperationKey(
            relativePath: String,
            config: S3ResolvedConfig,
            remoteFile: RemoteS3File?,
            remoteIndexEntry: S3RemoteIndexEntry?,
            metadata: com.lomo.data.local.entity.S3SyncMetadataEntity?,
        ): S3RemoteObjectKey =
            when {
                remoteFile != null -> validatedExistingKey(remoteFile.remotePath, config)
                remoteIndexEntry != null -> validatedExistingKey(remoteIndexEntry.remotePath, config)
                metadata != null -> validatedExistingKey(metadata.remotePath, config)
                else -> newKeyFor(relativePath, config)
            }
    }
