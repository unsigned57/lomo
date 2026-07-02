package com.lomo.app.feature.common

import com.lomo.ui.component.common.ExitAnimationRegistry

internal suspend fun <T> runDeleteAnimationWithRollback(
    itemId: String,
    registry: ExitAnimationRegistry<T>,
    item: T,
    anchoredAfterKey: String?,
    mutation: suspend () -> Unit,
) {
    runDeleteAnimationWithRollback(
        items = listOf(DeleteAnimationItem(itemId, item, anchoredAfterKey)),
        registry = registry,
        mutation = mutation,
    )
}

internal suspend fun <T> runDeleteAnimationWithRollback(
    items: List<DeleteAnimationItem<T>>,
    registry: ExitAnimationRegistry<T>,
    mutation: suspend () -> Unit,
) {
    if (items.isEmpty()) {
        return
    }

    items.forEach { item ->
        registry.beginExit(
            id = item.id,
            item = item.snapshot,
            anchoredAfterKey = item.anchoredAfterKey,
        )
    }

    runCatching {
        mutation()
        items.forEach { item ->
            registry.markExitMutationCommitted(item.id)
        }
    }.onFailure { throwable ->
        items.forEach { item ->
            registry.rollbackExit(item.id)
        }
        throw throwable
    }
}

data class DeleteAnimationItem<T>(
    val id: String,
    val snapshot: T,
    val anchoredAfterKey: String?,
)
