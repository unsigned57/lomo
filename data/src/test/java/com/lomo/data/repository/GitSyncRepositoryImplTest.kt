package com.lomo.data.repository

import android.content.Context
import com.lomo.data.git.GitCredentialStore
import com.lomo.data.git.GitMediaSyncBridge
import com.lomo.data.git.GitSyncEngine
import com.lomo.data.git.GitSyncQueryTestCoordinator
import com.lomo.data.git.SafGitMirrorBridge
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.parser.MarkdownParser
import com.lomo.data.source.MarkdownStorageDataSource
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
    private lateinit var context: Context

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
    private lateinit var gitMediaSyncBridge: GitMediaSyncBridge

    @MockK(relaxed = true)
    private lateinit var gitSyncQueryCoordinator: GitSyncQueryTestCoordinator

    @MockK(relaxed = true)
    private lateinit var markdownParser: MarkdownParser

    @MockK(relaxed = true)
    private lateinit var markdownStorageDataSource: MarkdownStorageDataSource

    private lateinit var repository: GitSyncRepositoryImpl

    @Before
    fun setUp() {
        MockKAnnotations.init(this)
        val tempFilesDir = Files.createTempDirectory("lomo-context-files").toFile()
        every { context.filesDir } returns tempFilesDir
        everyCommonConfig()
        val runtime =
            GitSyncRepositoryContext(
                context = context,
                gitSyncEngine = gitSyncEngine,
                credentialStore = credentialStore,
                dataStore = dataStore,
                memoSynchronizer = memoSynchronizer,
                safGitMirrorBridge = safGitMirrorBridge,
                gitMediaSyncBridge = gitMediaSyncBridge,
                gitSyncQueryCoordinator = gitSyncQueryCoordinator,
                markdownParser = markdownParser,
                markdownStorageDataSource = markdownStorageDataSource,
            )
        val support = GitSyncRepositorySupport(runtime)
        val memoMirror = GitSyncMemoMirror(runtime)
        repository =
            GitSyncRepositoryImpl(
                configurationRepository = GitSyncConfigurationRepositoryImpl(dataStore),
                configurationMutationRepository =
                    GitSyncConfigurationMutationRepositoryImpl(
                        dataStore = dataStore,
                        credentialStore = credentialStore,
                    ),
                operationRepository =
                    GitSyncOperationRepositoryImpl(
                        runtime = runtime,
                        initAndSyncExecutor =
                            GitSyncInitAndSyncExecutor(
                                runtime = runtime,
                                support = support,
                                memoMirror = memoMirror,
                            ),
                        statusExecutor =
                            GitSyncStatusExecutor(
                                runtime = runtime,
                                support = support,
                            ),
                        maintenanceExecutor =
                            GitSyncMaintenanceExecutor(
                                runtime = runtime,
                                support = support,
                                memoMirror = memoMirror,
                            ),
                    ),
                versionHistoryRepository =
                    GitSyncVersionHistoryRepositoryImpl(
                        runtime = runtime,
                        support = support,
                    ),
                conflictRepository =
                    GitSyncConflictRepositoryImpl(
                        runtime = runtime,
                        support = support,
                        memoMirror = memoMirror,
                    ),
                stateRepository = GitSyncStateRepositoryImpl(gitSyncEngine),
            )
    }

    @Test
    fun `sync returns error when memo refresh fails after successful git sync`() =
        runTest {
            val rootDir = createRepoRootWithGitDir()
            stubSameDirectoryLayout(rootDir)
            coEvery { credentialStore.getToken() } returns "token"
            coEvery {
                gitSyncEngine.sync(any(), REMOTE_URL)
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
                gitSyncEngine.sync(any(), REMOTE_URL)
                memoSynchronizer.refresh()
            }
        }

    @Test
    fun `sync rethrows cancellation when memo refresh is cancelled`() =
        runTest {
            val rootDir = createRepoRootWithGitDir()
            stubSameDirectoryLayout(rootDir)
            val cancellation = CancellationException("cancelled")
            coEvery { credentialStore.getToken() } returns "token"
            coEvery {
                gitSyncEngine.sync(any(), REMOTE_URL)
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
                gitSyncEngine.sync(any(), REMOTE_URL)
                memoSynchronizer.refresh()
            }
            verify(exactly = 0) { gitSyncEngine.markError(any()) }
        }

    private fun everyCommonConfig() {
        every { memoSynchronizer.outboxDrainCompleted } returns MutableSharedFlow()
        every { dataStore.gitSyncEnabled } returns flowOf(true)
        every { dataStore.gitRemoteUrl } returns flowOf(REMOTE_URL)
        every { dataStore.rootUri } returns flowOf(null)
        every { dataStore.imageDirectory } returns flowOf(null)
        every { dataStore.imageUri } returns flowOf(null)
        every { dataStore.voiceDirectory } returns flowOf(null)
        every { dataStore.voiceUri } returns flowOf(null)
    }

    private fun createRepoRootWithGitDir(): File {
        val rootDir = Files.createTempDirectory("lomo-sync-repo").toFile()
        File(rootDir, ".git").mkdirs()
        return rootDir
    }

    private fun stubSameDirectoryLayout(rootDir: File) {
        every { dataStore.rootDirectory } returns flowOf(rootDir.absolutePath)
        every { dataStore.imageDirectory } returns flowOf(rootDir.absolutePath)
        every { dataStore.voiceDirectory } returns flowOf(rootDir.absolutePath)
    }

    private companion object {
        const val REMOTE_URL = "https://example.com/lomo.git"
    }
}
