package com.lomo.data.engine

import com.lomo.data.engine.lan.LanDeviceIdentity
import com.lomo.data.engine.lan.LanBatchPreview
import com.lomo.data.engine.lan.LanDiscoveredPeer
import com.lomo.data.engine.lan.LanDiscoveryFacts
import com.lomo.data.engine.lan.LanLocalIdentity
import com.lomo.data.engine.lan.LanNetworkFacts
import com.lomo.data.engine.lan.LanPairingChallenge
import com.lomo.data.engine.lan.LanPeerPage
import com.lomo.data.engine.lan.LanRuntimeInbox
import com.lomo.data.engine.lan.LanServiceState
import com.lomo.data.engine.lan.LanSendItemPlan
import com.lomo.data.engine.lan.LanSessionChallenge
import com.lomo.data.engine.lan.LanSessionState
import com.lomo.data.engine.lan.LanTransferShape
import com.lomo.domain.model.EngineFailureCategory
import com.lomo.domain.model.EngineReadiness
import com.lomo.domain.model.EngineRetryDisposition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Adapts one native engine handle into domain readiness.
 *
 * Constructed only through [acquire], which owns the native port for the whole fallible acquisition
 * so a caller either receives a fully-owned adapter or nothing at all.
 *
 * Not the process owner — [ManagedEngineSession] opens/closes adapters and is the sole
 * [com.lomo.domain.repository.EngineReadinessRepository].
 */
