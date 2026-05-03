package com.lomo.app.feature.main

internal fun shouldPreserveDeleteViewportSession(
    session: DeleteViewportEntrySession,
    now: Long,
): Boolean = now < collapseWindowFor(session).last

internal fun isBottomTopEntryRevealEligible(
    itemId: String,
    now: Long,
    revealedTopEntryIds: Set<String>,
    bottomTopEntryVisibleSinceMillis: Map<String, Long>,
): Boolean {
    if (itemId in revealedTopEntryIds) {
        return true
    }
    val firstSeenAt = bottomTopEntryVisibleSinceMillis[itemId] ?: return false
    return now - firstSeenAt >= DELETE_VIEWPORT_ENTRY_BOTTOM_TOP_REVEAL_DELAY_MILLIS
}

internal fun bottomEntryTimelineOffsetFor(
    remainingDistancePx: Float,
    remainingMillis: Int,
    initialOffsetPx: Float?,
    initialDurationMillis: Int?,
): Float {
    val startingOffsetPx = initialOffsetPx ?: return remainingDistancePx
    val startingDurationMillis = initialDurationMillis ?: return remainingDistancePx
    if (startingDurationMillis <= 0) {
        return remainingDistancePx
    }
    return startingOffsetPx * (remainingMillis.toFloat() / startingDurationMillis.toFloat())
}

internal fun isHoldOffsetBlocked(
    itemId: String,
    session: DeleteViewportEntrySession,
    initialVisibleIds: Set<String>,
    pendingPlacementIds: Set<String>,
    completedPlacementIds: Set<String>,
    previousVisibleIds: Set<String>,
): Boolean {
    var blocked = itemId == session.deletingMemoId
    blocked = blocked || itemId in initialVisibleIds
    blocked = blocked || itemId in pendingPlacementIds
    blocked = blocked || itemId in completedPlacementIds
    blocked = blocked || session.collapseDistancePx <= 0
    blocked = blocked || previousVisibleIds.isEmpty()
    return blocked
}

internal fun holdEntryDirectionFor(
    itemIndex: Int,
    deleteIndex: Int,
): DeleteViewportEntryDirection? =
    when {
        itemIndex < deleteIndex -> DeleteViewportEntryDirection.FromAbove
        else -> null
    }

internal fun collapseWindowFor(session: DeleteViewportEntrySession): LongRange {
    val collapseStart = session.startedAtUptimeMillis + DELETE_VIEWPORT_ENTRY_FADE_DURATION_MILLIS
    val collapseEnd = collapseStart + DELETE_VIEWPORT_ENTRY_EFFECTIVE_COLLAPSE_DURATION_MILLIS
    return collapseStart..collapseEnd
}

internal fun remainingCollapseDistancePx(
    session: DeleteViewportEntrySession,
    now: Long,
): Float {
    val collapseStart = session.startedAtUptimeMillis + DELETE_VIEWPORT_ENTRY_FADE_DURATION_MILLIS
    val elapsedCollapseMillis =
        (now - collapseStart).coerceIn(0L, DELETE_VIEWPORT_ENTRY_EFFECTIVE_COLLAPSE_DURATION_MILLIS.toLong())
    val remainingFraction =
        1f - (elapsedCollapseMillis.toFloat() / DELETE_VIEWPORT_ENTRY_EFFECTIVE_COLLAPSE_DURATION_MILLIS.toFloat())
    return (session.collapseDistancePx * remainingFraction).coerceAtLeast(0f)
}

internal fun isSharedTopEntryOffsetBlocked(
    itemId: String,
    itemIndex: Int,
    session: DeleteViewportEntrySession,
): Boolean {
    var blocked = itemId == session.deletingMemoId
    blocked = blocked || itemIndex >= session.deleteIndex
    blocked = blocked || !session.wasBottomAnchoredDelete
    blocked = blocked || itemId !in session.initialVisibleIds
    return blocked
}
