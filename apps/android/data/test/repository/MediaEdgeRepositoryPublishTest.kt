package com.lomo.data.repository

import com.lomo.data.engine.media.MediaCommittedEntry
import com.lomo.data.engine.media.MediaManifest
import com.lomo.data.engine.media.MediaPort
import com.lomo.data.engine.media.MediaPromotePlan
import com.lomo.data.engine.media.MediaStagedFacts
import com.lomo.data.engine.media.PendingMediaStageRegistry
import com.lomo.data.engine.store.StoreMemoBatchCommit
import com.lomo.data.engine.store.StoreMemoCommand
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
import com.lomo.data.testing.DataFunSpec
import com.lomo.data.testing.fakes.FakeFileDataSource
import com.lomo.domain.model.MediaEntryId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import java.io.File
import kotlin.io.path.createTempDirectory

/*
 * Behavior Contract:
 * - Unit under test: MediaEdgeRepository committed-media publication.
 * - Owning layer: data.
 * - Priority tier: P1.
 * - Capability: the commit receipt carries final relative paths and witnessed digests, so the
 *   location owner learns each final location incrementally; the next manifest walk feeds the
 *   last witnessed entries back as weak digest-reuse hints.
 *
 * Scenarios:
 * - Given a memo commit promoted an image, when the receipt publishes the plan, then the image
 *   map resolves the basename to the final location under the witnessed content identity.
 * - Given a publish whose final file is absent, when the receipt arrives, then no location is
 *   fabricated for that basename.
 * - Given a completed manifest walk, when the next refresh runs, then the previous entries are
 *   handed back to the port as verified hints.
 *
 * Observable outcomes: map values' location/contentId, captured manifest-hint arguments.
 *
 * TDD proof: fails if publish is absent, fabricates locations, or drops the hint round-trip.
 *
 * Excludes: stage ledger semantics, Coil decoding, real SAF tree URIs.
 */
class MediaEdgeRepositoryPublishTest : DataFunSpec() {
    init {
        test("commit publish replaces the staged preview with the final location and content id") {
            val workspace = createTempDirectory(prefix = "lomo-media-publish-").toFile()
            val mediaDir = File(workspace, "media").apply { mkdirs() }
            val finalFile = File(mediaDir, "img_1.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val repository = mediaEdgeRepository(workspace)

            repository.publishCommittedMedia(
                listOf(
                    MediaPromotePlan(
                        operationId = "op-1",
                        staged = stagedFacts(digest = "a".repeat(64)),
                        finalRelativePath = "media/img_1.png",
                    ),
                ),
            )

            val descriptor = repository.observeImageLocations().first()[MediaEntryId("img_1.png")]
            descriptor shouldNotBe null
            descriptor!!.location.raw shouldBe "file://${finalFile.absolutePath}"
            descriptor.contentId shouldBe "a".repeat(64)
        }

        test("commit publish skips a plan whose final file does not exist") {
            val workspace = createTempDirectory(prefix = "lomo-media-publish-").toFile()
            File(workspace, "media").mkdirs()
            val repository = mediaEdgeRepository(workspace)

            repository.publishCommittedMedia(
                listOf(
                    MediaPromotePlan(
                        operationId = "op-2",
                        staged = stagedFacts(digest = "b".repeat(64)),
                        finalRelativePath = "media/missing.png",
                    ),
                ),
            )

            repository.observeImageLocations().first()[MediaEntryId("missing.png")] shouldBe null
        }

        test("manifest refresh feeds the previous witnessed entries back as verified hints") {
            val workspace = createTempDirectory(prefix = "lomo-media-publish-").toFile()
            val mediaDir = File(workspace, "media").apply { mkdirs() }
            val file = File(mediaDir, "img_1.png").apply { writeBytes(byteArrayOf(4, 5, 6)) }
            val port = RecordingManifestMediaPort(file)
            val repository = mediaEdgeRepository(workspace, mediaPort = port)

            repository.refreshImageLocations()
            port.seenVerifiedEntries.last() shouldBe emptyList()
            repository.refreshImageLocations()
            port.seenVerifiedEntries.last().map { it.digest } shouldBe listOf("c".repeat(64))
        }
    }

    private fun mediaEdgeRepository(
        workspace: File,
        mediaPort: MediaPort = NoOpMediaPort(),
    ): MediaEdgeRepository {
        val fileDataSource = FakeFileDataSource()
        return MediaEdgeRepository(
            dependencies =
                MediaEdgeRepositoryDependencies(
                    context = mockk(),
                    workspaceConfigSource = fileDataSource,
                    mediaStorageDataSource = fileDataSource,
                    mediaPort = mediaPort,
                    workspaceRoot = { workspace.absolutePath },
                    stageRoot = { workspace.absolutePath },
                    writeLease = alwaysWritableWorkspaceMutationLease(),
                    storePort = UnexpectedStorePort(),
                ),
            pendingStages = PendingMediaStageRegistry(mediaPort) { workspace.absolutePath },
        )
    }

    private fun stagedFacts(digest: String): MediaStagedFacts =
        MediaStagedFacts(
            digest = digest,
            size = 3,
            mime = "image/png",
            stagingPath = "/stage/img_1.png",
            humanNameHint = "img_1.png",
            suggestedFinalRelativePath = "media/img_1.png",
        )
}

private class RecordingManifestMediaPort(
    private val file: File,
) : MediaPort by NoOpMediaPort() {
    val seenVerifiedEntries = mutableListOf<List<MediaCommittedEntry>>()

    override fun queryMediaManifest(
        workspaceRoot: String,
        verifiedEntries: List<MediaCommittedEntry>,
    ): MediaManifest {
        seenVerifiedEntries += verifiedEntries
        return MediaManifest(
            stageDirName = ".lomo-media-stage",
            entries =
                listOf(
                    MediaCommittedEntry(
                        digest = "c".repeat(64),
                        absolutePath = file.absolutePath,
                        size = file.length(),
                        modifiedMs = file.lastModified(),
                    ),
                ),
        )
    }
}

private class UnexpectedStorePort : StorePort {
    private fun unexpected(): Nothing = error("unexpected store call in media publish test")

    override fun queryMemos(
        query: StoreMemoQuery,
        cursor: StorePageCursor?,
        pageSize: Int,
        startMemoId: String?,
        backward: Boolean,
    ): StoreMemoPage = unexpected()

    override fun getMemo(memoId: String): StoreMemoSnapshot? = unexpected()

    override fun queryCount(query: StoreMemoQuery): Long = unexpected()

    override fun sidebarProjection(): StoreSidebarProjection = unexpected()

    override fun queryReminderPlan(nowUtcMs: Long): StoreReminderPlan = unexpected()

    override fun applyMemoCommand(
        command: StoreMemoCommand,
        onPublication: (StoreMemoCommit) -> Unit,
    ): StoreMemoCommit = unexpected()

    override fun permanentDeleteMany(
        operationId: String,
        targets: List<StoreMemoDeleteTarget>,
    ): StoreMemoBatchCommit = unexpected()

    override fun commitDocumentMutation(
        mutation: com.lomo.domain.model.MemoDocumentMutation,
    ): StoreMemoCommit = unexpected()

    override fun startRebuild(batchSize: Int): StoreRebuildResult = unexpected()

    override fun snoozeReminder(
        opaqueId: String,
        snoozeDurationMs: Long,
    ) = unexpected()

    override fun recoverReminderSnooze() = unexpected()
}