internal class RustEngineAdapter private constructor(
    private val native: WorkspaceNativeEnginePort,
    private val platformBatchRunner: PlatformBatchRunner,
    private val projectionScanNowMillis: () -> Long,
    private val sourceDocumentFingerprintProbe: ((String) -> String?)?,
    private val safMediaPromoter: ((List<com.lomo.nativebridge.MediaPromotePlanDto>, String) -> Unit)? = null,
) : WorkspaceNativeAdapter,
    AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val _readiness = MutableStateFlow<EngineReadiness>(EngineReadiness.Opening)
    private var lastEventSequence: ULong? = null
    private val subscriptionRef = AtomicReference<NativeEngineSubscription?>(null)
    private val jobDriveMonitorLock = Any()
    private val jobDriveMonitors = mutableMapOf<String, JobDriveMonitor>()
    private val projectionRebuildCoordinator = ProjectionRebuildCoordinator()

    val readiness: StateFlow<EngineReadiness> = _readiness.asStateFlow()

    /**
     * Holds the adapter's snapshot lock while a session decides whether to publish this adapter.
     * Native invalidations use the same lock, so Ready validation and authority publication form
     * one boundary transaction instead of a check-then-commit race.
     */
    @Synchronized
    fun <T> withReadinessAtCommit(block: (EngineReadiness) -> T): T = block(_readiness.value)

    @Synchronized
    fun resnapshot() {
        check(!closed.get()) { "Rust engine adapter is closed" }
        publishBoundarySnapshot()
    }

    override fun lanTransferShape(): LanTransferShape = native.lanTransferShape()

    override fun updateLanNetworkSnapshot(snapshot: LanNetworkFacts) {
        native.updateLanNetworkSnapshot(snapshot)
    }

    override fun updateLanDiscoverySnapshot(snapshot: LanDiscoveryFacts) {
        native.updateLanDiscoverySnapshot(snapshot)
    }

    override fun startLanService(): LanServiceState = native.startLanService()

    override fun stopLanService(): LanServiceState = native.stopLanService()

    override fun listLanDiscoveredPeers(): List<LanDiscoveredPeer> = native.listLanDiscoveredPeers()

    override fun configureLanIdentity(identity: LanDeviceIdentity): LanLocalIdentity =
        native.configureLanIdentity(identity)

    override fun beginLanPairing(
        peerDeviceId: String,
        nowMs: Long,
        ttlMs: Long,
    ): LanPairingChallenge = native.beginLanPairing(peerDeviceId, nowMs, ttlMs)

    override fun pollLanListener(nowMs: Long): LanRuntimeInbox = native.pollLanListener(nowMs)

    override fun lanRuntimeInbox(): LanRuntimeInbox = native.lanRuntimeInbox()

    override fun lanPairingChallenge(pairingId: String): LanPairingChallenge =
        native.lanPairingChallenge(pairingId)

    override fun confirmLanPairing(
        pairingId: String,
        signature: ByteArray,
        nowMs: Long,
    ) {
        native.confirmLanPairing(pairingId, signature, nowMs)
    }

    override fun declineLanPairing(pairingId: String) {
        native.declineLanPairing(pairingId)
    }

    override fun beginLanSession(
        peerDeviceId: String,
        nowMs: Long,
        ttlMs: Long,
    ): LanSessionChallenge = native.beginLanSession(peerDeviceId, nowMs, ttlMs)

    override fun lanSessionChallenge(sessionId: String): LanSessionChallenge =
        native.lanSessionChallenge(sessionId)

    override fun confirmLanSession(
        sessionId: String,
        signature: ByteArray,
        nowMs: Long,
    ) {
        native.confirmLanSession(sessionId, signature, nowMs)
    }

    override fun lanSessionState(sessionId: String): LanSessionState =
        native.lanSessionState(sessionId)

    override fun prepareLanBatch(
        sessionId: String,
        batchId: String,
        items: List<LanSendItemPlan>,
    ) {
        native.prepareLanBatch(sessionId, batchId, items)
    }

    override fun lanBatchPreview(batchId: String): LanBatchPreview =
        native.lanBatchPreview(batchId)

    override fun approveLanBatch(
        sessionId: String,
        batchId: String,
        nowMs: Long,
        ttlMs: Long,
    ) {
        native.approveLanBatch(sessionId, batchId, nowMs, ttlMs)
    }

    override fun rejectLanBatch(
        sessionId: String,
        batchId: String,
        rejectedAtMs: Long,
    ) {
        native.rejectLanBatch(sessionId, batchId, rejectedAtMs)
    }

    override fun sendLanBatchChunk(
        sessionId: String,
        batchId: String,
        itemIndex: UInt,
        attachmentSlot: UInt,
        chunkIndex: UInt,
        plaintext: ByteArray,
    ) {
        native.sendLanBatchChunk(
            sessionId,
            batchId,
            itemIndex,
            attachmentSlot,
            chunkIndex,
            plaintext,
        )
    }

    override fun lanUnconfirmedBatchChunks(
        batchId: String,
        itemIndex: UInt,
        attachmentSlot: UInt,
    ): List<UInt> = native.lanUnconfirmedBatchChunks(batchId, itemIndex, attachmentSlot)

    override fun commitReceivedLanItem(
        batchId: String,
        itemIndex: UInt,
        nowMs: Long,
    ): String = native.commitReceivedLanItem(batchId, itemIndex, nowMs)

    override fun listLanPeers(): LanPeerPage = native.listLanPeers()

    override fun revokeLanPeer(
        deviceId: String,
        revokedAtMs: Long,
    ): LanPeerPage = native.revokeLanPeer(deviceId, revokedAtMs)

    override fun renderMarkdown(
        content: String,
        schemaVersion: UInt,
    ) = native.renderMarkdown(content, schemaVersion)

    override fun startWorkspaceScan(
        pageSize: UInt,
        cursor: String?,
        rootPath: String?,
        deadlineMillis: ULong,
    ): String = native.startWorkspaceScan(pageSize, cursor, rootPath, deadlineMillis)

    override fun driveJob(jobId: String): NativeJobStep {
        val holder =
            synchronized(jobDriveMonitorLock) {
                jobDriveMonitors.getOrPut(jobId, ::JobDriveMonitor).also { it.waiters += 1 }
            }
        return try {
            synchronized(holder.monitor) {
                holder.result ?: platformBatchRunner.drive(jobId).also { holder.result = it }
            }
        } finally {
            synchronized(jobDriveMonitorLock) {
                holder.waiters -= 1
                if (holder.waiters == 0 && jobDriveMonitors[jobId] === holder) {
                    jobDriveMonitors.remove(jobId)
                }
            }
        }
    }

    override fun readWorkspaceScanPage(jobId: String): WorkspaceScanPageSnapshot =
        native.readWorkspaceScanPage(jobId)

    override fun startWorkspaceTrashScan(
        pageSize: UInt,
        cursor: String?,
        deadlineMillis: ULong,
    ): String = native.startWorkspaceTrashScan(pageSize, cursor, deadlineMillis)

    override fun readWorkspaceTrashProjectionScanPage(
        jobId: String,
    ): WorkspaceTrashProjectionScanPageSnapshot =
        native.readWorkspaceTrashProjectionScanPage(jobId)

    override fun startWorkspaceHistoryScan(
        pageSize: UInt,
        cursor: String?,
        deadlineMillis: ULong,
    ): String = native.startWorkspaceHistoryScan(pageSize, cursor, deadlineMillis)

    override fun readWorkspaceHistoryProjectionScanPage(
        jobId: String,
    ): WorkspaceHistoryProjectionScanPageSnapshot =
        native.readWorkspaceHistoryProjectionScanPage(jobId)

    fun rebuildSafProjectionFromWorkspaceScan(): com.lomo.nativebridge.StoreRebuildResult {
        return projectionRebuildCoordinator.run {
            rebuildSafProjection(
                native = native,
                driveJob = ::driveJob,
                nowMillis = projectionScanNowMillis,
            )
        }
    }

    override fun startWorkspaceDocumentCommand(
        path: String,
        expectedState: WorkspaceNativeExpectedState,
        command: WorkspaceNativeCommandSpec,
        deadlineMillis: ULong,
    ): String =
        native.startWorkspaceDocumentCommand(
            path = path,
            expectedState = expectedState,
            command = command,
            deadlineMillis = deadlineMillis,
        )

    override fun readWorkspaceDocumentCommandResult(jobId: String): WorkspaceNativeCommandResultSnapshot =
        native.readWorkspaceDocumentCommandResult(jobId)

    override fun startWorkspaceTrashCommand(
        path: String,
        expectedFingerprint: String,
        command: WorkspaceNativeTrashCommandSpec,
        deadlineMillis: ULong,
    ): String =
        native.startWorkspaceTrashCommand(
            path = path,
            expectedFingerprint = expectedFingerprint,
            command = command,
            deadlineMillis = deadlineMillis,
        )

    override fun readWorkspaceTrashCommandResult(jobId: String): WorkspaceNativeTrashCommandResultSnapshot =
        native.readWorkspaceTrashCommandResult(jobId)

    override fun queryMemos(
        query: com.lomo.nativebridge.StoreMemoQuery,
        cursor: com.lomo.nativebridge.StorePageCursor?,
        pageSize: UInt,
    ): com.lomo.nativebridge.StoreMemoPage = native.queryMemos(query, cursor, pageSize)

    override fun getMemo(memoId: String): com.lomo.nativebridge.StoreMemoSnapshot? = native.getMemo(memoId)

    override fun queryCount(query: com.lomo.nativebridge.StoreMemoQuery): ULong = native.queryCount(query)

    override fun selectMemoPromotePlans(
        content: String,
        candidates: List<com.lomo.nativebridge.MediaPromotePlanDto>,
    ): List<com.lomo.nativebridge.MediaPromotePlanDto> =
        native.selectMemoPromotePlans(content, candidates)

    override fun memoStatisticsRows(): List<com.lomo.nativebridge.StoreMemoStatisticsRow> =
        native.memoStatisticsRows()

    override fun sourceDocumentFingerprint(sourcePath: String): String? {
        val probe = sourceDocumentFingerprintProbe
        return if (probe == null) {
            native.sourceDocumentFingerprint(sourcePath)
        } else {
            probe(sourcePath)
        }
    }

    /**
     * Platform execution of the memo-bound media promote for SAF workspaces.
     *
     * Deliberately not on [WorkspaceNativeAdapter]: only the SAF memo command boundary may invoke
     * it, under the same operation-id and command-kind rules as the Rust store transaction.
     */
    fun promoteSafMedia(
        promotes: List<com.lomo.nativebridge.MediaPromotePlanDto>,
        operationId: String,
    ) {
        if (promotes.isEmpty()) return
        val promoter =
            checkNotNull(safMediaPromoter) {
                "SAF media promoter is not configured on this adapter"
            }
        promoter.invoke(promotes, operationId)
    }

    override fun sidebarProjection(): com.lomo.nativebridge.StoreSidebarProjection =
        native.sidebarProjection()

    override fun listHistoryAttachmentRefs(): List<com.lomo.nativebridge.StoreHistoryAttachmentRef> =
        native.listHistoryAttachmentRefs()

    override fun listMemoHistory(
        memoId: String,
        cursor: String?,
        limit: UInt,
    ): com.lomo.nativebridge.StoreMemoHistoryPage =
        native.listMemoHistory(memoId, cursor, limit)

    override fun queryReminderPlan(
        query: com.lomo.nativebridge.StoreReminderQuery,
    ): com.lomo.nativebridge.StoreReminderPlan = native.queryReminderPlan(query)

    override fun applyMemoCommand(
        command: com.lomo.nativebridge.StoreMemoCommand,
        onPublication: (com.lomo.nativebridge.StoreMemoCommit) -> Unit,
    ): com.lomo.nativebridge.StoreMemoCommit = native.applyMemoCommand(command, onPublication)

    override fun openWorkspaceSession(
        host: com.lomo.nativebridge.PlatformBatchHost,
        timeZone: String,
    ): String = native.openWorkspaceSession(host, timeZone)

    override fun sessionCreateMemo(
        request: com.lomo.nativebridge.SessionCreateMemoRequest,
    ): com.lomo.nativebridge.StoreMemoCommit = native.sessionCreateMemo(request)

    override fun sessionUpdateMemo(
        request: com.lomo.nativebridge.SessionUpdateMemoRequest,
    ): com.lomo.nativebridge.StoreMemoCommit = native.sessionUpdateMemo(request)

    override fun sessionDeleteMemo(
        request: com.lomo.nativebridge.SessionDeleteMemoRequest,
    ): com.lomo.nativebridge.StoreMemoCommit = native.sessionDeleteMemo(request)

    override fun sessionPinMemo(
        request: com.lomo.nativebridge.SessionPinMemoRequest,
    ): com.lomo.nativebridge.StoreMemoCommit = native.sessionPinMemo(request)

    override fun sessionGetMemo(memoId: String): com.lomo.nativebridge.SessionMemoView? =
        native.sessionGetMemo(memoId)

    override fun sessionSearch(
        request: com.lomo.nativebridge.SessionSearchRequest,
    ): com.lomo.nativebridge.SessionSearchOutcome = native.sessionSearch(request)

    override fun sessionListTasks(): List<com.lomo.nativebridge.SessionTaskItem> =
        native.sessionListTasks()

    override fun sessionToggleTask(
        request: com.lomo.nativebridge.SessionToggleTaskRequest,
    ): com.lomo.nativebridge.StoreMemoCommit = native.sessionToggleTask(request)

    override fun sessionReviewCandidates(
        zone: String,
        date: com.lomo.nativebridge.SessionCivilDate,
    ): List<com.lomo.nativebridge.SessionReviewCandidate> =
        native.sessionReviewCandidates(zone, date)

    override fun sessionCompleteReview(
        zone: String,
        date: com.lomo.nativebridge.SessionCivilDate,
        memoId: String,
    ) {
        native.sessionCompleteReview(zone, date, memoId)
    }

    override fun sessionStatistics(
        snapshot: com.lomo.nativebridge.SessionStatisticsSnapshot,
    ): com.lomo.nativebridge.SessionStatistics = native.sessionStatistics(snapshot)

    override fun sessionListHistory(
        memoId: String,
        cursor: String?,
        limit: UInt,
    ): com.lomo.nativebridge.StoreMemoHistoryPage = native.sessionListHistory(memoId, cursor, limit)

    override fun sessionRestoreMemo(
        request: com.lomo.nativebridge.SessionRestoreRequest,
    ): com.lomo.nativebridge.SessionRestoreResult = native.sessionRestoreMemo(request)

    override fun sessionRestoreRevision(
        request: com.lomo.nativebridge.SessionRestoreRevisionRequest,
    ): com.lomo.nativebridge.StoreMemoCommit = native.sessionRestoreRevision(request)

    override fun sessionPermanentlyDeleteMemo(
        request: com.lomo.nativebridge.SessionRestoreRequest,
    ): com.lomo.nativebridge.SessionRestoreResult = native.sessionPermanentlyDeleteMemo(request)

    override fun sessionReminderPlan(nowUtcMs: Long?): com.lomo.nativebridge.StoreReminderPlan =
        native.sessionReminderPlan(nowUtcMs)

    override fun sessionRecordReminderFired(
        request: com.lomo.nativebridge.SessionFireReminderRequest,
    ): com.lomo.nativebridge.StoreMemoCommit = native.sessionRecordReminderFired(request)

    override fun permanentDeleteMany(
        request: com.lomo.nativebridge.StoreMemoBatchDelete,
    ): com.lomo.nativebridge.StoreMemoBatchCommit = native.permanentDeleteMany(request)

    override fun commitSafPermanentDeleteMany(
        request: com.lomo.nativebridge.StoreMemoBatchDelete,
    ): com.lomo.nativebridge.StoreMemoBatchCommit = native.commitSafPermanentDeleteMany(request)

    override fun commitSafProjectionMutation(
        command: com.lomo.nativebridge.StoreMemoCommand,
        projection: com.lomo.nativebridge.StoreSafMemoProjection?,
    ): com.lomo.nativebridge.StoreMemoCommit =
        native.commitSafProjectionMutation(command, projection)

    override fun commitWorkspaceDocumentFacts(
        command: com.lomo.nativebridge.StoreMemoCommand,
        projection: com.lomo.nativebridge.StoreSafMemoProjection,
    ): com.lomo.nativebridge.StoreMemoCommit =
        native.commitWorkspaceDocumentFacts(command, projection)

    override fun beginSafMemoCreate(
        begin: com.lomo.nativebridge.StoreSafMemoCreateBegin,
    ): com.lomo.nativebridge.StoreSafMemoCreateBeginResult = native.beginSafMemoCreate(begin)

    override fun rollbackSafMemoCreate(
        operationId: String,
        memoId: String,
    ): com.lomo.nativebridge.StoreSafMemoRollbackResult = native.rollbackSafMemoCreate(operationId, memoId)

    override fun startRebuild(batchSize: UInt): com.lomo.nativebridge.StoreRebuildResult =
        native.startRebuild(batchSize)

    override fun stageMedia(
        mediaRoot: String,
        sourceKind: com.lomo.nativebridge.MediaSourceKind,
        sourcePath: String,
        humanNameHint: String,
    ): com.lomo.nativebridge.MediaStagedDto =
        native.stageMedia(mediaRoot, sourceKind, sourcePath, humanNameHint)

    override fun allocateRecordingTarget(
        mediaRoot: String,
        extension: String,
    ): String = native.allocateRecordingTarget(mediaRoot, extension)

    override fun finalizeRecording(
        mediaRoot: String,
        recordingPath: String,
        humanNameHint: String,
    ): com.lomo.nativebridge.MediaStagedDto =
        native.finalizeRecording(mediaRoot, recordingPath, humanNameHint)

    override fun promoteMedia(
        workspaceRoot: String,
        plan: com.lomo.nativebridge.MediaPromotePlanDto,
    ): com.lomo.nativebridge.MediaPromoteResultDto = native.promoteMedia(workspaceRoot, plan)

    override fun queryMediaManifest(workspaceRoot: String): com.lomo.nativebridge.MediaManifestDto =
        native.queryMediaManifest(workspaceRoot)

    override fun mediaOrphanSweep(
        mediaRoot: String,
        committed: List<com.lomo.nativebridge.MediaCommittedEntryDto>,
        refs: List<com.lomo.nativebridge.MediaAttachmentRefDto>,
        existingTrash: List<com.lomo.nativebridge.MediaTrashEntryDto>,
        nowMs: ULong?,
        recoveryWindowMs: ULong,
    ): com.lomo.nativebridge.MediaOrphanSweepResultDto =
        native.mediaOrphanSweep(mediaRoot, committed, refs, existingTrash, nowMs, recoveryWindowMs)

    override fun archiveExport(
        workspaceRoot: String,
        archivePath: String,
    ): com.lomo.nativebridge.ArchiveExportResultDto = native.archiveExport(workspaceRoot, archivePath)

    override fun archiveInspect(
        archivePath: String,
        stagingRoot: String,
    ): com.lomo.nativebridge.ArchiveInspectResultDto = native.archiveInspect(archivePath, stagingRoot)

    override fun archiveImport(
        archivePath: String,
        stagingRoot: String,
    ): com.lomo.nativebridge.ArchiveInspectResultDto = native.archiveImport(archivePath, stagingRoot)

    override fun archiveActivate(
        stagingRoot: String,
        liveRoot: String,
        backupRoot: String,
    ) {
        native.archiveActivate(stagingRoot, liveRoot, backupRoot)
    }

    override fun archiveImportActivateRebuild(
        archivePath: String,
        stagingRoot: String,
        liveRoot: String,
        backupRoot: String,
        rebuildBatchSize: UInt,
    ): com.lomo.nativebridge.StoreRebuildResult =
        native.archiveImportActivateRebuild(
            archivePath,
            stagingRoot,
            liveRoot,
            backupRoot,
            rebuildBatchSize,
        )

    @Synchronized
    private fun onNativeEvent(event: NativeCoreEvent) {
        if (closed.get()) return
        // Core events are invalidations, never deltas. A gap makes this mandatory; contiguous
        // events use the same resnapshot path so Kotlin never becomes a second state authority.
        // Invoked only after BoundedInvalidationQueue drain — never on the native callback stack.
        if (lastEventSequence?.plus(1uL) != event.eventSequence) {
            lastEventSequence = null
        }
        publishBoundarySnapshot()
        lastEventSequence = event.eventSequence
    }

    /**
     * Reads, decodes and publishes the authoritative snapshot, converting any boundary failure into
     * typed recovery.
     *
     * A failed state read, a failed bootstrap drive, or an unknown category/disposition must never
     * leave the previous `Ready` published: the write gate reads that value, so a silently-failing
     * boundary would keep admitting writes against an engine whose state is unknown. The original
     * diagnostic is preserved rather than collapsed into a generic error.
     */
    private fun publishBoundarySnapshot() {
        val readiness =
            runCatching { driveIfOpening(native.state()).toDomain() }
                .getOrElse { error -> boundaryRecovery(error) }
        _readiness.value = readiness
        if (readiness is EngineReadiness.Ready) {
            lastEventSequence = readiness.eventSequence
        }
    }

    private fun boundaryRecovery(error: Throwable): EngineReadiness.ReadOnlyRecovery =
        EngineReadiness.ReadOnlyRecovery(
            category = EngineFailureCategory.INTERNAL,
            code = "engine_state_unavailable",
            retryDisposition = EngineRetryDisposition.AFTER_USER_ACTION,
            diagnostic =
                "Rust engine state could not be read at the adapter boundary: " +
                    (error.message ?: error::class.qualifiedName ?: "unknown failure"),
        )

    private fun driveIfOpening(snapshot: NativeEngineSnapshot): NativeEngineSnapshot {
        val opening = snapshot as? NativeEngineSnapshot.Opening ?: return snapshot
        when (val terminal = driveJob(opening.jobId)) {
            is NativeJobStep.Failed ->
                return NativeEngineSnapshot.ReadOnlyRecovery(terminal.failure)
            is NativeJobStep.BlockedByConflict ->
                return NativeEngineSnapshot.ReadOnlyRecovery(terminal.failure)
            else -> Unit
        }
        return native.state()
    }

    private fun publishSnapshot(snapshot: NativeEngineSnapshot) {
        val readiness = snapshot.toDomain()
        _readiness.value = readiness
        if (readiness is EngineReadiness.Ready) {
            lastEventSequence = readiness.eventSequence
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val release = ReleaseSequence()
        releaseOwnedInto(release)
        release.throwIfFailed()
    }

    /**
     * Takes the first snapshot, drives any durable bootstrap batch, then registers the callback.
     *
     * Every acquired resource is recorded in the adapter itself, so [acquire] can release whatever
     * this got through before it failed.
     */
    private fun completeAcquisition() {
        publishSnapshot(driveIfOpening(native.state()))
        lastEventSequence = (_readiness.value as? EngineReadiness.Ready)?.eventSequence
        subscriptionRef.set(native.subscribe(::onNativeEvent))
    }

    /** Fixed order: stop events (subscription), then release the native port/engine. */
    private fun releaseOwnedInto(release: ReleaseSequence) {
        subscriptionRef.getAndSet(null)?.let { subscription -> release.release(subscription::close) }
        release.release(native::close)
    }

    companion object {
        /**
         * Acquires one adapter as a single ownership transaction.
         *
         * [native] belongs to the acquisition until it completes. A failure while reading the first
         * state, driving the bootstrap batch, or subscribing releases everything already taken:
         * without this, the only reference able to close the engine dies with the constructor and
         * the workspace lock stays held until the process exits, so every retry reports `Busy`.
         */
        fun acquire(
            native: WorkspaceNativeEnginePort,
            platformBatchRunner: PlatformBatchRunner,
            projectionScanNowMillis: () -> Long = { System.nanoTime() / NANOS_PER_MILLISECOND },
            sourceDocumentFingerprintProbe: ((String) -> String?)? = null,
            safMediaPromoter: ((List<com.lomo.nativebridge.MediaPromotePlanDto>, String) -> Unit)? = null,
        ): RustEngineAdapter {
            val adapter =
                RustEngineAdapter(
                    native,
                    platformBatchRunner,
                    projectionScanNowMillis,
                    sourceDocumentFingerprintProbe,
                    safMediaPromoter,
                )
            runCatching { adapter.completeAcquisition() }
                .onFailure { failure ->
                    adapter.closed.set(true)
                    val release = ReleaseSequence()
                    release.record(failure)
                    adapter.releaseOwnedInto(release)
                    release.throwIfFailed()
                }
            return adapter
        }
    }
}

