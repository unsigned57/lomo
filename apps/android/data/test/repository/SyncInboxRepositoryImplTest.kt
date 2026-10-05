package com.lomo.data.repository

/*
 * Behavior Contract:
 * - Unit under test: SyncInboxRepositoryImpl.
 * - Owning layer: data.
 * - Priority tier: P0.
 * - Capability: an approved sync-inbox markdown file becomes a memo through the same
 *   stage→verify→session command path as drafts: media bytes are staged under an
 *   IncomingTransfer lease at preview, transferred to the frozen operation at commit, and
 *   published by the workspace executor via StoreMemoCommand.Create. Kotlin never mints a
 *   media filename and never writes legacy images//voice/ or a verbatim root document.
 *
 * Scenarios:
 * - Given an inbox note with a resolvable attachment, when the review is built, then the
 *   incoming content carries the owner-resolved media/ reference and the staged artifact is
 *   leased to the incoming transfer.
 * - Given an approved item, when the resolution commits, then one Create command carries the
 *   approved content, the promote plans, the inbox file's chronology, and a deterministic
 *   operation id; no legacy media directory or verbatim document lands in the workspace.
 * - Given the session commit fails once, when the resolution is retried, then the identical
 *   operation id is reused and the item stays pending in between.
 * - Given keep-local is chosen, when the resolution runs, then no command is issued, the
 *   incoming file is deleted, and its staged claims are released.
 * - Given an attachment cannot be resolved, when the review is built, then the item is blocked
 *   and nothing commits.
 *
 * Observable outcomes:
 * - Captured StoreMemoCommand payloads, ledger lease ownership, deleted staged artifacts, and
 *   inbox/workspace directory listings.
 *
 * TDD proof:
 * - RED before the fix because the inbox minted sha256(path) filenames and wrote
 *   images//voice/ plus a verbatim root document through WorkspaceMediaAccess, never touching
 *   the session command path.
 *
 * Excludes:
 * - Rust executor internals, SAF inbox roots, and projection publication.
 *
 * Test Change Justification:
 * - Reason category: MediaPort sweep contract widened for external draft guards.
 * - Old behavior/assertion being replaced: the recording fake implemented sessionMediaOrphanSweep
 *   without the external-draft guard list.
 * - Why old assertion is no longer correct: the port signature now takes the draft-guard list so
 *   a sweep cannot reclaim leases owned by durable drafts; the stub still errors because the
 *   inbox scenarios never reach the sweep.
 * - Coverage preserved by: all stage→verify→commit scenarios unchanged.
 * - Why this is not fitting the test to the implementation: only the unused fake's signature
 *   moved; no assertion changed.
 */

import android.content.Context
import com.lomo.data.engine.media.MediaCommittedEntry
import com.lomo.data.engine.media.MediaManifest
import com.lomo.data.engine.media.MediaPort
import com.lomo.data.engine.media.MediaSourceKind
import com.lomo.data.engine.media.MediaStageLease
import com.lomo.data.engine.media.MediaStageOwnerKind
import com.lomo.data.engine.media.MediaStageRecord
import com.lomo.data.engine.media.MediaStageRelease
import com.lomo.data.engine.media.MediaStagedFacts
import com.lomo.data.engine.media.MediaSweepDraftGuard
import com.lomo.data.engine.media.MediaSweepReport
import com.lomo.data.engine.media.PendingMediaStageRegistry
import com.lomo.data.engine.store.StoreInvalidationScope
import com.lomo.data.engine.store.StoreMemoBatchCommit
import com.lomo.data.engine.store.StoreMemoCommand
import com.lomo.data.engine.store.StoreMemoCommandKind
import com.lomo.data.engine.store.StoreMemoCommit
import com.lomo.data.engine.store.StoreMemoDeleteTarget
import com.lomo.data.engine.store.StoreMemoPage
import com.lomo.data.engine.store.StoreMemoQuery
import com.lomo.data.engine.store.StoreMemoSnapshot
import com.lomo.data.engine.store.StorePageCursor
import com.lomo.data.engine.store.StorePort
import com.lomo.data.engine.store.StoreRebuildResult
import com.lomo.data.engine.store.StoreReminderPlan
import com.lomo.data.engine.store.StoreSidebarProjection
import com.lomo.data.source.StorageRootType
import com.lomo.data.source.WorkspaceConfigSource
import com.lomo.data.sync.SyncConflictSuggestionPort
import com.lomo.data.testing.DataFunSpec
import com.lomo.data.testing.KotestTemporaryFolder
import com.lomo.data.testing.fakes.fakeMarkdownWorkspaceContentProjector
import com.lomo.domain.model.MemoDocumentMutation
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncMergeSuggestion
import com.lomo.domain.model.SyncReviewItemState
import com.lomo.domain.model.SyncReviewResolution
import com.lomo.domain.model.SyncReviewResolutionChoice
import com.lomo.domain.model.SyncReviewSession
import com.lomo.domain.model.UnifiedSyncOperation
import com.lomo.domain.model.UnifiedSyncResult
import com.lomo.data.testing.fakes.FakeSyncInboxPreferencesRepository
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import java.io.File
import java.security.MessageDigest

