package com.lomo.data.repository

import com.lomo.data.s3.S3OpenSslCompatCodec
import com.lomo.data.s3.S3RcloneCryptCompatCodec
import com.lomo.domain.model.S3EncryptionMode
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class S3SyncEncodingSupport
    @Inject
    constructor() {
        private val openSslCodec = S3OpenSslCompatCodec()
        private val rcloneCodec = S3RcloneCryptCompatCodec()

        fun remoteKeyPrefix(config: S3ResolvedConfig): String =
            config.prefix.trim().trim('/').takeIf(String::isNotBlank)?.let { "$it/" }.orEmpty()

        fun remotePathFor(
            relativePath: String,
            config: S3ResolvedConfig,
            existingRemotePath: String? = null,
        ): String = existingRemotePath ?: remoteKeyPrefix(config) + encodeRelativePath(relativePath, config)

        fun decodeRelativePath(
            remotePath: String,
            config: S3ResolvedConfig,
        ): String {
            val encodedPath = remotePath.removePrefix(remoteKeyPrefix(config))
            return when (config.encryptionMode) {
                S3EncryptionMode.NONE -> encodedPath
                S3EncryptionMode.OPENSSL ->
                    openSslCodec.decryptKey(encodedPath, config.requireEncryptionPassword())
                S3EncryptionMode.RCLONE_CRYPT ->
                    rcloneCodec.decryptKey(encodedPath, config.requireEncryptionPassword())
            }
        }

        fun encodeContent(
            bytes: ByteArray,
            config: S3ResolvedConfig,
        ): ByteArray =
            when (config.encryptionMode) {
                S3EncryptionMode.NONE -> bytes
                S3EncryptionMode.OPENSSL ->
                    openSslCodec.encryptBytes(bytes, config.requireEncryptionPassword())
                S3EncryptionMode.RCLONE_CRYPT ->
                    rcloneCodec.encryptBytes(bytes, config.requireEncryptionPassword())
            }

        fun decodeContent(
            bytes: ByteArray,
            config: S3ResolvedConfig,
        ): ByteArray =
            when (config.encryptionMode) {
                S3EncryptionMode.NONE -> bytes
                S3EncryptionMode.OPENSSL ->
                    openSslCodec.decryptBytes(bytes, config.requireEncryptionPassword())
                S3EncryptionMode.RCLONE_CRYPT ->
                    rcloneCodec.decryptBytes(bytes, config.requireEncryptionPassword())
            }

        fun objectMetadata(lastModified: Long?): Map<String, String> {
            if (lastModified == null || lastModified <= 0L) return emptyMap()
            val seconds = (lastModified / MILLIS_PER_SECOND).toString()
            return mapOf(
                S3_MTIME_METADATA_KEY to seconds,
                S3_CTIME_METADATA_KEY to seconds,
            )
        }

        fun resolveRemoteLastModified(
            metadata: Map<String, String>,
            fallback: Long?,
        ): Long? {
            val raw = metadata[S3_MTIME_METADATA_KEY] ?: metadata[S3_MTIME_LEGACY_METADATA_KEY]
            val parsed = raw?.toLongOrNull()
            return when {
                parsed == null -> fallback
                parsed >= EPOCH_MILLIS_THRESHOLD -> parsed
                else -> parsed * MILLIS_PER_SECOND
            }
        }

        private fun encodeRelativePath(
            relativePath: String,
            config: S3ResolvedConfig,
        ): String =
            when (config.encryptionMode) {
                S3EncryptionMode.NONE -> relativePath
                S3EncryptionMode.OPENSSL ->
                    openSslCodec.encryptKey(relativePath, config.requireEncryptionPassword())
                S3EncryptionMode.RCLONE_CRYPT ->
                    rcloneCodec.encryptKey(relativePath, config.requireEncryptionPassword())
            }
    }

private const val MILLIS_PER_SECOND = 1_000L
private const val EPOCH_MILLIS_THRESHOLD = 1_000_000_000_000L
