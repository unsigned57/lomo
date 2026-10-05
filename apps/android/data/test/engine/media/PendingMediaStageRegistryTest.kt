package com.lomo.data.engine.media

/*
 * Behavior Contract:
 * - Unit under test: PendingMediaStageRegistry.
 * - Owning layer: data.
 * - Priority tier: P0.
 * - Capability: a draft's staged media is leased to the draft and only transferred to the frozen
 *   operation on submit, so committing one draft never destroys bytes another draft still holds.
 *
 * Scenarios:
 * - Given staged facts leased to a draft, when the draft submits, then the claim is transferred to
 *   the operation and the returned plan carries the owner-resolved destination.
 * - Given staged facts leased to an incoming transfer, when the import resolves, then the claims
 *   transfer to the frozen operation; when the drop is discarded instead, every incoming claim is
 *   released and unshared bytes are deleted.
 * - Given a draft-owned destination, when the draft discards it, then only that draft's claim is
 *   released and the resolved destination is reported.
 *
 * Observable outcomes:
 * - Returned promote plans and the lease mutations performed through MediaPort.
 *
 * TDD proof:
 * - RED before the fix because the in-memory registry keyed staged facts by basename and rejected a
 *   second digest at the same key, so per-holder leases could not be represented at all.
 *
 * Excludes:
 * - Media bytes, platform writes, the Rust ledger durability (locked by the Rust stage lease
 *   contract), and promote semantics.
 * Test Change Justification:
 * - Reason category: domain contract change (durable per-holder stage leases).
 * - Old behavior/assertion being replaced: staged facts keyed by basename whose promotion
 *   destructively removed the fact.
 * - Why old assertion is no longer correct: staged media is leased per draft and transferred to
 *   the operation on submit; per-holder leases are the new law.
 * - Coverage preserved by: basename-keyed scenarios were rewritten as lease-lifecycle scenarios
 *   covering submit/release/discard and shared-holder survival.
 * - Why this is not fitting the test to the implementation: lease transfer and shared-holder
 *   survival are externally observable port mutations.
 */

import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.model.DraftId
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class PendingMediaStageRegistryTest : DataFunSpec() {
    init {
        test("submitting transfers the draft claim to the frozen operation") {
            val port = FakeLedgerMediaPort()
            val registry = PendingMediaStageRegistry(port, stageRoot = { MEDIA_ROOT })
            port.recordStageLease(
                workspaceRoot = null,
                staged = stagedFacts(),
                ownerKind = MediaStageOwnerKind.Draft,
                ownerId = DRAFT.value,
            )

            val plans = registry.plansForOperation(OPERATION_ID, DRAFT)

            plans shouldHaveSize 1
            plans.single().finalRelativePath shouldBe "media/photo.png"
            port.leaseOwners() shouldBe setOf(MediaStageOwnerKind.PendingOperation to OPERATION_ID)
        }

        test("resolving an inbox drop transfers the incoming claim to the frozen operation") {
            val port = FakeLedgerMediaPort()
            val registry = PendingMediaStageRegistry(port, stageRoot = { MEDIA_ROOT })
            registry.recordIncoming(stagedFacts(), ownerId = "inbox/note.md", workspaceRoot = null)

            val plans = registry.plansForIncomingOperation(OPERATION_ID, "inbox/note.md")

            plans shouldHaveSize 1
            plans.single().finalRelativePath shouldBe "media/photo.png"
            port.leaseOwners() shouldBe setOf(MediaStageOwnerKind.PendingOperation to OPERATION_ID)
        }

        test("keep-local releases the incoming claim and deletes unshared bytes") {
            val port = FakeLedgerMediaPort()
            val registry = PendingMediaStageRegistry(port, stageRoot = { MEDIA_ROOT })
            registry.recordIncoming(stagedFacts(), ownerId = "inbox/note.md", workspaceRoot = null)
            port.recordStageLease(null, stagedFacts(), MediaStageOwnerKind.Draft, DRAFT.value)

            registry.releaseIncoming("inbox/note.md")

            port.deletedArtifacts shouldBe emptyList()
            port.leaseOwners() shouldBe setOf(MediaStageOwnerKind.Draft to DRAFT.value)
        }

        test("discarding a draft destination releases only that draft's claim") {
            val port = FakeLedgerMediaPort()
            val registry = PendingMediaStageRegistry(port, stageRoot = { MEDIA_ROOT })
            port.recordStageLease(null, stagedFacts(), MediaStageOwnerKind.Draft, DRAFT.value)

            val released = registry.releaseDraftDestination(DRAFT, "photo.png")

            released shouldBe "media/photo.png"
            port.leaseOwners() shouldBe emptySet()
            port.deletedArtifacts shouldBe listOf("a".repeat(64))
        }
    }
}

