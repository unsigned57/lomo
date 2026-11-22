package com.lomo.data.repository

import app.cash.turbine.test
import com.lomo.data.engine.media.MediaCommittedEntry
import com.lomo.data.engine.media.MediaManifest
import com.lomo.data.engine.media.MediaPort
import com.lomo.data.engine.media.MediaPromotePlan
import com.lomo.data.engine.media.MediaStageLease
import com.lomo.data.engine.media.MediaStageOwnerKind
import com.lomo.data.engine.media.MediaStageRecord
import com.lomo.data.engine.media.MediaStagedFacts
import com.lomo.data.engine.media.PendingMediaStageRegistry
import com.lomo.data.source.MediaStorageSelection
import com.lomo.data.source.StorageRootType
import com.lomo.data.testing.DataFunSpec
import com.lomo.data.testing.fakes.FakeFileDataSource
import com.lomo.domain.model.DraftId
import com.lomo.domain.model.EngineReadiness
import com.lomo.domain.model.ImageLocationSnapshot
import com.lomo.domain.model.MediaEntryId
import com.lomo.domain.model.MemoCreateDraft
import com.lomo.domain.model.MemoEditDraft
import com.lomo.domain.model.ProjectionFreshness
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.model.WorkspaceAttachmentDirectories
import com.lomo.domain.model.WorkspaceAuthority
import com.lomo.domain.model.WorkspaceMount
import com.lomo.domain.repository.MemoCreateDraftRepository
import com.lomo.domain.repository.MemoEditDraftRepository
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.io.path.createTempDirectory

/*
 * Behavior Contract:
 * TDD proof (new receipt): target/lomo/reports/manual/directory-selection/receipt-red.log
 * times out after a successful new-file commit; verification-green.log covers immediate receipt publication,
 * no scan on projection-revision changes and rejection of receipts from a replaced binding generation.
 * Test Change Justification:
 * Reason category: verified media cache provenance.
 * Old behavior/assertion being replaced: fixtures supplied only the installed workspace path.
 * Why old assertion is no longer correct: attachment rebinds keep the same path but replace its native generation.
 * Coverage preserved by: prior root-scoping assertions remain; the fixture now publishes a coherent WorkspaceMount.
 * Why this is not fitting the test to the implementation: native commit receipts outrank older directory snapshots
 * only while their workspace binding remains installed.
 * Capability: Image-directory changes replace configured bare-name locations while commit receipts preserve qualified workspace content identities scoped to the workspace that committed them.
 * Owning layer / priority: MediaEdgeRepository; data platform adapter; P1.
 * Scenarios:
 * - Given engine activation before preferences commit, when images are observed, then only the installed workspace supplies locations.
 * - Given spaces or URI delimiters in image/recording paths, when displayed or finalized, then the URI round-trips to the exact file.
 * - Given an image-directory switch, when the map is observed, then new-directory entries appear and old bare entries disappear without an application restart.
 * - Given committed Direct or SAF media, when a receipt publishes, then its exact qualified path and digest replace the staging preview.
 * - Given a missing final location, when published, then no location is fabricated.
 * - Given sequential manifest refreshes, when the second runs, then witnessed digest hints are reused.
 * - Given commit receipts published under one workspace, when the engine installs another workspace, then those qualified entries are not served there and return once the committing workspace is active again.
 * - Given manifest digest hints witnessed under one workspace, when another workspace is active, then the next manifest refresh offers no foreign hints.
 * Observable outcomes: returned locations, emitted paging/map state, retained identities and surfaced errors.
 * TDD proof: target/lomo/reports/manual/media-toolbar-search/media-red.log: configured photo.png was missing; GREEN media-green.log: 7/7; media-uri-red.log rejects unescaped manifest and recording URIs.
 * TDD proof (installed root): target/lomo/reports/manual/directory-layout/workspace-binding-red.log times out because the second workspace still reads the first root; data-green.log verifies the explicit installed-root input.
 * TDD proof (workspace scope): target/lomo/reports/manual/media-workspace-scope/red.log: a receipt published under workspace A was still served while workspace B was installed and A's manifest hints were offered to B's walk (2 failed / 7 passed); green.log: 9/9.
 * TDD proof (failure recovery): target/lomo/reports/manual/directory-selection/data-red.log:
 * Expected item but found Error(IOException); verification-green.log covers failure-as-state and recovery.
 * Test Change Justification:
 * Reason category: explicit inventory loading protocol.
 * Old behavior/assertion being replaced: raw map emissions carried no loading or failure state.
 * Why old assertion is no longer correct: unavailable provider observations must be explicit and recoverable.
 * Coverage preserved by: all existing entry/location assertions observe Ready entries; the new failure scenario
 * observes Loading, Unavailable and Ready on the same subscription without filtering failures.
 * Why this is not fitting the test to the implementation: the reported release crash requires a recoverable
 * failure result instead of a terminated application-scoped observer.
 * Excludes: physical device timing, Compose pixel styling and remote provider behavior.
 * Test Change Justification:
 * Reason category: user-selected media directories now govern memo-bound promotion.
 * Old behavior/assertion being replaced: finalized recordings always targeted media/ in the notes tree.
 * Why old assertion is no longer correct: the voice namespace must resolve through the selected voice grant.
 * Coverage preserved by: exact decoded capture URI, staged lease identity and final logical recording path.
 * Why this is not fitting the test to the implementation: the destination category follows the settings contract, not a private helper.

 * Reason category: regression coverage and platform fixture correction.
 * Old behavior/assertion being replaced: no existing result assertions are removed or weakened.
 * Why old assertion is no longer correct: All existing assertions are retained; the fixture additionally accepts a stateful configuration source to exercise directory switching and the engine-installed workspace as a StateFlow so workspace scoping can be exercised; its Direct root derives from that same workspace, mirroring production wiring.
 * Coverage preserved by: every existing scenario plus the explicit directory/query regressions.
 * Why this is not fitting the test to the implementation: expected results follow selected-directory identity and normalized-query behavior.
 */
