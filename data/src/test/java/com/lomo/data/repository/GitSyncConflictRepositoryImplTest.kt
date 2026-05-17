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
import java.io.File
import java.nio.file.Files
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe

/*
 * Test Contract:
 * - Unit under test: GitSyncConflictRepositoryImpl
 * - Behavior focus: precondition guarding, repo-directory resolution, and post-resolution memo refresh orchestration.
 * - Observable outcomes: returned GitSyncResult type or code, chosen repo directory source, and memo mirror or refresh side effects.
 * - Red phase: Fails before behavior changes or migration are applied.
 * - Excludes: JGit conflict internals, filesystem mirroring implementation, and UI state mapping.
 */
class GitSyncConflictRepositoryImplTest : DataFunSpec() {
    init {
        beforeTest {
            setUp()
        }

        test("resolveConflicts returns repository-url error when remote is blank") { `resolveConflicts returns repository-url error when remote is blank`() }

        test("resolveConflicts returns pat-required error when token is blank") { `resolveConflicts returns pat-required error when token is blank`() }

        test("resolveConflicts returns memo-directory error when no repo root can be resolved") { `resolveConflicts returns memo-directory error when no repo root can be resolved`() }

        test("resolveConflicts mirrors memo and refreshes after successful direct-root resolution") { `resolveConflicts mirrors memo and refreshes after successful direct-root resolution`() }

        test("resolveConflicts skips mirror and refresh when engine returns error") { `resolveConflicts skips mirror and refresh when engine returns error`() }

        test("resolveConflicts uses saf mirror repo when all directories share the same tree") { `resolveConflicts uses saf mirror repo when all directories share the same tree`() }
    }


    @MockK(relaxed = true)
    private lateinit var context: Context

    @MockK(relaxed = true)
    private lateinit var dataStore: LomoDataStore

    @MockK(relaxed = true)
    private lateinit var credentialStore: GitCredentialStore

    @MockK(relaxed = true)
    private lateinit var gitSyncEngine: GitSyncEngine

    @MockK(relaxed = true)
    private lateinit var refreshEngine: MemoRefreshEngine

    @MockK(relaxed = true)
    private lateinit var mutationHandler: MemoMutationHandler

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
    private lateinit var support: GitSyncRepositorySupport

    @MockK(relaxed = true)
    private lateinit var memoMirror: GitSyncMemoMirror

    private lateinit var memoSynchronizer: MemoSynchronizer
    private lateinit var runtime: GitSyncRepositoryContext
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

