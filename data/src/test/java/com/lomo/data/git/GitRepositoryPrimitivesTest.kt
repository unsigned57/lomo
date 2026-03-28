package com.lomo.data.git

import com.lomo.domain.model.GitSyncResult
import io.mockk.mockk
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/*
 * Test Contract:
 * - Unit under test: GitRepositoryPrimitives
 * - Behavior focus: stale-lock cleanup, .gitignore creation policy, remote add/update behavior, commit-file reads, and amend eligibility checks.
 * - Observable outcomes: deleted vs preserved lock files, .gitignore file contents, repository remote URL config, file contents at a commit, and amend Boolean decisions.
 * - Red phase: Not applicable - test-only coverage addition; no production change.
 * - Excludes: credential fallback execution, network push transport, and JGit internal status computation beyond observable repository state.
 */
class GitRepositoryPrimitivesTest {
    private val primitives = GitRepositoryPrimitives()
    private val credentials = listOf(UsernamePasswordCredentialsProvider("user", "token"))
    private lateinit var tempRoot: File

    @Before
    fun setUp() {
        tempRoot = Files.createTempDirectory("git-primitives-test").toFile()
    }

    @After
    fun tearDown() {
        tempRoot.deleteRecursively()
    }

    @Test
    fun `cleanStaleLockFiles deletes only stale locks`() {
        val staleRepo = initRepo("stale-lock")
        val recentRepo = initRepo("recent-lock")
        val staleLock = File(staleRepo, ".git/index.lock").apply { writeText("stale") }
        val recentLock = File(recentRepo, ".git/index.lock").apply { writeText("recent") }

        staleLock.setLastModified(System.currentTimeMillis() - 10 * 60 * 1000L)
        recentLock.setLastModified(System.currentTimeMillis() - 60 * 1000L)

        primitives.cleanStaleLockFiles(staleRepo)
        primitives.cleanStaleLockFiles(recentRepo)

        assertFalse(staleLock.exists())
        assertTrue(recentLock.exists())
    }

    @Test
    fun `ensureGitignore creates defaults once and preserves existing file`() {
        val repoDir = initRepo("gitignore")
        val gitignore = File(repoDir, ".gitignore")

        primitives.ensureGitignore(repoDir)

        assertEquals(
            """
            .trash/
            *.db
            *.db-journal
            *.db-wal
            """.trimIndent() + "\n",
            gitignore.readText(),
        )

        gitignore.writeText("custom-entry\n")
        primitives.ensureGitignore(repoDir)

        assertEquals("custom-entry\n", gitignore.readText())
    }

    @Test
    fun `ensureRemote adds origin when missing and updates it when url changes`() {
        val repoDir = initRepo("remote")
        Git.open(repoDir).use { git ->
            primitives.ensureRemote(git, "https://example.com/org/repo-one.git")
            assertEquals(
                "https://example.com/org/repo-one.git",
                git.repository.config.getString("remote", "origin", "url"),
            )

            primitives.ensureRemote(git, "https://example.com/org/repo-two.git")

            assertEquals(
                "https://example.com/org/repo-two.git",
                git.repository.config.getString("remote", "origin", "url"),
            )
        }
    }

    @Test
    fun `readFileAtCommit returns tracked file contents and null for missing file`() {
        val repoDir = initRepo("history")
        Git.open(repoDir).use { git ->
            File(repoDir, "memo.md").writeText("hello history\n")
            git.add().addFilepattern("memo.md").call()
            val commit = commit(git, "save memo")

            assertEquals("hello history\n", primitives.readFileAtCommit(git, commit, "memo.md"))
            assertNull(primitives.readFileAtCommit(git, commit, "missing.md"))
        }
    }

    @Test
    fun `shouldAmendLastCommit returns false when latest commit is not a lomo commit`() {
        val repoDir = initRepo("manual")
        Git.open(repoDir).use { git ->
            File(repoDir, "memo.md").writeText("manual\n")
            git.add().addFilepattern("memo.md").call()
            commit(git, "manual save")

            assertFalse(primitives.shouldAmendLastCommit(git, git.repository.branch))
        }
    }

