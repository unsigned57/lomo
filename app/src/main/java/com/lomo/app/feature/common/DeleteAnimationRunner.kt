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
    deletingIds.update { it + itemId }
    delay(animationDelayMs)

    return try {
        mutation()
        Result.success(Unit)
    } catch (cancellation: CancellationException) {
        deletingIds.update { it - itemId }
        throw cancellation
    } catch (throwable: Throwable) {
        deletingIds.update { it - itemId }
        Result.failure(throwable)
    }
}
