package com.lomo.data.engine

import com.lomo.data.engine.lan.LanChunkSend
import com.lomo.data.engine.lan.LanCommittableItem
import com.lomo.data.engine.lan.LanDeviceIdentity
import com.lomo.data.engine.lan.LanDiscoveredPeer
import com.lomo.data.engine.lan.LanDiscoveryFacts
import com.lomo.data.engine.lan.LanInboxWait
import com.lomo.data.engine.lan.LanLocalIdentity
import com.lomo.data.engine.lan.LanNetworkFacts
import com.lomo.data.engine.lan.LanPairingChallenge
import com.lomo.data.engine.lan.LanPendingBatch
import com.lomo.data.engine.lan.LanPeer
import com.lomo.data.engine.lan.LanPeerPage
import com.lomo.data.engine.lan.LanRuntimeInbox
import com.lomo.data.engine.lan.LanServicePhase
import com.lomo.data.engine.lan.LanServiceState
import com.lomo.data.engine.lan.LanSendItemPlan
import com.lomo.data.engine.lan.LanSessionChallenge
import com.lomo.data.engine.lan.LanSessionPhase
import com.lomo.data.engine.lan.LanSessionState
import com.lomo.data.engine.lan.LanTransferShape
import com.lomo.data.engine.lan.LanProtocolLimits
import com.lomo.nativebridge.LomoEngine
import com.lomo.nativebridge.PlatformBatchResult
import com.lomo.nativebridge.RenderRequest
import com.lomo.nativebridge.ShutdownOutcome
import com.lomo.nativebridge.WorkspaceDocumentCommand
import java.util.concurrent.atomic.AtomicReference

/**
 * Sole owner of generated BoltFFI engine handles.
 *
 * Read leases cover every generated method call. Close takes the write lease after Open → Closing,
 * waits for in-flight readers via the RW lock (never a pre-lock reader counter), then runs the
 * fixed close order once. Store commits publish onto StoreInvalidationBus; journal CoreEvent is
 * not forwarded into Kotlin.
 */
