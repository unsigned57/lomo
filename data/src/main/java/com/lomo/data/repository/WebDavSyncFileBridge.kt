package com.lomo.data.repository

import com.lomo.data.local.entity.WebDavSyncMetadataEntity
import com.lomo.data.source.MemoDirectoryType
import com.lomo.data.sync.SyncDirectoryLayout
import com.lomo.data.util.runNonFatalCatching
import com.lomo.data.webdav.WebDavClient
import com.lomo.data.webdav.WebDavRemoteResource
import com.lomo.domain.model.WebDavSyncDirection
import com.lomo.domain.model.WebDavSyncReason
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

@Singleton
class WebDavSyncFileBridge
    @Inject
    constructor(
        private val runtime: WebDavSyncRepositoryContext,
        private val localFingerprintCache: WebDavLocalFingerprintCache,
        private val remoteListingCache: WebDavRemoteListingCache,
    ) {
        fun ensureRemoteDirectories(
            client: WebDavClient,
            layout: SyncDirectoryLayout,
        ) {
            client.ensureDirectory("")
            client.ensureDirectory(WEBDAV_ROOT)
            layout.distinctFolders.forEach { folder ->
                client.ensureDirectory(remoteFolderPath(folder))
            }
        }

        suspend fun localFiles(
            layout: SyncDirectoryLayout,
            targetPaths: Set<String>? = null,
            pruneCache: Boolean = true,
        ): Map<String, LocalWebDavFile> {
            val profile = runtime.performanceTuner.currentProfile()
            val memoPrefix = memoRemotePrefix(layout)
            val memoMetadataList =
                runtime.markdownStorageDataSource
                    .listMetadataIn(MemoDirectoryType.MAIN)
                    .filter { it.filename.endsWith(WEBDAV_MEMO_SUFFIX) }
                    .filter { metadata ->
                        targetPaths == null || "$memoPrefix${metadata.filename}" in targetPaths
                    }
            val fingerprintLimiter = Semaphore(profile.webDavFingerprintConcurrency.coercePositiveConcurrency())
            val memoFileEntries = coroutineScope {
                memoMetadataList.map { metadata ->
                    async(Dispatchers.IO) {
                        fingerprintLimiter.withPermit {
                            val remotePath = "$memoPrefix${metadata.filename}"
                            val cacheKey =
                                WebDavLocalFingerprintKey(
                                    path = remotePath,
                                    lastModified = metadata.lastModified,
                                    size = metadata.size,
                                )
                            val localFingerprint =
                                localFingerprintCache.get(cacheKey)
                                    ?: runtime.markdownStorageDataSource
                                        .readFileIn(
                                            MemoDirectoryType.MAIN,
                                            metadata.filename,
                                        )?.toByteArray(StandardCharsets.UTF_8)
                                        ?.md5Hex()
                                        ?.also { fingerprint ->
                                            localFingerprintCache.put(cacheKey, fingerprint)
                                        }
                            LocalWebDavFile(
                                path = remotePath,
                                lastModified = metadata.lastModified,
                                size = metadata.size,
                                localFingerprint = localFingerprint,
                            ) to cacheKey
                        }
                    }
                }.awaitAll()
            }
            val mediaEntries =
                runtime.localMediaSyncStore
                    .listFiles(layout)
                    .mapKeys { (path, _) -> "$WEBDAV_ROOT/$path" }
                    .filterKeys { path ->
                        targetPaths == null || path in targetPaths
                    }
            val mediaFileEntries = coroutineScope {
                mediaEntries.map { (path, metadata) ->
                    async(Dispatchers.IO) {
                        fingerprintLimiter.withPermit {
                            val cacheKey =
                                WebDavLocalFingerprintKey(
                                    path = path,
                                    lastModified = metadata.lastModified,
                                    size = metadata.size,
                                )
                            val localFingerprint =
                                localFingerprintCache.get(cacheKey)
                                    ?: runNonFatalCatching {
                                        runtime.localMediaSyncStore.md5Hex(path, layout)
                                    }.getOrNull()?.also { fingerprint ->
                                        localFingerprintCache.put(cacheKey, fingerprint)
                                    }
                            LocalWebDavFile(
                                path = path,
                                lastModified = metadata.lastModified,
                                size = metadata.size,
                                localFingerprint = localFingerprint,
                            ) to cacheKey
                        }
                    }
                }.awaitAll()
            }
            val validKeys = (memoFileEntries + mediaFileEntries).mapTo(linkedSetOf()) { (_, key) -> key }
            if (pruneCache) {
                localFingerprintCache.retain(validKeys)
            }
            val memoFiles = memoFileEntries.associate { (file, _) -> file.path to file }
            val mediaFiles = mediaFileEntries.associate { (file, _) -> file.path to file }
            return memoFiles + mediaFiles
        }

        suspend fun localFile(
            path: String,
            layout: SyncDirectoryLayout,
        ): LocalWebDavFile? = localFiles(layout, targetPaths = setOf(path), pruneCache = false)[path]

        suspend fun remoteFiles(
            client: WebDavClient,
            layout: SyncDirectoryLayout,
        ): Map<String, RemoteWebDavFile> {
            val folderPaths =
                layout.distinctFolders
                    .map(::remoteFolderPath)
                    .distinct()
            if (folderPaths.isEmpty()) return emptyMap()
            val profile = runtime.performanceTuner.currentProfile()
            val limiter = Semaphore(profile.webDavListConcurrency.coercePositiveConcurrency())
            val listed =
                coroutineScope {
                    folderPaths.map { folderPath ->
                        async(Dispatchers.IO) {
                            limiter.withPermit {
                                listRemoteFilesInFolder(remoteListingCache, client, folderPath)
                            }
                        }
                    }.awaitAll()
                }.flatten()
            return listed.associate(::toRemoteEntry)
        }

        fun remoteFilesInFolder(
            client: WebDavClient,
            folderPath: String,
            forceRefresh: Boolean = false,
        ): Map<String, RemoteWebDavFile> =
            listRemoteFilesInFolder(remoteListingCache, client, folderPath, forceRefresh)
                .associate(::toRemoteEntry)

    suspend fun persistMetadata(
        client: WebDavClient,
        layout: SyncDirectoryLayout,
        localFiles: Map<String, LocalWebDavFile>,
        remoteFiles: Map<String, RemoteWebDavFile>,
        actionOutcomes: Map<String, Pair<WebDavSyncDirection, WebDavSyncReason>>,
        localChanged: Boolean,
        remoteChanged: Boolean,
        unresolvedPaths: Set<String> = emptySet(),
        completeSnapshot: Boolean = true,
    ) {
        val syncedLocalFiles =
            if (localChanged && completeSnapshot) localFiles(layout) else localFiles
        val syncedRemoteFiles = resolveRemoteSnapshot(
            cache = remoteListingCache,
            client = client,
            remoteFiles = remoteFiles,
            actionOutcomes = actionOutcomes,
            remoteChanged = remoteChanged,
        )
        val now = System.currentTimeMillis()
        val intersectionPaths = syncedLocalFiles.keys.intersect(syncedRemoteFiles.keys)
        val candidatePaths =
            if (completeSnapshot) {
                intersectionPaths
            } else {
                actionOutcomes.keys.intersect(intersectionPaths)
            }
        val entities =
            candidatePaths
                .sorted()
                .mapNotNull { path ->
                    val local = syncedLocalFiles[path] ?: return@mapNotNull null
                    val remote = syncedRemoteFiles[path] ?: return@mapNotNull null
                    val outcome = actionOutcomes[path]
                    WebDavSyncMetadataEntity(
                        relativePath = path,
                        remotePath = path,
                        etag = remote.etag,
                        remoteLastModified = remote.lastModified,
                        localLastModified = local.lastModified,
                        localFingerprint = local.localFingerprint,
                        lastSyncedAt = now,
                        lastResolvedDirection = outcome?.first?.name ?: WebDavSyncMetadataEntity.NONE,
                        lastResolvedReason = outcome?.second?.name ?: WebDavSyncMetadataEntity.UNCHANGED,
                    )
                }
        if (completeSnapshot) {
            runtime.metadataDao.replaceAll(
                entities.filterNot { entity -> entity.relativePath in unresolvedPaths },
            )
            return
        }
        val existingPaths = runtime.metadataDao.getAll().map(WebDavSyncMetadataEntity::relativePath).toSet()
        val deletePaths =
            actionOutcomes.keys
                .asSequence()
                .filter { path ->
                    path !in unresolvedPaths &&
                        path !in intersectionPaths &&
                        path in existingPaths
                }.sorted()
                .toList()
        if (deletePaths.isNotEmpty()) {
            runtime.metadataDao.deleteByRelativePaths(deletePaths)
        }
        val upserts = entities.filterNot { entity -> entity.relativePath in unresolvedPaths }
        if (upserts.isNotEmpty()) {
            runtime.metadataDao.upsertAll(upserts)
        }
    }

        fun isMemoPath(
            path: String,
            layout: SyncDirectoryLayout,
        ): Boolean = isWebDavMemoPath(path, layout)

        fun extractMemoFilename(
            path: String,
            layout: SyncDirectoryLayout,
        ): String = extractWebDavMemoFilename(path, layout)

        fun contentTypeForPath(
            path: String,
            layout: SyncDirectoryLayout,
        ): String = webDavContentTypeForPath(path, layout, runtime)
    }

