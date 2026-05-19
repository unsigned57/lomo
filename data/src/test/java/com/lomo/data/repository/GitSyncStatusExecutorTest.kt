/*
 * Behavior Contract:
 * - Unit under test: GitSyncStatusExecutorTest
 * - Owning layer: data
 * - Priority tier: P0
 *
 * Scenarios:
 * - Happy: standard happy path for GitSyncStatusExecutorTest.
 * - Boundary: boundary and edge cases for GitSyncStatusExecutorTest.
 * - Failure: failure and error scenarios for GitSyncStatusExecutorTest.
 * - Must-not-happen: invariants are never violated for GitSyncStatusExecutorTest.
 *
 * - Behavior focus: test behavioral outcomes of GitSyncStatusExecutorTest.
 * - Observable outcomes: assertions verify expected outcomes.
 * - TDD proof: Fails before JUnit 4 to Kotest migration due to test runner.
 * - Excludes: none.
 */

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
import com.lomo.data.git.GitSyncErrorMessages
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
import io.mockk.verify
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: GitSyncStatusExecutor
 * - Behavior focus: status repository-path resolution and connection precondition gating.
 * - Observable outcomes: returned GitSyncStatus/GitSyncResult values and branch-specific collaborator calls.
 * - Excludes: git query engine internals, SAF mirror transport implementation, and UI behavior.
 */
