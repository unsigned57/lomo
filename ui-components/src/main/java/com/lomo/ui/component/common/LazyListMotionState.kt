package com.lomo.ui.component.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import com.lomo.ui.theme.MotionTokens
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.coroutines.delay

@Stable
class LazyListMotionState {
    internal val removeState = LazyListRemoveMotionState()
    internal val resizeState = LazyListResizeMotionState()
    internal val entranceState = LazyListMotionEntranceState()

    val activeResizeSessionGeneration: Long
        get() = resizeState.activeSessionGeneration

    val pendingResizeScrollByPx: Float
        get() = resizeState.pendingScrollByPx

    fun syncItemOrder(order: Map<String, Int>) {
        removeState.updateItemOrder(order)
        resizeState.updateItemOrder(order)
    }

    fun syncRemoveSession(
        removingItemId: String?,
        currentVisibleIds: Set<String>,
    ) {
        removeState.syncSession(
            removingItemId = removingItemId,
            currentVisibleIds = currentVisibleIds,
        )
    }

    fun onVisibleItemsChanged(snapshot: LazyListMotionViewportSnapshot) {
        removeState.onVisibleItemsChanged(snapshot)
        resizeState.onVisibleItemsChanged(snapshot)
    }

    fun onItemMeasured(
        itemId: String,
        itemIndex: Int,
        isRemoving: Boolean,
        heightPx: Int,
        bottomSpacingPx: Int = 0,
    ) {
        removeState.onItemMeasured(
            itemId = itemId,
            itemIndex = itemIndex,
            isRemoving = isRemoving,
            heightPx = heightPx,
            bottomSpacingPx = bottomSpacingPx,
        )
        resizeState.onItemMeasured(
            itemId = itemId,
            heightPx = heightPx,
        )
    }

    fun beginResizeTransition(
        itemId: String,
        expands: Boolean,
        snapshot: LazyListMotionViewportSnapshot,
    ): Long =
        resizeState.beginTransition(
            itemId = itemId,
            expands = expands,
            snapshot = snapshot,
        )

    fun beginContentResizeTransition(
        itemId: String,
        snapshot: LazyListMotionViewportSnapshot,
    ): Long =
        resizeState.beginTransition(
            itemId = itemId,
            expands = false,
            snapshot = snapshot,
        )

    fun settleResizeTransition(generation: Long) {
        resizeState.settleTransition(generation)
    }

    fun consumePendingResizeScrollByPx(): Float = resizeState.consumePendingScrollByPx()

    fun structureMotionActiveFor(itemId: String): Boolean =
        removeState.placementFor(itemId) != null ||
            removeState.holdOffsetFor(itemId) != null ||
            resizeState.blocksPlacementSpringFor(itemId)
}

