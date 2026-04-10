package com.lomo.data.repository

import com.lomo.data.s3.S3RcloneCryptCompatCodec
import com.lomo.domain.model.S3EncryptionMode
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class S3SyncEncodingSupport
    @Inject
    constructor() {
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
                S3EncryptionMode.RCLONE_CRYPT ->
                    rcloneCodec.decryptKey(
                        encryptedKey = encodedPath,
                        password = config.requireEncryptionPassword(),
                        password2 = config.encryptionPassword2.orEmpty(),
                        config = config.rcloneCryptConfig,
                    )
            }
        }

        fun encodeContent(
            bytes: ByteArray,
            config: S3ResolvedConfig,
        ): ByteArray =
            when (config.encryptionMode) {
                S3EncryptionMode.NONE -> bytes
                S3EncryptionMode.RCLONE_CRYPT ->
                    if (!config.rcloneCryptConfig.dataEncryptionEnabled) {
                        bytes
                    } else {
                        rcloneCodec.encryptBytes(
                            plaintext = bytes,
                            password = config.requireEncryptionPassword(),
                            password2 = config.encryptionPassword2.orEmpty(),
                        )
                    }
            }

        fun decodeContent(
            bytes: ByteArray,
            config: S3ResolvedConfig,
        ): ByteArray =
            when (config.encryptionMode) {
                S3EncryptionMode.NONE -> bytes
                S3EncryptionMode.RCLONE_CRYPT ->
                    if (!config.rcloneCryptConfig.dataEncryptionEnabled) {
                        bytes
                    } else {
                        rcloneCodec.decryptBytes(
                            encrypted = bytes,
                            password = config.requireEncryptionPassword(),
                            password2 = config.encryptionPassword2.orEmpty(),
                        )
                    }
            }

        internal fun prepareUploadFile(
            source: S3TransferFile,
            config: S3ResolvedConfig,
            session: S3SyncTransferSession,
        ): S3TransferFile =
            when (config.encryptionMode) {
                S3EncryptionMode.NONE -> source
                S3EncryptionMode.RCLONE_CRYPT ->
                    if (!config.rcloneCryptConfig.dataEncryptionEnabled) {
                        source
                    } else {
                        transformFile(
                            source = source.file,
                            session = session,
                            suffix = source.file.transferSuffix(".enc"),
                        ) { input, output ->
                            rcloneCodec.encryptFile(
                                input = input,
                                output = output,
                                password = config.requireEncryptionPassword(),
                                password2 = config.encryptionPassword2.orEmpty(),
                            )
                        }
                    }
            }

        internal fun decodeDownloadedFile(
            source: File,
            config: S3ResolvedConfig,
            session: S3SyncTransferSession,
        ): S3TransferFile =
            when (config.encryptionMode) {
                S3EncryptionMode.NONE -> S3TransferFile(source)
                S3EncryptionMode.RCLONE_CRYPT ->
                    if (!config.rcloneCryptConfig.dataEncryptionEnabled) {
                        S3TransferFile(source)
                    } else {
                        transformFile(
                            source = source,
                            session = session,
                            suffix = source.transferSuffix(".dec"),
                        ) { input, output ->
                            rcloneCodec.decryptFile(
                                input = input,
                                output = output,
                                password = config.requireEncryptionPassword(),
                                password2 = config.encryptionPassword2.orEmpty(),
                            )
                        }
                    }
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
                S3EncryptionMode.RCLONE_CRYPT ->
                    rcloneCodec.encryptKey(
                        key = relativePath,
                        password = config.requireEncryptionPassword(),
                        password2 = config.encryptionPassword2.orEmpty(),
                        config = config.rcloneCryptConfig,
                    )
            }

        private fun transformFile(
            source: File,
            session: S3SyncTransferSession,
            suffix: String,
            transform: (input: File, output: File) -> Unit,
        ): S3TransferFile {
            val output = session.createTempFile(prefix = "s3-transfer-", suffix = suffix)
            transform(source, output)
            return S3TransferFile(output)
        }
    }

private fun File.transferSuffix(defaultSuffix: String): String =
    name.substringAfterLast('.', "").takeIf(String::isNotBlank)?.let { ".$it" } ?: defaultSuffix

private const val MILLIS_PER_SECOND = 1_000L
private const val EPOCH_MILLIS_THRESHOLD = 1_000_000_000_000L
