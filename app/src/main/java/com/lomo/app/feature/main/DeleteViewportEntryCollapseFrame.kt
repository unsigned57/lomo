package com.lomo.app.feature.main

internal data class DeleteViewportEntryCollapseFrame(
    val currentVisibleIds: Set<String>,
    val newVisibleIds: Set<String>,
    val viewportHeightPx: Int,
    val remainingDistancePx: Float,
    val remainingMillis: Int,
    val isBottomAnchoredDelete: Boolean,
    val hasBottomEntryBatch: Boolean,
    val hasTopEntryBatch: Boolean,
)

internal fun resolveDeleteViewportEntryCollapseFrame(
    session: DeleteViewportEntrySession,
    snapshot: DeleteViewportEntryVisibilitySnapshot,
    currentVisibleIds: Set<String>,
    newVisibleIds: Set<String>,
    itemIndexById: Map<String, Int>,
    pendingPlacementIds: Set<String>,
    sharedTopEntryPlacementIds: Set<String>,
    completedPlacementIds: Set<String>,
    now: Long,
): DeleteViewportEntryCollapseFrame? {
    if (session.collapseDistancePx <= 0) return null

    val collapseStart = session.startedAtUptimeMillis + DELETE_VIEWPORT_ENTRY_FADE_DURATION_MILLIS
    val collapseEnd = collapseStart + DELETE_VIEWPORT_ENTRY_EFFECTIVE_COLLAPSE_DURATION_MILLIS
    if (now <= collapseStart || now >= collapseEnd) return null

    val viewportHeightPx = (snapshot.viewportEndPx - snapshot.viewportStartPx).coerceAtLeast(0)
    val remainingDistancePx =
        remainingCollapseDistancePx(session = session, now = now)
            .coerceAtMost(viewportHeightPx.toFloat().coerceAtLeast(0f))
    val remainingMillis = (collapseEnd - now).toInt().coerceAtLeast(1)
    return DeleteViewportEntryCollapseFrame(
        currentVisibleIds = currentVisibleIds,
        newVisibleIds = newVisibleIds,
        viewportHeightPx = viewportHeightPx,
        remainingDistancePx = remainingDistancePx,
        remainingMillis = remainingMillis,
        isBottomAnchoredDelete = session.wasBottomAnchoredDelete,
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
    snapshot: DeleteViewportEntryVisibilitySnapshot,
    currentVisibleIds: Set<String>,
    newVisibleIds: Set<String>,
    session: DeleteViewportEntrySession,
    itemIndexById: Map<String, Int>,
    pendingPlacementIds: Set<String>,
    sharedTopEntryPlacementIds: Set<String>,
    completedPlacementIds: Set<String>,
): Boolean {
    if (session.wasBottomAnchoredDelete) return false
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
        ) && itemIndex > session.deleteIndex
    } > 1
}

private fun hasTopEntryBatch(
    snapshot: DeleteViewportEntryVisibilitySnapshot,
    currentVisibleIds: Set<String>,
    session: DeleteViewportEntrySession,
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
        ) && itemIndex < session.deleteIndex
    }
