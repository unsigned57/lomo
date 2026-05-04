package com.lomo.ui.component.common

internal data class LazyListRemoveFrame(
    val currentVisibleIds: Set<String>,
    val newVisibleIds: Set<String>,
    val viewportHeightPx: Int,
    val remainingDistancePx: Float,
    val remainingMillis: Int,
    val isBottomAnchoredRemove: Boolean,
    val hasBottomEntryBatch: Boolean,
    val hasTopEntryBatch: Boolean,
)

internal fun resolveRemoveFrame(
    session: LazyListRemoveSession,
    snapshot: LazyListMotionViewportSnapshot,
    currentVisibleIds: Set<String>,
    newVisibleIds: Set<String>,
    itemIndexById: Map<String, Int>,
    pendingPlacementIds: Set<String>,
    sharedTopEntryPlacementIds: Set<String>,
    completedPlacementIds: Set<String>,
    now: Long,
): LazyListRemoveFrame? {
    if (session.collapseDistancePx <= 0) return null
    val collapseStart = session.startedAtUptimeMillis + LAZY_LIST_REMOVE_FADE_DURATION_MILLIS
    val collapseEnd = collapseStart + LAZY_LIST_REMOVE_EFFECTIVE_COLLAPSE_DURATION_MILLIS
    if (now <= collapseStart || now >= collapseEnd) return null
    val viewportHeightPx = (snapshot.viewportEndPx - snapshot.viewportStartPx).coerceAtLeast(0)
    return LazyListRemoveFrame(
        currentVisibleIds = currentVisibleIds,
        newVisibleIds = newVisibleIds,
        viewportHeightPx = viewportHeightPx,
        remainingDistancePx =
            remainingCollapseDistancePx(session = session, now = now)
                .coerceAtMost(viewportHeightPx.toFloat().coerceAtLeast(0f)),
        remainingMillis = (collapseEnd - now).toInt().coerceAtLeast(1),
        isBottomAnchoredRemove = session.wasBottomAnchoredRemove,
        hasBottomEntryBatch =
            hasBottomEntryBatch(
                snapshot = snapshot,
                currentVisibleIds = currentVisibleIds,
                newVisibleIds = newVisibleIds,
                session = session,
                itemIndexById = itemIndexById,
                pendingPlacementIds = pendingPlacementIds,
                sharedTopEntryPlacementIds = sharedTopEntryPlacementIds,
                completedPlacementIds = completedPlacementIds,
            ),
        hasTopEntryBatch =
            hasTopEntryBatch(
                snapshot = snapshot,
                currentVisibleIds = currentVisibleIds,
                session = session,
                itemIndexById = itemIndexById,
                pendingPlacementIds = pendingPlacementIds,
                sharedTopEntryPlacementIds = sharedTopEntryPlacementIds,
                completedPlacementIds = completedPlacementIds,
            ),
    )
}

private fun hasBottomEntryBatch(
    snapshot: LazyListMotionViewportSnapshot,
    currentVisibleIds: Set<String>,
    newVisibleIds: Set<String>,
    session: LazyListRemoveSession,
    itemIndexById: Map<String, Int>,
    pendingPlacementIds: Set<String>,
    sharedTopEntryPlacementIds: Set<String>,
    completedPlacementIds: Set<String>,
): Boolean {
    if (session.wasBottomAnchoredRemove) return false
    return snapshot.visibleItems.count { visibleItem ->
        val itemIndex = itemIndexById[visibleItem.id] ?: return@count false
        isNewUnhandledEntryCandidate(
            visibleId = visibleItem.id,
            currentVisibleIds = currentVisibleIds,
            newVisibleIds = newVisibleIds,
            session = session,
            pendingPlacementIds = pendingPlacementIds,
            sharedTopEntryPlacementIds = sharedTopEntryPlacementIds,
            completedPlacementIds = completedPlacementIds,
        ) &&
            itemIndex > session.removeIndex
    } > 1
}

private fun hasTopEntryBatch(
    snapshot: LazyListMotionViewportSnapshot,
    currentVisibleIds: Set<String>,
    session: LazyListRemoveSession,
    itemIndexById: Map<String, Int>,
    pendingPlacementIds: Set<String>,
    sharedTopEntryPlacementIds: Set<String>,
    completedPlacementIds: Set<String>,
): Boolean =
    snapshot.visibleItems.any { visibleItem ->
        val itemIndex = itemIndexById[visibleItem.id] ?: return@any false
        isUnhandledEntryCandidate(
            visibleId = visibleItem.id,
            currentVisibleIds = currentVisibleIds,
            session = session,
            pendingPlacementIds = pendingPlacementIds,
            sharedTopEntryPlacementIds = sharedTopEntryPlacementIds,
            completedPlacementIds = completedPlacementIds,
        ) &&
            itemIndex < session.removeIndex
    }

private fun isNewUnhandledEntryCandidate(
    visibleId: String,
    currentVisibleIds: Set<String>,
    newVisibleIds: Set<String>,
    session: LazyListRemoveSession,
    pendingPlacementIds: Set<String>,
    sharedTopEntryPlacementIds: Set<String>,
    completedPlacementIds: Set<String>,
): Boolean =
    visibleId in newVisibleIds &&
        isUnhandledEntryCandidate(
            visibleId = visibleId,
            currentVisibleIds = currentVisibleIds,
            session = session,
            pendingPlacementIds = pendingPlacementIds,
            sharedTopEntryPlacementIds = sharedTopEntryPlacementIds,
            completedPlacementIds = completedPlacementIds,
        )

private fun isUnhandledEntryCandidate(
    visibleId: String,
    currentVisibleIds: Set<String>,
    session: LazyListRemoveSession,
    pendingPlacementIds: Set<String>,
    sharedTopEntryPlacementIds: Set<String>,
    completedPlacementIds: Set<String>,
): Boolean {
    var eligible = visibleId in currentVisibleIds
    eligible = eligible && visibleId != session.removingItemId
    eligible = eligible && visibleId !in pendingPlacementIds
    eligible = eligible && visibleId !in sharedTopEntryPlacementIds
    eligible = eligible && visibleId !in completedPlacementIds
    eligible = eligible && visibleId !in session.initialVisibleIds
    return eligible
}
