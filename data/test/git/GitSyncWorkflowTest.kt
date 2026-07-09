package com.lomo.data.git

/**
 * Behavior Contract:
 * Capability: Kotest Migration
 * Scenarios: Given standard test execution, when tests run, then assertions hold.
 * Observable outcomes: Green tests
 * TDD proof: Compilation failure on Kotest transition
 * Excludes: none
 * 
 * Test Change Justification:
 * Reason category: Migration
 * Old behavior/assertion being replaced: JUnit4 assertions
 * Why old assertion is no longer correct: Transitioning to Kotest
 * Coverage preserved by: Kotest functional matching
 * Why this is not fitting the test to the implementation: Syntax translation
 */



import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.domain.model.GitSyncErrorCode
import com.lomo.domain.model.GitSyncResult
import com.lomo.domain.model.UnifiedSyncPhase
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.URIish
import java.io.File
import java.nio.file.Files
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull

/*
 * Behavior Contract:
 * - Unit under test: GitSyncWorkflow
 * - Behavior focus: local commit branching (no-op / commit / amend), non-repository error handling, PAT precondition, and a stable local-file sync success path.
 * - Observable outcomes: GitSyncResult subtype or message, commit history/count changes, and emitted syncing phases.
 * - TDD proof: Fails before behavior changes or migration are applied.
 * - Excludes: network transport behavior, conflict-content extraction internals, and engine-level state holder orchestration.
 */
class GitSyncWorkflowTest : DataFunSpec() {
    init {
        beforeTest {
            setUp()
        }

        afterTest {
            tearDown()
        }

        test("commitLocal returns non-repository error when git metadata is missing") { `commitLocal returns non-repository error when git metadata is missing`() }

        test("commitLocal returns nothing-to-commit on clean repository") { `commitLocal returns nothing-to-commit on clean repository`() }

        test("commitLocal creates normal lomo commit when pending changes exist and amend is false") { `commitLocal creates normal lomo commit when pending changes exist and amend is false`() }

        test("commitLocal amends latest commit when primitives request amend branch") { `commitLocal amends latest commit when primitives request amend branch`() }

        test("sync returns PAT-required error before touching repository state") { `sync returns PAT-required error before touching repository state`() }

        test("sync returns non-repository error when git metadata is missing") { `sync returns non-repository error when git metadata is missing`() }

        test("sync succeeds against local bare remote and emits commit-pull-push phases") { `sync succeeds against local bare remote and emits commit-pull-push phases`() }
    }


    private lateinit var tempRoot: File
    private lateinit var dataStore: LomoDataStore

    private fun setUp() {
        tempRoot = Files.createTempDirectory("git-sync-workflow-test").toFile()
        dataStore = mockk(relaxed = true)
        every { dataStore.gitAuthorName } returns flowOf("Lomo Test")
        every { dataStore.gitAuthorEmail } returns flowOf("lomo@test.local")
    }

    private fun tearDown() {
        tempRoot.deleteRecursively()
    }

    private fun `commitLocal returns non-repository error when git metadata is missing`() =
        runTest {
            val workflow = createWorkflow(token = "token")
            val notRepo = File(tempRoot, "not-repo").also { it.mkdirs() }

            val result = workflow.commitLocal(notRepo)

            result shouldBe GitSyncResult.Error("Not a git repository")
        }

    private fun `commitLocal returns nothing-to-commit on clean repository`() =
        runTest {
            val repoDir = initRepo("clean")
            val workflow = createWorkflow(token = "token")

            val result = workflow.commitLocal(repoDir)

            result shouldBe GitSyncResult.Success("Nothing to commit")
        }

    private fun `commitLocal creates normal lomo commit when pending changes exist and amend is false`() =
        runTest {
            val repoDir = initRepo("normal-commit")
            Git.open(repoDir).use { git ->
                File(repoDir, "memo.md").writeText("seed\n")
                git.add().addFilepattern("memo.md").call()
                commit(git, "seed commit")
            }
            File(repoDir, "memo.md").writeText("first\n")
            val workflow = createWorkflow(token = "token")

            val result = workflow.commitLocal(repoDir)

            result shouldBe GitSyncResult.Success("Committed locally")
            Git.open(repoDir).use { git ->
                val head = git.repository.resolve("HEAD")
                head.shouldNotBeNull()
                commitCount(git) shouldBe 2
                val latest = git.log().setMaxCount(1).call().first()
                (latest.fullMessage.startsWith("sync:")).shouldBeTrue()
                (latest.fullMessage.contains("via Lomo")).shouldBeTrue()
            }
        }

