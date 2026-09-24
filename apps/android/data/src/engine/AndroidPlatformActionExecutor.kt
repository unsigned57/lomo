package com.lomo.data.engine

import com.lomo.nativebridge.ActionOutcome
import com.lomo.nativebridge.ActionResult
import com.lomo.nativebridge.EngineFailure
import com.lomo.nativebridge.PlatformAction
import com.lomo.nativebridge.PlatformActionBatch
import com.lomo.nativebridge.PlatformBatchResult
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

internal fun interface PlatformActionAccess {
    /** Executes and independently verifies one Rust-authored Android capability action. */
    fun execute(action: PlatformAction): ActionOutcome
}

internal class AndroidPlatformActionExecutor(
    private val access: PlatformActionAccess,
    private val currentTimeMillis: () -> Long,
) {
    fun execute(batch: PlatformActionBatch): PlatformBatchResult {
        require(batch.schemaVersion == PLATFORM_SCHEMA_VERSION) {
            "Unsupported platform batch schema: ${batch.schemaVersion}"
        }
        require(batch.attempt > 0u) { "Platform batch attempt must be non-zero" }
        require(batch.actions.size in 1..MAX_PLATFORM_ACTIONS) {
            "Platform batch must contain 1..=$MAX_PLATFORM_ACTIONS actions"
        }

        val results =
            if (currentTimeMillis().toULong() > batch.deadlineEpochMillis) {
                listOf(
                    ActionResult(
                        actionId = batch.actions.first().actionId(),
                        outcome = ActionOutcome.Failed(batchDeadlineFailure(batch.jobId)),
                    ),
                )
            } else {
                executeVerifiedPrefix(batch.actions)
            }
        return PlatformBatchResult(
            schemaVersion = batch.schemaVersion,
            jobId = batch.jobId,
            batchId = batch.batchId,
            attempt = batch.attempt,
            actionResults = results,
        )
    }

    private fun executeVerifiedPrefix(actions: List<PlatformAction>): List<ActionResult> =
        if (actions.size > 1 && actions.all { action -> action is PlatformAction.ReadToExchange }) {
            executeIndependentReads(actions)
        } else {
            executeSequentialPrefix(actions)
        }

    private fun executeSequentialPrefix(actions: List<PlatformAction>): List<ActionResult> {
        val results = ArrayList<ActionResult>(actions.size)
        for (action in actions) {
            val outcome = access.execute(action)
            results += ActionResult(actionId = action.actionId(), outcome = outcome)
            if (outcome is ActionOutcome.Failed) break
        }
        return results
    }

    private fun executeIndependentReads(actions: List<PlatformAction>): List<ActionResult> {
        val executor = Executors.newFixedThreadPool(minOf(actions.size, MAX_PARALLEL_READS))
        return try {
            val futures =
                executor.invokeAll(
                    actions.map { action ->
                        Callable {
                            ActionResult(
                                actionId = action.actionId(),
                                outcome = access.execute(action),
                            )
                        }
                    },
                )
            val ordered =
                futures.map { future ->
                    try {
                        future.get()
                    } catch (failure: ExecutionException) {
                        throw failure.cause ?: failure
                    }
                }
            ordered.takeThroughFirstFailure()
        } finally {
            executor.shutdownNow()
        }
    }
}

private fun List<ActionResult>.takeThroughFirstFailure(): List<ActionResult> {
    val failureIndex = indexOfFirst { result -> result.outcome is ActionOutcome.Failed }
    return if (failureIndex < 0) this else subList(0, failureIndex + 1)
}

private fun batchDeadlineFailure(jobId: String): EngineFailure =
    EngineFailure(
        category = "timeout",
        code = "platform_batch_deadline_exceeded",
        retryDisposition = "after_user_action",
        operationId = null,
        jobId = jobId,
        diagnostic = "Platform batch deadline expired before Android execution",
    )

private fun PlatformAction.actionId(): String =
    when (this) {
        is PlatformAction.Stat -> actionId
        is PlatformAction.ListChildren -> actionId
        is PlatformAction.EnsureDirectory -> actionId
        is PlatformAction.ReadToExchange -> actionId
        is PlatformAction.WriteFromExchange -> actionId
        is PlatformAction.ArtifactWrite -> actionId
        is PlatformAction.Move -> actionId
        is PlatformAction.Delete -> actionId
    }

private const val PLATFORM_SCHEMA_VERSION = 1u
private const val MAX_PLATFORM_ACTIONS = 64
private const val MAX_PARALLEL_READS = 8
