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
import com.lomo.domain.model.GitSyncStatus
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue

/*
 * Behavior Contract:
 * - Unit under test: GitSyncOperationRepositoryImpl
 * - Behavior focus: sync guard short-circuiting, disabled/not-configured propagation, and executor delegation.
 * - Observable outcomes: returned GitSyncResult/GitSyncStatus values and collaborator invocation counts.
 * - Excludes: git engine internals, SAF mirror behavior, and repository wiring outside this operation facade.
 */
class GitSyncOperationRepositoryImplTest : DataFunSpec() {
    init {
        beforeTest {
            setUp()
        }

        test("initOrClone delegates to executor result") { `initOrClone delegates to executor result`() }

        test("sync propagates not-configured result from executor") { `sync propagates not-configured result from executor`() }

        test("sync short-circuits when another sync is in progress") { `sync short-circuits when another sync is in progress`() }

        test("sync releases guard after failure so a later sync can run") { `sync releases guard after failure so a later sync can run`() }

        test("getStatus delegates to status executor") { `getStatus delegates to status executor`() }

        test("testConnection delegates to status executor") { `testConnection delegates to status executor`() }

        test("maintenance operations delegate to maintenance executor") { `maintenance operations delegate to maintenance executor`() }
    }


    @MockK(relaxed = true)
    private lateinit var context: Context

    @MockK(relaxed = true)
    private lateinit var refreshEngine: MemoRefreshEngine

    @MockK(relaxed = true)
    private lateinit var mutationHandler: MemoMutationHandler

    @MockK(relaxed = true)
    private lateinit var dataStore: LomoDataStore

    @MockK(relaxed = true)
    private lateinit var gitSyncEngine: GitSyncEngine

    @MockK(relaxed = true)
    private lateinit var credentialStore: GitCredentialStore

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

    @MockK(relaxed = true)
    private lateinit var initAndSyncExecutor: GitSyncInitAndSyncExecutor

    @MockK(relaxed = true)
    private lateinit var statusExecutor: GitSyncStatusExecutor

    @MockK(relaxed = true)
    private lateinit var maintenanceExecutor: GitSyncMaintenanceExecutor

    private lateinit var memoSynchronizer: MemoSynchronizer
    private lateinit var runtime: GitSyncRepositoryContext
    private lateinit var repository: GitSyncOperationRepositoryImpl

    private fun setUp() {
        MockKAnnotations.init(this)
        every { context.filesDir } returns Files.createTempDirectory("git-sync-operation").toFile()
        every { dataStore.gitSyncEnabled } returns flowOf(false)
        memoSynchronizer =
            MemoSynchronizer(
                refreshEngine = refreshEngine,
                mutationHandler = mutationHandler,
                outboxScope = immediateTestBackgroundScope(),
                startOutboxCoordinator = false,
            )
        runtime =
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

        repository =
            GitSyncOperationRepositoryImpl(
                runtime = runtime,
                initAndSyncExecutor = initAndSyncExecutor,
                statusExecutor = statusExecutor,
                maintenanceExecutor = maintenanceExecutor,
            )
    }

    private fun `initOrClone delegates to executor result`() =
        runTest {
            val expected = GitSyncResult.Success("initialized")
            coEvery { initAndSyncExecutor.initOrClone() } returns expected

            val result = repository.initOrClone()

            result shouldBe expected
            coVerify(exactly = 1) { initAndSyncExecutor.initOrClone() }
        }

    private fun `sync propagates not-configured result from executor`() =
        runTest {
            coEvery { initAndSyncExecutor.sync() } returns GitSyncResult.NotConfigured

            val result = repository.sync()

            result shouldBe GitSyncResult.NotConfigured
            coVerify(exactly = 1) { initAndSyncExecutor.sync() }
        }

    private fun `sync short-circuits when another sync is in progress`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            coEvery { initAndSyncExecutor.sync() } coAnswers {
                gate.await()
                GitSyncResult.Success("sync done")
            }

            val firstCall = async { repository.sync() }
            kotlinx.coroutines.yield()
            val secondCall = repository.sync()

            secondCall shouldBe GitSyncResult.Success("Sync already in progress")

            gate.complete(Unit)
            firstCall.await() shouldBe GitSyncResult.Success("sync done")
            coVerify(exactly = 1) { initAndSyncExecutor.sync() }
        }

    private fun `sync releases guard after failure so a later sync can run`() =
        runTest {
            coEvery { initAndSyncExecutor.sync() } throws IllegalStateException("sync failed") andThen GitSyncResult.Success("recovered")

            val firstFailure =
                runCatching {
                    repository.sync()
                }.exceptionOrNull()
            val secondResult = repository.sync()

            (firstFailure is IllegalStateException).shouldBeTrue()
            firstFailure?.message shouldBe "sync failed"
            secondResult shouldBe GitSyncResult.Success("recovered")
            coVerify(exactly = 2) { initAndSyncExecutor.sync() }
        }

    private fun `getStatus delegates to status executor`() =
        runTest {
            val expected =
                GitSyncStatus(
                    hasLocalChanges = true,
                    aheadCount = 2,
                    behindCount = 1,
                    lastSyncTime = 123L,
                )
            coEvery { statusExecutor.getStatus() } returns expected

            val result = repository.getStatus()

            result shouldBe expected
            coVerify(exactly = 1) { statusExecutor.getStatus() }
        }

    private fun `testConnection delegates to status executor`() =
        runTest {
            val expected = GitSyncResult.Error("network down")
            coEvery { statusExecutor.testConnection() } returns expected

            val result = repository.testConnection()

            result shouldBe expected
            coVerify(exactly = 1) { statusExecutor.testConnection() }
        }

    private fun `maintenance operations delegate to maintenance executor`() =
        runTest {
            coEvery { maintenanceExecutor.resetRepository() } returns GitSyncResult.Success("reset")
            coEvery { maintenanceExecutor.resetLocalBranchToRemote() } returns GitSyncResult.Success("hard reset")
            coEvery { maintenanceExecutor.forcePushLocalToRemote() } returns GitSyncResult.Success("force push")

            repository.resetRepository() shouldBe GitSyncResult.Success("reset")
            repository.resetLocalBranchToRemote() shouldBe GitSyncResult.Success("hard reset")
            repository.forcePushLocalToRemote() shouldBe GitSyncResult.Success("force push")

            coVerify(exactly = 1) { maintenanceExecutor.resetRepository() }
            coVerify(exactly = 1) { maintenanceExecutor.resetLocalBranchToRemote() }
            coVerify(exactly = 1) { maintenanceExecutor.forcePushLocalToRemote() }
        }
}
