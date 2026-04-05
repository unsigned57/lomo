package com.lomo.app.feature.common

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal suspend fun runDeleteAnimationWithRollback(
    itemId: String,
    deletingIds: MutableStateFlow<Set<String>>,
    collapsedIds: MutableStateFlow<Set<String>>? = null,
    animationDelayMs: Long = 300L,
    mutation: suspend () -> Unit,
): Result<Unit> {
    return runDeleteAnimationWithRollback(
        itemIds = setOf(itemId),
        deletingIds = deletingIds,
        collapsedIds = collapsedIds,
        animationDelayMs = animationDelayMs,
        mutation = mutation,
    )
}

internal suspend fun runDeleteAnimationWithRollback(
    itemIds: Set<String>,
    deletingIds: MutableStateFlow<Set<String>>,
    collapsedIds: MutableStateFlow<Set<String>>? = null,
    animationDelayMs: Long = 300L,
    mutation: suspend () -> Unit,
): Result<Unit> {
    if (itemIds.isEmpty()) {
        return Result.success(Unit)
    }

    deletingIds.update { it + itemIds }
    delay(animationDelayMs)
    collapsedIds?.update { it + itemIds }

    return runCatching {
        mutation()
        Result.success(Unit)
    }.getOrElse { throwable ->
        deletingIds.update { it - itemIds }
        collapsedIds?.update { it - itemIds }
        if (throwable is CancellationException) {
            throw throwable
        }
        Result.failure(throwable)
    }
}
