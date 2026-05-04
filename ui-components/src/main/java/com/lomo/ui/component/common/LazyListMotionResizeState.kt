package com.lomo.ui.component.common

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lomo.ui.theme.MotionTokens
import kotlin.math.min

internal const val LAZY_LIST_RESIZE_POST_FRAME_BUFFER_MILLIS = 80L
const val LAZY_LIST_RESIZE_TRANSITION_SETTLE_MILLIS =
    MotionTokens.DurationLong2 + LAZY_LIST_RESIZE_POST_FRAME_BUFFER_MILLIS
private const val LAZY_LIST_RESIZE_VIEWPORT_ENTRY_GUARD_MILLIS = 160

private data class LazyListResizeSession(
    val itemId: String,
    val itemIndex: Int?,
    val expands: Boolean,
    val previousHeightPx: Int?,
    val remainingAnchorScrollPx: Int,
    val initialVisibleIds: Set<String>,
    val previousVisibleIds: Set<String>,
    val isHeightTransitionActive: Boolean,
    val awaitsPostSettleViewportSync: Boolean,
)

@Stable
class LazyListResizeMotionState {
    var activeSessionGeneration by mutableLongStateOf(0L)
        private set
    var pendingScrollByPx by mutableFloatStateOf(0f)
        private set
    private var activeSession by mutableStateOf<LazyListResizeSession?>(null)
    private var itemIndexById: Map<String, Int> = emptyMap()
    private val viewportEntryGuards = mutableStateMapOf<String, LazyListMotionPlacement>()
    private val completedViewportEntryGuardIds = mutableStateMapOf<String, Unit>()
    private val postSettleBlockedItemIds = mutableStateMapOf<String, Unit>()

    fun updateItemOrder(order: Map<String, Int>) {
        itemIndexById = order
        val session = activeSession
        if (
            session != null &&
            !session.isHeightTransitionActive &&
            !session.awaitsPostSettleViewportSync &&
            viewportEntryGuards.isEmpty()
        ) {
            postSettleBlockedItemIds.clear()
            completedViewportEntryGuardIds.clear()
            activeSession = null
        }
    }

    fun blocksPlacementSpringFor(itemId: String): Boolean {
        val session = activeSession
        if (itemId in postSettleBlockedItemIds) return true
        if (session == null) return viewportEntryGuards.containsKey(itemId)
        val heightTransitionActive = session.isHeightTransitionActive
        return (heightTransitionActive && session.itemId == itemId) ||
            (heightTransitionActive && isResizeAffectedSibling(itemId, session)) ||
            viewportEntryGuards.containsKey(itemId) ||
            (heightTransitionActive && itemId in completedViewportEntryGuardIds) ||
            (heightTransitionActive && isViewportEntryCandidate(itemId, session))
    }

    fun beginTransition(
        itemId: String,
        expands: Boolean,
        snapshot: LazyListMotionViewportSnapshot,
    ): Long {
        val visibleItem = snapshot.visibleItems.firstOrNull { item -> item.id == itemId }
        val initialVisibleIds = snapshot.viewportVisibleIds()
        activeSessionGeneration += 1
        pendingScrollByPx = 0f
        viewportEntryGuards.clear()
        completedViewportEntryGuardIds.clear()
        postSettleBlockedItemIds.clear()
        activeSession =
            LazyListResizeSession(
                itemId = itemId,
                itemIndex = itemIndexById[itemId] ?: visibleItem?.index,
                expands = expands,
                previousHeightPx = visibleItem?.sizePx,
                remainingAnchorScrollPx =
                    if (expands || visibleItem == null) {
                        0
                    } else {
                        visibleItem.resolveCollapseAnchorScrollPx(snapshot.viewportStartPx)
                    },
                initialVisibleIds = initialVisibleIds,
                previousVisibleIds = initialVisibleIds,
                isHeightTransitionActive = true,
                awaitsPostSettleViewportSync = false,
            )
        return activeSessionGeneration
    }

