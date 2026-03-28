package com.lomo.data.git

import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.domain.model.GitSyncErrorCode
import com.lomo.domain.model.GitSyncResult
import com.lomo.domain.model.SyncEngineState
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.URIish
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/*
 * Test Contract:
 * - Unit under test: GitSyncWorkflow
 * - Behavior focus: local commit branching (no-op / commit / amend), non-repository error handling, PAT precondition, and a stable local-file sync success path.
 * - Observable outcomes: GitSyncResult subtype or message, commit history/count changes, and emitted syncing phases.
 * - Red phase: Not applicable - test-only coverage addition; no production change.
 * - Excludes: network transport behavior, conflict-content extraction internals, and engine-level state holder orchestration.
 */
class GitSyncWorkflowTest {
    private lateinit var tempRoot: File
    private lateinit var dataStore: LomoDataStore

    @Before
    fun setUp() {
        tempRoot = Files.createTempDirectory("git-sync-workflow-test").toFile()
        dataStore = mockk(relaxed = true)
        every { dataStore.gitAuthorName } returns flowOf("Lomo Test")
        every { dataStore.gitAuthorEmail } returns flowOf("lomo@test.local")
    }

    @After
    fun tearDown() {
        tempRoot.deleteRecursively()
    }

    @Test
    fun `commitLocal returns non-repository error when git metadata is missing`() =
        runTest {
            val workflow = createWorkflow(token = "token")
            val notRepo = File(tempRoot, "not-repo").also { it.mkdirs() }

            val result = workflow.commitLocal(notRepo)

            assertEquals(GitSyncResult.Error("Not a git repository"), result)
        }

    @Test
    fun `commitLocal returns nothing-to-commit on clean repository`() =
        runTest {
            val repoDir = initRepo("clean")
            val workflow = createWorkflow(token = "token")

            val result = workflow.commitLocal(repoDir)

            assertEquals(GitSyncResult.Success("Nothing to commit"), result)
        }

    @Test
    fun `commitLocal creates normal lomo commit when pending changes exist and amend is false`() =
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

            assertEquals(GitSyncResult.Success("Committed locally"), result)
            Git.open(repoDir).use { git ->
                val head = git.repository.resolve("HEAD")
                assertNotNull(head)
                assertEquals(2, commitCount(git))
                val latest = git.log().setMaxCount(1).call().first()
                assertTrue(latest.fullMessage.startsWith("sync:"))
                assertTrue(latest.fullMessage.contains("via Lomo"))
            }
        }

    @Test
    fun `commitLocal amends latest commit when primitives request amend branch`() =
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

            assertEquals(GitSyncResult.Success("Amended local commit"), result)
            Git.open(repoDir).use { git ->
                assertEquals(1, commitCount(git))
                val latest = git.log().setMaxCount(1).call().first()
                assertTrue(latest.fullMessage.contains("via Lomo"))
            }
        }

    @Test
    fun `sync returns PAT-required error before touching repository state`() =
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
            assertEquals(GitSyncErrorCode.PAT_REQUIRED, error.code)
            assertEquals(GitSyncErrorMessages.PAT_REQUIRED, error.message)
        }

    @Test
    fun `sync returns non-repository error when git metadata is missing`() =
        runTest {
            val workflow = createWorkflow(token = "token")
            val notRepo = File(tempRoot, "sync-not-repo").also { it.mkdirs() }

            val result =
                workflow.sync(
                    rootDir = notRepo,
                    remoteUrl = "https://example.com/org/repo.git",
                    onSyncingState = {},
                )

            assertEquals(
                GitSyncResult.Error("Not a git repository. Please initialize first."),
                result,
            )
        }

    @Test
    fun `sync succeeds against local bare remote and emits commit-pull-push phases`() =
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
            val phases = mutableListOf<SyncEngineState.Syncing>()

            val result =
                workflow.sync(
                    rootDir = localDir,
                    remoteUrl = remoteDir.toURI().toString(),
                    onSyncingState = phases::add,
                )

            assertEquals(GitSyncResult.Success("Synced"), result)
            assertTrue(phases.contains(SyncEngineState.Syncing.Committing))
            assertTrue(phases.contains(SyncEngineState.Syncing.Pulling))
            assertTrue(phases.contains(SyncEngineState.Syncing.Pushing))
        }

    private fun createWorkflow(
        token: String?,
        primitives: GitRepositoryPrimitives = GitRepositoryPrimitives(),
    ): GitSyncWorkflow {
        val credentialStore = mockk<GitCredentialStore>()
        every { credentialStore.getToken() } returns token
        val credentialStrategy = GitCredentialStrategy(credentialStore)
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
