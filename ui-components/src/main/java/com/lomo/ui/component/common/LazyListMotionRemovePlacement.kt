package com.lomo.ui.component.common

internal data class LazyListRemovePlacementUpdate(
    val visibleId: String,
    val placement: LazyListMotionPlacement,
    val marksRevealedTopEntry: Boolean,
    val startsBottomEntryTimeline: Boolean,
    val bottomEntryTimelineInitialOffsetPx: Float,
)

internal data class LazyListRemovePlacementInput(
    val visibleItem: LazyListMotionVisibleItem,
    val snapshot: LazyListMotionViewportSnapshot,
    val session: LazyListRemoveSession,
    val frame: LazyListRemoveFrame,
    val itemIndexById: Map<String, Int>,
    val pendingPlacementIds: Set<String>,
    val sharedTopEntryPlacementIds: Set<String>,
    val completedPlacementIds: Set<String>,
    val revealedTopEntryIds: Set<String>,
    val bottomTopEntryVisibleSinceMillis: MutableMap<String, Long>,
    val bottomEntryTimelineStarted: Boolean,
    val bottomEntryTimelineInitialOffsetPx: Float?,
    val bottomEntryTimelineInitialDurationMillis: Int?,
    val now: Long,
)

private data class LazyListRemoveCandidate(
    val direction: LazyListRemoveDirection,
    val isDelayedBottomTopEntry: Boolean,
)

internal fun resolveRemovePlacementUpdate(
    input: LazyListRemovePlacementInput,
): LazyListRemovePlacementUpdate? {
    if (isHandledRemoveEntry(input)) return null
    val candidate = resolveRemoveCandidate(input) ?: return null
    if (!isRemoveEntryEligible(input, candidate)) return null
    val viewportOvershootPx = viewportOvershootPx(candidate.direction, input.visibleItem, input.snapshot)
    if (viewportOvershootPx <= 0f) return null
    val initialOffsetPx = resolveInitialOffsetPx(input, candidate, viewportOvershootPx)
    return LazyListRemovePlacementUpdate(
        visibleId = input.visibleItem.id,
        placement =
            LazyListMotionPlacement(
                initialOffsetPx = initialOffsetPx * candidate.direction.offsetSign,
                durationMillis = input.frame.remainingMillis,
            ),
        marksRevealedTopEntry = candidate.isDelayedBottomTopEntry,
        startsBottomEntryTimeline =
            !input.frame.isBottomAnchoredRemove &&
                candidate.direction == LazyListRemoveDirection.FromBelow,
        bottomEntryTimelineInitialOffsetPx = initialOffsetPx,
    )
}

private fun resolveRemoveCandidate(input: LazyListRemovePlacementInput): LazyListRemoveCandidate? {
    val visibleId = input.visibleItem.id
    val itemIndex = input.itemIndexById[visibleId] ?: return null
    val direction = removeEntryDirectionFor(itemIndex, input.session.removeIndex) ?: return null
    val isDelayedBottomTopEntry =
        input.frame.isBottomAnchoredRemove &&
            direction == LazyListRemoveDirection.FromAbove &&
            visibleId !in input.session.initialVisibleIds
    if (isDelayedBottomTopEntry) {
        input.bottomTopEntryVisibleSinceMillis.putIfAbsent(visibleId, input.now)
    }
    return LazyListRemoveCandidate(
        direction = direction,
        isDelayedBottomTopEntry = isDelayedBottomTopEntry,
    )
}

private fun isHandledRemoveEntry(input: LazyListRemovePlacementInput): Boolean {
    val visibleId = input.visibleItem.id
    var handled = visibleId !in input.frame.currentVisibleIds
    handled = handled || visibleId == input.session.removingItemId
    handled = handled || visibleId in input.pendingPlacementIds
    handled = handled || visibleId in input.sharedTopEntryPlacementIds
    handled = handled || visibleId in input.completedPlacementIds
    return handled
}

private fun isRemoveEntryEligible(
    input: LazyListRemovePlacementInput,
    candidate: LazyListRemoveCandidate,
): Boolean =
    when (candidate.direction) {
        LazyListRemoveDirection.FromBelow -> input.visibleItem.id in input.frame.newVisibleIds
        LazyListRemoveDirection.FromAbove ->
            if (input.frame.isBottomAnchoredRemove) {
                candidate.isDelayedBottomTopEntry &&
                    isBottomTopEntryRevealEligible(
                        itemId = input.visibleItem.id,
                        now = input.now,
                        revealedTopEntryIds = input.revealedTopEntryIds,
                        bottomTopEntryVisibleSinceMillis = input.bottomTopEntryVisibleSinceMillis,
                    )
            } else {
                input.frame.hasTopEntryBatch || input.visibleItem.id !in input.session.initialVisibleIds
            }
    }

private fun viewportOvershootPx(
    direction: LazyListRemoveDirection,
    visibleItem: LazyListMotionVisibleItem,
    snapshot: LazyListMotionViewportSnapshot,
): Float =
    when (direction) {
        LazyListRemoveDirection.FromBelow -> snapshot.viewportEndPx - visibleItem.offsetPx
        LazyListRemoveDirection.FromAbove -> visibleItem.bottomPx - snapshot.viewportStartPx
    }.coerceAtLeast(0)
        .coerceAtMost((snapshot.viewportEndPx - snapshot.viewportStartPx).coerceAtLeast(0))
        .toFloat()

private fun resolveInitialOffsetPx(
    input: LazyListRemovePlacementInput,
    candidate: LazyListRemoveCandidate,
    viewportOvershootPx: Float,
): Float =
    when (candidate.direction) {
        LazyListRemoveDirection.FromBelow -> resolveBelowEntryOffsetPx(input, viewportOvershootPx)
        LazyListRemoveDirection.FromAbove ->
            if (candidate.isDelayedBottomTopEntry) {
                input.frame.remainingDistancePx.coerceAtLeast(viewportOvershootPx)
            } else {
                input.frame.remainingDistancePx
            }
    }

private fun resolveBelowEntryOffsetPx(
    input: LazyListRemovePlacementInput,
    viewportOvershootPx: Float,
): Float {
    val useSharedTimeline =
        !input.frame.isBottomAnchoredRemove &&
            (input.frame.hasBottomEntryBatch || input.bottomEntryTimelineStarted)
    return if (useSharedTimeline) {
        bottomEntryTimelineOffsetFor(
            remainingDistancePx = input.frame.remainingDistancePx,
            remainingMillis = input.frame.remainingMillis,
            initialOffsetPx = input.bottomEntryTimelineInitialOffsetPx,
            initialDurationMillis = input.bottomEntryTimelineInitialDurationMillis,
        )
    } else {
        viewportOvershootPx.coerceAtMost(input.frame.remainingDistancePx).coerceAtLeast(0f)
    }
}

private fun removeEntryDirectionFor(
    itemIndex: Int,
    removeIndex: Int,
): LazyListRemoveDirection? =
    when {
        itemIndex > removeIndex -> LazyListRemoveDirection.FromBelow
        itemIndex < removeIndex -> LazyListRemoveDirection.FromAbove
        else -> null
    }
