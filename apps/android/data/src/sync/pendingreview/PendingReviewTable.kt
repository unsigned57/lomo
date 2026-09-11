package com.lomo.data.sync.pendingreview

/**
 * Sync Inbox pending-review table: the only Kotlin-owned durable sync surface (P5-13 boundary).
 *
 * The inbox is an independent SAF flow the Rust sync kernel cannot path-open, so this table stays
 * Kotlin-side by architecture. Remote-sync journals/index/shards/conflicts are owned by
 * `.lomo/sync/v1` in `lomo-sync`; memo/query/FTS projections remain in the Rust store owner only.
 */
interface PendingReviewTable {
    suspend fun getByBackend(
        backend: String,
        workspaceGeneration: String,
    ): PendingSyncReviewRecord?

    suspend fun upsert(record: PendingSyncReviewRecord)

    suspend fun deleteByBackend(
        backend: String,
        workspaceGeneration: String,
    )

    /** Removes every record across backends and workspace generations. */
    suspend fun clearAll()
}
