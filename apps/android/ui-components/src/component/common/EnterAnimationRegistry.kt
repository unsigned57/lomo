package com.lomo.ui.component.common

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class EnterAnimationRegistry {
    private var nextRequestValue = 0L
    private val _enterState = MutableStateFlow(EnterAnimationState())
    val enterState: StateFlow<EnterAnimationState> = _enterState.asStateFlow()

    fun beginEnter(id: String) {
        _enterState.update { state ->
            state.copy(activeEnters = state.activeEnters + id)
        }
    }

    fun beginPendingHeadEnter(baseline: HeadEnterBaseline): EnterRequestId {
        val requestId = EnterRequestId(++nextRequestValue)
        _enterState.update { state ->
            state.copy(
                pendingHeadEnters = state.pendingHeadEnters +
                    PendingHeadEnter(
                        requestId = requestId,
                        baseline = baseline,
                    ),
            )
        }
        return requestId
    }

    fun resolvePendingHeadEnter(
        requestId: EnterRequestId,
        headId: String,
    ) {
        _enterState.update { state ->
            if (state.pendingHeadEnters.none { it.requestId == requestId }) {
                state
            } else {
                state.copy(
                    activeEnters = state.activeEnters + headId,
                    pendingHeadEnters = state.pendingHeadEnters.filterNot { it.requestId == requestId },
                )
            }
        }
    }

    fun cancelEnterRequest(requestId: EnterRequestId) {
        _enterState.update { state ->
            state.copy(
                pendingHeadEnters = state.pendingHeadEnters.filterNot { it.requestId == requestId },
            )
        }
    }

    fun settleEnter(id: String) {
        _enterState.update { state ->
            state.copy(activeEnters = state.activeEnters - id)
        }
    }

    fun clear() {
        _enterState.value = EnterAnimationState()
    }
}
