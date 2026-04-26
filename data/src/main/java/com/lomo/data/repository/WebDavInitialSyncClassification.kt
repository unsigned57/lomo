package com.lomo.data.repository

import com.lomo.data.local.entity.WebDavSyncMetadataEntity
import com.lomo.data.sync.SyncDirectoryLayout
import com.lomo.data.util.runNonFatalCatching
import com.lomo.data.webdav.WebDavClient
import com.lomo.domain.model.WebDavSyncDirection
import com.lomo.domain.model.WebDavSyncReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal data class WebDavInitialSyncClassification(
    val equivalentMetadataByPath: Map<String, WebDavSyncMetadataEntity> = emptyMap(),
    val resolvedActionsByPath: Map<String, WebDavSyncAction> = emptyMap(),
)

internal suspend fun classifyWebDavInitialOverlaps(
    localFiles: Map<String, LocalWebDavFile>,
    remoteFiles: Map<String, RemoteWebDavFile>,
    metadataByPath: Map<String, WebDavSyncMetadataEntity>,
    client: WebDavClient,
    layout: SyncDirectoryLayout,
    timestampToleranceMs: Long = WEBDAV_TIMESTAMP_TOLERANCE_MS,
    overlapConcurrency: Int = WEBDAV_DEFAULT_INITIAL_OVERLAP_CONCURRENCY,
): WebDavInitialSyncClassification {
    val candidatePaths =
        localFiles.keys
            .intersect(remoteFiles.keys)
            .asSequence()
            .filterNot(metadataByPath::containsKey)
            .sorted()
            .toList()
    if (candidatePaths.isEmpty()) {
        return WebDavInitialSyncClassification()
    }

    val equivalentMetadataByPath = linkedMapOf<String, WebDavSyncMetadataEntity>()
    val resolvedActionsByPath = linkedMapOf<String, WebDavSyncAction>()

    val decisions =
        coroutineScope {
            val limiter = Semaphore(overlapConcurrency.coercePositiveConcurrency())
            candidatePaths.map { path ->
                async(Dispatchers.IO) {
                    limiter.withPermit {
                        val local = requireNotNull(localFiles[path])
                        val remote = requireNotNull(remoteFiles[path])
                        path to
                            resolveWebDavInitialOverlap(
                                path = path,
                                local = local,
                                remote = remote,
                                client = client,
                                layout = layout,
                                timestampToleranceMs = timestampToleranceMs,
                            )
                    }
                }
            }.awaitAll()
        }

    decisions.forEach { (path, decision) ->
        when (decision) {
            WebDavInitialOverlapDecision.EQUIVALENT ->
                equivalentMetadataByPath[path] =
                    initialEquivalentWebDavMetadata(
                        path = path,
                        local = requireNotNull(localFiles[path]),
                        remote = requireNotNull(remoteFiles[path]),
                    )

            WebDavInitialOverlapDecision.UPLOAD ->
                resolvedActionsByPath[path] =
                    WebDavSyncAction(path, WebDavSyncDirection.UPLOAD, WebDavSyncReason.LOCAL_ONLY)

            WebDavInitialOverlapDecision.DOWNLOAD ->
                resolvedActionsByPath[path] =
                    WebDavSyncAction(path, WebDavSyncDirection.DOWNLOAD, WebDavSyncReason.REMOTE_ONLY)

            WebDavInitialOverlapDecision.CONFLICT -> Unit
        }
    }

    return WebDavInitialSyncClassification(
        equivalentMetadataByPath = equivalentMetadataByPath,
        resolvedActionsByPath = resolvedActionsByPath,
    )
}

