package com.lomo.ui.component.common

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class EnterAnimationRegistry {
    private var nextRequestValue = 0L
    private val _enterState = MutableStateFlow(EnterAnimationState())
    val enterState: StateFlow<EnterAnimationState> = _enterState.asStateFlow()

    fun beginPendingEnter(baselineKeys: Set<String>): EnterRequestId {
        check(_enterState.value.pendingEnter == null) { "A creation presentation is already pending" }
        val requestId = EnterRequestId(++nextRequestValue)
        _enterState.update { state ->
            state.copy(pendingEnter = PendingEnter.Creating(requestId, baselineKeys.toSet()))
        }
        return requestId
    }

    fun bindPendingEnter(requestId: EnterRequestId, itemId: String) {
        _enterState.update { state ->
            if (state.pendingEnter?.requestId != requestId) {
                state
            } else {
                state.copy(pendingEnter = PendingEnter.AwaitingViewport(requestId, itemId))
            }
        }
    }

    /** The viewport owner acknowledges position before any row can start its enter. */
    fun startPendingEnter(requestId: EnterRequestId) {
        _enterState.update { state ->
            val pending = state.pendingEnter
            if (pending?.requestId != requestId) {
                state
            } else {
                check(pending is PendingEnter.AwaitingViewport) { "Creation has no committed identity" }
                state.copy(activeEnters = state.activeEnters + pending.itemId, pendingEnter = null)
            }
        }
    }

    fun cancelEnterRequest(requestId: EnterRequestId) {
        _enterState.update { state ->
            if (state.pendingEnter?.requestId == requestId) state.copy(pendingEnter = null) else state
        }
    }

    fun settleEnter(id: String) {
        _enterState.update { state -> state.copy(activeEnters = state.activeEnters - id) }
    }

    fun clear() {
        _enterState.value = EnterAnimationState()
    }
}
