package com.lomo.data.engine

import com.lomo.data.engine.lan.LanBatchRecovery
import com.lomo.data.engine.lan.LanCommittableItem
import com.lomo.data.engine.lan.LanDeviceIdentity
import com.lomo.data.engine.lan.LanBatchPreview
import com.lomo.data.engine.lan.LanDiscoveredPeer
import com.lomo.data.engine.lan.LanDiscoveryFacts
import com.lomo.data.engine.lan.LanLocalIdentity
import com.lomo.data.engine.lan.LanNetworkFacts
import com.lomo.data.engine.lan.LanOutgoingBatch
import com.lomo.data.engine.lan.LanOutgoingBatchPhase
import com.lomo.data.engine.lan.LanPairingChallenge
import com.lomo.data.engine.lan.LanPendingBatch
import com.lomo.data.engine.lan.LanPeer
import com.lomo.data.engine.lan.LanPeerPage
import com.lomo.data.engine.lan.LanReceivedBatchDecision
import com.lomo.data.engine.lan.LanReceivedItemRecovery
import com.lomo.data.engine.lan.LanRuntimeInbox
import com.lomo.data.engine.lan.LanServicePhase
import com.lomo.data.engine.lan.LanServiceState
import com.lomo.data.engine.lan.LanSendItemPlan
import com.lomo.data.engine.lan.LanSessionChallenge
import com.lomo.data.engine.lan.LanSessionPhase
import com.lomo.data.engine.lan.LanSessionState
import com.lomo.data.engine.lan.LanTransferShape
import com.lomo.nativebridge.CoreEventListener
import com.lomo.nativebridge.LomoEngine
import com.lomo.nativebridge.PlatformBatchResult
import com.lomo.nativebridge.RenderRequest
import com.lomo.nativebridge.ShutdownOutcome
import com.lomo.nativebridge.Subscription
import com.lomo.nativebridge.WorkspaceDocumentCommand
import com.lomo.nativebridge.WorkspaceScanRequest
import java.util.concurrent.atomic.AtomicReference

/**
 * Sole owner of generated BoltFFI engine/subscription handles.
 *
 * Read leases cover every generated method call. Close takes the write lease after Open → Closing,
 * waits for in-flight readers via the RW lock (never a pre-lock reader counter), then runs the
 * fixed close order once. Callbacks only enqueue bounded invalidations; they never re-enter
 * generated engine methods on the native callback stack.
 */
