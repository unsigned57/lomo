package com.lomo.data.share

import com.lomo.data.repository.MemoSynchronizer
import com.lomo.domain.repository.MediaRepository
import timber.log.Timber
import javax.inject.Inject

class ShareIncomingMemoSaver
    @Inject
    constructor(
        private val synchronizer: MemoSynchronizer,
        private val mediaRepository: MediaRepository,
    ) {
        private companion object {
            private const val TAG = "ShareIncomingMemoSaver"
        }

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
        ): String =
            attachmentMappings.entries.fold(content) { acc, (originalName, newName) ->
                acc.replace(originalName, newName)
            }
    }
