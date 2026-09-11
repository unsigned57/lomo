package com.lomo.domain.model

/**
 * Freshness of the disposable query projection for the active workspace.
 *
 * A verified projection may be used while it refreshes. A newly promoted workspace remains
 * read-only until its first atomic projection build verifies the candidate facts.
 */
sealed interface ProjectionFreshness {
    /** No active workspace projection is available. */
    data object Unavailable : ProjectionFreshness

    /** A newly promoted workspace is readable only after its first projection build commits. */
    data class Building(
        val baseRevision: ULong,
    ) : ProjectionFreshness

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

    /** The first projection build failed; no verified projection exists for this authority. */
    data class Failed(
        val baseRevision: ULong,
        val reasonCode: String,
    ) : ProjectionFreshness {
        init {
            require(reasonCode.matches(Regex("[a-z][a-z0-9_.-]{0,127}"))) {
                "Projection freshness reason code must be a bounded canonical identifier"
            }
        }
    }
}

/** Only a verified projection, including a refresh over an existing verified base, admits writes. */
fun ProjectionFreshness.permitsWrites(): Boolean =
    when (this) {
        ProjectionFreshness.Unavailable,
        is ProjectionFreshness.Building,
        is ProjectionFreshness.Failed,
        -> false
        is ProjectionFreshness.Refreshing,
        is ProjectionFreshness.Stale,
        is ProjectionFreshness.Verified,
        -> true
    }

/**
 * True only when this freshness value describes a readable projection at [revision].
 *
 * Matching the revision prevents independently collected authority/freshness flows from exposing
 * a retired projection during their brief publication hand-off.
 */
fun ProjectionFreshness.permitsReadsAt(revision: ULong): Boolean =
    when (this) {
        ProjectionFreshness.Unavailable,
        is ProjectionFreshness.Building,
        is ProjectionFreshness.Failed,
        -> false
        is ProjectionFreshness.Refreshing -> lastVerifiedRevision == revision
        is ProjectionFreshness.Stale -> lastVerifiedRevision == revision
        is ProjectionFreshness.Verified -> this.revision == revision
    }
