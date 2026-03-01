package com.lomo.data.git

import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.domain.model.GitSyncResult
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.eclipse.jgit.api.Git
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GitSyncEngineCollaborationTest {
    private lateinit var credentialStore: GitCredentialStore
    private lateinit var dataStore: LomoDataStore
    private lateinit var credentialStrategy: GitCredentialStrategy
    private lateinit var primitives: GitRepositoryPrimitives
    private lateinit var engine: GitSyncEngine
    private lateinit var tempRoot: File

    @Before
    fun setUp() {
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

        tempRoot = Files.createTempDirectory("git-sync-engine-collab").toFile()
    }

    @After
    fun tearDown() {
        tempRoot.deleteRecursively()
    }

    @Test
    fun `initOrClone delegates remote and lock handling to primitives`() =
        runTest {
            val localRepo = File(tempRoot, "local").also { it.mkdirs() }
            Git.init().setDirectory(localRepo).call().close()

            val result = engine.initOrClone(localRepo, "https://example.com/org/repo.git")

            assertTrue(result is GitSyncResult.Success)
            verify(exactly = 1) { primitives.cleanStaleLockFiles(localRepo) }
            verify(atLeast = 1) { primitives.ensureRemote(any(), "https://example.com/org/repo.git") }
        }

    @Test
    fun `getFileHistory delegates file loading to primitives`() {
        val localRepo = File(tempRoot, "history").also { it.mkdirs() }
        Git.init().setDirectory(localRepo).call().use { git ->
            File(localRepo, "memo.md").writeText("first\n")
            git.add().addFilepattern("memo.md").call()
            git.commit()
                .setMessage("add memo")
                .setAuthor("Local", "local@test.local")
                .setCommitter("Local", "local@test.local")
                .call()
        }

        val history = engine.getFileHistory(localRepo, "memo.md", maxCount = 1)

        assertTrue(history.isNotEmpty())
        verify(atLeast = 1) { primitives.readFileAtCommit(any(), any(), "memo.md") }
    }
}