class MediaEdgeRepositoryPublishTest : DataFunSpec() {
    init {
        test(
            "given inventory predates a new file when its commit receipt arrives then the file publishes without another scan",
        ) {
            runTest {
                val workspace = createTempDirectory("lomo-image-receipt-").toFile()
                try {
                    val source = FakeFileDataSource()
                    val mounted = MutableStateFlow(
                        imageMount(StorageLocation(workspace.path), image = "/selected-images"),
                    )
                    val committed = File(workspace, "images/a.png")
                    var scans = 0
                    val storage = object : com.lomo.data.source.MediaStorageDataSource {
                        override suspend fun listImageFiles(
                            selection: MediaStorageSelection,
                        ): List<Pair<String, String>> {
                            scans += 1
                            return emptyList()
                        }
                    }
                    val repository = mediaEdgeRepository(
                        workspace,
                        fileDataSource = source,
                        mediaStorage = storage,
                        activeMount = mounted,
                    )
                    repository.readyImageLocations().test {
                        val id = MediaEntryId("images/a.png")
                        awaitItem() shouldBe emptyMap()
                        requireNotNull(committed.parentFile).mkdirs()
                        committed.writeBytes(byteArrayOf(1, 2, 3))
                        mounted.value = mounted.value.copy(
                            authority = requireNotNull(mounted.value.authority).copy(projectionRevision = 1uL),
                            freshness = ProjectionFreshness.Verified(1uL),
                        )
                        repository.publishCommittedMedia(
                            listOf(MediaPromotePlan("receipt", stagedFacts("f".repeat(64)), id.raw)),
                        )
                        awaitItem().getValue(id).contentId shouldBe "f".repeat(64)
                        scans shouldBe 1
                        cancelAndIgnoreRemainingEvents()
                    }
                } finally {
                    workspace.deleteRecursively()
                }
            }
        }
        test(
            "given a receipt from a previous binding generation when the same workspace changes image roots then the old location is discarded",
        ) {
            runTest {
                val workspace = createTempDirectory("lomo-media-generation-").toFile()
                try {
                    val root = StorageLocation(workspace.path)
                    val mounted = MutableStateFlow(imageMount(root, image = "/old-images"))
                    val source = FakeFileDataSource()
                    val repository = mediaEdgeRepository(workspace, fileDataSource = source, activeMount = mounted)
                    repository.readyImageLocations().test {
                        awaitItem() shouldBe emptyMap()
                        File(workspace, "images/old.png").apply {
                            requireNotNull(parentFile).mkdirs()
                            writeBytes(byteArrayOf(1, 2, 3))
                        }
                        val id = MediaEntryId("images/old.png")
                        repository.publishCommittedMedia(
                            listOf(MediaPromotePlan("old-receipt", stagedFacts("e".repeat(64)), id.raw)),
                        )
                        awaitItem().getValue(id).contentId shouldBe "e".repeat(64)

                        mounted.value = imageMount(root, generation = 2, image = "/new-images")
                        awaitItem() shouldBe emptyMap()
                        cancelAndIgnoreRemainingEvents()
                    }
                } finally {
                    workspace.deleteRecursively()
                }
            }
        }
        test(
            "given a failed scan when the same selection is refreshed then the subscription retries and returns Ready",
        ) {
            runTest {
                val workspace = createTempDirectory("lomo-image-retry-").toFile()
                try {
                    val source = FakeFileDataSource()
                    val root = StorageLocation(workspace.path)
                    val mounted = MutableStateFlow(imageMount(root, image = "/images"))
                    var unavailable = true
                    val storage = object : com.lomo.data.source.MediaStorageDataSource {
                        override suspend fun listImageFiles(
                            selection: MediaStorageSelection,
                        ): List<Pair<String, String>> {
                            if (unavailable) throw java.io.IOException("provider unavailable")
                            return listOf("photo.png" to "file:///images/photo.png")
                        }
                    }
                    val repository = mediaEdgeRepository(
                        workspace,
                        fileDataSource = source,
                        mediaStorage = storage,
                        activeMount = mounted,
                    )
                    repository.observeImageLocations().test {
                        awaitItem() shouldBe ImageLocationSnapshot.Loading
                        awaitItem().shouldBeInstanceOf<ImageLocationSnapshot.Unavailable>()
                        unavailable = false
                        repository.refreshImageLocations()
                        awaitItem() shouldBe ImageLocationSnapshot.Loading
                        awaitItem().shouldBeInstanceOf<ImageLocationSnapshot.Ready>()
                            .entries.getValue(
                                MediaEntryId("photo.png"),
                            ).location.raw shouldBe "file:///images/photo.png"
                        cancelAndIgnoreRemainingEvents()
                    }
                } finally {
                    workspace.deleteRecursively()
                }
            }
        }
        test("given an unfinished old directory scan when another is selected then only the new snapshot publishes") {
            runTest {
                val workspace = createTempDirectory("lomo-image-cancel-").toFile()
                try {
                    val source = FakeFileDataSource()
                    val root = StorageLocation(workspace.path)
                    val mounted = MutableStateFlow(imageMount(root, image = "/old"))
                    val entered = CompletableDeferred<Unit>()
                    val cancelled = CompletableDeferred<Unit>()
                    val oldListing = CompletableDeferred<List<Pair<String, String>>>()
                    val storage = object : com.lomo.data.source.MediaStorageDataSource {
                        override suspend fun listImageFiles(
                            selection: MediaStorageSelection,
                        ): List<Pair<String, String>> {
                            if (selection.imageLocation == "/old") {
                                entered.complete(Unit)
                                try {
                                    return oldListing.await()
                                } finally {
                                    cancelled.complete(Unit)
                                }
                            }
                            return listOf("new.png" to "file:///new/new.png")
                        }
                    }
                    val repository = mediaEdgeRepository(
                        workspace,
                        fileDataSource = source,
                        mediaStorage = storage,
                        activeMount = mounted,
                    )
                    repository.observeImageLocations().test {
                        awaitItem() shouldBe ImageLocationSnapshot.Loading
                        entered.await()
                        mounted.value = imageMount(root, generation = 2, image = "/new")
                        cancelled.await()
                        awaitItem().shouldBeInstanceOf<ImageLocationSnapshot.Ready>()
                            .entries.keys shouldBe setOf(MediaEntryId("new.png"))
                        oldListing.complete(listOf("old.png" to "file:///old/old.png"))
                        expectNoEvents()
                        cancelAndIgnoreRemainingEvents()
                    }
                } finally {
                    workspace.deleteRecursively()
                }
            }
        }
        test(
            "given a directory scan fails when another directory is selected then the existing subscription recovers",
        ) {
            runTest {
                val workspace = createTempDirectory("lomo-image-recovery-").toFile()
                try {
                    val source = FakeFileDataSource()
                    val root = StorageLocation(workspace.path)
                    val mounted = MutableStateFlow(imageMount(root, image = "/images/first"))
                    var failure: java.io.IOException? = null
                    val storage = object : com.lomo.data.source.MediaStorageDataSource {
                        override suspend fun listImageFiles(
                            selection: MediaStorageSelection,
                        ): List<Pair<String, String>> {
                            failure?.let { throw it }
                            return source.listImageFiles(selection)
                        }
                    }
                    val repository = mediaEdgeRepository(
                        workspace,
                        fileDataSource = source,
                        mediaStorage = storage,
                        activeMount = mounted,
                    )
                    repository.observeImageLocations().test {
                        awaitItem() shouldBe ImageLocationSnapshot.Loading
                        awaitItem() shouldBe ImageLocationSnapshot.Ready(emptyMap())
                        failure = java.io.IOException("Selected image directory listing is incomplete")
                        mounted.value = imageMount(root, generation = 2, image = "/images/unavailable")
                        awaitItem() shouldBe ImageLocationSnapshot.Loading
                        val unavailable = awaitItem().shouldBeInstanceOf<ImageLocationSnapshot.Unavailable>()
                        unavailable.diagnostic shouldBe "Selected image directory listing is incomplete"
                        failure = null
                        source.imageLocations["recovered.png"] = "file:///images/final/recovered.png"
                        mounted.value = imageMount(root, generation = 3, image = "/images/final")
                        awaitItem() shouldBe ImageLocationSnapshot.Loading
                        awaitItem().shouldBeInstanceOf<ImageLocationSnapshot.Ready>().entries.getValue(
                            MediaEntryId("recovered.png"),
                        ).location.raw shouldBe
                            "file:///images/final/recovered.png"
                        cancelAndIgnoreRemainingEvents()
                    }
                } finally {
                    workspace.deleteRecursively()
                }
            }
        }
        test(
            "given activation precedes preference commit when images are observed then only the installed workspace supplies files",
        ) {
            runTest {
                val stage = createTempDirectory("lomo-workspace-image-binding-").toFile()
                try {
                    val first = StorageLocation("content://provider/tree/first")
                    val second = StorageLocation("content://provider/tree/second")
                    val source = FakeFileDataSource()
                    source.setRoot(StorageRootType.MAIN, first.raw)
                    source.workspaceImageLocations[first.raw] = mapOf(
                        "media/a.png" to "content://provider/document/first-a",
                    )
                    source.workspaceImageLocations[second.raw] = mapOf(
                        "media/b.png" to "content://provider/document/second-b",
                    )
                    val installed = MutableStateFlow(imageMount(first))
                    val repository = mediaEdgeRepository(stage, fileDataSource = source, activeMount = installed)
                    repository.readyImageLocations().test {
                        awaitItem().keys shouldBe setOf(MediaEntryId("media/a.png"))
                        installed.value = imageMount(second)
                        awaitItem().keys shouldBe setOf(MediaEntryId("media/b.png"))
                        source.getRootFlow(StorageRootType.MAIN).first() shouldBe first.raw
                        cancelAndIgnoreRemainingEvents()
                    }
                } finally {
                    stage.deleteRecursively()
                }
            }
        }

        test(
            "given a recording path containing URI syntax when allocated and finalized then its exact filesystem target survives",
        ) {
            val workspace = createTempDirectory(prefix = "lomo voice ").toFile()
            try {
                val file = File(workspace, "take #1.m4a").apply { writeBytes(byteArrayOf(1)) }
                val port = mockk<MediaPort>()
                val draft = DraftId("draft-uri")
                val staged = stagedFacts("a".repeat(64)).copy(
                    stagingPath = file.absolutePath,
                    mime = "audio/mp4",
                    humanNameHint = file.name,
                    suggestedFinalRelativePath = "media/take.m4a",
                )
                every { port.allocateRecordingTarget(workspace.absolutePath, "m4a") } returns file.absolutePath
                every { port.finalizeRecording(workspace.absolutePath, file.absolutePath, file.name) } returns staged
                every {
                    port.recordStageLease(
                        staged.copy(suggestedFinalRelativePath = "voice/take.m4a"),
                        MediaStageOwnerKind.Draft,
                        draft.value,
                    )
                } returns
                    MediaStageRecord(
                        "artifact-uri", staged.digest, staged.size, staged.mime, staged.stagingPath,
                        staged.humanNameHint, "voice/take.m4a",
                        listOf(MediaStageLease("artifact-uri", MediaStageOwnerKind.Draft, draft.value)), true,
                    )
                val repository = mediaEdgeRepository(workspace, mediaPort = port)
                val target = repository.allocateVoiceCaptureTarget(MediaEntryId(file.name))
                java.net.URI(target.raw).path shouldBe file.absolutePath
                repository.finalizeVoiceCapture(target, file.name, draft) shouldBe StorageLocation("voice/take.m4a")
            } finally {
                workspace.deleteRecursively()
            }
        }

        test("given a manifest path containing URI syntax when published then its URI decodes to the exact file") {
            val workspace = createTempDirectory(prefix = "lomo media ").toFile()
            try {
                val file = File(workspace, "media/photo #1.png").apply {
                    requireNotNull(parentFile).mkdirs()
                    writeBytes(byteArrayOf(1, 2, 3))
                }
                val repository = mediaEdgeRepository(workspace, mediaPort = RecordingManifestMediaPort(file))
                repository.refreshImageLocations()
                val raw = repository.readyImageLocations().first().getValue(
                    MediaEntryId("media/photo #1.png"),
                ).location.raw
                java.net.URI(raw).path shouldBe file.absolutePath
                raw.contains("%20") shouldBe true
                raw.contains("%23") shouldBe true
            } finally {
                workspace.deleteRecursively()
            }
        }

        test(
            "given a changed image directory when locations are observed then the new directory replaces old bare names",
        ) {
            runTest {
                val workspace = createTempDirectory(prefix = "lomo-media-roots-").toFile()
                try {
                    val source = FakeFileDataSource()
                    val root = StorageLocation(workspace.path)
                    val mounted = MutableStateFlow(imageMount(root, image = "/images/old"))
                    source.imageLocations["photo.png"] = "file:///images/old/photo.png"
                    val repository = mediaEdgeRepository(workspace, fileDataSource = source, activeMount = mounted)
                    repository.readyImageLocations().test {
                        awaitItem()[MediaEntryId("photo.png")]?.location?.raw shouldBe "file:///images/old/photo.png"
                        source.imageLocations.clear()
                        source.imageLocations["new.png"] = "file:///images/new/new.png"
                        mounted.value = imageMount(root, generation = 2, image = "/images/new")
                        val changed = awaitItem()
                        changed[MediaEntryId("photo.png")] shouldBe null
                        changed[MediaEntryId("new.png")]?.location?.raw shouldBe "file:///images/new/new.png"
                        cancelAndIgnoreRemainingEvents()
                    }
                } finally {
                    workspace.deleteRecursively()
                }
            }
        }

        test("given a SAF commit when promoted media publishes then the provider location replaces the stage preview") {
            val workspace = createTempDirectory(prefix = "lomo-media-saf-publish-").toFile()
            val finalLocation = StorageLocation("content://provider/tree/root/document/opaque-image-id")
            val repository = mediaEdgeRepository(workspace, committedLocation = { finalLocation }, directRoot = null)

            repository.publishCommittedMedia(
                listOf(MediaPromotePlan("op-saf", stagedFacts("d".repeat(64)), "media/img_1.png")),
            )

            val descriptor = repository.readyImageLocations().first()[MediaEntryId("media/img_1.png")]
            descriptor shouldNotBe null
            descriptor!!.location shouldBe finalLocation
            descriptor.contentId shouldBe "d".repeat(64)
        }

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

            val descriptor = repository.readyImageLocations().first()[MediaEntryId("media/img_1.png")]
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

            repository.readyImageLocations().first()[MediaEntryId("media/missing.png")] shouldBe null
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

        test(
            "given committed media under one workspace when the active workspace changes then those entries are served only while that workspace is active",
        ) {
            val workspaceA = createTempDirectory(prefix = "lomo-media-ws-a-").toFile()
            val workspaceB = createTempDirectory(prefix = "lomo-media-ws-b-").toFile()
            try {
                val finalFile =
                    File(workspaceA, "media/img_1.png").apply {
                        requireNotNull(parentFile).mkdirs()
                        writeBytes(byteArrayOf(1, 2, 3))
                    }
                val active = MutableStateFlow(imageMount(StorageLocation(workspaceA.absolutePath)))
                val repository = mediaEdgeRepository(workspaceA, activeMount = active)
                val entry = MediaEntryId("media/img_1.png")
                repository.publishCommittedMedia(
                    listOf(MediaPromotePlan("op-ws", stagedFacts("e".repeat(64)), "media/img_1.png")),
                )
                repository.readyImageLocations().first()[entry]?.location?.raw shouldBe
                    "file://${finalFile.absolutePath}"

                // A same-named reference in another workspace must not borrow this location.
                active.value = imageMount(StorageLocation(workspaceB.absolutePath))
                repository.readyImageLocations().first()[entry] shouldBe null

                // The committing workspace serves its own receipt again once it is active.
                active.value = imageMount(StorageLocation(workspaceA.absolutePath))
                repository.readyImageLocations().first()[entry]?.contentId shouldBe "e".repeat(64)
            } finally {
                workspaceA.deleteRecursively()
                workspaceB.deleteRecursively()
            }
        }

        test(
            "given manifest hints witnessed under one workspace when the active workspace changes then the next refresh offers no foreign hints",
        ) {
            val workspaceA = createTempDirectory(prefix = "lomo-media-hint-a-").toFile()
            val workspaceB = createTempDirectory(prefix = "lomo-media-hint-b-").toFile()
            try {
                val file =
                    File(workspaceA, "media/img_1.png").apply {
                        requireNotNull(parentFile).mkdirs()
                        writeBytes(byteArrayOf(4, 5, 6))
                    }
                val port = RecordingManifestMediaPort(file)
                val active = MutableStateFlow(imageMount(StorageLocation(workspaceA.absolutePath)))
                val repository = mediaEdgeRepository(workspaceA, mediaPort = port, activeMount = active)
                repository.refreshImageLocations()
                repository.refreshImageLocations()
                port.seenVerifiedEntries.last().map { it.digest } shouldBe listOf("c".repeat(64))

                active.value = imageMount(StorageLocation(workspaceB.absolutePath))
                repository.refreshImageLocations()

                port.seenVerifiedEntries.last() shouldBe emptyList()
            } finally {
                workspaceA.deleteRecursively()
                workspaceB.deleteRecursively()
            }
        }
    }

