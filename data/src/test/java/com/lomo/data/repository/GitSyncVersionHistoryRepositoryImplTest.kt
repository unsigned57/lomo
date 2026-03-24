package com.lomo.data.repository

import android.content.Context
import com.lomo.data.git.GitCredentialStore
import com.lomo.data.git.GitFileHistoryEntry
import com.lomo.data.git.GitMediaSyncBridge
import com.lomo.data.git.GitSyncEngine
import com.lomo.data.git.GitSyncQueryTestCoordinator
import com.lomo.data.git.SafGitMirrorBridge
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.parser.MarkdownParser
import com.lomo.data.source.MarkdownStorageDataSource
import com.lomo.domain.model.Memo
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/*
 * Test Contract:
 * - Unit under test: GitSyncVersionHistoryRepositoryImpl
 * - Behavior focus: root-resolution branches (direct vs SAF), version de-duplication, and current-version marking.
 * - Observable outcomes: returned MemoVersion list content/order/current flags, and branch-specific collaborator usage.
 * - Excludes: markdown parsing internals, git query engine internals, and SAF bridge filesystem implementation.
 */
class GitSyncVersionHistoryRepositoryImplTest {
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
    private lateinit var repository: GitSyncVersionHistoryRepositoryImpl

    @Before
    fun setUp() {
        MockKAnnotations.init(this)
        every { context.filesDir } returns Files.createTempDirectory("lomo-version-history-test").toFile()
        every { memoSynchronizer.outboxDrainCompleted } returns MutableSharedFlow()

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
        repository = GitSyncVersionHistoryRepositoryImpl(runtime, support)
    }

    @Test
    fun `direct-root history de-duplicates by memo content and marks current by current-file content`() =
        runTest {
            val dateKey = "2026_03_24"
            val targetTs = 1_700_000_000_000L
            val rootDir = Files.createTempDirectory("lomo-history-direct").toFile()
            File(rootDir, "$dateKey.md").writeText("current")
            stubLayout(
                rootDirectory = rootDir.absolutePath,
                rootUri = null,
                imageDirectory = rootDir.absolutePath,
                imageUri = null,
                voiceDirectory = rootDir.absolutePath,
                voiceUri = null,
            )

            every {
                gitSyncQueryCoordinator.getFileHistory(rootDir, "$dateKey.md")
            } returns
                listOf(
                    GitFileHistoryEntry("c1", 3_000L, "newest", "h1"),
                    GitFileHistoryEntry("c2", 2_000L, "older", "h2"),
                    GitFileHistoryEntry("c3", 1_000L, "duplicate-content", "h3"),
                )
            every { markdownParser.parseContent("h1", dateKey, any()) } returns listOf(memo(targetTs, "A", dateKey))
            every { markdownParser.parseContent("h2", dateKey, any()) } returns listOf(memo(targetTs, "B", dateKey))
            every { markdownParser.parseContent("h3", dateKey, any()) } returns listOf(memo(targetTs, "B", dateKey))
            every { markdownParser.parseFile(File(rootDir, "$dateKey.md")) } returns listOf(memo(targetTs, "B", dateKey))

            val result = repository.getMemoVersionHistory(dateKey = dateKey, memoTimestamp = targetTs)

            assertEquals(listOf("c1", "c2"), result.map { it.commitHash })
            assertEquals(listOf("A", "B"), result.map { it.memoContent })
            assertEquals(listOf(false, true), result.map { it.isCurrent })
        }

