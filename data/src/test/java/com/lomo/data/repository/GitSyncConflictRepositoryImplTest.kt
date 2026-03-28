package com.lomo.data.repository

import com.lomo.data.git.GitCredentialStore
import com.lomo.data.git.GitSyncEngine
import com.lomo.data.git.SafGitMirrorBridge
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.domain.model.GitSyncErrorCode
import com.lomo.domain.model.GitSyncResult
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncConflictFile
import com.lomo.domain.model.SyncConflictResolution
import com.lomo.domain.model.SyncConflictResolutionChoice
import com.lomo.domain.model.SyncConflictSet
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File

/*
 * Test Contract:
 * - Unit under test: GitSyncConflictRepositoryImpl
 * - Behavior focus: precondition guarding, repo-directory resolution, and post-resolution memo refresh orchestration.
 * - Observable outcomes: returned GitSyncResult type or code, chosen repo directory source, and memo mirror or refresh side effects.
 * - Red phase: Not applicable - test-only coverage addition; no production change.
 * - Excludes: JGit conflict internals, filesystem mirroring implementation, and UI state mapping.
 */
class GitSyncConflictRepositoryImplTest {
    @MockK(relaxed = true)
    private lateinit var runtime: GitSyncRepositoryContext

    @MockK(relaxed = true)
    private lateinit var dataStore: LomoDataStore

    @MockK(relaxed = true)
    private lateinit var credentialStore: GitCredentialStore

    @MockK(relaxed = true)
    private lateinit var gitSyncEngine: GitSyncEngine

    @MockK(relaxed = true)
    private lateinit var memoSynchronizer: MemoSynchronizer

    @MockK(relaxed = true)
    private lateinit var safGitMirrorBridge: SafGitMirrorBridge

    @MockK(relaxed = true)
    private lateinit var support: GitSyncRepositorySupport

    @MockK(relaxed = true)
    private lateinit var memoMirror: GitSyncMemoMirror

    private lateinit var repository: GitSyncConflictRepositoryImpl

    private val remoteUrl = "https://example.com/org/repo.git"
    private val resolution =
        SyncConflictResolution(
            perFileChoices = mapOf("memo.md" to SyncConflictResolutionChoice.KEEP_LOCAL),
        )
    private val conflictSet =
        SyncConflictSet(
            source = SyncBackendType.GIT,
            files =
                listOf(
                    SyncConflictFile(
                        relativePath = "memo.md",
                        localContent = "local",
                        remoteContent = "remote",
                        isBinary = false,
                    ),
                ),
            timestamp = 123L,
        )

    @Before
    fun setUp() {
        MockKAnnotations.init(this)

        every { runtime.dataStore } returns dataStore
        every { runtime.credentialStore } returns credentialStore
        every { runtime.gitSyncEngine } returns gitSyncEngine
        every { runtime.memoSynchronizer } returns memoSynchronizer
        every { runtime.safGitMirrorBridge } returns safGitMirrorBridge

        every { dataStore.gitRemoteUrl } returns flowOf(remoteUrl)
        every { dataStore.rootDirectory } returns flowOf("/memo")
        every { dataStore.rootUri } returns flowOf(null)
        every { dataStore.imageDirectory } returns flowOf("/images")
        every { dataStore.imageUri } returns flowOf(null)
        every { dataStore.voiceDirectory } returns flowOf("/voice")
        every { dataStore.voiceUri } returns flowOf(null)
        every { credentialStore.getToken() } returns "token"

        coEvery { support.runGitIo<GitSyncResult>(any()) } coAnswers {
            firstArg<suspend () -> GitSyncResult>().invoke()
        }
        coEvery { support.runGitIo<File>(any()) } coAnswers {
            firstArg<suspend () -> File>().invoke()
        }

        repository = GitSyncConflictRepositoryImpl(runtime, support, memoMirror)
    }

    @Test
    fun `resolveConflicts returns repository-url error when remote is blank`() =
        runTest {
            every { dataStore.gitRemoteUrl } returns flowOf("  ")

            val result = repository.resolveConflicts(resolution, conflictSet)

            assertEquals(GitSyncErrorCode.REMOTE_URL_NOT_CONFIGURED, (result as GitSyncResult.Error).code)
            assertEquals(REPOSITORY_URL_NOT_CONFIGURED_MESSAGE, result.message)
            coVerify(exactly = 0) { gitSyncEngine.resolveConflicts(any(), any(), any(), any()) }
            coVerify(exactly = 0) { memoMirror.mirrorMemoFromRepo(any(), any()) }
        }

