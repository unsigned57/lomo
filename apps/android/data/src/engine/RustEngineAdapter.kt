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
import com.lomo.data.engine.lan.LanServiceState
import com.lomo.data.engine.lan.LanSendItemPlan
import com.lomo.data.engine.lan.LanSessionChallenge
import com.lomo.data.engine.lan.LanSessionState
import com.lomo.data.engine.lan.LanTransferShape
import com.lomo.data.engine.lan.LanProtocolLimits
import com.lomo.data.repository.StoreInvalidationBus
import com.lomo.data.repository.StoreProjectionObserver
import com.lomo.domain.model.EngineFailureCategory
import com.lomo.domain.model.EngineReadiness
import com.lomo.domain.model.EngineRetryDisposition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

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
    invalidation: StoreInvalidationBus,
) : WorkspaceNativeAdapter,
    AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val _readiness = MutableStateFlow<EngineReadiness>(EngineReadiness.Opening)
    private val jobDriveMonitorLock = Any()
    private val jobDriveMonitors = mutableMapOf<String, JobDriveMonitor>()
    private val projectionObserver = StoreProjectionObserver(invalidation)

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

    override fun lanTransferShape(): LanTransferShape =
        withEngineFailureConversion { native.lanTransferShape() }

    override fun lanProtocolLimits(): LanProtocolLimits =
        withEngineFailureConversion { native.lanProtocolLimits() }

    override fun updateLanNetworkSnapshot(snapshot: LanNetworkFacts) {
        withEngineFailureConversion { native.updateLanNetworkSnapshot(snapshot) }
    }

    override fun updateLanDiscoverySnapshot(snapshot: LanDiscoveryFacts) {
        withEngineFailureConversion { native.updateLanDiscoverySnapshot(snapshot) }
    }

    override fun startLanService(): LanServiceState =
        withEngineFailureConversion { native.startLanService() }

    override fun stopLanService(): LanServiceState =
        withEngineFailureConversion { native.stopLanService() }

    override fun listLanDiscoveredPeers(): List<LanDiscoveredPeer> =
        withEngineFailureConversion { native.listLanDiscoveredPeers() }

    override fun configureLanIdentity(identity: LanDeviceIdentity): LanLocalIdentity =
        withEngineFailureConversion { native.configureLanIdentity(identity) }

    override fun beginLanPairing(
        peerDeviceId: String,
        nowMs: Long,
        ttlMs: Long,
    ): LanPairingChallenge =
        withEngineFailureConversion { native.beginLanPairing(peerDeviceId, nowMs, ttlMs) }

    override fun awaitLanInbox(lastGeneration: ULong, timeoutMs: ULong): LanInboxWait =
        withEngineFailureConversion { native.awaitLanInbox(lastGeneration, timeoutMs) }

    override fun lanPairingChallenge(pairingId: String): LanPairingChallenge =
        withEngineFailureConversion { native.lanPairingChallenge(pairingId) }

    override fun confirmLanPairing(
        pairingId: String,
        signature: ByteArray,
        nowMs: Long,
    ) {
        withEngineFailureConversion { native.confirmLanPairing(pairingId, signature, nowMs) }
    }

    override fun declineLanPairing(pairingId: String) {
        withEngineFailureConversion { native.declineLanPairing(pairingId) }
    }

    override fun beginLanSession(
        peerDeviceId: String,
        nowMs: Long,
        ttlMs: Long,
    ): LanSessionChallenge =
        withEngineFailureConversion { native.beginLanSession(peerDeviceId, nowMs, ttlMs) }

    override fun confirmLanSession(
        sessionId: String,
        signature: ByteArray,
        nowMs: Long,
    ) {
        withEngineFailureConversion { native.confirmLanSession(sessionId, signature, nowMs) }
    }

    override fun lanSessionState(sessionId: String): LanSessionState =
        withEngineFailureConversion { native.lanSessionState(sessionId) }

    override fun prepareLanBatch(
        sessionId: String,
        batchId: String,
        items: List<LanSendItemPlan>,
    ) {
        withEngineFailureConversion { native.prepareLanBatch(sessionId, batchId, items) }
    }

    override fun approveLanBatch(
        sessionId: String,
        batchId: String,
        nowMs: Long,
        ttlMs: Long,
    ) {
        withEngineFailureConversion { native.approveLanBatch(sessionId, batchId, nowMs, ttlMs) }
    }

    override fun rejectLanBatch(
        sessionId: String,
        batchId: String,
        rejectedAtMs: Long,
    ) {
        withEngineFailureConversion { native.rejectLanBatch(sessionId, batchId, rejectedAtMs) }
    }

    override fun sendLanBatchChunks(chunks: List<LanChunkSend>) {
        withEngineFailureConversion { native.sendLanBatchChunks(chunks) }
    }

    override fun lanUnconfirmedBatchChunks(
        batchId: String,
        itemIndex: UInt,
        attachmentSlot: UInt,
    ): List<UInt> =
        withEngineFailureConversion {
            native.lanUnconfirmedBatchChunks(batchId, itemIndex, attachmentSlot)
        }

    override fun commitReceivedLanItem(
        batchId: String,
        itemIndex: UInt,
        nowMs: Long,
    ): com.lomo.nativebridge.StoreMemoCommit {
        val commit =
            withEngineFailureConversion {
                native.commitReceivedLanItem(batchId, itemIndex, nowMs)
            }
        projectionObserver.observeNativeCommit(commit)
        return commit
    }

    override fun failReceivedLanItem(
        batchId: String,
        itemIndex: UInt,
        code: String,
    ) = withEngineFailureConversion { native.failReceivedLanItem(batchId, itemIndex, code) }

    override fun listLanPeers(): LanPeerPage =
        withEngineFailureConversion { native.listLanPeers() }

    override fun revokeLanPeer(
        deviceId: String,
        revokedAtMs: Long,
    ): LanPeerPage =
        withEngineFailureConversion { native.revokeLanPeer(deviceId, revokedAtMs) }

    override fun renderMarkdown(
        content: String,
        schemaVersion: UInt,
    ) = native.renderMarkdown(content, schemaVersion)

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

    override fun queryMemos(
        query: com.lomo.nativebridge.StoreMemoQuery,
        cursor: com.lomo.nativebridge.StorePageCursor?,
        pageSize: UInt,
        startMemoId: String?,
        backward: Boolean,
    ): com.lomo.nativebridge.StoreMemoPage =
        native.queryMemos(query, cursor, pageSize, startMemoId, backward)

    override fun getMemo(memoId: String): com.lomo.nativebridge.StoreMemoSnapshot? = native.getMemo(memoId)

    override fun queryCount(query: com.lomo.nativebridge.StoreMemoQuery): ULong = native.queryCount(query)

    override fun sidebarProjection(): com.lomo.nativebridge.StoreSidebarProjection =
        native.sidebarProjection()

    override fun openWorkspaceSession(
        host: com.lomo.nativebridge.PlatformBatchHost,
        timeZone: String,
        mediaStageRoot: String,
    ): String = native.openWorkspaceSession(host, timeZone, mediaStageRoot)

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

    override fun sessionSearch(
        request: com.lomo.nativebridge.SessionSearchRequest,
    ): com.lomo.nativebridge.SessionSearchOutcome = native.sessionSearch(request)

    override fun sessionListTasks(): List<com.lomo.nativebridge.SessionTaskItem> =
        native.sessionListTasks()

    override fun sessionToggleTask(
        request: com.lomo.nativebridge.SessionToggleTaskRequest,
    ): com.lomo.nativebridge.StoreMemoCommit {
        val commit = native.sessionToggleTask(request)
        projectionObserver.observeNativeCommit(commit)
        return commit
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
    ): com.lomo.nativebridge.StoreMemoCommit = native.sessionRestoreMemo(request)

    override fun sessionRestoreRevision(
        request: com.lomo.nativebridge.SessionRestoreRevisionRequest,
    ): com.lomo.nativebridge.StoreMemoCommit = native.sessionRestoreRevision(request)

    override fun sessionPermanentlyDeleteMemo(
        request: com.lomo.nativebridge.SessionRestoreRequest,
    ): com.lomo.nativebridge.StoreMemoCommit = native.sessionPermanentlyDeleteMemo(request)

    override fun sessionPermanentlyDeleteMany(
        request: com.lomo.nativebridge.StoreMemoBatchDelete,
    ): com.lomo.nativebridge.StoreMemoBatchCommit = native.sessionPermanentlyDeleteMany(request)

    override fun sessionReminderPlan(nowUtcMs: Long?): com.lomo.nativebridge.StoreReminderPlan =
        native.sessionReminderPlan(nowUtcMs)

    override fun sessionSnoozeReminder(
        opaqueId: String,
        snoozeDurationMs: Long,
    ) = native.sessionSnoozeReminder(opaqueId, snoozeDurationMs)

    override fun sessionRecoverReminderSnooze() = native.sessionRecoverReminderSnooze()

    override fun syncRunCycle(
        workspaceRoot: String,
        config: com.lomo.nativebridge.SyncBackendConfigDto,
        secretLeaseId: String,
        applyRemote: Boolean,
    ): com.lomo.nativebridge.SyncCyclePlanSummaryDto =
        native.syncRunCycle(
            workspaceRoot,
            config,
            secretLeaseId,
            applyRemote,
        )

    override fun commitWorkspaceDocumentFacts(
        command: com.lomo.nativebridge.StoreMemoCommand,
        projection: com.lomo.nativebridge.StoreSafMemoProjection,
    ): com.lomo.nativebridge.StoreMemoCommit =
        native.commitWorkspaceDocumentFacts(command, projection)

    override fun startRebuild(batchSize: UInt): com.lomo.nativebridge.StoreRebuildResult =
        native.startRebuild(batchSize)

    override fun stageMedia(
        mediaRoot: String,
        sourceKind: com.lomo.nativebridge.MediaSourceKind,
        sourcePath: String,
        humanNameHint: String,
    ): com.lomo.nativebridge.MediaStagedDto =
        native.stageMedia(mediaRoot, sourceKind, sourcePath, humanNameHint)

    override fun recordStageLease(
        workspaceRoot: String?,
        staged: com.lomo.nativebridge.MediaStagedDto,
        ownerKind: com.lomo.nativebridge.MediaStageOwnerKindDto,
        ownerId: String,
    ): com.lomo.nativebridge.MediaStageRecordDto =
        native.recordStageLease(workspaceRoot, staged, ownerKind, ownerId)

    override fun stageRecordsForOwner(
        mediaRoot: String,
        ownerKind: com.lomo.nativebridge.MediaStageOwnerKindDto,
        ownerId: String,
    ): List<com.lomo.nativebridge.MediaStageRecordDto> =
        native.stageRecordsForOwner(mediaRoot, ownerKind, ownerId)

    override fun transferStageLease(
        mediaRoot: String,
        from: com.lomo.nativebridge.MediaStageLeaseDto,
        to: com.lomo.nativebridge.MediaStageLeaseDto,
    ): com.lomo.nativebridge.MediaStageReleaseDto = native.transferStageLease(mediaRoot, from, to)

    override fun releaseStageLease(
        mediaRoot: String,
        lease: com.lomo.nativebridge.MediaStageLeaseDto,
    ): com.lomo.nativebridge.MediaStageReleaseDto = native.releaseStageLease(mediaRoot, lease)

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

    override fun queryMediaManifest(
        workspaceRoot: String,
        verifiedEntries: List<com.lomo.nativebridge.MediaCommittedEntryDto>,
    ): com.lomo.nativebridge.MediaManifestDto = native.queryMediaManifest(workspaceRoot, verifiedEntries)

    override fun sessionMediaOrphanSweep(
        nowMs: ULong?,
        recoveryWindowMs: ULong,
        externalDrafts: List<com.lomo.nativebridge.SessionDraftGuardDto>,
    ): com.lomo.nativebridge.SessionMediaSweepReportDto =
        native.sessionMediaOrphanSweep(nowMs, recoveryWindowMs, externalDrafts)

    override fun archiveExport(
        workspaceRoot: String,
        archivePath: String,
    ): com.lomo.nativebridge.ArchiveExportResultDto = native.archiveExport(workspaceRoot, archivePath)

    override fun sessionImportArchive(
        workspaceRoot: String,
        archivePath: String,
        stagingRoot: String,
    ): com.lomo.nativebridge.StoreRebuildResult =
        native.sessionImportArchive(
            workspaceRoot,
            archivePath,
            stagingRoot,
        )

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
        val snapshot =
            runCatching { driveIfOpening(native.state()) }
                .getOrElse { error ->
                    _readiness.value = boundaryRecovery(error)
                    return
                }
        val readiness =
            runCatching { snapshot.toDomain() }
                .getOrElse { error -> boundaryRecovery(error) }
        _readiness.value = readiness
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
        _readiness.value = snapshot.toDomain()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val release = ReleaseSequence()
        releaseOwnedInto(release)
        release.throwIfFailed()
    }

    /**
     * Takes the first snapshot and drives any durable bootstrap batch.
     *
     * Journal events are not an invalidation protocol; store commit receipts publish the bus.
     * Every acquired resource is recorded in the adapter itself, so [acquire] can release whatever
     * this got through before it failed.
     */
    private fun completeAcquisition() {
        publishSnapshot(driveIfOpening(native.state()))
    }

    /** Fixed order: release the native port/engine. */
    private fun releaseOwnedInto(release: ReleaseSequence) {
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
            invalidation: StoreInvalidationBus,
        ): RustEngineAdapter {
            val adapter =
                RustEngineAdapter(
                    native,
                    platformBatchRunner,
                    invalidation,
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

private fun NativeEngineSnapshot.toDomain(): EngineReadiness =
    when (this) {
        NativeEngineSnapshot.AwaitingWorkspaceSelection -> EngineReadiness.AwaitingWorkspaceSelection
        is NativeEngineSnapshot.Opening -> EngineReadiness.Opening
        is NativeEngineSnapshot.Ready -> EngineReadiness.Ready
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
        startMemoId = null,
        backward = false,
    ).highWaterRevision
