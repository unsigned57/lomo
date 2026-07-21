package com.lomo.data.repository

import com.lomo.data.source.MediaStorageDataSource
import com.lomo.domain.model.MediaFileExtensions

internal fun String.looksLikeVoiceAttachmentPath(): Boolean {
    if (startsWith("voice_", ignoreCase = true)) return true
    return MediaFileExtensions.hasAudioExtension(this)
}

/**
 * Deletes attachment paths no longer referenced after a memo mutation.
 *
 * Post P3-10: reference counting via Room memo tables is gone. Callers only pass paths that the
 * mutation removed from durable content; this helper performs the filesystem + sync journal side
 * effects. Store rebuild re-indexes remaining attachment references from Markdown facts.
 */
internal suspend fun deleteOrphanAttachments(
    paths: List<String>,
    mediaStorageDataSource: MediaStorageDataSource,
    s3LocalChangeRecorder: S3LocalChangeRecorder,
    webDavLocalChangeRecorder: WebDavLocalChangeRecorder,
) {
    paths
        .asSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() }
        .filterNot { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
        .distinct()
        .forEach { path ->
            if (path.looksLikeVoiceAttachmentPath()) {
                mediaStorageDataSource.deleteVoiceFile(path)
                s3LocalChangeRecorder.recordVoiceDelete(path)
                webDavLocalChangeRecorder.recordVoiceDelete(path)
            } else {
                mediaStorageDataSource.deleteImage(path)
                s3LocalChangeRecorder.recordImageDelete(path)
                webDavLocalChangeRecorder.recordImageDelete(path)
            }
        }
}