class SyncInboxRepositoryImplTest : DataFunSpec() {
    private lateinit var tempFolder: KotestTemporaryFolder
    private lateinit var inboxDir: File
    private lateinit var workspaceDir: File
    private lateinit var stageDir: File
    private lateinit var cacheDir: File

    init {
        beforeTest {
            tempFolder = KotestTemporaryFolder()
            inboxDir = tempFolder.newFolder("inbox")
            workspaceDir = tempFolder.newFolder("workspace")
            stageDir = tempFolder.newFolder("stage")
            cacheDir = tempFolder.newFolder("cache")
        }

        afterTest {
            tempFolder.cleanup()
        }

        test("approved inbox file commits through one session create with staged media") {
            runTest {
                val harness = newHarness()
                File(inboxDir, "images").mkdirs()
                File(inboxDir, "images/photo.png").writeBytes(PNG_BYTES)
                val note = File(inboxDir, "note.md")
                note.writeText("incoming body\n\n![photo](images/photo.png)\n")
                check(note.setLastModified(NOTE_MTIME_MS))

                val reviewResult = harness.repository.sync(UnifiedSyncOperation.MANUAL_SYNC)
                val review = reviewResult.shouldBeInstanceOf<UnifiedSyncResult.Review>().review
                review.items.shouldHaveSize(1)
                val item = review.items.single()
                item.state shouldBe SyncReviewItemState.READY_TO_IMPORT
                item.incomingContent shouldContain "media/photo.png"
                item.incomingContent shouldNotContain "images/photo.png"
                item.localContent shouldBe null
                harness.mediaPort.leaseOwners() shouldBe
                    setOf(MediaStageOwnerKind.IncomingTransfer to "inbox/note.md")

                val resolved =
                    harness.repository.resolveReview(
                        SyncReviewResolution(
                            perItemChoices =
                                mapOf(item.relativePath to SyncReviewResolutionChoice.KEEP_INCOMING),
                        ),
                        review,
                    )

                resolved.shouldBeInstanceOf<UnifiedSyncResult.Success>()
                harness.storePort.commands.shouldHaveSize(1)
                val command = harness.storePort.commands.single()
                command.kind shouldBe StoreMemoCommandKind.Create
                command.operationId shouldStartWith "inbox-import-"
                command.content shouldBe item.incomingContent
                command.chronologyEpochMs shouldBe NOTE_MTIME_MS
                command.pendingPromotes.shouldHaveSize(1)
                val plan = command.pendingPromotes.single()
                plan.operationId shouldBe command.operationId
                plan.finalRelativePath shouldBe "media/photo.png"
                plan.staged.digest shouldBe sha256Hex(PNG_BYTES)

                // Nothing bypassed the executor: no legacy media dir, no verbatim document.
                File(workspaceDir, "images").exists() shouldBe false
                File(workspaceDir, "voice").exists() shouldBe false
                File(workspaceDir, "note.md").exists() shouldBe false

                // The operation owns the staged claim now; the inbox source files are gone.
                harness.mediaPort.leaseOwners() shouldBe
                    setOf(MediaStageOwnerKind.PendingOperation to command.operationId)
                note.exists() shouldBe false
                File(inboxDir, "images/photo.png").exists() shouldBe false
            }
        }

        test("a failed session commit keeps the item pending and retries with the same operation id") {
            runTest {
                val harness = newHarness()
                val note = File(inboxDir, "note.md")
                note.writeText("plain incoming body\n")

                val review =
                    harness.repository
                        .sync(UnifiedSyncOperation.MANUAL_SYNC)
                        .shouldBeInstanceOf<UnifiedSyncResult.Review>()
                        .review
                val item = review.items.single()

                harness.storePort.failNext = true
                val first =
                    harness.repository.resolveReview(
                        SyncReviewResolution(
                            perItemChoices =
                                mapOf(item.relativePath to SyncReviewResolutionChoice.KEEP_INCOMING),
                        ),
                        review,
                    )
                first.shouldBeInstanceOf<UnifiedSyncResult.Review>()
                note.exists() shouldBe true

                val second =
                    harness.repository.resolveReview(
                        SyncReviewResolution(
                            perItemChoices =
                                mapOf(item.relativePath to SyncReviewResolutionChoice.KEEP_INCOMING),
                        ),
                        review,
                    )
                second.shouldBeInstanceOf<UnifiedSyncResult.Success>()
                harness.storePort.commands.shouldHaveSize(1)
                harness.storePort.attemptedOperationIds.shouldHaveSize(2)
                harness.storePort.attemptedOperationIds[0] shouldBe
                    harness.storePort.attemptedOperationIds[1]
                note.exists() shouldBe false
            }
        }

        test("keep-local deletes the drop and releases every staged claim without a command") {
            runTest {
                val harness = newHarness()
                File(inboxDir, "images").mkdirs()
                File(inboxDir, "images/photo.png").writeBytes(PNG_BYTES)
                File(inboxDir, "note.md").writeText("![photo](images/photo.png)\n")

                val review =
                    harness.repository
                        .sync(UnifiedSyncOperation.MANUAL_SYNC)
                        .shouldBeInstanceOf<UnifiedSyncResult.Review>()
                        .review
                val item = review.items.single()
                harness.mediaPort.leaseOwners() shouldBe
                    setOf(MediaStageOwnerKind.IncomingTransfer to "inbox/note.md")

                val resolved =
                    harness.repository.resolveReview(
                        SyncReviewResolution(
                            perItemChoices =
                                mapOf(item.relativePath to SyncReviewResolutionChoice.KEEP_LOCAL),
                        ),
                        review,
                    )

                resolved.shouldBeInstanceOf<UnifiedSyncResult.Success>()
                harness.storePort.commands.shouldBeEmpty()
                harness.mediaPort.leaseOwners().shouldBeEmpty()
                harness.mediaPort.deletedArtifacts shouldBe listOf(sha256Hex(PNG_BYTES))
                File(inboxDir, "note.md").exists() shouldBe false
            }
        }

        test("an unresolvable attachment blocks the item and nothing commits") {
            runTest {
                val harness = newHarness()
                File(inboxDir, "note.md").writeText("![gone](images/missing.png)\n")

                val review =
                    harness.repository
                        .sync(UnifiedSyncOperation.MANUAL_SYNC)
                        .shouldBeInstanceOf<UnifiedSyncResult.Review>()
                        .review
                val item = review.items.single()
                item.state shouldBe SyncReviewItemState.BLOCKED

                val resolved =
                    harness.repository.resolveReview(
                        SyncReviewResolution(
                            perItemChoices =
                                mapOf(item.relativePath to SyncReviewResolutionChoice.KEEP_INCOMING),
                        ),
                        review,
                    )

                resolved.shouldBeInstanceOf<UnifiedSyncResult.Review>()
                harness.storePort.commands.shouldBeEmpty()
                File(inboxDir, "note.md").exists() shouldBe true
            }
        }
    }

