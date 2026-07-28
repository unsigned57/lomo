package com.lomo.data.engine

import com.lomo.nativebridge.JobStep
import com.lomo.nativebridge.PlatformBatchResult

/**
 * Internal job step surface used by the platform-batch driver.
 *
 * Domain readiness only sees [NativeEngineSnapshot]; job/batch identities stay in data.
 */
internal sealed interface NativeJobStep {
    data object Running : NativeJobStep

    data class NeedsPlatformBatch(
        val batch: com.lomo.nativebridge.PlatformActionBatch,
    ) : NativeJobStep

    /**
     * Actor-external native task is in-flight (P5-02).
     *
     * Attempt and dispatch generation stay unsigned: narrowing them to Int/Long wraps large
     * generations negative and turns a legitimate retry into a nonsensical identity.
     */
    data class RunningNative(
        val taskKind: String,
        val attempt: UInt,
        val dispatchGeneration: ULong,
    ) : NativeJobStep

    data class BlockedByConflict(
        val failure: EngineFailureSnapshot,
    ) : NativeJobStep

    data object Completed : NativeJobStep

    data class Failed(
        val failure: EngineFailureSnapshot,
    ) : NativeJobStep
}

internal fun JobStep.toNative(): NativeJobStep =
    when (this) {
        JobStep.Running -> NativeJobStep.Running
        is JobStep.NeedsPlatformBatch -> NativeJobStep.NeedsPlatformBatch(batch)
        is JobStep.RunningNative ->
            NativeJobStep.RunningNative(
                taskKind = taskKind,
                attempt = attempt,
                dispatchGeneration = dispatchGeneration,
            )
        is JobStep.BlockedByConflict ->
            NativeJobStep.BlockedByConflict(
                EngineFailureSnapshot(
                    category = failure.category,
                    code = failure.code,
                    retryDisposition = failure.retryDisposition,
                    diagnostic = failure.diagnostic,
                ),
            )
        JobStep.Completed -> NativeJobStep.Completed
        is JobStep.Failed ->
            NativeJobStep.Failed(
                EngineFailureSnapshot(
                    category = failure.category,
                    code = failure.code,
                    retryDisposition = failure.retryDisposition,
                    diagnostic = failure.diagnostic,
                ),
            )
    }

/**
 * Drives a Rust job that is waiting on Android platform batches until a terminal step.
 *
 * Two waits are deliberately distinct. Platform batches are work this driver performs, so they are
 * bounded by a batch count. `Running` / `RunningNative` are work in flight elsewhere — an actor or
 * a native worker — so they are bounded by a wall-clock deadline with backoff. Counting those as
 * driver rounds spent the whole budget in microseconds and reported a healthy in-flight job as a
 * driver failure.
 *
 * Kotlin executes side effects only; durable terminal authority remains in Rust after
 * [NativeEnginePort.submitPlatformResult]. When the deadline passes, the last non-terminal step is
 * returned unchanged so no caller can mistake an unobserved job for a finished one.
 */
internal class PlatformBatchRunner(
    private val native: NativeEnginePort,
    private val executor: AndroidPlatformActionExecutor,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val sleepMillis: (Long) -> Unit = { millis -> Thread.sleep(millis) },
) {
    fun drive(jobId: String): NativeJobStep {
        var step = native.pollJob(jobId)
        var batches = 0
        var waitMillis = INITIAL_POLL_INTERVAL_MILLIS
        val deadline = nowMillis() + MAX_WAIT_MILLIS
        while (true) {
            when (step) {
                is NativeJobStep.NeedsPlatformBatch -> {
                    batches += 1
                    check(batches <= MAX_PLATFORM_BATCHES) {
                        "Platform batch driver exceeded $MAX_PLATFORM_BATCHES batches for job=$jobId"
                    }
                    val result: PlatformBatchResult = executor.execute(step.batch)
                    step = native.submitPlatformResult(jobId, result)
                    waitMillis = INITIAL_POLL_INTERVAL_MILLIS
                }
                is NativeJobStep.Running,
                is NativeJobStep.RunningNative,
                -> {
                    val remaining = deadline - nowMillis()
                    if (remaining <= 0) return step
                    sleepMillis(minOf(waitMillis, remaining))
                    waitMillis = (waitMillis * 2).coerceAtMost(MAX_POLL_INTERVAL_MILLIS)
                    step = native.pollJob(jobId)
                }
                is NativeJobStep.BlockedByConflict,
                is NativeJobStep.Completed,
                is NativeJobStep.Failed,
                -> return step
            }
        }
    }

    internal companion object {
        const val MAX_PLATFORM_BATCHES = 64
        const val INITIAL_POLL_INTERVAL_MILLIS = 1L
        const val MAX_POLL_INTERVAL_MILLIS = 50L
        const val MAX_WAIT_MILLIS = 30_000L
    }
}