private class JobDriveMonitor(
    val monitor: Any = Any(),
    var waiters: Int = 0,
    var result: NativeJobStep? = null,
)

private fun rebuildSafProjection(
    native: WorkspaceNativeEnginePort,
    driveJob: (String) -> NativeJobStep,
    nowMillis: () -> Long,
): com.lomo.nativebridge.StoreRebuildResult {
    val rebuildId = native.beginSafProjectionRebuild()
    try {
        var cursor: String? = null
        do {
            val deadlineMillis =
                nowMillis() + WorkspaceNativeAdapter.DEFAULT_JOB_DEADLINE_MILLIS.toLong()
            val jobId =
                // behavior-contract: loop-io-ok: no bulk workspace scan API; each iteration is one bounded page
                native.startWorkspaceScan(
                    pageSize = MAX_SAF_PROJECTION_PAGE_SIZE,
                    cursor = cursor,
                    rootPath = null,
                    deadlineMillis = WorkspaceNativeAdapter.DEFAULT_JOB_DEADLINE_MILLIS,
                )
            driveProjectionScanToTerminal(
                driveJob = driveJob,
                jobId = jobId,
                deadlineMillis = deadlineMillis,
                nowMillis = nowMillis,
            )
            // behavior-contract: loop-io-ok: no bulk projection scan API; each iteration is one bounded page
            val page = native.readWorkspaceProjectionScanPage(jobId)
            // behavior-contract: loop-io-ok: no bulk rebuild-append API; each iteration is one bounded page
            native.appendSafProjectionRebuildPage(rebuildId, page.items)
            cursor = page.nextCursor
        } while (cursor != null)
        cursor = null
        do {
            val deadlineMillis =
                nowMillis() + WorkspaceNativeAdapter.DEFAULT_JOB_DEADLINE_MILLIS.toLong()
            val jobId =
                // behavior-contract: loop-io-ok: no bulk trash scan API; each iteration is one bounded page
                native.startWorkspaceTrashScan(
                    pageSize = MAX_SAF_PROJECTION_PAGE_SIZE,
                    cursor = cursor,
                    deadlineMillis = WorkspaceNativeAdapter.DEFAULT_JOB_DEADLINE_MILLIS,
                )
            driveProjectionScanToTerminal(
                driveJob = driveJob,
                jobId = jobId,
                deadlineMillis = deadlineMillis,
                nowMillis = nowMillis,
            )
            // behavior-contract: loop-io-ok: no bulk trash scan API; each iteration is one bounded page
            val page = native.readWorkspaceTrashProjectionScanPage(jobId)
            // behavior-contract: loop-io-ok: no bulk trash rebuild-append API; each iteration is one bounded page
            native.appendSafTrashProjectionRebuildPage(rebuildId, page.items)
            cursor = page.nextCursor
        } while (cursor != null)
        cursor = null
        do {
            val deadlineMillis =
                nowMillis() + WorkspaceNativeAdapter.DEFAULT_JOB_DEADLINE_MILLIS.toLong()
            val jobId =
                // behavior-contract: loop-io-ok: no bulk history scan API; each iteration is one bounded page
                native.startWorkspaceHistoryScan(
                    pageSize = MAX_SAF_PROJECTION_PAGE_SIZE,
                    cursor = cursor,
                    deadlineMillis = WorkspaceNativeAdapter.DEFAULT_JOB_DEADLINE_MILLIS,
                )
            driveProjectionScanToTerminal(
                driveJob = driveJob,
                jobId = jobId,
                deadlineMillis = deadlineMillis,
                nowMillis = nowMillis,
            )
            // behavior-contract: loop-io-ok: no bulk history scan API; each iteration is one bounded page
            val page = native.readWorkspaceHistoryProjectionScanPage(jobId)
            // behavior-contract: loop-io-ok: no bulk history rebuild-append API; each iteration is one bounded page
            native.appendSafHistoryProjectionRebuildPage(rebuildId, page.items)
            cursor = page.nextCursor
        } while (cursor != null)
        return native.finishSafProjectionRebuild(rebuildId)
    } catch (error: Exception) {
        try {
            native.abortSafProjectionRebuild(rebuildId)
        } catch (abortError: Exception) {
            error.addSuppressed(abortError)
        }
        throw error
    }
}

