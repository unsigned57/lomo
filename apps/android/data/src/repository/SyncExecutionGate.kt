package com.lomo.data.repository

import java.util.concurrent.atomic.AtomicBoolean

internal class SyncExecutionGate<TResult>(
    private val defaultInProgressResult: (() -> TResult)? = null,
) {
    private val inProgress = AtomicBoolean(false)

    suspend fun run(block: suspend () -> TResult): TResult =
        run(
            inProgressResult =
                requireNotNull(defaultInProgressResult) {
                    "SyncExecutionGate requires a default in-progress result or an override per call."
                },
            block = block,
        )

    suspend fun run(
        inProgressResult: () -> TResult,
        block: suspend () -> TResult,
    ): TResult {
        if (!inProgress.compareAndSet(false, true)) {
            return inProgressResult()
        }
        return try {
            block()
        } finally {
            inProgress.set(false)
        }
    }
}
