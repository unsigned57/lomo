package com.lomo.ui.component.common

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf

@Stable
class LazyListMotionEntranceState {
    private val suppressedItemIds = mutableStateMapOf<String, Unit>()

    fun blocksEntranceRecovery(
        itemId: String,
        entranceState: LazyListItemEntranceState,
    ): Boolean =
        entranceState == LazyListItemEntranceState.Active &&
            itemId in suppressedItemIds

    fun recordResolvedItem(
        itemId: String,
        entranceState: LazyListItemEntranceState,
        structureMotionActive: Boolean,
    ) {
        if (entranceState == LazyListItemEntranceState.Settled) {
            suppressedItemIds.clear()
            return
        }
        if (structureMotionActive) {
            suppressedItemIds[itemId] = Unit
        }
    }
}
