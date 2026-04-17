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

@Singleton
class WebDavSyncFileBridge
    @Inject
    constructor(
        private val runtime: WebDavSyncRepositoryContext,
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

        suspend fun localFiles(layout: SyncDirectoryLayout): Map<String, LocalWebDavFile> {
            val memoPrefix = memoRemotePrefix(layout)
            val memoFiles =
                runtime.markdownStorageDataSource
                    .listMetadataIn(MemoDirectoryType.MAIN)
                    .filter { it.filename.endsWith(WEBDAV_MEMO_SUFFIX) }
                    .associate { metadata ->
                        val content =
                            runtime.markdownStorageDataSource.readFileIn(
                                MemoDirectoryType.MAIN,
                                metadata.filename,
                            )
                        val remotePath = "$memoPrefix${metadata.filename}"
                        remotePath to
                            LocalWebDavFile(
                                path = remotePath,
                                lastModified = metadata.lastModified,
                                localFingerprint =
                                    content
                                        ?.toByteArray(StandardCharsets.UTF_8)
                                        ?.md5Hex(),
                            )
                    }
            val mediaFiles =
                runtime.localMediaSyncStore
                    .listFiles(layout)
                    .mapKeys { (path, _) -> "$WEBDAV_ROOT/$path" }
                    .mapValues { (path, metadata) ->
                        LocalWebDavFile(
                            path = path,
                            lastModified = metadata.lastModified,
                            localFingerprint =
                                runNonFatalCatching {
                                    runtime.localMediaSyncStore.readBytes(path, layout).md5Hex()
                                }.getOrNull(),
                        )
                    }
            return memoFiles + mediaFiles
        }

        fun remoteFiles(
            client: WebDavClient,
            layout: SyncDirectoryLayout,
        ): Map<String, RemoteWebDavFile> {
            val listed = mutableListOf<WebDavRemoteResource>()
            val visitedFolders = mutableSetOf<String>()
            layout.distinctFolders.forEach { folder ->
                val remotePath = remoteFolderPath(folder)
                if (visitedFolders.add(remotePath)) {
                    listed.addAll(client.list(remotePath).filterNot(WebDavRemoteResource::isDirectory))
                }
            }
            return listed.associate(::toRemoteEntry)
        }

        fun remoteFilesInFolder(
            client: WebDavClient,
            folderPath: String,
        ): Map<String, RemoteWebDavFile> =
            client.list(folderPath)
                .filterNot(WebDavRemoteResource::isDirectory)
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
        val syncedLocalFiles = if (localChanged) localFiles(layout) else localFiles
        val syncedRemoteFiles =
            if (remoteChanged) {
                runNonFatalCatching {
                        remoteFiles(client, layout)
                    }.getOrElse { error ->
                        val message = error.message ?: WEBDAV_UNKNOWN_ERROR_MESSAGE
                        throw IllegalStateException(
                            "Failed to reload remote WebDAV files after sync: $message",
                            error,
                        )
                    }
                } else {
                    remoteFiles
                }
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
        ): Boolean {
            val memoPrefix = memoRemotePrefix(layout)
            return path.startsWith(memoPrefix) && path.endsWith(WEBDAV_MEMO_SUFFIX)
        }

        fun extractMemoFilename(
            path: String,
            layout: SyncDirectoryLayout,
        ): String {
            return path.removePrefix(memoRemotePrefix(layout))
        }

        fun contentTypeForPath(
            path: String,
            layout: SyncDirectoryLayout,
        ): String =
            if (isMemoPath(path, layout)) {
                WEBDAV_MARKDOWN_CONTENT_TYPE
            } else {
                runtime.localMediaSyncStore.contentTypeForPath(path, layout)
            }

        private fun toRemoteEntry(resource: WebDavRemoteResource): Pair<String, RemoteWebDavFile> =
            resource.path to
                RemoteWebDavFile(
                    path = resource.path,
                    etag = resource.etag,
                    lastModified = resource.lastModified,
                )

        private fun memoRemotePrefix(layout: SyncDirectoryLayout): String = "$WEBDAV_ROOT/${layout.memoFolder}/"

        private fun remoteFolderPath(folder: String): String = "$WEBDAV_ROOT/$folder"
    }
