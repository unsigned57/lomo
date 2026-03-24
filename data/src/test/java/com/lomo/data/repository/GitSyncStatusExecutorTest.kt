package com.lomo.data.repository

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
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/*
 * Test Contract:
 * - Unit under test: GitSyncStatusExecutor
 * - Behavior focus: status repository-path resolution and connection precondition gating.
 * - Observable outcomes: returned GitSyncStatus/GitSyncResult values and branch-specific collaborator calls.
 * - Excludes: git query engine internals, SAF mirror transport implementation, and UI behavior.
 */
class GitSyncStatusExecutorTest {
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

    @Before
    fun setUp() {
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

    @Test
    fun `getStatus uses direct root repo when direct root exists`() =
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

            assertEquals(expected.copy(lastSyncTime = 123L), result)
            verify(exactly = 1) { gitSyncQueryCoordinator.getStatus(rootDir) }
        }

    @Test
    fun `getStatus uses hashed SAF repo in split-directory layout`() =
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

            assertEquals(expected, result)
            verify(exactly = 1) { gitSyncQueryCoordinator.getStatus(safRepoDir) }
            coVerify(exactly = 0) { safGitMirrorBridge.mirrorDirectoryFor(any()) }
        }

    @Test
    fun `getStatus returns empty status when SAF all-same mirror preparation fails`() =
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

            assertEquals(
                GitSyncStatus(
                    hasLocalChanges = false,
                    aheadCount = 0,
                    behindCount = 0,
                    lastSyncTime = 777L,
                ),
                result,
            )
            verify(exactly = 0) { gitSyncQueryCoordinator.getStatus(any()) }
        }

    @Test
    fun `getStatus returns empty status and no query when no root is configured`() =
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

            assertEquals(
                GitSyncStatus(
                    hasLocalChanges = false,
                    aheadCount = 0,
                    behindCount = 0,
                    lastSyncTime = null,
                ),
                result,
            )
            verify(exactly = 0) { gitSyncQueryCoordinator.getStatus(any()) }
        }

    @Test
    fun `testConnection returns repository-url-not-configured when remote url is blank`() =
        runTest {
            every { dataStore.gitRemoteUrl } returns flowOf(" ")

            val result = executor.testConnection()

            assertEquals(GitSyncResult.Error(REPOSITORY_URL_NOT_CONFIGURED_MESSAGE), result)
            verify(exactly = 0) { gitSyncQueryCoordinator.testConnection(any()) }
        }

    @Test
    fun `testConnection returns PAT required when token is missing`() =
        runTest {
            every { dataStore.gitRemoteUrl } returns flowOf("https://example.com/lomo.git")
            coEvery { credentialStore.getToken() } returns ""

            val result = executor.testConnection()

            assertEquals(GitSyncResult.Error(GitSyncErrorMessages.PAT_REQUIRED), result)
            verify(exactly = 0) { gitSyncQueryCoordinator.testConnection(any()) }
        }

    @Test
    fun `testConnection delegates to query coordinator when preconditions are satisfied`() =
        runTest {
            every { dataStore.gitRemoteUrl } returns flowOf("https://example.com/lomo.git")
            coEvery { credentialStore.getToken() } returns "token"
            val expected = GitSyncResult.Success("connected")
            every { gitSyncQueryCoordinator.testConnection("https://example.com/lomo.git") } returns expected

            val result = executor.testConnection()

            assertEquals(expected, result)
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
