package com.lomo.data.sync

import com.lomo.data.engine.sync.RemoteSyncBoundaryFailure
import com.lomo.domain.model.SyncMergeChoice
import com.lomo.domain.model.SyncMergeSuggestion
import com.lomo.nativebridge.EngineError
import com.lomo.nativebridge.syncSuggestConflictResolution

/**
 * Owner-computed conflict suggestion boundary: bodies + mtimes in, suggestion wire out.
 *
 * The merge algorithm, memo-identity merge, and time adjudication are Rust-owned; Kotlin only
 * displays the suggestion and submits the user's choice or edited merge text. An unknown wire
 * choice is a contract drift — fail closed rather than silently drop the suggestion.
 *
 * Hosts depend on [SyncConflictSuggestionPort] so JVM tests can substitute a fake; the
 * production binding is [BoltFfiSyncConflictSuggestionPort].
 */
fun interface SyncConflictSuggestionPort {
    fun suggest(
        localBody: String?,
        remoteBody: String?,
        localLastModifiedMs: Long?,
        remoteLastModifiedMs: Long?,
        isBinary: Boolean,
    ): SyncMergeSuggestion
}

internal class BoltFfiSyncConflictSuggestionPort : SyncConflictSuggestionPort {
    override fun suggest(
        localBody: String?,
        remoteBody: String?,
        localLastModifiedMs: Long?,
        remoteLastModifiedMs: Long?,
        isBinary: Boolean,
    ): SyncMergeSuggestion =
        try {
            conflictMergeSuggestion(
                localBody = localBody,
                remoteBody = remoteBody,
                localLastModifiedMs = localLastModifiedMs,
                remoteLastModifiedMs = remoteLastModifiedMs,
                isBinary = isBinary,
            )
        } catch (error: EngineError.Failure) {
            val failure = error.failure
            throw RemoteSyncBoundaryFailure(
                category = failure.category,
                code = failure.code,
                retryDisposition = failure.retryDisposition,
                diagnostic = failure.diagnostic,
                operationId = failure.operationId,
                jobId = failure.jobId,
            ).also { mapped -> mapped.initCause(error) }
        }
}

private fun conflictMergeSuggestion(
    localBody: String?,
    remoteBody: String?,
    localLastModifiedMs: Long?,
    remoteLastModifiedMs: Long?,
    isBinary: Boolean,
): SyncMergeSuggestion {
    val dto =
        syncSuggestConflictResolution(
            localBody = localBody,
            remoteBody = remoteBody,
            localLastModifiedMs = localLastModifiedMs,
            remoteLastModifiedMs = remoteLastModifiedMs,
            isBinary = isBinary,
        )
    return SyncMergeSuggestion(
        suggested = dto.suggestedChoice.toMergeChoice(),
        safe = dto.safeChoice.toMergeChoice(),
        mergedText = dto.mergedBody,
    )
}

private fun String?.toMergeChoice(): SyncMergeChoice? =
    when (this) {
        null -> null
        "keep_local" -> SyncMergeChoice.KEEP_LOCAL
        "keep_remote" -> SyncMergeChoice.KEEP_OTHER
        "merge_text" -> SyncMergeChoice.MERGE_TEXT
        else -> error("unknown conflict suggestion kind: $this")
    }
