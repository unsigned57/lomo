package com.lomo.data.repository

import androidx.documentfile.provider.DocumentFile
import com.lomo.data.media.AndroidPresentationMimeTypes
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
        // Android presentation MIME only — not Rust media identity (see AndroidPresentationMimeTypes).
        WorkspaceMediaCategory.IMAGE -> AndroidPresentationMimeTypes.imageMimeForFilename(filename)
        WorkspaceMediaCategory.VOICE -> AndroidPresentationMimeTypes.audioMimeForFilename(filename)
    }

private fun isWorkspaceSafAudioFilename(name: String): Boolean =
    MediaFileExtensions.hasAudioExtension(name)

private const val AUDIO_MIME_PREFIX = "audio/"
private const val IMAGE_MIME_PREFIX = "image/"