private fun driveProjectionScanToTerminal(
    driveJob: (String) -> NativeJobStep,
    jobId: String,
    deadlineMillis: Long,
    nowMillis: () -> Long,
) {
    var step = driveJob(jobId)
    while (step is NativeJobStep.Running ||
        step is NativeJobStep.RunningNative ||
        step is NativeJobStep.NeedsPlatformBatch
    ) {
        if (nowMillis() >= deadlineMillis) {
            throw ProjectionScanDeadlineExceededException()
        }
        // The runner returns a durable non-terminal step when its bounded driver window expires.
        // Continue the same Rust job instead of aborting or starting a duplicate scan.
        step = driveJob(jobId)
    }
    val failure =
        when (step) {
            NativeJobStep.Completed -> null
            is NativeJobStep.Failed -> step.failure
            is NativeJobStep.BlockedByConflict -> step.failure
            NativeJobStep.Running,
            is NativeJobStep.RunningNative,
            is NativeJobStep.NeedsPlatformBatch,
            -> error("Workspace projection scan did not reach a terminal state")
        }
    failure?.let { throw it.toProjectionRebuildException() }
}

private fun EngineFailureSnapshot.toProjectionRebuildException(): ProjectionRebuildException =
    ProjectionRebuildException(code, category, diagnostic)

