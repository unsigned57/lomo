package com.lomo.app

import androidx.lifecycle.Lifecycle

internal object ForegroundEntryPolicy {
    data class State(
        val entryId: Long,
        val ignoreNextResume: Boolean,
    )

    fun initialState(currentState: Lifecycle.State): State =
        if (currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            State(entryId = 1L, ignoreNextResume = true)
        } else {
            State(entryId = 0L, ignoreNextResume = false)
        }

    fun applyLifecycleEvent(
        state: State,
        event: Lifecycle.Event,
    ): State =
        when (event) {
            Lifecycle.Event.ON_RESUME ->
                if (state.ignoreNextResume) {
                    state.copy(ignoreNextResume = false)
                } else {
                    state.copy(entryId = state.entryId + 1L)
                }

            Lifecycle.Event.ON_PAUSE,
            Lifecycle.Event.ON_STOP,
            Lifecycle.Event.ON_DESTROY,
            -> state.copy(ignoreNextResume = false)

            else -> state
        }
}
