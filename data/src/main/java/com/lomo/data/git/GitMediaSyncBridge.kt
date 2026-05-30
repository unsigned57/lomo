package com.lomo.data.git

import com.lomo.data.sync.SyncDirectoryLayout
import com.lomo.data.webdav.LocalMediaSyncStore
import com.lomo.data.webdav.MediaSyncCategory
import com.lomo.domain.model.MediaFileExtensions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GitMediaSyncBridge
    @Inject
    constructor(
        private val localMediaSyncStore: LocalMediaSyncStore,
        private val stateStore: GitMediaSyncStateStore,
        private val planner: GitMediaSyncPlanner,
        private val fingerprintIndex: GitMediaSyncFingerprintIndex,
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

                val metadata = stateStore.read()
                val localFiles = fingerprintIndex.localFiles(layout, metadata)
                val repoFiles = fingerprintIndex.repoFiles(repoRootDir, categories, layout, metadata)
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
                                    layout = layout,
                                )
                    }

                    GitMediaSyncDirection.PULL_TO_LOCAL -> {
                        localChanged =
                            localChanged ||
                                pullToLocal(
                                    repoRootDir = repoRootDir,
                                    path = action.path,
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
                    fingerprintIndex.localFiles(layout, previousMetadata)
                } else {
                    localFiles
                }
            val refreshedRepoFiles =
                if (repoChanged) {
                    fingerprintIndex.repoFiles(repoRootDir, categories, layout, previousMetadata)
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
                            repoSize = repo.size,
                            localSize = local.size,
                            repoFingerprint = repo.fingerprint,
                            localFingerprint = local.fingerprint,
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

        private suspend fun pushToRepo(
            repoRootDir: File,
            path: String,
            local: LocalGitMediaFile?,
            layout: SyncDirectoryLayout,
        ): Boolean {
            val localFile = local ?: return false
            val repoPath = repoRelativePath(path, layout)
            val target = File(repoRootDir, repoPath)
            target.parentFile?.mkdirs()
            localMediaSyncStore.exportToFile(path, layout, target)
            if (localFile.lastModified > 0L) {
                target.setLastModified(localFile.lastModified)
            }
            return true
        }

        private suspend fun pullToLocal(
            repoRootDir: File,
            path: String,
            repo: RepoGitMediaFile?,
            layout: SyncDirectoryLayout,
        ): Boolean {
            val repoFile =
                repo
                    ?.let { repoFile -> File(repoRootDir, repoRelativePath(repoFile.path, layout)) }
                    ?.takeIf(File::exists)
                    ?: return false
            localMediaSyncStore.importFromFile(path, repoFile, layout)
            return true
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

    }

data class GitMediaSyncSummary(
    val repoChanged: Boolean = false,
    val localChanged: Boolean = false,
)

@Singleton
class GitMediaSyncFingerprintIndex
    @Inject
    constructor(
        private val localMediaSyncStore: LocalMediaSyncStore,
    ) {
        suspend fun localFiles(
            layout: SyncDirectoryLayout,
            metadata: Map<String, GitMediaSyncMetadataEntry>,
        ): Map<String, LocalGitMediaFile> =
            localMediaSyncStore
                .listFiles(layout)
                .mapValues { (path, file) ->
                    val previous = metadata[path]
                    val fingerprint =
                        previous?.localFingerprint
                            ?.takeIf {
                                previous.localLastModified == file.lastModified &&
                                    previous.localSize == file.size
                            } ?: localMediaSyncStore.md5Hex(path, layout)
                    LocalGitMediaFile(
                        path = path,
                        lastModified = file.lastModified,
                        size = file.size,
                        fingerprint = fingerprint,
                    )
                }

        fun repoFiles(
            repoRootDir: File,
            categories: Set<MediaSyncCategory>,
            layout: SyncDirectoryLayout,
            metadata: Map<String, GitMediaSyncMetadataEntry>,
        ): Map<String, RepoGitMediaFile> =
            categories
                .flatMap { category -> listRepoFilesForCategory(repoRootDir, category, layout, metadata) }
                .associateBy(RepoGitMediaFile::path)
    }

private fun listRepoFilesForCategory(
    repoRootDir: File,
    category: MediaSyncCategory,
    layout: SyncDirectoryLayout,
    metadata: Map<String, GitMediaSyncMetadataEntry>,
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
            val lastModified = file.lastModified()
            val size = file.length()
            val previous = metadata[path]
            val fingerprint =
                previous?.repoFingerprint
                    ?.takeIf {
                        previous.repoLastModified == lastModified &&
                            previous.repoSize == size
                    } ?: file.md5Hex()
            RepoGitMediaFile(
                path = path,
                lastModified = lastModified,
                size = size,
                fingerprint = fingerprint,
            )
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
        MediaSyncCategory.IMAGE -> filename.hasExtension(MediaFileExtensions.IMAGE)
        MediaSyncCategory.VOICE -> filename.hasExtension(MediaFileExtensions.AUDIO)
    }

private fun String.hasExtension(extensions: Set<String>): Boolean =
    substringAfterLast('.', "").lowercase(java.util.Locale.ROOT).let { extension ->
        extension.isNotBlank() && extension in extensions
    }

private fun File.md5Hex(): String =
    inputStream().use { input ->
        val digest = MessageDigest.getInstance("MD5")
        val buffer = ByteArray(FILE_DIGEST_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
        digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

private const val FILE_DIGEST_BUFFER_SIZE = 16 * 1024
