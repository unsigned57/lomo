package com.lomo.data.engine

/*
 * Behavior Contract:
 * - Unit under test: RustEngineAdapter.
 * - Owning layer: data.
 * - Priority tier: P0.
 * - Capability: expose Rust engine readiness as a platform-neutral StateFlow while treating every
 *   callback as an invalidation, explicitly closing the native subscription, and closing the
 *   native port/engine exactly once.
 *
 * Scenarios:
 * - Given a native Ready snapshot, when the adapter starts, then readiness contains the Rust-owned
 *   core revision and event sequence.
 * - Given an event claims a revision but the current native snapshot differs, when the callback is
 *   handled, then the adapter publishes the snapshot and never merges callback payload as truth.
 * - Given event sequence N is followed by N+2, when the gap is observed, then the adapter reloads
 *   the complete snapshot rather than manufacturing N+1.
 * - Given foreground resumption or adapter close, when requested, then state is resnapshotted and
 *   the native subscription and port are each closed exactly once.
 * - Given Opening with a platform batch runner, when bootstrap completes, then Ready is published
 *   after the runner drives the job.
 *
 * Observable outcomes:
 * - StateFlow readiness, native state-read count, subscription closure, and port closure.
 *
 * TDD proof:
 * - RED when adapter close only unsubscribes and never closes NativeEnginePort.
 *
 * Excludes:
 * - SAF action execution internals, workspace selection persistence, Compose rendering, and Rust.
 * - BoltFFI callback-thread enqueue (covered by BoundedInvalidationQueueTest).
 */

import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.model.EngineReadiness
import com.lomo.nativebridge.PlatformBatchResult
import io.kotest.matchers.shouldBe

class RustEngineAdapterTest : DataFunSpec() {
    init {
        test("given native ready state when adapter starts then Rust revision and sequence are exposed") {
            val native = FakeNativeEnginePort(NativeEngineSnapshot.Ready(coreRevision = 4uL, eventSequence = 9uL))

            val adapter = testRustEngineAdapter(native)

            adapter.readiness.value shouldBe EngineReadiness.Ready(coreRevision = 4uL, eventSequence = 9uL)
            native.stateReads shouldBe 1
            adapter.close()
        }

        test("given callback payload differs from native state when event arrives then complete snapshot wins") {
            val native = FakeNativeEnginePort(NativeEngineSnapshot.Ready(coreRevision = 1uL, eventSequence = 5uL))
            val adapter = testRustEngineAdapter(native)
            native.snapshot = NativeEngineSnapshot.Ready(coreRevision = 2uL, eventSequence = 6uL)

            native.emit(NativeCoreEvent(coreRevision = 999uL, eventSequence = 6uL))

            adapter.readiness.value shouldBe EngineReadiness.Ready(coreRevision = 2uL, eventSequence = 6uL)
            native.stateReads shouldBe 2
            adapter.close()
        }

        test("given sequence gap when N plus 2 arrives then adapter never manufactures the missing state") {
            val native = FakeNativeEnginePort(NativeEngineSnapshot.Ready(coreRevision = 3uL, eventSequence = 10uL))
            val adapter = testRustEngineAdapter(native)
            native.snapshot =
                NativeEngineSnapshot.ReadOnlyRecovery(
                    EngineFailureSnapshot(
                        category = "permission",
                        code = "saf_grant_revoked",
                        retryDisposition = "after_user_action",
                        diagnostic = "Workspace permission is no longer available",
                    ),
                )

            native.emit(NativeCoreEvent(coreRevision = 3uL, eventSequence = 12uL))

            adapter.readiness.value shouldBe
                EngineReadiness.ReadOnlyRecovery(
                    category = EngineReadiness.FailureCategory.PERMISSION,
                    code = "saf_grant_revoked",
                    retryDisposition = EngineReadiness.RetryDisposition.AFTER_USER_ACTION,
                    diagnostic = "Workspace permission is no longer available",
                )
            native.stateReads shouldBe 2
            adapter.close()
        }

        test("given foreground resnapshot and repeated close then state reloads and subscription and port close once") {
            val native = FakeNativeEnginePort(NativeEngineSnapshot.AwaitingWorkspaceSelection)
            val adapter = testRustEngineAdapter(native)
            native.snapshot = NativeEngineSnapshot.Ready(coreRevision = 2uL, eventSequence = 3uL)

            adapter.resnapshot()
            adapter.close()
            adapter.close()

            adapter.readiness.value shouldBe EngineReadiness.Ready(coreRevision = 2uL, eventSequence = 3uL)
            native.stateReads shouldBe 2
            native.subscriptionCloseCount shouldBe 1
            native.portCloseCount shouldBe 1
        }

        test("given opening bootstrap when platform runner completes then Ready is published") {
            val native =
                FakeNativeEnginePort(NativeEngineSnapshot.Opening(jobId = "job-bootstrap")).apply {
                    pollResults["job-bootstrap"] =
                        ArrayDeque(
                            listOf(
                                NativeJobStep.Completed,
                            ),
                        )
                    afterSubmitSnapshot =
                        NativeEngineSnapshot.Ready(coreRevision = 0uL, eventSequence = 1uL)
                    // driveIfOpening calls runner then native.state(); simulate Ready after drive.
                    onPoll = {
                        snapshot = NativeEngineSnapshot.Ready(coreRevision = 0uL, eventSequence = 1uL)
                    }
                }
            val runner =
                PlatformBatchRunner(
                    native = native,
                    executor =
                        AndroidPlatformActionExecutor(
                            access = PlatformActionAccess {
                                error("no platform actions expected for completed job")
                            },
                            currentTimeMillis = { 0L },
                        ),
                )

            val adapter = RustEngineAdapter(native, platformBatchRunner = runner)

            adapter.readiness.value shouldBe EngineReadiness.Ready(coreRevision = 0uL, eventSequence = 1uL)
            adapter.close()
        }
    }
}

