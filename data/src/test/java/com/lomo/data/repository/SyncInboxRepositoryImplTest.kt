package com.lomo.data.repository

import android.content.Context
import com.lomo.data.source.FileMetadata
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/*
 * Test Contract:
 * - Unit under test: SyncInboxRepositoryImpl
 * - Behavior focus: inbox conflict detection, safe auto-merge before surfacing a conflict, and pending-conflict persistence.
 * - Observable outcomes: returned UnifiedSyncResult, saved merged memo content, refresh invocation, and pending-conflict store contents.
 * - Red phase: Fails before the fix when same-name inbox/local memos contain disjoint multi-line content because the inbox path raises a conflict instead of auto-merging safely.
 * - Excludes: Compose dialog rendering, SAF transport behavior, and attachment import edge cases unrelated to conflict decisions.
 */
class SyncInboxRepositoryImplTest {
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

    private lateinit var memoSynchronizer: MemoSynchronizer
    private lateinit var pendingConflictStore: PendingSyncConflictStore
    private lateinit var repository: SyncInboxRepositoryImpl

    @Before
    fun setUp() {
        MockKAnnotations.init(this)
        every { preferencesRepository.isSyncInboxEnabled() } returns flowOf(true)
        coEvery { mutationHandler.nextMemoFileOutbox() } returns null
        coEvery { mutationHandler.hasPendingMemoFileOutbox() } returns false

        memoSynchronizer =
            MemoSynchronizer(
                refreshEngine = refreshEngine,
                mutationHandler = mutationHandler,
                startOutboxCoordinator = false,
            )
        pendingConflictStore = InMemoryPendingSyncConflictStore()
        repository =
            SyncInboxRepositoryImpl(
                context = context,
                preferencesRepository = preferencesRepository,
                workspaceConfigSource = workspaceConfigSource,
                markdownStorageDataSource = markdownStorageDataSource,
                workspaceMediaAccess = workspaceMediaAccess,
                memoSynchronizer = memoSynchronizer,
                pendingConflictStore = pendingConflictStore,
            )
    }

