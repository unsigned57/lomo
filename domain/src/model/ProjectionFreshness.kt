package com.lomo.domain.model

/**
 * Freshness of the disposable query projection for the active workspace.
 *
 * This state never grants or revokes write authority. Writes are protected independently by
 * [WorkspaceAuthority] plus target expected-revision/fingerprint checks at the platform boundary.
 */
sealed interface ProjectionFreshness {
    /** No active workspace projection is available. */
    data object Unavailable : ProjectionFreshness

    /** A verified projection is usable while a newer staging projection is reconciled. */
    data class Refreshing(
        val lastVerifiedRevision: ULong,
    ) : ProjectionFreshness

    /** The published projection was atomically verified at [revision]. */
    data class Verified(
        val revision: ULong,
    ) : ProjectionFreshness

    /** Refresh stopped without invalidating the last verified projection or write authority. */
    data class Stale(
        val lastVerifiedRevision: ULong,
        val reasonCode: String,
    ) : ProjectionFreshness {
        init {
            require(reasonCode.matches(Regex("[a-z][a-z0-9_.-]{0,127}"))) {
                "Projection freshness reason code must be a bounded canonical identifier"
            }
        }
    }
}
