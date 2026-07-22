package com.lomo.data.repository

/**
 * Behavior Contract:
 * - Unit under test: MediaEdgeRepository.importImage + draft removeImage stage drop +
 *   StoreMemoMutationRepository pendingPromotes wiring (D4 / D8).
 * - Owning layer: data production media edge + memo mutation edge.
 * - Priority tier: P0.
 * - Capability: import = stage+verify only; staged facts held in PendingMediaStageRegistry;
 *   memo save/update fills StoreMemoCommand.pendingPromotes under same operation-id;
 *   sync upsert only after memo-bound promote; content:// never passed as Rust path (A4).
 *
 * Scenarios:
 * - Given a file source, when importImage runs, then stageMedia is called and promoteMedia is not;
 *   registry holds suggestedFinalRelativePath; return value is that relative path.
 * - Given a content:// URI, when importImage runs, then stage uses StagedTemp on a private temp path
 *   (not the content URI string) with bounded copy.
 * - Given staged import, when removeImage runs before save, then stage is dropped without orphan
 *   sweep of committed media.
 * - Given staged import destination in body, when saveMemo runs, then command.pendingPromotes is
 *   non-empty with matching operationId and sync upsert is journaled for basenames.
 * - Given finalizeVoiceCapture, when recording stops, then finalizeRecording only (no promote) and
 *   registry holds suggestedFinalRelativePath; saveMemo pendingPromotes share operationId.
 * - Given takePlans then applyMemoCommand fails, when saveMemo throws, then stages are re-put for retry.
 * - Given uncommitted capture name (no registry entry), when removeVoiceCapture runs, then stage files
 *   may be deleted but sync delete is never journaled (D4/D8 fail-closed).
 * - Given finalized voice stage in registry, when removeVoiceCapture runs, then stage drops without
 *   sync delete journal.
 *
 * Observable outcomes: MediaPort call lists; StoreMemoCommand.pendingPromotes; sync recorder calls.
 *
 * TDD proof:
 * - Target: ./kotlin test --include-module=data --include-classes='com.lomo.data.repository.MediaEdgeImportStageOnlyTest'
 * - RED: import promoted immediately with a random UUID; content:// could be passed as a Rust path.
 * - GREEN: import only stages; pendingPromotes share memo operation-id; content:// uses bounded StagedTemp.
 *
 * Excludes: real JNI, real SAF DocumentFile trees, archive paths.
 */

import android.content.Context
import android.net.Uri
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
import com.lomo.data.engine.media.PendingMediaStageRegistry
import com.lomo.data.engine.media.WorkspaceFilesystemRoot
import com.lomo.data.engine.store.StoreHistoryAttachmentRef
import com.lomo.data.engine.store.StoreMemoCommand
import com.lomo.data.engine.store.StoreMemoCommandKind
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
import com.lomo.data.testing.fakes.FakeReminderCoordinator
import com.lomo.domain.model.MediaEntryId
import com.lomo.domain.model.StorageLocation
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.io.path.createTempDirectory