    @Test
    fun `sync auto merges disjoint inbox memo content before surfacing a conflict`() =
        runTest {
            val inboxRoot = Files.createTempDirectory("sync-inbox").toFile()
            val inboxFile = File(inboxRoot, "2026_04_15.md")
            inboxFile.writeText("remote idea\nremote detail")

            every { workspaceConfigSource.getRootFlow(StorageRootType.SYNC_INBOX) } returns flowOf(inboxRoot.absolutePath)
            coEvery {
                markdownStorageDataSource.readFileIn(MemoDirectoryType.MAIN, "2026_04_15.md")
            } returns "local idea\nlocal detail"
            coEvery {
                markdownStorageDataSource.getFileMetadataIn(MemoDirectoryType.MAIN, "2026_04_15.md")
            } returns FileMetadata(filename = "2026_04_15.md", lastModified = 0L)

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
                    filename = "2026_04_15.md",
                    content = "local idea\nlocal detail\n\nremote idea\nremote detail",
                    append = false,
                    uri = null,
                )
            }
            coVerify(exactly = 1) { refreshEngine.refreshImportedSync("2026_04_15.md") }
            assertTrue("Inbox source file should be deleted after import", !inboxFile.exists())
            assertNull(pendingConflictStore.read(SyncBackendType.INBOX))
        }

    @Test
    fun `sync batches all unresolved inbox conflicts from one scan`() =
        runTest {
            val inboxRoot = Files.createTempDirectory("sync-inbox-conflicts").toFile()
            val firstInboxFile = File(inboxRoot, "2026_04_15.md").apply { writeText("start\nremote first\nend") }
            val secondInboxFile = File(inboxRoot, "2026_04_16.md").apply { writeText("start\nremote second\nend") }

            every { workspaceConfigSource.getRootFlow(StorageRootType.SYNC_INBOX) } returns flowOf(inboxRoot.absolutePath)
            coEvery {
                markdownStorageDataSource.readFileIn(MemoDirectoryType.MAIN, "2026_04_15.md")
            } returns "start\nlocal first\nend"
            coEvery {
                markdownStorageDataSource.readFileIn(MemoDirectoryType.MAIN, "2026_04_16.md")
            } returns "start\nlocal second\nend"
            coEvery {
                markdownStorageDataSource.getFileMetadataIn(MemoDirectoryType.MAIN, any())
            } returns null

            val result = repository.sync(UnifiedSyncOperation.PROCESS_PENDING_CHANGES)

            assertTrue(result is UnifiedSyncResult.Conflict)
            val conflicts = (result as UnifiedSyncResult.Conflict).conflicts
            assertEquals(SyncBackendType.INBOX, conflicts.source)
            assertEquals(
                listOf("inbox/2026_04_15.md", "inbox/2026_04_16.md"),
                conflicts.files.map { it.relativePath },
            )
            assertEquals(
                conflicts,
                pendingConflictStore.read(SyncBackendType.INBOX),
            )
            assertTrue(firstInboxFile.exists())
            assertTrue(secondInboxFile.exists())
            coVerify(exactly = 0) {
                markdownStorageDataSource.saveFileIn(any(), any(), any(), any(), any())
            }
            coVerify(exactly = 0) { refreshEngine.refreshImportedSync(any()) }
        }

    @Test
    fun `sync reprocesses pending inbox conflicts that are now safe to auto merge`() =
        runTest {
            val inboxRoot = Files.createTempDirectory("sync-inbox-pending").toFile()
            val inboxFile = File(inboxRoot, "2026_04_17.md").apply { writeText("remote-only note") }
            every { workspaceConfigSource.getRootFlow(StorageRootType.SYNC_INBOX) } returns flowOf(inboxRoot.absolutePath)
            coEvery {
                markdownStorageDataSource.readFileIn(MemoDirectoryType.MAIN, "2026_04_17.md")
            } returns "local-only note"
            coEvery {
                markdownStorageDataSource.getFileMetadataIn(MemoDirectoryType.MAIN, "2026_04_17.md")
            } returns FileMetadata(filename = "2026_04_17.md", lastModified = 20L)
            pendingConflictStore.write(
                com.lomo.domain.model.SyncConflictSet(
                    source = SyncBackendType.INBOX,
                    files =
                        listOf(
                            com.lomo.domain.model.SyncConflictFile(
                                relativePath = "inbox/2026_04_17.md",
                                localContent = "local-only note",
                                remoteContent = "remote-only note",
                                isBinary = false,
                                localLastModified = 20L,
                                remoteLastModified = 10L,
                            ),
                        ),
                    timestamp = 123L,
                ),
            )

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
                    filename = "2026_04_17.md",
                    content = "remote-only note\n\nlocal-only note",
                    append = false,
                    uri = null,
                )
            }
            assertNull(pendingConflictStore.read(SyncBackendType.INBOX))
            assertTrue(!inboxFile.exists())
        }

    @Test
    fun `sync auto merges sanitized sample shaped like reported inbox conflict`() =
        runTest {
            val inboxRoot = Files.createTempDirectory("sync-inbox-sanitized").toFile()
            val inboxFile =
                File(inboxRoot, "2026_04_13.md").apply {
                    writeText(
                        "\n- 21:02:55 这是一段脱敏后的长文本，用来模拟用户描述的单段笔记内容，它与另一侧的条目型内容不重叠。",
                    )
                }

            every { workspaceConfigSource.getRootFlow(StorageRootType.SYNC_INBOX) } returns flowOf(inboxRoot.absolutePath)
            coEvery {
                markdownStorageDataSource.readFileIn(MemoDirectoryType.MAIN, "2026_04_13.md")
            } returns "- 20:13:50\n简短条目一\n\n- 07:26:18 简短条目二\n![image](img_sample.png)"
            coEvery {
                markdownStorageDataSource.getFileMetadataIn(MemoDirectoryType.MAIN, "2026_04_13.md")
            } returns FileMetadata(filename = "2026_04_13.md", lastModified = 20L)

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
                    filename = "2026_04_13.md",
                    content =
                        "- 20:13:50\n简短条目一\n\n- 07:26:18 简短条目二\n![image](img_sample.png)\n\n" +
                            "- 21:02:55 这是一段脱敏后的长文本，用来模拟用户描述的单段笔记内容，它与另一侧的条目型内容不重叠。",
                    append = false,
                    uri = null,
                )
            }
            assertNull(pendingConflictStore.read(SyncBackendType.INBOX))
            assertTrue(!inboxFile.exists())
        }
}