    @Test
    fun `resolveConflicts returns pat-required error when token is blank`() =
        runTest {
            every { credentialStore.getToken() } returns ""

            val result = repository.resolveConflicts(resolution, conflictSet)

            assertEquals(GitSyncErrorCode.PAT_REQUIRED, (result as GitSyncResult.Error).code)
            coVerify(exactly = 0) { gitSyncEngine.resolveConflicts(any(), any(), any(), any()) }
            coVerify(exactly = 0) { memoSynchronizer.refresh() }
        }

    @Test
    fun `resolveConflicts returns memo-directory error when no repo root can be resolved`() =
        runTest {
            coEvery { support.resolveRootDir() } returns null
            coEvery { support.resolveSafRootUri() } returns null

            val result = repository.resolveConflicts(resolution, conflictSet)

            assertEquals(GitSyncErrorCode.MEMO_DIRECTORY_NOT_CONFIGURED, (result as GitSyncResult.Error).code)
            assertEquals(MEMO_DIRECTORY_NOT_CONFIGURED_MESSAGE, result.message)
            coVerify(exactly = 0) { gitSyncEngine.resolveConflicts(any(), any(), any(), any()) }
            coVerify(exactly = 0) { memoMirror.mirrorMemoFromRepo(any(), any()) }
        }

    @Test
    fun `resolveConflicts mirrors memo and refreshes after successful direct-root resolution`() =
        runTest {
            val directRoot = File("/tmp/lomo-direct-root")
            val repoDir = File("/tmp/lomo-direct-repo")
            coEvery { support.resolveRootDir() } returns directRoot
            every { support.resolveGitRepoDir(directRoot, any()) } returns repoDir
            coEvery {
                gitSyncEngine.resolveConflicts(repoDir, remoteUrl, resolution, conflictSet)
            } returns GitSyncResult.Success("Conflicts resolved")

            val result = repository.resolveConflicts(resolution, conflictSet)

            assertEquals(GitSyncResult.Success("Conflicts resolved"), result)
            coVerify(exactly = 1) { gitSyncEngine.resolveConflicts(repoDir, remoteUrl, resolution, conflictSet) }
            coVerify(exactly = 1) {
                memoMirror.mirrorMemoFromRepo(
                    repoDir,
                    match { layout -> layout.memoFolder == "memo" && !layout.allSameDirectory },
                )
            }
            coVerify(exactly = 1) { memoSynchronizer.refresh() }
        }

    @Test
    fun `resolveConflicts skips mirror and refresh when engine returns error`() =
        runTest {
            val directRoot = File("/tmp/lomo-direct-root")
            val repoDir = File("/tmp/lomo-direct-repo")
            coEvery { support.resolveRootDir() } returns directRoot
            every { support.resolveGitRepoDir(directRoot, any()) } returns repoDir
            coEvery {
                gitSyncEngine.resolveConflicts(repoDir, remoteUrl, resolution, conflictSet)
            } returns GitSyncResult.Error("boom")

            val result = repository.resolveConflicts(resolution, conflictSet)

            assertEquals("boom", (result as GitSyncResult.Error).message)
            coVerify(exactly = 0) { memoMirror.mirrorMemoFromRepo(any(), any()) }
            coVerify(exactly = 0) { memoSynchronizer.refresh() }
        }

    @Test
    fun `resolveConflicts uses saf mirror repo when all directories share the same tree`() =
        runTest {
            val safRootUri = "/tree/shared"
            val repoDir = File("/tmp/lomo-saf-repo")
            every { dataStore.rootDirectory } returns flowOf(null)
            every { dataStore.rootUri } returns flowOf(safRootUri)
            every { dataStore.imageDirectory } returns flowOf(null)
            every { dataStore.imageUri } returns flowOf(safRootUri)
            every { dataStore.voiceDirectory } returns flowOf(null)
            every { dataStore.voiceUri } returns flowOf(safRootUri)
            coEvery { support.resolveRootDir() } returns null
            coEvery { support.resolveSafRootUri() } returns safRootUri
            coEvery { safGitMirrorBridge.mirrorDirectoryFor(safRootUri) } returns repoDir
            coEvery {
                gitSyncEngine.resolveConflicts(repoDir, remoteUrl, resolution, conflictSet)
            } returns GitSyncResult.Success("Resolved from SAF")

            val result = repository.resolveConflicts(resolution, conflictSet)

            assertEquals(GitSyncResult.Success("Resolved from SAF"), result)
            coVerify(exactly = 1) { safGitMirrorBridge.mirrorDirectoryFor(safRootUri) }
            verify(exactly = 0) { support.resolveGitRepoDirForUri(any()) }
            coVerify(exactly = 1) { memoMirror.mirrorMemoFromRepo(repoDir, match { it.allSameDirectory }) }
        }
}
