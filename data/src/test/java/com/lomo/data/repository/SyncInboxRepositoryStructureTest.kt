package com.lomo.data.repository

import android.content.Context
import com.lomo.data.source.MarkdownStorageDataSource
import com.lomo.data.source.MemoDirectoryType
import com.lomo.data.source.StorageRootType
import com.lomo.data.source.WorkspaceConfigSource
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.UnifiedSyncOperation
import com.lomo.domain.model.UnifiedSyncResult
import com.lomo.domain.repository.PreferencesRepository
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
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
 * - Unit under test: SyncInboxRepositoryImpl
 * - Behavior focus: ensuring the required sync-inbox directory structure and importing memo markdown from the dedicated memo subdirectory.
 * - Observable outcomes: created memo/images/voice directories, sync success result, imported memo save call, and removal of processed inbox files.
 * - Red phase: Fails before the fix because selecting a sync inbox does not create the required subdirectories and the importer only scans root-level markdown files.
 * - Excludes: SAF tree creation mechanics, media attachment import rules already covered elsewhere, and conflict-resolution heuristics.
 */
class SyncInboxRepositoryStructureTest {
    @MockK(relaxed = true)
    private lateinit var context: Context

    @MockK(relaxed = true)
    private lateinit var preferencesRepository: PreferencesRepository

    @MockK(relaxed = true)
    private lateinit var workspaceConfigSource: WorkspaceConfigSource

    @MockK(relaxed = true)
    private lateinit var markdownStorageDataSource: MarkdownStorageDataSource

    @MockK(relaxed = true)
    private lateinit var workspaceMediaAccess: WorkspaceMediaAccess

    @MockK(relaxed = true)
    private lateinit var refreshEngine: MemoRefreshEngine

    @MockK(relaxed = true)
    private lateinit var mutationHandler: MemoMutationHandler

    private lateinit var repository: SyncInboxRepositoryImpl

    @Before
    fun setUp() {
        MockKAnnotations.init(this)
        every { preferencesRepository.isSyncInboxEnabled() } returns flowOf(true)
        coEvery { mutationHandler.nextMemoFileOutbox() } returns null
        coEvery { mutationHandler.hasPendingMemoFileOutbox() } returns false

        repository =
            SyncInboxRepositoryImpl(
                context = context,
                preferencesRepository = preferencesRepository,
                workspaceConfigSource = workspaceConfigSource,
                markdownStorageDataSource = markdownStorageDataSource,
                workspaceMediaAccess = workspaceMediaAccess,
                memoSynchronizer =
                    MemoSynchronizer(
                        refreshEngine = refreshEngine,
                        mutationHandler = mutationHandler,
                        startOutboxCoordinator = false,
                    ),
                pendingConflictStore = InMemoryPendingSyncConflictStore(),
            )
    }

    @Test
    fun `ensureDirectoryStructure creates memo images and voice directories in a direct root`() =
        runTest {
            val inboxRoot = Files.createTempDirectory("sync-inbox-structure").toFile()
            every { workspaceConfigSource.getRootFlow(StorageRootType.SYNC_INBOX) } returns flowOf(inboxRoot.absolutePath)

            repository.ensureDirectoryStructure()

            assertTrue(File(inboxRoot, "memo").isDirectory)
            assertTrue(File(inboxRoot, "images").isDirectory)
            assertTrue(File(inboxRoot, "voice").isDirectory)
        }

    @Test
    fun `sync imports markdown files from the memo subdirectory`() =
        runTest {
            val inboxRoot = Files.createTempDirectory("sync-inbox-memo-dir").toFile()
            val memoDirectory = File(inboxRoot, "memo").apply { mkdirs() }
            File(inboxRoot, "images").mkdirs()
            File(inboxRoot, "voice").mkdirs()
            val inboxFile = File(memoDirectory, "2026_04_16.md").apply { writeText("memo from sync inbox") }

            every { workspaceConfigSource.getRootFlow(StorageRootType.SYNC_INBOX) } returns flowOf(inboxRoot.absolutePath)
            coEvery { markdownStorageDataSource.readFileIn(MemoDirectoryType.MAIN, "2026_04_16.md") } returns null

            val result = repository.sync(UnifiedSyncOperation.PROCESS_PENDING_CHANGES)

            assertEquals(
                UnifiedSyncResult.Success(
                    provider = SyncBackendType.INBOX,
                    message = "Sync inbox processed",
                ),
                result,
            )
            coVerify(exactly = 1) {
                markdownStorageDataSource.saveFileIn(
                    directory = MemoDirectoryType.MAIN,
                    filename = "2026_04_16.md",
                    content = "memo from sync inbox",
                    append = false,
                    uri = null,
                )
            }
            coVerify(exactly = 1) { refreshEngine.refreshImportedSync("2026_04_16.md") }
            assertTrue(!inboxFile.exists())
        }

