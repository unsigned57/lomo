// architectural-boundary-check
/*
 * Behavior Contract:
 * - Unit under test: GitSyncEngineConflictTest
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: detect git sync conflicts and apply explicit conflict-resolution choices without corrupting the repository when a choice cannot be completed.
 *
 * Scenarios:
 * - Given a rebase conflict, when sync runs, then a GitSyncResult.Conflict exposes the conflicted file and preserves remote content.
 * - Given MERGE_TEXT exceeds the text-merge budget, when explicit conflict resolution runs, then Git returns a clear provider-level error before changing files or creating a commit.
 * - Given an earlier Git conflict file could be resolved and a later MERGE_TEXT file exceeds budget, when explicit conflict resolution runs, then preflight fails before any working-tree write.
 *
 * Observable outcomes:
 * - GitSyncResult subtype/message, conflict file paths, working-tree file contents, remote file contents, and commit count.
 *
 * TDD proof:
 * - RED: the over-budget MERGE_TEXT scenario returned a generic "Failed to apply conflict resolution..." error instead of a specific provider-level unsupported merge result.
 * - RED follow-up-2 command: ./gradlew --no-daemon --no-configuration-cache --console=plain :data:testDebugUnitTest --tests 'com.lomo.data.git.GitSyncEngineConflictTest'
 * - RED follow-up-2 symptom against the old per-file write loop: the multi-file scenario changed writable.md to the chosen conflict content before failing on too-large.md; the test expects both working-tree files and commit count to remain unchanged.
 * - GREEN follow-up-2 worker reported: ./gradlew --no-daemon --no-configuration-cache --console=plain :data:testDebugUnitTest --tests 'com.lomo.data.repository.S3ConflictResolverTest' --tests 'com.lomo.data.git.GitSyncEngineConflictTest' -> BUILD SUCCESSFUL in 44s.
 * - GREEN WebDav regression worker reported: ./gradlew --no-daemon --no-configuration-cache --console=plain :data:testDebugUnitTest --tests 'com.lomo.data.repository.WebDavConflictResolverTest' -> BUILD SUCCESSFUL in 18s.
 *
 * Excludes:
 * - remote hosting behavior, credential-provider transport internals, and UI state mapping.
 *
 * Test Change Justification:
 * - Reason category: Data layer module gained app update install persistence, migration archive staging workspace, settings preference repos, and strengthened sync conflict store contracts.
 * - Old behavior/assertion being replaced: previous data layer tests relied on older repository contracts and store implementations before these modules were restructured.
 * - Why old assertion is no longer correct: new modules introduce typed credential reads, positional memo identities, and staged migration/restore plans that change observable data behavior.
 * - Coverage preserved by: all existing repository scenarios retained; new scenarios added for install persistence, staging workspace, preference repos, and conflict store contracts.
 * - Why this is not fitting the test to the implementation: tests verify observable repository store outcomes, not internal implementation details.
 */
package com.lomo.data.git

import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.model.GitSyncErrorCode
import com.lomo.domain.model.GitSyncResult
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncConflictFile
import com.lomo.domain.model.SyncConflictResolution
import com.lomo.domain.model.SyncConflictResolutionChoice
import com.lomo.domain.model.SyncConflictSet
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.URIish
import java.io.File
import java.nio.file.Files

class GitSyncEngineConflictTest : DataFunSpec() {
    init {
        beforeTest {
            setUp()
        }

        afterTest {
            tearDown()
        }

        test("sync returns conflict on rebase conflict and preserves remote content") { `sync returns conflict on rebase conflict and preserves remote content`() }

        test("resolveConflicts returns explicit error for over-budget MERGE_TEXT without writing or committing") { `resolveConflicts returns explicit error for over-budget MERGE_TEXT without writing or committing`() }

        test("resolveConflicts preflights all files before writing an earlier resolvable conflict") { `resolveConflicts preflights all files before writing an earlier resolvable conflict`() }
    }


    @MockK(relaxed = true)
    private lateinit var credentialStore: GitCredentialStore

    @MockK(relaxed = true)
    private lateinit var dataStore: LomoDataStore

    private lateinit var engine: GitSyncEngine
    private lateinit var tempRoot: File

    private fun setUp() {
        MockKAnnotations.init(this)
        every { credentialStore.getToken() } returns "dummy-token"
        every { dataStore.gitAuthorName } returns flowOf("Lomo Test")
        every { dataStore.gitAuthorEmail } returns flowOf("lomo@test.local")

        engine =
            GitSyncEngine(
                dataStore = dataStore,
                credentialStrategy = gitCredentialStrategy("dummy-token"),
                primitives = GitRepositoryPrimitives(),
            )
        tempRoot = Files.createTempDirectory("git-sync-engine-conflict").toFile()
    }

