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
import com.lomo.data.source.MarkdownStorageDataSource
import com.lomo.data.source.MemoDirectoryType
import com.lomo.data.source.StorageRootType
import com.lomo.data.source.WorkspaceConfigSource
import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncReviewItem
import com.lomo.domain.model.SyncReviewItemState.BLOCKED
import com.lomo.domain.model.SyncReviewItemState.READY_TO_IMPORT
import com.lomo.domain.model.SyncReviewResolution
import com.lomo.domain.model.SyncReviewResolutionChoice.KEEP_INCOMING
import com.lomo.domain.model.SyncReviewSessionKind
import com.lomo.domain.model.UnifiedSyncOperation
import com.lomo.domain.model.UnifiedSyncResult
import com.lomo.domain.repository.PreferencesRepository
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: SyncInboxRepositoryImpl
 * - Owning layer: data
 * - Priority tier: P0
 *
 * Scenarios:
 * - Happy: sync previews inbox markdown from root and memo directories and rewrites attachment references for review.
 * - Boundary: bare attachment filenames resolve through images, voice, and recording fallbacks without importing files during preview.
 * - Failure: missing attachments surface blocked review entries while later inbox files remain reviewable.
 * - Must-not-happen: resolving reviewed imports must preserve rewritten attachment references, stream attachment bytes, and reuse stable filenames for shared sources.
 *
 * Observable outcomes:
 * - created inbox directories, returned review items, rewritten incoming content, blocked review messages, streamed media bytes, saved memo content, and source cleanup.
 *
 * TDD proof:
 * - RED: Sync inbox stream API test is weak before strengthening because byte-array and stream writes record identical outcomes.
 * - Test-only strengthening: production already streams attachments; the fake now records method selection so a regression to byte-array writes fails.
 *
 * Excludes:
 * - SAF tree mechanics, UI rendering, and content-difference review behavior covered by SyncInboxRepositoryImplTest.
 */
class SyncInboxRepositoryStructureTest : DataFunSpec() {
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
    private lateinit var workspaceMediaAccess: RecordingWorkspaceMediaAccess
    private lateinit var pendingReviewStore: PendingSyncReviewStore
    private lateinit var repository: SyncInboxRepositoryImpl

    init {
        beforeTest {
            setUp()
        }

        test("ensureDirectoryStructure creates memo images and voice directories in a direct root") {
            runTest { verifyDirectoryStructureCreation() }
        }

        test("sync returns review entries for markdown files from the memo subdirectory") {
            runTest { verifyMemoSubdirectoryReview() }
        }

        test("sync rewrites inbox attachment references in review content without importing files") {
            runTest { verifyAttachmentPreviewRewrite() }
        }

        test("sync rewrites bare image filename references from the images directory") {
            runTest { verifyBareImagePreviewRewrite() }
        }

        test("sync rewrites bare voice filename references from the voice directory") {
            runTest { verifyBareVoicePreviewRewrite() }
        }

        test("sync rewrites bare voice filename references from the recording directory") {
            runTest { verifyRecordingPreviewRewrite() }
        }

        test("sync returns a blocked review and preserves source files when a referenced attachment is missing") {
            runTest { verifyMissingAttachmentReview() }
        }

        test("sync keeps later review files when one file is missing an attachment") {
            runTest { verifyBatchReviewWhenOneAttachmentIsMissing() }
        }

        test("resolveConflicts reuses one imported attachment filename across memos sharing the same source path") {
            runTest { verifyStableAttachmentFilenameReuse() }
        }

        test("resolveConflicts streams attachment copies through workspace media access") {
            runTest { verifyAttachmentStreaming() }
        }
    }

