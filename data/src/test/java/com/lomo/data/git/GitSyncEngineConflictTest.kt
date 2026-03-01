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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class GitSyncEngineConflictTest {
    @MockK(relaxed = true)
    private lateinit var credentialStore: GitCredentialStore

    @MockK(relaxed = true)
    private lateinit var dataStore: LomoDataStore

    private lateinit var engine: GitSyncEngine
    private lateinit var tempRoot: File

    @Before
    fun setUp() {
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

    @After
    fun tearDown() {
        tempRoot.deleteRecursively()
    }

    @Test
    fun `sync returns error on rebase conflict and preserves remote content`() =
        runTest {
            val remoteBareDir = File(tempRoot, "remote.git").also { it.mkdirs() }
            val remoteUrl = remoteBareDir.toURI().toString()
            Git.init().setBare(true).setDirectory(remoteBareDir).call().use { }

            val seedDir = File(tempRoot, "seed").also { it.mkdirs() }
            createBaseCommitAndPush(seedDir, remoteUrl)

            val localDir = File(tempRoot, "local")
            Git.cloneRepository()
                .setURI(seedDir.toURI().toString())
                .setDirectory(localDir)
                .setBranch("main")
                .call()
                .close()

            Git.open(seedDir).use { seedGit ->
                File(seedDir, "memo.md").writeText("remote change\n")
                seedGit.add().addFilepattern("memo.md").call()
                seedGit.commit()
                    .setMessage("remote: update memo")
                    .setAuthor("Remote", "remote@test.local")
                    .setCommitter("Remote", "remote@test.local")
                    .call()
                seedGit.push()
                    .setRemote("origin")
                    .setRefSpecs(RefSpec("refs/heads/main:refs/heads/main"))
                    .call()
            }

            File(localDir, "memo.md").writeText("local change\n")

            val result = engine.sync(localDir, remoteUrl)
            assertTrue(result is GitSyncResult.Error)
            val error = result as GitSyncResult.Error
            assertTrue(error.message.contains("rebase", ignoreCase = true))

            Git.open(seedDir).use { seedGit ->
                seedGit.fetch().setRemote("origin").call()
                seedGit.reset()
                    .setMode(ResetCommand.ResetType.HARD)
                    .setRef("origin/main")
                    .call()
            }
            assertEquals("remote change\n", File(seedDir, "memo.md").readText())
        }

    private fun createBaseCommitAndPush(
        seedDir: File,
        remoteUrl: String,
    ) {
        Git.init().setDirectory(seedDir).call().use { seedGit ->
            File(seedDir, "memo.md").writeText("base\n")
            seedGit.add().addFilepattern("memo.md").call()
            seedGit.commit()
                .setMessage("base: add memo")
                .setAuthor("Seed", "seed@test.local")
                .setCommitter("Seed", "seed@test.local")
                .call()

            val currentBranch = seedGit.repository.branch
            if (currentBranch != "main") {
                seedGit.branchRename().setOldName(currentBranch).setNewName("main").call()
            }

            seedGit.remoteAdd()
                .setName("origin")
                .setUri(URIish(remoteUrl))
                .call()
            seedGit.push()
                .setRemote("origin")
                .setRefSpecs(RefSpec("refs/heads/main:refs/heads/main"))
                .call()
        }
    }
}
