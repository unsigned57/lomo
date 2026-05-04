package com.lomo.ui.component.common

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

const val LAZY_LIST_REMOVE_FADE_DURATION_MILLIS = 300
const val LAZY_LIST_REMOVE_COLLAPSE_DURATION_MILLIS = 300
const val LAZY_LIST_REMOVE_SPRING_SETTLE_WINDOW_MILLIS = 460
const val LAZY_LIST_REMOVE_EFFECTIVE_COLLAPSE_DURATION_MILLIS =
    LAZY_LIST_REMOVE_COLLAPSE_DURATION_MILLIS + LAZY_LIST_REMOVE_SPRING_SETTLE_WINDOW_MILLIS
internal const val LAZY_LIST_REMOVE_BOTTOM_TOP_REVEAL_DELAY_MILLIS = 150L

data class LazyListMotionPlacement(
    val initialOffsetPx: Float,
    val durationMillis: Int,
)

internal data class LazyListRemoveSession(
    val removingItemId: String,
    val removeIndex: Int,
    val wasBottomAnchoredRemove: Boolean,
    val collapseDistancePx: Int,
    val startedAtUptimeMillis: Long,
    val initialVisibleIds: Set<String>,
)

internal enum class LazyListRemoveDirection(
    val offsetSign: Float,
) {
    FromBelow(offsetSign = 1f),
    FromAbove(offsetSign = -1f),
}

