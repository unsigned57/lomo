package com.lomo.data.repository

import com.lomo.data.local.entity.S3SyncMetadataEntity
import com.lomo.data.util.md5Hex as streamMd5Hex
import java.io.File
import java.security.MessageDigest
import java.util.Locale

internal fun normalizeSinglePartS3Md5(etag: String?): String? {
    val normalized = etag?.trim()?.removePrefix("\"")?.removeSuffix("\"")?.lowercase(Locale.ROOT)
    return normalized?.takeIf { value ->
        value.length == SINGLE_PART_MD5_HEX_LENGTH &&
            value.all { it in '0'..'9' || it in 'a'..'f' } &&
            '-' !in value
    }
}

internal fun ByteArray.md5Hex(): String =
    MessageDigest
        .getInstance("MD5")
        .digest(this)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

internal fun File.md5Hex(): String =
    streamMd5Hex()

/**
 * One bridge-owned source for local content fingerprints. Enumeration is metadata-only;
 * planners resolve fingerprints on demand through this boundary as a last resort.
 */
internal fun interface S3LocalFingerprintSource {
    suspend fun fingerprint(
        path: String,
        local: LocalS3File,
    ): String?
}

/**
 * Persisted fingerprint cache rule: the last synced fingerprint is valid for a local file
 * exactly when its observed (lastModified, size) stat still matches the persisted stat.
 */
internal fun S3SyncMetadataEntity.cachedLocalFingerprintFor(local: LocalS3File): String? =
    localFingerprint?.takeIf { localLastModified == local.lastModified && localSize == local.size }

internal suspend fun resolveLocalContentFingerprint(
    path: String,
    local: LocalS3File,
    metadata: S3SyncMetadataEntity?,
    source: S3LocalFingerprintSource,
): String? =
    local.localFingerprint
        ?: metadata?.cachedLocalFingerprintFor(local)
        ?: source.fingerprint(path, local)

private const val SINGLE_PART_MD5_HEX_LENGTH = 32

