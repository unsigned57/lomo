package com.lomo.data.repository

import android.content.Context
import com.lomo.data.git.GitCredentialStore
import com.lomo.data.git.GitMediaSyncBridge
import com.lomo.data.git.GitMediaSyncSummary
import com.lomo.data.git.GitSyncEngine
import com.lomo.data.git.GitSyncErrorMessages
import com.lomo.data.git.GitSyncQueryTestCoordinator
import com.lomo.data.git.SafGitMirrorBridge
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.parser.MarkdownParser
import com.lomo.data.source.MarkdownStorageDataSource
import com.lomo.data.sync.SyncDirectoryLayout
import com.lomo.domain.model.GitSyncResult
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncConflictFile
import com.lomo.domain.model.SyncConflictSet
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/*
 * Test Contract:
 * - Unit under test: GitSyncInitAndSyncExecutor, GitSyncMaintenanceExecutor
 * - Behavior focus: precondition gating, direct-vs-SAF path branching, split-layout memo mirroring, and exception mapping after legacy pre-sync capture cleanup.
 * - Observable outcomes: GitSyncResult values, error messages, and side-effect invocation ordering across collaborators.
 * - Red phase: Fails before the fix because Git sync still depends on retired pre-sync capture work in the sync path.
 * - Excludes: git engine internal algorithms, transport/JGit behavior, and UI or DI wiring.
 */
class GitSyncExecutorsTest {
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

    @MockK(relaxed = true)
    private lateinit var memoMirror: GitSyncMemoMirror

    private lateinit var support: GitSyncRepositorySupport
    private lateinit var initExecutor: GitSyncInitAndSyncExecutor
    private lateinit var maintenanceExecutor: GitSyncMaintenanceExecutor

    @Before
    fun setUp() {
        MockKAnnotations.init(this)

        val filesDir = Files.createTempDirectory("lomo-git-sync-executors").toFile()
        every { context.filesDir } returns filesDir
        every { memoSynchronizer.outboxDrainCompleted } returns MutableSharedFlow()
        every { dataStore.gitSyncEnabled } returns flowOf(true)
        every { dataStore.gitRemoteUrl } returns flowOf(REMOTE_URL)
        every { dataStore.rootDirectory } returns flowOf(null)
        every { dataStore.rootUri } returns flowOf(null)
        every { dataStore.imageDirectory } returns flowOf(null)
        every { dataStore.imageUri } returns flowOf(null)
        every { dataStore.voiceDirectory } returns flowOf(null)
        every { dataStore.voiceUri } returns flowOf(null)
        coEvery { credentialStore.getToken() } returns "token"
        coEvery { gitMediaSyncBridge.reconcile(any(), any()) } returns GitMediaSyncSummary()
        coEvery { memoSynchronizer.refresh() } returns Unit
        coEvery { safGitMirrorBridge.pullFromSaf(any(), any()) } returns Unit
        coEvery { safGitMirrorBridge.pushToSaf(any(), any()) } returns Unit
        coEvery { gitSyncEngine.initOrClone(any(), any()) } returns GitSyncResult.Success("init ok")
        coEvery { gitSyncEngine.sync(any(), any()) } returns GitSyncResult.Success("sync ok")
        coEvery { gitSyncEngine.resetRepository(any()) } returns GitSyncResult.Success("reset ok")
        coEvery {
            gitSyncEngine.resetLocalBranchToRemote(
                any(),
                any(),
            )
        } returns GitSyncResult.Success("reset to remote ok")
        coEvery {
            gitSyncEngine.forcePushLocalToRemote(
                any(),
                any(),
            )
        } returns GitSyncResult.Success("force push ok")

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
        initExecutor =
            GitSyncInitAndSyncExecutor(
                runtime = runtime,
                support = support,
                memoMirror = memoMirror,
            )
        maintenanceExecutor =
            GitSyncMaintenanceExecutor(
                runtime = runtime,
                support = support,
                memoMirror = memoMirror,
            )
    }