@Stable
class LazyListRemoveMotionState(
    private val uptimeMillis: () -> Long = android.os.SystemClock::uptimeMillis,
) {
    private var activeSession by mutableStateOf<LazyListRemoveSession?>(null)
    private val pendingPlacements = mutableStateMapOf<String, LazyListMotionPlacement>()
    private val sharedTopEntryPlacements = mutableStateMapOf<String, LazyListMotionPlacement>()
    private val completedPlacementIds = mutableStateMapOf<String, Unit>()
    private val revealedTopEntryIds = mutableStateMapOf<String, Unit>()
    private val bottomTopEntryVisibleSinceMillis = mutableStateMapOf<String, Long>()
    private var latestVisibleIds: Set<String> = emptySet()
    private var previousVisibleIds: Set<String> = emptySet()
    private var itemIndexById: Map<String, Int> = emptyMap()
    private var bottomEntryTimelineStarted = false
    private var bottomEntryTimelineInitialOffsetPx: Float? = null
    private var bottomEntryTimelineInitialDurationMillis: Int? = null

    fun updateItemOrder(order: Map<String, Int>) {
        itemIndexById = order
        val session = activeSession ?: return
        val updatedIndex = order[session.removingItemId] ?: run {
            if (shouldPreserveRemoveSession(session, uptimeMillis())) return
            clearSession(preservePendingPlacements = true)
            return
        }
        if (updatedIndex != session.removeIndex) {
            activeSession = session.copy(removeIndex = updatedIndex)
        }
    }

    fun syncSession(
        removingItemId: String?,
        currentVisibleIds: Set<String> = latestVisibleIds,
    ) {
        val removeIndex = removingItemId?.let(itemIndexById::get)
        if (removingItemId == null || removeIndex == null) {
            preserveOrClearSession(removingItemId, currentVisibleIds)
            return
        }

        val session = activeSession
        if (session?.removingItemId == removingItemId) return

        clearSession(preservePendingPlacements = false)
        latestVisibleIds = currentVisibleIds
        previousVisibleIds = currentVisibleIds
        activeSession =
            LazyListRemoveSession(
                removingItemId = removingItemId,
                removeIndex = removeIndex,
                wasBottomAnchoredRemove = removeIndex == itemIndexById.size - 1,
                collapseDistancePx = 0,
                startedAtUptimeMillis = uptimeMillis(),
                initialVisibleIds = currentVisibleIds,
            )
    }

    fun onItemMeasured(
        itemId: String,
        itemIndex: Int,
        isRemoving: Boolean,
        heightPx: Int,
        bottomSpacingPx: Int,
    ) {
        val session = activeSession ?: return
        if (!isRemoving || itemId != session.removingItemId || itemIndex != session.removeIndex) return
        val collapseDistancePx = (heightPx + bottomSpacingPx).coerceAtLeast(0)
        if (collapseDistancePx != session.collapseDistancePx) {
            activeSession = session.copy(collapseDistancePx = collapseDistancePx)
        }
    }

    fun onVisibleItemsChanged(snapshot: LazyListMotionViewportSnapshot) {
        val currentVisibleIds = snapshot.viewportVisibleIds()
        latestVisibleIds = currentVisibleIds
        val session = activeSession ?: return
        val newVisibleIds = currentVisibleIds - previousVisibleIds
        previousVisibleIds = currentVisibleIds
        val now = uptimeMillis()
        val frame =
            resolveRemoveFrame(
                session = session,
                snapshot = snapshot,
                currentVisibleIds = currentVisibleIds,
                newVisibleIds = newVisibleIds,
                itemIndexById = itemIndexById,
                pendingPlacementIds = pendingPlacements.keys,
                sharedTopEntryPlacementIds = sharedTopEntryPlacements.keys,
                completedPlacementIds = completedPlacementIds.keys,
                now = now,
            ) ?: return
        updateBottomEntryVisibility(frame, currentVisibleIds)
        snapshot.visibleItems.forEach { visibleItem ->
            applyPlacementUpdate(visibleItem, snapshot, session, frame, now)
        }
    }

    fun placementFor(itemId: String): LazyListMotionPlacement? =
        sharedTopEntryPlacements[itemId] ?: pendingPlacements[itemId]

    fun holdOffsetFor(itemId: String): Float? {
        val session = activeSession ?: return null
        if (
            isHoldOffsetBlocked(
                itemId = itemId,
                session = session,
                pendingPlacementIds = pendingPlacements.keys,
                completedPlacementIds = completedPlacementIds.keys,
                previousVisibleIds = previousVisibleIds,
            )
        ) {
            return null
        }
        val itemIndex = itemIndexById[itemId] ?: return null
        val direction = holdEntryDirectionFor(itemIndex, session.removeIndex) ?: return null
        val now = uptimeMillis()
        val collapseWindow = collapseWindowFor(session)
        return when {
            session.wasBottomAnchoredRemove && itemId !in session.initialVisibleIds ->
                if (itemId in revealedTopEntryIds) null else 0f
            now <= collapseWindow.first || now >= collapseWindow.last -> null
            else -> remainingCollapseDistancePx(session, now) * direction.offsetSign
        }
    }

    fun clearPlacement(itemId: String) {
        val removedPlacement = pendingPlacements.remove(itemId) ?: sharedTopEntryPlacements.remove(itemId)
        val session = activeSession
        if (removedPlacement != null && session != null) {
            val itemIndex = itemIndexById[itemId]
            if ((itemIndex != null && itemIndex < session.removeIndex) || removedPlacement.initialOffsetPx < 0f) {
                completedPlacementIds[itemId] = Unit
            }
        }
    }

    private fun preserveOrClearSession(
        removingItemId: String?,
        currentVisibleIds: Set<String>,
    ) {
        val session = activeSession
        if (
            session != null &&
            shouldPreserveRemoveSession(session, uptimeMillis()) &&
            (removingItemId == null || removingItemId == session.removingItemId)
        ) {
            latestVisibleIds = currentVisibleIds
            previousVisibleIds = currentVisibleIds
        } else {
            clearSession(preservePendingPlacements = true)
        }
    }

    private fun updateBottomEntryVisibility(
        frame: LazyListRemoveFrame,
        currentVisibleIds: Set<String>,
    ) {
        if (!frame.isBottomAnchoredRemove) return
        bottomTopEntryVisibleSinceMillis.keys.removeAll { itemId ->
            itemId !in currentVisibleIds && itemId !in revealedTopEntryIds
        }
    }

    private fun applyPlacementUpdate(
        visibleItem: LazyListMotionVisibleItem,
        snapshot: LazyListMotionViewportSnapshot,
        session: LazyListRemoveSession,
        frame: LazyListRemoveFrame,
        now: Long,
    ) {
        val update =
            resolveRemovePlacementUpdate(
                input =
                    LazyListRemovePlacementInput(
                        visibleItem = visibleItem,
                        snapshot = snapshot,
                        session = session,
                        frame = frame,
                        itemIndexById = itemIndexById,
                        pendingPlacementIds = pendingPlacements.keys,
                        sharedTopEntryPlacementIds = sharedTopEntryPlacements.keys,
                        completedPlacementIds = completedPlacementIds.keys,
                        revealedTopEntryIds = revealedTopEntryIds.keys,
                        bottomTopEntryVisibleSinceMillis = bottomTopEntryVisibleSinceMillis,
                        bottomEntryTimelineStarted = bottomEntryTimelineStarted,
                        bottomEntryTimelineInitialOffsetPx = bottomEntryTimelineInitialOffsetPx,
                        bottomEntryTimelineInitialDurationMillis = bottomEntryTimelineInitialDurationMillis,
                        now = now,
                    ),
            ) ?: return
        if (update.marksRevealedTopEntry) {
            revealedTopEntryIds[update.visibleId] = Unit
        }
        pendingPlacements[update.visibleId] = update.placement
        if (update.startsBottomEntryTimeline) {
            bottomEntryTimelineStarted = true
            if (bottomEntryTimelineInitialOffsetPx == null) {
                bottomEntryTimelineInitialOffsetPx = update.bottomEntryTimelineInitialOffsetPx
                bottomEntryTimelineInitialDurationMillis = frame.remainingMillis
            }
        }
    }

    private fun clearSession(preservePendingPlacements: Boolean) {
        activeSession = null
        latestVisibleIds = emptySet()
        previousVisibleIds = emptySet()
        completedPlacementIds.clear()
        revealedTopEntryIds.clear()
        bottomTopEntryVisibleSinceMillis.clear()
        bottomEntryTimelineStarted = false
        bottomEntryTimelineInitialOffsetPx = null
        bottomEntryTimelineInitialDurationMillis = null
        if (!preservePendingPlacements) {
            pendingPlacements.clear()
            sharedTopEntryPlacements.clear()
        }
    }
}
