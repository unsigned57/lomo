package com.lomo.ui.component.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.toImmutableSet

object LomoListEnterSupport

class LomoListEnterState(
    val enteringIds: ImmutableSet<String>,
    val heldIds: ImmutableSet<String>,
    val structuralMotionActive: Boolean,
    val onEnterSettled: (String) -> Unit,
)

data class LomoListEnterDetection(val enteringIds: ImmutableSet<String>, val heldIds: ImmutableSet<String>)

fun <T> resolveEnteringIds(
    enterState: EnterAnimationState,
    allItems: List<T>,
    itemKey: (T) -> String,
    isProvisional: (T) -> Boolean,
): LomoListEnterDetection {
    val keys = allItems.map(itemKey).toSet()
    val heldIds =
        when (val pending = enterState.pendingEnter) {
            // While the identity is unknown, the only row that may be the one under creation is a
            // row the engine has published as an uncommitted create. Holding every row absent from
            // the pre-submit window instead blanked whole pages that paging merely loaded: after a
            // return-to-top the entire fresh top page stayed zero-height for the durable write.
            is PendingEnter.Creating ->
                allItems
                    .filter { item -> itemKey(item) !in pending.baselineKeys && isProvisional(item) }
                    .mapTo(mutableSetOf(), itemKey)

            is PendingEnter.AwaitingViewport -> keys.intersect(setOf(pending.itemId))

            null -> emptySet()
        }
    return LomoListEnterDetection(
        enteringIds = (enterState.activeEnters.intersect(keys) - heldIds).toImmutableSet(),
        heldIds = heldIds.toImmutableSet(),
    )
}

@Composable
fun <T> rememberLomoListEnterState(
    registry: EnterAnimationRegistry,
    allItems: List<T>,
    itemKey: (T) -> String,
    isProvisional: (T) -> Boolean,
): LomoListEnterState {
    val state by registry.enterState.collectAsStateWithLifecycle()
    val currentIsProvisional by rememberUpdatedState(isProvisional)
    val detection = remember(allItems, state) {
        resolveEnteringIds(state, allItems, itemKey, currentIsProvisional)
    }
    return remember(detection, state) {
        LomoListEnterState(
            enteringIds = detection.enteringIds,
            heldIds = detection.heldIds,
            structuralMotionActive = state.pendingEnter != null || state.activeEnters.isNotEmpty(),
            onEnterSettled = registry::settleEnter,
        )
    }
}
