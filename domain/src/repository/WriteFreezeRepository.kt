package com.lomo.domain.repository

import kotlinx.coroutines.flow.StateFlow

/**
 * Global freeze for workspace writes during candidate root switch.
 *
 * Distinct from engine readiness: freeze is a temporary barrier so candidate validation cannot race
 * concurrent memo/sync mutations. Engine readiness remains the durable write authority.
 */
interface WriteFreezeRepository {
    val isFrozen: StateFlow<Boolean>

    /** Begins a freeze. Returns false when a freeze is already active. */
    fun begin(): Boolean

    /** Ends the active freeze. Idempotent. */
    fun end()
}