    fun onVisibleItemsChanged(snapshot: LazyListMotionViewportSnapshot) {
        val session = activeSession ?: return
        val currentVisibleIds = snapshot.viewportVisibleIds()
        val newVisibleIds = currentVisibleIds - session.previousVisibleIds
        activeSession =
            session.copy(
                previousVisibleIds = currentVisibleIds,
                awaitsPostSettleViewportSync =
                    if (session.isHeightTransitionActive) {
                        session.awaitsPostSettleViewportSync
                    } else {
                        false
                    },
            )
        if (!session.isHeightTransitionActive) return
        if (session.expands) return
        newVisibleIds.forEach { itemId ->
            if (isViewportEntryCandidate(itemId, session)) {
                viewportEntryGuards[itemId] =
                    LazyListMotionPlacement(
                        initialOffsetPx = 0f,
                        durationMillis = LAZY_LIST_RESIZE_VIEWPORT_ENTRY_GUARD_MILLIS,
                    )
            }
        }
    }

    fun viewportEntryGuardFor(itemId: String): LazyListMotionPlacement? = viewportEntryGuards[itemId]

    fun clearViewportEntryGuard(itemId: String) {
        if (viewportEntryGuards.remove(itemId) != null) {
            completedViewportEntryGuardIds[itemId] = Unit
        }
    }

    fun onItemMeasured(
        itemId: String,
        heightPx: Int,
    ) {
        val session = activeSession ?: return
        if (session.itemId != itemId || !session.isHeightTransitionActive) return
        val previousHeightPx = session.previousHeightPx
        if (session.expands || previousHeightPx == null || session.remainingAnchorScrollPx <= 0) {
            activeSession = session.copy(previousHeightPx = heightPx)
            return
        }

        val shrinkPx = (previousHeightPx - heightPx).coerceAtLeast(0)
        val appliedScrollPx = min(shrinkPx, session.remainingAnchorScrollPx)
        if (appliedScrollPx > 0) {
            pendingScrollByPx -= appliedScrollPx.toFloat()
        }
        activeSession =
            session.copy(
                previousHeightPx = heightPx,
                remainingAnchorScrollPx = session.remainingAnchorScrollPx - appliedScrollPx,
            )
    }

    fun settleTransition(generation: Long) {
        if (generation != activeSessionGeneration) return
        val session = activeSession
        if (session?.isHeightTransitionActive == true) {
            postSettleBlockedItemIds.clear()
            itemIndexById.keys
                .filter { itemId -> itemId == session.itemId || isResizeAffectedSibling(itemId, session) }
                .forEach { itemId -> postSettleBlockedItemIds[itemId] = Unit }
            viewportEntryGuards.keys.forEach { itemId -> postSettleBlockedItemIds[itemId] = Unit }
            completedViewportEntryGuardIds.keys.forEach { itemId -> postSettleBlockedItemIds[itemId] = Unit }
        }
        activeSession =
            session?.copy(
                previousHeightPx = null,
                remainingAnchorScrollPx = 0,
                isHeightTransitionActive = false,
                awaitsPostSettleViewportSync = true,
            )
        pendingScrollByPx = 0f
    }

    fun consumePendingScrollByPx(): Float {
        val consumed = pendingScrollByPx
        pendingScrollByPx = 0f
        return consumed
    }

    private fun isViewportEntryCandidate(
        itemId: String,
        session: LazyListResizeSession,
    ): Boolean {
        if (session.expands) return false
        if (itemId == session.itemId) return false
        if (itemId in session.initialVisibleIds) return false
        if (itemId in viewportEntryGuards) return false
        if (itemId in completedViewportEntryGuardIds) return false
        val resizedIndex = session.itemIndex ?: return false
        val itemIndex = itemIndexById[itemId] ?: return false
        return itemIndex != resizedIndex
    }

    private fun isResizeAffectedSibling(
        itemId: String,
        session: LazyListResizeSession,
    ): Boolean {
        if (itemId == session.itemId) return false
        val resizedIndex = session.itemIndex ?: return false
        val itemIndex = itemIndexById[itemId] ?: return false
        return itemIndex > resizedIndex
    }
}

private fun LazyListMotionVisibleItem.resolveCollapseAnchorScrollPx(viewportStartPx: Int): Int =
    if (offsetPx < viewportStartPx && bottomPx > viewportStartPx) {
        (viewportStartPx - offsetPx)
            .coerceAtLeast(0)
            .coerceAtMost(sizePx)
    } else {
        0
    }