    private fun mediaEdgeRepository(
        workspace: File,
        mediaPort: MediaPort = NoOpMediaPort(),
        committedLocation: (String) -> StorageLocation? = { path ->
            File(workspace, path).takeIf { it.isFile }?.let { StorageLocation(it.toPath().toUri().toASCIIString()) }
        },
        directRoot: String? = workspace.absolutePath,
        fileDataSource: FakeFileDataSource = FakeFileDataSource(),
        activeMount: StateFlow<WorkspaceMount> = MutableStateFlow(imageMount(directRoot?.let(::StorageLocation))),
        mediaStorage: com.lomo.data.source.MediaStorageDataSource = fileDataSource,
    ): MediaEdgeRepository = MediaEdgeRepository(
        dependencies =
        MediaEdgeRepositoryDependencies(
            context = mockk(),
            workspaceConfigSource = fileDataSource,
            mediaStorageDataSource = mediaStorage,
            mediaPort = mediaPort,
            workspaceMount = activeMount,
            committedLocation = committedLocation,
            stageRoot = { workspace.absolutePath },
            writeLease = alwaysWritableWorkspaceMutationLease(),
            createDraftRepository = EmptyCreateDrafts(),
            editDraftRepository = EmptyEditDrafts(),
        ),
        pendingStages = PendingMediaStageRegistry(mediaPort) { workspace.absolutePath },
    )

