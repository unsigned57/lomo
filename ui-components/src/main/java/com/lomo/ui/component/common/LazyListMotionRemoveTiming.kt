package com.lomo.ui.component.common

internal fun holdEntryDirectionFor(
    itemIndex: Int,
    removeIndex: Int,
): LazyListRemoveDirection? =
    when {
        itemIndex < removeIndex -> LazyListRemoveDirection.FromAbove
        else -> null
    }

internal fun shouldPreserveRemoveSession(
    session: LazyListRemoveSession,
    now: Long,
): Boolean = now < collapseWindowFor(session).last

internal fun isBottomTopEntryRevealEligible(
    itemId: String,
    now: Long,
    revealedTopEntryIds: Set<String>,
    bottomTopEntryVisibleSinceMillis: Map<String, Long>,
): Boolean {
    if (itemId in revealedTopEntryIds) return true
    val firstSeenAt = bottomTopEntryVisibleSinceMillis[itemId] ?: return false
    return now - firstSeenAt >= LAZY_LIST_REMOVE_BOTTOM_TOP_REVEAL_DELAY_MILLIS
}

internal fun bottomEntryTimelineOffsetFor(
    remainingDistancePx: Float,
    remainingMillis: Int,
    initialOffsetPx: Float?,
    initialDurationMillis: Int?,
): Float {
    val startingOffsetPx = initialOffsetPx ?: return remainingDistancePx
    val startingDurationMillis = initialDurationMillis ?: return remainingDistancePx
    if (startingDurationMillis <= 0) return remainingDistancePx
    return startingOffsetPx * (remainingMillis.toFloat() / startingDurationMillis.toFloat())
}

internal fun isHoldOffsetBlocked(
    itemId: String,
    session: LazyListRemoveSession,
    pendingPlacementIds: Set<String>,
    completedPlacementIds: Set<String>,
    previousVisibleIds: Set<String>,
): Boolean {
    var blocked = itemId == session.removingItemId
    blocked = blocked || itemId in session.initialVisibleIds
    blocked = blocked || itemId in pendingPlacementIds
    blocked = blocked || itemId in completedPlacementIds
    blocked = blocked || session.collapseDistancePx <= 0
    blocked = blocked || previousVisibleIds.isEmpty()
    return blocked
}

internal fun collapseWindowFor(session: LazyListRemoveSession): LongRange {
    val collapseStart = session.startedAtUptimeMillis + LAZY_LIST_REMOVE_FADE_DURATION_MILLIS
    val collapseEnd = collapseStart + LAZY_LIST_REMOVE_EFFECTIVE_COLLAPSE_DURATION_MILLIS
    return collapseStart..collapseEnd
}

internal fun remainingCollapseDistancePx(
    session: LazyListRemoveSession,
    now: Long,
): Float {
    val collapseStart = session.startedAtUptimeMillis + LAZY_LIST_REMOVE_FADE_DURATION_MILLIS
    val elapsedMillis =
        (now - collapseStart).coerceIn(0L, LAZY_LIST_REMOVE_EFFECTIVE_COLLAPSE_DURATION_MILLIS.toLong())
    val remainingFraction =
        1f - (elapsedMillis.toFloat() / LAZY_LIST_REMOVE_EFFECTIVE_COLLAPSE_DURATION_MILLIS.toFloat())
    return (session.collapseDistancePx * remainingFraction).coerceAtLeast(0f)
}