private fun toRemoteEntry(resource: WebDavRemoteResource): Pair<String, RemoteWebDavFile> =
    resource.path to
        RemoteWebDavFile(
            path = resource.path,
            etag = resource.etag,
            lastModified = resource.lastModified,
            size = resource.size,
        )

private fun listRemoteFilesInFolder(
    cache: WebDavRemoteListingCache,
    client: WebDavClient,
    folderPath: String,
    forceRefresh: Boolean = false,
): List<WebDavRemoteResource> =
    if (forceRefresh) {
        cache.invalidate(client, folderPath)
        cache.getOrLoad(client, folderPath) {
            client.list(folderPath).filterNot(WebDavRemoteResource::isDirectory)
        }
    } else {
        cache.getOrLoad(client, folderPath) {
            client.list(folderPath).filterNot(WebDavRemoteResource::isDirectory)
        }
    }

private fun memoRemotePrefix(layout: SyncDirectoryLayout): String = "$WEBDAV_ROOT/${layout.memoFolder}/"

private fun remoteFolderPath(folder: String): String = "$WEBDAV_ROOT/$folder"

private fun resolveRemoteSnapshot(
    cache: WebDavRemoteListingCache,
    client: WebDavClient,
    remoteFiles: Map<String, RemoteWebDavFile>,
    actionOutcomes: Map<String, Pair<WebDavSyncDirection, WebDavSyncReason>>,
    remoteChanged: Boolean,
): Map<String, RemoteWebDavFile> {
    if (!remoteChanged) return remoteFiles
    val changedPaths =
        actionOutcomes
            .asSequence()
            .filter { (_, outcome) ->
                outcome.first == WebDavSyncDirection.UPLOAD ||
                    outcome.first == WebDavSyncDirection.DELETE_REMOTE
            }.map { (path, _) -> path }
            .toSet()
    if (changedPaths.isEmpty()) return remoteFiles
    val foldersToRefresh = changedPaths.mapNotNullTo(mutableSetOf()) { path ->
        val lastSlash = path.lastIndexOf('/')
        if (lastSlash > 0) path.substring(0, lastSlash) else null
    }
    val changedRemote = mutableMapOf<String, RemoteWebDavFile>()
    foldersToRefresh.forEach { folder ->
        runNonFatalCatching {
            cache.invalidate(client, folder)
            listRemoteFilesInFolder(cache, client, folder)
                .forEach { resource ->
                    if (resource.path in changedPaths) {
                        changedRemote[resource.path] = RemoteWebDavFile(
                            path = resource.path,
                            etag = resource.etag,
                            lastModified = resource.lastModified,
                            size = resource.size,
                        )
                    }
                }
        }
    }
    val mergedRemoteFiles = remoteFiles.toMutableMap()
    changedPaths.forEach(mergedRemoteFiles::remove)
    return mergedRemoteFiles + changedRemote
}
