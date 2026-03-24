package com.lomo.app.feature.common

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal suspend fun runDeleteAnimationWithRollback(
    itemId: String,
    deletingIds: MutableStateFlow<Set<String>>,
    animationDelayMs: Long = 300L,
    mutation: suspend () -> Unit,
): Result<Unit> {
    return runDeleteAnimationWithRollback(
        itemIds = setOf(itemId),
        deletingIds = deletingIds,
        animationDelayMs = animationDelayMs,
        mutation = mutation,
    )
}

internal suspend fun runDeleteAnimationWithRollback(
    itemIds: Set<String>,
    deletingIds: MutableStateFlow<Set<String>>,
    animationDelayMs: Long = 300L,
    mutation: suspend () -> Unit,
): Result<Unit> {
    if (itemIds.isEmpty()) {
        return Result.success(Unit)
    }

    deletingIds.update { it + itemIds }
    delay(animationDelayMs)

    return runCatching {
        mutation()
        Result.success(Unit)
    }.getOrElse { throwable ->
        deletingIds.update { it - itemIds }
        if (throwable is CancellationException) {
            throw throwable
        }
        Result.failure(throwable)
    }
}
