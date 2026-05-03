package com.lomo.app.feature.main

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet

internal const val DELETE_VIEWPORT_ENTRY_FADE_DURATION_MILLIS = 300
internal const val DELETE_VIEWPORT_ENTRY_COLLAPSE_DURATION_MILLIS = 300
internal const val DELETE_VIEWPORT_ENTRY_SPRING_SETTLE_WINDOW_MILLIS = 460
internal const val DELETE_VIEWPORT_ENTRY_EFFECTIVE_COLLAPSE_DURATION_MILLIS =
    DELETE_VIEWPORT_ENTRY_COLLAPSE_DURATION_MILLIS + DELETE_VIEWPORT_ENTRY_SPRING_SETTLE_WINDOW_MILLIS
internal const val DELETE_VIEWPORT_ENTRY_BOTTOM_TOP_REVEAL_DELAY_MILLIS = 150L

internal data class DeleteViewportEntrySession(
    val deletingMemoId: String,
    val deleteIndex: Int,
    val wasBottomAnchoredDelete: Boolean,
    val collapseDistancePx: Int,
    val startedAtUptimeMillis: Long,
    val initialVisibleIds: Set<String>,
)

internal data class DeleteViewportEntryPlacement(
    val initialOffsetPx: Float,
    val durationMillis: Int,
)

internal data class DeleteViewportEntryVisibleItem(
    val id: String,
    val topPx: Int,
    val bottomPx: Int,
)

internal data class DeleteViewportEntryVisibilitySnapshot(
    val visibleItems: List<DeleteViewportEntryVisibleItem>,
    val viewportStartPx: Int,
    val viewportEndPx: Int,
)

internal fun DeleteViewportEntryVisibilitySnapshot.viewportVisibleIds(): Set<String> =
    visibleItems
        .asSequence()
        .filter { item -> item.bottomPx > viewportStartPx && item.topPx < viewportEndPx }
        .map(DeleteViewportEntryVisibleItem::id)
        .toSet()

