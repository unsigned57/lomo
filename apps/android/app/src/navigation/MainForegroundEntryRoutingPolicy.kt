package com.lomo.app.navigation

internal object MainForegroundEntryRoutingPolicy {
    data class State(
        val evaluatedForegroundEntryId: Long,
        val mainForegroundEntryId: Long,
    )

    fun resolve(
        foregroundEntryId: Long,
        evaluatedForegroundEntryId: Long,
        currentMainForegroundEntryId: Long,
        hasVisibleDestination: Boolean,
        isMainVisible: Boolean,
        suppressForegroundAutoInput: Boolean,
    ): State {
        val visibleMainEntryId =
            if (isMainVisible) {
                currentMainForegroundEntryId
            } else {
                0L
            }

        if (foregroundEntryId <= 0L || foregroundEntryId == evaluatedForegroundEntryId || !hasVisibleDestination) {
            return State(
                evaluatedForegroundEntryId = evaluatedForegroundEntryId,
                mainForegroundEntryId = visibleMainEntryId,
            )
        }

        return State(
            evaluatedForegroundEntryId = foregroundEntryId,
            mainForegroundEntryId =
                if (isMainVisible && !suppressForegroundAutoInput) {
                    foregroundEntryId
                } else {
                    0L
                },
        )
    }
}