internal class BoltFfiNativeEnginePort(
    engine: LomoEngine,
    private val exchangeResolver: ExchangeResolver,
    private val onInvalidationFatal: (Throwable) -> Unit = {},
) : WorkspaceNativeEnginePort,
    AutoCloseable {
    private val lease = NativeHandleLease()
    private val engineRef = AtomicReference(engine)
    private val listenerRef = AtomicReference<((NativeCoreEvent) -> Unit)?>(null)
    private val invalidationQueue =
        BoundedInvalidationQueue(
            onFatal = onInvalidationFatal,
            deliver = { event ->
                if (lease.isOpen()) {
                    listenerRef.get()?.invoke(event)
                }
            },
        )

    override fun state(): NativeEngineSnapshot =
        withReadLease { engine ->
            engine.state().toSnapshot()
        }

    override fun lanTransferShape(): LanTransferShape =
        withReadLease { engine ->
            engine.lanTransferShape().let { shape ->
                LanTransferShape(shape.bodySlot, shape.chunkPlaintextBytes)
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

    override fun pollLanListener(nowMs: Long): LanRuntimeInbox =
        withReadLease { engine -> engine.pollLanListener(nowMs).toSnapshot() }

    override fun lanRuntimeInbox(): LanRuntimeInbox =
        withReadLease { engine -> engine.lanRuntimeInbox().toSnapshot() }

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

    override fun lanSessionChallenge(sessionId: String): LanSessionChallenge =
        withReadLease { engine -> engine.lanSessionChallenge(sessionId).toSnapshot() }

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

    override fun lanBatchPreview(batchId: String): LanBatchPreview =
        withReadLease { engine -> engine.lanBatchPreview(batchId).toSnapshot() }

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

    override fun sendLanBatchChunk(
        sessionId: String,
        batchId: String,
        itemIndex: UInt,
        attachmentSlot: UInt,
        chunkIndex: UInt,
        plaintext: ByteArray,
    ) {
        withReadLease { engine ->
            engine.sendLanBatchChunk(
                sessionId,
                batchId,
                itemIndex,
                attachmentSlot,
                chunkIndex,
                plaintext,
            )
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
    ): String =
        withReadLease { engine -> engine.commitReceivedLanItem(batchId, itemIndex, nowMs) }

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

    override fun startWorkspaceScan(
        pageSize: UInt,
        cursor: String?,
        rootPath: String?,
        deadlineMillis: ULong,
    ): String =
        withReadLease { engine ->
            engine.startWorkspaceScan(
                WorkspaceScanRequest(pageSize = pageSize, cursor = cursor, rootPath = rootPath),
                deadlineMillis,
            )
        }

    override fun readWorkspaceScanPage(jobId: String): WorkspaceScanPageSnapshot =
        withReadLease { engine ->
            engine.readWorkspaceScanPage(jobId).toSnapshot(exchangeResolver)
        }

    override fun readWorkspaceProjectionScanPage(jobId: String): WorkspaceProjectionScanPageSnapshot =
        withReadLease { engine ->
            engine.readWorkspaceScanPage(jobId).toProjectionSnapshot()
        }

    override fun beginSafProjectionRebuild(): String =
        withReadLease { engine -> engine.beginSafProjectionRebuild() }

    override fun appendSafProjectionRebuildPage(
        rebuildId: String,
        memos: List<SafMemoProjectionReferenceSnapshot>,
    ) {
        withReadLease { engine ->
            engine.appendSafProjectionRebuildPage(
                rebuildId,
                memos.map { memo ->
                    com.lomo.nativebridge.StoreSafMemoProjectionReference(
                        memoId = memo.memoId,
                        sourcePath = memo.sourcePath,
                        fileFingerprint = memo.fileFingerprint,
                        chronologyEpochMs = memo.chronologyEpochMs,
                        content =
                            com.lomo.nativebridge.WorkspaceMemoContentReference(
                                exchangeToken = memo.content.token,
                                length = memo.content.length,
                                digest = memo.content.digest,
                            ),
                        tags = memo.tags,
                        attachmentPaths = memo.attachmentPaths,
                        hasTodo = memo.hasTodo,
                        hasUrl = memo.hasUrl,
                        reminders = memo.reminders.map { reminder -> reminder.toBridge() },
                    )
                },
            )
        }
    }

    private fun WorkspaceReminderReferenceSnapshot.toBridge():
        com.lomo.nativebridge.WorkspaceReminderReference =
        com.lomo.nativebridge.WorkspaceReminderReference(
            opaqueId = opaqueId,
            revision = revision,
            memoIdentity = memoIdentity,
            sourceStart = sourceStart,
            sourceEnd = sourceEnd,
            tokenFingerprint = tokenFingerprint,
            token = token,
            dueAtLocal = dueAtLocal,
            repeatCount = repeatCount,
            firedCount = firedCount,
            done = done,
            intervalMinutes = intervalMinutes,
            recurrenceCode = recurrenceCode,
        )

    override fun finishSafProjectionRebuild(rebuildId: String): com.lomo.nativebridge.StoreRebuildResult =
        withReadLease { engine -> engine.finishSafProjectionRebuild(rebuildId) }

    override fun abortSafProjectionRebuild(rebuildId: String) {
        withReadLease { engine -> engine.abortSafProjectionRebuild(rebuildId) }
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
    ): com.lomo.nativebridge.StoreMemoPage =
        withReadLease { engine -> engine.queryMemos(query, cursor, pageSize) }

    override fun getMemo(memoId: String): com.lomo.nativebridge.StoreMemoSnapshot? =
        withReadLease { engine -> engine.getMemo(memoId) }

    override fun sidebarProjection(): com.lomo.nativebridge.StoreSidebarProjection =
        withReadLease { engine -> engine.sidebarProjection() }

    override fun listHistoryAttachmentRefs(): List<com.lomo.nativebridge.StoreHistoryAttachmentRef> =
        withReadLease { engine -> engine.listHistoryAttachmentRefs() }

    override fun listMemoHistory(
        memoId: String,
        cursor: String?,
        limit: UInt,
    ): com.lomo.nativebridge.StoreMemoHistoryPage =
        withReadLease { engine -> engine.listMemoHistory(memoId, cursor, limit) }

    override fun applyMemoCommand(
        command: com.lomo.nativebridge.StoreMemoCommand,
    ): com.lomo.nativebridge.StoreMemoCommit =
        withReadLease { engine -> engine.applyMemoCommand(command) }

    override fun commitSafProjectionMutation(
        command: com.lomo.nativebridge.StoreMemoCommand,
        projection: com.lomo.nativebridge.StoreSafMemoProjection?,
    ): com.lomo.nativebridge.StoreMemoCommit =
        withReadLease { engine -> engine.commitSafProjectionMutation(command, projection) }

    override fun startRebuild(batchSize: UInt): com.lomo.nativebridge.StoreRebuildResult =
        withReadLease { engine -> engine.startRebuild(batchSize) }

    override fun stageMedia(
        mediaRoot: String,
        sourceKind: com.lomo.nativebridge.MediaSourceKind,
        sourcePath: String,
        humanNameHint: String,
    ): com.lomo.nativebridge.MediaStagedDto =
        withReadLease { engine -> engine.stageMedia(mediaRoot, sourceKind, sourcePath, humanNameHint) }

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

    override fun promoteMedia(
        workspaceRoot: String,
        plan: com.lomo.nativebridge.MediaPromotePlanDto,
    ): com.lomo.nativebridge.MediaPromoteResultDto =
        withReadLease { engine -> engine.promoteMedia(workspaceRoot, plan) }

    override fun queryMediaManifest(workspaceRoot: String): com.lomo.nativebridge.MediaManifestDto =
        withReadLease { engine -> engine.queryMediaManifest(workspaceRoot) }

    override fun mediaOrphanSweep(
        mediaRoot: String,
        committed: List<com.lomo.nativebridge.MediaCommittedEntryDto>,
        refs: List<com.lomo.nativebridge.MediaAttachmentRefDto>,
        existingTrash: List<com.lomo.nativebridge.MediaTrashEntryDto>,
        nowMs: ULong?,
        recoveryWindowMs: ULong,
    ): com.lomo.nativebridge.MediaOrphanSweepResultDto =
        withReadLease { engine ->
            engine.mediaOrphanSweep(
                mediaRoot,
                committed,
                refs,
                existingTrash,
                nowMs,
                recoveryWindowMs,
            )
        }

    override fun archiveExport(
        workspaceRoot: String,
        archivePath: String,
    ): com.lomo.nativebridge.ArchiveExportResultDto =
        withReadLease { engine -> engine.archiveExport(workspaceRoot, archivePath) }

    override fun archiveInspect(
        archivePath: String,
        stagingRoot: String,
    ): com.lomo.nativebridge.ArchiveInspectResultDto =
        withReadLease { engine -> engine.archiveInspect(archivePath, stagingRoot) }

    override fun archiveImport(
        archivePath: String,
        stagingRoot: String,
    ): com.lomo.nativebridge.ArchiveInspectResultDto =
        withReadLease { engine -> engine.archiveImport(archivePath, stagingRoot) }

    override fun archiveActivate(
        stagingRoot: String,
        liveRoot: String,
        backupRoot: String,
    ) {
        withReadLease { engine -> engine.archiveActivate(stagingRoot, liveRoot, backupRoot) }
    }

    override fun archiveImportActivateRebuild(
        archivePath: String,
        stagingRoot: String,
        liveRoot: String,
        backupRoot: String,
        rebuildBatchSize: UInt,
    ): com.lomo.nativebridge.StoreRebuildResult =
        withReadLease { engine ->
            engine.archiveImportActivateRebuild(
                archivePath,
                stagingRoot,
                liveRoot,
                backupRoot,
                rebuildBatchSize,
            )
        }

    override fun subscribe(listener: (NativeCoreEvent) -> Unit): NativeEngineSubscription {
        // Published before the native call so no invalidation raised by registration is dropped;
        // a failed subscribe rolls the bridge back instead of leaving a listener bound to nothing.
        val displaced = listenerRef.getAndSet(listener)
        val subscription =
            runCatching {
                withReadLease { engine ->
                    engine.subscribe(
                        object : CoreEventListener {
                            override fun onEvent(event: com.lomo.nativebridge.CoreEvent) {
                                // Invalidation enqueue only. No FFI re-entry on this stack.
                                invalidationQueue.enqueue(
                                    NativeCoreEvent(
                                        coreRevision = event.coreRevision,
                                        eventSequence = event.eventSequence,
                                    ),
                                )
                            }
                        },
                    )
                }
            }.onFailure {
                listenerRef.compareAndSet(listener, displaced)
            }.getOrThrow()
        return NativeEngineSubscription {
            val release = ReleaseSequence()
            release.release {
                withReadLease {
                    check(subscription.unsubscribe()) {
                        "Native engine subscription was already unregistered"
                    }
                }
            }
            // Generated handle release is idempotent; do not hold a read lease so engine close
            // can still acquire the write lease without waiting on this stack.
            release.release { closeSubscriptionHandle(subscription) }
            listenerRef.compareAndSet(listener, null)
            release.throwIfFailed()
        }
    }

    override fun close() {
        val ran =
            lease.closeOnce {
                // Fixed order: stop invalidations, drop listener, shutdown, release engine, stop the
                // drain thread. Every step runs even when an earlier one fails, so a refused
                // shutdown cannot leak the engine handle or the drain executor.
                val release = ReleaseSequence()
                release.release(invalidationQueue::stop)
                listenerRef.set(null)
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
                release.release(invalidationQueue::close)
                release.throwIfFailed()
            }
        if (!ran) {
            invalidationQueue.stop()
        }
    }

    private fun closeSubscriptionHandle(subscription: Subscription) {
        subscription.close()
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

private fun LanDiscoveryFacts.toBridge(): com.lomo.nativebridge.LanDiscoverySnapshotDto =
    com.lomo.nativebridge.LanDiscoverySnapshotDto(
        revision = revision,
        peers =
            peers.map { peer ->
                com.lomo.nativebridge.LanDiscoveredPeerDto(
                    deviceId = peer.deviceId,
                    displayName = peer.displayName,
                    host = peer.host,
                    port = peer.port,
                    protocolVersion = peer.protocolVersion,
                )
            },
    )

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

private fun LanSendItemPlan.toBridge(): com.lomo.nativebridge.LanSendItemDto =
    com.lomo.nativebridge.LanSendItemDto(
        timestampMs = timestampMs,
        contentDigest = contentDigest,
        contentBytes = contentBytes,
        title = title,
        attachments =
            attachments.map { attachment ->
                com.lomo.nativebridge.LanAttachmentDto(
                    slot = attachment.slot,
                    sourceReference = attachment.sourceReference,
                    name = attachment.name,
                    digest = attachment.digest,
                    sizeBytes = attachment.sizeBytes,
                )
            },
    )

private fun com.lomo.nativebridge.LanBatchPreviewDto.toSnapshot(): LanBatchPreview =
    LanBatchPreview(
        batchId = batchId,
        senderDeviceId = senderDeviceId,
        senderDisplayName = senderDisplayName,
        itemCount = itemCount,
        attachmentCount = attachmentCount,
        totalBytes = totalBytes,
        titles = titles,
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
        batchRecoveries =
            batchRecoveries.map { recovery ->
                LanBatchRecovery(
                    sessionId = recovery.sessionId,
                    preview = recovery.preview.toSnapshot(),
                    decision =
                        when (recovery.decision) {
                            com.lomo.nativebridge.LanReceivedBatchDecisionDto.PENDING ->
                                LanReceivedBatchDecision.Pending
                            com.lomo.nativebridge.LanReceivedBatchDecisionDto.APPROVED ->
                                LanReceivedBatchDecision.Approved
                            com.lomo.nativebridge.LanReceivedBatchDecisionDto.REJECTED ->
                                LanReceivedBatchDecision.Rejected
                        },
                    items =
                        buildList {
                            recovery.pendingItems.forEach { item ->
                                add(
                                    LanReceivedItemRecovery.Pending(
                                        itemId = item.itemId,
                                        itemIndex = item.itemIndex,
                                    ),
                                )
                            }
                            recovery.committedItems.forEach { item ->
                                add(
                                    LanReceivedItemRecovery.Committed(
                                        itemId = item.itemId,
                                        itemIndex = item.itemIndex,
                                        memoId = item.memoId,
                                    ),
                                )
                            }
                            recovery.failedItems.forEach { item ->
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
            },
        committableItems =
            committableItems.map { item ->
                LanCommittableItem(batchId = item.batchId, itemIndex = item.itemIndex)
            },
        outgoingBatches =
            outgoingBatches.map { batch ->
                LanOutgoingBatch(
                    batchId = batch.batchId,
                    phase =
                        when (batch.phase) {
                            com.lomo.nativebridge.LanOutgoingBatchPhaseDto.AWAITING_APPROVAL ->
                                LanOutgoingBatchPhase.AwaitingApproval
                            com.lomo.nativebridge.LanOutgoingBatchPhaseDto.APPROVED ->
                                LanOutgoingBatchPhase.Approved
                            com.lomo.nativebridge.LanOutgoingBatchPhaseDto.REJECTED ->
                                LanOutgoingBatchPhase.Rejected
                        },
                )
            },
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