    private fun newHarness(): Harness {
        val context = mockk<Context>()
        every { context.cacheDir } returns cacheDir
        val preferencesRepository = FakeSyncInboxPreferencesRepository()
        val workspaceConfigSource = mockk<WorkspaceConfigSource>()
        every { workspaceConfigSource.getRootFlow(StorageRootType.SYNC_INBOX) } returns
            MutableStateFlow(inboxDir.absolutePath)
        val mediaPort = RecordingStageLedgerMediaPort()
        val pendingStages = PendingMediaStageRegistry(mediaPort, stageRoot = { stageDir.absolutePath })
        val storePort = InboxRecordingStorePort()
        val reviewStore = FakePendingSyncReviewStore()
        val repository =
            SyncInboxRepositoryImpl(
                dependencies =
                    SyncInboxRepositoryDependencies(
                        context = context,
                        preferencesRepository = preferencesRepository,
                        workspaceConfigSource = workspaceConfigSource,
                        pendingReviewStore = reviewStore,
                        storePort = storePort,
                        pendingStages = pendingStages,
                        workspaceRoot = { workspaceDir.absolutePath },
                        committedMediaSink = RecordingCommittedMediaLocationSink(),
                    ),
                writeLease = alwaysWritableWorkspaceMutationLease(),
                contentProjector = fakeMarkdownWorkspaceContentProjector(),
                suggestionPort =
                    SyncConflictSuggestionPort { _, _, _, _, _ ->
                        SyncMergeSuggestion(suggested = null, safe = null, mergedText = null)
                    },
            )
        return Harness(repository, mediaPort, storePort, reviewStore)
    }

