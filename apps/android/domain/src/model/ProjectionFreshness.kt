package com.lomo.domain.model

/**
 * Freshness of the disposable query projection for the active workspace.
 *
 * [Verified] admits reads and writes at [Verified.revision]. [Revalidating] admits reads of the last
 * verified projection while a later scan runs, and never admits writes. A workspace with no trusted
 * projection stays [Unavailable] (Opening), not a phantom verified state.
 */
sealed interface ProjectionFreshness {
    /** No trusted projection is available for this mount. */
    data object Unavailable : ProjectionFreshness

    /**
     * The last verified projection at [lastVerifiedRevision] is readable while the same workspace is
     * re-scanned. Writes stay closed until the scan publishes [Verified].
     */
    data class Revalidating(
        val lastVerifiedRevision: ULong,
    ) : ProjectionFreshness

    /** The published projection was atomically verified at [revision]. */
    data class Verified(
        val revision: ULong,
    ) : ProjectionFreshness
}

/** Only a verified projection admits writes. Revalidation is read-only. */
fun ProjectionFreshness.permitsWrites(): Boolean =
    this is ProjectionFreshness.Verified

/**
 * True only when this freshness value describes a readable projection at [revision].
 *
 * Matching the revision prevents independently collected authority/freshness flows from exposing
 * a retired projection during their brief publication hand-off.
 */
fun ProjectionFreshness.permitsReadsAt(revision: ULong): Boolean =
    when (this) {
        ProjectionFreshness.Unavailable -> false
        is ProjectionFreshness.Revalidating -> lastVerifiedRevision == revision
        is ProjectionFreshness.Verified -> this.revision == revision
    }