    private fun setUp() {
        MockKAnnotations.init(this)
        savedFiles.clear()
        workspaceMediaAccess = RecordingWorkspaceMediaAccess()
        pendingReviewStore = InMemoryPendingSyncReviewStore()
        every { preferencesRepository.isSyncInboxEnabled() } returns flowOf(true)
        coEvery { mutationHandler.nextMemoFileOutbox() } returns null
        coEvery { mutationHandler.hasPendingMemoFileOutbox() } returns false
        coEvery { markdownStorageDataSource.getFileMetadataIn(any(), any()) } returns null
        coEvery { markdownStorageDataSource.saveFileIn(any(), any(), any(), any(), any()) } answers {
            savedFiles[secondArg<String>()] = thirdArg<String>()
            null
        }

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
                        outboxScope = immediateTestBackgroundScope(),
                        startOutboxCoordinator = false,
                    ),
                pendingReviewStore = pendingReviewStore,
            )
    }

    private suspend fun verifyDirectoryStructureCreation() {
        val inboxRoot = Files.createTempDirectory("sync-inbox-structure").toFile()
        configureInboxRoot(inboxRoot)

        repository.ensureDirectoryStructure()

        File(inboxRoot, "memo").isDirectory.shouldBeTrue()
        File(inboxRoot, "images").isDirectory.shouldBeTrue()
        File(inboxRoot, "voice").isDirectory.shouldBeTrue()
    }

    private suspend fun verifyMemoSubdirectoryReview() {
        val inboxRoot = Files.createTempDirectory("sync-inbox-memo-dir").toFile()
        val memoDirectory = File(inboxRoot, "memo").apply { mkdirs() }
        val inboxFile = File(memoDirectory, "2026_04_16.md").apply { writeText("memo from sync inbox") }
        configureInboxRoot(inboxRoot)
        coEvery { markdownStorageDataSource.readFileIn(MemoDirectoryType.MAIN, "2026_04_16.md") } returns null

        val result = repository.sync(UnifiedSyncOperation.PROCESS_PENDING_CHANGES) as UnifiedSyncResult.Review
        result.message shouldBe "Sync inbox review required"
        result.review.kind shouldBe SyncReviewSessionKind.SYNC_INBOX_IMPORT_REVIEW
        result.review.items shouldBe
            listOf(
                SyncReviewItem(
                    relativePath = "inbox/memo/2026_04_16.md",
                    localContent = null,
                    incomingContent = "memo from sync inbox",
                    isBinary = false,
                    incomingLastModified = inboxFile.lastModified(),
                    state = READY_TO_IMPORT,
                ),
            )
        pendingReviewStore.storedReview(SyncBackendType.INBOX) shouldBe result.review
        savedFiles shouldBe emptyMap()
        workspaceMediaAccess.writes.size shouldBe 0
        inboxFile.exists().shouldBeTrue()
    }

    private suspend fun verifyAttachmentPreviewRewrite() {
        val inboxRoot = Files.createTempDirectory("sync-inbox-attachments").toFile()
        val memoDirectory = File(inboxRoot, "memo").apply { mkdirs() }
        val imagesDirectory = File(inboxRoot, "images").apply { mkdirs() }
        val inboxFile =
            File(memoDirectory, "2026_04_18.md").apply {
                writeText("memo with image ![cover](images/cover.png)")
            }
        File(imagesDirectory, "cover.png").writeBytes("cover".toByteArray())
        configureInboxRoot(inboxRoot)
        coEvery { markdownStorageDataSource.readFileIn(MemoDirectoryType.MAIN, "2026_04_18.md") } returns null

        val result = repository.sync(UnifiedSyncOperation.PROCESS_PENDING_CHANGES) as UnifiedSyncResult.Review
        val reviewItem = result.review.items.single()
        reviewItem.state shouldBe READY_TO_IMPORT
        reviewItem.incomingContent shouldMatch Regex("""memo with image !\[cover]\(cover_[0-9a-f]{10}\.png\)""")
        reviewItem.message.shouldBeNull()
        workspaceMediaAccess.writes.size shouldBe 0
        savedFiles shouldBe emptyMap()
        inboxFile.exists().shouldBeTrue()
        File(imagesDirectory, "cover.png").exists().shouldBeTrue()
    }

    private suspend fun verifyBareImagePreviewRewrite() {
        val inboxRoot = Files.createTempDirectory("sync-inbox-image-bare").toFile()
        val memoDirectory = File(inboxRoot, "memo").apply { mkdirs() }
        val imagesDirectory = File(inboxRoot, "images").apply { mkdirs() }
        File(imagesDirectory, "poster.png").writeBytes("poster".toByteArray())
        File(memoDirectory, "2026_04_22.md").writeText("memo with image ![poster](poster.png)")
        configureInboxRoot(inboxRoot)
        coEvery { markdownStorageDataSource.readFileIn(MemoDirectoryType.MAIN, "2026_04_22.md") } returns null

        val result = repository.sync(UnifiedSyncOperation.PROCESS_PENDING_CHANGES) as UnifiedSyncResult.Review
        result.review.items.single().incomingContent shouldMatch
            Regex("""memo with image !\[poster]\(poster_[0-9a-f]{10}\.png\)""")
        workspaceMediaAccess.writes.size shouldBe 0
        savedFiles shouldBe emptyMap()
    }

    private suspend fun verifyBareVoicePreviewRewrite() {
        val inboxRoot = Files.createTempDirectory("sync-inbox-voice-bare").toFile()
        val memoDirectory = File(inboxRoot, "memo").apply { mkdirs() }
        val voiceDirectory = File(inboxRoot, "voice").apply { mkdirs() }
        File(voiceDirectory, "voice_20260416.m4a").writeBytes("voice".toByteArray())
        File(memoDirectory, "2026_04_19.md").writeText("memo with voice ![voice](voice_20260416.m4a)")
        configureInboxRoot(inboxRoot)
        coEvery { markdownStorageDataSource.readFileIn(MemoDirectoryType.MAIN, "2026_04_19.md") } returns null

        val result = repository.sync(UnifiedSyncOperation.PROCESS_PENDING_CHANGES) as UnifiedSyncResult.Review
        result.review.items.single().incomingContent shouldMatch
            Regex("""memo with voice !\[voice]\(voice_20260416_[0-9a-f]{10}\.m4a\)""")
        workspaceMediaAccess.writes.size shouldBe 0
        savedFiles shouldBe emptyMap()
    }

    private suspend fun verifyRecordingPreviewRewrite() {
        val inboxRoot = Files.createTempDirectory("sync-inbox-recording-bare").toFile()
        val memoDirectory = File(inboxRoot, "memo").apply { mkdirs() }
        val recordingDirectory = File(inboxRoot, "recording").apply { mkdirs() }
        File(recordingDirectory, "voice_20260420.m4a").writeBytes("recording".toByteArray())
        File(memoDirectory, "2026_04_20.md").writeText("memo with voice ![voice](voice_20260420.m4a)")
        configureInboxRoot(inboxRoot)
        coEvery { markdownStorageDataSource.readFileIn(MemoDirectoryType.MAIN, "2026_04_20.md") } returns null

        val result = repository.sync(UnifiedSyncOperation.PROCESS_PENDING_CHANGES) as UnifiedSyncResult.Review
        result.review.items.single().incomingContent shouldMatch
            Regex("""memo with voice !\[voice]\(voice_20260420_[0-9a-f]{10}\.m4a\)""")
        workspaceMediaAccess.writes.size shouldBe 0
        savedFiles shouldBe emptyMap()
    }

    private suspend fun verifyMissingAttachmentReview() {
        val inboxRoot = Files.createTempDirectory("sync-inbox-missing-attachment").toFile()
        val memoDirectory = File(inboxRoot, "memo").apply { mkdirs() }
        val inboxFile =
            File(memoDirectory, "2026_04_21.md").apply {
                writeText("memo with missing image ![cover](cover.png)")
            }
        configureInboxRoot(inboxRoot)
        coEvery { markdownStorageDataSource.readFileIn(MemoDirectoryType.MAIN, "2026_04_21.md") } returns null

        val result = repository.sync(UnifiedSyncOperation.PROCESS_PENDING_CHANGES) as UnifiedSyncResult.Review
        val reviewItem = result.review.items.single()

        reviewItem.relativePath shouldBe "inbox/memo/2026_04_21.md"
        reviewItem.state shouldBe BLOCKED
        reviewItem.message shouldBe "Missing attachments: cover.png"
        pendingReviewStore.storedReview(SyncBackendType.INBOX) shouldBe result.review
        workspaceMediaAccess.writes.size shouldBe 0
        savedFiles shouldBe emptyMap()
        inboxFile.exists().shouldBeTrue()
    }

    private suspend fun verifyBatchReviewWhenOneAttachmentIsMissing() {
        val inboxRoot = Files.createTempDirectory("sync-inbox-missing-attachment-batch").toFile()
        val memoDirectory = File(inboxRoot, "memo").apply { mkdirs() }
        val brokenInboxFile =
            File(memoDirectory, "2026_04_23.md").apply {
                writeText("memo with missing image ![missing](images/missing.png)")
            }
        val validInboxFile =
            File(memoDirectory, "2026_04_24.md").apply {
                writeText("valid memo after broken attachment")
            }
        configureInboxRoot(inboxRoot)
        coEvery { markdownStorageDataSource.readFileIn(MemoDirectoryType.MAIN, "2026_04_23.md") } returns null
        coEvery { markdownStorageDataSource.readFileIn(MemoDirectoryType.MAIN, "2026_04_24.md") } returns null

        val result = repository.sync(UnifiedSyncOperation.PROCESS_PENDING_CHANGES) as UnifiedSyncResult.Review

        result.review.items.map { it.relativePath to it.state } shouldBe
            listOf(
                "inbox/memo/2026_04_23.md" to BLOCKED,
                "inbox/memo/2026_04_24.md" to READY_TO_IMPORT,
            )
        result.review.items.last().incomingContent shouldBe "valid memo after broken attachment"
        pendingReviewStore.storedReview(SyncBackendType.INBOX) shouldBe result.review
        workspaceMediaAccess.writes.size shouldBe 0
        savedFiles shouldBe emptyMap()
        brokenInboxFile.exists().shouldBeTrue()
        validInboxFile.exists().shouldBeTrue()
    }

    private suspend fun verifyStableAttachmentFilenameReuse() {
        val inboxRoot = Files.createTempDirectory("sync-inbox-stable-attachments").toFile()
        val memoDirectory = File(inboxRoot, "memo").apply { mkdirs() }
        val imagesDirectory = File(inboxRoot, "images").apply { mkdirs() }
        val imageBytes = "shared-cover".toByteArray()
        val sharedImage = File(imagesDirectory, "cover.png").apply { writeBytes(imageBytes) }
        val firstInboxFile = File(memoDirectory, "2026_04_25.md").apply { writeText("first ![cover](images/cover.png)") }
        val secondInboxFile = File(memoDirectory, "2026_04_26.md").apply { writeText("second ![cover](images/cover.png)") }
        configureInboxRoot(inboxRoot)
        coEvery { markdownStorageDataSource.readFileIn(MemoDirectoryType.MAIN, any()) } returns null

        val review = repository.sync(UnifiedSyncOperation.PROCESS_PENDING_CHANGES) as UnifiedSyncResult.Review
        val resolution =
            SyncReviewResolution(
                perItemChoices = review.review.items.associate { it.relativePath to KEEP_INCOMING },
            )

        val result = repository.resolveReview(resolution, review.review)
        val importedFilenames = workspaceMediaAccess.writes.map { it.filename }
        val importedFilename = importedFilenames.first()

        result shouldBe UnifiedSyncResult.Success(SyncBackendType.INBOX, "Sync inbox review resolved")
        importedFilenames.size shouldBe 2
        importedFilenames.toSet().size shouldBe 1
        savedFiles["2026_04_25.md"] shouldBe "first ![cover]($importedFilename)"
        savedFiles["2026_04_26.md"] shouldBe "second ![cover]($importedFilename)"
        coVerify(exactly = 1) { refreshEngine.refreshImportedSync() }
        sharedImage.exists() shouldBe false
        firstInboxFile.exists() shouldBe false
        secondInboxFile.exists() shouldBe false
        pendingReviewStore.storedReview(SyncBackendType.INBOX).shouldBeNull()
    }

    private suspend fun verifyAttachmentStreaming() {
        val inboxRoot = Files.createTempDirectory("sync-inbox-streamed-attachment").toFile()
        val memoDirectory = File(inboxRoot, "memo").apply { mkdirs() }
        val imagesDirectory = File(inboxRoot, "images").apply { mkdirs() }
        val imageBytes = "cover".toByteArray()
        File(imagesDirectory, "cover.png").writeBytes(imageBytes)
        File(memoDirectory, "2026_05_01.md").writeText("memo ![cover](images/cover.png)")
        configureInboxRoot(inboxRoot)
        coEvery { markdownStorageDataSource.readFileIn(MemoDirectoryType.MAIN, "2026_05_01.md") } returns null

        val review = repository.sync(UnifiedSyncOperation.PROCESS_PENDING_CHANGES) as UnifiedSyncResult.Review
        val resolution =
            SyncReviewResolution(
                perItemChoices = review.review.items.associate { it.relativePath to KEEP_INCOMING },
            )

        repository.resolveReview(resolution, review.review)

        val write = workspaceMediaAccess.writes.single()
        write.category shouldBe WorkspaceMediaCategory.IMAGE
        write.method shouldBe RecordedMediaWriteMethod.STREAM
        write.bytes.contentEquals(imageBytes) shouldBe true
        savedFiles["2026_05_01.md"] shouldBe "memo ![cover](${write.filename})"
    }

    private fun configureInboxRoot(inboxRoot: File) {
        every { workspaceConfigSource.getRootFlow(StorageRootType.SYNC_INBOX) } returns flowOf(inboxRoot.absolutePath)
    }

    private data class RecordedMediaWrite(
        val category: WorkspaceMediaCategory,
        val filename: String,
        val bytes: ByteArray,
        val method: RecordedMediaWriteMethod,
    )

    private enum class RecordedMediaWriteMethod {
        BYTE_ARRAY,
        STREAM,
    }

    private class RecordingWorkspaceMediaAccess : WorkspaceMediaAccess {
        val writes = mutableListOf<RecordedMediaWrite>()

        override suspend fun listFiles(category: WorkspaceMediaCategory): List<WorkspaceMediaDescriptor> = emptyList()

        override suspend fun listFilenames(category: WorkspaceMediaCategory): List<String> = emptyList()

        override suspend fun readFileToStream(
            category: WorkspaceMediaCategory,
            filename: String,
            destination: OutputStream,
        ): Boolean = false

        override suspend fun writeFileFromStream(
            category: WorkspaceMediaCategory,
            filename: String,
            source: suspend (OutputStream) -> Unit,
        ) {
            val output = ByteArrayOutputStream()
            source(output)
            writes +=
                RecordedMediaWrite(
                    category = category,
                    filename = filename,
                    bytes = output.toByteArray(),
                    method = RecordedMediaWriteMethod.STREAM,
                )
        }

        override suspend fun deleteFile(
            category: WorkspaceMediaCategory,
            filename: String,
        ) = Unit
    }
}
