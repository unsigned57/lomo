package com.lomo.data.repository

/**
 * Behavior Contract:
 * - Unit under test: MediaEdgeRepository delete / orphan sweep orchestration.
 * - Owning layer: data production media edge (P4-10A D6).
 * - Priority tier: P0.
 * - Capability: removeImage/removeVoiceCapture never treat host File.delete as permanent
 *   committed-media authority; they journal sync delete then run mediaOrphanSweep with
 *   committed manifest + store-derived attachment refs (current ∪ trash ∪ history).
 *
 * Scenarios:
 * - Given a committed digest with zero store refs, when removeImage runs, then
 *   mediaOrphanSweep is invoked with that digest in committed and empty refs.
 * - Given a committed digest still referenced by a current memo imageUrl, when removeImage
 *   runs, then sweep still runs and refs include that digest as source=current.
 * - Given a digest only referenced by history, when sweep runs, then refs include source=history.
 * - Given Direct root absent, when removeImage runs, then sweep is skipped without crash.
 * - Given sweep result, when zero-ref removeImage runs, then movedToTrash is observed.
 *
 * Observable outcomes: MediaPort.mediaOrphanSweep call counts/args; sync delete journaled;
 * no permanent File.delete of media/ paths from production edge.
 *
 * TDD proof: RED before removeImage wired through orphan sweep (File.delete path).
 *
 * Excludes: real JNI, SAF trees, magic validation, archive paths.
 */

import android.content.Context
import com.lomo.data.engine.media.MediaAttachmentRef
import com.lomo.data.engine.media.MediaCommittedEntry
import com.lomo.data.engine.media.MediaManifest
import com.lomo.data.engine.media.MediaOrphanSweepResult
import com.lomo.data.engine.media.MediaPort
import com.lomo.data.engine.media.MediaPromotePlan
import com.lomo.data.engine.media.MediaPromoteResult
import com.lomo.data.engine.media.MediaSourceKind
import com.lomo.data.engine.media.MediaStagedFacts
import com.lomo.data.engine.media.MediaSyncEdgeAdapter
import com.lomo.data.engine.media.MediaTrashEntry
import com.lomo.data.engine.media.WorkspaceFilesystemRoot
import com.lomo.data.engine.store.StoreHistoryAttachmentRef
import com.lomo.data.engine.store.StoreMemoCommand
import com.lomo.data.engine.store.StoreMemoCommit
import com.lomo.data.engine.store.StoreMemoPage
import com.lomo.data.engine.store.StoreMemoQuery
import com.lomo.data.engine.store.StoreMemoSnapshot
import com.lomo.data.engine.store.StoreMemoSummary
import com.lomo.data.engine.store.StorePageCursor
import com.lomo.data.engine.store.StorePort
import com.lomo.data.engine.store.StoreRebuildResult
import com.lomo.data.source.MediaStorageDataSource
import com.lomo.data.source.WorkspaceConfigSource
import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.model.MediaEntryId
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory

