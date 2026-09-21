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
    val dateFromInclusiveMs: Long? = null,
    val dateUntilExclusiveMs: Long? = null,
    val hasTodo: Boolean? = null,
    val hasAttachment: Boolean? = null,
    val hasUrl: Boolean? = null,
    val pinnedOnly: Boolean = false,
    val includeTrash: Boolean = false,
    val trashOnly: Boolean = false,
)

enum class StoreMemoSortField {
    CreatedAt,
    UpdatedAt,
}

enum class StoreSortDirection {
    Ascending,
    Descending,
}

data class StoreMemoSort(
    val field: StoreMemoSortField = StoreMemoSortField.CreatedAt,
    val direction: StoreSortDirection = StoreSortDirection.Descending,
)

/** Inclusive upper bound for a stable ordering window (used by Daily Review sessions). */
data class StoreMemoQueryBoundary(
    val isPinned: Boolean,
    val primarySortMs: Long,
    val createdAtMs: Long,
    val memoId: String,
)

data class StoreMemoQuery(
    val searchText: String? = null,
    val filters: StoreMemoFilters = StoreMemoFilters(),
    val sort: StoreMemoSort = StoreMemoSort(),
    val boundary: StoreMemoQueryBoundary? = null,
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
    /** Published by a begun create whose durable commit has not landed yet. */
    val isPending: Boolean = false,
    /** Full-document character count from the store projection (UTF-16 code units). */
    val charCount: Long = 0,
)

data class StoreMemoPage(
    val items: List<StoreMemoSummary>,
    val nextCursor: StorePageCursor?,
    val highWaterRevision: Long,
    val queryFingerprint: String,
    val prevCursor: StorePageCursor? = null,
    val itemsBefore: Long = 0,
    val itemsAfter: Long = 0,
)

data class StoreMemoSnapshot(
    val summary: StoreMemoSummary,
    val body: String,
)

/** Compact materialized statistics row; no memo body crosses the repository boundary. */
data class StoreMemoStatisticsRow(
    val createdAtMs: Long,
    val wordCount: Long,
    val charCount: Long,
)

/** Rust-owned projection domains attached to each committed store mutation. */
enum class StoreInvalidationScope {
    MemoList,
    Search,
    Trash,
    Pin,
    Tags,
    Stats,
    Reminder,
    Full,
}

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
    val scopes: List<StoreInvalidationScope>,
    val idempotentReplay: Boolean,
)

/** CAS facts captured from one trash projection row for an atomic permanent-delete batch. */
data class StoreMemoDeleteTarget(
    val memoId: String,
    val sourcePath: String,
    val expectedRevision: Long,
    val expectedFingerprint: String,
    /** Filled only after a verified SAF provider mutation; Direct leaves this null. */
    val resultFingerprint: String? = null,
)

data class StoreMemoDeletedMemo(
    val memoId: String,
    val reminderIds: List<String>,
)

/** One logical batch publication; all target rows share this revision/event pair. */
data class StoreMemoBatchCommit(
    val operationId: String,
    val deleted: List<StoreMemoDeletedMemo>,
    val coreRevision: Long,
    val eventSequence: Long,
    val scopes: List<StoreInvalidationScope>,
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
    /** Explicit chronology is accepted only for create/backfill; Rust owns mutation commit clocks. */
    val chronologyEpochMs: Long? = null,
    /** History sequence used by [StoreMemoCommandKind.HistoryRestore] onto session restore. */
    val historyRevision: Long? = null,
)

data class StoreRebuildResult(
    val memosIndexed: Long,
    val fileCount: Long,
    val attachmentCount: Long,
    val workspaceDigest: String,
    val storeDigest: String,
    val corruptLomoIsolated: Long,
    val highWaterRevision: Long,
    val rewritten: Boolean,
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
    /** Durable occurrence identity (`generation␟reminder␟triggerMs`) issued by the Rust plan. */
    val occurrenceId: String,
    val opaqueId: String,
    val memoIdentity: String,
    val triggerAtUtcMs: Long,
    val isCatchUp: Boolean,
)

data class StoreReminderPlan(
    val alarms: List<StorePlannedAlarm>,
    /**
     * Future alarms omitted because the Rust rolling window is full. Non-zero means the caller
     * must re-plan after the earliest in-window occurrence completes or is cancelled.
     */
    val droppedCount: Int,
    val workspaceGeneration: String,
)

/** Read-only projection capabilities. Keeping this seam separate prevents query consumers from
 * accidentally depending on mutation authority. */
interface StoreReadPort {
    fun queryMemos(
        query: StoreMemoQuery,
        cursor: StorePageCursor?,
        pageSize: Int,
        startMemoId: String? = null,
        backward: Boolean = false,
    ): StoreMemoPage

    fun getMemo(memoId: String): StoreMemoSnapshot?

    /** Counts the exact query predicate without transferring page rows. */
    fun queryCount(query: StoreMemoQuery): Long

    /** Reads compact materialized statistics rows without loading memo bodies. */
    fun memoStatisticsRows(): List<StoreMemoStatisticsRow>

    fun sidebarProjection(): StoreSidebarProjection

    /** Attachment paths still referenced by durable history revision bodies. */
    fun listHistoryAttachmentRefs(): List<StoreHistoryAttachmentRef>

    fun listMemoHistory(memoId: String, cursor: String?, limit: Int): StoreMemoHistoryPage

    /** Builds the bounded next-trigger/catch-up plan from Rust-owned reminder semantics. */
    fun queryReminderPlan(nowUtcMs: Long): StoreReminderPlan
}

/** Durable mutation capabilities owned by the single Rust store writer. */
interface StoreWritePort {
    /**
     * Applies one memo command.
     *
     * [onPublication] receives projection publications the engine emits while the command is still
     * executing — currently the pending-create publication a SAF create publishes before its
     * durable platform I/O. [PublishingStorePort] observes those stamps; command callers must not
     * publish them onto the invalidation bus. The command's own commit is still the returned value.
     */
    fun applyMemoCommand(
        command: StoreMemoCommand,
        onPublication: (StoreMemoCommit) -> Unit,
    ): StoreMemoCommit

    /** Permanently deletes a bounded trash batch and publishes one projection revision. */
    fun permanentDeleteMany(
        operationId: String,
        targets: List<StoreMemoDeleteTarget>,
    ): StoreMemoBatchCommit

    /** Converges Rust workspace-command facts without rewriting the already-committed document. */
    fun commitDocumentMutation(
        mutation: com.lomo.domain.model.MemoDocumentMutation,
    ): StoreMemoCommit

    fun startRebuild(batchSize: Int): StoreRebuildResult

    /**
     * Writes one durable app-private snooze binding scoped to the workspace generation. The caller
     * supplies a validated duration; the deadline instant is computed on the owner clock.
     */
    fun snoozeReminder(
        opaqueId: String,
        snoozeDurationMs: Long,
    )

    /** Clears the durable snooze binding for one reminder definition. */
    fun clearReminderSnooze(opaqueId: String)

    /** True when durable snooze state is quarantined and scheduling is paused pending recovery. */
    fun reminderSnoozeRecoveryPending(): Boolean

    /** Explicitly recovers corrupt durable snooze state (quarantine + fresh store). */
    fun recoverReminderSnooze()
}

/** Complete boundary retained for callers that need both read and write capabilities. */
interface StorePort : StoreReadPort, StoreWritePort