class MediaEdgeImportStageOnlyTest : DataFunSpec() {
    init {
        test("given file source when importImage then stage only and registry holds promote facts") {
            val root = createTempDirectory("lomo-stage-import-").toFile()
            val sourceFile = File(root, "photo.png").apply { writeBytes(PNG_1X1) }
            val stageDir = File(root, ".lomo-media-stage").apply { mkdirs() }
            val stagedPath = File(stageDir, "d".repeat(64) + ".png").apply { writeBytes(PNG_1X1) }
            val port =
                RecordingMediaPort(
                    staged =
                        MediaStagedFacts(
                            digest = "d".repeat(64),
                            size = PNG_1X1.size.toLong(),
                            mime = "image/png",
                            stagingPath = stagedPath.absolutePath,
                            humanNameHint = "photo.png",
                            suggestedFinalRelativePath = "media/photo.png",
                        ),
                )
            val registry = PendingMediaStageRegistry()
            val edge = edge(root = root, port = port, registry = registry)

            val location =
                runBlocking {
                    edge.importImage(StorageLocation(sourceFile.absolutePath))
                }

            location.raw shouldBe "media/photo.png"
            port.stageCalls shouldHaveSize 1
            port.promoteCalls.shouldBeEmpty()
            registry.get("media/photo.png") shouldNotBe null
            registry.get("photo.png") shouldNotBe null
        }

        test("given content URI when importImage then StagedTemp private path not content scheme") {
            val root = createTempDirectory("lomo-stage-content-").toFile()
            val cache = createTempDirectory("cache-").toFile()
            val stageDir = File(root, ".lomo-media-stage").apply { mkdirs() }
            val stagedPath = File(stageDir, "e".repeat(64) + ".png").apply { writeBytes(PNG_1X1) }
            val port =
                RecordingMediaPort(
                    staged =
                        MediaStagedFacts(
                            digest = "e".repeat(64),
                            size = PNG_1X1.size.toLong(),
                            mime = "image/png",
                            stagingPath = stagedPath.absolutePath,
                            humanNameHint = "from-gallery.png",
                            suggestedFinalRelativePath = "media/from-gallery.png",
                        ),
                )
            val registry = PendingMediaStageRegistry()
            val contentUri = mockk<Uri>(relaxed = true)
            every { contentUri.scheme } returns "content"
            every { contentUri.lastPathSegment } returns "from-gallery.png"
            mockkStatic(Uri::class)
            try {
                every { Uri.parse(match { it.startsWith("content:") }) } returns contentUri
                val edge = edge(root = root, port = port, registry = registry, cacheDir = cache)
                runBlocking {
                    edge.importImage(StorageLocation("content://media/external/images/media/42"))
                }
            } finally {
                unmockkStatic(Uri::class)
            }

            port.stageCalls shouldHaveSize 1
            val call = port.stageCalls.single()
            call.sourceKind shouldBe MediaSourceKind.StagedTemp
            call.sourcePath shouldStartWith cache.absolutePath
            call.sourcePath shouldNotContain "content:"
            port.promoteCalls.shouldBeEmpty()
        }

        test("given staged import when removeImage then stage dropped without orphan sweep") {
            val root = createTempDirectory("lomo-stage-discard-").toFile()
            val sourceFile = File(root, "draft.png").apply { writeBytes(PNG_1X1) }
            val stageDir = File(root, ".lomo-media-stage").apply { mkdirs() }
            val stagedPath = File(stageDir, "f".repeat(64) + ".png").apply { writeBytes(PNG_1X1) }
            val port =
                RecordingMediaPort(
                    staged =
                        MediaStagedFacts(
                            digest = "f".repeat(64),
                            size = PNG_1X1.size.toLong(),
                            mime = "image/png",
                            stagingPath = stagedPath.absolutePath,
                            humanNameHint = "draft.png",
                            suggestedFinalRelativePath = "media/draft.png",
                        ),
                )
            val registry = PendingMediaStageRegistry()
            val edge = edge(root = root, port = port, registry = registry)

            runBlocking {
                edge.importImage(StorageLocation(sourceFile.absolutePath))
                edge.removeImage(MediaEntryId("media/draft.png"))
            }

            registry.get("media/draft.png") shouldBe null
            port.promoteCalls.shouldBeEmpty()
            port.sweepCalls.shouldBeEmpty()
            stagedPath.exists() shouldBe false
        }

        test("given staged destination in body when saveMemo then pendingPromotes share operationId") {
            val registry = PendingMediaStageRegistry()
            val staged =
                MediaStagedFacts(
                    digest = "a".repeat(64),
                    size = 8,
                    mime = "image/png",
                    stagingPath = "/tmp/stage.png",
                    humanNameHint = "shot.png",
                    suggestedFinalRelativePath = "media/shot.png",
                )
            registry.put(staged)
            val port = MutationRecordingStorePort()
            val s3 = RecordingS3()
            val webDav = RecordingWebDav()
            val mutation =
                StoreMemoMutationRepository(
                    port = port,
                    queryRepository = mockk(relaxed = true),
                    reminderScheduler = FakeReminderCoordinator(),
                    writeAuthority = AlwaysWritableWorkspaceWriteAuthority,
                    invalidation = StoreInvalidationBus(),
                    pendingStages = registry,
                    syncEdge =
                        MediaSyncEdgeAdapter(
                            s3LocalChangeRecorder = s3,
                            webDavLocalChangeRecorder = webDav,
                        ),
                )

            runBlocking {
                mutation.saveMemo(
                    content = "note ![shot](media/shot.png)",
                    timestamp = 1L,
                    geoLocation = null,
                )
            }

            val cmd = port.commands.single()
            cmd.kind shouldBe StoreMemoCommandKind.Create
            cmd.pendingPromotes shouldHaveSize 1
            cmd.pendingPromotes.single().operationId shouldBe cmd.operationId
            cmd.pendingPromotes.single().finalRelativePath shouldBe "media/shot.png"
            registry.get("media/shot.png") shouldBe null
            s3.upserts shouldBe listOf("shot.png")
            webDav.upserts shouldBe listOf("shot.png")
        }

        test("given voice finalize when saveMemo then pendingPromotes share operationId and no promote on finalize") {
            val root = createTempDirectory("lomo-stage-voice-").toFile()
            val stageDir = File(root, ".lomo-media-stage").apply { mkdirs() }
            val capture = File(stageDir, "rec.m4a").apply { writeBytes(byteArrayOf(0, 0, 0, 0)) }
            val stagedPath = File(stageDir, "v".repeat(64) + ".m4a").apply { writeBytes(byteArrayOf(1)) }
            val port =
                RecordingMediaPort(
                    staged =
                        MediaStagedFacts(
                            digest = "v".repeat(64),
                            size = 1,
                            mime = "audio/mp4",
                            stagingPath = stagedPath.absolutePath,
                            humanNameHint = "voice_20260101_120000.m4a",
                            suggestedFinalRelativePath = "media/voice_20260101_120000.m4a",
                        ),
                )
            val registry = PendingMediaStageRegistry()
            val edge = edge(root = root, port = port, registry = registry)

            val dest =
                runBlocking {
                    edge.finalizeVoiceCapture(
                        recordingLocation = StorageLocation(capture.absolutePath),
                        humanNameHint = "voice_20260101_120000.m4a",
                    )
                }

            dest.raw shouldBe "media/voice_20260101_120000.m4a"
            port.finalizeCalls shouldHaveSize 1
            port.promoteCalls.shouldBeEmpty()
            registry.get("media/voice_20260101_120000.m4a") shouldNotBe null

            val store = MutationRecordingStorePort()
            val mutation =
                StoreMemoMutationRepository(
                    port = store,
                    queryRepository = mockk(relaxed = true),
                    reminderScheduler = FakeReminderCoordinator(),
                    writeAuthority = AlwaysWritableWorkspaceWriteAuthority,
                    invalidation = StoreInvalidationBus(),
                    pendingStages = registry,
                    syncEdge = null,
                )
            runBlocking {
                mutation.saveMemo(
                    content = "![voice](media/voice_20260101_120000.m4a)",
                    timestamp = 1L,
                    geoLocation = null,
                )
            }
            val cmd = store.commands.single()
            cmd.pendingPromotes shouldHaveSize 1
            cmd.pendingPromotes.single().operationId shouldBe cmd.operationId
            cmd.pendingPromotes.single().finalRelativePath shouldBe "media/voice_20260101_120000.m4a"
            port.promoteCalls.shouldBeEmpty()
        }

        test("given takePlans when applyMemoCommand fails then stages re-put for retry") {
            val registry = PendingMediaStageRegistry()
            val staged =
                MediaStagedFacts(
                    digest = "b".repeat(64),
                    size = 8,
                    mime = "image/png",
                    stagingPath = "/tmp/stage-fail.png",
                    humanNameHint = "fail.png",
                    suggestedFinalRelativePath = "media/fail.png",
                )
            registry.put(staged)
            val port = FailingMutationStorePort()
            val mutation =
                StoreMemoMutationRepository(
                    port = port,
                    queryRepository = mockk(relaxed = true),
                    reminderScheduler = FakeReminderCoordinator(),
                    writeAuthority = AlwaysWritableWorkspaceWriteAuthority,
                    invalidation = StoreInvalidationBus(),
                    pendingStages = registry,
                    syncEdge = null,
                )

            val thrown =
                runCatching {
                    runBlocking {
                        mutation.saveMemo(
                            content = "note ![x](media/fail.png)",
                            timestamp = 1L,
                            geoLocation = null,
                        )
                    }
                }.exceptionOrNull()
            thrown shouldNotBe null
            // Stage available again for retry under a new operation-id.
            registry.get("media/fail.png") shouldNotBe null
            registry.get("fail.png") shouldNotBe null
        }

        test("given uncommitted capture name when removeVoiceCapture then no sync delete journal") {
            val root = createTempDirectory("lomo-voice-discard-").toFile()
            val stageDir = File(root, ".lomo-media-stage").apply { mkdirs() }
            val capture = File(stageDir, "voice_partial.m4a").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val port =
                RecordingMediaPort(
                    staged =
                        MediaStagedFacts(
                            digest = "v".repeat(64),
                            size = 3,
                            mime = "audio/mp4",
                            stagingPath = capture.absolutePath,
                            humanNameHint = "voice_partial.m4a",
                            suggestedFinalRelativePath = "media/voice_partial.m4a",
                        ),
                )
            val registry = PendingMediaStageRegistry()
            val s3 = RecordingS3()
            val webDav = RecordingWebDav()
            val edge = edge(root = root, port = port, registry = registry, s3 = s3, webDav = webDav)

            runBlocking { edge.removeVoiceCapture(MediaEntryId("voice_partial.m4a")) }

            s3.deletes.shouldBeEmpty()
            webDav.deletes.shouldBeEmpty()
            port.sweepCalls.shouldBeEmpty()
            capture.exists() shouldBe false
        }

        test("given finalized voice stage when removeVoiceCapture then stage drop without journal") {
            val root = createTempDirectory("lomo-voice-stage-drop-").toFile()
            val stageDir = File(root, ".lomo-media-stage").apply { mkdirs() }
            val stagedPath = File(stageDir, "v".repeat(64) + ".m4a").apply { writeBytes(byteArrayOf(9)) }
            val staged =
                MediaStagedFacts(
                    digest = "v".repeat(64),
                    size = 1,
                    mime = "audio/mp4",
                    stagingPath = stagedPath.absolutePath,
                    humanNameHint = "voice_20260101_120000.m4a",
                    suggestedFinalRelativePath = "media/voice_20260101_120000.m4a",
                )
            val port = RecordingMediaPort(staged = staged)
            val registry = PendingMediaStageRegistry().also { it.put(staged) }
            val s3 = RecordingS3()
            val webDav = RecordingWebDav()
            val edge = edge(root = root, port = port, registry = registry, s3 = s3, webDav = webDav)

            runBlocking { edge.removeVoiceCapture(MediaEntryId("media/voice_20260101_120000.m4a")) }

            registry.get("media/voice_20260101_120000.m4a") shouldBe null
            s3.deletes.shouldBeEmpty()
            webDav.deletes.shouldBeEmpty()
            port.sweepCalls.shouldBeEmpty()
            stagedPath.exists() shouldBe false
        }
    }

