package com.lomo.app.feature.synccenter

import com.lomo.domain.model.RemoteSyncBinaryConflictFacts
import com.lomo.domain.model.RemoteSyncConflictPath
import com.lomo.domain.model.RemoteSyncConflictResolution
import com.lomo.domain.model.RemoteSyncMarkdownConflictFacts

/**
 * Live-path markdown facts: prefer repository-loaded detail when present, else digest-only helper.
 */
fun markdownFactsFromState(
    ready: SyncCenterLoadState.Ready,
    path: RemoteSyncConflictPath,
): RemoteSyncMarkdownConflictFacts {
    ready.markdownDetailByPath[path.path]?.let { loaded ->
        val draft = ready.mergedDrafts[path.path]
        return if (draft != null && draft != loaded.mergedDraft) {
            loaded.copy(mergedDraft = draft)
        } else {
            loaded
        }
    }
    return markdownFactsFor(
        path = path,
        mergedDraft = ready.mergedDrafts[path.path],
    )
}

/**
 * Live-path binary facts: prefer repository-loaded detail when present, else digest-only helper.
 * Never invents text preview bodies.
 */
fun binaryFactsFromState(
    ready: SyncCenterLoadState.Ready,
    path: RemoteSyncConflictPath,
): RemoteSyncBinaryConflictFacts =
    ready.binaryDetailByPath[path.path] ?: binaryFactsFor(path)

fun selectedConflict(state: SyncCenterUiState): RemoteSyncConflictPath? {
    val ready = state.load as? SyncCenterLoadState.Ready ?: return null
    val path = ready.selectedPath ?: return null
    return ready.items.firstOrNull { it.path == path }
}

/**
 * Binary detail facts from a list path. Never invents text body preview.
 *
 * MIME / size are unknown on the list wire until production detail ports land — null is honest.
 */
fun binaryFactsFor(path: RemoteSyncConflictPath): RemoteSyncBinaryConflictFacts {
    require(path.isBinary) { "binaryFactsFor requires kind=binary" }
    return RemoteSyncBinaryConflictFacts(
        path = path.path,
        mimeType = null,
        sizeBytes = null,
        localDigest = path.localDigest,
        remoteDigest = path.remoteDigest,
        baselineDigest = path.baselineDigest,
        sourceLabel = "remote_sync",
    )
}

/**
 * Markdown detail facts from a list path.
 *
 * Pure presentation helper for reducer tests and Compose when the host has not yet loaded
 * durable bodies. Prefer domain remote-sync center markdown detail ports
 * when artifact-backed bodies are available (dark data adapter).
 */
fun markdownFactsFor(
    path: RemoteSyncConflictPath,
    mergedDraft: String?,
    baseBody: String? = null,
    localBody: String? = null,
    remoteBody: String? = null,
): RemoteSyncMarkdownConflictFacts {
    require(path.isMarkdown) { "markdownFactsFor requires kind=markdown" }
    return RemoteSyncMarkdownConflictFacts(
        path = path.path,
        baseDigest = path.baselineDigest,
        localDigest = path.localDigest,
        remoteDigest = path.remoteDigest,
        baseBody = baseBody,
        localBody = localBody,
        remoteBody = remoteBody,
        mergedDraft = mergedDraft,
    )
}

internal fun buildResolutions(ready: SyncCenterLoadState.Ready): List<RemoteSyncConflictResolution> =
    ready.perPathResolutionKind.mapNotNull { (path, kind) ->
        when (kind) {
            RemoteSyncConflictResolution.KIND_MERGED_BODY -> {
                val body = ready.mergedDrafts[path]
                if (body.isNullOrEmpty()) {
                    null
                } else {
                    RemoteSyncConflictResolution(path = path, kind = kind, mergedBody = body)
                }
            }

            RemoteSyncConflictResolution.KIND_KEEP_LOCAL,
            RemoteSyncConflictResolution.KIND_KEEP_REMOTE,
            RemoteSyncConflictResolution.KIND_SKIP_FOR_NOW,
            -> RemoteSyncConflictResolution(path = path, kind = kind, mergedBody = null)

            else -> null
        }
    }
