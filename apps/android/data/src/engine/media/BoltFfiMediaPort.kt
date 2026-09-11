package com.lomo.data.engine.media

import com.lomo.nativebridge.MediaAttachmentRefDto as BridgeAttachmentRef
import com.lomo.nativebridge.MediaCommittedEntryDto as BridgeCommitted
import com.lomo.nativebridge.MediaPromotePlanDto as BridgePromotePlan
import com.lomo.nativebridge.MediaSourceKind as BridgeSourceKind
import com.lomo.nativebridge.MediaStagedDto as BridgeStaged
import com.lomo.nativebridge.MediaTrashEntryDto as BridgeTrash

/**
 * Production [MediaPort] over [MediaNativeBridge] (ManagedEngineSession / BoltFFI).
 *
 * Mapping only — identity/digest/mime/orphan rules stay in Rust.
 * Standalone [promoteMedia] is recovery-only and requires a non-blank operationId (D4);
 * production import/recording never call it — memo-bound pendingPromotes own promote.
 */
internal class BoltFfiMediaPort(
    private val bridge: MediaNativeBridge,
) : MediaPort {
    override fun stageMedia(
        mediaRoot: String,
        sourceKind: MediaSourceKind,
        sourcePath: String,
        humanNameHint: String,
    ): MediaStagedFacts =
        bridge
            .stageMedia(
                mediaRoot = mediaRoot,
                sourceKind = sourceKind.toBridge(),
                sourcePath = sourcePath,
                humanNameHint = humanNameHint,
            ).toFacts()

    override fun allocateRecordingTarget(
        mediaRoot: String,
        extension: String,
    ): String = bridge.allocateRecordingTarget(mediaRoot, extension)

    override fun finalizeRecording(
        mediaRoot: String,
        recordingPath: String,
        humanNameHint: String,
    ): MediaStagedFacts =
        bridge
            .finalizeRecording(mediaRoot, recordingPath, humanNameHint)
            .toFacts()

    override fun promoteMedia(
        workspaceRoot: String,
        plan: MediaPromotePlan,
    ): MediaPromoteResult {
        val operationId = plan.operationId.trim()
        require(operationId.isNotEmpty()) {
            "promoteMedia requires non-blank operationId (recovery-only; never mint UUID)"
        }
        val result =
            bridge.promoteMedia(
                workspaceRoot,
                BridgePromotePlan(
                    operationId = operationId,
                    staged = plan.staged.toBridge(),
                    finalRelativePath = plan.finalRelativePath,
                ),
            )
        return MediaPromoteResult(
            operationId = result.operationId,
            digest = result.digest,
            mime = result.mime,
            size = result.size.toLong(),
            finalAbsolutePath = result.finalAbsolutePath,
            finalRelativePath = result.finalRelativePath,
        )
    }

    override fun queryMediaManifest(workspaceRoot: String): MediaManifest {
        val manifest = bridge.queryMediaManifest(workspaceRoot)
        return MediaManifest(
            stageDirName = manifest.stageDirName,
            entries =
                manifest.entries.map { entry ->
                    MediaCommittedEntry(
                        digest = entry.digest,
                        absolutePath = entry.absolutePath,
                    )
                },
        )
    }

    override fun mediaOrphanSweep(
        mediaRoot: String,
        committed: List<MediaCommittedEntry>,
        refs: List<MediaAttachmentRef>,
        existingTrash: List<MediaTrashEntry>,
        nowMs: Long?,
        recoveryWindowMs: Long,
    ): MediaOrphanSweepResult {
        val result =
            bridge.mediaOrphanSweep(
                mediaRoot = mediaRoot,
                committed =
                    committed.map { entry ->
                        BridgeCommitted(digest = entry.digest, absolutePath = entry.absolutePath)
                    },
                refs =
                    refs.map { ref ->
                        BridgeAttachmentRef(
                            digest = ref.digest,
                            ownerKey = ref.ownerKey,
                            source = ref.source,
                        )
                    },
                existingTrash =
                    existingTrash.map { trash ->
                        BridgeTrash(
                            digest = trash.digest,
                            trashPath = trash.trashPath,
                            trashedAtMs = trash.trashedAtMs.toULong(),
                            expiresAtMs = trash.expiresAtMs.toULong(),
                        )
                    },
                nowMs = nowMs?.toULong(),
                recoveryWindowMs = recoveryWindowMs.toULong(),
            )
        return MediaOrphanSweepResult(
            movedToTrash =
                result.movedToTrash.map { trash ->
                    MediaTrashEntry(
                        digest = trash.digest,
                        trashPath = trash.trashPath,
                        trashedAtMs = trash.trashedAtMs.toLong(),
                        expiresAtMs = trash.expiresAtMs.toLong(),
                    )
                },
            permanentlyDeletedDigests = result.permanentlyDeletedDigests,
            keptLive = result.keptLive.toLong(),
        )
    }

    private fun MediaSourceKind.toBridge(): BridgeSourceKind =
        when (this) {
            MediaSourceKind.DirectPath -> BridgeSourceKind.DIRECT_PATH
            MediaSourceKind.StagedTemp -> BridgeSourceKind.STAGED_TEMP
        }

    private fun BridgeStaged.toFacts(): MediaStagedFacts =
        MediaStagedFacts(
            digest = digest,
            size = size.toLong(),
            mime = mime,
            stagingPath = stagingPath,
            humanNameHint = humanNameHint,
            suggestedFinalRelativePath = suggestedFinalRelativePath,
        )

    private fun MediaStagedFacts.toBridge(): BridgeStaged =
        BridgeStaged(
            digest = digest,
            size = size.toULong(),
            mime = mime,
            stagingPath = stagingPath,
            humanNameHint = humanNameHint,
            suggestedFinalRelativePath = suggestedFinalRelativePath,
        )
}
