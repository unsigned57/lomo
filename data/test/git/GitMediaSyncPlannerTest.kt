package com.lomo.data.git

import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: GitMediaSyncPlanner
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: Git media reconcile decisions are made from indexed file identity instead of full-byte copier comparisons.
 *
 * Scenarios:
 * - Given local and repo media have matching fingerprints, when timestamps differ, then no sync action is planned.
 * - Given one side is missing, when the surviving side matches or differs from its indexed baseline, then delete or copy is planned explicitly.
 * - Given fingerprints cannot establish equality, when timestamps indicate one side changed, then the existing newer-side policy still applies.
 *
 * Observable outcomes:
 * - Planned GitMediaSyncAction direction/reason list for each path.
 *
 * TDD proof:
 * - RED: `./kotlin test --include-classes='com.lomo.data.git.GitMediaSyncBridgeTest'`
 *   fails before the fix because Git media file descriptors and metadata have no fingerprint/index contract.
 *
 * Excludes:
 * - Actual file I/O, Git transport, and metadata store persistence.
 */
class GitMediaSyncPlannerTest : DataFunSpec() {
    init {
        test("plan resolves both-present combinations including unchanged local-only repo-only and newer wins") { `plan resolves both-present combinations including unchanged local-only repo-only and newer wins`() }

        test("plan resolves local-only and repo-only files using metadata deletion hints") { `plan resolves local-only and repo-only files using metadata deletion hints`() }

        test("plan treats matching local and repo fingerprints as unchanged despite timestamp drift") { `plan treats matching local and repo fingerprints as unchanged despite timestamp drift`() }

        test("plan resolves missing-side decisions from fingerprint baseline") { `plan resolves missing-side decisions from fingerprint baseline`() }
    }


    private val planner = GitMediaSyncPlanner(timestampToleranceMs = 1000L)

