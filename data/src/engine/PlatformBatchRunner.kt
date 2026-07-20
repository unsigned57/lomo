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
 * Kotlin executes side effects only; durable terminal authority remains in Rust after
 * [NativeEnginePort.submitPlatformResult].
 */
internal class PlatformBatchRunner(
    private val native: NativeEnginePort,
    private val executor: AndroidPlatformActionExecutor,
) {
    fun drive(jobId: String): NativeJobStep {
        var step = native.pollJob(jobId)
        var guard = 0
        while (step is NativeJobStep.NeedsPlatformBatch || step is NativeJobStep.Running) {
            guard += 1
            check(guard <= MAX_POLL_ROUNDS) {
                "Platform batch driver exceeded $MAX_POLL_ROUNDS rounds for job=$jobId"
            }
            when (step) {
                is NativeJobStep.NeedsPlatformBatch -> {
                    val result: PlatformBatchResult = executor.execute(step.batch)
                    step = native.submitPlatformResult(jobId, result)
                }
                is NativeJobStep.Running -> {
                    step = native.pollJob(jobId)
                }
                is NativeJobStep.BlockedByConflict,
                is NativeJobStep.Completed,
                is NativeJobStep.Failed,
                -> break
            }
        }
        return step
    }

    private companion object {
        const val MAX_POLL_ROUNDS = 64
    }
}