@Composable
fun rememberLazyListMotionState(
    itemKeys: ImmutableList<String>,
    removingKeys: ImmutableSet<String>,
    listState: LazyListState,
): LazyListMotionState {
    val state = remember(listState) { LazyListMotionState() }
    val itemOrder =
        remember(itemKeys) {
            itemKeys.mapIndexed { index, key -> key to index }.toMap()
        }
    val removingItemId = remember(removingKeys) { removingKeys.singleOrNull() }
    val activeResizeGeneration = state.activeResizeSessionGeneration

    SideEffect {
        state.syncItemOrder(itemOrder)
        state.syncRemoveSession(
            removingItemId = removingItemId,
            currentVisibleIds = listState.layoutInfo.toLazyListMotionViewportSnapshot().viewportVisibleIds(),
        )
    }

    LaunchedEffect(activeResizeGeneration) {
        if (activeResizeGeneration > 0L) {
            delay(LAZY_LIST_RESIZE_TRANSITION_SETTLE_MILLIS)
            state.settleResizeTransition(activeResizeGeneration)
        }
    }

    LaunchedEffect(listState, state) {
        snapshotFlow { listState.layoutInfo.toLazyListMotionViewportSnapshot() }
            .collect { snapshot -> state.onVisibleItemsChanged(snapshot) }
    }

    LaunchedEffect(listState, state) {
        snapshotFlow { state.pendingResizeScrollByPx }
            .collect {
                val scrollByPx = state.consumePendingResizeScrollByPx()
                if (scrollByPx != 0f) {
                    listState.scrollBy(scrollByPx)
                }
            }
    }

    return state
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun Modifier.lazyListMotionItem(
    lazyItemScope: LazyItemScope,
    itemKey: String,
    motionState: LazyListMotionState,
    entranceState: LazyListItemEntranceState = LazyListItemEntranceState.Settled,
    placementMode: LazyListItemPlacementMode = LazyListItemPlacementMode.Spring,
    structureMotionActive: Boolean = false,
): Modifier {
    val removePlacement = motionState.removeState.placementFor(itemKey)
    val removeHoldOffset = motionState.removeState.holdOffsetFor(itemKey)
    val resizeGuard = motionState.resizeState.viewportEntryGuardFor(itemKey)
    val baseStructureMotionActive =
        structureMotionActive ||
            removePlacement != null ||
            removeHoldOffset != null ||
            resizeGuard != null ||
            motionState.structureMotionActiveFor(itemKey)
    val resolvedStructureMotionActive =
        baseStructureMotionActive ||
            motionState.entranceState.blocksEntranceRecovery(
                itemId = itemKey,
                entranceState = entranceState,
            )
    val policy =
        resolveLazyListItemMotionPolicy(
            entranceState = entranceState,
            placementMode = placementMode,
            structureMotionActive = resolvedStructureMotionActive,
        )
    SideEffect {
        motionState.entranceState.recordResolvedItem(
            itemId = itemKey,
            entranceState = entranceState,
            structureMotionActive = baseStructureMotionActive,
        )
    }
    return with(lazyItemScope) {
        this@lazyListMotionItem.animateItem(
            fadeInSpec = if (policy.usesLazyItemFadeIn) lazyListMotionFadeInSpec() else null,
            fadeOutSpec = null,
            placementSpec = if (policy.usesPlacementSpring) lazyListMotionPlacementSpring() else snap(),
        )
    }
        .lazyListMotionPlacement(
            placement = removePlacement,
            holdOffsetPx = removeHoldOffset,
            onAnimationConsumed = { motionState.removeState.clearPlacement(itemKey) },
        )
        .lazyListMotionPlacement(
            placement = resizeGuard,
            onAnimationConsumed = { motionState.resizeState.clearViewportEntryGuard(itemKey) },
        )
}

private fun lazyListMotionFadeInSpec(): FiniteAnimationSpec<Float> =
    keyframes {
        durationMillis = MotionTokens.DurationLong2
        0f at 0
        1f at MotionTokens.DurationLong2 using MotionTokens.EasingEmphasizedDecelerate
    }

private fun lazyListMotionPlacementSpring(): FiniteAnimationSpec<IntOffset> =
    spring(
        stiffness = Spring.StiffnessLow,
        dampingRatio = Spring.DampingRatioNoBouncy,
    )

@Composable
private fun Modifier.lazyListMotionPlacement(
    placement: LazyListMotionPlacement?,
    holdOffsetPx: Float? = null,
    onAnimationConsumed: () -> Unit,
): Modifier {
    val translationYPx =
        remember(placement, holdOffsetPx) {
            Animatable(placement?.initialOffsetPx ?: holdOffsetPx ?: 0f)
        }
    val latestOnAnimationConsumed by rememberUpdatedState(onAnimationConsumed)

    LaunchedEffect(placement) {
        if (placement == null) return@LaunchedEffect
        translationYPx.animateTo(
            targetValue = 0f,
            animationSpec =
                tween(
                    durationMillis = placement.durationMillis,
                    easing = LinearEasing,
                ),
        )
        latestOnAnimationConsumed()
    }

    return graphicsLayer {
        translationY = translationYPx.value
        if (holdOffsetPx != null && placement == null) {
            alpha = 0f
        }
    }
}
