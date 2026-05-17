/*
 * Test Contract:
 * - Unit under test: GitSyncEngineCollaborationTest
 * - Owning layer: data
 * - Priority tier: P0
 *
 * Scenario matrix:
 * - Happy: standard happy path for GitSyncEngineCollaborationTest.
 * - Boundary: boundary and edge cases for GitSyncEngineCollaborationTest.
 * - Failure: failure and error scenarios for GitSyncEngineCollaborationTest.
 * - Must-not-happen: invariants are never violated for GitSyncEngineCollaborationTest.
 *
 * - Behavior focus: test behavioral outcomes of GitSyncEngineCollaborationTest.
 * - Observable outcomes: assertions verify expected outcomes.
 * - Red phase: Fails before JUnit 4 to Kotest migration due to test runner.
 * - Excludes: none.
 */

package com.lomo.data.git


import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.domain.model.GitSyncResult
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.eclipse.jgit.api.Git
import java.io.File
import java.nio.file.Files
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.booleans.shouldBeTrue

class GitSyncEngineCollaborationTest : DataFunSpec() {
    init {
        beforeTest {
            setUp()
        }

        afterTest {
            tearDown()
        }

        test("initOrClone delegates remote and lock handling to primitives") { `initOrClone delegates remote and lock handling to primitives`() }

        test("getFileHistory delegates file loading to primitives") { `getFileHistory delegates file loading to primitives`() }
    }


    private lateinit var credentialStore: GitCredentialStore
    private lateinit var dataStore: LomoDataStore
    private lateinit var credentialStrategy: GitCredentialStrategy
    private lateinit var primitives: GitRepositoryPrimitives
    private lateinit var engine: GitSyncEngine
    private lateinit var queryCoordinator: GitSyncQueryTestCoordinator
    private lateinit var tempRoot: File

    private fun setUp() {
        MockKAnnotations.init(this)
        credentialStore = mockk(relaxed = true)
        dataStore = mockk(relaxed = true)
        every { credentialStore.getToken() } returns "dummy-token"
        every { dataStore.gitAuthorName } returns flowOf("Lomo Test")
        every { dataStore.gitAuthorEmail } returns flowOf("lomo@test.local")

        credentialStrategy = GitCredentialStrategy(credentialStore)
        primitives = spyk(GitRepositoryPrimitives())
        engine =
            GitSyncEngine(
                dataStore = dataStore,
                credentialStrategy = credentialStrategy,
                primitives = primitives,
            )
        queryCoordinator =
            GitSyncQueryTestCoordinator(
                credentialStrategy = credentialStrategy,
                fileHistoryReader = GitFileHistoryReader(primitives),
            )

        tempRoot = Files.createTempDirectory("git-sync-engine-collab").toFile()
    }

    private fun tearDown() {
        tempRoot.deleteRecursively()
    }

    private fun `initOrClone delegates remote and lock handling to primitives`() =
        runTest {
            val localRepo = File(tempRoot, "local").also { it.mkdirs() }
            Git
                .init()
                .setDirectory(localRepo)
                .call()
                .close()

            val result = engine.initOrClone(localRepo, "https://example.com/org/repo.git")

            (result is GitSyncResult.Success).shouldBeTrue()
            verify(exactly = 1) { primitives.cleanStaleLockFiles(localRepo) }
            verify(atLeast = 1) { primitives.ensureRemote(any(), "https://example.com/org/repo.git") }
        }

    private fun `getFileHistory delegates file loading to primitives`() {
        val localRepo = File(tempRoot, "history").also { it.mkdirs() }
        Git.init().setDirectory(localRepo).call().use { git ->
            File(localRepo, "memo.md").writeText("first\n")
            git.add().addFilepattern("memo.md").call()
            git
                .commit()
                .setMessage("add memo")
                .setAuthor("Local", "local@test.local")
                .setCommitter("Local", "local@test.local")
                .call()
        }

        val history = queryCoordinator.getFileHistory(localRepo, "memo.md", maxCount = 1)

        (history.isNotEmpty()).shouldBeTrue()
        verify(atLeast = 1) { primitives.readFileAtCommit(any(), any(), "memo.md") }
    }
}