    @Test
    fun `sync imports inbox attachments once while rewriting memo references`() =
        runTest {
            val inboxRoot = Files.createTempDirectory("sync-inbox-attachments").toFile()
            val memoDirectory = File(inboxRoot, "memo").apply { mkdirs() }
            val imagesDirectory = File(inboxRoot, "images").apply { mkdirs() }
            File(inboxRoot, "voice").mkdirs()
            val imageBytes = "cover".toByteArray()
            File(imagesDirectory, "cover.png").writeBytes(imageBytes)
            val inboxFile =
                File(memoDirectory, "2026_04_18.md").apply {
                    writeText("memo with image ![cover](images/cover.png)")
                }

            every { workspaceConfigSource.getRootFlow(StorageRootType.SYNC_INBOX) } returns flowOf(inboxRoot.absolutePath)
            coEvery { markdownStorageDataSource.readFileIn(MemoDirectoryType.MAIN, "2026_04_18.md") } returns null

            val result = repository.sync(UnifiedSyncOperation.PROCESS_PENDING_CHANGES)

            assertEquals(
                UnifiedSyncResult.Success(
                    provider = SyncBackendType.INBOX,
                    message = "Sync inbox processed",
                ),
                result,
            )
            coVerify(exactly = 1) {
                workspaceMediaAccess.writeFile(
                    category = WorkspaceMediaCategory.IMAGE,
                    filename = match { it.startsWith("cover_") && it.endsWith(".png") },
                    bytes = match { it.contentEquals(imageBytes) },
                )
            }
            coVerify(exactly = 1) {
                markdownStorageDataSource.saveFileIn(
                    directory = MemoDirectoryType.MAIN,
                    filename = "2026_04_18.md",
                    content = match { it.contains("![cover](cover_") && it.endsWith(".png)") },
                    append = false,
                    uri = null,
                )
            }
            assertTrue(!inboxFile.exists())
            assertTrue(!File(imagesDirectory, "cover.png").exists())
        }

    @Test
    fun `sync imports bare image filename references from the images directory`() =
        runTest {
            val inboxRoot = Files.createTempDirectory("sync-inbox-image-bare").toFile()
            val memoDirectory = File(inboxRoot, "memo").apply { mkdirs() }
            val imagesDirectory = File(inboxRoot, "images").apply { mkdirs() }
            File(inboxRoot, "voice").mkdirs()
            val imageBytes = "poster".toByteArray()
            File(imagesDirectory, "poster.png").writeBytes(imageBytes)
            val inboxFile =
                File(memoDirectory, "2026_04_22.md").apply {
                    writeText("memo with image ![poster](poster.png)")
                }

            every { workspaceConfigSource.getRootFlow(StorageRootType.SYNC_INBOX) } returns flowOf(inboxRoot.absolutePath)
            coEvery { markdownStorageDataSource.readFileIn(MemoDirectoryType.MAIN, "2026_04_22.md") } returns null

            val result = repository.sync(UnifiedSyncOperation.PROCESS_PENDING_CHANGES)

            assertEquals(
                UnifiedSyncResult.Success(
                    provider = SyncBackendType.INBOX,
                    message = "Sync inbox processed",
                ),
                result,
            )
            coVerify(exactly = 1) {
                workspaceMediaAccess.writeFile(
                    category = WorkspaceMediaCategory.IMAGE,
                    filename = match { it.startsWith("poster_") && it.endsWith(".png") },
                    bytes = match { it.contentEquals(imageBytes) },
                )
            }
            coVerify(exactly = 1) {
                markdownStorageDataSource.saveFileIn(
                    directory = MemoDirectoryType.MAIN,
                    filename = "2026_04_22.md",
                    content = match { it.contains("![poster](poster_") && it.endsWith(".png)") },
                    append = false,
                    uri = null,
                )
            }
            assertTrue(!inboxFile.exists())
            assertTrue(!File(imagesDirectory, "poster.png").exists())
        }