class MediaEdgeRepositoryOrphanSweepTest : DataFunSpec() {
    init {
        test("given zero-refcount committed media when removeImage then orphan sweep runs with empty refs") {
            val root = createTempDirectory("lomo-media-edge-").toFile()
            val mediaDir = File(root, "media").apply { mkdirs() }
            val file = File(mediaDir, "orphan.png").apply { writeText("png-bytes") }
            val digest = "a".repeat(64)
            val port =
                RecordingMediaPort(
                    manifest =
                        MediaManifest(
                            stageDirName = ".lomo-media-stage",
                            entries =
                                listOf(
                                    MediaCommittedEntry(digest = digest, absolutePath = file.absolutePath),
                                ),
                        ),
                )
            val store =
                FakeStorePort(
                    pages =
                        listOf(
                            StoreMemoPage(
                                items = emptyList(),
                                nextCursor = null,
                                highWaterRevision = 1,
                                queryFingerprint = "fp",
                            ),
                        ),
                )
            val edge = edge(root = root, port = port, store = store)

            runBlocking { edge.removeImage(MediaEntryId("orphan.png")) }

            port.sweepCalls.size shouldBe 1
            val call = port.sweepCalls.single()
            call.committed.map { it.digest } shouldContain digest
            call.refs shouldBe emptyList()
            call.existingTrash shouldBe emptyList()
        }

        test("given live memo attachment when removeImage then sweep refs keep current source") {
            val root = createTempDirectory("lomo-media-edge-live-").toFile()
            val mediaDir = File(root, "media").apply { mkdirs() }
            val file = File(mediaDir, "live.png").apply { writeText("live") }
            val digest = "b".repeat(64)
            val port =
                RecordingMediaPort(
                    manifest =
                        MediaManifest(
                            stageDirName = ".lomo-media-stage",
                            entries =
                                listOf(
                                    MediaCommittedEntry(digest = digest, absolutePath = file.absolutePath),
                                ),
                        ),
                )
            val store =
                FakeStorePort(
                    pages =
                        listOf(
                            StoreMemoPage(
                                items =
                                    listOf(
                                        summary(
                                            memoId = "m1",
                                            isTrashed = false,
                                            imageUrls = listOf("media/live.png"),
                                        ),
                                    ),
                                nextCursor = null,
                                highWaterRevision = 1,
                                queryFingerprint = "fp",
                            ),
                        ),
                )
            val edge = edge(root = root, port = port, store = store)

            runBlocking { edge.removeImage(MediaEntryId("live.png")) }

            val call = port.sweepCalls.single()
            call.refs.map { it.digest } shouldContain digest
            call.refs.single().source shouldBe "current"
            call.refs.single().ownerKey shouldBe "m1"
        }

        test("given trash memo attachment when sweep then source is trash") {
            val root = createTempDirectory("lomo-media-edge-trash-").toFile()
            val mediaDir = File(root, "media").apply { mkdirs() }
            val file = File(mediaDir, "t.png").apply { writeText("t") }
            val digest = "c".repeat(64)
            val port =
                RecordingMediaPort(
                    manifest =
                        MediaManifest(
                            stageDirName = ".lomo-media-stage",
                            entries =
                                listOf(
                                    MediaCommittedEntry(digest = digest, absolutePath = file.absolutePath),
                                ),
                        ),
                )
            val store =
                FakeStorePort(
                    pages =
                        listOf(
                            StoreMemoPage(
                                items =
                                    listOf(
                                        summary(
                                            memoId = "trash-1",
                                            isTrashed = true,
                                            imageUrls = listOf("t.png"),
                                        ),
                                    ),
                                nextCursor = null,
                                highWaterRevision = 1,
                                queryFingerprint = "fp",
                            ),
                        ),
                )
            val edge = edge(root = root, port = port, store = store)

            runBlocking { edge.runOrphanSweepAtOperationBoundary() }

            port.sweepCalls.single().refs.single().source shouldBe "trash"
        }

        test("given history-only attachment when sweep then source is history") {
            val root = createTempDirectory("lomo-media-edge-hist-").toFile()
            val mediaDir = File(root, "media").apply { mkdirs() }
            val file = File(mediaDir, "hist.png").apply { writeText("hist") }
            val digest = "d".repeat(64)
            val port =
                RecordingMediaPort(
                    manifest =
                        MediaManifest(
                            stageDirName = ".lomo-media-stage",
                            entries =
                                listOf(
                                    MediaCommittedEntry(digest = digest, absolutePath = file.absolutePath),
                                ),
                        ),
                )
            val store =
                FakeStorePort(
                    pages =
                        listOf(
                            StoreMemoPage(
                                items = emptyList(),
                                nextCursor = null,
                                highWaterRevision = 1,
                                queryFingerprint = "fp",
                            ),
                        ),
                    historyRefs =
                        listOf(
                            StoreHistoryAttachmentRef(
                                memoId = "m-hist",
                                revision = 3,
                                relativePath = "media/hist.png",
                                ownerKey = "m-hist@r3",
                            ),
                        ),
                )
            val edge = edge(root = root, port = port, store = store)

            runBlocking { edge.runOrphanSweepAtOperationBoundary() }

            val ref = port.sweepCalls.single().refs.single()
            ref.digest shouldBe digest
            ref.source shouldBe "history"
            ref.ownerKey shouldBe "m-hist@r3"
        }

        test("given zero-ref removeImage when sweep reports trash move then permanentlyDeleted empty") {
            val root = createTempDirectory("lomo-media-edge-fs-").toFile()
            val mediaDir = File(root, "media").apply { mkdirs() }
            val file = File(mediaDir, "gone.png").apply { writeText("gone") }
            val digest = "e".repeat(64)
            val trashPath = File(mediaDir, ".lomo-media-trash/${digest}_1000_gone.png").absolutePath
            val port =
                RecordingMediaPort(
                    manifest =
                        MediaManifest(
                            stageDirName = ".lomo-media-stage",
                            entries =
                                listOf(
                                    MediaCommittedEntry(digest = digest, absolutePath = file.absolutePath),
                                ),
                        ),
                    sweepResult =
                        MediaOrphanSweepResult(
                            movedToTrash =
                                listOf(
                                    MediaTrashEntry(
                                        digest = digest,
                                        trashPath = trashPath,
                                        trashedAtMs = 1_000L,
                                        expiresAtMs = 2_000L,
                                    ),
                                ),
                            permanentlyDeletedDigests = emptyList(),
                            keptLive = 0,
                        ),
                )
            val store =
                FakeStorePort(
                    pages =
                        listOf(
                            StoreMemoPage(
                                items = emptyList(),
                                nextCursor = null,
                                highWaterRevision = 1,
                                queryFingerprint = "fp",
                            ),
                        ),
                )
            val edge = edge(root = root, port = port, store = store)

            runBlocking { edge.removeImage(MediaEntryId("gone.png")) }

            val result = port.lastSweepResult
            result.movedToTrash.map { it.digest } shouldContain digest
            result.permanentlyDeletedDigests shouldBe emptyList()
        }

        test("given no Direct workspace root when removeImage then sweep is skipped") {
            val port = RecordingMediaPort(manifest = MediaManifest(stageDirName = "s", entries = emptyList()))
            val edge =
                edge(
                    root = null,
                    port = port,
                    store = FakeStorePort(pages = emptyList()),
                )

            runBlocking { edge.removeImage(MediaEntryId("x.png")) }

            port.sweepCalls.size shouldBe 0
        }
    }

