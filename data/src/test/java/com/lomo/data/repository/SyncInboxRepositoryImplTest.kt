package com.lomo.data.repository

import android.content.Context
import com.lomo.data.source.FileMetadata
import com.lomo.data.source.MarkdownStorageDataSource
import com.lomo.data.source.MemoDirectoryType
import com.lomo.data.source.StorageRootType
import com.lomo.data.source.WorkspaceConfigSource
import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncConflictFile
import com.lomo.domain.model.SyncConflictFileReviewState.BLOCKED
import com.lomo.domain.model.SyncConflictFileReviewState.CONTENT_DIFFERENCE
import com.lomo.domain.model.SyncConflictFileReviewState.READY_TO_IMPORT
import com.lomo.domain.model.SyncConflictResolution
import com.lomo.domain.model.SyncConflictResolutionChoice.KEEP_REMOTE
import com.lomo.domain.model.SyncConflictSessionKind
import com.lomo.domain.model.SyncConflictSet
import com.lomo.domain.model.UnifiedSyncOperation
import com.lomo.domain.model.UnifiedSyncResult
import com.lomo.domain.repository.PreferencesRepository
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

/*
 * Test Contract:
 * - Unit under test: SyncInboxRepositoryImpl
 * - Owning layer: data
 * - Priority tier: P0
 *
 * Scenario matrix:
 * - Happy: sync returns review conflicts for inbox files and persists them for later resolution.
 * - Boundary: an already pending inbox review is surfaced again without rescanning inbox files.
 * - Failure: a per-file inspection exception becomes a blocked review item while later files still appear in the batch.
 * - Must-not-happen: resolving multiple accepted review files refreshes imported sync once and clears pending conflicts.
 *
 * Observable outcomes:
 * - returned UnifiedSyncResult, review states and messages, pending-conflict store contents, saved memo content, refresh invocation, and source cleanup.
 *
 * Red phase:
 * - Fails before behavior changes or migration are applied.
 *
 * Excludes:
 * - SAF transport behavior, UI rendering, and attachment path variants covered by SyncInboxRepositoryStructureTest.
 */
class SyncInboxRepositoryImplTest : DataFunSpec() {
    @MockK(relaxed = true)
    private lateinit var context: Context

    @MockK(relaxed = true)
    private lateinit var preferencesRepository: PreferencesRepository

    @MockK(relaxed = true)
    private lateinit var workspaceConfigSource: WorkspaceConfigSource

    @MockK(relaxed = true)
    private lateinit var markdownStorageDataSource: MarkdownStorageDataSource

    @MockK(relaxed = true)
    private lateinit var refreshEngine: MemoRefreshEngine

    @MockK(relaxed = true)
    private lateinit var mutationHandler: MemoMutationHandler

    private val savedFiles = linkedMapOf<String, String>()
    private lateinit var memoSynchronizer: MemoSynchronizer
    private lateinit var pendingConflictStore: PendingSyncConflictStore
    private lateinit var repository: SyncInboxRepositoryImpl

    init {
        beforeTest {
            setUp()
        }

        test("sync surfaces content difference review for disjoint inbox memo content") {
            runTest { verifyContentDifferenceReview() }
        }

        test("sync batches review files from one scan and preserves review states") {
            runTest { verifyBatchReviewFiles() }
        }

        test("sync returns pending inbox review without rescanning inbox files") {
            runTest { verifyPendingReviewIsReturned() }
        }

        test("sync surfaces sanitized sample shaped like reported inbox conflict as review") {
            runTest { verifySanitizedSampleReview() }
        }

        test("sync keeps later review files when one markdown file throws during inspection") {
            runTest { verifyInspectionFailureBecomesBlockedReview() }
        }

        test("resolveConflicts refreshes imported files once after multiple successful resolutions") {
            runTest { verifyResolveConflictsRefreshesOnce() }
        }
    }

