package com.lomo.data.repository

import com.lomo.data.local.dao.MemoSearchDao
import com.lomo.data.source.MediaStorageDataSource

private val VOICE_ATTACHMENT_SUFFIXES = setOf(".m4a", ".mp3", ".aac", ".wav", ".ogg")

internal fun String.looksLikeVoiceAttachmentPath(): Boolean {
    if (startsWith("voice_", ignoreCase = true)) return true
    val lower = lowercase()
    return VOICE_ATTACHMENT_SUFFIXES.any { suffix -> lower.endsWith(suffix) }
}

/**
 * Deletes any attachment path that is no longer referenced by any active or trashed memo.
 *
 * Routes voice/audio targets to [MediaStorageDataSource.deleteVoiceFile] and other attachments to
 * [MediaStorageDataSource.deleteImage] so the correct SAF/direct sub-root is hit.
 *
 * [s3LocalChangeRecorder] and [webDavLocalChangeRecorder] are invoked per deleted path so sync
 * engines propagate the removal. Callers that intentionally run silently on sync may pass the
 * NoOp defaults.
 */
internal suspend fun deleteOrphanAttachments(
    paths: List<String>,
    excludeMemoId: String,
    memoSearchDao: MemoSearchDao,
    mediaStorageDataSource: MediaStorageDataSource,
    s3LocalChangeRecorder: S3LocalChangeRecorder = NoOpS3LocalChangeRecorder,
    webDavLocalChangeRecorder: WebDavLocalChangeRecorder = NoOpWebDavLocalChangeRecorder,
) {
    paths
        .asSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() }
        .filterNot { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
        .distinct()
        .forEach { path ->
            if (memoSearchDao.countMemosAndTrashWithImage(path, excludeMemoId) != 0) {
                return@forEach
            }
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
