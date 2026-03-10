package com.lomo.data.git

import com.lomo.data.sync.SyncDirectoryLayout
import com.lomo.data.webdav.LocalMediaSyncStore
import com.lomo.data.webdav.MediaSyncCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class GitMediaSyncBridge
    @Inject
    constructor(
        private val localMediaSyncStore: LocalMediaSyncStore,
        private val stateStore: GitMediaSyncStateStore,
        private val planner: GitMediaSyncPlanner,
    ) {
        suspend fun reconcile(
            repoRootDir: File,
            layout: SyncDirectoryLayout,
        ): GitMediaSyncSummary =
            withContext(Dispatchers.IO) {
                val categories = localMediaSyncStore.configuredCategories()
                if (categories.isEmpty()) return@withContext GitMediaSyncSummary()

                val localFiles = listLocalFiles(layout)
                val repoFiles = listRepoFiles(repoRootDir, categories, layout)
                val metadata = stateStore.read()
                val actions = planner.plan(localFiles, repoFiles, metadata)
                val actionsByPath = actions.associateBy { it.path }
                var repoChanged = false
                var localChanged = false

                actions.forEach { action ->
                    when (action.direction) {
                        GitMediaSyncDirection.PUSH_TO_REPO -> {
                            if (pushToRepo(repoRootDir, action.path, localFiles[action.path], repoFiles[action.path], layout)) {
                                repoChanged = true
                            }
                        }

                        GitMediaSyncDirection.PULL_TO_LOCAL -> {
                            if (pullToLocal(repoRootDir, action.path, localFiles[action.path], repoFiles[action.path], layout)) {
                                localChanged = true
                            }
                        }

                        GitMediaSyncDirection.DELETE_REPO -> {
                            if (deleteRepoFile(repoRootDir, action.path)) {
                                repoChanged = true
                            }
                        }

                        GitMediaSyncDirection.DELETE_LOCAL -> {
                            if (deleteLocalFile(action.path, localFiles[action.path], layout)) {
                                localChanged = true
                            }
                        }
                    }
                }

                persistState(
                    repoRootDir = repoRootDir,
                    categories = categories,
                    layout = layout,
                    previousMetadata = metadata,
                    localFiles = localFiles,
                    repoFiles = repoFiles,
                    localChanged = localChanged,
                    repoChanged = repoChanged,
                    actionsByPath = actionsByPath,
                )

                GitMediaSyncSummary(repoChanged = repoChanged, localChanged = localChanged)
            }

        private suspend fun persistState(
            repoRootDir: File,
            categories: Set<MediaSyncCategory>,
            layout: SyncDirectoryLayout,
            previousMetadata: Map<String, GitMediaSyncMetadataEntry>,
            localFiles: Map<String, LocalGitMediaFile>,
            repoFiles: Map<String, RepoGitMediaFile>,
            localChanged: Boolean,
            repoChanged: Boolean,
            actionsByPath: Map<String, GitMediaSyncAction>,
        ) {
            val refreshedLocalFiles =
                if (localChanged) {
                    listLocalFiles(layout)
                } else {
                    localFiles
                }
            val refreshedRepoFiles =
                if (repoChanged) {
                    listRepoFiles(repoRootDir, categories, layout)
                } else {
                    repoFiles
                }
            val now = System.currentTimeMillis()
            val entries =
                (refreshedLocalFiles.keys intersect refreshedRepoFiles.keys)
                    .sorted()
                    .map { path ->
                        val local = refreshedLocalFiles.getValue(path)
                        val repo = refreshedRepoFiles.getValue(path)
                        val action = actionsByPath[path]
                        val previous = previousMetadata[path]
                        GitMediaSyncMetadataEntry(
                            relativePath = path,
                            repoLastModified = repo.lastModified,
                            localLastModified = local.lastModified,
                            lastSyncedAt = now,
                            lastResolvedDirection =
                                action?.direction?.name ?: previous?.lastResolvedDirection ?: GitMediaSyncMetadataEntry.NONE,
                            lastResolvedReason = action?.reason?.name ?: previous?.lastResolvedReason ?: GitMediaSyncMetadataEntry.UNCHANGED,
                        )
                    }
            stateStore.write(entries)
        }

        private suspend fun listLocalFiles(layout: SyncDirectoryLayout): Map<String, LocalGitMediaFile> =
            localMediaSyncStore
                .listFiles(layout)
                .mapValues { (path, file) ->
                    LocalGitMediaFile(
                        path = path,
                        lastModified = file.lastModified,
                    )
                }

        private fun listRepoFiles(
            repoRootDir: File,
            categories: Set<MediaSyncCategory>,
            layout: SyncDirectoryLayout,
        ): Map<String, RepoGitMediaFile> =
            buildMap {
                if (layout.allSameDirectory) {
                    // When all directories are the same, media files live directly in the repo root.
                    categories.forEach { category ->
                        val folder = category.remoteFolder(layout)
                        repoRootDir.listFiles()?.forEach { file ->
                            if (!file.isFile || !accepts(category, file.name)) return@forEach
                            val path = "$folder/${file.name}"
                            put(path, RepoGitMediaFile(path = path, lastModified = file.lastModified()))
                        }
                    }
                } else {
                    categories.forEach { category ->
                        val folder = category.remoteFolder(layout)
                        val directory = File(repoRootDir, folder)
                        if (!directory.exists() || !directory.isDirectory) return@forEach
                        directory.listFiles()?.forEach { file ->
                            if (!file.isFile || !accepts(category, file.name)) return@forEach
                            val path = "$folder/${file.name}"
                            put(path, RepoGitMediaFile(path = path, lastModified = file.lastModified()))
                        }
                    }
                }
            }

        private suspend fun pushToRepo(
            repoRootDir: File,
            path: String,
            local: LocalGitMediaFile?,
            repo: RepoGitMediaFile?,
            layout: SyncDirectoryLayout,
        ): Boolean {
            val localFile = local ?: return false
            val localBytes = localMediaSyncStore.readBytes(path, layout)
            val repoPath = repoRelativePath(path, layout)
            val target = File(repoRootDir, repoPath)
            if (repo != null && target.exists()) {
                val repoBytes = target.readBytes()
                if (repoBytes.contentEquals(localBytes)) {
                    if (localFile.lastModified > 0L && abs(target.lastModified() - localFile.lastModified) > TIMESTAMP_TOLERANCE_MS) {
                        target.setLastModified(localFile.lastModified)
                    }
                    return false
                }
            }
            writeRepoFile(repoRootDir, repoPath, localBytes, localFile.lastModified)
            return true
        }

        private suspend fun pullToLocal(
            repoRootDir: File,
            path: String,
            local: LocalGitMediaFile?,
            repo: RepoGitMediaFile?,
            layout: SyncDirectoryLayout,
        ): Boolean {
            val repoFile = repo ?: return false
            val repoPath = repoRelativePath(repoFile.path, layout)
            val source = File(repoRootDir, repoPath)
            if (!source.exists()) return false
            val repoBytes = source.readBytes()
            if (local != null) {
                val localBytes = localMediaSyncStore.readBytes(path, layout)
                if (localBytes.contentEquals(repoBytes)) {
                    return false
                }
            }
            localMediaSyncStore.writeBytes(path, repoBytes, layout)
            return true
        }

        private suspend fun deleteLocalFile(
            path: String,
            local: LocalGitMediaFile?,
            layout: SyncDirectoryLayout,
        ): Boolean {
            if (local == null) return false
            localMediaSyncStore.delete(path, layout)
            return true
        }

        private fun deleteRepoFile(
            repoRootDir: File,
            relativePath: String,
        ): Boolean {
            val target = File(repoRootDir, relativePath)
            return target.exists() && target.delete()
        }

        private fun writeRepoFile(
            repoRootDir: File,
            relativePath: String,
            bytes: ByteArray,
            lastModified: Long,
        ) {
            val target = File(repoRootDir, relativePath)
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
            if (lastModified > 0L) {
                target.setLastModified(lastModified)
            }
        }

        /**
         * When [SyncDirectoryLayout.allSameDirectory] is true, media files live directly
         * in the repo root so we strip the folder prefix. Otherwise, the path already
         * contains the correct subdirectory.
         */
        private fun repoRelativePath(
            syncPath: String,
            layout: SyncDirectoryLayout,
        ): String =
            if (layout.allSameDirectory) {
                // "images/photo.jpg" → "photo.jpg" (flat in repo root)
                syncPath.substringAfter('/')
            } else {
                syncPath
            }

        private fun accepts(
            category: MediaSyncCategory,
            filename: String,
        ): Boolean =
            when (category) {
                MediaSyncCategory.IMAGE -> filename.hasExtension(IMAGE_EXTENSIONS)
                MediaSyncCategory.VOICE -> filename.hasExtension(VOICE_EXTENSIONS)
            }

        private fun String.hasExtension(extensions: Set<String>): Boolean =
            substringAfterLast('.', "").lowercase().let { it.isNotBlank() && it in extensions }

        companion object {
            private const val TIMESTAMP_TOLERANCE_MS = 1000L
            private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif")
            private val VOICE_EXTENSIONS = setOf("m4a", "mp3", "aac", "wav", "ogg")
        }
    }

data class GitMediaSyncSummary(
    val repoChanged: Boolean = false,
    val localChanged: Boolean = false,
)
