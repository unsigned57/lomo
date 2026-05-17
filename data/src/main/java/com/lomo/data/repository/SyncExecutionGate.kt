package com.lomo.data.repository

import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncConflictSet
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

internal suspend fun <TResult> restorePendingConflict(
    pendingConflictStore: PendingSyncConflictStore,
    backendType: SyncBackendType,
    onRestored: (SyncConflictSet) -> Unit,
    asResult: (SyncConflictSet) -> TResult,
): TResult? {
    val pending = pendingConflictStore.read(backendType) ?: return null
    onRestored(pending)
    return asResult(pending)
}

internal suspend fun <TResult> clearPendingConflictOnSuccess(
    pendingConflictStore: PendingSyncConflictStore,
    backendType: SyncBackendType,
    result: TResult,
    isSuccess: (TResult) -> Boolean,
) {
    if (isSuccess(result)) {
        pendingConflictStore.clear(backendType)
    }
}
