package com.lomo.data.git

import kotlin.math.abs

data class LocalGitMediaFile(
    val path: String,
    val lastModified: Long,
)

data class RepoGitMediaFile(
    val path: String,
    val lastModified: Long,
)

enum class GitMediaSyncDirection {
    PUSH_TO_REPO,
    PULL_TO_LOCAL,
    DELETE_REPO,
    DELETE_LOCAL,
}

enum class GitMediaSyncReason {
    LOCAL_ONLY,
    REPO_ONLY,
    LOCAL_NEWER,
    REPO_NEWER,
    LOCAL_DELETED,
    REPO_DELETED,
    SAME_TIMESTAMP,
}

data class GitMediaSyncAction(
    val path: String,
    val direction: GitMediaSyncDirection,
    val reason: GitMediaSyncReason,
)

class GitMediaSyncPlanner(
    private val timestampToleranceMs: Long = 1000L,
) {
    fun plan(
        localFiles: Map<String, LocalGitMediaFile>,
        repoFiles: Map<String, RepoGitMediaFile>,
        metadata: Map<String, GitMediaSyncMetadataEntry>,
    ): List<GitMediaSyncAction> =
        buildList {
            (localFiles.keys + repoFiles.keys + metadata.keys)
                .sorted()
                .forEach { path ->
                    createAction(
                        path = path,
                        local = localFiles[path],
                        repo = repoFiles[path],
                        metadata = metadata[path],
                    )?.let(::add)
                }
        }

    private fun createAction(
        path: String,
        local: LocalGitMediaFile?,
        repo: RepoGitMediaFile?,
        metadata: GitMediaSyncMetadataEntry?,
    ): GitMediaSyncAction? =
        when {
            local != null && repo != null -> handleBothPresent(path, local, repo, metadata)
            local != null -> handleLocalOnly(path, local, metadata)
            repo != null -> handleRepoOnly(path, repo, metadata)
            else -> null
        }

    private fun handleBothPresent(
        path: String,
        local: LocalGitMediaFile,
        repo: RepoGitMediaFile,
        metadata: GitMediaSyncMetadataEntry?,
    ): GitMediaSyncAction? {
        if (metadata == null) {
            return newerWins(path, local.lastModified, repo.lastModified)
        }

        val localChanged = changed(local.lastModified, metadata.localLastModified)
        val repoChanged = changed(repo.lastModified, metadata.repoLastModified)

        return when {
            !localChanged && !repoChanged -> null
            localChanged && !repoChanged ->
                GitMediaSyncAction(path, GitMediaSyncDirection.PUSH_TO_REPO, GitMediaSyncReason.LOCAL_ONLY)

            !localChanged && repoChanged ->
                GitMediaSyncAction(path, GitMediaSyncDirection.PULL_TO_LOCAL, GitMediaSyncReason.REPO_ONLY)

            else -> newerWins(path, local.lastModified, repo.lastModified)
        }
    }

    private fun handleLocalOnly(
        path: String,
        local: LocalGitMediaFile,
        metadata: GitMediaSyncMetadataEntry?,
    ): GitMediaSyncAction =
        when {
            metadata == null -> {
                GitMediaSyncAction(path, GitMediaSyncDirection.PUSH_TO_REPO, GitMediaSyncReason.LOCAL_ONLY)
            }

            !changed(local.lastModified, metadata.localLastModified) -> {
                GitMediaSyncAction(path, GitMediaSyncDirection.DELETE_LOCAL, GitMediaSyncReason.REPO_DELETED)
            }

            else -> {
                val repoReference = metadata.repoLastModified ?: metadata.lastSyncedAt
                if (local.lastModified >= repoReference) {
                    GitMediaSyncAction(path, GitMediaSyncDirection.PUSH_TO_REPO, GitMediaSyncReason.LOCAL_NEWER)
                } else {
                    GitMediaSyncAction(path, GitMediaSyncDirection.DELETE_LOCAL, GitMediaSyncReason.REPO_DELETED)
                }
            }
        }

    private fun handleRepoOnly(
        path: String,
        repo: RepoGitMediaFile,
        metadata: GitMediaSyncMetadataEntry?,
    ): GitMediaSyncAction =
        when {
            metadata == null -> {
                GitMediaSyncAction(path, GitMediaSyncDirection.PULL_TO_LOCAL, GitMediaSyncReason.REPO_ONLY)
            }

            !changed(repo.lastModified, metadata.repoLastModified) -> {
                GitMediaSyncAction(path, GitMediaSyncDirection.DELETE_REPO, GitMediaSyncReason.LOCAL_DELETED)
            }

            else -> {
                val localReference = metadata.localLastModified ?: metadata.lastSyncedAt
                if (repo.lastModified >= localReference) {
                    GitMediaSyncAction(path, GitMediaSyncDirection.PULL_TO_LOCAL, GitMediaSyncReason.REPO_NEWER)
                } else {
                    GitMediaSyncAction(path, GitMediaSyncDirection.DELETE_REPO, GitMediaSyncReason.LOCAL_DELETED)
                }
            }
        }

    private fun newerWins(
        path: String,
        localTimestamp: Long,
        repoTimestamp: Long,
    ): GitMediaSyncAction =
        if (abs(localTimestamp - repoTimestamp) <= timestampToleranceMs) {
            GitMediaSyncAction(path, GitMediaSyncDirection.PUSH_TO_REPO, GitMediaSyncReason.SAME_TIMESTAMP)
        } else if (localTimestamp > repoTimestamp) {
            GitMediaSyncAction(path, GitMediaSyncDirection.PUSH_TO_REPO, GitMediaSyncReason.LOCAL_NEWER)
        } else {
            GitMediaSyncAction(path, GitMediaSyncDirection.PULL_TO_LOCAL, GitMediaSyncReason.REPO_NEWER)
        }

    private fun changed(
        current: Long?,
        previous: Long?,
    ): Boolean =
        if (current == null || previous == null) {
            current != previous
        } else {
            abs(current - previous) > timestampToleranceMs
        }
}
