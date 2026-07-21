package com.lomo.data.share

import com.lomo.domain.repository.MediaRepository
import com.lomo.domain.repository.MemoMutationRepository
import timber.log.Timber

/** Applies platform attachment name maps then persists received memo content. */
fun interface ShareAttachmentDestinationRemapper {
    fun remap(
        content: String,
        attachmentMappings: Map<String, String>,
    ): String
}

class OwnerShareAttachmentDestinationRemapper : ShareAttachmentDestinationRemapper {
    override fun remap(
        content: String,
        attachmentMappings: Map<String, String>,
    ): String = ShareAttachmentReferenceRemapper.remapMarkdownTargets(content, attachmentMappings)
}

class ShareIncomingMemoSaver(
    private val memoMutationRepository: MemoMutationRepository,
    private val mediaRepository: MediaRepository,
    private val attachmentRemapper: ShareAttachmentDestinationRemapper =
        OwnerShareAttachmentDestinationRemapper(),
) {
    suspend fun saveReceivedMemo(
        content: String,
        timestamp: Long,
        attachmentMappings: Map<String, String>,
    ) {
        val adaptedContent = attachmentRemapper.remap(content, attachmentMappings)
        memoMutationRepository.saveMemo(adaptedContent, timestamp)
        mediaRepository.refreshImageLocations()
        Timber.tag(TAG).d("Received memo saved successfully")
    }

    private companion object {
        private const val TAG = "ShareIncomingMemoSaver"
    }
}
