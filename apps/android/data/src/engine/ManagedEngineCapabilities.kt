package com.lomo.data.engine

import com.lomo.data.engine.lan.LanChunkSend
import com.lomo.data.engine.lan.LanDeviceIdentity
import com.lomo.data.engine.lan.LanDiscoveredPeer
import com.lomo.data.engine.lan.LanDiscoveryFacts
import com.lomo.data.engine.lan.LanInboxWait
import com.lomo.data.engine.lan.LanLocalIdentity
import com.lomo.data.engine.lan.LanNetworkFacts
import com.lomo.data.engine.lan.LanPairingChallenge
import com.lomo.data.engine.lan.LanPeerPage
import com.lomo.data.engine.lan.LanProtocolLimits
import com.lomo.data.engine.lan.LanSendItemPlan
import com.lomo.data.engine.lan.LanServicePhase
import com.lomo.data.engine.lan.LanServiceState
import com.lomo.data.engine.lan.LanSessionChallenge
import com.lomo.data.engine.lan.LanSessionState
import com.lomo.data.engine.lan.LanTransferShape
import com.lomo.data.engine.store.toStoreLong
import com.lomo.data.reminder.REMINDER_DUE_AT_FORMATTER
import com.lomo.data.reminder.toDomainRecurrence
import com.lomo.domain.model.EngineCommandFailureException
import com.lomo.domain.model.EngineFailureCategory
import com.lomo.domain.model.EngineRetryDisposition
import com.lomo.domain.model.ReminderMarker
import com.lomo.domain.model.ReminderReference
import com.lomo.domain.model.markdown.MarkdownRenderDocument
import com.lomo.domain.model.markdown.MarkdownSourceSpan
import com.lomo.domain.repository.MarkdownReminderRepository
import com.lomo.domain.repository.MarkdownWorkspaceRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

