package com.lomo.data.engine

import com.lomo.data.engine.lan.LanBatchPreview
import com.lomo.data.engine.lan.LanBatchRecovery
import com.lomo.data.engine.lan.LanNetworkFacts
import com.lomo.data.engine.lan.LanOutgoingBatch
import com.lomo.data.engine.lan.LanOutgoingBatchDrive
import com.lomo.data.engine.lan.LanReceivedBatchDecision
import com.lomo.data.engine.lan.LanReceivedBatchDrive
import com.lomo.data.engine.lan.LanReceivedItemRecovery
import com.lomo.nativebridge.EngineState

internal fun EngineState.toSnapshot(): NativeEngineSnapshot =
    when (this) {
        EngineState.AwaitingWorkspaceSelection -> NativeEngineSnapshot.AwaitingWorkspaceSelection
        is EngineState.Opening -> NativeEngineSnapshot.Opening(jobId = jobId)
        is EngineState.Ready -> NativeEngineSnapshot.Ready(coreRevision, eventSequence)
        is EngineState.ReadOnlyRecovery ->
            NativeEngineSnapshot.ReadOnlyRecovery(
                EngineFailureSnapshot(
                    category = failure.category,
                    code = failure.code,
                    retryDisposition = failure.retryDisposition,
                    diagnostic = failure.diagnostic,
                ),
            )
        EngineState.ShuttingDown -> NativeEngineSnapshot.ShuttingDown
    }

internal fun LanNetworkFacts.toBridge(): com.lomo.nativebridge.LanNetworkSnapshotDto =
    com.lomo.nativebridge.LanNetworkSnapshotDto(
        revision = revision,
        localNetworkPermissionGranted = localNetworkPermissionGranted,
        candidates =
            candidates.map { candidate ->
                com.lomo.nativebridge.LanBindCandidateDto(
                    host = candidate.host,
                    port = candidate.port,
                )
            },
    )

internal fun com.lomo.nativebridge.LanBatchPreviewDto.toSnapshot(): LanBatchPreview =
    LanBatchPreview(
        batchId = batchId,
        senderDeviceId = senderDeviceId,
        senderDisplayName = senderDisplayName,
        itemCount = itemCount,
        attachmentCount = attachmentCount,
        totalBytes = totalBytes,
        titles = titles,
    )

internal fun com.lomo.nativebridge.LanBatchRecoveryDto.toSnapshot(): LanBatchRecovery =
    LanBatchRecovery(
        sessionId = sessionId,
        preview = preview.toSnapshot(),
        decision =
            when (decision) {
                com.lomo.nativebridge.LanReceivedBatchDecisionDto.PENDING ->
                    LanReceivedBatchDecision.Pending
                com.lomo.nativebridge.LanReceivedBatchDecisionDto.APPROVED ->
                    LanReceivedBatchDecision.Approved
                com.lomo.nativebridge.LanReceivedBatchDecisionDto.REJECTED ->
                    LanReceivedBatchDecision.Rejected
            },
        drive =
            when (drive) {
                com.lomo.nativebridge.LanReceivedBatchDriveDto.AWAITING_DECISION ->
                    LanReceivedBatchDrive.AwaitingDecision
                com.lomo.nativebridge.LanReceivedBatchDriveDto.RECEIVING ->
                    LanReceivedBatchDrive.Receiving
                com.lomo.nativebridge.LanReceivedBatchDriveDto.READY_TO_COMMIT ->
                    LanReceivedBatchDrive.ReadyToCommit
                com.lomo.nativebridge.LanReceivedBatchDriveDto.NEEDS_REBIND ->
                    LanReceivedBatchDrive.NeedsRebind
                com.lomo.nativebridge.LanReceivedBatchDriveDto.APPROVAL_EXPIRED ->
                    LanReceivedBatchDrive.ApprovalExpired
                com.lomo.nativebridge.LanReceivedBatchDriveDto.REJECTED ->
                    LanReceivedBatchDrive.Rejected
                com.lomo.nativebridge.LanReceivedBatchDriveDto.COMPLETE ->
                    LanReceivedBatchDrive.Complete
            },
        confirmedBytes = confirmedBytes,
        items =
            buildList {
                pendingItems.forEach { item ->
                    add(
                        LanReceivedItemRecovery.Pending(
                            itemId = item.itemId,
                            itemIndex = item.itemIndex,
                        ),
                    )
                }
                committedItems.forEach { item ->
                    add(
                        LanReceivedItemRecovery.Committed(
                            itemId = item.itemId,
                            itemIndex = item.itemIndex,
                            memoId = item.memoId,
                        ),
                    )
                }
                failedItems.forEach { item ->
                    add(
                        LanReceivedItemRecovery.Failed(
                            itemId = item.itemId,
                            itemIndex = item.itemIndex,
                            code = item.code,
                        ),
                    )
                }
            }.sortedBy(LanReceivedItemRecovery::itemIndex),
    )

internal fun com.lomo.nativebridge.LanOutgoingBatchDto.toSnapshot(): LanOutgoingBatch =
    LanOutgoingBatch(
        batchId = batchId,
        drive =
            when (drive) {
                com.lomo.nativebridge.LanOutgoingBatchDriveDto.AWAITING_DECISION ->
                    LanOutgoingBatchDrive.AwaitingDecision
                com.lomo.nativebridge.LanOutgoingBatchDriveDto.SENDABLE ->
                    LanOutgoingBatchDrive.Sendable
                com.lomo.nativebridge.LanOutgoingBatchDriveDto.AWAITING_REPORT ->
                    LanOutgoingBatchDrive.AwaitingReport
                com.lomo.nativebridge.LanOutgoingBatchDriveDto.NEEDS_REBIND ->
                    LanOutgoingBatchDrive.NeedsRebind
                com.lomo.nativebridge.LanOutgoingBatchDriveDto.REJECTED ->
                    LanOutgoingBatchDrive.Rejected
                com.lomo.nativebridge.LanOutgoingBatchDriveDto.FAILED ->
                    LanOutgoingBatchDrive.Failed
                com.lomo.nativebridge.LanOutgoingBatchDriveDto.COMPLETE ->
                    LanOutgoingBatchDrive.Complete
            },
        failureCode = failureCode,
        confirmedBytes = confirmedBytes,
        totalBytes = totalBytes,
    )