    @Test
    fun `when current file is missing first distinct version is marked current`() =
        runTest {
            val dateKey = "2026_03_24"
            val targetTs = 1_700_000_000_000L
            val rootDir = Files.createTempDirectory("lomo-history-missing-current").toFile()
            stubLayout(
                rootDirectory = rootDir.absolutePath,
                rootUri = null,
                imageDirectory = rootDir.absolutePath,
                imageUri = null,
                voiceDirectory = rootDir.absolutePath,
                voiceUri = null,
            )

            every {
                gitSyncQueryCoordinator.getFileHistory(rootDir, "$dateKey.md")
            } returns
                listOf(
                    GitFileHistoryEntry("c1", 3_000L, "newest", "h1"),
                    GitFileHistoryEntry("c2", 2_000L, "older", "h2"),
                )
            every { markdownParser.parseContent("h1", dateKey, any()) } returns listOf(memo(targetTs, "A", dateKey))
            every { markdownParser.parseContent("h2", dateKey, any()) } returns listOf(memo(targetTs, "B", dateKey))

            val result = repository.getMemoVersionHistory(dateKey = dateKey, memoTimestamp = targetTs)

            assertEquals(2, result.size)
            assertTrue(result.first().isCurrent)
            assertTrue(!result.last().isCurrent)
            verify(exactly = 0) { markdownParser.parseFile(any()) }
        }

    @Test
    fun `saf split-directory mode resolves hashed repo dir without SAF mirror pull`() =
        runTest {
            val dateKey = "2026_03_24"
            val targetTs = 1_700_000_000_000L
            val safRootUri = "/saf/root"
            stubLayout(
                rootDirectory = null,
                rootUri = safRootUri,
                imageDirectory = null,
                imageUri = "/saf/images",
                voiceDirectory = null,
                voiceUri = "/saf/voice",
            )

            val expectedRepoDir = support.resolveGitRepoDirForUri(safRootUri)
            every {
                gitSyncQueryCoordinator.getFileHistory(expectedRepoDir, any())
            } returns emptyList()

            val result = repository.getMemoVersionHistory(dateKey = dateKey, memoTimestamp = targetTs)

            assertTrue(result.isEmpty())
            verify(exactly = 1) { gitSyncQueryCoordinator.getFileHistory(expectedRepoDir, any()) }
            coVerify(exactly = 0) { safGitMirrorBridge.mirrorDirectoryFor(any()) }
            coVerify(exactly = 0) { safGitMirrorBridge.pullFromSaf(any(), any()) }
        }

    @Test
    fun `saf all-same-directory mode returns empty when mirror preparation fails`() =
        runTest {
            val dateKey = "2026_03_24"
            val targetTs = 1_700_000_000_000L
            val safRootUri = "/saf/root"
            stubLayout(
                rootDirectory = null,
                rootUri = safRootUri,
                imageDirectory = null,
                imageUri = safRootUri,
                voiceDirectory = null,
                voiceUri = safRootUri,
            )
            coEvery { safGitMirrorBridge.mirrorDirectoryFor(safRootUri) } throws IllegalStateException("mirror failed")

            val result = repository.getMemoVersionHistory(dateKey = dateKey, memoTimestamp = targetTs)

            assertTrue(result.isEmpty())
            coVerify(exactly = 1) { safGitMirrorBridge.mirrorDirectoryFor(safRootUri) }
            verify(exactly = 0) { gitSyncQueryCoordinator.getFileHistory(any(), any()) }
        }

    @Test
    fun `returns empty when neither direct root nor saf root is configured`() =
        runTest {
            stubLayout(
                rootDirectory = null,
                rootUri = null,
                imageDirectory = null,
                imageUri = null,
                voiceDirectory = null,
                voiceUri = null,
            )

            val result = repository.getMemoVersionHistory(dateKey = "2026_03_24", memoTimestamp = 1_700_000_000_000L)

            assertTrue(result.isEmpty())
            verify(exactly = 0) { gitSyncQueryCoordinator.getFileHistory(any(), any()) }
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

    private fun memo(
        timestamp: Long,
        content: String,
        dateKey: String,
    ): Memo =
        Memo(
            id = "memo-$content",
            timestamp = timestamp,
            content = content,
            rawContent = content,
            dateKey = dateKey,
        )
}