internal class BoltFfiNativeEnginePort(
    engine: LomoEngine,
) : WorkspaceNativeEnginePort,
    AutoCloseable {
    private val lease = NativeHandleLease()
    private val engineRef = AtomicReference(engine)

    override fun state(): NativeEngineSnapshot =
        withReadLease { engine ->
            engine.state().toSnapshot()
        }

    override fun lanTransferShape(): LanTransferShape =
        withReadLease { engine ->
            engine.lanTransferShape().let { shape ->
                LanTransferShape(shape.bodySlot, shape.chunkPlaintextBytes, shape.maxInflightChunks)
            }
        }

    override fun lanProtocolLimits(): LanProtocolLimits =
        withReadLease { engine ->
            engine.lanProtocolLimits().let { limits ->
                LanProtocolLimits(
                    protocolVersion = limits.protocolVersion,
                    pairingTtlMs = limits.pairingTtlMs,
                    sessionTtlMs = limits.sessionTtlMs,
                    approvalTtlMs = limits.approvalTtlMs,
                )
            }
        }

    override fun updateLanNetworkSnapshot(snapshot: LanNetworkFacts) {
        withReadLease { engine -> engine.updateLanNetworkSnapshot(snapshot.toBridge()) }
    }

    override fun updateLanDiscoverySnapshot(snapshot: LanDiscoveryFacts) {
        withReadLease { engine -> engine.updateLanDiscoverySnapshot(snapshot.toBridge()) }
    }

    override fun startLanService(): LanServiceState =
        withReadLease { engine -> engine.startLanService().toSnapshot() }

    override fun stopLanService(): LanServiceState =
        withReadLease { engine -> engine.stopLanService().toSnapshot() }

    override fun awaitLanInbox(lastGeneration: ULong, timeoutMs: ULong): LanInboxWait =
        withReadLease { engine ->
            engine.awaitLanInbox(lastGeneration, timeoutMs).toSnapshot()
        }

    override fun listLanDiscoveredPeers(): List<LanDiscoveredPeer> =
        withReadLease { engine -> engine.listLanDiscoveredPeers().map { peer -> peer.toSnapshot() } }

    override fun configureLanIdentity(identity: LanDeviceIdentity): LanLocalIdentity =
        withReadLease { engine ->
            engine.configureLanIdentity(
                com.lomo.nativebridge.LanDeviceIdentityDto(
                    publicKey = identity.publicKey,
                    displayName = identity.displayName,
                ),
            ).let { configured ->
                LanLocalIdentity(
                    deviceId = configured.deviceId,
                    displayName = configured.displayName,
                )
            }
        }

    override fun beginLanPairing(
        peerDeviceId: String,
        nowMs: Long,
        ttlMs: Long,
    ): LanPairingChallenge =
        withReadLease { engine ->
            engine.beginLanPairing(peerDeviceId, nowMs, ttlMs).toSnapshot()
        }

    override fun lanPairingChallenge(pairingId: String): LanPairingChallenge =
        withReadLease { engine -> engine.lanPairingChallenge(pairingId).toSnapshot() }

    override fun confirmLanPairing(
        pairingId: String,
        signature: ByteArray,
        nowMs: Long,
    ) {
        withReadLease { engine -> engine.confirmLanPairing(pairingId, signature, nowMs) }
    }

    override fun declineLanPairing(pairingId: String) {
        withReadLease { engine -> engine.declineLanPairing(pairingId) }
    }

    override fun beginLanSession(
        peerDeviceId: String,
        nowMs: Long,
        ttlMs: Long,
    ): LanSessionChallenge =
        withReadLease { engine -> engine.beginLanSession(peerDeviceId, nowMs, ttlMs).toSnapshot() }

    override fun confirmLanSession(
        sessionId: String,
        signature: ByteArray,
        nowMs: Long,
    ) {
        withReadLease { engine -> engine.confirmLanSession(sessionId, signature, nowMs) }
    }

    override fun lanSessionState(sessionId: String): LanSessionState =
        withReadLease { engine -> engine.lanSessionSnapshot(sessionId).toSnapshot() }

    override fun prepareLanBatch(
        sessionId: String,
        batchId: String,
        items: List<LanSendItemPlan>,
    ) {
        withReadLease { engine ->
            engine.prepareLanBatch(sessionId, batchId, items.map(LanSendItemPlan::toBridge))
        }
    }

    override fun approveLanBatch(
        sessionId: String,
        batchId: String,
        nowMs: Long,
        ttlMs: Long,
    ) {
        withReadLease { engine ->
            engine.approveLanBatch(sessionId, batchId, nowMs, ttlMs)
        }
    }

    override fun rejectLanBatch(
        sessionId: String,
        batchId: String,
        rejectedAtMs: Long,
    ) {
        withReadLease { engine -> engine.rejectLanBatch(sessionId, batchId, rejectedAtMs) }
    }

    override fun sendLanBatchChunks(chunks: List<LanChunkSend>) {
        withReadLease { engine ->
            engine.sendLanBatchChunks(chunks.map(LanChunkSend::toBridge))
        }
    }

    @OptIn(ExperimentalUnsignedTypes::class)
    override fun lanUnconfirmedBatchChunks(
        batchId: String,
        itemIndex: UInt,
        attachmentSlot: UInt,
    ): List<UInt> =
        withReadLease { engine ->
            engine.lanUnconfirmedBatchChunks(batchId, itemIndex, attachmentSlot).toList()
        }

    override fun commitReceivedLanItem(
        batchId: String,
        itemIndex: UInt,
        nowMs: Long,
    ): com.lomo.nativebridge.StoreMemoCommit =
        withReadLease { engine -> engine.commitReceivedLanItem(batchId, itemIndex, nowMs) }

    override fun failReceivedLanItem(
        batchId: String,
        itemIndex: UInt,
        code: String,
    ) = withReadLease { engine -> engine.failReceivedLanItem(batchId, itemIndex, code) }

    override fun listLanPeers(): LanPeerPage =
        withReadLease { engine -> engine.listLanPeers().toSnapshot() }

    override fun revokeLanPeer(
        deviceId: String,
        revokedAtMs: Long,
    ): LanPeerPage =
        withReadLease { engine -> engine.revokeLanPeer(deviceId, revokedAtMs).toSnapshot() }

    override fun pollJob(jobId: String): NativeJobStep =
        withReadLease { engine ->
            engine.pollJob(jobId).toNative()
        }

    override fun submitPlatformResult(
        jobId: String,
        result: PlatformBatchResult,
    ): NativeJobStep =
        withReadLease { engine ->
            engine.submitPlatformResult(jobId, result).toNative()
        }

    override fun renderMarkdown(
        content: String,
        schemaVersion: UInt,
    ) =
        withReadLease { engine ->
            engine
                .renderMarkdown(RenderRequest(content = content, schemaVersion = schemaVersion))
                .toDomainDocument(sourceContent = content)
        }

    override fun startWorkspaceDocumentCommand(
        path: String,
        expectedState: WorkspaceNativeExpectedState,
        command: WorkspaceNativeCommandSpec,
        deadlineMillis: ULong,
    ): String =
        withReadLease { engine ->
            engine.startWorkspaceDocumentCommand(
                WorkspaceDocumentCommand(
                    path = path,
                    expectedState = expectedState.toBridge(),
                    command = command.toBridge(),
                    history = command.historyWrite()?.toBridge(),
                ),
                deadlineMillis,
            )
        }

    override fun readWorkspaceDocumentCommandResult(jobId: String): WorkspaceNativeCommandResultSnapshot =
        withReadLease { engine ->
            engine.readWorkspaceDocumentCommandResult(jobId).toSnapshot()
        }

    override fun queryMemos(
        query: com.lomo.nativebridge.StoreMemoQuery,
        cursor: com.lomo.nativebridge.StorePageCursor?,
        pageSize: UInt,
        startMemoId: String?,
        backward: Boolean,
    ): com.lomo.nativebridge.StoreMemoPage =
        withReadLease { engine ->
            engine.queryMemos(query, cursor, pageSize, startMemoId, backward)
        }

    override fun getMemo(memoId: String): com.lomo.nativebridge.StoreMemoSnapshot? =
        withReadLease { engine -> engine.getMemo(memoId) }

    override fun queryCount(query: com.lomo.nativebridge.StoreMemoQuery): ULong =
        withReadLease { engine -> engine.queryCount(query) }

    override fun sidebarProjection(): com.lomo.nativebridge.StoreSidebarProjection =
        withReadLease { engine -> engine.sidebarProjection() }

    override fun openWorkspaceSession(
        host: com.lomo.nativebridge.PlatformBatchHost,
        timeZone: String,
        mediaStageRoot: String,
    ): String =
        withReadLease { engine -> engine.openWorkspaceSession(host, timeZone, mediaStageRoot) }

    override fun sessionCreateMemo(
        request: com.lomo.nativebridge.SessionCreateMemoRequest,
    ): com.lomo.nativebridge.StoreMemoCommit =
        withReadLease { engine -> engine.sessionCreateMemo(request) }

    override fun sessionUpdateMemo(
        request: com.lomo.nativebridge.SessionUpdateMemoRequest,
    ): com.lomo.nativebridge.StoreMemoCommit =
        withReadLease { engine -> engine.sessionUpdateMemo(request) }

    override fun sessionDeleteMemo(
        request: com.lomo.nativebridge.SessionDeleteMemoRequest,
    ): com.lomo.nativebridge.StoreMemoCommit =
        withReadLease { engine -> engine.sessionDeleteMemo(request) }

    override fun sessionPinMemo(
        request: com.lomo.nativebridge.SessionPinMemoRequest,
    ): com.lomo.nativebridge.StoreMemoCommit =
        withReadLease { engine -> engine.sessionPinMemo(request) }

    override fun sessionSearch(
        request: com.lomo.nativebridge.SessionSearchRequest,
    ): com.lomo.nativebridge.SessionSearchOutcome =
        withReadLease { engine -> engine.sessionSearch(request) }

    override fun sessionListTasks(): List<com.lomo.nativebridge.SessionTaskItem> =
        withReadLease { engine -> engine.sessionListTasks() }

    override fun sessionToggleTask(
        request: com.lomo.nativebridge.SessionToggleTaskRequest,
    ): com.lomo.nativebridge.StoreMemoCommit =
        withReadLease { engine -> engine.sessionToggleTask(request) }

    override fun sessionStatistics(
        snapshot: com.lomo.nativebridge.SessionStatisticsSnapshot,
    ): com.lomo.nativebridge.SessionStatistics =
        withReadLease { engine -> engine.sessionStatistics(snapshot) }

    override fun sessionListHistory(
        memoId: String,
        cursor: String?,
        limit: UInt,
    ): com.lomo.nativebridge.StoreMemoHistoryPage =
        withReadLease { engine -> engine.sessionListHistory(memoId, cursor, limit) }

    override fun sessionRestoreMemo(
        request: com.lomo.nativebridge.SessionRestoreRequest,
    ): com.lomo.nativebridge.StoreMemoCommit =
        withReadLease { engine -> engine.sessionRestoreMemo(request) }

    override fun sessionRestoreRevision(
        request: com.lomo.nativebridge.SessionRestoreRevisionRequest,
    ): com.lomo.nativebridge.StoreMemoCommit =
        withReadLease { engine -> engine.sessionRestoreRevision(request) }

    override fun sessionPermanentlyDeleteMemo(
        request: com.lomo.nativebridge.SessionRestoreRequest,
    ): com.lomo.nativebridge.StoreMemoCommit =
        withReadLease { engine -> engine.sessionPermanentlyDeleteMemo(request) }

    override fun sessionPermanentlyDeleteMany(
        request: com.lomo.nativebridge.StoreMemoBatchDelete,
    ): com.lomo.nativebridge.StoreMemoBatchCommit =
        withReadLease { engine -> engine.sessionPermanentlyDeleteMany(request) }

    override fun sessionReminderPlan(nowUtcMs: Long?): com.lomo.nativebridge.StoreReminderPlan =
        withReadLease { engine -> engine.sessionReminderPlan(nowUtcMs) }

    override fun sessionSnoozeReminder(
        opaqueId: String,
        snoozeDurationMs: Long,
    ) = withReadLease { engine -> engine.sessionSnoozeReminder(opaqueId, snoozeDurationMs) }

    override fun sessionRecoverReminderSnooze() =
        withReadLease { engine -> engine.sessionRecoverReminderSnooze() }

    override fun syncRunCycle(
        workspaceRoot: String,
        config: com.lomo.nativebridge.SyncBackendConfigDto,
        secretLeaseId: String,
        applyRemote: Boolean,
    ): com.lomo.nativebridge.SyncCyclePlanSummaryDto =
        withReadLease { engine ->
            engine.syncRunCycle(
                workspaceRoot,
                config,
                secretLeaseId,
                applyRemote,
            )
        }

    override fun commitWorkspaceDocumentFacts(
        command: com.lomo.nativebridge.StoreMemoCommand,
        projection: com.lomo.nativebridge.StoreSafMemoProjection,
    ): com.lomo.nativebridge.StoreMemoCommit =
        withReadLease { engine -> engine.commitWorkspaceDocumentFacts(command, projection) }

    override fun startRebuild(batchSize: UInt): com.lomo.nativebridge.StoreRebuildResult =
        withReadLease { engine -> engine.startRebuild(batchSize) }

    override fun stageMedia(
        mediaRoot: String,
        sourceKind: com.lomo.nativebridge.MediaSourceKind,
        sourcePath: String,
        humanNameHint: String,
    ): com.lomo.nativebridge.MediaStagedDto =
        withReadLease { engine -> engine.stageMedia(mediaRoot, sourceKind, sourcePath, humanNameHint) }

    override fun recordStageLease(
        workspaceRoot: String?,
        staged: com.lomo.nativebridge.MediaStagedDto,
        ownerKind: com.lomo.nativebridge.MediaStageOwnerKindDto,
        ownerId: String,
    ): com.lomo.nativebridge.MediaStageRecordDto =
        withReadLease { engine -> engine.recordStageLease(workspaceRoot, staged, ownerKind, ownerId) }

    override fun stageRecordsForOwner(
        mediaRoot: String,
        ownerKind: com.lomo.nativebridge.MediaStageOwnerKindDto,
        ownerId: String,
    ): List<com.lomo.nativebridge.MediaStageRecordDto> =
        withReadLease { engine -> engine.stageRecordsForOwner(mediaRoot, ownerKind, ownerId) }

    override fun transferStageLease(
        mediaRoot: String,
        from: com.lomo.nativebridge.MediaStageLeaseDto,
        to: com.lomo.nativebridge.MediaStageLeaseDto,
    ): com.lomo.nativebridge.MediaStageReleaseDto =
        withReadLease { engine -> engine.transferStageLease(mediaRoot, from, to) }

    override fun releaseStageLease(
        mediaRoot: String,
        lease: com.lomo.nativebridge.MediaStageLeaseDto,
    ): com.lomo.nativebridge.MediaStageReleaseDto =
        withReadLease { engine -> engine.releaseStageLease(mediaRoot, lease) }

    override fun allocateRecordingTarget(
        mediaRoot: String,
        extension: String,
    ): String = withReadLease { engine -> engine.allocateRecordingTarget(mediaRoot, extension) }

    override fun finalizeRecording(
        mediaRoot: String,
        recordingPath: String,
        humanNameHint: String,
    ): com.lomo.nativebridge.MediaStagedDto =
        withReadLease { engine -> engine.finalizeRecording(mediaRoot, recordingPath, humanNameHint) }

    override fun queryMediaManifest(
        workspaceRoot: String,
        verifiedEntries: List<com.lomo.nativebridge.MediaCommittedEntryDto>,
    ): com.lomo.nativebridge.MediaManifestDto =
        withReadLease { engine -> engine.queryMediaManifest(workspaceRoot, verifiedEntries) }

    override fun sessionMediaOrphanSweep(
        nowMs: ULong?,
        recoveryWindowMs: ULong,
    ): com.lomo.nativebridge.SessionMediaSweepReportDto =
        withReadLease { engine -> engine.sessionMediaOrphanSweep(nowMs, recoveryWindowMs) }

    override fun archiveExport(
        workspaceRoot: String,
        archivePath: String,
    ): com.lomo.nativebridge.ArchiveExportResultDto =
        withReadLease { engine -> engine.archiveExport(workspaceRoot, archivePath) }

    override fun sessionImportArchive(
        workspaceRoot: String,
        archivePath: String,
        stagingRoot: String,
    ): com.lomo.nativebridge.StoreRebuildResult =
        withReadLease { engine ->
            engine.sessionImportArchive(
                workspaceRoot,
                archivePath,
                stagingRoot,
            )
        }

    override fun close() {
        lease.closeOnce {
            val release = ReleaseSequence()
            engineRef.getAndSet(null)?.let { engine ->
                release.release {
                    val outcome = engine.shutdown(SHUTDOWN_DEADLINE_MILLIS)
                    check(
                        outcome == ShutdownOutcome.COMPLETED ||
                            outcome == ShutdownOutcome.ALREADY_SHUTDOWN,
                    ) {
                        "Native engine shutdown failed with outcome=$outcome"
                    }
                }
                release.release(engine::close)
            }
            release.throwIfFailed()
        }
    }

    private inline fun <T> withReadLease(crossinline block: (LomoEngine) -> T): T =
        lease.withRead {
            val engine =
                engineRef.get()
                    ?: error("Native engine handle is closed")
            block(engine)
        }

    private companion object {
        const val SHUTDOWN_DEADLINE_MILLIS: ULong = 5_000uL
    }
}

