package com.lomo.domain.model

private const val MUCH_NEWER_THRESHOLD_MS = 5 * 60 * 1000L

object SyncConflictAutoResolutionAdvisor {
    fun suggestedChoice(file: SyncConflictFile): SyncConflictResolutionChoice? {
        val safeChoice = safeAutoResolutionChoice(file)
        return safeChoice ?: newerSideChoice(file)
    }

    fun safeAutoResolutionChoice(file: SyncConflictFile): SyncConflictResolutionChoice? {
        if (file.isBinary) return null

        val localContent = file.localContent.orEmpty()
        val remoteContent = file.remoteContent.orEmpty()
        val normalizedLocal = localContent.trim()
        val normalizedRemote = remoteContent.trim()
        val newerSideChoice = newerSideChoice(file)
        val mergeChoice =
            mergedText(file)
                ?.takeIf { mergedText ->
                    mergedText.trim() != normalizedLocal && mergedText.trim() != normalizedRemote
                }?.let { SyncConflictResolutionChoice.MERGE_TEXT }

        return when {
            normalizedLocal == normalizedRemote -> newerSideChoice ?: SyncConflictResolutionChoice.KEEP_REMOTE
            normalizedLocal.isBlank() && normalizedRemote.isBlank() ->
                newerSideChoice ?: SyncConflictResolutionChoice.KEEP_REMOTE

            normalizedLocal.isBlank() -> SyncConflictResolutionChoice.KEEP_REMOTE
            normalizedRemote.isBlank() -> SyncConflictResolutionChoice.KEEP_LOCAL
            normalizedRemote.contains(normalizedLocal) -> SyncConflictResolutionChoice.KEEP_REMOTE
            normalizedLocal.contains(normalizedRemote) -> SyncConflictResolutionChoice.KEEP_LOCAL
            mergeChoice != null -> mergeChoice
            else -> null
        }
    }

    fun mergedText(file: SyncConflictFile): String? =
        if (file.isBinary) {
            null
        } else {
            SyncConflictTextMerge.merge(
                localText = file.localContent,
                remoteText = file.remoteContent,
                localLastModified = file.localLastModified,
                remoteLastModified = file.remoteLastModified,
            )
        }

    private fun newerSideChoice(file: SyncConflictFile): SyncConflictResolutionChoice? {
        val localTimestamp = file.localLastModified ?: return null
        val remoteTimestamp = file.remoteLastModified ?: return null
        return when {
            localTimestamp - remoteTimestamp >= MUCH_NEWER_THRESHOLD_MS -> SyncConflictResolutionChoice.KEEP_LOCAL
            remoteTimestamp - localTimestamp >= MUCH_NEWER_THRESHOLD_MS -> SyncConflictResolutionChoice.KEEP_REMOTE
            else -> null
        }
    }
}

object SyncReviewAutoResolutionAdvisor {
    fun suggestedChoice(item: SyncReviewItem): SyncReviewResolutionChoice? =
        safeAutoResolutionChoice(item) ?: newerSideChoice(item)

    fun safeAutoResolutionChoice(item: SyncReviewItem): SyncReviewResolutionChoice? {
        if (item.isBinary) return null

        val localContent = item.localContent.orEmpty()
        val incomingContent = item.incomingContent.orEmpty()
        val normalizedLocal = localContent.trim()
        val normalizedIncoming = incomingContent.trim()
        val newerSideChoice = newerSideChoice(item)
        val mergeChoice =
            mergedText(item)
                ?.takeIf { mergedText ->
                    mergedText.trim() != normalizedLocal && mergedText.trim() != normalizedIncoming
                }?.let { SyncReviewResolutionChoice.MERGE_TEXT }

        return when {
            normalizedLocal == normalizedIncoming -> newerSideChoice ?: SyncReviewResolutionChoice.KEEP_INCOMING
            normalizedLocal.isBlank() && normalizedIncoming.isBlank() ->
                newerSideChoice ?: SyncReviewResolutionChoice.KEEP_INCOMING

            normalizedLocal.isBlank() -> SyncReviewResolutionChoice.KEEP_INCOMING
            normalizedIncoming.isBlank() -> SyncReviewResolutionChoice.KEEP_LOCAL
            normalizedIncoming.contains(normalizedLocal) -> SyncReviewResolutionChoice.KEEP_INCOMING
            normalizedLocal.contains(normalizedIncoming) -> SyncReviewResolutionChoice.KEEP_LOCAL
            mergeChoice != null -> mergeChoice
            else -> null
        }
    }

    fun mergedText(item: SyncReviewItem): String? =
        if (item.isBinary) {
            null
        } else {
            SyncConflictTextMerge.merge(
                localText = item.localContent,
                remoteText = item.incomingContent,
                localLastModified = item.localLastModified,
                remoteLastModified = item.incomingLastModified,
            )
        }

    private fun newerSideChoice(item: SyncReviewItem): SyncReviewResolutionChoice? {
        val localTimestamp = item.localLastModified ?: return null
        val incomingTimestamp = item.incomingLastModified ?: return null
        return when {
            localTimestamp - incomingTimestamp >= MUCH_NEWER_THRESHOLD_MS -> SyncReviewResolutionChoice.KEEP_LOCAL
            incomingTimestamp - localTimestamp >= MUCH_NEWER_THRESHOLD_MS -> SyncReviewResolutionChoice.KEEP_INCOMING
            else -> null
        }
    }
}
