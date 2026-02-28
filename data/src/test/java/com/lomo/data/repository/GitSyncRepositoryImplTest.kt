package com.lomo.data.repository

import com.lomo.data.git.GitCredentialStore
import com.lomo.data.git.GitSyncEngine
import com.lomo.data.git.SafGitMirrorBridge
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.parser.MarkdownParser
import com.lomo.domain.model.GitSyncResult
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class GitSyncRepositoryImplTest {
    @MockK(relaxed = true)
    private lateinit var gitSyncEngine: GitSyncEngine

    @MockK(relaxed = true)
    private lateinit var credentialStore: GitCredentialStore

    @MockK(relaxed = true)
    private lateinit var dataStore: LomoDataStore

    @MockK(relaxed = true)
    private lateinit var memoSynchronizer: MemoSynchronizer

    @MockK(relaxed = true)
    private lateinit var safGitMirrorBridge: SafGitMirrorBridge

    @MockK(relaxed = true)
    private lateinit var markdownParser: MarkdownParser

    private lateinit var repository: GitSyncRepositoryImpl

    @Before
    fun setUp() {
        MockKAnnotations.init(this)
        everyCommonConfig()
        repository =
            GitSyncRepositoryImpl(
                gitSyncEngine = gitSyncEngine,
                credentialStore = credentialStore,
                dataStore = dataStore,
                memoSynchronizer = memoSynchronizer,
                safGitMirrorBridge = safGitMirrorBridge,
                markdownParser = markdownParser,
            )
    }

    @Test
    fun `sync returns error when memo refresh fails after successful git sync`() =
        runTest {
            val rootDir = createRepoRootWithGitDir()
            every { dataStore.rootDirectory } returns flowOf(rootDir.absolutePath)
            coEvery { credentialStore.getToken() } returns "token"
            coEvery {
                gitSyncEngine.sync(File(rootDir.absolutePath), REMOTE_URL)
            } returns GitSyncResult.Success("git sync done")
            coEvery { memoSynchronizer.refresh() } throws IllegalStateException("refresh failed")

            val result = repository.sync()

            assertTrue(result is GitSyncResult.Error)
            val error = result as GitSyncResult.Error
            assertTrue(error.message.contains("memo refresh failed"))
            assertTrue(error.message.contains("refresh failed"))
            assertTrue(error.exception is IllegalStateException)
            val markedErrorSlot = slot<String>()
            verify(exactly = 1) {
                gitSyncEngine.markError(capture(markedErrorSlot))
            }
            assertTrue(markedErrorSlot.captured.contains("memo refresh failed"))
            assertTrue(markedErrorSlot.captured.contains("refresh failed"))
            coVerifyOrder {
                credentialStore.getToken()
                gitSyncEngine.sync(File(rootDir.absolutePath), REMOTE_URL)
                memoSynchronizer.refresh()
            }
        }

    @Test
    fun `sync rethrows cancellation when memo refresh is cancelled`() =
        runTest {
            val rootDir = createRepoRootWithGitDir()
            every { dataStore.rootDirectory } returns flowOf(rootDir.absolutePath)
            val cancellation = CancellationException("cancelled")
            coEvery { credentialStore.getToken() } returns "token"
            coEvery {
                gitSyncEngine.sync(File(rootDir.absolutePath), REMOTE_URL)
            } returns GitSyncResult.Success("git sync done")
            coEvery { memoSynchronizer.refresh() } throws cancellation

            try {
                repository.sync()
                fail("Expected CancellationException")
            } catch (e: CancellationException) {
                assertSame(cancellation, e)
            }

            coVerifyOrder {
                credentialStore.getToken()
                gitSyncEngine.sync(File(rootDir.absolutePath), REMOTE_URL)
                memoSynchronizer.refresh()
            }
            verify(exactly = 0) { gitSyncEngine.markError(any()) }
        }

    private fun everyCommonConfig() {
        every { memoSynchronizer.outboxDrainCompleted } returns MutableSharedFlow()
        every { dataStore.gitSyncEnabled } returns flowOf(true)
        every { dataStore.gitRemoteUrl } returns flowOf(REMOTE_URL)
        every { dataStore.rootUri } returns flowOf(null)
    }

    private fun createRepoRootWithGitDir(): File {
        val rootDir = Files.createTempDirectory("lomo-sync-repo").toFile()
        File(rootDir, ".git").mkdirs()
        return rootDir
    }

    private companion object {
        const val REMOTE_URL = "https://example.com/lomo.git"
    }
}
