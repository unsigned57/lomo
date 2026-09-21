package com.lomo.data.engine

import com.lomo.domain.model.EngineReadiness
import com.lomo.domain.model.ProjectionFreshness
import com.lomo.domain.model.WorkspaceMount

/**
 * Pure mount transitions used by [ManagedEngineSession] around a workspace switch.
 *
 * Kept out of the session so the published mount only ever changes in one caller-visible step: the
 * session reads the current mount, asks for the next value here, and publishes it. A `Revalidating`
 * republish during prepare must never be mistaken for a verified projection, and a failed switch
 * may only restore `Verified` when the authority it verified is still the published one.
 */
internal object WorkspaceMountTransitions {
    /** The published mount when it is a ready, authorised, verified one; otherwise null. */
    fun verifiedOrNull(current: WorkspaceMount): WorkspaceMount? =
        current.takeIf { mount ->
            mount.readiness is EngineReadiness.Ready &&
                mount.authority != null &&
                mount.freshness is ProjectionFreshness.Verified
        }

    /**
     * The same mount republished as [ProjectionFreshness.Revalidating] while a candidate prepares.
     * Null when there is no verified snapshot to keep showing as stale.
     */
    fun revalidating(verified: WorkspaceMount?): WorkspaceMount? {
        val freshness = verified?.freshness as? ProjectionFreshness.Verified ?: return null
        return verified.copy(freshness = ProjectionFreshness.Revalidating(freshness.revision))
    }

    /**
     * Restores verified freshness after a failed switch. Null when the current mount moved on to a
     * different authority or is no longer ready, so a stale snapshot cannot mask a real transition.
     */
    fun restoreVerified(
        current: WorkspaceMount,
        verified: WorkspaceMount?,
    ): WorkspaceMount? {
        val snapshot = verified ?: return null
        return if (current.authority == snapshot.authority && current.readiness is EngineReadiness.Ready) {
            current.copy(freshness = snapshot.freshness)
        } else {
            null
        }
    }
}
