package com.lomo.data.sync

import com.lomo.domain.model.SyncConflictTextMerge
import com.lomo.domain.repository.MemoIdentityConflictMerger

/**
 * Production sync conflict text merge: owner identity merge + domain LCS / disjoint concat.
 *
 * All write-back and auto-resolution surfaces in `data` must use this entry so memo block
 * segmentation never re-enters Kotlin header-line authority.
 */
object SyncConflictMerge {
    val identityMerger: MemoIdentityConflictMerger = OwnerMemoIdentityConflictMerger()

    fun merge(
        localText: String?,
        remoteText: String?,
        localLastModified: Long? = null,
        remoteLastModified: Long? = null,
        policy: SyncConflictTextMerge.Policy = SyncConflictTextMerge.Policy(),
    ): String? =
        SyncConflictTextMerge.merge(
            localText = localText,
            remoteText = remoteText,
            localLastModified = localLastModified,
            remoteLastModified = remoteLastModified,
            policy = policy,
            identityMerger = identityMerger,
        )
}
