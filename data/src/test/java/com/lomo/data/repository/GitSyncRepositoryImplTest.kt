package com.lomo.data.repository

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
import java.io.File
import java.nio.file.Files
import com.lomo.data.testing.DataFunSpec
import io.kotest.assertions.fail
import io.kotest.matchers.booleans.shouldBeTrue

/*
 * Behavior Contract:
 * - Unit under test: GitSyncRepositoryImpl
 * - Behavior focus: sync still reports refresh failures and cancellation correctly after removing unused Git memo-history wiring.
 * - Observable outcomes: returned GitSyncResult error details, cancellation propagation, and collaborator call ordering.
 * - TDD proof: Fails to compile before the cleanup because GitSyncRepositoryImpl still requires the deleted version-history collaborator.
 * - Excludes: version-history queries, git engine internals, and memo refresh parsing details.
 */
class GitSyncRepositoryImplTest : DataFunSpec() {
    init {
        beforeTest {
            setUp()
        }

        test("sync returns error when memo refresh fails after successful git sync") { `sync returns error when memo refresh fails after successful git sync`() }

        test("sync rethrows cancellation when memo refresh is cancelled") { `sync rethrows cancellation when memo refresh is cancelled`() }
    }


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

    private fun setUp() {
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
                conflictRepository =
                    GitSyncConflictRepositoryImpl(
                        runtime = runtime,
                        support = support,
                        memoMirror = memoMirror,
                    ),
                stateRepository = GitSyncStateRepositoryImpl(gitSyncEngine),
            )
    }

    private fun `sync returns error when memo refresh fails after successful git sync`() =
        runTest {
            val rootDir = createRepoRootWithGitDir()
            stubSameDirectoryLayout(rootDir)
            coEvery { credentialStore.getToken() } returns "token"
            coEvery {
                gitSyncEngine.sync(any(), REMOTE_URL)
            } returns GitSyncResult.Success("git sync done")
            coEvery { memoSynchronizer.refresh() } throws IllegalStateException("refresh failed")

            val result = repository.sync()

            (result is GitSyncResult.Error).shouldBeTrue()
            val error = result as GitSyncResult.Error
            (error.message.contains("memo refresh failed")).shouldBeTrue()
            (error.message.contains("refresh failed")).shouldBeTrue()
            (error.exception is IllegalStateException).shouldBeTrue()
            val markedErrorSlot = slot<String>()
            verify(exactly = 1) {
                gitSyncEngine.markError(capture(markedErrorSlot))
            }
            (markedErrorSlot.captured.contains("memo refresh failed")).shouldBeTrue()
            (markedErrorSlot.captured.contains("refresh failed")).shouldBeTrue()
            coVerifyOrder {
                credentialStore.getToken()
                gitSyncEngine.sync(any(), REMOTE_URL)
                memoSynchronizer.refresh()
            }
        }

    private fun `sync rethrows cancellation when memo refresh is cancelled`() =
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
                (e === cancellation).shouldBeTrue()
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