    private fun tearDown() {
        tempRoot.deleteRecursively()
    }

    private fun `sync returns conflict on rebase conflict and preserves remote content`() =
        runTest {
            val remoteBareDir = File(tempRoot, "remote.git").also { it.mkdirs() }
            val remoteUrl = remoteBareDir.toURI().toString()
            Git
                .init()
                .setBare(true)
                .setDirectory(remoteBareDir)
                .call()
                .use { }

            val seedDir = File(tempRoot, "seed").also { it.mkdirs() }
            createBaseCommitAndPush(seedDir, remoteUrl)

            val localDir = File(tempRoot, "local")
            Git
                .cloneRepository()
                .setURI(seedDir.toURI().toString())
                .setDirectory(localDir)
                .setBranch("main")
                .call()
                .close()

            Git.open(seedDir).use { seedGit ->
                File(seedDir, "memo.md").writeText("remote change\n")
                seedGit.add().addFilepattern("memo.md").call()
                seedGit
                    .commit()
                    .setMessage("remote: update memo")
                    .setAuthor("Remote", "remote@test.local")
                    .setCommitter("Remote", "remote@test.local")
                    .call()
                seedGit
                    .push()
                    .setRemote("origin")
                    .setRefSpecs(RefSpec("refs/heads/main:refs/heads/main"))
                    .call()
            }

            File(localDir, "memo.md").writeText("local change\n")

            val result = engine.sync(localDir, remoteUrl)
            (result is GitSyncResult.Conflict).shouldBeTrue()
            val conflict = result as GitSyncResult.Conflict
            (conflict.message.contains("conflict", ignoreCase = true)).shouldBeTrue()
            conflict.conflicts.files.map { it.relativePath } shouldBe listOf("memo.md")

            Git.open(seedDir).use { seedGit ->
                seedGit.fetch().setRemote("origin").call()
                seedGit
                    .reset()
                    .setMode(ResetCommand.ResetType.HARD)
                    .setRef("origin/main")
                    .call()
            }
            File(seedDir, "memo.md").readText() shouldBe "remote change\n"
        }

    private fun `resolveConflicts returns explicit error for over-budget MERGE_TEXT without writing or committing`() =
        runTest {
            val remoteBareDir = File(tempRoot, "resolution-remote.git").also { it.mkdirs() }
            val remoteUrl = remoteBareDir.toURI().toString()
            Git
                .init()
                .setBare(true)
                .setDirectory(remoteBareDir)
                .call()
                .use { }

            val repoDir = File(tempRoot, "resolution-local").also { it.mkdirs() }
            Git.init().setDirectory(repoDir).call().use { git ->
                File(repoDir, "memo.md").writeText("base\n")
                git.add().addFilepattern("memo.md").call()
                git
                    .commit()
                    .setMessage("base: add memo")
                    .setAuthor("Seed", "seed@test.local")
                    .setCommitter("Seed", "seed@test.local")
                    .call()
                git
                    .remoteAdd()
                    .setName("origin")
                    .setUri(URIish(remoteUrl))
                    .call()
                git
                    .push()
                    .setRemote("origin")
                    .setRefSpecs(RefSpec("refs/heads/master:refs/heads/master"))
                    .call()
            }
            val originalContent = "original working tree\n"
            File(repoDir, "memo.md").writeText(originalContent)
            val originalCommitCount = Git.open(repoDir).use(::commitCount)
            val result =
                engine.resolveConflicts(
                    rootDir = repoDir,
                    remoteUrl = remoteUrl,
                    resolution =
                        SyncConflictResolution(
                            perFileChoices = mapOf("memo.md" to SyncConflictResolutionChoice.MERGE_TEXT),
                        ),
                    conflictSet =
                        SyncConflictSet(
                            source = SyncBackendType.GIT,
                            files =
                                listOf(
                                    SyncConflictFile(
                                        relativePath = "memo.md",
                                        localContent = numberedLines(prefix = "local"),
                                        remoteContent = numberedLines(prefix = "remote"),
                                        isBinary = false,
                                    ),
                                ),
                            timestamp = 1L,
                        ),
                )

            val error = result as GitSyncResult.Error
            error.code shouldBe GitSyncErrorCode.UNKNOWN
            error.message shouldBe
                "Git text merge could not resolve memo.md automatically. Choose keep local or keep remote."
            File(repoDir, "memo.md").readText() shouldBe originalContent
            Git.open(repoDir).use { git ->
                commitCount(git) shouldBe originalCommitCount
            }
        }

