package com.lomo.data.engine.store

private const val MAX_REMINDER_ZONE_TRANSITIONS = 32
private const val MAX_REMINDER_SESSIONS = 10_000

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

data class StoreZoneTransition(
    val transitionUtcMs: Long,
    val offsetBeforeSecs: Int,
    val offsetAfterSecs: Int,
)

data class StoreTimeZoneContext(
    val zoneId: String,
    val baseOffsetSecs: Int,
    val transitions: List<StoreZoneTransition>,
) {
    init {
        require(zoneId.isNotBlank()) { "Reminder zone id must be non-blank" }
        require(transitions.zipWithNext().all { (left, right) -> left.transitionUtcMs < right.transitionUtcMs }) {
            "Reminder zone transitions must be strictly ordered"
        }
        require(transitions.size <= MAX_REMINDER_ZONE_TRANSITIONS) {
            "Reminder zone transition list is unbounded"
        }
    }
}

data class StoreReminderSession(
    val opaqueId: String,
    val memoIdentity: String,
    val memoRevision: String,
    val token: String,
    val dueAtLocal: String,
    val repeatCount: Int,
    val firedCount: Int,
    val done: Boolean,
    val intervalMinutes: Int,
    val recurrenceCode: String,
) {
    init {
        require(opaqueId.isNotBlank()) { "Reminder opaque id must be non-blank" }
        require(memoIdentity.isNotBlank()) { "Reminder memo identity must be non-blank" }
        require(memoRevision.isNotBlank()) { "Reminder memo revision must be non-blank" }
        require(token.isNotBlank()) { "Reminder token must be non-blank" }
        require(repeatCount > 0) { "Reminder repeat count must be positive" }
        require(firedCount in 0..repeatCount) { "Reminder fired count is outside repeat count" }
        require(intervalMinutes >= 0) { "Reminder interval must be non-negative" }
    }
}

data class StoreReminderQuery(
    val nowUtcMs: Long,
    val zone: StoreTimeZoneContext,
    val sessions: List<StoreReminderSession>,
    val rollingWindow: Int,
    val workspaceGeneration: Long,
) {
    init {
        require(rollingWindow > 0) { "Reminder rolling window must be positive" }
        require(workspaceGeneration >= 0) { "Reminder workspace generation must be non-negative" }
        require(sessions.size <= MAX_REMINDER_SESSIONS) { "Reminder session list is unbounded" }
    }
}

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
    fun queryReminderPlan(query: StoreReminderQuery): StoreReminderPlan
}

/** Durable mutation capabilities owned by the single Rust store writer. */
interface StoreWritePort {
    /**
     * Applies one memo command.
     *
     * [onPublication] receives projection publications the engine emits while the command is still
     * executing — currently the pending-create publication a SAF create publishes before its
     * durable platform I/O. The caller feeds them to its invalidation bus so the list shows the
     * begun memo immediately; the command's own commit is still the returned value.
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
}

/** Complete boundary retained for callers that need both read and write capabilities. */
interface StorePort : StoreReadPort, StoreWritePort