private fun com.lomo.nativebridge.LanServiceSnapshotDto.toSnapshot(): LanServiceState =
    LanServiceState(
        phase =
            when (phase) {
                com.lomo.nativebridge.LanServicePhaseDto.STOPPED -> LanServicePhase.Stopped
                com.lomo.nativebridge.LanServicePhaseDto.LISTENING -> LanServicePhase.Listening
            },
        listenAddress = listenAddress,
    )

private fun com.lomo.nativebridge.LanDiscoveredPeerDto.toSnapshot(): LanDiscoveredPeer =
    LanDiscoveredPeer(
        deviceId = deviceId,
        displayName = displayName,
        host = host,
        port = port,
        protocolVersion = protocolVersion,
    )

private fun com.lomo.nativebridge.LanPairingChallengeDto.toSnapshot(): LanPairingChallenge =
    LanPairingChallenge(
        pairingId = pairingId,
        peerDeviceId = peerDeviceId,
        peerDisplayName = peerDisplayName,
        shortCode = shortCode,
        transcriptToSign = transcriptToSign,
        deadlineMs = deadlineMs,
    )

private fun com.lomo.nativebridge.LanSessionChallengeDto.toSnapshot(): LanSessionChallenge =
    LanSessionChallenge(
        sessionId = sessionId,
        peerDeviceId = peerDeviceId,
        transcriptToSign = transcriptToSign,
        deadlineMs = deadlineMs,
    )