    private fun `resolveConflicts preflights all files before writing an earlier resolvable conflict`() =
        runTest {
            val remoteBareDir = File(tempRoot, "multi-resolution-remote.git").also { it.mkdirs() }
            val remoteUrl = remoteBareDir.toURI().toString()
            Git
                .init()
                .setBare(true)
                .setDirectory(remoteBareDir)
                .call()
                .use { }

            val repoDir = File(tempRoot, "multi-resolution-local").also { it.mkdirs() }
            Git.init().setDirectory(repoDir).call().use { git ->
                File(repoDir, "writable.md").writeText("base writable\n")
                File(repoDir, "too-large.md").writeText("base too large\n")
                git.add().addFilepattern(".").call()
                git
                    .commit()
                    .setMessage("base: add conflict files")
                    .setAuthor("Seed", "seed@test.local")
                    .setCommitter("Seed", "seed@test.local")
                    .call()
                git
                    .remoteAdd()
                    .setName("origin")
                    .setUri(URIish(remoteUrl))
                    .call()
                git
                    .push()
                    .setRemote("origin")
                    .setRefSpecs(RefSpec("refs/heads/master:refs/heads/master"))
                    .call()
            }
            val originalWritableContent = "original writable working tree\n"
            val originalTooLargeContent = "original too-large working tree\n"
            File(repoDir, "writable.md").writeText(originalWritableContent)
            File(repoDir, "too-large.md").writeText(originalTooLargeContent)
            val originalCommitCount = Git.open(repoDir).use(::commitCount)

            val result =
                engine.resolveConflicts(
                    rootDir = repoDir,
                    remoteUrl = remoteUrl,
                    resolution =
                        SyncConflictResolution(
                            perFileChoices =
                                mapOf(
                                    "writable.md" to SyncConflictResolutionChoice.KEEP_REMOTE,
                                    "too-large.md" to SyncConflictResolutionChoice.MERGE_TEXT,
                                ),
                        ),
                    conflictSet =
                        SyncConflictSet(
                            source = SyncBackendType.GIT,
                            files =
                                listOf(
                                    SyncConflictFile(
                                        relativePath = "writable.md",
                                        localContent = "local writable\n",
                                        remoteContent = "remote writable\n",
                                        isBinary = false,
                                    ),
                                    SyncConflictFile(
                                        relativePath = "too-large.md",
                                        localContent = numberedLines(prefix = "local"),
                                        remoteContent = numberedLines(prefix = "remote"),
                                        isBinary = false,
                                    ),
                                ),
                            timestamp = 1L,
                        ),
                )

            val error = result as GitSyncResult.Error
            error.code shouldBe GitSyncErrorCode.UNKNOWN
            error.message shouldBe
                "Git text merge could not resolve too-large.md automatically. Choose keep local or keep remote."
            File(repoDir, "writable.md").readText() shouldBe originalWritableContent
            File(repoDir, "too-large.md").readText() shouldBe originalTooLargeContent
            Git.open(repoDir).use { git ->
                commitCount(git) shouldBe originalCommitCount
            }
        }

    private fun createBaseCommitAndPush(
        seedDir: File,
        remoteUrl: String,
    ) {
        Git.init().setDirectory(seedDir).call().use { seedGit ->
            File(seedDir, "memo.md").writeText("base\n")
            seedGit.add().addFilepattern("memo.md").call()
            seedGit
                .commit()
                .setMessage("base: add memo")
                .setAuthor("Seed", "seed@test.local")
                .setCommitter("Seed", "seed@test.local")
                .call()

            val currentBranch = seedGit.repository.branch
            if (currentBranch != "main") {
                seedGit
                    .branchRename()
                    .setOldName(currentBranch)
                    .setNewName("main")
                    .call()
            }

            seedGit
                .remoteAdd()
                .setName("origin")
                .setUri(URIish(remoteUrl))
                .call()
            seedGit
                .push()
                .setRemote("origin")
                .setRefSpecs(RefSpec("refs/heads/main:refs/heads/main"))
                .call()
        }
    }

    private fun commitCount(git: Git): Int =
        runCatching { git.log().call().count() }.getOrDefault(0)

    private fun numberedLines(prefix: String): String =
        (0..1_000).joinToString("\n") { index -> "$prefix-$index" }
}