    private class EmptyCreateDrafts : MemoCreateDraftRepository {
        override suspend fun read(): MemoCreateDraft? = null

        override suspend fun write(draft: MemoCreateDraft) = error("draft writes are not exercised")

        override suspend fun clear() = error("draft clears are not exercised")
    }

    private class EmptyEditDrafts : MemoEditDraftRepository {
        override suspend fun read(): MemoEditDraft? = null

        override suspend fun write(draft: MemoEditDraft) = error("draft writes are not exercised")

        override suspend fun clear(memoId: String) = error("draft clears are not exercised")
    }

    private fun stagedFacts(digest: String): MediaStagedFacts = MediaStagedFacts(
        digest = digest,
        size = 3,
        mime = "image/png",
        stagingPath = "/stage/img_1.png",
        humanNameHint = "img_1.png",
        suggestedFinalRelativePath = "media/img_1.png",
    )
}

private class RecordingManifestMediaPort(private val file: File) : MediaPort by NoOpMediaPort() {
    val seenVerifiedEntries = mutableListOf<List<MediaCommittedEntry>>()

    override fun queryMediaManifest(workspaceRoot: String, verifiedEntries: List<MediaCommittedEntry>): MediaManifest {
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

private fun com.lomo.domain.repository.MediaRepository.readyImageLocations() =
    observeImageLocations().filterIsInstance<ImageLocationSnapshot.Ready>().map { it.entries }

private fun imageMount(location: StorageLocation?, generation: Long = 1, image: String? = null): WorkspaceMount =
    WorkspaceMount(
        readiness = if (location == null) EngineReadiness.AwaitingWorkspaceSelection else EngineReadiness.Ready,
        location = location,
        authority = location?.let { WorkspaceAuthority(it.raw, generation, 0uL) },
        freshness = if (location == null) ProjectionFreshness.Unavailable else ProjectionFreshness.Verified(0uL),
        attachments = WorkspaceAttachmentDirectories(images = image?.let(::StorageLocation)),
    )
