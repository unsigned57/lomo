package com.lomo.data.share

import com.lomo.data.repository.MemoSynchronizer
import com.lomo.domain.repository.MediaRepository
import timber.log.Timber


class ShareIncomingMemoSaver(
    private val synchronizer: MemoSynchronizer,
    private val mediaRepository: MediaRepository,
) {
        suspend fun saveReceivedMemo(
            content: String,
            timestamp: Long,
            attachmentMappings: Map<String, String>,
        ) {
            val adaptedContent = remapAttachmentReferences(content, attachmentMappings)
            synchronizer.saveMemo(adaptedContent, timestamp)
            mediaRepository.refreshImageLocations()
            Timber.tag(TAG).d("Received memo saved successfully")
        }

        private fun remapAttachmentReferences(
            content: String,
            attachmentMappings: Map<String, String>,
        ): String = ShareAttachmentReferenceRemapper.remapMarkdownTargets(content, attachmentMappings)

        private companion object {
            private const val TAG = "ShareIncomingMemoSaver"
        }
    }