    @Test
    fun `sync returns not configured when git sync is disabled`() =
        runTest {
            every { dataStore.gitSyncEnabled } returns flowOf(false)

            val result = initExecutor.sync()

            assertEquals(GitSyncResult.NotConfigured, result)
            verify(exactly = 1) { gitSyncEngine.markNotConfigured() }
            coVerify(exactly = 0) { gitSyncEngine.sync(any(), any()) }
        }

    @Test
    fun `initOrClone returns not configured when remote url is blank`() =
        runTest {
            every { dataStore.gitRemoteUrl } returns flowOf(" ")

            val result = initExecutor.initOrClone()

            assertEquals(GitSyncResult.NotConfigured, result)
            verify(exactly = 1) { gitSyncEngine.markNotConfigured() }
            coVerify(exactly = 0) { gitSyncEngine.initOrClone(any(), any()) }
        }

    @Test
    fun `sync returns pat required error when token is missing`() =
        runTest {
            coEvery { credentialStore.getToken() } returns null
            configureDirectLayout(createExistingDirectory("direct-root"))

            val result = initExecutor.sync()

            assertEquals(GitSyncResult.Error(GitSyncErrorMessages.PAT_REQUIRED), result)
            verify(exactly = 1) { gitSyncEngine.markError(GitSyncErrorMessages.PAT_REQUIRED) }
            coVerify(exactly = 0) { gitSyncEngine.sync(any(), any()) }
        }

    @Test
    fun `sync returns direct path required when both direct and SAF roots are absent`() =
        runTest {
            val result = initExecutor.sync()

            assertEquals(GitSyncResult.DirectPathRequired, result)
            verify(exactly = 1) { gitSyncEngine.markError(MEMO_DIRECTORY_NOT_CONFIGURED_MESSAGE) }
            coVerify(exactly = 0) { gitSyncEngine.sync(any(), any()) }
        }

    @Test
    fun `initOrClone in direct mode mirrors to repo and invokes git init once`() =
        runTest {
            val rootDir = createExistingDirectory("direct-mode-root")
            configureDirectLayout(rootDir)

            val result = initExecutor.initOrClone()

            assertEquals(GitSyncResult.Success("init ok"), result)
            coVerifyOrder {
                memoMirror.mirrorMemoToRepo(rootDir, any())
                gitSyncEngine.initOrClone(rootDir, REMOTE_URL)
            }
            coVerify(exactly = 0) { safGitMirrorBridge.pullFromSaf(any(), any()) }
            coVerify(exactly = 0) { safGitMirrorBridge.pushToSaf(any(), any()) }
        }

    @Test
    fun `initOrClone in SAF all-same mode prepares mirror then pushes back on success`() =
        runTest {
            configureSafLayout(allSameDirectory = true)
            val mirrorDir = createExistingDirectory("saf-allsame-mirror")
            coEvery { safGitMirrorBridge.mirrorDirectoryFor(SAF_ROOT_URI) } returns mirrorDir

            val result = initExecutor.initOrClone()

            assertEquals(GitSyncResult.Success("init ok"), result)
            coVerifyOrder {
                safGitMirrorBridge.mirrorDirectoryFor(SAF_ROOT_URI)
                safGitMirrorBridge.pullFromSaf(SAF_ROOT_URI, mirrorDir)
                gitSyncEngine.initOrClone(mirrorDir, REMOTE_URL)
                safGitMirrorBridge.pushToSaf(SAF_ROOT_URI, mirrorDir)
            }
            coVerify(exactly = 0) { memoMirror.mirrorMemoToRepo(any(), any()) }
        }