    @Test
    fun `shouldAmendLastCommit returns true when lomo commit is ahead of tracked remote`() {
        val remoteDir = File(tempRoot, "origin.git")
        Git.init().setBare(true).setDirectory(remoteDir).call().close()

        val repoDir = initRepo("ahead")
        Git.open(repoDir).use { git ->
            val branch = git.repository.branch
            primitives.ensureRemote(git, remoteDir.toURI().toString())
            git.repository.config.apply {
                setString("branch", branch, "remote", "origin")
                setString("branch", branch, "merge", "refs/heads/$branch")
                save()
            }

            File(repoDir, "memo.md").writeText("seed\n")
            git.add().addFilepattern("memo.md").call()
            commit(git, "seed")
            git
                .push()
                .setRemote("origin")
                .setRefSpecs(RefSpec("refs/heads/$branch:refs/heads/$branch"))
                .call()

            File(repoDir, "memo.md").appendText("local-only\n")
            git.add().addFilepattern("memo.md").call()
            commit(git, "update via Lomo")

            assertTrue(primitives.shouldAmendLastCommit(git, branch))
        }
    }

    @Test
    fun `abortRebaseQuietly ignores repositories without a rebase state`() {
        val repoDir = initRepo("abort-rebase")

        Git.open(repoDir).use { git ->
            primitives.abortRebaseQuietly(git)
        }
    }

    @Test
    fun `tryPush returns success when file remote accepts the update`() {
        val remoteDir = File(tempRoot, "push-success.git")
        Git.init().setBare(true).setDirectory(remoteDir).call().close()
        val repoDir = initRepo("push-success-local")

        Git.open(repoDir).use { git ->
            primitives.ensureRemote(git, remoteDir.toURI().toString())
            File(repoDir, "memo.md").writeText("push success\n")
            git.add().addFilepattern("memo.md").call()
            commit(git, "push via Lomo")

            val result =
                primitives.tryPush(
                    git = git,
                    credentialStrategy = credentialStrategy(),
                    credentials = credentials,
                    branch = git.repository.branch,
                    successMessage = "Push ok",
                )

            assertEquals(GitSyncResult.Success("Push ok"), result)
        }
    }

    @Test
    fun `tryPush reports non fast forward when remote has diverged`() {
        val remoteDir = File(tempRoot, "push-reject.git")
        Git.init().setBare(true).setDirectory(remoteDir).call().close()
        val repoOneDir = initRepo("push-reject-one")
        val repoTwoDir = File(tempRoot, "push-reject-two")

        Git.open(repoOneDir).use { git ->
            primitives.ensureRemote(git, remoteDir.toURI().toString())
            File(repoOneDir, "memo.md").writeText("seed\n")
            git.add().addFilepattern("memo.md").call()
            commit(git, "seed")
            val seedPush =
                primitives.tryPush(
                    git = git,
                    credentialStrategy = credentialStrategy(),
                    credentials = credentials,
                    branch = git.repository.branch,
                    successMessage = "seed pushed",
                )
            assertEquals(GitSyncResult.Success("seed pushed"), seedPush)
        }

        Git.cloneRepository().setURI(remoteDir.toURI().toString()).setDirectory(repoTwoDir).call().use { git ->
            File(repoTwoDir, "memo.md").appendText("remote update\n")
            git.add().addFilepattern("memo.md").call()
            commit(git, "remote update")
            val remotePush =
                primitives.tryPush(
                    git = git,
                    credentialStrategy = credentialStrategy(),
                    credentials = credentials,
                    branch = git.repository.branch,
                    successMessage = "remote pushed",
                )
            assertEquals(GitSyncResult.Success("remote pushed"), remotePush)
        }

        Git.open(repoOneDir).use { git ->
            File(repoOneDir, "memo.md").appendText("local stale update\n")
            git.add().addFilepattern("memo.md").call()
            commit(git, "local stale update")

            val result =
                primitives.tryPush(
                    git = git,
                    credentialStrategy = credentialStrategy(),
                    credentials = credentials,
                    branch = git.repository.branch,
                    successMessage = "should not succeed",
                )

            assertEquals(
                GitSyncResult.Error("Push rejected: non-fast-forward. Remote has diverged."),
                result,
            )
        }
    }

    private fun initRepo(name: String): File {
        val repoDir = File(tempRoot, name).also { it.mkdirs() }
        Git.init().setDirectory(repoDir).call().close()
        return repoDir
    }

    private fun credentialStrategy(): GitCredentialStrategy = GitCredentialStrategy(mockk(relaxed = true))

    private fun commit(
        git: Git,
        message: String,
    ) = git
        .commit()
        .setMessage(message)
        .setAuthor("Lomo Test", "lomo@test.local")
        .setCommitter("Lomo Test", "lomo@test.local")
        .call()
}
