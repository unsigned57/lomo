package com.lomo.app.feature.common

import com.lomo.ui.component.common.ExitAnimationRegistry

internal suspend fun <T> runDeleteAnimationWithRollback(
    itemId: String,
    registry: ExitAnimationRegistry<T>,
    item: T,
    anchoredAfterKey: String?,
    mutation: suspend () -> Unit,
) {
    registry.beginExit(itemId, item, anchoredAfterKey)
    var committed = false
    try {
        mutation()
        registry.markExitMutationCommitted(itemId)
        committed = true
    } finally {
        if (!committed) {
            registry.rollbackExit(itemId)
        }
    }
}