    private fun setUp() {
        MockKAnnotations.init(this)
        savedFiles.clear()
        every { preferencesRepository.isSyncInboxEnabled() } returns flowOf(true)
        coEvery { mutationHandler.nextMemoFileOutbox() } returns null
        coEvery { mutationHandler.hasPendingMemoFileOutbox() } returns false
        coEvery { markdownStorageDataSource.saveFileIn(any(), any(), any(), any(), any()) } answers {
            savedFiles[secondArg<String>()] = thirdArg<String>()
            null
        }

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
                workspaceMediaAccess = NoOpWorkspaceMediaAccess,
                memoSynchronizer = memoSynchronizer,
                pendingConflictStore = pendingConflictStore,
            )
    }

    private suspend fun verifyContentDifferenceReview() {
        val inboxRoot = Files.createTempDirectory("sync-inbox").toFile()
        val inboxFile = File(inboxRoot, "2026_04_15.md").apply { writeText("remote idea\nremote detail") }
        configureInboxRoot(inboxRoot)
        coEvery {
            markdownStorageDataSource.readFileIn(MemoDirectoryType.MAIN, "2026_04_15.md")
        } returns "local idea\nlocal detail"
        coEvery {
            markdownStorageDataSource.getFileMetadataIn(MemoDirectoryType.MAIN, "2026_04_15.md")
        } returns FileMetadata(filename = "2026_04_15.md", lastModified = 0L)

        val result = repository.sync(UnifiedSyncOperation.PROCESS_PENDING_CHANGES) as UnifiedSyncResult.Conflict

        result.message shouldBe "Sync inbox review required"
        result.conflicts.sessionKind shouldBe SyncConflictSessionKind.SYNC_INBOX_REVIEW
        result.conflicts.files shouldBe
            listOf(
                SyncConflictFile(
                    relativePath = "inbox/2026_04_15.md",
                    localContent = "local idea\nlocal detail",
                    remoteContent = "remote idea\nremote detail",
                    isBinary = false,
                    localLastModified = 0L,
                    remoteLastModified = inboxFile.lastModified(),
                    reviewState = CONTENT_DIFFERENCE,
                ),
            )
        pendingConflictStore.read(SyncBackendType.INBOX) shouldBe result.conflicts
        savedFiles shouldBe emptyMap()
        coVerify(exactly = 0) { refreshEngine.refreshImportedSync(any()) }
        inboxFile.exists().shouldBeTrue()
    }

    private suspend fun verifyBatchReviewFiles() {
        val inboxRoot = Files.createTempDirectory("sync-inbox-conflicts").toFile()
        File(inboxRoot, "2026_04_15.md").writeText("start\nremote first\nend")
        File(inboxRoot, "2026_04_16.md").writeText("ready to import")
        configureInboxRoot(inboxRoot)
        coEvery {
            markdownStorageDataSource.readFileIn(MemoDirectoryType.MAIN, "2026_04_15.md")
        } returns "start\nlocal first\nend"
        coEvery {
            markdownStorageDataSource.readFileIn(MemoDirectoryType.MAIN, "2026_04_16.md")
        } returns null

        val result = repository.sync(UnifiedSyncOperation.PROCESS_PENDING_CHANGES) as UnifiedSyncResult.Conflict

        result.conflicts.source shouldBe SyncBackendType.INBOX
        result.conflicts.sessionKind shouldBe SyncConflictSessionKind.SYNC_INBOX_REVIEW
        result.conflicts.files.map { it.relativePath to it.reviewState } shouldBe
            listOf(
                "inbox/2026_04_15.md" to CONTENT_DIFFERENCE,
                "inbox/2026_04_16.md" to READY_TO_IMPORT,
            )
        result.conflicts.files.map { it.remoteContent } shouldBe
            listOf(
                "start\nremote first\nend",
                "ready to import",
            )
        pendingConflictStore.read(SyncBackendType.INBOX) shouldBe result.conflicts
        savedFiles shouldBe emptyMap()
        coVerify(exactly = 0) { refreshEngine.refreshImportedSync(any()) }
    }

    private suspend fun verifyPendingReviewIsReturned() {
        val inboxRoot = Files.createTempDirectory("sync-inbox-pending").toFile()
        configureInboxRoot(inboxRoot)
        val pendingConflict =
            SyncConflictSet(
                source = SyncBackendType.INBOX,
                files =
                    listOf(
                        SyncConflictFile(
                            relativePath = "inbox/2026_04_17.md",
                            localContent = "local-only note",
                            remoteContent = "remote-only note",
                            isBinary = false,
                            reviewState = CONTENT_DIFFERENCE,
                        ),
                    ),
                timestamp = 123L,
            )
        pendingConflictStore.write(pendingConflict)

        val result = repository.sync(UnifiedSyncOperation.PROCESS_PENDING_CHANGES) as UnifiedSyncResult.Conflict

        result.message shouldBe "Pending sync inbox review"
        result.conflicts shouldBe pendingConflict.copy(sessionKind = SyncConflictSessionKind.SYNC_INBOX_REVIEW)
        pendingConflictStore.read(SyncBackendType.INBOX) shouldBe pendingConflict
        coVerify(exactly = 0) { markdownStorageDataSource.readFileIn(any(), any()) }
        savedFiles shouldBe emptyMap()
    }

    private suspend fun verifySanitizedSampleReview() {
        val inboxRoot = Files.createTempDirectory("sync-inbox-sanitized").toFile()
        val inboxFile =
            File(inboxRoot, "2026_04_13.md").apply {
                writeText(
                    "\n- 21:02:55 这是一段脱敏后的长文本，用来模拟用户描述的单段笔记内容，它与另一侧的条目型内容不重叠。",
                )
            }
        configureInboxRoot(inboxRoot)
        coEvery {
            markdownStorageDataSource.readFileIn(MemoDirectoryType.MAIN, "2026_04_13.md")
        } returns "- 20:13:50\n简短条目一\n\n- 07:26:18 简短条目二\n![image](img_sample.png)"
        coEvery {
            markdownStorageDataSource.getFileMetadataIn(MemoDirectoryType.MAIN, "2026_04_13.md")
        } returns FileMetadata(filename = "2026_04_13.md", lastModified = 20L)

        val result = repository.sync(UnifiedSyncOperation.PROCESS_PENDING_CHANGES) as UnifiedSyncResult.Conflict
        val conflictFile = result.conflicts.files.single()

        conflictFile.relativePath shouldBe "inbox/2026_04_13.md"
        conflictFile.reviewState shouldBe CONTENT_DIFFERENCE
        conflictFile.localContent shouldBe "- 20:13:50\n简短条目一\n\n- 07:26:18 简短条目二\n![image](img_sample.png)"
        conflictFile.remoteContent shouldBe "\n- 21:02:55 这是一段脱敏后的长文本，用来模拟用户描述的单段笔记内容，它与另一侧的条目型内容不重叠。"
        conflictFile.localLastModified shouldBe 20L
        conflictFile.remoteLastModified shouldBe inboxFile.lastModified()
        pendingConflictStore.read(SyncBackendType.INBOX) shouldBe result.conflicts
        savedFiles shouldBe emptyMap()
        inboxFile.exists().shouldBeTrue()
    }

    private suspend fun verifyInspectionFailureBecomesBlockedReview() {
        val inboxRoot = Files.createTempDirectory("sync-inbox-exception-batch").toFile()
        val memoDirectory = File(inboxRoot, "memo").apply { mkdirs() }
        File(memoDirectory, "2026_04_27.md").writeText("broken memo")
        File(memoDirectory, "2026_04_28.md").writeText("healthy memo")
        configureInboxRoot(inboxRoot)
        coEvery { markdownStorageDataSource.readFileIn(MemoDirectoryType.MAIN, "2026_04_27.md") } throws
            IllegalStateException("boom")
        coEvery { markdownStorageDataSource.readFileIn(MemoDirectoryType.MAIN, "2026_04_28.md") } returns null

        val result = repository.sync(UnifiedSyncOperation.PROCESS_PENDING_CHANGES) as UnifiedSyncResult.Conflict
        val blockedFile = result.conflicts.files.first { it.relativePath == "inbox/memo/2026_04_27.md" }
        val readyFile = result.conflicts.files.first { it.relativePath == "inbox/memo/2026_04_28.md" }

        blockedFile.reviewState shouldBe BLOCKED
        blockedFile.reviewMessage shouldBe "boom"
        blockedFile.remoteContent.shouldBeNull()
        readyFile.reviewState shouldBe READY_TO_IMPORT
        readyFile.remoteContent shouldBe "healthy memo"
        pendingConflictStore.read(SyncBackendType.INBOX) shouldBe result.conflicts
        savedFiles shouldBe emptyMap()
        coVerify(exactly = 0) { refreshEngine.refreshImportedSync(any()) }
    }

    private suspend fun verifyResolveConflictsRefreshesOnce() {
        val inboxRoot = Files.createTempDirectory("sync-inbox-batch-refresh").toFile()
        val memoDirectory = File(inboxRoot, "memo").apply { mkdirs() }
        val firstInboxFile = File(memoDirectory, "2026_04_29.md").apply { writeText("first") }
        val secondInboxFile = File(memoDirectory, "2026_04_30.md").apply { writeText("second") }
        configureInboxRoot(inboxRoot)
        coEvery { markdownStorageDataSource.readFileIn(MemoDirectoryType.MAIN, any()) } returns null

        val review = repository.sync(UnifiedSyncOperation.PROCESS_PENDING_CHANGES) as UnifiedSyncResult.Conflict
        val resolution =
            SyncConflictResolution(
                perFileChoices = review.conflicts.files.associate { it.relativePath to KEEP_REMOTE },
            )

        val result = repository.resolveConflicts(resolution, review.conflicts)

        result shouldBe
            UnifiedSyncResult.Success(
                provider = SyncBackendType.INBOX,
                message = "Sync inbox conflicts resolved",
            )
        savedFiles shouldBe
            linkedMapOf(
                "2026_04_29.md" to "first",
                "2026_04_30.md" to "second",
            )
        coVerify(exactly = 1) { refreshEngine.refreshImportedSync() }
        firstInboxFile.exists() shouldBe false
        secondInboxFile.exists() shouldBe false
        pendingConflictStore.read(SyncBackendType.INBOX).shouldBeNull()
    }

    private fun configureInboxRoot(inboxRoot: File) {
        every { workspaceConfigSource.getRootFlow(StorageRootType.SYNC_INBOX) } returns flowOf(inboxRoot.absolutePath)
    }
}