/** Routes capability calls through the lifecycle owner's read leases. */
internal abstract class ManagedEngineCapabilities :
    WorkspaceNativeAdapter,
    MarkdownWorkspaceRepository,
    MarkdownReminderRepository {
    protected abstract val engineDispatcher: CoroutineDispatcher

    protected abstract fun <T> withActiveWorkspaceAdapter(block: (RustEngineAdapter) -> T): T

    protected abstract fun <T> withProjectionAdapter(block: (RustEngineAdapter) -> T): T

    protected abstract fun <T> withActiveEngineAdapter(block: (RustEngineAdapter) -> T): T

    /**
     * Capabilities whose answer is defined without a native owner. The installed adapter is
     * captured in one step under the routing lock — no presence probe followed by a second
     * call that retirement can race — and [unhosted] is the domain answer when none exists
     * (cold start, recovery that never installed bootstrap, the reopen retirement window).
     */
    protected abstract fun <T> withInstalledEngineAdapter(unhosted: () -> T, block: (RustEngineAdapter) -> T): T

    protected abstract fun rebuildActiveStore(batchSize: UInt): com.lomo.nativebridge.StoreRebuildResult

    override fun renderMarkdown(content: String) =
        renderMarkdown(content = content, schemaVersion = MarkdownRenderDocument.SCHEMA_VERSION)

    override suspend fun loadMemoPreviews(
        requests: List<com.lomo.domain.model.MemoPreviewRenderRequest>,
    ): Map<String, MarkdownRenderDocument> = fetchUncachedMemoPreviews(
        session = this,
        ioDispatcher = engineDispatcher,
        requests = requests,
    )

    override fun remindersForMemo(memoIdentity: String): List<ReminderMarker> = withProjectionAdapter { adapter ->
        adapter
            .requireMemoSnapshot(memoIdentity)
            .summary
            .reminders
            .map(com.lomo.nativebridge.WorkspaceReminderReference::toSnapshot)
            .map(WorkspaceReminderReferenceSnapshot::toDomainMarker)
    }

    override suspend fun planReminderRewrite(
        memoId: String,
        reminderId: String,
        expectedToken: String,
        replacement: String,
    ): com.lomo.domain.model.ReminderRewritePlan = withActiveWorkspaceAdapter { adapter ->
        val plan = adapter.sessionPlanReminderRewrite(
            com.lomo.nativebridge.SessionReminderRewriteRequest(memoId, reminderId, expectedToken, replacement),
        )
        com.lomo.domain.model.ReminderRewritePlan(
            memoId = plan.memoId,
            expectedRevision = plan.expectedRevision.toStoreLong("reminder rewrite revision"),
            expectedFingerprint = plan.expectedFingerprint,
            content = plan.content,
        )
    }

    // The owner commits source and projection together before this read returns the body.
    override suspend fun toggleTask(
        operationId: com.lomo.domain.model.MemoOperationId,
        memoIdentity: String,
        actionSpan: MarkdownSourceSpan,
    ): String = withContext(engineDispatcher) {
        withActiveWorkspaceAdapter { adapter ->
            val relativeStart = actionSpan.startByte
            val relativeEnd = actionSpan.endByte
            if (relativeStart >= relativeEnd) {
                throw engineCommandFailure(
                    category = EngineFailureCategory.VALIDATION,
                    code = "task_action_span_out_of_bounds",
                    retryDisposition = EngineRetryDisposition.NEVER,
                    diagnostic = "Task action span is outside the rendered memo body",
                )
            }
            adapter.sessionToggleTaskMarker(operationId.value, memoIdentity, relativeStart, relativeEnd)
            adapter.requireMemoSnapshot(memoIdentity).body
        }
    }

    override fun updateLanNetworkSnapshot(snapshot: LanNetworkFacts) =
        withActiveEngineAdapter { adapter -> adapter.updateLanNetworkSnapshot(snapshot) }

    override fun lanTransferShape(): LanTransferShape = withActiveEngineAdapter(RustEngineAdapter::lanTransferShape)

    override fun lanProtocolLimits(): LanProtocolLimits = withActiveEngineAdapter(RustEngineAdapter::lanProtocolLimits)

    override fun updateLanDiscoverySnapshot(snapshot: LanDiscoveryFacts) =
        withActiveEngineAdapter { adapter -> adapter.updateLanDiscoverySnapshot(snapshot) }

    override fun startLanService(): LanServiceState = withActiveEngineAdapter(RustEngineAdapter::startLanService)

    /**
     * Idempotent teardown. The listener lives inside its native adapter and dies with it, so
     * with no adapter installed the postcondition already holds: Stopped. LAN failure handlers
     * call this from process-scope catch blocks; demanding an adapter here was process-fatal.
     */
    override fun stopLanService(): LanServiceState = withInstalledEngineAdapter(
        unhosted = { LanServiceState(LanServicePhase.Stopped, listenAddress = null) },
        block = RustEngineAdapter::stopLanService,
    )

    override fun awaitLanInbox(lastGeneration: ULong, timeoutMs: ULong): LanInboxWait =
        withActiveEngineAdapter { adapter -> adapter.awaitLanInbox(lastGeneration, timeoutMs) }

    override fun listLanDiscoveredPeers(): List<LanDiscoveredPeer> =
        withActiveEngineAdapter(RustEngineAdapter::listLanDiscoveredPeers)

    override fun configureLanIdentity(identity: LanDeviceIdentity): LanLocalIdentity =
        withActiveEngineAdapter { adapter -> adapter.configureLanIdentity(identity) }

    override fun beginLanPairing(peerDeviceId: String, nowMs: Long, ttlMs: Long): LanPairingChallenge =
        withActiveEngineAdapter { adapter -> adapter.beginLanPairing(peerDeviceId, nowMs, ttlMs) }

    override fun lanPairingChallenge(pairingId: String): LanPairingChallenge =
        withActiveEngineAdapter { adapter -> adapter.lanPairingChallenge(pairingId) }

    override fun confirmLanPairing(pairingId: String, signature: ByteArray, nowMs: Long) =
        withActiveEngineAdapter { adapter -> adapter.confirmLanPairing(pairingId, signature, nowMs) }

    override fun declineLanPairing(pairingId: String) =
        withActiveEngineAdapter { adapter -> adapter.declineLanPairing(pairingId) }

    override fun beginLanSession(peerDeviceId: String, nowMs: Long, ttlMs: Long): LanSessionChallenge =
        withActiveEngineAdapter { adapter -> adapter.beginLanSession(peerDeviceId, nowMs, ttlMs) }

    override fun confirmLanSession(sessionId: String, signature: ByteArray, nowMs: Long) =
        withActiveEngineAdapter { adapter -> adapter.confirmLanSession(sessionId, signature, nowMs) }

    override fun lanSessionState(sessionId: String): LanSessionState =
        withActiveEngineAdapter { adapter -> adapter.lanSessionState(sessionId) }

    override fun prepareLanBatch(sessionId: String, batchId: String, items: List<LanSendItemPlan>) =
        withActiveEngineAdapter { adapter -> adapter.prepareLanBatch(sessionId, batchId, items) }

    override fun approveLanBatch(sessionId: String, batchId: String, nowMs: Long, ttlMs: Long) =
        withActiveEngineAdapter { adapter -> adapter.approveLanBatch(sessionId, batchId, nowMs, ttlMs) }

    override fun rejectLanBatch(sessionId: String, batchId: String, rejectedAtMs: Long) =
        withActiveEngineAdapter { adapter -> adapter.rejectLanBatch(sessionId, batchId, rejectedAtMs) }

    override fun sendLanBatchChunks(chunks: List<LanChunkSend>) =
        withActiveEngineAdapter { adapter -> adapter.sendLanBatchChunks(chunks) }

    override fun lanUnconfirmedBatchChunks(batchId: String, itemIndex: UInt, attachmentSlot: UInt): List<UInt> =
        withActiveEngineAdapter { adapter ->
            adapter.lanUnconfirmedBatchChunks(batchId, itemIndex, attachmentSlot)
        }

    override fun commitReceivedLanItem(
        batchId: String,
        itemIndex: UInt,
        nowMs: Long,
    ): com.lomo.nativebridge.StoreMemoCommit =
        withActiveEngineAdapter { adapter -> adapter.commitReceivedLanItem(batchId, itemIndex, nowMs) }

    override fun failReceivedLanItem(batchId: String, itemIndex: UInt, code: String) =
        withActiveEngineAdapter { adapter -> adapter.failReceivedLanItem(batchId, itemIndex, code) }

    /**
     * Passive projection of engine-durable trust. Before any adapter exists, or while one is
     * being retired, no peer can be authenticated, so the honest page is empty; the next
     * post-attach inbox generation repopulates it. Pairing/session commands stay fail-closed.
     */
    override fun listLanPeers(): LanPeerPage = withInstalledEngineAdapter(
        unhosted = { LanPeerPage(peers = emptyList(), total = 0u) },
        block = RustEngineAdapter::listLanPeers,
    )

    override fun revokeLanPeer(deviceId: String, revokedAtMs: Long): LanPeerPage =
        withActiveEngineAdapter { adapter -> adapter.revokeLanPeer(deviceId, revokedAtMs) }

    override fun renderMarkdown(content: String, schemaVersion: UInt) =
        withActiveEngineAdapter { adapter -> adapter.renderMarkdown(content, schemaVersion) }

    override fun driveJob(jobId: String): NativeJobStep =
        withActiveWorkspaceAdapter { adapter -> adapter.driveJob(jobId) }

    override fun startWorkspaceDocumentCommand(
        path: String,
        expectedState: WorkspaceNativeExpectedState,
        command: WorkspaceNativeCommandSpec,
        deadlineMillis: ULong,
    ): String = withActiveWorkspaceAdapter { adapter ->
        adapter.startWorkspaceDocumentCommand(path, expectedState, command, deadlineMillis)
    }

    override fun readWorkspaceDocumentCommandResult(jobId: String): WorkspaceNativeCommandResultSnapshot =
        withActiveWorkspaceAdapter { adapter -> adapter.readWorkspaceDocumentCommandResult(jobId) }

    override fun queryMemos(
        query: com.lomo.nativebridge.StoreMemoQuery,
        cursor: com.lomo.nativebridge.StorePageCursor?,
        pageSize: UInt,
        startMemoId: String?,
        backward: Boolean,
    ): com.lomo.nativebridge.StoreMemoPage = withProjectionAdapter { adapter ->
        adapter.queryMemos(query, cursor, pageSize, startMemoId, backward)
    }

    override fun queryGalleryPage(
        cursor: com.lomo.nativebridge.StorePageCursor?,
        pageSize: UInt,
        startMemoId: String?,
        backward: Boolean,
    ): com.lomo.nativebridge.StoreGalleryPage = withProjectionAdapter { adapter ->
        adapter.queryGalleryPage(cursor, pageSize, startMemoId, backward)
    }

    override fun getMemo(memoId: String): com.lomo.nativebridge.StoreMemoSnapshot? =
        withProjectionAdapter { adapter -> adapter.getMemo(memoId) }

    override fun queryCount(query: com.lomo.nativebridge.StoreMemoQuery): ULong =
        withProjectionAdapter { adapter -> adapter.queryCount(query) }

    override fun sidebarProjection(): com.lomo.nativebridge.StoreSidebarProjection =
        withProjectionAdapter { adapter -> adapter.sidebarProjection() }

    override fun openWorkspaceSession(
        host: com.lomo.nativebridge.PlatformBatchHost,
        timeZone: String,
        dateFormat: String,
        mediaStageRoot: String,
    ): com.lomo.nativebridge.SessionOpenResult = withActiveEngineAdapter { adapter ->
        adapter.openWorkspaceSession(host, timeZone, dateFormat, mediaStageRoot)
    }

    override fun sessionCreateMemo(
        request: com.lomo.nativebridge.SessionCreateMemoRequest,
        observer: com.lomo.nativebridge.MemoCreateObserver,
    ): com.lomo.nativebridge.StoreMemoCommit =
        withActiveWorkspaceAdapter { adapter -> adapter.sessionCreateMemo(request, observer) }

    override fun sessionUpdateMemo(
        request: com.lomo.nativebridge.SessionUpdateMemoRequest,
    ): com.lomo.nativebridge.StoreMemoCommit =
        withActiveWorkspaceAdapter { adapter -> adapter.sessionUpdateMemo(request) }

    override fun sessionDeleteMemo(
        request: com.lomo.nativebridge.SessionDeleteMemoRequest,
    ): com.lomo.nativebridge.StoreMemoCommit =
        withActiveWorkspaceAdapter { adapter -> adapter.sessionDeleteMemo(request) }

    override fun sessionPinMemo(
        request: com.lomo.nativebridge.SessionPinMemoRequest,
    ): com.lomo.nativebridge.StoreMemoCommit = withActiveWorkspaceAdapter { adapter -> adapter.sessionPinMemo(request) }

    override fun sessionSearch(
        request: com.lomo.nativebridge.SessionSearchRequest,
    ): com.lomo.nativebridge.SessionSearchOutcome = withProjectionAdapter { adapter -> adapter.sessionSearch(request) }

    override fun sessionWarmupSearchIndex() {
        withProjectionAdapter { adapter -> adapter.sessionWarmupSearchIndex() }
    }

    override fun sessionListTasks(): List<com.lomo.nativebridge.SessionTaskItem> =
        withProjectionAdapter { adapter -> adapter.sessionListTasks() }

    override fun sessionToggleTask(
        request: com.lomo.nativebridge.SessionToggleTaskRequest,
    ): com.lomo.nativebridge.StoreMemoCommit =
        withActiveWorkspaceAdapter { adapter -> adapter.sessionToggleTask(request) }

    override fun sessionStatistics(
        snapshot: com.lomo.nativebridge.SessionStatisticsSnapshot,
    ): com.lomo.nativebridge.SessionStatistics =
        withProjectionAdapter { adapter -> adapter.sessionStatistics(snapshot) }

    override fun sessionListHistory(
        memoId: String,
        cursor: String?,
        limit: UInt,
    ): com.lomo.nativebridge.StoreMemoHistoryPage =
        withProjectionAdapter { adapter -> adapter.sessionListHistory(memoId, cursor, limit) }

    override fun sessionRestoreMemo(
        request: com.lomo.nativebridge.SessionRestoreRequest,
    ): com.lomo.nativebridge.StoreMemoCommit =
        withActiveWorkspaceAdapter { adapter -> adapter.sessionRestoreMemo(request) }

    override fun sessionRestoreRevision(
        request: com.lomo.nativebridge.SessionRestoreRevisionRequest,
    ): com.lomo.nativebridge.StoreMemoCommit =
        withActiveWorkspaceAdapter { adapter -> adapter.sessionRestoreRevision(request) }

    override fun sessionPermanentlyDeleteMemo(
        request: com.lomo.nativebridge.SessionRestoreRequest,
    ): com.lomo.nativebridge.StoreMemoCommit =
        withActiveWorkspaceAdapter { adapter -> adapter.sessionPermanentlyDeleteMemo(request) }

    override fun sessionPermanentlyDeleteMany(
        request: com.lomo.nativebridge.StoreMemoBatchDelete,
    ): com.lomo.nativebridge.StoreMemoBatchCommit =
        withActiveWorkspaceAdapter { adapter -> adapter.sessionPermanentlyDeleteMany(request) }

    override fun sessionEmptyTrash(
        operationId: String,
        cancellation: com.lomo.nativebridge.EmptyTrashCancellation,
    ): com.lomo.nativebridge.SessionEmptyTrashResult =
        withActiveWorkspaceAdapter { adapter -> adapter.sessionEmptyTrash(operationId, cancellation) }

    override fun sessionPlanReminderRewrite(
        request: com.lomo.nativebridge.SessionReminderRewriteRequest,
    ): com.lomo.nativebridge.SessionReminderRewritePlan =
        withActiveWorkspaceAdapter { adapter -> adapter.sessionPlanReminderRewrite(request) }

    override fun sessionReminderPlan(nowUtcMs: Long?): com.lomo.nativebridge.StoreReminderPlan =
        withProjectionAdapter { adapter -> adapter.sessionReminderPlan(nowUtcMs) }

    override fun sessionSnoozeReminder(opaqueId: String, interval: com.lomo.nativebridge.ReminderSnoozeInterval) =
        withActiveWorkspaceAdapter { adapter -> adapter.sessionSnoozeReminder(opaqueId, interval) }

    override fun sessionRecoverReminderSnooze() =
        withActiveWorkspaceAdapter { adapter -> adapter.sessionRecoverReminderSnooze() }

    override fun sessionRenderMemoPreviews(
        items: List<com.lomo.nativebridge.SessionMemoPreviewRequest>,
        schemaVersion: UInt,
    ): List<com.lomo.nativebridge.SessionMemoPreviewOutcome> = withProjectionAdapter { adapter ->
        adapter.sessionRenderMemoPreviews(items, schemaVersion)
    }

    override fun syncPrivateStateRoot(workspaceRoot: String): String =
        withProjectionAdapter { adapter -> adapter.syncPrivateStateRoot(workspaceRoot) }

    override fun syncRunCycle(
        workspaceRoot: String,
        config: com.lomo.nativebridge.SyncBackendConfigDto,
        secretLeaseId: String,
        applyRemote: Boolean,
    ): com.lomo.nativebridge.SyncCyclePlanSummaryDto = withActiveWorkspaceAdapter { adapter ->
        adapter.syncRunCycle(
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
        withActiveWorkspaceAdapter { adapter -> adapter.commitWorkspaceDocumentFacts(command, projection) }

    override fun startRebuild(batchSize: UInt): com.lomo.nativebridge.StoreRebuildResult = rebuildActiveStore(batchSize)

    override fun stageMedia(
        mediaRoot: String,
        sourceKind: com.lomo.nativebridge.MediaSourceKind,
        sourcePath: String,
        humanNameHint: String,
    ): com.lomo.nativebridge.MediaStagedDto = withActiveWorkspaceAdapter { adapter ->
        adapter.stageMedia(mediaRoot, sourceKind, sourcePath, humanNameHint)
    }

    override fun recordStageLease(
        staged: com.lomo.nativebridge.MediaStagedDto,
        ownerKind: com.lomo.nativebridge.MediaStageOwnerKindDto,
        ownerId: String,
    ): com.lomo.nativebridge.MediaStageRecordDto = withActiveWorkspaceAdapter { adapter ->
        adapter.recordStageLease(staged, ownerKind, ownerId)
    }

    override fun stageRecordsForOwner(
        mediaRoot: String,
        ownerKind: com.lomo.nativebridge.MediaStageOwnerKindDto,
        ownerId: String,
    ): List<com.lomo.nativebridge.MediaStageRecordDto> = withProjectionAdapter { adapter ->
        adapter.stageRecordsForOwner(mediaRoot, ownerKind, ownerId)
    }

    override fun transferStageLease(
        mediaRoot: String,
        from: com.lomo.nativebridge.MediaStageLeaseDto,
        to: com.lomo.nativebridge.MediaStageLeaseDto,
    ): com.lomo.nativebridge.MediaStageReleaseDto =
        withActiveWorkspaceAdapter { adapter -> adapter.transferStageLease(mediaRoot, from, to) }

    override fun releaseStageLease(
        mediaRoot: String,
        lease: com.lomo.nativebridge.MediaStageLeaseDto,
    ): com.lomo.nativebridge.MediaStageReleaseDto =
        withProjectionAdapter { adapter -> adapter.releaseStageLease(mediaRoot, lease) }

    override fun allocateRecordingTarget(mediaRoot: String, extension: String): String =
        withActiveWorkspaceAdapter { adapter -> adapter.allocateRecordingTarget(mediaRoot, extension) }

    override fun finalizeRecording(
        mediaRoot: String,
        recordingPath: String,
        humanNameHint: String,
    ): com.lomo.nativebridge.MediaStagedDto = withActiveWorkspaceAdapter { adapter ->
        adapter.finalizeRecording(mediaRoot, recordingPath, humanNameHint)
    }

    override fun queryMediaManifest(
        workspaceRoot: String,
        verifiedEntries: List<com.lomo.nativebridge.MediaCommittedEntryDto>,
    ): com.lomo.nativebridge.MediaManifestDto = withActiveWorkspaceAdapter { adapter ->
        adapter.queryMediaManifest(workspaceRoot, verifiedEntries)
    }

    override fun sessionMediaOrphanSweep(
        nowMs: ULong?,
        recoveryWindowMs: ULong,
        externalDrafts: List<com.lomo.nativebridge.SessionDraftGuardDto>,
    ): com.lomo.nativebridge.SessionMediaSweepReportDto = withActiveWorkspaceAdapter { adapter ->
        adapter.sessionMediaOrphanSweep(nowMs, recoveryWindowMs, externalDrafts)
    }

    override fun sessionExportArchive(
        fileNameHint: String?,
        mediaCapabilities: List<String>,
    ): com.lomo.nativebridge.ArchiveExportResultDto = withActiveWorkspaceAdapter { adapter ->
        adapter.sessionExportArchive(fileNameHint, mediaCapabilities)
    }

    override fun sessionImportArchive(
        workspaceRoot: String,
        archivePath: String,
        stagingRoot: String,
    ): com.lomo.nativebridge.StoreRebuildResult = withActiveWorkspaceAdapter { adapter ->
        adapter.sessionImportArchive(
            workspaceRoot,
            archivePath,
            stagingRoot,
        )
    }
}

internal fun WorkspaceNativeAdapter.driveToCompletion(jobId: String) {
    val failure = when (val terminal = driveJob(jobId)) {
        NativeJobStep.Completed -> null

        is NativeJobStep.Failed -> terminal.failure.toWorkspaceCommandException()

        is NativeJobStep.BlockedByConflict -> terminal.failure.toWorkspaceCommandException()

        else -> engineCommandFailure(
            category = EngineFailureCategory.INTERNAL,
            code = "workspace_job_not_terminal",
            retryDisposition = EngineRetryDisposition.AFTER_USER_ACTION,
            diagnostic = "Workspace job did not reach a terminal state",
            jobId = jobId,
        )
    }
    failure?.let { throw it }
}

private fun EngineFailureSnapshot.toWorkspaceCommandException(): EngineCommandFailureException =
    EngineCommandFailureException(toEngineCommandFailure())

private fun WorkspaceNativeAdapter.requireMemoSnapshot(identity: String): com.lomo.nativebridge.StoreMemoSnapshot {
    require(identity.isNotBlank()) { "Memo identity must be non-blank" }
    return getMemo(identity)
        ?: throw engineCommandFailure(
            category = EngineFailureCategory.VALIDATION,
            code = "memo_identity_not_found",
            retryDisposition = EngineRetryDisposition.NEVER,
            diagnostic = "Memo identity was not found in the active store projection",
        )
}

private fun WorkspaceReminderReferenceSnapshot.toDomainMarker(): ReminderMarker = ReminderMarker(
    dueAtLocal = dueAtLocal,
    dueAt =
    try {
        LocalDateTime.parse(dueAtLocal, REMINDER_DUE_AT_FORMATTER)
    } catch (error: java.time.format.DateTimeParseException) {
        throw WorkspaceRenderBoundaryException(
            code = "invalid_reminder_due_at",
            message = "Rust reminder due-at fact is invalid: ${error.message}",
            cause = error,
        )
    },
    repeatCount = repeatCount.toIntExact("repeat_count"),
    firedCount = firedCount.toIntExact("fired_count"),
    done = done,
    intervalMinutes = intervalMinutes.toIntExact("interval_minutes"),
    recurrence = recurrence.toDomainRecurrence(),
    reference =
    ReminderReference(
        opaqueId = opaqueId,
        revision = revision,
        memoIdentity = memoIdentity,
        sourceSpan = MarkdownSourceSpan(startByte = sourceStart, endByte = sourceEnd),
        tokenFingerprint = tokenFingerprint,
        fingerprintOrdinal = fingerprintOrdinal,
        embeddedId = embeddedId,
    ),
    token = token,
)

private fun UInt.toIntExact(field: String): Int {
    if (this > Int.MAX_VALUE.toUInt()) {
        throw WorkspaceRenderBoundaryException(
            code = "invalid_reminder_$field",
            message = "Rust reminder $field exceeds Kotlin Int",
        )
    }
    return toInt()
}
