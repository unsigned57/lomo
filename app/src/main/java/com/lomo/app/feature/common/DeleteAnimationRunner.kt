package com.lomo.app.feature.common

import com.lomo.ui.component.common.ExitAnimationRegistry
import kotlinx.coroutines.CancellationException

internal suspend fun <T> runDeleteAnimationWithRollback(
    itemId: String,
    registry: ExitAnimationRegistry<T>,
    item: T,
    anchoredAfterKey: String?,
    mutation: suspend () -> Unit,
) {
    runDeleteAnimationWithRollback(
        items = listOf(Triple(itemId, item, anchoredAfterKey)),
        registry = registry,
        mutation = mutation,
    )
}

internal suspend fun <T> runDeleteAnimationWithRollback(
    items: List<Triple<String, T, String?>>,
    registry: ExitAnimationRegistry<T>,
    mutation: suspend () -> Unit,
) {
    if (items.isEmpty()) {
        return
    }

    items.forEach { (id, item, anchor) ->
        registry.beginExit(id, item, anchor)
    }

    runCatching {
        mutation()
    }.onFailure { throwable ->
        items.forEach { (id, _, _) ->
            registry.settleExit(id)
        }
        throw throwable
    }
}

