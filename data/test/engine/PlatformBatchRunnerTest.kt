package com.lomo.data.engine

/*
 * Behavior Contract:
 * - Unit under test: PlatformBatchRunner.
 * - Owning layer: data Android capability edge.
 * - Priority tier: P0.
 * - Capability: poll a Rust job, execute NeedsPlatformBatch through the Android executor, and submit
 *   the ordered result until a terminal job step is observed.
 *
 * Scenarios:
 * - Given NeedsPlatformBatch then Completed, when driven, then the executor runs once and Completed
 *   is returned.
 * - Given a Failed terminal after submit, when driven, then Failed is returned without further polls.
 *
 * Observable outcomes:
 * - terminal NativeJobStep and executor invocation count.
 *
 * TDD proof:
 * - RED when PlatformBatchRunner does not exist.
 *
 * Excludes:
 * - Real SAF I/O and generated BoltFFI handles.
 */

import com.lomo.data.testing.DataFunSpec
import com.lomo.nativebridge.ActionOutcome
import com.lomo.nativebridge.DocumentKind
import com.lomo.nativebridge.DocumentMetadata
import com.lomo.nativebridge.ActionEvidence
import com.lomo.nativebridge.PlatformAction
import com.lomo.nativebridge.PlatformActionBatch
import com.lomo.nativebridge.PlatformActionOutput
import com.lomo.nativebridge.PlatformBatchResult
import com.lomo.nativebridge.WorkspaceTarget
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class PlatformBatchRunnerTest : DataFunSpec() {
    init {
        test("given needs platform batch when driven then executor runs and completed is returned") {
            val batch =
                PlatformActionBatch(
                    schemaVersion = 1u,
                    jobId = "job-1",
                    batchId = "batch-1",
                    attempt = 1u,
                    deadlineEpochMillis = 9_000uL,
                    actions =
                        listOf(
                            PlatformAction.Stat("action-1", "cap", WorkspaceTarget.Root),
                        ),
                )
            val native =
                RecordingNativePort(
                    pollQueue =
                        ArrayDeque(
                            listOf(NativeJobStep.NeedsPlatformBatch(batch)),
                        ),
                    submitResult = NativeJobStep.Completed,
                )
            var executed = 0
            val runner =
                PlatformBatchRunner(
                    native = native,
                    executor =
                        AndroidPlatformActionExecutor(
                            access =
                                PlatformActionAccess {
                                    executed += 1
                                    ActionOutcome.Applied(
                                        PlatformActionOutput.Stat(
                                            DocumentMetadata(
                                                target = WorkspaceTarget.Root,
                                                kind = DocumentKind.DIRECTORY,
                                                mimeType = null,
                                                evidence =
                                                    ActionEvidence(
                                                        length = 0uL,
                                                        digest = "c".repeat(64),
                                                        fingerprint = "root-fingerprint",
                                                    ),
                                            ),
                                        ),
                                    )
                                },
                            currentTimeMillis = { 1_000L },
                        ),
                )

            val terminal = runner.drive("job-1")

            terminal shouldBe NativeJobStep.Completed
            executed shouldBe 1
            native.submitCount shouldBe 1
        }

        test("given failed terminal after submit when driven then failed is returned") {
            val batch =
                PlatformActionBatch(
                    schemaVersion = 1u,
                    jobId = "job-2",
                    batchId = "batch-2",
                    attempt = 1u,
                    deadlineEpochMillis = 9_000uL,
                    actions =
                        listOf(
                            PlatformAction.Stat("action-1", "cap", WorkspaceTarget.Root),
                        ),
                )
            val failure =
                EngineFailureSnapshot(
                    category = "permission",
                    code = "saf_grant_revoked",
                    retryDisposition = "after_user_action",
                    diagnostic = "revoked",
                )
            val native =
                RecordingNativePort(
                    pollQueue =
                        ArrayDeque(
                            listOf(NativeJobStep.NeedsPlatformBatch(batch)),
                        ),
                    submitResult = NativeJobStep.Failed(failure),
                )
            val runner =
                PlatformBatchRunner(
                    native = native,
                    executor =
                        AndroidPlatformActionExecutor(
                            access =
                                PlatformActionAccess {
                                    ActionOutcome.Applied(
                                        PlatformActionOutput.Stat(
                                            DocumentMetadata(
                                                target = WorkspaceTarget.Root,
                                                kind = DocumentKind.DIRECTORY,
                                                mimeType = null,
                                                evidence =
                                                    ActionEvidence(
                                                        length = 0uL,
                                                        digest = "c".repeat(64),
                                                        fingerprint = "root-fingerprint",
                                                    ),
                                            ),
                                        ),
                                    )
                                },
                            currentTimeMillis = { 1_000L },
                        ),
                )

            val terminal = runner.drive("job-2")

            val failed = terminal.shouldBeInstanceOf<NativeJobStep.Failed>()
            failed.failure.code shouldBe "saf_grant_revoked"
        }
    }
}

private class RecordingNativePort(
    private val pollQueue: ArrayDeque<NativeJobStep>,
    private val submitResult: NativeJobStep,
) : NativeEnginePort {
    var submitCount: Int = 0
        private set

    override fun state(): NativeEngineSnapshot = NativeEngineSnapshot.Opening("job")

    override fun subscribe(listener: (NativeCoreEvent) -> Unit): NativeEngineSubscription =
        NativeEngineSubscription {}

    override fun pollJob(jobId: String): NativeJobStep =
        pollQueue.removeFirstOrNull() ?: NativeJobStep.Running

    override fun submitPlatformResult(
        jobId: String,
        result: PlatformBatchResult,
    ): NativeJobStep {
        submitCount += 1
        return submitResult
    }

    override fun close() = Unit
}