@Stable
internal class DeleteViewportEntryCompensationState(
    private val uptimeMillis: () -> Long = SystemClock::uptimeMillis,
) {
    private var activeSession by mutableStateOf<DeleteViewportEntrySession?>(null)
    private val pendingPlacements = mutableStateMapOf<String, DeleteViewportEntryPlacement>()
    private val sharedTopEntryPlacements = mutableStateMapOf<String, DeleteViewportEntryPlacement>()
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
        val updatedIndex = order[session.deletingMemoId] ?: run {
            if (shouldPreserveDeleteViewportSession(session, uptimeMillis())) {
                return
            }
            clearSession(preservePendingPlacements = true)
            return
        }
        if (updatedIndex != session.deleteIndex) {
            activeSession = session.copy(deleteIndex = updatedIndex)
        }
    }

    fun syncSession(
        deletingMemoId: String?,
        currentVisibleIds: Set<String> = latestVisibleIds,
    ) {
        val deleteIndex = deletingMemoId?.let(itemIndexById::get)
        if (deletingMemoId == null || deleteIndex == null) {
            val session = activeSession
            if (
                session != null &&
                shouldPreserveDeleteViewportSession(session, uptimeMillis()) &&
                (deletingMemoId == null || deletingMemoId == session.deletingMemoId)
            ) {
                latestVisibleIds = currentVisibleIds
                previousVisibleIds = currentVisibleIds
                return
            }
            clearSession(preservePendingPlacements = true)
            return
        }

        val session = activeSession
        if (session?.deletingMemoId == deletingMemoId) {
            return
        }

        pendingPlacements.clear()
        sharedTopEntryPlacements.clear()
        completedPlacementIds.clear()
        revealedTopEntryIds.clear()
        bottomTopEntryVisibleSinceMillis.clear()
        bottomEntryTimelineStarted = false
        bottomEntryTimelineInitialOffsetPx = null
        bottomEntryTimelineInitialDurationMillis = null
        latestVisibleIds = currentVisibleIds
        previousVisibleIds = currentVisibleIds
        activeSession =
            DeleteViewportEntrySession(
                deletingMemoId = deletingMemoId,
                deleteIndex = deleteIndex,
                wasBottomAnchoredDelete = deleteIndex == itemIndexById.size - 1,
                collapseDistancePx = 0,
                startedAtUptimeMillis = uptimeMillis(),
                initialVisibleIds = currentVisibleIds,
            )
    }

    fun onItemMeasured(
        itemId: String,
        itemIndex: Int,
        isDeleting: Boolean,
        heightPx: Int,
        bottomSpacingPx: Int,
    ) {
        val session = activeSession ?: return
        if (!isDeleting || itemId != session.deletingMemoId || itemIndex != session.deleteIndex) {
            return
        }
        val updatedCollapseDistancePx = (heightPx + bottomSpacingPx).coerceAtLeast(0)
        if (updatedCollapseDistancePx != session.collapseDistancePx) {
            activeSession = session.copy(collapseDistancePx = updatedCollapseDistancePx)
        }
    }

    fun onVisibleItemsChanged(snapshot: DeleteViewportEntryVisibilitySnapshot) {
        val currentVisibleIds = snapshot.viewportVisibleIds()
        latestVisibleIds = currentVisibleIds
        val session = activeSession ?: return
        val newVisibleIds = currentVisibleIds - previousVisibleIds
        previousVisibleIds = currentVisibleIds
        val now = uptimeMillis()
        val frame =
            resolveDeleteViewportEntryCollapseFrame(
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
        if (frame.isBottomAnchoredDelete) {
            bottomTopEntryVisibleSinceMillis.keys.removeAll { itemId ->
                itemId !in currentVisibleIds && itemId !in revealedTopEntryIds
            }
        }
        snapshot.visibleItems.forEach { visibleItem ->
            val update =
                resolveDeleteViewportEntryPlacementUpdate(
                    DeleteViewportEntryPlacementInput(
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
                ) ?: return@forEach
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
    }

    fun compensationFor(itemId: String): DeleteViewportEntryPlacement? = pendingPlacements[itemId]

    fun sharedTopEntryCompensationFor(itemId: String): DeleteViewportEntryPlacement? = sharedTopEntryPlacements[itemId]

    fun holdOffsetFor(itemId: String): Float? {
        val session = activeSession ?: return null
        if (
            isHoldOffsetBlocked(
                itemId = itemId,
                session = session,
                initialVisibleIds = session.initialVisibleIds,
                pendingPlacementIds = pendingPlacements.keys,
                completedPlacementIds = completedPlacementIds.keys,
                previousVisibleIds = previousVisibleIds,
            )
        ) {
            return null
        }

        val itemIndex = itemIndexById[itemId] ?: return null
        val entryDirection = holdEntryDirectionFor(itemIndex, session.deleteIndex) ?: return null

        val now = uptimeMillis()
        val collapseWindow = collapseWindowFor(session)
        return when {
            session.wasBottomAnchoredDelete && itemId !in session.initialVisibleIds ->
                if (itemId in revealedTopEntryIds) null else 0f
            now <= collapseWindow.first || now >= collapseWindow.last -> null
            else -> remainingCollapseDistancePx(session, now) * entryDirection.offsetSign
        }
    }

    fun sharedTopEntryOffsetFor(itemId: String): Float? {
        val session = activeSession ?: return null
        val itemIndex = itemIndexById[itemId] ?: return null
        if (isSharedTopEntryOffsetBlocked(itemId, itemIndex, session)) return null

        val now = uptimeMillis()
        val collapseWindow = collapseWindowFor(session)
        return when {
            session.collapseDistancePx <= 0 -> 0f
            now <= collapseWindow.first -> 0f
            now >= collapseWindow.last -> null
            else -> remainingCollapseDistancePx(session, now) * DeleteViewportEntryDirection.FromBelow.offsetSign
        }
    }

    fun sharedBottomEntryOffsetFor(itemId: String): Float? {
        val session = activeSession ?: return null
        val itemIndex = itemIndexById[itemId] ?: return null
        if (session.wasBottomAnchoredDelete || itemId == session.deletingMemoId || itemIndex <= session.deleteIndex) {
            return null
        }
        val now = uptimeMillis()
        val collapseWindow = collapseWindowFor(session)
        return when {
            session.collapseDistancePx <= 0 -> 0f
            now <= collapseWindow.first -> 0f
            now >= collapseWindow.last -> null
            else -> 0f
        }
    }

    fun clearCompensation(itemId: String) {
        val removedPlacement = pendingPlacements.remove(itemId) ?: sharedTopEntryPlacements.remove(itemId)
        val session = activeSession
        if (removedPlacement != null && session != null) {
            val itemIndex = itemIndexById[itemId]
            if ((itemIndex != null && itemIndex < session.deleteIndex) || removedPlacement.initialOffsetPx < 0f) {
                completedPlacementIds[itemId] = Unit
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

internal fun DeleteViewportEntryCompensationState.shouldHoldUntilCompensated(itemId: String): Boolean =
    holdOffsetFor(itemId) != null

internal enum class DeleteViewportEntryDirection(
    val offsetSign: Float,
) {
    FromBelow(offsetSign = 1f),
    FromAbove(offsetSign = -1f),
}

@Composable
internal fun rememberDeleteViewportEntryCompensation(
    sourceItems: ImmutableList<MemoUiModel>,
    deletingIds: ImmutableSet<String>,
    listState: LazyListState,
): DeleteViewportEntryCompensationState {
    val viewportEntryCompensation = remember(listState) { DeleteViewportEntryCompensationState() }
    val itemOrder =
        remember(sourceItems) {
            sourceItems.mapIndexed { index, item -> item.memo.id to index }.toMap()
        }
    val deletingMemoId = remember(deletingIds) { deletingIds.singleOrNull() }

    SideEffect {
        val currentVisibleIds = listState.layoutInfo.toDeleteViewportEntryVisibilitySnapshot().viewportVisibleIds()
        viewportEntryCompensation.updateItemOrder(itemOrder)
        viewportEntryCompensation.syncSession(
            deletingMemoId = deletingMemoId,
            currentVisibleIds = currentVisibleIds,
        )
    }

    LaunchedEffect(listState, viewportEntryCompensation) {
        snapshotFlow {
            listState.layoutInfo.toDeleteViewportEntryVisibilitySnapshot()
        }.collect { snapshot ->
            viewportEntryCompensation.onVisibleItemsChanged(snapshot)
        }
    }

    return viewportEntryCompensation
}

private fun LazyListLayoutInfo.toDeleteViewportEntryVisibilitySnapshot(): DeleteViewportEntryVisibilitySnapshot =
    DeleteViewportEntryVisibilitySnapshot(
        visibleItems =
            visibleItemsInfo
                .mapNotNull { item ->
                    (item.key as? String)?.let { key ->
                        DeleteViewportEntryVisibleItem(
                            id = key,
                            topPx = item.offset,
                            bottomPx = item.offset + item.size,
                        )
                    }
                },
        viewportStartPx = viewportStartOffset,
        viewportEndPx = viewportEndOffset,
    )

@Composable
internal fun Modifier.deleteViewportEntryCompensation(
    compensation: DeleteViewportEntryPlacement?,
    holdOffsetPx: Float? = null,
    onAnimationConsumed: () -> Unit,
): Modifier =
    deleteViewportEntryCompensationInternal(
        compensation = compensation,
        holdOffsetPx = holdOffsetPx,
        onAnimationConsumed = onAnimationConsumed,
    )

@Composable
private fun Modifier.deleteViewportEntryCompensationInternal(
    compensation: DeleteViewportEntryPlacement?,
    holdOffsetPx: Float?,
    onAnimationConsumed: () -> Unit,
): Modifier {
    // Keyed on the compensation instance so the Animatable is created with the
    // correct initial offset from the very first frame — no snapTo jump.
    val translationYPx =
        remember(compensation, holdOffsetPx) { Animatable(compensation?.initialOffsetPx ?: holdOffsetPx ?: 0f) }
    val latestOnAnimationConsumed by rememberUpdatedState(onAnimationConsumed)

    LaunchedEffect(compensation) {
        if (compensation == null) {
            return@LaunchedEffect
        }
        translationYPx.animateTo(
            targetValue = 0f,
            animationSpec =
                tween(
                    durationMillis = compensation.durationMillis,
                    easing = LinearEasing,
                ),
        )
        latestOnAnimationConsumed()
    }

    return graphicsLayer {
        translationY = translationYPx.value
        if (holdOffsetPx != null && compensation == null) {
            alpha = 0f
        }
    }

}
