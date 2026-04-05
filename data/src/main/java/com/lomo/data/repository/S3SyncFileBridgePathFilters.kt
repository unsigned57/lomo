package com.lomo.data.repository

import com.lomo.data.sync.SyncDirectoryLayout
import java.util.Locale

internal fun String.matchesLegacyFolder(folder: String): Boolean = this == folder || startsWith("$folder/")

internal fun joinRelativePath(
    base: String?,
    remainder: String,
): String =
    listOfNotNull(
        base?.takeIf(String::isNotBlank),
        remainder.takeIf(String::isNotBlank),
    ).joinToString("/")

internal fun sanitizeRelativePath(path: String): String? =
    path
        .replace('\\', '/')
        .trim()
        .trim('/')
        .takeIf(String::isNotBlank)

internal fun isLegacyManagedPath(
    path: String,
    layout: SyncDirectoryLayout,
): Boolean =
    when {
        path.startsWith("$S3_ROOT/${layout.memoFolder}/") -> path.endsWith(S3_MEMO_SUFFIX)
        path.startsWith("$S3_ROOT/${layout.imageFolder}/") ->
            isSupportedAttachmentPath(path.removePrefix("$S3_ROOT/"))
        path.startsWith("$S3_ROOT/${layout.voiceFolder}/") ->
            isSupportedAttachmentPath(path.removePrefix("$S3_ROOT/"))
        else -> false
    }

internal fun isSyncableContentPath(relativePath: String): Boolean =
    !hasHiddenSegment(relativePath) &&
        (relativePath.endsWith(S3_MEMO_SUFFIX) || isSupportedAttachmentPath(relativePath))

internal fun isSupportedAttachmentPath(relativePath: String): Boolean {
    val extension = relativePath.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return extension in S3_SYNC_IMAGE_EXTENSIONS || extension in S3_SYNC_VOICE_EXTENSIONS
}

private fun hasHiddenSegment(relativePath: String): Boolean =
    relativePath
        .split('/')
        .any { segment -> segment.startsWith(".") }

internal fun legacyMemoRemotePrefix(layout: SyncDirectoryLayout): String = "$S3_ROOT/${layout.memoFolder}/"

internal val IMAGE_CONTENT_TYPES =
    mapOf(
        "png" to "image/png",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "bmp" to "image/bmp",
        "heic" to "image/heic",
        "heif" to "image/heif",
        "avif" to "image/avif",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
    )

internal val VOICE_CONTENT_TYPES =
    mapOf(
        "m4a" to "audio/mp4",
        "mp3" to "audio/mpeg",
        "aac" to "audio/aac",
        "wav" to "audio/wav",
        "ogg" to "audio/ogg",
    )

internal val S3_SYNC_IMAGE_EXTENSIONS =
    setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif")

internal val S3_SYNC_VOICE_EXTENSIONS = setOf("m4a", "mp3", "aac", "wav", "ogg")

internal const val DEFAULT_IMAGE_CONTENT_TYPE = "image/jpeg"
internal const val DEFAULT_VOICE_CONTENT_TYPE = "audio/mp4"
internal const val OCTET_STREAM = "application/octet-stream"
