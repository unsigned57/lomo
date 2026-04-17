package com.lomo.data.repository

import com.lomo.data.local.entity.WebDavSyncMetadataEntity
import com.lomo.data.sync.SyncDirectoryLayout
import com.lomo.data.util.runNonFatalCatching
import com.lomo.data.webdav.WebDavClient
import com.lomo.domain.model.WebDavSyncDirection
import com.lomo.domain.model.WebDavSyncReason

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
    fileBridge: WebDavSyncFileBridge,
    timestampToleranceMs: Long = WEBDAV_TIMESTAMP_TOLERANCE_MS,
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

    candidatePaths.forEach { path ->
        val local = requireNotNull(localFiles[path])
        val remote = requireNotNull(remoteFiles[path])
        when (
            resolveWebDavInitialOverlap(
                path = path,
                local = local,
                remote = remote,
                client = client,
                layout = layout,
                fileBridge = fileBridge,
                timestampToleranceMs = timestampToleranceMs,
            )
        ) {
            WebDavInitialOverlapDecision.EQUIVALENT ->
                equivalentMetadataByPath[path] =
                    initialEquivalentWebDavMetadata(path, local, remote)

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
    fileBridge: WebDavSyncFileBridge,
    timestampToleranceMs: Long,
): WebDavInitialOverlapDecision {
    val localFingerprint = local.localFingerprint
        ?: return newerSideWebDavDecision(local.lastModified, remote.lastModified, timestampToleranceMs)

    val remoteBytes =
        runNonFatalCatching { client.get(path).bytes }.getOrNull()
            ?: return if (fileBridge.isMemoPath(path, layout)) {
                WebDavInitialOverlapDecision.CONFLICT
            } else {
                newerSideWebDavDecision(local.lastModified, remote.lastModified, timestampToleranceMs)
            }
    val remoteFingerprint = remoteBytes.md5Hex()
    return if (localFingerprint == remoteFingerprint) {
        WebDavInitialOverlapDecision.EQUIVALENT
    } else {
        newerSideWebDavDecision(local.lastModified, remote.lastModified, timestampToleranceMs)
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
