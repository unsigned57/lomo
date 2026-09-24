package com.lomo.data.engine.media

import com.lomo.nativebridge.MediaCommittedEntryDto as BridgeCommitted
import com.lomo.nativebridge.MediaManifestDto as BridgeManifest
import com.lomo.nativebridge.MediaSourceKind as BridgeSourceKind
import com.lomo.nativebridge.MediaStageLeaseDto as BridgeStageLease
import com.lomo.nativebridge.MediaStageOwnerKindDto as BridgeStageOwnerKind
import com.lomo.nativebridge.MediaStageRecordDto as BridgeStageRecord
import com.lomo.nativebridge.MediaStageReleaseDto as BridgeStageRelease
import com.lomo.nativebridge.MediaStagedDto as BridgeStaged
import com.lomo.nativebridge.SessionMediaSweepReportDto as BridgeSweepReport

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

    fun recordStageLease(
        workspaceRoot: String?,
        staged: BridgeStaged,
        ownerKind: BridgeStageOwnerKind,
        ownerId: String,
    ): BridgeStageRecord

    fun stageRecordsForOwner(
        mediaRoot: String,
        ownerKind: BridgeStageOwnerKind,
        ownerId: String,
    ): List<BridgeStageRecord>

    fun transferStageLease(
        mediaRoot: String,
        from: BridgeStageLease,
        to: BridgeStageLease,
    ): BridgeStageRelease

    fun releaseStageLease(
        mediaRoot: String,
        lease: BridgeStageLease,
    ): BridgeStageRelease

    fun allocateRecordingTarget(
        mediaRoot: String,
        extension: String,
    ): String

    fun finalizeRecording(
        mediaRoot: String,
        recordingPath: String,
        humanNameHint: String,
    ): BridgeStaged

    fun queryMediaManifest(
        workspaceRoot: String,
        verifiedEntries: List<BridgeCommitted>,
    ): BridgeManifest

    /**
     * Session-owned two-phase media orphan sweep; the Rust session proves the protection set
     * inside its transaction lock and reports candidates/protections/moves/deletions/failures.
     */
    fun sessionMediaOrphanSweep(
        nowMs: ULong?,
        recoveryWindowMs: ULong,
    ): BridgeSweepReport
}