    private class Harness(
        val repository: SyncInboxRepositoryImpl,
        val mediaPort: RecordingStageLedgerMediaPort,
        val storePort: InboxRecordingStorePort,
        val reviewStore: FakePendingSyncReviewStore,
    )

    private companion object {
        const val NOTE_MTIME_MS = 1_710_000_000_000L
        val PNG_BYTES = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4)

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { byte -> "%02x".format(byte) }
    }
}

private class FakePendingSyncReviewStore : PendingSyncReviewStore {
    val clearedSources = mutableListOf<SyncBackendType>()
    val writtenSessions = mutableListOf<SyncReviewSession>()

    override suspend fun readDescriptor(source: SyncBackendType): PendingSyncReviewDescriptor? = null

    override suspend fun write(review: SyncReviewSession) {
        writtenSessions += review
    }

    override suspend fun writeDescriptor(descriptor: PendingSyncReviewDescriptor) = Unit

    override suspend fun clear(source: SyncBackendType) {
        clearedSources += source
    }
}

/**
 * Media port fake with real content-addressed staging: bytes are copied into the stage root and
 * identities are derived from the actual payload, so promote plans carry true digests.
 */
private class RecordingStageLedgerMediaPort : MediaPort {
    private val records = linkedMapOf<String, MediaStageRecord>()
    val deletedArtifacts = mutableListOf<String>()

    fun leaseOwners(): Set<Pair<MediaStageOwnerKind, String>> =
        records.values
            .flatMap { record -> record.leases.map { it.ownerKind to it.ownerId } }
            .toSet()

    override fun stageMedia(
        mediaRoot: String,
        sourceKind: MediaSourceKind,
        sourcePath: String,
        humanNameHint: String,
    ): MediaStagedFacts {
        val source = File(sourcePath)
        val digest = sha256(source.readBytes())
        val extension = humanNameHint.substringAfterLast('.', "")
        val stagedFile =
            File(mediaRoot, ".lomo-media-stage/$digest${if (extension.isBlank()) "" else ".$extension"}")
        stagedFile.parentFile?.mkdirs()
        source.copyTo(stagedFile, overwrite = true)
        return MediaStagedFacts(
            digest = digest,
            size = stagedFile.length(),
            mime = if (extension == "png") "image/png" else "application/octet-stream",
            stagingPath = stagedFile.absolutePath,
            humanNameHint = humanNameHint,
            suggestedFinalRelativePath = "media/$humanNameHint",
        )
    }

    override fun recordStageLease(
        workspaceRoot: String?,
        staged: MediaStagedFacts,
        ownerKind: MediaStageOwnerKind,
        ownerId: String,
    ): MediaStageRecord {
        val lease = MediaStageLease(staged.digest, ownerKind, ownerId)
        val existing = records[staged.digest]
        val record =
            if (existing == null) {
                MediaStageRecord(
                    artifactId = staged.digest,
                    digest = staged.digest,
                    size = staged.size,
                    mime = staged.mime,
                    stagingPath = staged.stagingPath,
                    humanNameHint = staged.humanNameHint,
                    suggestedFinalRelativePath = staged.suggestedFinalRelativePath,
                    leases = listOf(lease),
                    stagedBytesPresent = true,
                )
            } else if (existing.leases.contains(lease)) {
                existing
            } else {
                existing.copy(leases = existing.leases + lease)
            }
        records[staged.digest] = record
        return record
    }