    private fun setUp() {
        MockKAnnotations.init(this)
        every { context.filesDir } returns Files.createTempDirectory("git-sync-conflict").toFile()
        memoSynchronizer =
            MemoSynchronizer(
                refreshEngine = refreshEngine,
                mutationHandler = mutationHandler,
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

        every { dataStore.gitRemoteUrl } returns flowOf(remoteUrl)
        every { dataStore.rootDirectory } returns flowOf("/memo")
        every { dataStore.rootUri } returns flowOf(null)
        every { dataStore.imageDirectory } returns flowOf("/images")
        every { dataStore.imageUri } returns flowOf(null)
        every { dataStore.voiceDirectory } returns flowOf("/voice")
        every { dataStore.voiceUri } returns flowOf(null)
        every { credentialStore.getToken() } returns "token"
        coEvery { mutationHandler.nextMemoFileOutbox() } returns null
        coEvery { mutationHandler.hasPendingMemoFileOutbox() } returns false

        coEvery { support.runGitIo<GitSyncResult>(any()) } coAnswers {
            firstArg<suspend () -> GitSyncResult>().invoke()
        }
        coEvery { support.runGitIo<File>(any()) } coAnswers {
            firstArg<suspend () -> File>().invoke()
        }

        repository = GitSyncConflictRepositoryImpl(runtime, support, memoMirror)
    }

    private fun `resolveConflicts returns repository-url error when remote is blank`() =
        runTest {
            every { dataStore.gitRemoteUrl } returns flowOf("  ")

            val result = repository.resolveConflicts(resolution, conflictSet)

            (result as GitSyncResult.Error).code shouldBe GitSyncErrorCode.REMOTE_URL_NOT_CONFIGURED
            result.message shouldBe REPOSITORY_URL_NOT_CONFIGURED_MESSAGE
            coVerify(exactly = 0) { gitSyncEngine.resolveConflicts(any(), any(), any(), any()) }
            coVerify(exactly = 0) { memoMirror.mirrorMemoFromRepo(any(), any()) }
        }

    private fun `resolveConflicts returns pat-required error when token is blank`() =
        runTest {
            every { credentialStore.getToken() } returns ""

            val result = repository.resolveConflicts(resolution, conflictSet)

            (result as GitSyncResult.Error).code shouldBe GitSyncErrorCode.PAT_REQUIRED
            coVerify(exactly = 0) { gitSyncEngine.resolveConflicts(any(), any(), any(), any()) }
            coVerify(exactly = 0) { refreshEngine.refresh(any()) }
        }

    private fun `resolveConflicts returns memo-directory error when no repo root can be resolved`() =
        runTest {
            coEvery { support.resolveRootDir() } returns null
            coEvery { support.resolveSafRootUri() } returns null

            val result = repository.resolveConflicts(resolution, conflictSet)

            (result as GitSyncResult.Error).code shouldBe GitSyncErrorCode.MEMO_DIRECTORY_NOT_CONFIGURED
            result.message shouldBe MEMO_DIRECTORY_NOT_CONFIGURED_MESSAGE
            coVerify(exactly = 0) { gitSyncEngine.resolveConflicts(any(), any(), any(), any()) }
            coVerify(exactly = 0) { memoMirror.mirrorMemoFromRepo(any(), any()) }
        }

    private fun `resolveConflicts mirrors memo and refreshes after successful direct-root resolution`() =
        runTest {
            val directRoot = File("/tmp/lomo-direct-root")
            val repoDir = File("/tmp/lomo-direct-repo")
            coEvery { support.resolveRootDir() } returns directRoot
            every { support.resolveGitRepoDir(directRoot, any()) } returns repoDir
            coEvery {
                gitSyncEngine.resolveConflicts(repoDir, remoteUrl, resolution, conflictSet)
            } returns GitSyncResult.Success("Conflicts resolved")

            val result = repository.resolveConflicts(resolution, conflictSet)

            result shouldBe GitSyncResult.Success("Conflicts resolved")
            coVerify(exactly = 1) { gitSyncEngine.resolveConflicts(repoDir, remoteUrl, resolution, conflictSet) }
            coVerify(exactly = 1) {
                memoMirror.mirrorMemoFromRepo(
                    repoDir,
                    match { layout -> layout.memoFolder == "memo" && !layout.allSameDirectory },
                )
            }
            coVerify(exactly = 1) { refreshEngine.refresh(null) }
        }

    private fun `resolveConflicts skips mirror and refresh when engine returns error`() =
        runTest {
            val directRoot = File("/tmp/lomo-direct-root")
            val repoDir = File("/tmp/lomo-direct-repo")
            coEvery { support.resolveRootDir() } returns directRoot
            every { support.resolveGitRepoDir(directRoot, any()) } returns repoDir
            coEvery {
                gitSyncEngine.resolveConflicts(repoDir, remoteUrl, resolution, conflictSet)
            } returns GitSyncResult.Error("boom")

            val result = repository.resolveConflicts(resolution, conflictSet)

            (result as GitSyncResult.Error).message shouldBe "boom"
            coVerify(exactly = 0) { memoMirror.mirrorMemoFromRepo(any(), any()) }
            coVerify(exactly = 0) { refreshEngine.refresh(any()) }
        }

    private fun `resolveConflicts uses saf mirror repo when all directories share the same tree`() =
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

            result shouldBe GitSyncResult.Success("Resolved from SAF")
            coVerify(exactly = 1) { safGitMirrorBridge.mirrorDirectoryFor(safRootUri) }
            verify(exactly = 0) { support.resolveGitRepoDirForUri(any()) }
            coVerify(exactly = 1) { memoMirror.mirrorMemoFromRepo(repoDir, match { it.allSameDirectory }) }
        }
}
