package com.lomo.data.repository

import com.lomo.data.local.dao.MemoStatisticsDao
import com.lomo.data.source.MediaStorageDataSource
import com.lomo.domain.model.MediaFileExtensions

internal fun String.looksLikeVoiceAttachmentPath(): Boolean {
    if (startsWith("voice_", ignoreCase = true)) return true
    return MediaFileExtensions.hasAudioExtension(this)
}

/**
 * Deletes any attachment path that is no longer referenced by any active or trashed memo.
 *
 * Routes voice/audio targets to [MediaStorageDataSource.deleteVoiceFile] and other attachments to
 * [MediaStorageDataSource.deleteImage] so the correct SAF/direct sub-root is hit.
 *
 * [s3LocalChangeRecorder] and [webDavLocalChangeRecorder] are invoked per deleted path so sync
 * engines propagate the removal.
 */
internal suspend fun deleteOrphanAttachments(
    paths: List<String>,
    excludeMemoId: String,
    memoStatisticsDao: MemoStatisticsDao,
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
            if (memoStatisticsDao.countMemosAndTrashWithImage(path, excludeMemoId) != 0) {
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
