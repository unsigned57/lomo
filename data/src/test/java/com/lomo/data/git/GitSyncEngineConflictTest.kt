/*
 * Test Contract:
 * - Unit under test: GitSyncEngineConflictTest
 * - Owning layer: data
 * - Priority tier: P0
 *
 * Scenario matrix:
 * - Happy: standard happy path for GitSyncEngineConflictTest.
 * - Boundary: boundary and edge cases for GitSyncEngineConflictTest.
 * - Failure: failure and error scenarios for GitSyncEngineConflictTest.
 * - Must-not-happen: invariants are never violated for GitSyncEngineConflictTest.
 *
 * - Behavior focus: test behavioral outcomes of GitSyncEngineConflictTest.
 * - Observable outcomes: assertions verify expected outcomes.
 * - Red phase: Fails before JUnit 4 to Kotest migration due to test runner.
 * - Excludes: none.
 */

package com.lomo.data.git


import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.domain.model.GitSyncResult
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.URIish
import java.io.File
import java.nio.file.Files
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue

class GitSyncEngineConflictTest : DataFunSpec() {
    init {
        beforeTest {
            setUp()
        }

        afterTest {
            tearDown()
        }

        test("sync returns conflict on rebase conflict and preserves remote content") { `sync returns conflict on rebase conflict and preserves remote content`() }
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
                credentialStrategy = GitCredentialStrategy(credentialStore),
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
}