    @Test
    fun `initOrClone maps SAF mirror preparation failure to GitSyncResult error`() =
        runTest {
            configureSafLayout(allSameDirectory = true)
            val failure = IllegalStateException("mirror broken")
            coEvery { safGitMirrorBridge.mirrorDirectoryFor(SAF_ROOT_URI) } throws failure

            val result = initExecutor.initOrClone()

            assertTrue(result is GitSyncResult.Error)
            result as GitSyncResult.Error
            assertTrue(result.message.contains("mirror broken"))
            assertTrue(result.exception is IllegalStateException)
            assertEquals("mirror broken", result.exception?.message)
            verify(exactly = 1) { gitSyncEngine.markError("mirror broken") }
            coVerify(exactly = 0) { gitSyncEngine.initOrClone(any(), any()) }
        }

    @Test
    fun `sync in SAF all-same mode mirrors from repo pushes SAF and refreshes memos`() =
        runTest {
            configureSafLayout(allSameDirectory = true)
            val mirrorDir = createExistingDirectory("sync-saf-allsame")
            File(mirrorDir, ".git").mkdirs()
            coEvery { safGitMirrorBridge.mirrorDirectoryFor(SAF_ROOT_URI) } returns mirrorDir
            coEvery { gitMediaSyncBridge.reconcile(mirrorDir, any()) } returns GitMediaSyncSummary(repoChanged = false)

            val result = initExecutor.sync()

            assertEquals(GitSyncResult.Success("sync ok"), result)
            coVerifyOrder {
                gitSyncEngine.sync(mirrorDir, REMOTE_URL)
                memoMirror.mirrorMemoFromRepo(mirrorDir, any())
                safGitMirrorBridge.pushToSaf(SAF_ROOT_URI, mirrorDir)
                memoSynchronizer.refresh()
            }
        }

    @Test
    fun `sync reruns git sync after media reconcile reports repo changes`() =
        runTest {
            configureSafLayout(allSameDirectory = true)
            val mirrorDir = createExistingDirectory("sync-media-reconcile")
            File(mirrorDir, ".git").mkdirs()
            coEvery { safGitMirrorBridge.mirrorDirectoryFor(SAF_ROOT_URI) } returns mirrorDir
            coEvery {
                gitMediaSyncBridge.reconcile(mirrorDir, any())
            } returnsMany
                listOf(
                    GitMediaSyncSummary(repoChanged = true),
                    GitMediaSyncSummary(repoChanged = false),
                )

            val result = initExecutor.sync()

            assertEquals(GitSyncResult.Success("sync ok"), result)
            coVerify(exactly = 2) { gitSyncEngine.sync(mirrorDir, REMOTE_URL) }
            coVerify(exactly = 2) { gitMediaSyncBridge.reconcile(mirrorDir, any()) }
            coVerify(exactly = 1) { memoMirror.mirrorMemoFromRepo(mirrorDir, any()) }
            coVerify(exactly = 1) { memoSynchronizer.refresh() }
        }

    @Test
    fun `sync in direct split layout mirrors local memos into repo before git sync`() =
        runTest {
            val rootDir = createExistingDirectory("sync-direct-split-root")
            val imageDir = createExistingDirectory("sync-direct-split-images")
            val voiceDir = createExistingDirectory("sync-direct-split-voice")
            configureDirectSplitLayout(
                rootDir = rootDir,
                imageDir = imageDir,
                voiceDir = voiceDir,
            )
            val repoDir = support.resolveGitRepoDir(rootDir, SyncDirectoryLayout.resolve(dataStore))
            File(repoDir, ".git").mkdirs()

            val result = initExecutor.sync()

            assertEquals(GitSyncResult.Success("sync ok"), result)
            coVerifyOrder {
                memoMirror.mirrorMemoToRepo(repoDir, any())
                gitSyncEngine.sync(repoDir, REMOTE_URL)
                memoMirror.mirrorMemoFromRepo(repoDir, any())
                memoSynchronizer.refresh()
            }
        }

