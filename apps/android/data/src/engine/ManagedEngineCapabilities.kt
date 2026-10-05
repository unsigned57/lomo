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
import com.lomo.data.engine.lan.LanSendItemPlan
import com.lomo.data.engine.lan.LanServiceState
import com.lomo.data.engine.lan.LanSessionChallenge
import com.lomo.data.engine.lan.LanSessionState
import com.lomo.data.engine.lan.LanTransferShape
import com.lomo.data.engine.lan.LanProtocolLimits
import com.lomo.domain.model.EngineCommandFailureException
import com.lomo.domain.model.EngineFailureCategory
import com.lomo.domain.model.EngineRetryDisposition
import com.lomo.domain.model.Recurrence
import com.lomo.domain.model.ReminderMarker
import com.lomo.domain.model.ReminderReference
import com.lomo.domain.model.MemoDocumentMutation
import com.lomo.domain.model.markdown.MarkdownRenderDocument
import com.lomo.domain.model.markdown.MarkdownSourceSpan
import com.lomo.domain.repository.MarkdownReminderRepository
import com.lomo.domain.repository.MarkdownWorkspaceRepository
import java.time.LocalDateTime

/** Routes capability calls through the lifecycle owner's read leases. */
internal abstract class ManagedEngineCapabilities :
    WorkspaceNativeAdapter,
    MarkdownWorkspaceRepository,
    MarkdownReminderRepository {
    protected abstract fun <T> withActiveWorkspaceAdapter(block: (RustEngineAdapter) -> T): T

    protected abstract fun <T> withActiveEngineAdapter(block: (RustEngineAdapter) -> T): T

    protected abstract fun rebuildActiveStore(
        batchSize: UInt,
    ): com.lomo.nativebridge.StoreRebuildResult

    override fun renderMarkdown(content: String) =
        renderMarkdown(content = content, schemaVersion = MarkdownRenderDocument.SCHEMA_VERSION)

    override fun remindersForMemo(memoIdentity: String): List<ReminderMarker> =
        withActiveWorkspaceAdapter { adapter ->
            adapter
                .requireMemoSnapshot(memoIdentity)
                .summary
                .reminders
                .map(com.lomo.nativebridge.WorkspaceReminderReference::toSnapshot)
                .map(WorkspaceReminderReferenceSnapshot::toDomainMarker)
        }

    // behavior-contract: in-situ-read-ok: id-only command; engine snapshot is the mutation baseline
    override suspend fun rewriteReminder(
        reference: ReminderReference,
        replacement: String,
    ): MemoDocumentMutation =
        withActiveWorkspaceAdapter { adapter ->
            val before = adapter.requireMemoSnapshot(reference.memoIdentity)
            val reminder =
                before.summary.reminders
                    .map(com.lomo.nativebridge.WorkspaceReminderReference::toSnapshot)
                    .singleOrNull { candidate -> candidate.matches(reference) }
                    ?: throw engineCommandFailure(
                        category = EngineFailureCategory.CONFLICT,
                        code = "stale_snapshot",
                        retryDisposition = EngineRetryDisposition.AFTER_USER_ACTION,
                        diagnostic = "Reminder reference is not present in the current memo revision",
                    )
            val jobId =
                adapter.startWorkspaceDocumentCommand(
                    path = before.summary.sourcePath,
                    expectedState = WorkspaceNativeExpectedState.Match(before.summary.fileFingerprint),
                    command = WorkspaceNativeCommandSpec.RewriteReminder(reminder, replacement),
                )
            adapter.driveToCompletion(jobId)
            val result = adapter.readWorkspaceDocumentCommandResult(jobId)
            result.toMemoDocumentMutation(
                before = before,
                identity = reference.memoIdentity,
                operationId = jobId,
            )
        }

    // behavior-contract: in-situ-read-ok: id-only command; engine snapshot is the mutation baseline
    override suspend fun toggleTask(
        memoIdentity: String,
        actionSpan: MarkdownSourceSpan,
    ): MemoDocumentMutation =
        withActiveWorkspaceAdapter { adapter ->
            val before = adapter.requireMemoSnapshot(memoIdentity)
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
            val jobId =
                adapter.startWorkspaceDocumentCommand(
                    path = before.summary.sourcePath,
                    expectedState = WorkspaceNativeExpectedState.Match(before.summary.fileFingerprint),
                    command =
                        WorkspaceNativeCommandSpec.ToggleTask(
                            identity = memoIdentity,
                            bodyStart = relativeStart,
                            bodyEnd = relativeEnd,
                        ),
                )
            adapter.driveToCompletion(jobId)
            val result = adapter.readWorkspaceDocumentCommandResult(jobId)
            result.toMemoDocumentMutation(
                before = before,
                identity = memoIdentity,
                operationId = jobId,
            )
        }

    override fun updateLanNetworkSnapshot(snapshot: LanNetworkFacts) =
        withActiveEngineAdapter { adapter -> adapter.updateLanNetworkSnapshot(snapshot) }

    override fun lanTransferShape(): LanTransferShape =
        withActiveEngineAdapter(RustEngineAdapter::lanTransferShape)

    override fun lanProtocolLimits(): LanProtocolLimits =
        withActiveEngineAdapter(RustEngineAdapter::lanProtocolLimits)

    override fun updateLanDiscoverySnapshot(snapshot: LanDiscoveryFacts) =
        withActiveEngineAdapter { adapter -> adapter.updateLanDiscoverySnapshot(snapshot) }

    override fun startLanService(): LanServiceState =
        withActiveEngineAdapter(RustEngineAdapter::startLanService)

    override fun stopLanService(): LanServiceState =
        withActiveEngineAdapter(RustEngineAdapter::stopLanService)

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

    override fun lanUnconfirmedBatchChunks(
        batchId: String,
        itemIndex: UInt,
        attachmentSlot: UInt,
    ): List<UInt> =
        withActiveEngineAdapter { adapter ->
            adapter.lanUnconfirmedBatchChunks(batchId, itemIndex, attachmentSlot)
        }

    override fun commitReceivedLanItem(
        batchId: String,
        itemIndex: UInt,
        nowMs: Long,
    ): com.lomo.nativebridge.StoreMemoCommit =
        withActiveEngineAdapter { adapter -> adapter.commitReceivedLanItem(batchId, itemIndex, nowMs) }

    override fun failReceivedLanItem(
        batchId: String,
        itemIndex: UInt,
        code: String,
    ) = withActiveEngineAdapter { adapter -> adapter.failReceivedLanItem(batchId, itemIndex, code) }

    override fun listLanPeers(): LanPeerPage = withActiveEngineAdapter(RustEngineAdapter::listLanPeers)

    override fun revokeLanPeer(deviceId: String, revokedAtMs: Long): LanPeerPage =
        withActiveEngineAdapter { adapter -> adapter.revokeLanPeer(deviceId, revokedAtMs) }

    override fun renderMarkdown(content: String, schemaVersion: UInt) =
        withActiveWorkspaceAdapter { adapter -> adapter.renderMarkdown(content, schemaVersion) }

    override fun driveJob(jobId: String): NativeJobStep =
        withActiveWorkspaceAdapter { adapter -> adapter.driveJob(jobId) }

    override fun startWorkspaceDocumentCommand(
        path: String,
        expectedState: WorkspaceNativeExpectedState,
        command: WorkspaceNativeCommandSpec,
        deadlineMillis: ULong,
    ): String =
        withActiveWorkspaceAdapter { adapter ->
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
    ): com.lomo.nativebridge.StoreMemoPage =
        withActiveWorkspaceAdapter { adapter ->
            adapter.queryMemos(query, cursor, pageSize, startMemoId, backward)
        }

    override fun getMemo(memoId: String): com.lomo.nativebridge.StoreMemoSnapshot? =
        withActiveWorkspaceAdapter { adapter -> adapter.getMemo(memoId) }

    override fun queryCount(query: com.lomo.nativebridge.StoreMemoQuery): ULong =
        withActiveWorkspaceAdapter { adapter -> adapter.queryCount(query) }

    override fun sidebarProjection(): com.lomo.nativebridge.StoreSidebarProjection =
        withActiveWorkspaceAdapter { adapter -> adapter.sidebarProjection() }

    override fun openWorkspaceSession(
        host: com.lomo.nativebridge.PlatformBatchHost,
        timeZone: String,
        mediaStageRoot: String,
    ): String =
        withActiveEngineAdapter { adapter ->
            adapter.openWorkspaceSession(host, timeZone, mediaStageRoot)
        }

    override fun sessionCreateMemo(
        request: com.lomo.nativebridge.SessionCreateMemoRequest,
    ): com.lomo.nativebridge.StoreMemoCommit =
        withActiveWorkspaceAdapter { adapter -> adapter.sessionCreateMemo(request) }

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
    ): com.lomo.nativebridge.StoreMemoCommit =
        withActiveWorkspaceAdapter { adapter -> adapter.sessionPinMemo(request) }

    override fun sessionSearch(
        request: com.lomo.nativebridge.SessionSearchRequest,
    ): com.lomo.nativebridge.SessionSearchOutcome =
        withActiveWorkspaceAdapter { adapter -> adapter.sessionSearch(request) }

    override fun sessionListTasks(): List<com.lomo.nativebridge.SessionTaskItem> =
        withActiveWorkspaceAdapter { adapter -> adapter.sessionListTasks() }

    override fun sessionToggleTask(
        request: com.lomo.nativebridge.SessionToggleTaskRequest,
    ): com.lomo.nativebridge.StoreMemoCommit =
        withActiveWorkspaceAdapter { adapter -> adapter.sessionToggleTask(request) }

    override fun sessionStatistics(
        snapshot: com.lomo.nativebridge.SessionStatisticsSnapshot,
    ): com.lomo.nativebridge.SessionStatistics =
        withActiveWorkspaceAdapter { adapter -> adapter.sessionStatistics(snapshot) }

    override fun sessionListHistory(
        memoId: String,
        cursor: String?,
        limit: UInt,
    ): com.lomo.nativebridge.StoreMemoHistoryPage =
        withActiveWorkspaceAdapter { adapter -> adapter.sessionListHistory(memoId, cursor, limit) }

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

    override fun sessionReminderPlan(nowUtcMs: Long?): com.lomo.nativebridge.StoreReminderPlan =
        withActiveWorkspaceAdapter { adapter -> adapter.sessionReminderPlan(nowUtcMs) }

    override fun sessionSnoozeReminder(
        opaqueId: String,
        snoozeDurationMs: Long,
    ) = withActiveWorkspaceAdapter { adapter -> adapter.sessionSnoozeReminder(opaqueId, snoozeDurationMs) }

    override fun sessionRecoverReminderSnooze() =
        withActiveWorkspaceAdapter { adapter -> adapter.sessionRecoverReminderSnooze() }

    override fun syncRunCycle(
        workspaceRoot: String,
        config: com.lomo.nativebridge.SyncBackendConfigDto,
        secretLeaseId: String,
        applyRemote: Boolean,
    ): com.lomo.nativebridge.SyncCyclePlanSummaryDto =
        withActiveWorkspaceAdapter { adapter ->
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

    override fun startRebuild(batchSize: UInt): com.lomo.nativebridge.StoreRebuildResult =
        rebuildActiveStore(batchSize)

    override fun stageMedia(
        mediaRoot: String,
        sourceKind: com.lomo.nativebridge.MediaSourceKind,
        sourcePath: String,
        humanNameHint: String,
    ): com.lomo.nativebridge.MediaStagedDto =
        withActiveWorkspaceAdapter { adapter ->
            adapter.stageMedia(mediaRoot, sourceKind, sourcePath, humanNameHint)
        }

    override fun recordStageLease(
        workspaceRoot: String?,
        staged: com.lomo.nativebridge.MediaStagedDto,
        ownerKind: com.lomo.nativebridge.MediaStageOwnerKindDto,
        ownerId: String,
    ): com.lomo.nativebridge.MediaStageRecordDto =
        withActiveWorkspaceAdapter { adapter ->
            adapter.recordStageLease(workspaceRoot, staged, ownerKind, ownerId)
        }

    override fun stageRecordsForOwner(
        mediaRoot: String,
        ownerKind: com.lomo.nativebridge.MediaStageOwnerKindDto,
        ownerId: String,
    ): List<com.lomo.nativebridge.MediaStageRecordDto> =
        withActiveWorkspaceAdapter { adapter ->
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
        withActiveWorkspaceAdapter { adapter -> adapter.releaseStageLease(mediaRoot, lease) }

    override fun allocateRecordingTarget(mediaRoot: String, extension: String): String =
        withActiveWorkspaceAdapter { adapter -> adapter.allocateRecordingTarget(mediaRoot, extension) }

    override fun finalizeRecording(
        mediaRoot: String,
        recordingPath: String,
        humanNameHint: String,
    ): com.lomo.nativebridge.MediaStagedDto =
        withActiveWorkspaceAdapter { adapter ->
            adapter.finalizeRecording(mediaRoot, recordingPath, humanNameHint)
        }

    override fun queryMediaManifest(
        workspaceRoot: String,
        verifiedEntries: List<com.lomo.nativebridge.MediaCommittedEntryDto>,
    ): com.lomo.nativebridge.MediaManifestDto =
        withActiveWorkspaceAdapter { adapter ->
            adapter.queryMediaManifest(workspaceRoot, verifiedEntries)
        }

    override fun sessionMediaOrphanSweep(
        nowMs: ULong?,
        recoveryWindowMs: ULong,
        externalDrafts: List<com.lomo.nativebridge.SessionDraftGuardDto>,
    ): com.lomo.nativebridge.SessionMediaSweepReportDto =
        withActiveWorkspaceAdapter { adapter ->
            adapter.sessionMediaOrphanSweep(nowMs, recoveryWindowMs, externalDrafts)
        }

    override fun archiveExport(
        workspaceRoot: String,
        archivePath: String,
    ): com.lomo.nativebridge.ArchiveExportResultDto =
        withActiveWorkspaceAdapter { adapter -> adapter.archiveExport(workspaceRoot, archivePath) }

    override fun sessionImportArchive(
        workspaceRoot: String,
        archivePath: String,
        stagingRoot: String,
    ): com.lomo.nativebridge.StoreRebuildResult =
        withActiveWorkspaceAdapter { adapter ->
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

private fun WorkspaceNativeAdapter.requireMemoSnapshot(
    identity: String,
): com.lomo.nativebridge.StoreMemoSnapshot {
    require(identity.isNotBlank()) { "Memo identity must be non-blank" }
    return getMemo(identity)
        ?: throw engineCommandFailure(
            category = EngineFailureCategory.VALIDATION,
            code = "memo_identity_not_found",
            retryDisposition = EngineRetryDisposition.NEVER,
            diagnostic = "Memo identity was not found in the active store projection",
        )
}

private fun WorkspaceNativeCommandResultSnapshot.toMemoDocumentMutation(
    before: com.lomo.nativebridge.StoreMemoSnapshot,
    identity: String,
    operationId: String,
): MemoDocumentMutation {
    val affected = requireAffectedMemo(path = before.summary.sourcePath, identity = identity)
    val content = affected.content
        ?: throw engineCommandFailure(
            category = EngineFailureCategory.CORRUPTION,
            code = "document_result_content_missing",
            retryDisposition = EngineRetryDisposition.AFTER_USER_ACTION,
            diagnostic = "Rust document command completed without the affected memo body",
            jobId = operationId,
        )
    return MemoDocumentMutation(
        operationId = operationId,
        expectedRevision = before.summary.contentRevision.toLong(),
        expectedFingerprint = before.summary.fileFingerprint,
        facts =
            com.lomo.domain.model.MemoDocumentFacts(
                memoId = affected.identity,
                sourcePath = affected.path,
                fileFingerprint = affected.fingerprint,
                chronologyEpochMs = before.summary.createdAtMs,
                content = content,
                tags = affected.tags,
                attachmentPaths = affected.attachments,
                reminders = affected.reminders.map(WorkspaceReminderReferenceSnapshot::toDomainMarker),
                hasTodo = affected.hasTodo,
                hasUrl = affected.hasUrl,
            ),
    )
}

private fun WorkspaceReminderReferenceSnapshot.matches(reference: ReminderReference): Boolean =
    opaqueId == reference.opaqueId &&
        revision == reference.revision &&
        memoIdentity == reference.memoIdentity &&
        sourceStart == reference.sourceSpan.startByte &&
        sourceEnd == reference.sourceSpan.endByte &&
        tokenFingerprint == reference.tokenFingerprint

private fun WorkspaceReminderReferenceSnapshot.toDomainMarker(): ReminderMarker =
    ReminderMarker(
        dueAt =
            try {
                LocalDateTime.parse(dueAtLocal, ReminderMarker.TIMESTAMP_FORMAT)
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
        recurrence = Recurrence.fromCode(recurrenceCode),
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
