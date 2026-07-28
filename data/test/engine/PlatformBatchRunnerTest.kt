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
 * - Given an actor-external native task that needs many polls, when driven, then the driver waits
 *   for it instead of spending a fixed round budget in microseconds and failing the job.
 * - Given a native task that never reports terminal, when the wait deadline passes, then the last
 *   non-terminal step is returned so Rust keeps terminal authority.
 * - Given a RunningNative step at the maximum unsigned attempt and dispatch generation, when it is
 *   converted, then both keep their unsigned values instead of wrapping negative.
 *
 * Observable outcomes:
 * - terminal NativeJobStep, executor invocation count, poll count, and slept durations.
 *
 * TDD proof:
 * - RED when PlatformBatchRunner does not exist.
 * - RED on 2026-07-27: RunningNative was polled with no delay against the same fixed 64-round
 *   guard as Running, so an in-flight worker produced a spurious driver failure, and attempt /
 *   dispatchGeneration were narrowed to Int/Long and could wrap negative.
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

        test("given a long-running native task when driven then the driver waits instead of failing") {
            val pending = ArrayDeque<NativeJobStep>()
            repeat(200) {
                pending.addLast(
                    NativeJobStep.RunningNative(
                        taskKind = "remote-sync",
                        attempt = 1u,
                        dispatchGeneration = 1uL,
                    ),
                )
            }
            pending.addLast(NativeJobStep.Completed)
            val native = RecordingNativePort(pollQueue = pending, submitResult = NativeJobStep.Completed)
            val clock = FakeDriverClock()
            val runner = waitingRunner(native, clock)

            val terminal = runner.drive("job-native")

            terminal shouldBe NativeJobStep.Completed
            // Waiting must back off rather than spin: far fewer sleeps than polls would be needed
            // if every wait were the initial interval.
            clock.slept.isNotEmpty() shouldBe true
            clock.slept.max() shouldBe PlatformBatchRunner.MAX_POLL_INTERVAL_MILLIS
        }

        test("given a native task that never finishes when the deadline passes then it stays non-terminal") {
            val native =
                RecordingNativePort(
                    pollQueue = ArrayDeque(),
                    submitResult = NativeJobStep.Completed,
                    fallbackPoll =
                        NativeJobStep.RunningNative(
                            taskKind = "remote-sync",
                            attempt = 3u,
                            dispatchGeneration = 7uL,
                        ),
                )
            val clock = FakeDriverClock()
            val runner = waitingRunner(native, clock)

            val step = runner.drive("job-stuck")

            // Rust owns terminal authority: an unobserved job must not be reported as failed here.
            val running = step.shouldBeInstanceOf<NativeJobStep.RunningNative>()
            running.attempt shouldBe 3u
            running.dispatchGeneration shouldBe 7uL
            clock.slept.sum() shouldBe PlatformBatchRunner.MAX_WAIT_MILLIS
        }

        test("given maximum unsigned running-native values when converted then widths are preserved") {
            val converted =
                com.lomo.nativebridge.JobStep
                    .RunningNative(
                        taskKind = "remote-sync",
                        attempt = UInt.MAX_VALUE,
                        dispatchGeneration = ULong.MAX_VALUE,
                    ).toNative()

            val running = converted.shouldBeInstanceOf<NativeJobStep.RunningNative>()
            running.attempt shouldBe UInt.MAX_VALUE
            running.dispatchGeneration shouldBe ULong.MAX_VALUE
        }
    }
}

private class FakeDriverClock {
    val slept = mutableListOf<Long>()
    private var now = 0L

    fun nowMillis(): Long = now

    fun sleep(millis: Long) {
        slept += millis
        now += millis
    }
}

private fun waitingRunner(
    native: NativeEnginePort,
    clock: FakeDriverClock,
): PlatformBatchRunner =
    PlatformBatchRunner(
        native = native,
        executor =
            AndroidPlatformActionExecutor(
                access = PlatformActionAccess { error("no platform action expected") },
                currentTimeMillis = { 0L },
            ),
        nowMillis = clock::nowMillis,
        sleepMillis = clock::sleep,
    )

private class RecordingNativePort(
    private val pollQueue: ArrayDeque<NativeJobStep>,
    private val submitResult: NativeJobStep,
    private val fallbackPoll: NativeJobStep = NativeJobStep.Running,
) : NativeEnginePort {
    var submitCount: Int = 0
        private set

    override fun state(): NativeEngineSnapshot = NativeEngineSnapshot.Opening("job")

    override fun subscribe(listener: (NativeCoreEvent) -> Unit): NativeEngineSubscription =
        NativeEngineSubscription {}

    override fun pollJob(jobId: String): NativeJobStep = pollQueue.removeFirstOrNull() ?: fallbackPoll

    override fun submitPlatformResult(
        jobId: String,
        result: PlatformBatchResult,
    ): NativeJobStep {
        submitCount += 1
        return submitResult
    }

    override fun close() = Unit
}
