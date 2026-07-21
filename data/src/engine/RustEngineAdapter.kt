package com.lomo.data.engine

import com.lomo.domain.model.EngineReadiness
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Adapts one native engine handle into domain readiness.
 *
 * Not the process owner — [ManagedEngineSession] opens/closes adapters and is the sole
 * [com.lomo.domain.repository.EngineReadinessRepository].
 */
internal class RustEngineAdapter(
    private val native: WorkspaceNativeEnginePort,
    private val platformBatchRunner: PlatformBatchRunner,
) : WorkspaceNativeAdapter,
    AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val _readiness = MutableStateFlow<EngineReadiness>(EngineReadiness.Opening)
    private var lastEventSequence: ULong? = null
    private val subscription: NativeEngineSubscription

    init {
        // Drive any durable bootstrap platform batch before publishing the first readiness value.
        publishSnapshot(driveIfOpening(native.state()))
        lastEventSequence = (_readiness.value as? EngineReadiness.Ready)?.eventSequence
        subscription = native.subscribe(::onNativeEvent)
    }

    val readiness: StateFlow<EngineReadiness> = _readiness.asStateFlow()

    @Synchronized
    fun resnapshot() {
        check(!closed.get()) { "Rust engine adapter is closed" }
        publishSnapshot(driveIfOpening(native.state()))
    }

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

    override fun driveJob(jobId: String): NativeJobStep = platformBatchRunner.drive(jobId)

    override fun readWorkspaceScanPage(jobId: String): WorkspaceScanPageSnapshot =
        native.readWorkspaceScanPage(jobId)

    override fun startWorkspaceDocumentCommand(
        path: String,
        expectedFingerprint: String,
        command: WorkspaceNativeCommandSpec,
        deadlineMillis: ULong,
    ): String =
        native.startWorkspaceDocumentCommand(
            path = path,
            expectedFingerprint = expectedFingerprint,
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

    override fun applyMemoCommand(
        command: com.lomo.nativebridge.StoreMemoCommand,
    ): com.lomo.nativebridge.StoreMemoCommit = native.applyMemoCommand(command)

    override fun startRebuild(batchSize: UInt): com.lomo.nativebridge.StoreRebuildResult =
        native.startRebuild(batchSize)

    @Synchronized
    private fun onNativeEvent(event: NativeCoreEvent) {
        if (closed.get()) return
        // Core events are invalidations, never deltas. A gap makes this mandatory; contiguous
        // events use the same resnapshot path so Kotlin never becomes a second state authority.
        // Invoked only after BoundedInvalidationQueue drain — never on the native callback stack.
        if (lastEventSequence?.plus(1uL) != event.eventSequence) {
            lastEventSequence = null
        }
        publishSnapshot(driveIfOpening(native.state()))
        lastEventSequence = event.eventSequence
    }

    private fun driveIfOpening(snapshot: NativeEngineSnapshot): NativeEngineSnapshot {
        val opening = snapshot as? NativeEngineSnapshot.Opening ?: return snapshot
        when (val terminal = platformBatchRunner.drive(opening.jobId)) {
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
        // Fixed order: stop events (subscription), then release the native port/engine.
        subscription.close()
        native.close()
    }
}

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

private fun String.toFailureCategory(): EngineReadiness.FailureCategory =
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
