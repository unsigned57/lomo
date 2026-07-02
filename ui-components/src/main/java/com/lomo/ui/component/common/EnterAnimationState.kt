package com.lomo.ui.component.common

sealed interface HeadEnterBaseline {
    data class ExistingHead(val id: String) : HeadEnterBaseline

    data object EmptyList : HeadEnterBaseline
}

data class PendingHeadEnter(
    val requestId: EnterRequestId,
    val baseline: HeadEnterBaseline,
)

data class EnterAnimationState(
    val activeEnters: Set<String> = emptySet(),
    val pendingHeadEnters: List<PendingHeadEnter> = emptyList(),
)