private fun com.lomo.nativebridge.LanSessionSnapshotDto.toSnapshot(): LanSessionState =
    LanSessionState(
        sessionId = sessionId,
        peerDeviceId = peerDeviceId,
        phase =
            when (phase) {
                com.lomo.nativebridge.LanSessionPhaseDto.AUTHENTICATED -> LanSessionPhase.Authenticated
            },
    )

private fun com.lomo.nativebridge.LanInboxWaitDto.toSnapshot(): LanInboxWait =
    LanInboxWait(
        generation = generation,
        inbox = inbox?.toSnapshot(),
        rejectedConnectionCount = rejectedConnectionCount,
        lastRejectionDiagnostic = lastRejectionDiagnostic,
    )

private fun com.lomo.nativebridge.LanRuntimeInboxDto.toSnapshot(): LanRuntimeInbox =
    LanRuntimeInbox(
        pairingChallenges = pairingChallenges.map { challenge -> challenge.toSnapshot() },
        sessionChallenges = sessionChallenges.map { challenge -> challenge.toSnapshot() },
        activeSessions = activeSessions.map { session -> session.toSnapshot() },
        pendingBatches =
            pendingBatches.map { pending ->
                LanPendingBatch(
                    sessionId = pending.sessionId,
                    preview = pending.preview.toSnapshot(),
                )
            },
        batchRecoveries = batchRecoveries.map { recovery -> recovery.toSnapshot() },
        committableItems =
            committableItems.map { item ->
                LanCommittableItem(batchId = item.batchId, itemIndex = item.itemIndex)
            },
        outgoingBatches = outgoingBatches.map { batch -> batch.toSnapshot() },
    )

private fun com.lomo.nativebridge.LanPeerDto.toSnapshot(): LanPeer =
    LanPeer(
        deviceId = deviceId,
        displayName = displayName,
        publicKey = publicKey,
        pairedAtMs = pairedAtMs,
        revoked = revoked,
        revokedAtMs = revokedAtMs,
    )

private fun com.lomo.nativebridge.LanPeerPageDto.toSnapshot(): LanPeerPage =
    LanPeerPage(
        peers = peers.map { peer -> peer.toSnapshot() },
        total = total,
    )
