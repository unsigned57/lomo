package com.lomo.data.git

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
        suspend fun reconcile(repoRootDir: File): GitMediaSyncSummary =
            withContext(Dispatchers.IO) {
                val categories = localMediaSyncStore.configuredCategories()
                if (categories.isEmpty()) return@withContext GitMediaSyncSummary()

                val localFiles = listLocalFiles()
                val repoFiles = listRepoFiles(repoRootDir, categories)
                val metadata = stateStore.read()
                val actions = planner.plan(localFiles, repoFiles, metadata)
                val actionsByPath = actions.associateBy { it.path }
                var repoChanged = false
                var localChanged = false

                actions.forEach { action ->
                    when (action.direction) {
                        GitMediaSyncDirection.PUSH_TO_REPO -> {
                            if (pushToRepo(repoRootDir, action.path, localFiles[action.path], repoFiles[action.path])) {
                                repoChanged = true
                            }
                        }

                        GitMediaSyncDirection.PULL_TO_LOCAL -> {
                            if (pullToLocal(repoRootDir, action.path, localFiles[action.path], repoFiles[action.path])) {
                                localChanged = true
                            }
                        }

                        GitMediaSyncDirection.DELETE_REPO -> {
                            if (deleteRepoFile(repoRootDir, action.path)) {
                                repoChanged = true
                            }
                        }

                        GitMediaSyncDirection.DELETE_LOCAL -> {
                            if (deleteLocalFile(action.path, localFiles[action.path])) {
                                localChanged = true
                            }
                        }
                    }
                }

                persistState(
                    repoRootDir = repoRootDir,
                    categories = categories,
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
            previousMetadata: Map<String, GitMediaSyncMetadataEntry>,
            localFiles: Map<String, LocalGitMediaFile>,
            repoFiles: Map<String, RepoGitMediaFile>,
            localChanged: Boolean,
            repoChanged: Boolean,
            actionsByPath: Map<String, GitMediaSyncAction>,
        ) {
            val refreshedLocalFiles =
                if (localChanged) {
                    listLocalFiles()
                } else {
                    localFiles
                }
            val refreshedRepoFiles =
                if (repoChanged) {
                    listRepoFiles(repoRootDir, categories)
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

        private suspend fun listLocalFiles(): Map<String, LocalGitMediaFile> =
            localMediaSyncStore
                .listFiles()
                .mapValues { (path, file) ->
                    LocalGitMediaFile(
                        path = path,
                        lastModified = file.lastModified,
                    )
                }

        private fun listRepoFiles(
            repoRootDir: File,
            categories: Set<MediaSyncCategory>,
        ): Map<String, RepoGitMediaFile> =
            buildMap {
                categories.forEach { category ->
                    val directory = File(repoRootDir, category.remoteFolder)
                    if (!directory.exists() || !directory.isDirectory) return@forEach
                    directory.listFiles()?.forEach { file ->
                        if (!file.isFile || !accepts(category, file.name)) return@forEach
                        val path = "${category.remoteFolder}/${file.name}"
                        put(
                            path,
                            RepoGitMediaFile(
                                path = path,
                                lastModified = file.lastModified(),
                            ),
                        )
                    }
                }
            }

        private suspend fun pushToRepo(
            repoRootDir: File,
            path: String,
            local: LocalGitMediaFile?,
            repo: RepoGitMediaFile?,
        ): Boolean {
            val localFile = local ?: return false
            val localBytes = localMediaSyncStore.readBytes(path)
            val target = File(repoRootDir, path)
            if (repo != null && target.exists()) {
                val repoBytes = target.readBytes()
                if (repoBytes.contentEquals(localBytes)) {
                    if (localFile.lastModified > 0L && abs(target.lastModified() - localFile.lastModified) > TIMESTAMP_TOLERANCE_MS) {
                        target.setLastModified(localFile.lastModified)
                    }
                    return false
                }
            }
            writeRepoFile(repoRootDir, path, localBytes, localFile.lastModified)
            return true
        }

        private suspend fun pullToLocal(
            repoRootDir: File,
            path: String,
            local: LocalGitMediaFile?,
            repo: RepoGitMediaFile?,
        ): Boolean {
            val repoFile = repo ?: return false
            val source = File(repoRootDir, repoFile.path)
            if (!source.exists()) return false
            val repoBytes = source.readBytes()
            if (local != null) {
                val localBytes = localMediaSyncStore.readBytes(path)
                if (localBytes.contentEquals(repoBytes)) {
                    return false
                }
            }
            localMediaSyncStore.writeBytes(path, repoBytes)
            return true
        }

        private suspend fun deleteLocalFile(
            path: String,
            local: LocalGitMediaFile?,
        ): Boolean {
            if (local == null) return false
            localMediaSyncStore.delete(path)
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
