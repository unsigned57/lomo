package com.lomo.domain.model

object SyncConflictAutoResolutionAdvisor {
    private const val MUCH_NEWER_THRESHOLD_MS = 5 * 60 * 1000L

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
