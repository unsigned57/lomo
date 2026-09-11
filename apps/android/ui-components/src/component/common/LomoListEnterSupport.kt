package com.lomo.ui.component.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
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

data class LomoListEnterDetection(
    val enteringIds: ImmutableSet<String>,
    val resolvedPendingHeadEnters: Map<EnterRequestId, String>,
)

fun <T> resolveEnteringIds(
    enterState: EnterAnimationState,
    allItems: List<T>,
    itemKey: (T) -> String,
): LomoListEnterDetection {
    val currentKeys = allItems.map(itemKey).toSet()
    val activeEnteringIds = enterState.activeEnters.filter { it in currentKeys }
    val headId = allItems.firstOrNull()?.let(itemKey)
    val resolvedPendingHeadEnters =
        headId
            ?.let { loadedHeadId ->
                enterState.pendingHeadEnters
                    .filter { pendingEnter -> pendingEnter.baseline.isResolvedBy(loadedHeadId) }
                    .associate { pendingEnter -> pendingEnter.requestId to loadedHeadId }
            }
            ?: emptyMap()
    return LomoListEnterDetection(
        enteringIds = (activeEnteringIds + resolvedPendingHeadEnters.values).toImmutableSet(),
        resolvedPendingHeadEnters = resolvedPendingHeadEnters,
    )
}

private fun HeadEnterBaseline.isResolvedBy(headId: String): Boolean =
    when (this) {
        HeadEnterBaseline.EmptyList -> true
        is HeadEnterBaseline.ExistingHead -> id != headId
    }

@Composable
fun <T> rememberLomoListEnterState(
    registry: EnterAnimationRegistry,
    allItems: ImmutableList<T>,
    itemKey: (T) -> String,
): LomoListEnterState {
    val enterState by registry.enterState.collectAsStateWithLifecycle()

    val detection = remember(allItems, enterState) {
        resolveEnteringIds(enterState, allItems, itemKey)
    }

    SideEffect {
        detection.resolvedPendingHeadEnters.forEach { (requestId, headId) ->
            registry.resolvePendingHeadEnter(
                requestId = requestId,
                headId = headId,
            )
        }
    }

    return remember(detection.enteringIds) {
        LomoListEnterState(
            enteringIds = detection.enteringIds,
            onEnterSettled = { id -> registry.settleEnter(id) },
        )
    }
}