internal class ProjectionRebuildException(
    val failureCode: String,
    val failureCategory: String,
    diagnostic: String,
) : IllegalStateException("$failureCode: $diagnostic")

internal class ProjectionScanDeadlineExceededException :
    IllegalStateException(
        "Workspace projection scan exceeded its ${WorkspaceNativeAdapter.DEFAULT_JOB_DEADLINE_MILLIS}ms deadline",
    )

// Rust enumerates at the protocol limit and repartitions independent reads into 63-action batches.
// One full page therefore needs at most five read batches inside the driver's 64-batch window.
private const val MAX_SAF_PROJECTION_PAGE_SIZE: UInt = 256u
private const val NANOS_PER_MILLISECOND = 1_000_000L

private fun NativeEngineSnapshot.toDomain(): EngineReadiness =
    when (this) {
        NativeEngineSnapshot.AwaitingWorkspaceSelection -> EngineReadiness.AwaitingWorkspaceSelection
        is NativeEngineSnapshot.Opening -> EngineReadiness.Opening
        is NativeEngineSnapshot.Ready -> EngineReadiness.Ready(coreRevision, eventSequence)
        is NativeEngineSnapshot.ReadOnlyRecovery ->
            EngineReadiness.ReadOnlyRecovery(
                category = failure.category.toFailureCategory(),
                code = failure.code,
                retryDisposition = failure.retryDisposition.toRetryDisposition(),
                diagnostic = failure.diagnostic,
            )
        NativeEngineSnapshot.ShuttingDown -> EngineReadiness.ShuttingDown
    }

