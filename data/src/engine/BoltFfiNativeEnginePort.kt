package com.lomo.data.engine

import com.lomo.nativebridge.CoreEventListener
import com.lomo.nativebridge.EngineState
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
) : WorkspaceNativeEnginePort,
    AutoCloseable {
    private val lease = NativeHandleLease()
    private val engineRef = AtomicReference(engine)
    private val listenerRef = AtomicReference<((NativeCoreEvent) -> Unit)?>(null)
    private val invalidationQueue =
        BoundedInvalidationQueue { event ->
            if (!lease.isOpen()) return@BoundedInvalidationQueue
            listenerRef.get()?.invoke(event)
        }

    override fun state(): NativeEngineSnapshot =
        withReadLease { engine ->
            engine.state().toSnapshot()
        }

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

    override fun startWorkspaceDocumentCommand(
        path: String,
        expectedFingerprint: String,
        command: WorkspaceNativeCommandSpec,
        deadlineMillis: ULong,
    ): String =
        withReadLease { engine ->
            engine.startWorkspaceDocumentCommand(
                WorkspaceDocumentCommand(
                    path = path,
                    expectedFingerprint = expectedFingerprint,
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

    override fun listHistoryAttachmentRefs(): List<com.lomo.nativebridge.StoreHistoryAttachmentRef> =
        withReadLease { engine -> engine.listHistoryAttachmentRefs() }

    override fun applyMemoCommand(
        command: com.lomo.nativebridge.StoreMemoCommand,
    ): com.lomo.nativebridge.StoreMemoCommit =
        withReadLease { engine -> engine.applyMemoCommand(command) }

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
        listenerRef.set(listener)
        val subscription =
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
        return NativeEngineSubscription {
            withReadLease {
                check(subscription.unsubscribe()) {
                    "Native engine subscription was already unregistered"
                }
            }
            // Generated handle release is idempotent; do not hold a read lease so engine close
            // can still acquire the write lease without waiting on this stack.
            closeSubscriptionHandle(subscription)
            listenerRef.compareAndSet(listener, null)
        }
    }

    override fun close() {
        val ran =
            lease.closeOnce {
                // Fixed order: stop invalidations, drop listener, shutdown, release engine.
                invalidationQueue.stop()
                listenerRef.set(null)
                val engine =
                    engineRef.getAndSet(null)
                        ?: return@closeOnce
                try {
                    val outcome = engine.shutdown(SHUTDOWN_DEADLINE_MILLIS)
                    check(
                        outcome == ShutdownOutcome.COMPLETED ||
                            outcome == ShutdownOutcome.ALREADY_SHUTDOWN,
                    ) {
                        "Native engine shutdown failed with outcome=$outcome"
                    }
                } finally {
                    engine.close()
                    invalidationQueue.close()
                }
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

private fun EngineState.toSnapshot(): NativeEngineSnapshot =
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
