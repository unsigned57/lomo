package com.lomo.data.engine.media

import com.lomo.nativebridge.MediaAttachmentRefDto as BridgeAttachmentRef
import com.lomo.nativebridge.MediaCommittedEntryDto as BridgeCommitted
import com.lomo.nativebridge.MediaManifestDto as BridgeManifest
import com.lomo.nativebridge.MediaOrphanSweepResultDto as BridgeSweep
import com.lomo.nativebridge.MediaPromotePlanDto as BridgePromotePlan
import com.lomo.nativebridge.MediaPromoteResultDto as BridgePromoteResult
import com.lomo.nativebridge.MediaSourceKind as BridgeSourceKind
import com.lomo.nativebridge.MediaStagedDto as BridgeStaged
import com.lomo.nativebridge.MediaTrashEntryDto as BridgeTrash

/**
 * True FFI edge for media operations.
 *
 * Production: [com.lomo.data.engine.ManagedEngineSession] / engine handle.
 * Host tests inject fakes so [BoltFfiMediaPort] mapping is exercised without JNI.
 */
internal interface MediaNativeBridge {
    fun stageMedia(
        mediaRoot: String,
        sourceKind: BridgeSourceKind,
        sourcePath: String,
        humanNameHint: String,
    ): BridgeStaged

    fun allocateRecordingTarget(
        mediaRoot: String,
        extension: String,
    ): String

    fun finalizeRecording(
        mediaRoot: String,
        recordingPath: String,
        humanNameHint: String,
    ): BridgeStaged

    fun promoteMedia(
        workspaceRoot: String,
        plan: BridgePromotePlan,
    ): BridgePromoteResult

    fun queryMediaManifest(workspaceRoot: String): BridgeManifest

    fun mediaOrphanSweep(
        mediaRoot: String,
        committed: List<BridgeCommitted>,
        refs: List<BridgeAttachmentRef>,
        existingTrash: List<BridgeTrash>,
        nowMs: ULong?,
        recoveryWindowMs: ULong,
    ): BridgeSweep
}