private const val MEDIA_ROOT = "/media"
private const val OPERATION_ID = "op-1"
private val DRAFT = DraftId("draft-a")

private fun stagedFacts() =
    MediaStagedFacts(
        digest = "a".repeat(64),
        size = 4L,
        mime = "image/png",
        stagingPath = "$MEDIA_ROOT/.lomo-media-stage/${"a".repeat(64)}.png",
        humanNameHint = "photo.png",
        suggestedFinalRelativePath = "media/photo.png",
    )

private class FakeLedgerMediaPort : MediaPort {
    private val records = linkedMapOf<String, MediaStageRecord>()
    val deletedArtifacts = mutableListOf<String>()

    fun leaseOwners(): Set<Pair<MediaStageOwnerKind, String>> =
        records.values.flatMap { record -> record.leases.map { it.ownerKind to it.ownerId } }.toSet()

    override fun stageMedia(
        mediaRoot: String,
        sourceKind: MediaSourceKind,
        sourcePath: String,
        humanNameHint: String,
    ): MediaStagedFacts = stagedFacts()

    override fun recordStageLease(
        workspaceRoot: String?,
        staged: MediaStagedFacts,
        ownerKind: MediaStageOwnerKind,
        ownerId: String,
    ): MediaStageRecord {
        val existing = records[staged.digest]
        val record =
            existing?.copy(
                leases = existing.leases + MediaStageLease(staged.digest, ownerKind, ownerId),
            ) ?: MediaStageRecord(
                artifactId = staged.digest,
                digest = staged.digest,
                size = staged.size,
                mime = staged.mime,
                stagingPath = staged.stagingPath,
                humanNameHint = staged.humanNameHint,
                suggestedFinalRelativePath = staged.suggestedFinalRelativePath,
                leases = listOf(MediaStageLease(staged.digest, ownerKind, ownerId)),
                stagedBytesPresent = true,
            )
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
        val leases =
            record.leases.filterNot { it == from }.let { remaining ->
                if (remaining.contains(to)) remaining else remaining + to
            }
        records[from.artifactId] = record.copy(leases = leases)
        if (leases.isEmpty()) {
            records.remove(from.artifactId)
            deletedArtifacts += from.artifactId
        }
        return MediaStageRelease(from.artifactId, leases.size.toLong(), leases.isEmpty())
    }

    override fun releaseStageLease(
        mediaRoot: String,
        lease: MediaStageLease,
    ): MediaStageRelease {
        val record = records.getValue(lease.artifactId)
        val leases = record.leases.filterNot { it == lease }
        if (leases.isEmpty()) {
            records.remove(lease.artifactId)
            deletedArtifacts += lease.artifactId
        } else {
            records[lease.artifactId] = record.copy(leases = leases)
        }
        return MediaStageRelease(lease.artifactId, leases.size.toLong(), leases.isEmpty())
    }

    override fun allocateRecordingTarget(
        mediaRoot: String,
        extension: String,
    ): String = "$mediaRoot/recording.$extension"

    override fun finalizeRecording(
        mediaRoot: String,
        recordingPath: String,
        humanNameHint: String,
    ): MediaStagedFacts = stagedFacts()

    override fun queryMediaManifest(
        workspaceRoot: String,
        verifiedEntries: List<MediaCommittedEntry>,
    ): MediaManifest =
        MediaManifest(stageDirName = ".lomo-media-stage", entries = emptyList())

    override fun sessionMediaOrphanSweep(
        nowMs: Long?,
        recoveryWindowMs: Long,
        externalDrafts: List<MediaSweepDraftGuard>,
    ): MediaSweepReport =
        MediaSweepReport(
            candidates = 0,
            protections = emptyList(),
            movedToTrash = emptyList(),
            permanentlyDeletedDigests = emptyList(),
            keptLive = 0,
            failures = emptyList(),
        )
}
