package com.lomo.data.repository

import androidx.documentfile.provider.DocumentFile
import com.lomo.data.source.safIsImageFilename
import com.lomo.domain.model.MediaFileExtensions

internal fun workspaceMatchesSafCategory(
    category: WorkspaceMediaCategory,
    file: DocumentFile,
): Boolean {
    val filename = file.name ?: return false
    return when (category) {
        WorkspaceMediaCategory.IMAGE ->
            file.type?.startsWith(IMAGE_MIME_PREFIX) == true || safIsImageFilename(filename)
        WorkspaceMediaCategory.VOICE ->
            file.type?.startsWith(AUDIO_MIME_PREFIX) == true || isWorkspaceSafAudioFilename(filename)
    }
}

internal fun workspaceMimeTypeFor(
    category: WorkspaceMediaCategory,
    filename: String,
): String =
    when (category) {
        WorkspaceMediaCategory.IMAGE -> workspaceImageMimeType(filename)
        WorkspaceMediaCategory.VOICE -> workspaceAudioMimeType(filename)
    }

private fun workspaceImageMimeType(filename: String): String =
    when (filename.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)) {
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "heic" -> "image/heic"
        "heif" -> "image/heif"
        "avif" -> "image/avif"
        else -> "image/jpeg"
    }

private fun workspaceAudioMimeType(filename: String): String =
    when (filename.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)) {
        "mp3" -> "audio/mpeg"
        "aac" -> "audio/aac"
        "ogg" -> "audio/ogg"
        "wav" -> "audio/wav"
        else -> "audio/mp4"
    }

private fun isWorkspaceSafAudioFilename(name: String): Boolean =
    MediaFileExtensions.hasAudioExtension(name)

private const val AUDIO_MIME_PREFIX = "audio/"
private const val IMAGE_MIME_PREFIX = "image/"
