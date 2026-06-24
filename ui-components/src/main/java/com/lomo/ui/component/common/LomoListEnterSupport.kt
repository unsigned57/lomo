package com.lomo.ui.component.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.toImmutableSet

/**
 * Anchor object matching the file name to satisfy Detekt MatchingDeclarationName rule.
 */
object LomoListEnterSupport

/**
 * Composable state holder that drives list-level two-phase enter detection — the symmetric
 * counterpart of [rememberLomoListExitState].
 */
class LomoListEnterState(
    val enteringIds: ImmutableSet<String>,
    val onEnterSettled: (String) -> Unit,
)

fun <T> resolveEnteringIds(
    activeEnters: Set<String>,
    allItems: List<T>,
    itemKey: (T) -> String,
): ImmutableSet<String> {
    val currentKeys = allItems.map(itemKey).toSet()
    return activeEnters.filter { it in currentKeys }.toImmutableSet()
}

@Composable
fun <T> rememberLomoListEnterState(
    registry: EnterAnimationRegistry,
    allItems: ImmutableList<T>,
    itemKey: (T) -> String,
): LomoListEnterState {
    val activeEnters by registry.activeEnters.collectAsStateWithLifecycle()

    val enteringIds = remember(allItems, activeEnters) {
        resolveEnteringIds(activeEnters, allItems, itemKey)
    }

    return remember(enteringIds) {
        LomoListEnterState(
            enteringIds = enteringIds,
            onEnterSettled = { id -> registry.settleEnter(id) },
        )
    }
}
