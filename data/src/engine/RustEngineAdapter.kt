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
import com.lomo.domain.model.EngineReadiness
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
            synchronized(holder.monitor) { platformBatchRunner.drive(jobId) }
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

    fun rebuildSafProjectionFromWorkspaceScan(): com.lomo.nativebridge.StoreRebuildResult {
        return projectionRebuildCoordinator.run {
            rebuildSafProjection(
                native = native,
                driveJob = ::driveJob,
                nowMillis = projectionScanNowMillis,
            )
        }
    }

    fun storeProjectionRevision(): ULong =
        native.queryMemos(
            query =
                com.lomo.nativebridge.StoreMemoQuery(
                    searchText = null,
                    filters =
                        com.lomo.nativebridge.StoreMemoFilters(
                            tag = null,
                            tagSubtree = false,
                            dateFromMs = null,
                            dateToMs = null,
                            hasTodo = null,
                            hasAttachment = null,
                            hasUrl = null,
                            pinnedOnly = false,
                            includeTrash = false,
                            trashOnly = false,
                        ),
                ),
            cursor = null,
            pageSize = 1u,
        ).highWaterRevision

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
    ): com.lomo.nativebridge.StoreMemoPage = native.queryMemos(query, cursor, pageSize)

    override fun getMemo(memoId: String): com.lomo.nativebridge.StoreMemoSnapshot? = native.getMemo(memoId)

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

    override fun applyMemoCommand(
        command: com.lomo.nativebridge.StoreMemoCommand,
    ): com.lomo.nativebridge.StoreMemoCommit = native.applyMemoCommand(command)

    override fun commitSafProjectionMutation(
        command: com.lomo.nativebridge.StoreMemoCommand,
        projection: com.lomo.nativebridge.StoreSafMemoProjection?,
    ): com.lomo.nativebridge.StoreMemoCommit =
        native.commitSafProjectionMutation(command, projection)

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
            category = EngineReadiness.FailureCategory.INTERNAL,
            code = "engine_state_unavailable",
            retryDisposition = EngineReadiness.RetryDisposition.AFTER_USER_ACTION,
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
        ): RustEngineAdapter {
            val adapter = RustEngineAdapter(native, platformBatchRunner, projectionScanNowMillis)
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
            val page = native.readWorkspaceProjectionScanPage(jobId)
            native.appendSafProjectionRebuildPage(rebuildId, page.items)
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

// A projection scan job is driven by the same bounded platform-batch budget as memo scans:
// one list batch plus at most 63 file reads must stay within the driver's 64-batch limit.
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

internal fun String.toFailureCategory(): EngineReadiness.FailureCategory =
    when (this) {
        "validation" -> EngineReadiness.FailureCategory.VALIDATION
        "permission" -> EngineReadiness.FailureCategory.PERMISSION
        "corruption" -> EngineReadiness.FailureCategory.CORRUPTION
        "storage" -> EngineReadiness.FailureCategory.STORAGE
        "network" -> EngineReadiness.FailureCategory.NETWORK
        "authentication" -> EngineReadiness.FailureCategory.AUTHENTICATION
        "conflict" -> EngineReadiness.FailureCategory.CONFLICT
        "cancelled" -> EngineReadiness.FailureCategory.CANCELLED
        "timeout" -> EngineReadiness.FailureCategory.TIMEOUT
        "busy" -> EngineReadiness.FailureCategory.BUSY
        "resource_limit" -> EngineReadiness.FailureCategory.RESOURCE_LIMIT
        "internal" -> EngineReadiness.FailureCategory.INTERNAL
        else -> error("Unknown Rust engine failure category: $this")
    }

private fun String.toRetryDisposition(): EngineReadiness.RetryDisposition =
    when (this) {
        "never" -> EngineReadiness.RetryDisposition.NEVER
        "after_user_action" -> EngineReadiness.RetryDisposition.AFTER_USER_ACTION
        "transient" -> EngineReadiness.RetryDisposition.TRANSIENT
        else -> error("Unknown Rust engine retry disposition: $this")
    }