    private fun edge(
        root: File?,
        port: MediaPort,
        store: StorePort,
    ): MediaEdgeRepository {
        val context = mockk<Context>(relaxed = true)
        every { context.cacheDir } returns createTempDirectory("cache-").toFile()
        val workspaceConfig = mockk<WorkspaceConfigSource>(relaxed = true)
        val storage = mockk<MediaStorageDataSource>(relaxed = true)
        val s3 = mockk<S3LocalChangeRecorder>(relaxed = true)
        val webDav = mockk<WebDavLocalChangeRecorder>(relaxed = true)
        coEvery { s3.recordImageDelete(any()) } returns Unit
        coEvery { webDav.recordImageDelete(any()) } returns Unit
        coEvery { s3.recordVoiceDelete(any()) } returns Unit
        coEvery { webDav.recordVoiceDelete(any()) } returns Unit
        val writeAuthority = mockk<WorkspaceWriteAuthority>(relaxed = true)
        every { writeAuthority.requireWritable() } returns Unit
        return MediaEdgeRepository(
            context = context,
            workspaceConfigSource = workspaceConfig,
            mediaStorageDataSource = storage,
            mediaPort = port,
            workspaceRoot = WorkspaceFilesystemRoot { root?.absolutePath },
            syncEdge = MediaSyncEdgeAdapter(s3LocalChangeRecorder = s3, webDavLocalChangeRecorder = webDav),
            writeAuthority = writeAuthority,
            storePort = store,
            pendingStages = com.lomo.data.engine.media.PendingMediaStageRegistry(),
            clockMs = { 1_000L },
            recoveryWindowMs = 1_000L,
        )
    }