    override fun stageRecordsForOwner(
        mediaRoot: String,
        ownerKind: MediaStageOwnerKind,
        ownerId: String,
    ): List<MediaStageRecord> =
        records.values.filter { record ->
            record.leases.any { it.ownerKind == ownerKind && it.ownerId == ownerId }
        }

    override fun transferStageLease(
        mediaRoot: String,
        from: MediaStageLease,
        to: MediaStageLease,
    ): MediaStageRelease {
        val record = records.getValue(from.artifactId)
        if (!record.leases.contains(from)) {
            check(record.leases.contains(to)) { "stage lease transfer source is not held" }
            return MediaStageRelease(from.artifactId, record.leases.size.toLong(), bytesDeleted = false)
        }
        val leases = record.leases.filterNot { it == from } + listOf(to).filterNot(record.leases::contains)
        records[from.artifactId] = record.copy(leases = leases)
        return MediaStageRelease(from.artifactId, leases.size.toLong(), bytesDeleted = false)
    }

    override fun releaseStageLease(
        mediaRoot: String,
        lease: MediaStageLease,
    ): MediaStageRelease {
        val record = records.getValue(lease.artifactId)
        val leases = record.leases.filterNot { it == lease }
        if (leases.isEmpty()) {
            records.remove(lease.artifactId)
            File(record.stagingPath).delete()
            deletedArtifacts += lease.artifactId
        } else {
            records[lease.artifactId] = record.copy(leases = leases)
        }
        return MediaStageRelease(lease.artifactId, leases.size.toLong(), leases.isEmpty())
    }

    override fun allocateRecordingTarget(
        mediaRoot: String,
        extension: String,
    ): String = error("recording is not exercised by the inbox test")

    override fun finalizeRecording(
        mediaRoot: String,
        recordingPath: String,
        humanNameHint: String,
    ): MediaStagedFacts = error("recording is not exercised by the inbox test")

    override fun queryMediaManifest(
        workspaceRoot: String,
        verifiedEntries: List<MediaCommittedEntry>,
    ): MediaManifest =
        MediaManifest(stageDirName = ".lomo-media-stage", entries = emptyList())

    override fun sessionMediaOrphanSweep(
        nowMs: Long?,
        recoveryWindowMs: Long,
        externalDrafts: List<MediaSweepDraftGuard>,
    ): MediaSweepReport = error("orphan sweep is not exercised by the inbox test")

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

/** Store port fake: captures memo commands; every other surface is unexpected in inbox tests. */
private class InboxRecordingStorePort : StorePort {
    val commands = mutableListOf<StoreMemoCommand>()
    val attemptedOperationIds = mutableListOf<String>()
    var failNext = false

    override fun applyMemoCommand(
        command: StoreMemoCommand,
        onPublication: (StoreMemoCommit) -> Unit,
    ): StoreMemoCommit {
        attemptedOperationIds += command.operationId
        if (failNext) {
            failNext = false
            error("injected commit failure")
        }
        commands += command
        return StoreMemoCommit(
            operationId = command.operationId,
            memoId = "memo-${command.operationId}",
            coreRevision = 1L,
            eventSequence = 1L,
            contentRevision = 1L,
            fileFingerprint = "ff",
            scopes = listOf(StoreInvalidationScope.MemoList),
            idempotentReplay = false,
        )
    }

    override fun permanentDeleteMany(
        operationId: String,
        targets: List<StoreMemoDeleteTarget>,
    ): StoreMemoBatchCommit = error("unexpected")

    override fun commitDocumentMutation(mutation: MemoDocumentMutation): StoreMemoCommit = error("unexpected")

    override fun startRebuild(batchSize: Int): StoreRebuildResult = error("unexpected")

    override fun snoozeReminder(
        opaqueId: String,
        snoozeDurationMs: Long,
    ) = error("unexpected")

    override fun recoverReminderSnooze() = error("unexpected")

    override fun queryMemos(
        query: StoreMemoQuery,
        cursor: StorePageCursor?,
        pageSize: Int,
        startMemoId: String?,
        backward: Boolean,
    ): StoreMemoPage = error("unexpected")

    override fun getMemo(memoId: String): StoreMemoSnapshot? = error("unexpected")

    override fun queryCount(query: StoreMemoQuery): Long = error("unexpected")

    override fun sidebarProjection(): StoreSidebarProjection = error("unexpected")

    override fun queryReminderPlan(nowUtcMs: Long): StoreReminderPlan = error("unexpected")
}