    private fun `plan resolves both-present combinations including unchanged local-only repo-only and newer wins`() {
        val actions =
            planner.plan(
                localFiles =
                    mapOf(
                        "local-changed.jpg" to localFile("local-changed.jpg", 3_500L),
                        "repo-changed.jpg" to localFile("repo-changed.jpg", 2_000L),
                        "same-time.jpg" to localFile("same-time.jpg", 5_100L),
                        "newer-local.jpg" to localFile("newer-local.jpg", 8_300L),
                        "newer-repo.jpg" to localFile("newer-repo.jpg", 7_200L),
                    ),
                repoFiles =
                    mapOf(
                        "local-changed.jpg" to repoFile("local-changed.jpg", 2_000L),
                        "repo-changed.jpg" to repoFile("repo-changed.jpg", 3_500L),
                        "same-time.jpg" to repoFile("same-time.jpg", 5_500L),
                        "newer-local.jpg" to repoFile("newer-local.jpg", 7_100L),
                        "newer-repo.jpg" to repoFile("newer-repo.jpg", 9_500L),
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

        actions shouldBe listOf(
                GitMediaSyncAction("local-changed.jpg", GitMediaSyncDirection.PUSH_TO_REPO, GitMediaSyncReason.LOCAL_ONLY),
                GitMediaSyncAction("newer-local.jpg", GitMediaSyncDirection.PUSH_TO_REPO, GitMediaSyncReason.LOCAL_NEWER),
                GitMediaSyncAction("newer-repo.jpg", GitMediaSyncDirection.PULL_TO_LOCAL, GitMediaSyncReason.REPO_NEWER),
                GitMediaSyncAction("repo-changed.jpg", GitMediaSyncDirection.PULL_TO_LOCAL, GitMediaSyncReason.REPO_ONLY),
                GitMediaSyncAction("same-time.jpg", GitMediaSyncDirection.PUSH_TO_REPO, GitMediaSyncReason.SAME_TIMESTAMP),
            )
    }

    private fun `plan resolves local-only and repo-only files using metadata deletion hints`() {
        val actions =
            planner.plan(
                localFiles =
                    mapOf(
                        "brand-new-local.m4a" to localFile("brand-new-local.m4a", 10_000L),
                        "repo-deleted.m4a" to localFile("repo-deleted.m4a", 9_000L),
                        "local-newer-than-repo-ref.m4a" to localFile("local-newer-than-repo-ref.m4a", 20_000L),
                    ),
                repoFiles =
                    mapOf(
                        "brand-new-repo.m4a" to repoFile("brand-new-repo.m4a", 11_000L),
                        "local-deleted.m4a" to repoFile("local-deleted.m4a", 9_000L),
                        "repo-newer-than-local-ref.m4a" to repoFile("repo-newer-than-local-ref.m4a", 20_000L),
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

        actions shouldBe listOf(
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
            )
    }

    private fun `plan treats matching local and repo fingerprints as unchanged despite timestamp drift`() {
        val actions =
            planner.plan(
                localFiles =
                    mapOf(
                        "images/same.jpg" to
                            LocalGitMediaFile(
                                path = "images/same.jpg",
                                lastModified = 30_000L,
                                size = 12L,
                                fingerprint = "md5:same",
                            ),
                    ),
                repoFiles =
                    mapOf(
                        "images/same.jpg" to
                            RepoGitMediaFile(
                                path = "images/same.jpg",
                                lastModified = 40_000L,
                                size = 12L,
                                fingerprint = "md5:same",
                            ),
                    ),
                metadata =
                    mapOf(
                        "images/same.jpg" to
                            metadata(
                                path = "images/same.jpg",
                                local = 10_000L,
                                repo = 10_000L,
                                localSize = 12L,
                                repoSize = 12L,
                                localFingerprint = "md5:same",
                                repoFingerprint = "md5:same",
                            ),
                    ),
            )

        actions shouldBe emptyList()
    }

    private fun `plan resolves missing-side decisions from fingerprint baseline`() {
        val actions =
            planner.plan(
                localFiles =
                    mapOf(
                        "images/repo-deleted.jpg" to
                            LocalGitMediaFile(
                                path = "images/repo-deleted.jpg",
                                lastModified = 20_000L,
                                size = 4L,
                                fingerprint = "md5:unchanged-local",
                            ),
                        "images/local-changed.jpg" to
                            LocalGitMediaFile(
                                path = "images/local-changed.jpg",
                                lastModified = 21_000L,
                                size = 4L,
                                fingerprint = "md5:changed-local",
                            ),
                    ),
                repoFiles =
                    mapOf(
                        "voice/local-deleted.m4a" to
                            RepoGitMediaFile(
                                path = "voice/local-deleted.m4a",
                                lastModified = 22_000L,
                                size = 5L,
                                fingerprint = "md5:unchanged-repo",
                            ),
                        "voice/repo-changed.m4a" to
                            RepoGitMediaFile(
                                path = "voice/repo-changed.m4a",
                                lastModified = 23_000L,
                                size = 5L,
                                fingerprint = "md5:changed-repo",
                            ),
                    ),
                metadata =
                    mapOf(
                        "images/repo-deleted.jpg" to
                            metadata(
                                "images/repo-deleted.jpg",
                                local = 10_000L,
                                repo = 10_000L,
                                localSize = 4L,
                                repoSize = 4L,
                                localFingerprint = "md5:unchanged-local",
                                repoFingerprint = "md5:old-repo",
                                syncedAt = 10_000L,
                            ),
                        "images/local-changed.jpg" to
                            metadata(
                                "images/local-changed.jpg",
                                local = 10_000L,
                                repo = 10_000L,
                                localSize = 4L,
                                repoSize = 4L,
                                localFingerprint = "md5:old-local",
                                repoFingerprint = "md5:old-repo",
                                syncedAt = 10_000L,
                            ),
                        "voice/local-deleted.m4a" to
                            metadata(
                                "voice/local-deleted.m4a",
                                local = 10_000L,
                                repo = 10_000L,
                                localSize = 5L,
                                repoSize = 5L,
                                localFingerprint = "md5:old-local",
                                repoFingerprint = "md5:unchanged-repo",
                                syncedAt = 10_000L,
                            ),
                        "voice/repo-changed.m4a" to
                            metadata(
                                "voice/repo-changed.m4a",
                                local = 10_000L,
                                repo = 10_000L,
                                localSize = 5L,
                                repoSize = 5L,
                                localFingerprint = "md5:old-local",
                                repoFingerprint = "md5:old-repo",
                                syncedAt = 10_000L,
                            ),
                    ),
            )

        actions shouldBe listOf(
            GitMediaSyncAction("images/local-changed.jpg", GitMediaSyncDirection.PUSH_TO_REPO, GitMediaSyncReason.LOCAL_NEWER),
            GitMediaSyncAction("images/repo-deleted.jpg", GitMediaSyncDirection.DELETE_LOCAL, GitMediaSyncReason.REPO_DELETED),
            GitMediaSyncAction("voice/local-deleted.m4a", GitMediaSyncDirection.DELETE_REPO, GitMediaSyncReason.LOCAL_DELETED),
            GitMediaSyncAction("voice/repo-changed.m4a", GitMediaSyncDirection.PULL_TO_LOCAL, GitMediaSyncReason.REPO_NEWER),
        )
    }

    private fun metadata(
        path: String,
        local: Long?,
        repo: Long?,
        localSize: Long? = null,
        repoSize: Long? = null,
        localFingerprint: String? = null,
        repoFingerprint: String? = null,
        syncedAt: Long = 0L,
    ): GitMediaSyncMetadataEntry =
        GitMediaSyncMetadataEntry(
            relativePath = path,
            repoLastModified = repo,
            localLastModified = local,
            repoSize = repoSize,
            localSize = localSize,
            repoFingerprint = repoFingerprint,
            localFingerprint = localFingerprint,
            lastSyncedAt = syncedAt,
            lastResolvedDirection = GitMediaSyncMetadataEntry.UNCHANGED,
            lastResolvedReason = GitMediaSyncMetadataEntry.UNCHANGED,
        )

    private fun localFile(
        path: String,
        lastModified: Long,
    ): LocalGitMediaFile =
        LocalGitMediaFile(
            path = path,
            lastModified = lastModified,
            size = path.length.toLong(),
            fingerprint = "local:$path:$lastModified",
        )

    private fun repoFile(
        path: String,
        lastModified: Long,
    ): RepoGitMediaFile =
        RepoGitMediaFile(
            path = path,
            lastModified = lastModified,
            size = path.length.toLong(),
            fingerprint = "repo:$path:$lastModified",
        )
}
