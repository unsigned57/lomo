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
                if (categories.isEmpty()) {
                    return@withContext GitMediaSyncSummary()
                }

                val localFiles = listLocalFiles(layout)
                val repoFiles = listRepoFiles(repoRootDir, categories, layout)
                val metadata = stateStore.read()
                val actions = planner.plan(localFiles, repoFiles, metadata)
                val actionsByPath = actions.associateBy(GitMediaSyncAction::path)
                val summary =
                    applyActions(
                        repoRootDir = repoRootDir,
                        layout = layout,
                        localFiles = localFiles,
                        repoFiles = repoFiles,
                        actions = actions,
                    )

                persistState(
                    repoRootDir = repoRootDir,
                    categories = categories,
                    layout = layout,
                    previousMetadata = metadata,
                    localFiles = localFiles,
                    repoFiles = repoFiles,
                    localChanged = summary.localChanged,
                    repoChanged = summary.repoChanged,
                    actionsByPath = actionsByPath,
                )

                summary
            }

        private suspend fun applyActions(
            repoRootDir: File,
            layout: SyncDirectoryLayout,
            localFiles: Map<String, LocalGitMediaFile>,
            repoFiles: Map<String, RepoGitMediaFile>,
            actions: List<GitMediaSyncAction>,
        ): GitMediaSyncSummary {
            var repoChanged = false
            var localChanged = false

            actions.forEach { action ->
                when (action.direction) {
                    GitMediaSyncDirection.PUSH_TO_REPO -> {
                        repoChanged =
                            repoChanged ||
                                pushToRepo(
                                    repoRootDir = repoRootDir,
                                    path = action.path,
                                    local = localFiles[action.path],
                                    repo = repoFiles[action.path],
                                    layout = layout,
                                )
                    }

                    GitMediaSyncDirection.PULL_TO_LOCAL -> {
                        localChanged =
                            localChanged ||
                                pullToLocal(
                                    repoRootDir = repoRootDir,
                                    path = action.path,
                                    local = localFiles[action.path],
                                    repo = repoFiles[action.path],
                                    layout = layout,
                                )
                    }

                    GitMediaSyncDirection.DELETE_REPO -> {
                        repoChanged = repoChanged || deleteRepoFile(repoRootDir, action.path)
                    }

                    GitMediaSyncDirection.DELETE_LOCAL -> {
                        localChanged =
                            localChanged ||
                                deleteLocalFile(
                                    path = action.path,
                                    local = localFiles[action.path],
                                    layout = layout,
                                )
                    }
                }
            }

            return GitMediaSyncSummary(repoChanged = repoChanged, localChanged = localChanged)
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
                                action?.direction?.name
                                    ?: previous?.lastResolvedDirection
                                    ?: GitMediaSyncMetadataEntry.NONE,
                            lastResolvedReason =
                                action?.reason?.name
                                    ?: previous?.lastResolvedReason
                                    ?: GitMediaSyncMetadataEntry.UNCHANGED,
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
            categories
                .flatMap { category -> listRepoFilesForCategory(repoRootDir, category, layout) }
                .associateBy(RepoGitMediaFile::path)

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
            val shouldWrite =
                !(repo != null && target.exists() && target.readBytes().contentEquals(localBytes))

            if (shouldWrite) {
                writeRepoFile(
                    repoRootDir = repoRootDir,
                    relativePath = repoPath,
                    bytes = localBytes,
                    lastModified = localFile.lastModified,
                )
            } else if (
                localFile.lastModified > 0L &&
                abs(target.lastModified() - localFile.lastModified) > TIMESTAMP_TOLERANCE_MS
            ) {
                target.setLastModified(localFile.lastModified)
            }

            return shouldWrite
        }

        private suspend fun pullToLocal(
            repoRootDir: File,
            path: String,
            local: LocalGitMediaFile?,
            repo: RepoGitMediaFile?,
            layout: SyncDirectoryLayout,
        ): Boolean {
            val repoBytes =
                repo
                    ?.let { repoFile -> File(repoRootDir, repoRelativePath(repoFile.path, layout)) }
                    ?.takeIf(File::exists)
                    ?.readBytes()
            val shouldWrite =
                if (repoBytes == null) {
                    false
                } else {
                    local
                        ?.let { current ->
                            !localMediaSyncStore
                                .readBytes(current.path, layout)
                                .contentEquals(repoBytes)
                        }
                        ?: true
                }

            if (shouldWrite && repoBytes != null) {
                localMediaSyncStore.writeBytes(path, repoBytes, layout)
            }

            return shouldWrite
        }

        private suspend fun deleteLocalFile(
            path: String,
            local: LocalGitMediaFile?,
            layout: SyncDirectoryLayout,
        ): Boolean {
            if (local == null) {
                return false
            }
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

        companion object {
            private const val TIMESTAMP_TOLERANCE_MS = 1000L
        }
    }

data class GitMediaSyncSummary(
    val repoChanged: Boolean = false,
    val localChanged: Boolean = false,
)

private fun listRepoFilesForCategory(
    repoRootDir: File,
    category: MediaSyncCategory,
    layout: SyncDirectoryLayout,
): List<RepoGitMediaFile> {
    val folder = category.remoteFolder(layout)
    val directory =
        if (layout.allSameDirectory) {
            repoRootDir
        } else {
            File(repoRootDir, folder).takeIf(File::isDirectory) ?: return emptyList()
        }

    return directory
        .listFiles()
        .orEmpty()
        .filter { file -> file.isFile && accepts(category, file.name) }
        .map { file ->
            val path = "$folder/${file.name}"
            RepoGitMediaFile(path = path, lastModified = file.lastModified())
        }
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

private fun repoRelativePath(
    syncPath: String,
    layout: SyncDirectoryLayout,
): String =
    if (layout.allSameDirectory) {
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
    substringAfterLast('.', "").lowercase().let { extension ->
        extension.isNotBlank() && extension in extensions
    }

private val IMAGE_EXTENSIONS =
    setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif")

private val VOICE_EXTENSIONS = setOf("m4a", "mp3", "aac", "wav", "ogg")
