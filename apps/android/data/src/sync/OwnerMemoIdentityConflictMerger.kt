package com.lomo.data.sync

import com.lomo.domain.repository.MemoIdentityConflictMerger
import com.lomo.nativebridge.mergeMemoShardByIdentity

/**
 * Conversion-only adapter: local/remote shard bytes → workspace owner identity merge plan.
 *
 * Owner declines (`null`) and structured FFI failures both surface as `null` so auto-merge fails
 * closed into conflict review rather than inventing Kotlin memo-block boundaries.
 */
class OwnerMemoIdentityConflictMerger : MemoIdentityConflictMerger {
    override fun mergeSharedIdentities(
        localText: String,
        remoteText: String,
        localLastModified: Long?,
        remoteLastModified: Long?,
    ): String? =
        // behavior-contract: silent-result-ok: owner decline / validation → null for auto-merge review
        runCatching {
            mergeMemoShardByIdentity(
                localText = localText,
                remoteText = remoteText,
                localLastModified = localLastModified,
                remoteLastModified = remoteLastModified,
            )
        }.getOrNull()
}