    private fun `commitLocal amends latest commit when primitives request amend branch`() =
        runTest {
            val repoDir = initRepo("amend-commit")
            Git.open(repoDir).use { git ->
                File(repoDir, "memo.md").writeText("seed\n")
                git.add().addFilepattern("memo.md").call()
                commit(git, "seed commit")
            }
            File(repoDir, "memo.md").writeText("seed-updated\n")

            val primitives =
                spyk(GitRepositoryPrimitives()).apply {
                    every { shouldAmendLastCommit(any(), any()) } returns true
                }
            val workflow = createWorkflow(token = "token", primitives = primitives)

            val result = workflow.commitLocal(repoDir)

            result shouldBe GitSyncResult.Success("Amended local commit")
            Git.open(repoDir).use { git ->
                commitCount(git) shouldBe 1
                val latest = git.log().setMaxCount(1).call().first()
                (latest.fullMessage.contains("via Lomo")).shouldBeTrue()
            }
        }

    private fun `sync returns PAT-required error before touching repository state`() =
        runTest {
            val repoDir = initRepo("sync-pat")
            val workflow = createWorkflow(token = null)

            val result =
                workflow.sync(
                    rootDir = repoDir,
                    remoteUrl = "https://example.com/org/repo.git",
                    onSyncingState = {},
                )

            val error = result as GitSyncResult.Error
            error.code shouldBe GitSyncErrorCode.PAT_REQUIRED
            error.message shouldBe GitSyncErrorMessages.PAT_REQUIRED
        }

    private fun `sync returns non-repository error when git metadata is missing`() =
        runTest {
            val workflow = createWorkflow(token = "token")
            val notRepo = File(tempRoot, "sync-not-repo").also { it.mkdirs() }

            val result =
                workflow.sync(
                    rootDir = notRepo,
                    remoteUrl = "https://example.com/org/repo.git",
                    onSyncingState = {},
                )

            result shouldBe GitSyncResult.Error("Not a git repository. Please initialize first.")
        }

    private fun `sync succeeds against local bare remote and emits commit-pull-push phases`() =
        runTest {
            val remoteDir = File(tempRoot, "remote.git").also { it.mkdirs() }
            Git.init().setBare(true).setDirectory(remoteDir).call().close()

            val localDir = initRepo("sync-success-local")
            Git.open(localDir).use { git ->
                File(localDir, "memo.md").writeText("seed\n")
                git.add().addFilepattern("memo.md").call()
                commit(git, "seed commit")

                val branch = git.repository.branch
                git.remoteAdd().setName("origin").setUri(URIish(remoteDir.toURI().toString())).call()
                git
                    .push()
                    .setRemote("origin")
                    .setRefSpecs(RefSpec("refs/heads/$branch:refs/heads/$branch"))
                    .call()
            }

            val workflow = createWorkflow(token = "token")
            val phases = mutableListOf<UnifiedSyncPhase>()

            val result =
                workflow.sync(
                    rootDir = localDir,
                    remoteUrl = remoteDir.toURI().toString(),
                    onSyncingState = phases::add,
                )

            result shouldBe GitSyncResult.Success("Synced")
            (phases.contains(UnifiedSyncPhase.COMMITTING)).shouldBeTrue()
            (phases.contains(UnifiedSyncPhase.PULLING)).shouldBeTrue()
            (phases.contains(UnifiedSyncPhase.PUSHING)).shouldBeTrue()
        }

    private fun createWorkflow(
        token: String?,
        primitives: GitRepositoryPrimitives = GitRepositoryPrimitives(),
    ): GitSyncWorkflow {
        val credentialStrategy = gitCredentialStrategy(token)
        return GitSyncWorkflow(dataStore, credentialStrategy, primitives)
    }

    private fun initRepo(name: String): File {
        val repoDir = File(tempRoot, name).also { it.mkdirs() }
        Git.init().setDirectory(repoDir).call().close()
        return repoDir
    }

    private fun commit(
        git: Git,
        message: String,
    ) {
        git
            .commit()
            .setMessage(message)
            .setAuthor("Lomo Test", "lomo@test.local")
            .setCommitter("Lomo Test", "lomo@test.local")
            .call()
    }

    private fun commitCount(git: Git): Int =
        runCatching { git.log().call().count() }.getOrDefault(0)
}
