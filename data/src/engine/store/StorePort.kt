package com.lomo.data.engine.store

/**
 * Production store surface (P3-10) over BoltFFI `query_memos` / `get_memo` /
 * `apply_memo_command` / reminder / rebuild APIs.
 *
 * Sole local-data authority after Room cutover. Kotlin never opens SQLite.
 */
data class StoreMemoFilters(
    val tag: String? = null,
    val tagSubtree: Boolean = false,
    val dateFromMs: Long? = null,
    val dateToMs: Long? = null,
    val hasTodo: Boolean? = null,
    val hasAttachment: Boolean? = null,
    val hasUrl: Boolean? = null,
    val pinnedOnly: Boolean = false,
    val includeTrash: Boolean = false,
    val trashOnly: Boolean = false,
)

data class StoreMemoQuery(
    val searchText: String? = null,
    val filters: StoreMemoFilters = StoreMemoFilters(),
)

data class StorePageCursor(
    val encoded: String,
)

data class StoreMemoSummary(
    val memoId: String,
    val sourcePath: String,
    val fileFingerprint: String,
    val updatedAtMs: Long,
    val createdAtMs: Long,
    val hasTodo: Boolean,
    val hasUrl: Boolean,
    val hasAttachment: Boolean,
    val isPinned: Boolean,
    val isTrashed: Boolean,
    val bodyPreview: String,
    val contentRevision: Long,
    val rank: Double? = null,
    val tags: List<String> = emptyList(),
    val imageUrls: List<String> = emptyList(),
    val reminders: List<com.lomo.domain.model.ReminderMarker> = emptyList(),
)

data class StoreMemoPage(
    val items: List<StoreMemoSummary>,
    val nextCursor: StorePageCursor?,
    val highWaterRevision: Long,
    val queryFingerprint: String,
)

data class StoreMemoSnapshot(
    val summary: StoreMemoSummary,
    val body: String,
)

data class StoreSidebarDateCount(
    val date: String,
    val count: Int,
)

data class StoreSidebarTagCount(
    val name: String,
    val count: Int,
)

data class StoreSidebarProjection(
    val schemaVersion: UInt,
    val memoCount: Int,
    val dateCounts: List<StoreSidebarDateCount>,
    val tagCounts: List<StoreSidebarTagCount>,
)

data class StoreMemoCommit(
    val operationId: String,
    val memoId: String,
    val coreRevision: Long,
    val eventSequence: Long,
    val contentRevision: Long,
    val fileFingerprint: String,
    val scopes: List<String>,
    val idempotentReplay: Boolean,
)

enum class StoreMemoCommandKind {
    Create,
    Update,
    Delete,
    PermanentDelete,
    Restore,
    Pin,
    Unpin,
    HistoryRestore,
}

data class StoreMemoCommand(
    val operationId: String,
    val kind: StoreMemoCommandKind,
    val memoId: String,
    val expectedRevision: Long,
    val expectedFingerprint: String? = null,
    val content: String? = null,
    val tags: List<String> = emptyList(),
    val pin: Boolean? = null,
    /** Committed promote plans only; empty means no media promote in this operation. */
    val pendingPromotes: List<com.lomo.data.engine.media.MediaPromotePlan> = emptyList(),
    /** Required for SAF create; absent for update/delete/pin and ignored by Direct writes. */
    val chronologyEpochMs: Long? = null,
)

data class StoreRebuildResult(
    val memosIndexed: Long,
    val fileCount: Long,
    val attachmentCount: Long,
    val workspaceDigest: String,
    val storeDigest: String,
    val corruptLomoIsolated: Long,
    val highWaterRevision: Long,
)

/** History-window attachment path for D6 orphan keep-set (store-owned projection). */
data class StoreHistoryAttachmentRef(
    val memoId: String,
    val revision: Long,
    val relativePath: String,
    val ownerKey: String,
)

data class StoreMemoHistoryRevision(
    val revision: Long,
    val createdAtMs: Long,
    val content: String,
    val fileFingerprint: String,
)

data class StoreMemoHistoryPage(
    val items: List<StoreMemoHistoryRevision>,
    val nextCursor: String?,
)

data class StorePlannedAlarm(
    val opaqueId: String,
    val memoIdentity: String,
    val triggerAtUtcMs: Long,
    val isCatchUp: Boolean,
)

data class StoreReminderPlan(
    val alarms: List<StorePlannedAlarm>,
    val workspaceGeneration: String,
)

interface StorePort {
    fun queryMemos(
        query: StoreMemoQuery,
        cursor: StorePageCursor?,
        pageSize: Int,
    ): StoreMemoPage

    fun getMemo(memoId: String): StoreMemoSnapshot?

    fun sidebarProjection(): StoreSidebarProjection

    /** Attachment paths still referenced by durable history revision bodies. */
    fun listHistoryAttachmentRefs(): List<StoreHistoryAttachmentRef>

    fun listMemoHistory(memoId: String, cursor: String?, limit: Int): StoreMemoHistoryPage =
        error("Store history capability is not available on this port")

    fun applyMemoCommand(command: StoreMemoCommand): StoreMemoCommit

    fun startRebuild(batchSize: Int): StoreRebuildResult
}
