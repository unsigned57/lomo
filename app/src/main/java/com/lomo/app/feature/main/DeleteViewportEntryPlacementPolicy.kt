package com.lomo.app.feature.main

internal data class DeleteViewportEntryPlacementUpdate(
    val visibleId: String,
    val placement: DeleteViewportEntryPlacement,
    val marksRevealedTopEntry: Boolean,
    val startsBottomEntryTimeline: Boolean,
    val bottomEntryTimelineInitialOffsetPx: Float,
)

internal fun resolveDeleteViewportEntryPlacementUpdate(
    input: DeleteViewportEntryPlacementInput,
): DeleteViewportEntryPlacementUpdate? {
    if (isHandledDeleteViewportEntry(input)) return null
    val candidate = resolveDeleteViewportEntryCandidate(input) ?: return null
    if (!isDeleteViewportEntryEligible(input, candidate)) return null

    val viewportOvershootPx =
        viewportOvershootPx(
            direction = candidate.direction,
            visibleItem = input.visibleItem,
            snapshot = input.snapshot,
            viewportHeightPx = input.frame.viewportHeightPx,
        )
    return if (viewportOvershootPx <= 0f) {
        null
    } else {
        val initialOffsetPx = resolveInitialOffsetPx(input, candidate, viewportOvershootPx)
        val entryDirection = candidate.direction
        DeleteViewportEntryPlacementUpdate(
            visibleId = input.visibleItem.id,
            placement =
                DeleteViewportEntryPlacement(
                    initialOffsetPx = initialOffsetPx * entryDirection.offsetSign,
                    durationMillis = input.frame.remainingMillis,
                ),
            marksRevealedTopEntry = candidate.isDelayedBottomTopEntry,
            startsBottomEntryTimeline =
                !input.frame.isBottomAnchoredDelete &&
                    candidate.direction == DeleteViewportEntryDirection.FromBelow,
            bottomEntryTimelineInitialOffsetPx = initialOffsetPx,
        )
    }
}

internal data class DeleteViewportEntryPlacementInput(
    val visibleItem: DeleteViewportEntryVisibleItem,
    val snapshot: DeleteViewportEntryVisibilitySnapshot,
    val session: DeleteViewportEntrySession,
    val frame: DeleteViewportEntryCollapseFrame,
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

internal fun isNewUnhandledEntryCandidate(
    visibleId: String,
    currentVisibleIds: Set<String>,
    newVisibleIds: Set<String>,
    session: DeleteViewportEntrySession,
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

internal fun isUnhandledEntryCandidate(
    visibleId: String,
    currentVisibleIds: Set<String>,
    session: DeleteViewportEntrySession,
    pendingPlacementIds: Set<String>,
    sharedTopEntryPlacementIds: Set<String>,
    completedPlacementIds: Set<String>,
): Boolean {
    var eligible = visibleId in currentVisibleIds
    eligible = eligible && visibleId != session.deletingMemoId
    eligible = eligible && visibleId !in pendingPlacementIds
    eligible = eligible && visibleId !in sharedTopEntryPlacementIds
    eligible = eligible && visibleId !in completedPlacementIds
    eligible = eligible && visibleId !in session.initialVisibleIds
    return eligible
}

private data class DeleteViewportEntryCandidate(
    val itemIndex: Int,
    val direction: DeleteViewportEntryDirection,
    val isDelayedBottomTopEntry: Boolean,
)

private fun resolveDeleteViewportEntryCandidate(
    input: DeleteViewportEntryPlacementInput,
): DeleteViewportEntryCandidate? {
    val visibleId = input.visibleItem.id
    val itemIndex = input.itemIndexById[visibleId] ?: return null
    val direction = resolveDeleteViewportEntryDirection(itemIndex, input.session.deleteIndex) ?: return null
    val isDelayedBottomTopEntry =
        input.frame.isBottomAnchoredDelete &&
            direction == DeleteViewportEntryDirection.FromAbove &&
            visibleId !in input.session.initialVisibleIds
    if (isDelayedBottomTopEntry) {
        input.bottomTopEntryVisibleSinceMillis.putIfAbsent(visibleId, input.now)
    }
    return DeleteViewportEntryCandidate(
        itemIndex = itemIndex,
        direction = direction,
        isDelayedBottomTopEntry = isDelayedBottomTopEntry,
    )
}

private fun isHandledDeleteViewportEntry(input: DeleteViewportEntryPlacementInput): Boolean {
    val visibleId = input.visibleItem.id
    var handled = visibleId !in input.frame.currentVisibleIds
    handled = handled || visibleId == input.session.deletingMemoId
    handled = handled || visibleId in input.pendingPlacementIds
    handled = handled || visibleId in input.sharedTopEntryPlacementIds
    handled = handled || visibleId in input.completedPlacementIds
    return handled
}

private fun resolveDeleteViewportEntryDirection(
    itemIndex: Int,
    deleteIndex: Int,
): DeleteViewportEntryDirection? =
    when {
        itemIndex > deleteIndex -> DeleteViewportEntryDirection.FromBelow
        itemIndex < deleteIndex -> DeleteViewportEntryDirection.FromAbove
        else -> null
    }

private fun isDeleteViewportEntryEligible(
    input: DeleteViewportEntryPlacementInput,
    candidate: DeleteViewportEntryCandidate,
): Boolean =
    when (candidate.direction) {
        DeleteViewportEntryDirection.FromBelow -> input.visibleItem.id in input.frame.newVisibleIds
        DeleteViewportEntryDirection.FromAbove ->
            if (input.frame.isBottomAnchoredDelete) {
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
    direction: DeleteViewportEntryDirection,
    visibleItem: DeleteViewportEntryVisibleItem,
    snapshot: DeleteViewportEntryVisibilitySnapshot,
    viewportHeightPx: Int,
): Float =
    when (direction) {
        DeleteViewportEntryDirection.FromBelow -> snapshot.viewportEndPx - visibleItem.topPx
        DeleteViewportEntryDirection.FromAbove -> visibleItem.bottomPx - snapshot.viewportStartPx
    }.coerceAtLeast(0)
        .coerceAtMost(viewportHeightPx)
        .toFloat()

private fun resolveInitialOffsetPx(
    input: DeleteViewportEntryPlacementInput,
    candidate: DeleteViewportEntryCandidate,
    viewportOvershootPx: Float,
): Float =
    when (candidate.direction) {
        DeleteViewportEntryDirection.FromBelow -> resolveBelowEntryOffsetPx(input, viewportOvershootPx)
        DeleteViewportEntryDirection.FromAbove ->
            if (candidate.isDelayedBottomTopEntry) {
                input.frame.remainingDistancePx.coerceAtLeast(viewportOvershootPx)
            } else {
                input.frame.remainingDistancePx
            }
    }

private fun resolveBelowEntryOffsetPx(
    input: DeleteViewportEntryPlacementInput,
    viewportOvershootPx: Float,
): Float {
    val shouldUseSharedBottomTimeline =
        !input.frame.isBottomAnchoredDelete &&
            (input.frame.hasBottomEntryBatch || input.bottomEntryTimelineStarted)
    return if (shouldUseSharedBottomTimeline) {
        bottomEntryTimelineOffsetFor(
            remainingDistancePx = input.frame.remainingDistancePx,
            remainingMillis = input.frame.remainingMillis,
            initialOffsetPx = input.bottomEntryTimelineInitialOffsetPx,
            initialDurationMillis = input.bottomEntryTimelineInitialDurationMillis,
        )
    } else {
        viewportOvershootPx
            .coerceAtMost(input.frame.remainingDistancePx)
            .coerceAtLeast(0f)
    }
}
