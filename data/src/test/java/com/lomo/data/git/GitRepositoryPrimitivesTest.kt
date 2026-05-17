package com.lomo.data.git


import com.lomo.domain.model.GitSyncResult
import io.mockk.mockk
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File
import java.nio.file.Files
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.nulls.shouldBeNull

/*
 * Test Contract:
 * - Unit under test: GitRepositoryPrimitives
 * - Behavior focus: stale-lock cleanup, .gitignore creation policy, remote add/update behavior, commit-file reads, and amend eligibility checks.
 * - Observable outcomes: deleted vs preserved lock files, .gitignore file contents, repository remote URL config, file contents at a commit, and amend Boolean decisions.
 * - Red phase: Fails before behavior changes or migration are applied.
 * - Excludes: credential fallback execution, network push transport, and JGit internal status computation beyond observable repository state.
 */
class GitRepositoryPrimitivesTest : DataFunSpec() {
    init {
        beforeTest {
            setUp()
        }

        afterTest {
            tearDown()
        }

        test("cleanStaleLockFiles deletes only stale locks") { `cleanStaleLockFiles deletes only stale locks`() }

        test("ensureGitignore creates defaults once and preserves existing file") { `ensureGitignore creates defaults once and preserves existing file`() }

        test("ensureRemote adds origin when missing and updates it when url changes") { `ensureRemote adds origin when missing and updates it when url changes`() }

        test("readFileAtCommit returns tracked file contents and null for missing file") { `readFileAtCommit returns tracked file contents and null for missing file`() }

        test("shouldAmendLastCommit returns false when latest commit is not a lomo commit") { `shouldAmendLastCommit returns false when latest commit is not a lomo commit`() }

        test("shouldAmendLastCommit returns true when lomo commit is ahead of tracked remote") { `shouldAmendLastCommit returns true when lomo commit is ahead of tracked remote`() }

        test("abortRebaseQuietly ignores repositories without a rebase state") { `abortRebaseQuietly ignores repositories without a rebase state`() }

        test("tryPush returns success when file remote accepts the update") { `tryPush returns success when file remote accepts the update`() }

        test("tryPush reports non fast forward when remote has diverged") { `tryPush reports non fast forward when remote has diverged`() }
    }


    private val primitives = GitRepositoryPrimitives()
    private val credentials = listOf(UsernamePasswordCredentialsProvider("user", "token"))
    private lateinit var tempRoot: File

    private fun setUp() {
        tempRoot = Files.createTempDirectory("git-primitives-test").toFile()
    }

    private fun tearDown() {
        tempRoot.deleteRecursively()
    }

    private fun `cleanStaleLockFiles deletes only stale locks`() {
        val staleRepo = initRepo("stale-lock")
        val recentRepo = initRepo("recent-lock")
        val staleLock = File(staleRepo, ".git/index.lock").apply { writeText("stale") }
        val recentLock = File(recentRepo, ".git/index.lock").apply { writeText("recent") }

        staleLock.setLastModified(System.currentTimeMillis() - 10 * 60 * 1000L)
        recentLock.setLastModified(System.currentTimeMillis() - 60 * 1000L)

        primitives.cleanStaleLockFiles(staleRepo)
        primitives.cleanStaleLockFiles(recentRepo)

        (staleLock.exists()).shouldBeFalse()
        (recentLock.exists()).shouldBeTrue()
    }

    private fun `ensureGitignore creates defaults once and preserves existing file`() {
        val repoDir = initRepo("gitignore")
        val gitignore = File(repoDir, ".gitignore")

        primitives.ensureGitignore(repoDir)

        gitignore.readText() shouldBe """
            .trash/
            *.db
            *.db-journal
            *.db-wal
            """.trimIndent() + "\n"

        gitignore.writeText("custom-entry\n")
        primitives.ensureGitignore(repoDir)

        gitignore.readText() shouldBe "custom-entry\n"
    }

    private fun `ensureRemote adds origin when missing and updates it when url changes`() {
        val repoDir = initRepo("remote")
        Git.open(repoDir).use { git ->
            primitives.ensureRemote(git, "https://example.com/org/repo-one.git")
            git.repository.config.getString("remote", "origin", "url") shouldBe "https://example.com/org/repo-one.git"

            primitives.ensureRemote(git, "https://example.com/org/repo-two.git")

            git.repository.config.getString("remote", "origin", "url") shouldBe "https://example.com/org/repo-two.git"
        }
    }

    private fun `readFileAtCommit returns tracked file contents and null for missing file`() {
        val repoDir = initRepo("history")
        Git.open(repoDir).use { git ->
            File(repoDir, "memo.md").writeText("hello history\n")
            git.add().addFilepattern("memo.md").call()
            val commit = commit(git, "save memo")

            primitives.readFileAtCommit(git, commit, "memo.md") shouldBe "hello history\n"
            primitives.readFileAtCommit(git, commit, "missing.md").shouldBeNull()
        }
    }

    private fun `shouldAmendLastCommit returns false when latest commit is not a lomo commit`() {
        val repoDir = initRepo("manual")
        Git.open(repoDir).use { git ->
            File(repoDir, "memo.md").writeText("manual\n")
            git.add().addFilepattern("memo.md").call()
            commit(git, "manual save")

            (primitives.shouldAmendLastCommit(git, git.repository.branch)).shouldBeFalse()
        }
    }

    private fun `shouldAmendLastCommit returns true when lomo commit is ahead of tracked remote`() {
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

            (primitives.shouldAmendLastCommit(git, branch)).shouldBeTrue()
        }
    }

    private fun `abortRebaseQuietly ignores repositories without a rebase state`() {
        val repoDir = initRepo("abort-rebase")

        Git.open(repoDir).use { git ->
            primitives.abortRebaseQuietly(git)
        }
    }

    private fun `tryPush returns success when file remote accepts the update`() {
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

            result shouldBe GitSyncResult.Success("Push ok")
        }
    }

    private fun `tryPush reports non fast forward when remote has diverged`() {
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
            seedPush shouldBe GitSyncResult.Success("seed pushed")
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
            remotePush shouldBe GitSyncResult.Success("remote pushed")
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

            result shouldBe GitSyncResult.Error("Push rejected: non-fast-forward. Remote has diverged.")
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
