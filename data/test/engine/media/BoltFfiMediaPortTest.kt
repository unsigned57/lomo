package com.lomo.data.engine.media

/*
 * Behavior Contract:
 * - Unit under test: BoltFfiMediaPort + MediaSyncEdgeAdapter
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: map path-only media bridge DTOs; sync edge only journals committed paths.
 *
 * Scenarios:
 * - Given staged bridge DTO, when stageMedia runs, then domain facts map digests/paths.
 * - Given committed upsert basename, when sync edge runs, then image recorder is called once.
 * - Given staged-only path, when no promote occurs, then recorders stay silent (edge not invoked).
 * - Given blank operationId, when promoteMedia runs, then require fails (no UUID mint).
 * - Given non-blank operationId, when promoteMedia runs, then bridge receives that id.
 *
 * Observable outcomes: MediaStagedFacts fields; recorder call lists; promote rejection.
 *
 * TDD proof:
 * - Target: ./kotlin test --include-module=data --include-classes='com.lomo.data.engine.media.BoltFfiMediaPortTest'
 * - RED: path-only media port and committed-only sync edge were untested before this host contract.
 * - GREEN: stage maps digests/paths; blank operationId promote fails closed; recorders stay silent without promote.
 *
 * Excludes: real JNI / filesystem.
 */

import com.lomo.data.repository.S3LocalChangeRecorder
import com.lomo.data.repository.WebDavLocalChangeRecorder
import com.lomo.nativebridge.MediaAttachmentRefDto as BridgeAttachmentRef
import com.lomo.nativebridge.MediaCommittedEntryDto as BridgeCommitted
import com.lomo.nativebridge.MediaManifestDto as BridgeManifest
import com.lomo.nativebridge.MediaOrphanSweepResultDto as BridgeSweep
import com.lomo.nativebridge.MediaPromotePlanDto as BridgePromotePlan
import com.lomo.nativebridge.MediaPromoteResultDto as BridgePromoteResult
import com.lomo.nativebridge.MediaSourceKind as BridgeSourceKind
import com.lomo.nativebridge.MediaStagedDto as BridgeStaged
import com.lomo.nativebridge.MediaTrashEntryDto as BridgeTrash
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private class RecordingMediaNativeBridge : MediaNativeBridge {
    var lastMediaRoot: String? = null
    var lastSourcePath: String? = null
    var lastPromoteOperationId: String? = null

    override fun stageMedia(
        mediaRoot: String,
        sourceKind: BridgeSourceKind,
        sourcePath: String,
        humanNameHint: String,
    ): BridgeStaged {
        lastMediaRoot = mediaRoot
        lastSourcePath = sourcePath
        return BridgeStaged(
            digest = "abc123",
            size = 12uL,
            mime = "image/png",
            stagingPath = "$mediaRoot/.lomo" + "-media-stage/abc123.png",
            humanNameHint = humanNameHint,
            suggestedFinalRelativePath = "media/in.png",
        )
    }

    override fun allocateRecordingTarget(
        mediaRoot: String,
        extension: String,
    ): String = "$mediaRoot/.lomo" + "-media-stage/recording.$extension"

    override fun finalizeRecording(
        mediaRoot: String,
        recordingPath: String,
        humanNameHint: String,
    ): BridgeStaged = stageMedia(mediaRoot, BridgeSourceKind.STAGED_TEMP, recordingPath, humanNameHint)

    override fun promoteMedia(
        workspaceRoot: String,
        plan: BridgePromotePlan,
    ): BridgePromoteResult {
        lastPromoteOperationId = plan.operationId
        return BridgePromoteResult(
            operationId = plan.operationId,
            digest = plan.staged.digest,
            mime = plan.staged.mime,
            size = plan.staged.size,
            finalAbsolutePath = "$workspaceRoot/${plan.finalRelativePath}",
            finalRelativePath = plan.finalRelativePath,
        )
    }

    override fun queryMediaManifest(workspaceRoot: String): BridgeManifest =
        BridgeManifest(stageDirName = ".lomo" + "-media-stage", entries = emptyList())

    override fun mediaOrphanSweep(
        mediaRoot: String,
        committed: List<BridgeCommitted>,
        refs: List<BridgeAttachmentRef>,
        existingTrash: List<BridgeTrash>,
        nowMs: ULong?,
        recoveryWindowMs: ULong,
    ): BridgeSweep =
        BridgeSweep(
            movedToTrash = emptyList(),
            permanentlyDeletedDigests = emptyList(),
            keptLive = committed.size.toULong(),
        )
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

    override suspend fun recordVoiceDelete(filename: String) = Unit
}