private suspend fun resolveWebDavInitialOverlap(
    path: String,
    local: LocalWebDavFile,
    remote: RemoteWebDavFile,
    client: WebDavClient,
    layout: SyncDirectoryLayout,
    timestampToleranceMs: Long,
): WebDavInitialOverlapDecision {
    val localFingerprint = local.localFingerprint
        ?: return newerSideWebDavDecision(local.lastModified, remote.lastModified, timestampToleranceMs)
    val timestampDecision =
        newerSideWebDavDecision(local.lastModified, remote.lastModified, timestampToleranceMs)
    return when {
        timestampDecision != WebDavInitialOverlapDecision.CONFLICT -> timestampDecision
        local.size != null && remote.size != null && local.size != remote.size -> timestampDecision
        else -> {
            val etagDecision = remote.etag?.let { resolveEtagOverlapDecision(localFingerprint, it, timestampDecision) }
            if (etagDecision != null) {
                etagDecision
            } else {
                resolveDownloadedOverlapDecision(
                    path = path,
                    localFingerprint = localFingerprint,
                    client = client,
                    layout = layout,
                    timestampDecision = timestampDecision,
                    localLastModified = local.lastModified,
                    remoteLastModified = remote.lastModified,
                    timestampToleranceMs = timestampToleranceMs,
                )
            }
        }
    }
}

private fun resolveEtagOverlapDecision(
    localFingerprint: String,
    remoteEtag: String,
    timestampDecision: WebDavInitialOverlapDecision,
): WebDavInitialOverlapDecision? =
    normalizeSinglePartS3Md5(remoteEtag)?.let { remoteFingerprint ->
        if (localFingerprint == remoteFingerprint) {
            WebDavInitialOverlapDecision.EQUIVALENT
        } else {
            timestampDecision
        }
    }

private suspend fun resolveDownloadedOverlapDecision(
    path: String,
    localFingerprint: String,
    client: WebDavClient,
    layout: SyncDirectoryLayout,
    timestampDecision: WebDavInitialOverlapDecision,
    localLastModified: Long,
    remoteLastModified: Long?,
    timestampToleranceMs: Long,
): WebDavInitialOverlapDecision {
    if (!isWebDavMemoPath(path, layout)) {
        return WebDavInitialOverlapDecision.CONFLICT
    }
    val remoteBytes = runNonFatalCatching { client.get(path).bytes }.getOrNull()
    if (remoteBytes == null) {
        return if (isWebDavMemoPath(path, layout)) {
            WebDavInitialOverlapDecision.CONFLICT
        } else {
            newerSideWebDavDecision(localLastModified, remoteLastModified, timestampToleranceMs)
        }
    }
    return if (localFingerprint == remoteBytes.md5Hex()) {
        WebDavInitialOverlapDecision.EQUIVALENT
    } else {
        timestampDecision
    }
}

private fun newerSideWebDavDecision(
    localLastModified: Long,
    remoteLastModified: Long?,
    timestampToleranceMs: Long,
): WebDavInitialOverlapDecision {
    val remoteTimestamp = remoteLastModified ?: return WebDavInitialOverlapDecision.UPLOAD
    return when {
        localLastModified - remoteTimestamp > timestampToleranceMs -> WebDavInitialOverlapDecision.UPLOAD
        remoteTimestamp - localLastModified > timestampToleranceMs -> WebDavInitialOverlapDecision.DOWNLOAD
        else -> WebDavInitialOverlapDecision.CONFLICT
    }
}

private fun initialEquivalentWebDavMetadata(
    path: String,
    local: LocalWebDavFile,
    remote: RemoteWebDavFile,
): WebDavSyncMetadataEntity =
    WebDavSyncMetadataEntity(
        relativePath = path,
        remotePath = path,
        etag = remote.etag,
        remoteLastModified = remote.lastModified,
        localLastModified = local.lastModified,
        localFingerprint = local.localFingerprint,
        lastSyncedAt = System.currentTimeMillis(),
        lastResolvedDirection = WebDavSyncMetadataEntity.NONE,
        lastResolvedReason = WebDavSyncMetadataEntity.UNCHANGED,
    )

private enum class WebDavInitialOverlapDecision {
    EQUIVALENT,
    UPLOAD,
    DOWNLOAD,
    CONFLICT,
}

private const val WEBDAV_TIMESTAMP_TOLERANCE_MS = 1000L
