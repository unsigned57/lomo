package com.lomo.data.engine.media

import com.lomo.data.repository.S3LocalChangeRecorder
import com.lomo.data.repository.WebDavLocalChangeRecorder
import com.lomo.domain.model.MediaFileExtensions

/**
 * D8 sync edge: translates **committed + verified** media path events into frozen sync-v1
 * `recordImageUpsert/Delete` / voice journals. Staged/pending media never reaches recorders.
 */
class MediaSyncEdgeAdapter(
    private val s3LocalChangeRecorder: S3LocalChangeRecorder,
    private val webDavLocalChangeRecorder: WebDavLocalChangeRecorder,
) {
    suspend fun onCommittedMediaUpsert(relativeOrFilename: String) {
        val name = relativeOrFilename.trim()
        if (name.isEmpty()) return
        if (looksLikeVoice(name)) {
            s3LocalChangeRecorder.recordVoiceUpsert(name)
            webDavLocalChangeRecorder.recordVoiceUpsert(name)
        } else {
            s3LocalChangeRecorder.recordImageUpsert(name)
            webDavLocalChangeRecorder.recordImageUpsert(name)
        }
    }

    suspend fun onCommittedMediaDelete(relativeOrFilename: String) {
        val name = relativeOrFilename.trim()
        if (name.isEmpty()) return
        if (looksLikeVoice(name)) {
            s3LocalChangeRecorder.recordVoiceDelete(name)
            webDavLocalChangeRecorder.recordVoiceDelete(name)
        } else {
            s3LocalChangeRecorder.recordImageDelete(name)
            webDavLocalChangeRecorder.recordImageDelete(name)
        }
    }

    private fun looksLikeVoice(path: String): Boolean {
        if (path.startsWith("voice_", ignoreCase = true)) return true
        return MediaFileExtensions.hasAudioExtension(path)
    }
}
