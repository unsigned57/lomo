package com.lomo.domain.repository

/**
 * Owner-planned identity-keyed merge of two memo-shard texts for sync conflict write-back.
 *
 * Implementations must call the workspace document owner — Kotlin must not segment memo blocks via
 * header-line parsers for production write-back authority.
 */
fun interface MemoIdentityConflictMerger {
    /**
     * Returns owner-planned merged bytes when both sides share memo identities; null declines so
     * non-identity text merge (LCS / disjoint concat) may proceed.
     */
    fun mergeSharedIdentities(
        localText: String,
        remoteText: String,
        localLastModified: Long?,
        remoteLastModified: Long?,
    ): String?

    companion object {
        /** Declines every identity merge (tests / pure LCS surfaces without owner). */
        val Decline: MemoIdentityConflictMerger =
            MemoIdentityConflictMerger { _, _, _, _ -> null }
    }
}