    private fun edge(
        root: File?,
        port: MediaPort,
        registry: PendingMediaStageRegistry,
        cacheDir: File = createTempDirectory("cache-").toFile(),
        s3: RecordingS3 = RecordingS3(),
        webDav: RecordingWebDav = RecordingWebDav(),
    ): MediaEdgeRepository {
        val context = mockk<Context>(relaxed = true)
        every { context.cacheDir } returns cacheDir
        every { context.filesDir } returns createTempDirectory("files-").toFile()
        every { context.contentResolver.openInputStream(any()) } answers {
            ByteArrayInputStream(PNG_1X1)
        }
        val workspaceConfig = mockk<WorkspaceConfigSource>(relaxed = true)
        val storage = mockk<MediaStorageDataSource>(relaxed = true)
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
            storePort = EmptyStorePort,
            pendingStages = registry,
            clockMs = { 1_000L },
            recoveryWindowMs = 1_000L,
        )
    }

    private data class StageCall(
        val mediaRoot: String,
        val sourceKind: MediaSourceKind,
        val sourcePath: String,
        val humanNameHint: String,
    )

    private class RecordingMediaPort(
        private val staged: MediaStagedFacts,
    ) : MediaPort {
        val stageCalls = mutableListOf<StageCall>()
        val finalizeCalls = mutableListOf<Pair<String, String>>()
        val promoteCalls = mutableListOf<MediaPromotePlan>()
        val sweepCalls = mutableListOf<String>()

        override fun stageMedia(
            mediaRoot: String,
            sourceKind: MediaSourceKind,
            sourcePath: String,
            humanNameHint: String,
        ): MediaStagedFacts {
            stageCalls += StageCall(mediaRoot, sourceKind, sourcePath, humanNameHint)
            return staged
        }

        override fun allocateRecordingTarget(
            mediaRoot: String,
            extension: String,
        ): String = error("unused")

        override fun finalizeRecording(
            mediaRoot: String,
            recordingPath: String,
            humanNameHint: String,
        ): MediaStagedFacts {
            finalizeCalls += recordingPath to humanNameHint
            return staged
        }

        override fun promoteMedia(
            workspaceRoot: String,
            plan: MediaPromotePlan,
        ): MediaPromoteResult {
            promoteCalls += plan
            error("production import/recording must not call promoteMedia")
        }

        override fun queryMediaManifest(workspaceRoot: String): MediaManifest =
            MediaManifest(stageDirName = ".lomo-media-stage", entries = emptyList())

        override fun mediaOrphanSweep(
            mediaRoot: String,
            committed: List<MediaCommittedEntry>,
            refs: List<MediaAttachmentRef>,
            existingTrash: List<MediaTrashEntry>,
            nowMs: Long?,
            recoveryWindowMs: Long,
        ): MediaOrphanSweepResult {
            sweepCalls += mediaRoot
            return MediaOrphanSweepResult(emptyList(), emptyList(), 0)
        }
    }

    private object EmptyStorePort : StorePort {
        override fun queryMemos(
            query: StoreMemoQuery,
            cursor: StorePageCursor?,
            pageSize: Int,
        ): StoreMemoPage = StoreMemoPage(emptyList(), null, 0, "empty")

        override fun getMemo(memoId: String): StoreMemoSnapshot? = null

        override fun applyMemoCommand(command: StoreMemoCommand): StoreMemoCommit = error("unused")

        override fun startRebuild(batchSize: Int): StoreRebuildResult =
            StoreRebuildResult(0, 0, 0, "", "", 0, 0)

        override fun listHistoryAttachmentRefs(): List<StoreHistoryAttachmentRef> = emptyList()
    }

    private class MutationRecordingStorePort : StorePort {
        val commands = mutableListOf<StoreMemoCommand>()

        override fun queryMemos(
            query: StoreMemoQuery,
            cursor: StorePageCursor?,
            pageSize: Int,
        ): StoreMemoPage = StoreMemoPage(emptyList(), null, 0, "empty")

        override fun getMemo(memoId: String): StoreMemoSnapshot? {
            val id = memoId.ifBlank { "created-1" }
            return StoreMemoSnapshot(
                summary =
                    StoreMemoSummary(
                        memoId = id,
                        sourcePath = "m.md",
                        fileFingerprint = "fp",
                        updatedAtMs = 0,
                        createdAtMs = 0,
                        hasTodo = false,
                        hasUrl = false,
                        hasAttachment = false,
                        isPinned = false,
                        isTrashed = false,
                        bodyPreview = "body",
                        contentRevision = 1,
                        imageUrls = emptyList(),
                        tags = emptyList(),
                    ),
                body = "body",
            )
        }

        override fun applyMemoCommand(command: StoreMemoCommand): StoreMemoCommit {
            commands += command
            val id = if (command.memoId.isBlank()) "created-1" else command.memoId
            return StoreMemoCommit(
                operationId = command.operationId,
                memoId = id,
                coreRevision = 1L,
                eventSequence = 1L,
                contentRevision = 1L,
                fileFingerprint = "fp",
                scopes = listOf("memo:$id"),
                idempotentReplay = false,
            )
        }

        override fun startRebuild(batchSize: Int): StoreRebuildResult =
            StoreRebuildResult(0, 0, 0, "", "", 0, 0)

        override fun listHistoryAttachmentRefs(): List<StoreHistoryAttachmentRef> = emptyList()
    }

    private class FailingMutationStorePort : StorePort {
        override fun queryMemos(
            query: StoreMemoQuery,
            cursor: StorePageCursor?,
            pageSize: Int,
        ): StoreMemoPage = StoreMemoPage(emptyList(), null, 0, "empty")

        override fun getMemo(memoId: String): StoreMemoSnapshot? = null

        override fun applyMemoCommand(command: StoreMemoCommand): StoreMemoCommit =
            error("forced apply failure for re-stage contract")

        override fun startRebuild(batchSize: Int): StoreRebuildResult =
            StoreRebuildResult(0, 0, 0, "", "", 0, 0)

        override fun listHistoryAttachmentRefs(): List<StoreHistoryAttachmentRef> = emptyList()
    }

    private class RecordingS3 : S3LocalChangeRecorder {
        val upserts = mutableListOf<String>()
        val deletes = mutableListOf<String>()

        override suspend fun recordMemoUpsert(filename: String) = Unit

        override suspend fun recordMemoDelete(filename: String) = Unit

        override suspend fun recordImageUpsert(filename: String) {
            upserts += filename
        }

        override suspend fun recordImageDelete(filename: String) {
            deletes += filename
        }

        override suspend fun recordVoiceUpsert(filename: String) = Unit

        override suspend fun recordVoiceDelete(filename: String) {
            deletes += filename
        }
    }

    private class RecordingWebDav : WebDavLocalChangeRecorder {
        val upserts = mutableListOf<String>()
        val deletes = mutableListOf<String>()

        override suspend fun recordMemoUpsert(filename: String) = Unit

        override suspend fun recordMemoDelete(filename: String) = Unit

        override suspend fun recordImageUpsert(filename: String) {
            upserts += filename
        }

        override suspend fun recordImageDelete(filename: String) {
            deletes += filename
        }

        override suspend fun recordVoiceUpsert(filename: String) = Unit

        override suspend fun recordVoiceDelete(filename: String) {
            deletes += filename
        }
    }

    companion object {
        // Minimal PNG magic so copy bounds tests stay tiny.
        private val PNG_1X1 =
            byteArrayOf(
                0x89.toByte(),
                0x50,
                0x4E,
                0x47,
                0x0D,
                0x0A,
                0x1A,
                0x0A,
            )
    }
}