    @Test
    fun `sync returns conflict result without mirror push or refresh`() =
        runTest {
            configureDirectLayout(createExistingDirectory("sync-conflict-root"))
            val conflict =
                GitSyncResult.Conflict(
                    message = "rebase STOPPED",
                    conflicts =
                        SyncConflictSet(
                            source = SyncBackendType.GIT,
                            files =
                                listOf(
                                    SyncConflictFile(
                                        relativePath = "memos/2026_03_24.md",
                                        localContent = "local",
                                        remoteContent = "remote",
                                        isBinary = false,
                                    ),
                                ),
                            timestamp = 123L,
                        ),
                )
            coEvery { gitSyncEngine.sync(any(), REMOTE_URL) } returns conflict

            val result = initExecutor.sync()

            assertEquals(conflict, result)
            coVerify(exactly = 0) { memoMirror.mirrorMemoFromRepo(any(), any()) }
            coVerify(exactly = 0) { safGitMirrorBridge.pushToSaf(any(), any()) }
            coVerify(exactly = 0) { memoSynchronizer.refresh() }
        }

    @Test
    fun `resetRepository returns not configured error when no root is available`() =
        runTest {
            val result = maintenanceExecutor.resetRepository()

            assertEquals(GitSyncResult.Error(MEMO_DIRECTORY_NOT_CONFIGURED_MESSAGE), result)
            coVerify(exactly = 0) { gitSyncEngine.resetRepository(any()) }
        }

    @Test
    fun `resetRepository wraps runtime exception into reset failed result`() =
        runTest {
            configureSafLayout(allSameDirectory = true)
            val mirrorDir = createExistingDirectory("reset-repo-mirror")
            val failure = IllegalStateException("io fail")
            coEvery { safGitMirrorBridge.mirrorDirectoryFor(SAF_ROOT_URI) } returns mirrorDir
            coEvery { gitSyncEngine.resetRepository(mirrorDir) } throws failure

            val result = maintenanceExecutor.resetRepository()

            assertTrue(result is GitSyncResult.Error)
            result as GitSyncResult.Error
            assertEquals("Reset failed: io fail", result.message)
            assertTrue(result.exception is IllegalStateException)
            assertEquals("io fail", result.exception?.message)
        }

    @Test
    fun `resetLocalBranchToRemote returns repository-url error before token lookup`() =
        runTest {
            every { dataStore.gitRemoteUrl } returns flowOf("")

            val result = maintenanceExecutor.resetLocalBranchToRemote()

            assertEquals(GitSyncResult.Error(REPOSITORY_URL_NOT_CONFIGURED_MESSAGE), result)
            coVerify(exactly = 1) { credentialStore.getToken() }
            coVerify(exactly = 0) { gitSyncEngine.resetLocalBranchToRemote(any(), any()) }
        }

    @Test
    fun `resetLocalBranchToRemote in SAF split mode mirrors from repo on success`() =
        runTest {
            configureSafLayout(allSameDirectory = false)

            val result = maintenanceExecutor.resetLocalBranchToRemote()

            assertEquals(GitSyncResult.Success("reset to remote ok"), result)
            coVerify(exactly = 1) { gitSyncEngine.resetLocalBranchToRemote(any(), REMOTE_URL) }
            coVerify(exactly = 1) { memoMirror.mirrorMemoFromRepo(any(), any()) }
            coVerify(exactly = 0) { safGitMirrorBridge.pullFromSaf(any(), any()) }
            coVerify(exactly = 0) { safGitMirrorBridge.pushToSaf(any(), any()) }
        }

    @Test
    fun `forcePush in direct mode mirrors local memos before git force push`() =
        runTest {
            val rootDir = createExistingDirectory("force-push-direct-root")
            configureDirectLayout(rootDir)

            val result = maintenanceExecutor.forcePushLocalToRemote()

            assertEquals(GitSyncResult.Success("force push ok"), result)
            coVerifyOrder {
                memoMirror.mirrorMemoToRepo(rootDir, any())
                gitSyncEngine.forcePushLocalToRemote(rootDir, REMOTE_URL)
            }
        }

