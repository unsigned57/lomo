package com.lomo.ui.component.common

/** A creation holds new identities until the durable result identifies its actual row. */
sealed interface PendingEnter {
    val requestId: EnterRequestId

    data class Creating(override val requestId: EnterRequestId, val baselineKeys: Set<String>) : PendingEnter

    data class AwaitingViewport(override val requestId: EnterRequestId, val itemId: String) : PendingEnter
}

data class EnterAnimationState(val activeEnters: Set<String> = emptySet(), val pendingEnter: PendingEnter? = null)