internal fun String.toFailureCategory(): EngineFailureCategory =
    EngineFailureCategory.fromWireOrNull(this)
        ?: error("Unknown Rust engine failure category: $this")

private fun String.toRetryDisposition(): EngineRetryDisposition =
    EngineRetryDisposition.fromWireOrNull(this)
        ?: error("Unknown Rust engine retry disposition: $this")

/**
 * Current store revision through the public query surface; callers use it to decide whether a
 * fresh projection publication is required before an activation or rebuild completes.
 */
internal fun WorkspaceNativeAdapter.storeProjectionRevision(): ULong =
    queryMemos(
        query =
            com.lomo.nativebridge.StoreMemoQuery(
                searchText = null,
                filters =
                    com.lomo.nativebridge.StoreMemoFilters(
                        tag = null,
                        tagSubtree = false,
                        dateFromInclusiveMs = null,
                        dateUntilExclusiveMs = null,
                        hasTodo = null,
                        hasAttachment = null,
                        hasUrl = null,
                        pinnedOnly = false,
                        includeTrash = false,
                        trashOnly = false,
                    ),
                sort =
                    com.lomo.nativebridge.StoreMemoSort(
                        field = com.lomo.nativebridge.StoreMemoSortField.CREATED_AT,
                        direction = com.lomo.nativebridge.StoreSortDirection.DESCENDING,
                    ),
                boundary = null,
            ),
        cursor = null,
        pageSize = 1u,
    ).highWaterRevision