private class FakeNativeEnginePort(
    initialSnapshot: NativeEngineSnapshot,
) : WorkspaceNativeEnginePort {
    var snapshot: NativeEngineSnapshot = initialSnapshot
    var stateReads: Int = 0
    var subscriptionCloseCount: Int = 0
    var portCloseCount: Int = 0
    val pollResults = mutableMapOf<String, ArrayDeque<NativeJobStep>>()
    var afterSubmitSnapshot: NativeEngineSnapshot? = null
    var onPoll: (() -> Unit)? = null
    private var listener: ((NativeCoreEvent) -> Unit)? = null

    override fun state(): NativeEngineSnapshot {
        stateReads += 1
        return snapshot
    }

    override fun subscribe(listener: (NativeCoreEvent) -> Unit): NativeEngineSubscription {
        this.listener = listener
        return NativeEngineSubscription {
            subscriptionCloseCount += 1
            this.listener = null
        }
    }

    override fun pollJob(jobId: String): NativeJobStep {
        onPoll?.invoke()
        val queue = pollResults[jobId]
        return queue?.removeFirstOrNull() ?: NativeJobStep.Running
    }

    override fun submitPlatformResult(
        jobId: String,
        result: PlatformBatchResult,
    ): NativeJobStep {
        afterSubmitSnapshot?.let { snapshot = it }
        val queue = pollResults[jobId]
        return queue?.removeFirstOrNull() ?: NativeJobStep.Completed
    }

    override fun renderMarkdown(
        content: String,
        schemaVersion: UInt,
    ): com.lomo.domain.model.markdown.MarkdownRenderDocument = error("render not expected")

    override fun startWorkspaceScan(
        pageSize: UInt,
        cursor: String?,
        rootPath: String?,
        deadlineMillis: ULong,
    ): String = error("scan not expected")

    override fun readWorkspaceScanPage(jobId: String): WorkspaceScanPageSnapshot =
        error("scan page not expected")

    override fun startWorkspaceDocumentCommand(
        path: String,
        expectedFingerprint: String,
        command: WorkspaceNativeCommandSpec,
        deadlineMillis: ULong,
    ): String = error("document command not expected")

    override fun readWorkspaceDocumentCommandResult(jobId: String): WorkspaceNativeCommandResultSnapshot =
        error("document result not expected")

    override fun close() {
        portCloseCount += 1
    }

    fun emit(event: NativeCoreEvent) {
        listener?.invoke(event)
    }
}

private fun testRustEngineAdapter(native: FakeNativeEnginePort): RustEngineAdapter =
    RustEngineAdapter(
        native = native,
        platformBatchRunner =
            PlatformBatchRunner(
                native = native,
                executor =
                    AndroidPlatformActionExecutor(
                        access = PlatformActionAccess { error("platform action not expected") },
                        currentTimeMillis = { 0L },
                    ),
            ),
    )