    @Test
    fun `sync imports bare voice filename references from the voice directory`() =
        runTest {
            val inboxRoot = Files.createTempDirectory("sync-inbox-voice-bare").toFile()
            val memoDirectory = File(inboxRoot, "memo").apply { mkdirs() }
            File(inboxRoot, "images").mkdirs()
            val voiceDirectory = File(inboxRoot, "voice").apply { mkdirs() }
            val voiceBytes = "voice".toByteArray()
            File(voiceDirectory, "voice_20260416.m4a").writeBytes(voiceBytes)
            val inboxFile =
                File(memoDirectory, "2026_04_19.md").apply {
                    writeText("memo with voice ![voice](voice_20260416.m4a)")
                }

            every { workspaceConfigSource.getRootFlow(StorageRootType.SYNC_INBOX) } returns flowOf(inboxRoot.absolutePath)
            coEvery { markdownStorageDataSource.readFileIn(MemoDirectoryType.MAIN, "2026_04_19.md") } returns null

            val result = repository.sync(UnifiedSyncOperation.PROCESS_PENDING_CHANGES)

            assertEquals(
                UnifiedSyncResult.Success(
                    provider = SyncBackendType.INBOX,
                    message = "Sync inbox processed",
                ),
                result,
            )
            coVerify(exactly = 1) {
                workspaceMediaAccess.writeFile(
                    category = WorkspaceMediaCategory.VOICE,
                    filename = match { it.startsWith("voice_20260416_") && it.endsWith(".m4a") },
                    bytes = match { it.contentEquals(voiceBytes) },
                )
            }
            coVerify(exactly = 1) {
                markdownStorageDataSource.saveFileIn(
                    directory = MemoDirectoryType.MAIN,
                    filename = "2026_04_19.md",
                    content = match { it.contains("![voice](voice_20260416_") && it.endsWith(".m4a)") },
                    append = false,
                    uri = null,
                )
            }
            assertTrue(!inboxFile.exists())
            assertTrue(!File(voiceDirectory, "voice_20260416.m4a").exists())
        }

    @Test
    fun `sync imports bare voice filename references from the recording directory`() =
        runTest {
            val inboxRoot = Files.createTempDirectory("sync-inbox-recording-bare").toFile()
            val memoDirectory = File(inboxRoot, "memo").apply { mkdirs() }
            File(inboxRoot, "images").mkdirs()
            File(inboxRoot, "voice").mkdirs()
            val recordingDirectory = File(inboxRoot, "recording").apply { mkdirs() }
            val voiceBytes = "recording".toByteArray()
            File(recordingDirectory, "voice_20260420.m4a").writeBytes(voiceBytes)
            File(memoDirectory, "2026_04_20.md").writeText("memo with voice ![voice](voice_20260420.m4a)")

            every { workspaceConfigSource.getRootFlow(StorageRootType.SYNC_INBOX) } returns flowOf(inboxRoot.absolutePath)
            coEvery { markdownStorageDataSource.readFileIn(MemoDirectoryType.MAIN, "2026_04_20.md") } returns null

            val result = repository.sync(UnifiedSyncOperation.PROCESS_PENDING_CHANGES)

            assertEquals(
                UnifiedSyncResult.Success(
                    provider = SyncBackendType.INBOX,
                    message = "Sync inbox processed",
                ),
                result,
            )
            coVerify(exactly = 1) {
                workspaceMediaAccess.writeFile(
                    category = WorkspaceMediaCategory.VOICE,
                    filename = match { it.startsWith("voice_20260420_") && it.endsWith(".m4a") },
                    bytes = match { it.contentEquals(voiceBytes) },
                )
            }
            assertTrue(!File(recordingDirectory, "voice_20260420.m4a").exists())
        }

    @Test
    fun `sync returns an error and preserves source files when a referenced attachment is missing`() =
        runTest {
            val inboxRoot = Files.createTempDirectory("sync-inbox-missing-attachment").toFile()
            val memoDirectory = File(inboxRoot, "memo").apply { mkdirs() }
            File(inboxRoot, "images").mkdirs()
            File(inboxRoot, "voice").mkdirs()
            val inboxFile =
                File(memoDirectory, "2026_04_21.md").apply {
                    writeText("memo with missing image ![cover](cover.png)")
                }

            every { workspaceConfigSource.getRootFlow(StorageRootType.SYNC_INBOX) } returns flowOf(inboxRoot.absolutePath)
            coEvery { markdownStorageDataSource.readFileIn(MemoDirectoryType.MAIN, "2026_04_21.md") } returns null

            val result = repository.sync(UnifiedSyncOperation.PROCESS_PENDING_CHANGES)

            assertTrue(result is UnifiedSyncResult.Error)
            assertEquals(
                "Referenced sync inbox attachment not found: cover.png",
                (result as UnifiedSyncResult.Error).error.message,
            )
            coVerify(exactly = 0) {
                markdownStorageDataSource.saveFileIn(any(), any(), any(), any(), any())
            }
            coVerify(exactly = 0) { workspaceMediaAccess.writeFile(any(), any(), any()) }
            assertTrue(inboxFile.exists())
        }
}