    private fun summary(
        memoId: String,
        isTrashed: Boolean,
        imageUrls: List<String>,
    ): StoreMemoSummary =
        StoreMemoSummary(
            memoId = memoId,
            sourcePath = "$memoId.md",
            fileFingerprint = "fp",
            updatedAtMs = 0,
            createdAtMs = 0,
            hasTodo = false,
            hasUrl = false,
            hasAttachment = imageUrls.isNotEmpty(),
            isPinned = false,
            isTrashed = isTrashed,
            bodyPreview = "",
            contentRevision = 1,
            imageUrls = imageUrls,
        )

    private data class SweepCall(
        val mediaRoot: String,
        val committed: List<MediaCommittedEntry>,
        val refs: List<MediaAttachmentRef>,
        val existingTrash: List<MediaTrashEntry>,
        val nowMs: Long?,
        val recoveryWindowMs: Long,
    )

    private class RecordingMediaPort(
        private val manifest: MediaManifest,
        private val sweepResult: MediaOrphanSweepResult =
            MediaOrphanSweepResult(
                movedToTrash = emptyList(),
                permanentlyDeletedDigests = emptyList(),
                keptLive = 0,
            ),
    ) : MediaPort {
        val sweepCalls = mutableListOf<SweepCall>()
        var lastSweepResult: MediaOrphanSweepResult = sweepResult

        override fun stageMedia(
            mediaRoot: String,
            sourceKind: MediaSourceKind,
            sourcePath: String,
            humanNameHint: String,
        ): MediaStagedFacts = error("unused")

        override fun allocateRecordingTarget(
            mediaRoot: String,
            extension: String,
        ): String = error("unused")

        override fun finalizeRecording(
            mediaRoot: String,
            recordingPath: String,
            humanNameHint: String,
        ): MediaStagedFacts = error("unused")

        override fun promoteMedia(
            workspaceRoot: String,
            plan: MediaPromotePlan,
        ): MediaPromoteResult = error("unused")

        override fun queryMediaManifest(workspaceRoot: String): MediaManifest = manifest

        override fun mediaOrphanSweep(
            mediaRoot: String,
            committed: List<MediaCommittedEntry>,
            refs: List<MediaAttachmentRef>,
            existingTrash: List<MediaTrashEntry>,
            nowMs: Long?,
            recoveryWindowMs: Long,
        ): MediaOrphanSweepResult {
            sweepCalls +=
                SweepCall(
                    mediaRoot = mediaRoot,
                    committed = committed,
                    refs = refs,
                    existingTrash = existingTrash,
                    nowMs = nowMs,
                    recoveryWindowMs = recoveryWindowMs,
                )
            lastSweepResult = sweepResult
            return sweepResult
        }
    }

    private class FakeStorePort(
        private val pages: List<StoreMemoPage>,
        private val historyRefs: List<StoreHistoryAttachmentRef> = emptyList(),
    ) : StorePort {
        private var index = 0

        override fun queryMemos(
            query: StoreMemoQuery,
            cursor: StorePageCursor?,
            pageSize: Int,
        ): StoreMemoPage {
            if (pages.isEmpty()) {
                return StoreMemoPage(
                    items = emptyList(),
                    nextCursor = null,
                    highWaterRevision = 0,
                    queryFingerprint = "empty",
                )
            }
            val page = pages.getOrElse(index) { pages.last() }
            index += 1
            return page
        }

        override fun getMemo(memoId: String): StoreMemoSnapshot? = null

        override fun listHistoryAttachmentRefs(): List<StoreHistoryAttachmentRef> = historyRefs

        override fun applyMemoCommand(command: StoreMemoCommand): StoreMemoCommit = error("unused")

        override fun startRebuild(batchSize: Int): StoreRebuildResult = error("unused")
    }
}