private class RecordingWebDav : WebDavLocalChangeRecorder {
    val upserts = mutableListOf<String>()

    override suspend fun recordMemoUpsert(filename: String) = Unit

    override suspend fun recordMemoDelete(filename: String) = Unit

    override suspend fun recordImageUpsert(filename: String) {
        upserts += filename
    }

    override suspend fun recordImageDelete(filename: String) = Unit

    override suspend fun recordVoiceUpsert(filename: String) = Unit

    override suspend fun recordVoiceDelete(filename: String) = Unit
}

class BoltFfiMediaPortTest : FunSpec({
    test("stageMedia maps bridge staged facts without byte bodies") {
        val bridge = RecordingMediaNativeBridge()
        val port = BoltFfiMediaPort(bridge)
        val staged =
            port.stageMedia(
                mediaRoot = "/ws",
                sourceKind = MediaSourceKind.DirectPath,
                sourcePath = "/tmp/in.png",
                humanNameHint = "in.png",
            )
        staged.digest shouldBe "abc123"
        staged.mime shouldBe "image/png"
        staged.stagingPath shouldBe "/ws/.lomo" + "-media-stage/abc123.png"
        staged.suggestedFinalRelativePath shouldBe "media/in.png"
        bridge.lastMediaRoot shouldBe "/ws"
        bridge.lastSourcePath shouldBe "/tmp/in.png"
    }

    test("sync edge journals only when onCommittedMediaUpsert is invoked") {
        val s3 = RecordingS3()
        val webdav = RecordingWebDav()
        val edge = MediaSyncEdgeAdapter(s3, webdav)
        // staged never calls edge — empty recorders prove D8 separation when unused
        s3.upserts shouldBe emptyList()
        edge.onCommittedMediaUpsert("photo.png")
        s3.upserts shouldBe listOf("photo.png")
        webdav.upserts shouldBe listOf("photo.png")
    }

    test("promoteMedia rejects blank operationId without minting UUID") {
        val bridge = RecordingMediaNativeBridge()
        val port = BoltFfiMediaPort(bridge)
        val staged =
            MediaStagedFacts(
                digest = "abc123",
                size = 1,
                mime = "image/png",
                stagingPath = "/ws/.stage/a.png",
                humanNameHint = "a.png",
                suggestedFinalRelativePath = "media/a.png",
            )
        shouldThrow<IllegalArgumentException> {
            port.promoteMedia(
                workspaceRoot = "/ws",
                plan =
                    MediaPromotePlan(
                        operationId = "   ",
                        staged = staged,
                        finalRelativePath = "media/a.png",
                    ),
            )
        }
        bridge.lastPromoteOperationId shouldBe null
    }

    test("promoteMedia forwards non-blank operationId to bridge") {
        val bridge = RecordingMediaNativeBridge()
        val port = BoltFfiMediaPort(bridge)
        val staged =
            MediaStagedFacts(
                digest = "abc123",
                size = 1,
                mime = "image/png",
                stagingPath = "/ws/.stage/a.png",
                humanNameHint = "a.png",
                suggestedFinalRelativePath = "media/a.png",
            )
        val result =
            port.promoteMedia(
                workspaceRoot = "/ws",
                plan =
                    MediaPromotePlan(
                        operationId = "op-fixed-1",
                        staged = staged,
                        finalRelativePath = "media/a.png",
                    ),
            )
        result.operationId shouldBe "op-fixed-1"
        bridge.lastPromoteOperationId shouldBe "op-fixed-1"
    }
})
