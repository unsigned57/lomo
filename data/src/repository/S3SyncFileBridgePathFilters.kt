package com.lomo.data.repository

import com.lomo.data.sync.SyncDirectoryLayout
import com.lomo.domain.model.MediaFileExtensions
import java.util.Locale

internal fun String.matchesLegacyFolder(folder: String): Boolean = this == folder || startsWith("$folder/")

@JvmInline
internal value class VaultRootPath private constructor(
    val value: String,
) {
    override fun toString(): String = value

    companion object {
        fun from(path: String): VaultRootPath? {
            val normalized = path.trim()
            if (!normalized.hasValidRelativePathShape()) {
                return null
            }
            val segments = normalized.split('/')
            if (!segments.hasValidRelativePathSegments()) {
                return null
            }
            return VaultRootPath(segments.joinToString("/"))
        }
    }
}

internal fun joinRelativePath(
    base: String?,
    remainder: String,
): String =
    listOfNotNull(
        base?.takeIf(String::isNotBlank),
        remainder.takeIf(String::isNotBlank),
    ).joinToString("/")

internal fun sanitizeRelativePath(path: String): String? =
    VaultRootPath.from(path)?.value

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

internal fun isConfiguredRootWorkspacePath(relativePath: String): Boolean =
    !hasHiddenSegment(relativePath) &&
        !hasSystemPathSegment(relativePath) &&
        !hasSystemFileName(relativePath)

internal fun S3LocalSyncMode.VaultRoot.acceptsWorkspacePath(relativePath: String): Boolean =
    if (legacyRemoteCompatibility) {
        isSyncableContentPath(relativePath)
    } else {
        isConfiguredRootWorkspacePath(relativePath)
    }

private fun hasHiddenSegment(relativePath: String): Boolean =
    relativePath
        .split('/')
        .any { segment -> segment.startsWith(".") }

private fun hasSystemPathSegment(relativePath: String): Boolean =
    relativePath
        .split('/')
        .any { segment ->
            segment.lowercase(Locale.ROOT) in SYSTEM_DIRECTORY_SEGMENTS
        }

private fun hasSystemFileName(relativePath: String): Boolean {
    val filename = relativePath.substringAfterLast('/').lowercase(Locale.ROOT)
    return filename in SYSTEM_FILE_NAMES
}

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

internal val S3_SYNC_IMAGE_EXTENSIONS = MediaFileExtensions.IMAGE

internal val S3_SYNC_VOICE_EXTENSIONS = MediaFileExtensions.AUDIO

internal const val DEFAULT_IMAGE_CONTENT_TYPE = "image/jpeg"
internal const val DEFAULT_VOICE_CONTENT_TYPE = "audio/mp4"
internal const val OCTET_STREAM = "application/octet-stream"

private val SYSTEM_DIRECTORY_SEGMENTS =
    setOf(
        "system volume information",
        "\$recycle.bin",
        "lost+found",
    )

private val SYSTEM_FILE_NAMES =
    setOf(
        "desktop.ini",
        "thumbs.db",
    )