class GitSyncStatusExecutorTest : DataFunSpec() {
    init {
        beforeTest {
            setUp()
        }

        test("getStatus uses direct root repo when direct root exists") { `getStatus uses direct root repo when direct root exists`() }

        test("getStatus uses hashed SAF repo in split-directory layout") { `getStatus uses hashed SAF repo in split-directory layout`() }

        test("getStatus returns empty status when SAF all-same mirror preparation fails") { `getStatus returns empty status when SAF all-same mirror preparation fails`() }

        test("getStatus returns empty status and no query when no root is configured") { `getStatus returns empty status and no query when no root is configured`() }

        test("testConnection returns repository-url-not-configured when remote url is blank") { `testConnection returns repository-url-not-configured when remote url is blank`() }

        test("testConnection returns PAT required when token is missing") { `testConnection returns PAT required when token is missing`() }

        test("testConnection delegates to query coordinator when preconditions are satisfied") { `testConnection delegates to query coordinator when preconditions are satisfied`() }
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

    private lateinit var support: GitSyncRepositorySupport
    private lateinit var executor: GitSyncStatusExecutor

    private fun setUp() {
        MockKAnnotations.init(this)
        every { context.filesDir } returns Files.createTempDirectory("git-status-executor").toFile()
        every { memoSynchronizer.outboxDrainCompleted } returns MutableSharedFlow()

        stubLayout(
            rootDirectory = null,
            rootUri = null,
            imageDirectory = null,
            imageUri = null,
            voiceDirectory = null,
            voiceUri = null,
        )
        every { dataStore.gitLastSyncTime } returns flowOf(0L)
        every { dataStore.gitRemoteUrl } returns flowOf("https://example.com/lomo.git")
        coEvery { credentialStore.getToken() } returns "token"

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
        support = GitSyncRepositorySupport(runtime)
        executor = GitSyncStatusExecutor(runtime, support)
    }

    private fun `getStatus uses direct root repo when direct root exists`() =
        runTest {
            val rootDir = Files.createTempDirectory("git-status-direct").toFile()
            stubLayout(
                rootDirectory = rootDir.absolutePath,
                rootUri = null,
                imageDirectory = rootDir.absolutePath,
                imageUri = null,
                voiceDirectory = rootDir.absolutePath,
                voiceUri = null,
            )
            every { dataStore.gitLastSyncTime } returns flowOf(123L)
            val expected =
                GitSyncStatus(
                    hasLocalChanges = true,
                    aheadCount = 2,
                    behindCount = 1,
                    lastSyncTime = null,
                )
            every {
                gitSyncQueryCoordinator.getStatus(rootDir)
            } returns expected

            val result = executor.getStatus()

            result shouldBe expected.copy(lastSyncTime = 123L)
            verify(exactly = 1) { gitSyncQueryCoordinator.getStatus(rootDir) }
        }

    private fun `getStatus uses hashed SAF repo in split-directory layout`() =
        runTest {
            val safRoot = "saf-root"
            stubLayout(
                rootDirectory = null,
                rootUri = safRoot,
                imageDirectory = null,
                imageUri = "saf-images",
                voiceDirectory = null,
                voiceUri = "saf-voice",
            )
            every { dataStore.gitLastSyncTime } returns flowOf(0L)
            val safRepoDir = support.resolveGitRepoDirForUri(safRoot)
            val expected =
                GitSyncStatus(
                    hasLocalChanges = false,
                    aheadCount = 0,
                    behindCount = 3,
                    lastSyncTime = null,
                )
            every { gitSyncQueryCoordinator.getStatus(safRepoDir) } returns expected

            val result = executor.getStatus()

            result shouldBe expected
            verify(exactly = 1) { gitSyncQueryCoordinator.getStatus(safRepoDir) }
            coVerify(exactly = 0) { safGitMirrorBridge.mirrorDirectoryFor(any()) }
        }

    private fun `getStatus returns empty status when SAF all-same mirror preparation fails`() =
        runTest {
            val safRoot = "saf-shared-root"
            stubLayout(
                rootDirectory = null,
                rootUri = safRoot,
                imageDirectory = null,
                imageUri = safRoot,
                voiceDirectory = null,
                voiceUri = safRoot,
            )
            every { dataStore.gitLastSyncTime } returns flowOf(777L)
            coEvery { safGitMirrorBridge.mirrorDirectoryFor(safRoot) } throws IllegalStateException("mirror failed")

            val result = executor.getStatus()

            result shouldBe GitSyncStatus(
                    hasLocalChanges = false,
                    aheadCount = 0,
                    behindCount = 0,
                    lastSyncTime = 777L,
                )
            verify(exactly = 0) { gitSyncQueryCoordinator.getStatus(any()) }
        }

    private fun `getStatus returns empty status and no query when no root is configured`() =
        runTest {
            stubLayout(
                rootDirectory = null,
                rootUri = null,
                imageDirectory = null,
                imageUri = null,
                voiceDirectory = null,
                voiceUri = null,
            )
            every { dataStore.gitLastSyncTime } returns flowOf(0L)

            val result = executor.getStatus()

            result shouldBe GitSyncStatus(
                    hasLocalChanges = false,
                    aheadCount = 0,
                    behindCount = 0,
                    lastSyncTime = null,
                )
            verify(exactly = 0) { gitSyncQueryCoordinator.getStatus(any()) }
        }

    private fun `testConnection returns repository-url-not-configured when remote url is blank`() =
        runTest {
            every { dataStore.gitRemoteUrl } returns flowOf(" ")

            val result = executor.testConnection()

            result shouldBe GitSyncResult.Error(REPOSITORY_URL_NOT_CONFIGURED_MESSAGE)
            verify(exactly = 0) { gitSyncQueryCoordinator.testConnection(any()) }
        }

    private fun `testConnection returns PAT required when token is missing`() =
        runTest {
            every { dataStore.gitRemoteUrl } returns flowOf("https://example.com/lomo.git")
            coEvery { credentialStore.getToken() } returns ""

            val result = executor.testConnection()

            result shouldBe GitSyncResult.Error(GitSyncErrorMessages.PAT_REQUIRED)
            verify(exactly = 0) { gitSyncQueryCoordinator.testConnection(any()) }
        }

    private fun `testConnection delegates to query coordinator when preconditions are satisfied`() =
        runTest {
            every { dataStore.gitRemoteUrl } returns flowOf("https://example.com/lomo.git")
            coEvery { credentialStore.getToken() } returns "token"
            val expected = GitSyncResult.Success("connected")
            every { gitSyncQueryCoordinator.testConnection("https://example.com/lomo.git") } returns expected

            val result = executor.testConnection()

            result shouldBe expected
            verify(exactly = 1) { gitSyncQueryCoordinator.testConnection("https://example.com/lomo.git") }
        }

    private fun stubLayout(
        rootDirectory: String?,
        rootUri: String?,
        imageDirectory: String?,
        imageUri: String?,
        voiceDirectory: String?,
        voiceUri: String?,
    ) {
        every { dataStore.rootDirectory } returns flowOf(rootDirectory)
        every { dataStore.rootUri } returns flowOf(rootUri)
        every { dataStore.imageDirectory } returns flowOf(imageDirectory)
        every { dataStore.imageUri } returns flowOf(imageUri)
        every { dataStore.voiceDirectory } returns flowOf(voiceDirectory)
        every { dataStore.voiceUri } returns flowOf(voiceUri)
    }
}
