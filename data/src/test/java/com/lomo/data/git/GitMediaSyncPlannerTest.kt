package com.lomo.data.git

import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: GitMediaSyncPlanner
 * - Behavior focus: media sync direction selection for local-only, repo-only, changed, deleted, and tolerance-window cases.
 * - Observable outcomes: planned GitMediaSyncAction direction/reason list for each path.
 * - Red phase: Not applicable - test-only coverage addition; no production change.
 * - Excludes: actual file I/O, Git transport, and metadata store persistence.
 */
class GitMediaSyncPlannerTest {
    private val planner = GitMediaSyncPlanner(timestampToleranceMs = 1000L)

    @Test
    fun `plan resolves both-present combinations including unchanged local-only repo-only and newer wins`() {
        val actions =
            planner.plan(
                localFiles =
                    mapOf(
                        "local-changed.jpg" to LocalGitMediaFile("local-changed.jpg", 3_500L),
                        "repo-changed.jpg" to LocalGitMediaFile("repo-changed.jpg", 2_000L),
                        "same-time.jpg" to LocalGitMediaFile("same-time.jpg", 5_100L),
                        "newer-local.jpg" to LocalGitMediaFile("newer-local.jpg", 8_300L),
                        "newer-repo.jpg" to LocalGitMediaFile("newer-repo.jpg", 7_200L),
                    ),
                repoFiles =
                    mapOf(
                        "local-changed.jpg" to RepoGitMediaFile("local-changed.jpg", 2_000L),
                        "repo-changed.jpg" to RepoGitMediaFile("repo-changed.jpg", 3_500L),
                        "same-time.jpg" to RepoGitMediaFile("same-time.jpg", 5_500L),
                        "newer-local.jpg" to RepoGitMediaFile("newer-local.jpg", 7_100L),
                        "newer-repo.jpg" to RepoGitMediaFile("newer-repo.jpg", 9_500L),
                    ),
                metadata =
                    mapOf(
                        "local-changed.jpg" to metadata("local-changed.jpg", local = 2_000L, repo = 2_000L),
                        "repo-changed.jpg" to metadata("repo-changed.jpg", local = 2_000L, repo = 2_000L),
                        "same-time.jpg" to metadata("same-time.jpg", local = 4_000L, repo = 4_000L),
                        "newer-local.jpg" to metadata("newer-local.jpg", local = 6_000L, repo = 6_000L),
                        "newer-repo.jpg" to metadata("newer-repo.jpg", local = 6_000L, repo = 6_000L),
                    ),
            )

        assertEquals(
            listOf(
                GitMediaSyncAction("local-changed.jpg", GitMediaSyncDirection.PUSH_TO_REPO, GitMediaSyncReason.LOCAL_ONLY),
                GitMediaSyncAction("newer-local.jpg", GitMediaSyncDirection.PUSH_TO_REPO, GitMediaSyncReason.LOCAL_NEWER),
                GitMediaSyncAction("newer-repo.jpg", GitMediaSyncDirection.PULL_TO_LOCAL, GitMediaSyncReason.REPO_NEWER),
                GitMediaSyncAction("repo-changed.jpg", GitMediaSyncDirection.PULL_TO_LOCAL, GitMediaSyncReason.REPO_ONLY),
                GitMediaSyncAction("same-time.jpg", GitMediaSyncDirection.PUSH_TO_REPO, GitMediaSyncReason.SAME_TIMESTAMP),
            ),
            actions,
        )
    }

    @Test
    fun `plan resolves local-only and repo-only files using metadata deletion hints`() {
        val actions =
            planner.plan(
                localFiles =
                    mapOf(
                        "brand-new-local.m4a" to LocalGitMediaFile("brand-new-local.m4a", 10_000L),
                        "repo-deleted.m4a" to LocalGitMediaFile("repo-deleted.m4a", 9_000L),
                        "local-newer-than-repo-ref.m4a" to LocalGitMediaFile("local-newer-than-repo-ref.m4a", 20_000L),
                    ),
                repoFiles =
                    mapOf(
                        "brand-new-repo.m4a" to RepoGitMediaFile("brand-new-repo.m4a", 11_000L),
                        "local-deleted.m4a" to RepoGitMediaFile("local-deleted.m4a", 9_000L),
                        "repo-newer-than-local-ref.m4a" to RepoGitMediaFile("repo-newer-than-local-ref.m4a", 20_000L),
                    ),
                metadata =
                    mapOf(
                        "repo-deleted.m4a" to metadata("repo-deleted.m4a", local = 9_000L, repo = 12_000L, syncedAt = 12_000L),
                        "local-newer-than-repo-ref.m4a" to metadata(
                            "local-newer-than-repo-ref.m4a",
                            local = 15_000L,
                            repo = 18_000L,
                            syncedAt = 18_000L,
                        ),
                        "local-deleted.m4a" to metadata("local-deleted.m4a", local = 12_000L, repo = 9_000L, syncedAt = 12_000L),
                        "repo-newer-than-local-ref.m4a" to metadata(
                            "repo-newer-than-local-ref.m4a",
                            local = 18_000L,
                            repo = 15_000L,
                            syncedAt = 18_000L,
                        ),
                    ),
            )

        assertEquals(
            listOf(
                GitMediaSyncAction("brand-new-local.m4a", GitMediaSyncDirection.PUSH_TO_REPO, GitMediaSyncReason.LOCAL_ONLY),
                GitMediaSyncAction("brand-new-repo.m4a", GitMediaSyncDirection.PULL_TO_LOCAL, GitMediaSyncReason.REPO_ONLY),
                GitMediaSyncAction("local-deleted.m4a", GitMediaSyncDirection.DELETE_REPO, GitMediaSyncReason.LOCAL_DELETED),
                GitMediaSyncAction(
                    "local-newer-than-repo-ref.m4a",
                    GitMediaSyncDirection.PUSH_TO_REPO,
                    GitMediaSyncReason.LOCAL_NEWER,
                ),
                GitMediaSyncAction("repo-deleted.m4a", GitMediaSyncDirection.DELETE_LOCAL, GitMediaSyncReason.REPO_DELETED),
                GitMediaSyncAction(
                    "repo-newer-than-local-ref.m4a",
                    GitMediaSyncDirection.PULL_TO_LOCAL,
                    GitMediaSyncReason.REPO_NEWER,
                ),
            ),
            actions,
        )
    }

    private fun metadata(
        path: String,
        local: Long?,
        repo: Long?,
        syncedAt: Long = 0L,
    ): GitMediaSyncMetadataEntry =
        GitMediaSyncMetadataEntry(
            relativePath = path,
            repoLastModified = repo,
            localLastModified = local,
            lastSyncedAt = syncedAt,
            lastResolvedDirection = GitMediaSyncMetadataEntry.UNCHANGED,
            lastResolvedReason = GitMediaSyncMetadataEntry.UNCHANGED,
        )
}