    @Test
    fun `forcePush in SAF all-same mode maps push-back failure and marks error`() =
        runTest {
            configureSafLayout(allSameDirectory = true)
            val mirrorDir = createExistingDirectory("force-push-saf-allsame")
            val failure = IllegalStateException("push failed")
            coEvery { safGitMirrorBridge.mirrorDirectoryFor(SAF_ROOT_URI) } returns mirrorDir
            coEvery { safGitMirrorBridge.pushToSaf(SAF_ROOT_URI, mirrorDir) } throws failure

            val result = maintenanceExecutor.forcePushLocalToRemote()

            assertTrue(result is GitSyncResult.Error)
            result as GitSyncResult.Error
            assertTrue(result.message.contains("push failed"))
            assertTrue(result.exception is IllegalStateException)
            assertEquals("push failed", result.exception?.message)
            verify(exactly = 1) { gitSyncEngine.markError("push failed") }
            coVerify(exactly = 0) { memoMirror.mirrorMemoToRepo(any(), any()) }
        }

    @Test
    fun `initOrClone in SAF split mode uses internal repo path and does not push SAF`() =
        runTest {
            configureSafLayout(allSameDirectory = false)
            val repoDirSlot = slot<File>()

            val result = initExecutor.initOrClone()

            assertEquals(GitSyncResult.Success("init ok"), result)
            coVerify(exactly = 1) { gitSyncEngine.initOrClone(capture(repoDirSlot), REMOTE_URL) }
            assertTrue(repoDirSlot.captured.absolutePath.contains("git_sync_repo"))
            coVerify(exactly = 0) { safGitMirrorBridge.pullFromSaf(any(), any()) }
            coVerify(exactly = 0) { safGitMirrorBridge.pushToSaf(any(), any()) }
        }

    private fun configureDirectLayout(rootDir: File) {
        every { dataStore.rootDirectory } returns flowOf(rootDir.absolutePath)
        every { dataStore.rootUri } returns flowOf(null)
        every { dataStore.imageDirectory } returns flowOf(rootDir.absolutePath)
        every { dataStore.imageUri } returns flowOf(null)
        every { dataStore.voiceDirectory } returns flowOf(rootDir.absolutePath)
        every { dataStore.voiceUri } returns flowOf(null)
    }

    private fun configureDirectSplitLayout(
        rootDir: File,
        imageDir: File,
        voiceDir: File,
    ) {
        every { dataStore.rootDirectory } returns flowOf(rootDir.absolutePath)
        every { dataStore.rootUri } returns flowOf(null)
        every { dataStore.imageDirectory } returns flowOf(imageDir.absolutePath)
        every { dataStore.imageUri } returns flowOf(null)
        every { dataStore.voiceDirectory } returns flowOf(voiceDir.absolutePath)
        every { dataStore.voiceUri } returns flowOf(null)
    }

    private fun configureSafLayout(allSameDirectory: Boolean) {
        every { dataStore.rootDirectory } returns flowOf(null)
        every { dataStore.imageDirectory } returns flowOf(null)
        every { dataStore.voiceDirectory } returns flowOf(null)

        if (allSameDirectory) {
            every { dataStore.rootUri } returns flowOf(SAF_ROOT_URI)
            every { dataStore.imageUri } returns flowOf(SAF_ROOT_URI)
            every { dataStore.voiceUri } returns flowOf(SAF_ROOT_URI)
        } else {
            every { dataStore.rootUri } returns flowOf(SAF_ROOT_URI)
            every { dataStore.imageUri } returns flowOf(SAF_IMAGE_URI)
            every { dataStore.voiceUri } returns flowOf(SAF_VOICE_URI)
        }
    }

    private fun createExistingDirectory(prefix: String): File = Files.createTempDirectory(prefix).toFile()

    private companion object {
        private const val REMOTE_URL = "https://example.com/lomo.git"
        private const val SAF_ROOT_URI = "/virtual-saf/root"
        private const val SAF_IMAGE_URI = "/virtual-saf/images"
        private const val SAF_VOICE_URI = "/virtual-saf/voice"
    }
}
