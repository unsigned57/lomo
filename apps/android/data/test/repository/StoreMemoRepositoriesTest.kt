package com.lomo.data.repository

/*
 * Behavior Contract:
 * - Unit under test: StoreMemoQueryRepository, StoreMemoMutationRepository,
 *   StoreMemoStatisticsRepository (production cutover owners over StorePort).
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: after Room cutover, list/get/mutate/stats go solely through StorePort; mutations
 *   require write authority, bump invalidation, and sync reminders; queries map store summaries
 *   to domain Memo.
 *
 * Scenarios:
 * - Given store pages with memos, when bounded reads / getMemoById / getMemoCount run, then
 *   domain memos and counts are observed.
 * - Given recent-list rows, when getRecentMemos runs, then each row maps bodyPreview without
 *   reading a memo body.
 * - Given identities across the default sort, when rankInDefaultMainList runs, then it returns
 *   itemsBefore of the identity page and null when the identity is missing or filtered out.
 * - Given a live main-list paging source, when reanchorMainListToIdentity runs, then that source
 *   is invalidated so the next refresh starts at the identity.
 * - Given writable authority, when saveMemo succeeds, then Create is applied, invalidation bumps,
 *   reminder sync runs, and returned Memo matches getMemo.
 * - Given a mid-flight pending create publication, when saveMemo returns the durable commit, then
 *   paging invalidates once and the publication clock advances to the confirming revision.
 * - Given writable authority, when update/delete/pin run, then correct command kinds are applied.
 * - Given a selected historical revision, when restore runs, then its body rather than its ID is
 *   submitted as the replacement content.
 * - Given frozen write authority, when saveMemo is attempted, then it fails closed.
 * - Given session statistics, when getMemoStatistics runs, then counts map from the session DTO
 *   and store memoStatisticsRows is not walked.
 * - Given session history revisions, when listMemoRevisions runs, then items map from the session
 *   page and store listMemoHistory is not walked.
 * - Given a fuzzy search page, when the search repository loads, then session hits hydrate through
 *   getMemo; a discarded epoch invalidates the source.
 * - Given a main-list date range and sort, when a page is loaded, then the typed range and sort
 *   reach StorePort without being dropped.
 * - Given an already-created trash PagingSource, when a delete commit is published, then that
 *   source invalidates and a new source observes the trashed memo.
 * - Given a committed mutation, when it completes, then the diagnostics channel records the
 *   published revision so a write can be told apart from a silent no-op.
 * - Given a rejected mutation, when it fails, then the diagnostics channel records the typed
 *   failure (category/code/retry) instead of only a user-facing sentence.
 * - Given an unreadable active projection, when a trash mutation is attempted, then it fails
 *   closed before asking Rust for a memo body.
 * - Given a manual projection refresh, when SAF discovery scans the workspace, then it does not
 *   hold the user-write lease; Rust rejects a stale publish if a concurrent write advances revision.
 * - Given a matching workspace fingerprint, when refreshMemos runs, then startRebuild still runs
 *   and the syncing flag flips, but Full invalidation is not published.
 * - Given a trashed memo, when restoreMemo runs, then Restore command is applied, invalidation
 *   bumps, and reminders are synced.
 * - Given a trashed memo with reminders, when deletePermanently runs, then PermanentDelete command
 *   is applied, invalidation bumps, orphan media is swept, and reminders are cancelled.
 *
 * Observable outcomes: domain Memo fields, command kinds recorded on fake port, reminder calls,
 * invalidation tick advancement, write-lease admission, and thrown check failures.
 *
 * TDD proof:
 * - Target: ./kotlin test --include-module=data --include-classes='com.lomo.data.repository.StoreMemoRepositoriesTest'
 * - RED: production StoreMemo* repositories had zero host executions (coverage gaming C1).
 * - RED on 2026-08-25: manual refresh entered the workspace write lease before the full SAF scan,
 *   so a local rebuild blocked memo submission until every document had been read.
 * - RED on 2026-09-12: refreshMemos published Full invalidation even when startRebuild reported
 *   rewritten = false.
 *
 * Excludes:
 * - Real BoltFFI and Room dual-stack (deleted).
 *
 * Test Change Justification:
 * - Reason category: memo mutations accept pending media promotes after stage-4 cutover.
 * - Old behavior/assertion being replaced: fake StorePort / command fixtures without promote fields.
 * - Why old assertion is no longer correct: production StoreMemoCommand carries pendingPromotes and
 *   history attachment refs for media lifecycle.
 * - Coverage preserved by: query/mutate/stats, write-authority fail-closed, and invalidation/reminder
 *   side effects remain asserted.
 * - Why this is not fitting the test to the implementation: still locks observable domain Memo and
 *   command-kind outcomes, not media digest algorithms.
 */

import com.lomo.domain.model.MemoCreateAttempt
import com.lomo.domain.model.MemoUpdateAttempt
import com.lomo.domain.model.MemoOperationId
import com.lomo.domain.model.EditableMemoSnapshot
import app.cash.turbine.test
import androidx.paging.PagingSource
import com.lomo.data.engine.SessionNativeBridge
import com.lomo.data.engine.store.StoreMemoCommand
import com.lomo.data.engine.store.StoreMemoCommandKind
import com.lomo.data.engine.store.StoreMemoCommit
import com.lomo.data.engine.store.StoreMemoBatchCommit
import com.lomo.data.engine.store.StoreMemoDeletedMemo
import com.lomo.data.engine.store.StoreMemoDeleteTarget
import com.lomo.data.engine.store.StoreInvalidationScope
import com.lomo.data.engine.store.StoreMemoPage
import com.lomo.data.engine.store.StoreMemoQuery
import com.lomo.data.engine.store.StoreMemoQueryBoundary
import com.lomo.data.engine.store.StoreMemoSnapshot
import com.lomo.data.engine.store.StoreMemoSummary
import com.lomo.data.engine.store.StoreMemoSortField
import com.lomo.data.engine.store.StoreSortDirection
import com.lomo.data.engine.store.StoreHistoryAttachmentRef
import com.lomo.data.engine.store.StorePageCursor
import com.lomo.data.engine.store.StorePort
import com.lomo.data.engine.store.PublishingStorePort
import com.lomo.data.engine.store.StoreRebuildResult
import com.lomo.data.engine.store.StoreReminderPlan
import com.lomo.data.testing.fakes.FakeEngineReadinessRepository
import com.lomo.data.testing.fakes.FakeReminderCoordinator
import com.lomo.domain.model.EngineReadiness
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoContentKind
import com.lomo.domain.model.MemoRevision
import com.lomo.domain.model.MemoRevisionLifecycleState
import com.lomo.domain.model.MemoRevisionOrigin
import com.lomo.domain.model.MemoListFilter
import com.lomo.domain.model.MemoSearchMode
import com.lomo.domain.model.MemoQueryDateRange
import com.lomo.domain.model.MemoQuerySort
import com.lomo.domain.model.MemoQuerySpec
import com.lomo.domain.model.MemoSortOption
import com.lomo.domain.model.MemoTagCount
import com.lomo.data.diagnostics.RingBufferEngineDiagnosticsRecorder
import com.lomo.domain.model.EngineCommandFailure
import com.lomo.domain.model.EngineCommandFailureException
import com.lomo.domain.model.EngineDiagnosticEvent
import com.lomo.domain.model.EngineFailureCategory
import com.lomo.domain.model.EngineRetryDisposition
import com.lomo.domain.repository.WorkspaceMutationLease
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import com.lomo.nativebridge.SessionCivilDate
import com.lomo.nativebridge.SessionCivilTime
import com.lomo.nativebridge.SessionHourCount
import com.lomo.nativebridge.SessionSearchHit
import com.lomo.nativebridge.SessionSearchMode
import com.lomo.nativebridge.SessionSearchOutcome
import com.lomo.nativebridge.SessionSearchPage
import com.lomo.nativebridge.SessionSearchRequest
import com.lomo.nativebridge.SessionStatistics
import com.lomo.nativebridge.SessionStatisticsSnapshot
import com.lomo.nativebridge.SessionTagCount
import com.lomo.nativebridge.StoreMemoHistoryPage
import com.lomo.nativebridge.StoreMemoHistoryRevision
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest

private class RecordingStorePort : StorePort {
    val commands = mutableListOf<StoreMemoCommand>()
    var queryCount = 0
    var queryCountCallCount = 0
    var customQueryCount: Long? = null
    var statisticsCallCount = 0
    var getMemoCallCount = 0
    var emitPendingCreatePublication = false
    var batchDeleteCallCount = 0
    var lastBatchDeleteTargets: List<StoreMemoDeleteTarget> = emptyList()
    var sidebarQueryCount = 0
    var rebuildCount = 0
    var rebuildRewrites = true
    private val memos = linkedMapOf<String, StoreMemoSnapshot>()
    private var nextId = 1
    private var coreRevision = 0L
    private var eventSequence = 0L
    val queries = mutableListOf<StoreMemoQuery>()

    fun seed(snapshot: StoreMemoSnapshot) {
        memos[snapshot.summary.memoId] = snapshot
    }

    override fun queryMemos(
        query: StoreMemoQuery,
        cursor: StorePageCursor?,
        pageSize: Int,
        startMemoId: String?,
        backward: Boolean,
    ): StoreMemoPage {
        queryCount += 1
        queries += query
        val all =
            memos.values
                .map { it.summary }
                .filter { summary ->
                    if (!query.filters.includeTrash && !query.filters.trashOnly && summary.isTrashed) {
                        return@filter false
                    }
                    if (query.filters.trashOnly && !summary.isTrashed) {
                        return@filter false
                    }
                    if (query.filters.hasAttachment == true && !summary.hasAttachment) {
                        return@filter false
                    }
                    if (
                        query.filters.dateFromInclusiveMs?.let { summary.createdAtMs < it } == true
                    ) {
                        return@filter false
                    }
                    if (
                        query.filters.dateUntilExclusiveMs?.let { summary.createdAtMs >= it } == true
                    ) {
                        return@filter false
                    }
                    true
                }.sortedWith(query.summaryComparator())
                .filter { summary ->
                    val boundary = query.boundary ?: return@filter true
                    query.summaryComparator().compare(summary, boundarySummary(boundary)) >= 0
                }
        val identityIndex = startMemoId?.let { identity -> all.indexOfFirst { it.memoId == identity } }
        val start =
            when {
                identityIndex != null && identityIndex >= 0 -> identityIndex
                cursor == null -> 0
                else ->
                    all.indexOfFirst { it.memoId == cursor.encoded }.let { index ->
                        if (index < 0) 0 else index + 1
                    }
            }
        val slice = all.drop(start).take(pageSize.coerceAtLeast(1))
        val next =
            if (start + slice.size < all.size) {
                StorePageCursor(slice.last().memoId)
            } else {
                null
            }
        return StoreMemoPage(
            items = slice,
            nextCursor = next,
            highWaterRevision = all.size.toLong(),
            queryFingerprint = "fp",
            itemsBefore = start.toLong(),
            itemsAfter = (all.size - start - slice.size).coerceAtLeast(0).toLong(),
        )
    }

    override fun getMemo(memoId: String): StoreMemoSnapshot? {
        getMemoCallCount += 1
        return memos[memoId]
    }

    override fun queryCount(query: StoreMemoQuery): Long {
        queryCountCallCount += 1
        return customQueryCount ?: memos.values.count { matchesQuery(it.summary, query) }.toLong()
    }

    override fun memoStatisticsRows(): List<com.lomo.data.engine.store.StoreMemoStatisticsRow> {
        statisticsCallCount += 1
        return memos.values
            .asSequence()
            .map { snapshot ->
                com.lomo.data.engine.store.StoreMemoStatisticsRow(
                    createdAtMs = snapshot.summary.createdAtMs,
                    wordCount = snapshot.body.trim().split(Regex("\\s+")).let { words ->
                        if (snapshot.body.isBlank()) 0L else words.size.toLong()
                    },
                    charCount = snapshot.body.length.toLong(),
                )
            }.toList()
    }

    override fun sidebarProjection(): com.lomo.data.engine.store.StoreSidebarProjection {
        sidebarQueryCount += 1
        val active = memos.values.map { it.summary }.filterNot { it.isTrashed }
        return com.lomo.data.engine.store.StoreSidebarProjection(
            schemaVersion = 1u,
            memoCount = active.size,
            dateCounts =
                active
                    .groupingBy { summary ->
                        java.time.Instant
                            .ofEpochMilli(summary.createdAtMs)
                            .atZone(ZoneId.systemDefault())
                            .toLocalDate()
                            .toString()
                    }.eachCount()
                    .map { (date, count) -> com.lomo.data.engine.store.StoreSidebarDateCount(date, count) },
            tagCounts =
                active
                    .flatMap { it.tags }
                    .groupingBy { it }
                    .eachCount()
                    .entries
                    .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                    .map { (name, count) -> com.lomo.data.engine.store.StoreSidebarTagCount(name, count) },
        )
    }

    private fun matchesQuery(
        summary: com.lomo.data.engine.store.StoreMemoSummary,
        query: StoreMemoQuery,
    ): Boolean {
        if (!query.filters.includeTrash && !query.filters.trashOnly && summary.isTrashed) return false
        if (query.filters.trashOnly && !summary.isTrashed) return false
        if (query.filters.hasAttachment != null && summary.hasAttachment != query.filters.hasAttachment) return false
        if (query.filters.hasTodo != null && summary.hasTodo != query.filters.hasTodo) return false
        if (query.filters.hasUrl != null && summary.hasUrl != query.filters.hasUrl) return false
        if (query.filters.pinnedOnly && !summary.isPinned) return false
        if (query.filters.dateFromInclusiveMs?.let { summary.createdAtMs < it } == true) return false
        if (query.filters.dateUntilExclusiveMs?.let { summary.createdAtMs >= it } == true) return false
        if (query.searchText?.isNotBlank() == true && !summary.bodyPreview.contains(query.searchText, ignoreCase = true)) return false
        return true
    }

    override fun listHistoryAttachmentRefs(): List<StoreHistoryAttachmentRef> = emptyList()

    val historyCalls = mutableListOf<Triple<String, String?, Int>>()

    override fun listMemoHistory(
        memoId: String,
        cursor: String?,
        limit: Int,
    ): com.lomo.data.engine.store.StoreMemoHistoryPage {
        historyCalls += Triple(memoId, cursor, limit)
        return com.lomo.data.engine.store.StoreMemoHistoryPage(emptyList(), null)
    }

    override fun queryReminderPlan(nowUtcMs: Long): StoreReminderPlan =
        StoreReminderPlan(emptyList(), 0, "gen-test")

    /** When set, the store refuses the next command exactly as a converted engine rejection does. */
    var rejection: EngineCommandFailureException? = null

    override fun applyMemoCommand(
        command: StoreMemoCommand,
        onPublication: (StoreMemoCommit) -> Unit,
    ): StoreMemoCommit {
        commands += command
        rejection?.let { throw it }
        return when (command.kind) {
            StoreMemoCommandKind.Create -> {
                val id = "m-${nextId++}"
                val summary =
                    StoreMemoSummary(
                        memoId = id,
                        sourcePath = "memos/2026_07_21.md",
                        fileFingerprint = "ff-$id",
                        updatedAtMs = 2_000L,
                        createdAtMs = 1_000L,
                        hasTodo = false,
                        hasUrl = false,
                        hasAttachment = false,
                        isPinned = false,
                        isTrashed = false,
                        bodyPreview = command.content.orEmpty().take(80),
                        contentRevision = 1L,
                    )
                memos[id] = StoreMemoSnapshot(summary = summary, body = command.content.orEmpty())
                val pending = commitOf(command, memos.getValue(id))
                if (emitPendingCreatePublication) {
                    onPublication(pending)
                    commitOf(command, memos.getValue(id))
                } else {
                    pending
                }
            }
            StoreMemoCommandKind.Update -> {
                val existing = memos[command.memoId] ?: error("missing")
                val body = command.content.orEmpty()
                val updated =
                    existing.copy(
                        body = body,
                        summary =
                            existing.summary.copy(
                                bodyPreview = body.take(80),
                                contentRevision = existing.summary.contentRevision + 1,
                                fileFingerprint = "ff-upd",
                                updatedAtMs = existing.summary.updatedAtMs + 1,
                            ),
                    )
                memos[command.memoId] = updated
                commitOf(command, updated)
            }
            StoreMemoCommandKind.Delete -> {
                val existing = memos[command.memoId] ?: error("missing")
                val updated =
                    existing.copy(
                        summary =
                            existing.summary.copy(
                                isTrashed = true,
                                contentRevision = existing.summary.contentRevision + 1,
                            ),
                    )
                memos[command.memoId] = updated
                commitOf(command, updated)
            }
            StoreMemoCommandKind.PermanentDelete -> {
                val existing = memos[command.memoId] ?: error("missing")
                if (command.expectedFingerprint != null && command.expectedFingerprint != existing.summary.fileFingerprint) {
                    throw EngineCommandFailureException(
                        EngineCommandFailure(
                            category = EngineFailureCategory.CONFLICT,
                            code = "stale_snapshot",
                            retryDisposition = EngineRetryDisposition.AFTER_USER_ACTION,
                            operationId = command.operationId,
                            jobId = null,
                            diagnostic = "File fingerprint changed before permanent delete",
                        ),
                    )
                }
                memos.remove(command.memoId)
                val newFingerprint = "ff-after-${command.memoId}"
                memos.replaceAll { _, memoSnapshot ->
                    if (memoSnapshot.summary.sourcePath == existing.summary.sourcePath) {
                        memoSnapshot.copy(summary = memoSnapshot.summary.copy(fileFingerprint = newFingerprint))
                    } else {
                        memoSnapshot
                    }
                }
                commitOf(command, existing, fileFingerprint = "")
            }
            StoreMemoCommandKind.Pin, StoreMemoCommandKind.Unpin -> {
                val existing = memos[command.memoId] ?: error("missing")
                val updated =
                    existing.copy(
                        summary =
                            existing.summary.copy(
                                isPinned = command.kind == StoreMemoCommandKind.Pin || command.pin == true,
                                contentRevision = existing.summary.contentRevision + 1,
                            ),
                    )
                memos[command.memoId] = updated
                commitOf(command, updated)
            }
            StoreMemoCommandKind.Restore, StoreMemoCommandKind.HistoryRestore -> {
                val existing = memos[command.memoId] ?: error("missing")
                val updated =
                    existing.copy(
                        summary =
                            existing.summary.copy(
                                isTrashed = false,
                                contentRevision = existing.summary.contentRevision + 1,
                            ),
                    )
                memos[command.memoId] = updated
                commitOf(command, updated)
            }
        }
    }

    override fun permanentDeleteMany(
        operationId: String,
        targets: List<StoreMemoDeleteTarget>,
    ): StoreMemoBatchCommit {
        batchDeleteCallCount += 1
        lastBatchDeleteTargets = targets
        require(targets.isNotEmpty())
        val deleted =
            targets.map { target ->
                val existing = memos[target.memoId] ?: error("missing")
                require(existing.summary.isTrashed) { "target is not trashed" }
                require(existing.summary.sourcePath == target.sourcePath)
                require(existing.summary.contentRevision == target.expectedRevision)
                require(existing.summary.fileFingerprint == target.expectedFingerprint)
                memos.remove(target.memoId)
                StoreMemoDeletedMemo(
                    memoId = target.memoId,
                    reminderIds = existing.summary.reminders.map { it.reference.opaqueId },
                )
            }
        coreRevision += 1L
        eventSequence += 1L
        return StoreMemoBatchCommit(
            operationId = operationId,
            deleted = deleted,
            coreRevision = coreRevision,
            eventSequence = eventSequence,
            scopes =
                listOf(
                    StoreInvalidationScope.MemoList,
                    StoreInvalidationScope.Trash,
                    StoreInvalidationScope.Search,
                    StoreInvalidationScope.Stats,
                ),
            idempotentReplay = false,
        )
    }

    override fun commitDocumentMutation(
        mutation: com.lomo.domain.model.MemoDocumentMutation,
    ): StoreMemoCommit {
        val existing = memos[mutation.facts.memoId] ?: error("missing")
        val facts = mutation.facts
        val updated =
            existing.copy(
                body = facts.content,
                summary =
                    existing.summary.copy(
                        sourcePath = facts.sourcePath,
                        fileFingerprint = facts.fileFingerprint,
                        bodyPreview = facts.content.take(80),
                        hasTodo = facts.hasTodo,
                        hasUrl = facts.hasUrl,
                        hasAttachment = facts.attachmentPaths.isNotEmpty(),
                        tags = facts.tags,
                        reminders = facts.reminders,
                        contentRevision = existing.summary.contentRevision + 1,
                    ),
            )
        memos[facts.memoId] = updated
        return commitOf(
            StoreMemoCommand(
                operationId = mutation.operationId,
                kind = StoreMemoCommandKind.Update,
                memoId = facts.memoId,
                expectedRevision = mutation.expectedRevision,
                expectedFingerprint = mutation.expectedFingerprint,
                content = facts.content,
            ),
            updated,
        )
    }

    override fun startRebuild(batchSize: Int): StoreRebuildResult {
        rebuildCount++
        if (rebuildRewrites) {
            coreRevision += 1L
            eventSequence += 1L
        }
        val digest = "digest-${memos.size}"
        return StoreRebuildResult(
            memosIndexed = memos.size.toLong(),
            fileCount = memos.size.toLong(),
            attachmentCount = memos.values.count { it.summary.hasAttachment }.toLong(),
            workspaceDigest = digest,
            storeDigest = digest,
            corruptLomoIsolated = 0L,
            highWaterRevision = coreRevision,
            rewritten = rebuildRewrites,
        )
    }

    override fun snoozeReminder(
        opaqueId: String,
        snoozeDurationMs: Long,
    ) = error("reminder snooze is not expected")

    override fun clearReminderSnooze(opaqueId: String) =
        error("reminder clear-snooze is not expected")

    override fun reminderSnoozeRecoveryPending(): Boolean =
        error("reminder snooze recovery query is not expected")

    override fun recoverReminderSnooze() = error("reminder snooze recovery is not expected")

    private fun commitOf(
        command: StoreMemoCommand,
        snap: StoreMemoSnapshot,
        fileFingerprint: String = snap.summary.fileFingerprint,
    ): StoreMemoCommit {
        coreRevision += 1L
        eventSequence += 1L
        return StoreMemoCommit(
            operationId = command.operationId,
            memoId = snap.summary.memoId,
            coreRevision = coreRevision,
            eventSequence = eventSequence,
            contentRevision = snap.summary.contentRevision,
            fileFingerprint = fileFingerprint,
            scopes = command.kind.invalidationScopes(),
            idempotentReplay = false,
        )
    }

    private fun StoreMemoCommandKind.invalidationScopes(): List<StoreInvalidationScope> =
        when (this) {
            StoreMemoCommandKind.Create,
            StoreMemoCommandKind.Update,
            StoreMemoCommandKind.HistoryRestore,
            -> listOf(
                StoreInvalidationScope.MemoList,
                StoreInvalidationScope.Search,
                StoreInvalidationScope.Tags,
                StoreInvalidationScope.Stats,
            )
            StoreMemoCommandKind.Delete,
            StoreMemoCommandKind.PermanentDelete,
            StoreMemoCommandKind.Restore,
            -> listOf(
                StoreInvalidationScope.MemoList,
                StoreInvalidationScope.Trash,
                StoreInvalidationScope.Search,
                StoreInvalidationScope.Stats,
            )
            StoreMemoCommandKind.Pin,
            StoreMemoCommandKind.Unpin,
            -> listOf(
                StoreInvalidationScope.MemoList,
                StoreInvalidationScope.Pin,
                StoreInvalidationScope.Stats,
            )
        }

    private fun StoreMemoQuery.summaryComparator(): Comparator<StoreMemoSummary> {
        val temporal =
            Comparator<StoreMemoSummary> { left, right ->
                val leftPrimary =
                    when (sort.field) {
                        StoreMemoSortField.CreatedAt -> left.createdAtMs
                        StoreMemoSortField.UpdatedAt -> left.updatedAtMs
                    }
                val rightPrimary =
                    when (sort.field) {
                        StoreMemoSortField.CreatedAt -> right.createdAtMs
                        StoreMemoSortField.UpdatedAt -> right.updatedAtMs
                    }
                val primary = leftPrimary.compareTo(rightPrimary)
                val created = left.createdAtMs.compareTo(right.createdAtMs)
                val id = left.memoId.compareTo(right.memoId)
                val ascending = primary.takeIf { it != 0 } ?: created.takeIf { it != 0 } ?: id
                if (sort.direction == StoreSortDirection.Ascending) ascending else -ascending
            }
        return compareByDescending<StoreMemoSummary> { it.isPinned }.then(temporal)
    }

    private fun boundarySummary(boundary: StoreMemoQueryBoundary): StoreMemoSummary =
        StoreMemoSummary(
            memoId = boundary.memoId,
            sourcePath = "boundary",
            fileFingerprint = "boundary",
            updatedAtMs = boundary.primarySortMs,
            createdAtMs = boundary.createdAtMs,
            hasTodo = false,
            hasUrl = false,
            hasAttachment = false,
            isPinned = boundary.isPinned,
            isTrashed = false,
            bodyPreview = "",
            contentRevision = 1L,
        )
}

private class RecordingSessionBridge : SessionNativeBridge {
    val searchRequests = mutableListOf<SessionSearchRequest>()
    var searchOutcome: SessionSearchOutcome =
        SessionSearchOutcome.Ready(
            SessionSearchPage(
                queryEpoch = 1uL,
                mode = SessionSearchMode.FUZZY,
                items = emptyList(),
                nextCursor = null,
            ),
        )
    var lastStatisticsSnapshot: SessionStatisticsSnapshot? = null
    var statisticsCallCount = 0
    var statistics: SessionStatistics = sessionStatistics()
    val historyCalls = mutableListOf<Triple<String, String?, UInt>>()
    var historyPage: StoreMemoHistoryPage =
        StoreMemoHistoryPage(items = emptyList(), nextCursor = null)

    override fun sessionSearch(request: SessionSearchRequest): SessionSearchOutcome {
        searchRequests += request
        return searchOutcome
    }

    override fun sessionStatistics(snapshot: SessionStatisticsSnapshot): SessionStatistics {
        statisticsCallCount += 1
        lastStatisticsSnapshot = snapshot
        return statistics
    }

    override fun sessionListHistory(
        memoId: String,
        cursor: String?,
        limit: UInt,
    ): StoreMemoHistoryPage {
        historyCalls += Triple(memoId, cursor, limit)
        return historyPage
    }
}

private fun sessionStatistics(
    asOf: SessionCivilDate = SessionCivilDate(year = 2024, month = 7u.toUByte(), day = 3u.toUByte()),
    totalMemos: ULong = 0uL,
    tagCounts: List<SessionTagCount> = emptyList(),
): SessionStatistics =
    SessionStatistics(
        asOf = asOf,
        totalMemos = totalMemos,
        totalWords = 0uL,
        totalCharacters = 0uL,
        averageWordsPerMemo = 0.0,
        totalTags = tagCounts.size.toULong(),
        activeDays = 0uL,
        currentStreak = 0uL,
        longestStreak = 0uL,
        memoCountByDate = emptyList(),
        hourlyDistribution = emptyList(),
        weeklyHourDistribution = emptyList(),
        earliestDailyMemoTime = null,
        latestDailyMemoTime = null,
        thisWeekCount = 0uL,
        lastWeekCount = 0uL,
        thisMonthCount = 0uL,
        lastMonthCount = 0uL,
        thisYearCount = 0uL,
        lastYearCount = 0uL,
        tagCounts = tagCounts,
    )

private fun seededSnapshot(
    id: String,
    body: String,
    createdAtMs: Long = 1_000L,
    hasAttachment: Boolean = false,
    tags: List<String> = emptyList(),
    imageUrls: List<String> = emptyList(),
    sourcePath: String = "memos/2026_07_21.md",
    fileFingerprint: String = "ff-$id",
    isTrashed: Boolean = false,
    reminders: List<com.lomo.domain.model.ReminderMarker> = emptyList(),
): StoreMemoSnapshot =
    StoreMemoSnapshot(
        summary =
            StoreMemoSummary(
                memoId = id,
                sourcePath = sourcePath,
                fileFingerprint = fileFingerprint,
                updatedAtMs = createdAtMs + 1,
                createdAtMs = createdAtMs,
                hasTodo = false,
                hasUrl = false,
                hasAttachment = hasAttachment,
                isPinned = false,
                isTrashed = isTrashed,
                bodyPreview = body.take(80),
                contentRevision = 1L,
                tags = tags,
                imageUrls = imageUrls,
                reminders = reminders,
            ),
        body = body,
    )

private fun sampleReminderMarker(
    opaqueId: String,
    memoId: String,
): com.lomo.domain.model.ReminderMarker =
    com.lomo.domain.model.ReminderMarker(
        dueAt = java.time.LocalDateTime.of(2026, 9, 4, 12, 0),
        repeatCount = 1,
        firedCount = 0,
        done = false,
        reference =
            com.lomo.domain.model.ReminderReference(
                opaqueId = opaqueId,
                revision = "1",
                memoIdentity = memoId,
                sourceSpan = com.lomo.domain.model.markdown.MarkdownSourceSpan(0uL, 10uL),
                tokenFingerprint = "fp",
            ),
        token = "⏰",
    )

private fun notReadyWriteLease(): WorkspaceMutationLease =
    ProcessWorkspaceMutationLease(
        engineReadinessRepository =
            FakeEngineReadinessRepository(EngineReadiness.AwaitingWorkspaceSelection),
    )

class StoreMemoRepositoriesTest : FunSpec({
    test("gallery paging source requests bounded store pages") {
        runTest {
            val port = RecordingStorePort()
            repeat(31) { index ->
                port.seed(seededSnapshot("gallery-$index", "body", hasAttachment = true))
            }
            val repo = StoreMemoQueryRepository(port, StoreInvalidationBus(), FakeEngineReadinessRepository())
            val source = repo.getGalleryMemosPagingSource()

            val result = source.load(
                PagingSource.LoadParams.Refresh(
                    key = null,
                    loadSize = 30,
                    placeholdersEnabled = false,
                ),
            )

            result.shouldBeInstanceOf<PagingSource.LoadResult.Page<String, Memo>>()
                .data shouldHaveSize 30
            port.queryCount shouldBe 1
        }
    }

    test("query repository loads bounded memo reads from store pages") {
        runTest {
            val port = RecordingStorePort()
            port.seed(seededSnapshot("a", "alpha", tags = listOf("life")))
            port.seed(
                seededSnapshot(
                    "b",
                    "beta",
                    hasAttachment = true,
                    tags = listOf("work"),
                    imageUrls = listOf("images/b.png"),
                ),
            )
            val invalidation = StoreInvalidationBus()
            val repo = StoreMemoQueryRepository(port, invalidation, FakeEngineReadinessRepository())

            repo.getMemoById("b").shouldNotBeNull().content shouldBe "beta"
            repo.getMemoCount() shouldBe 2
            repo.getRecentMemos(1).shouldHaveSize(1)
        }
    }

    test("getRecentMemos maps list-row previews without reading memo bodies") {
        runTest {
            val port = RecordingStorePort()
            val longBody = "a".repeat(200)
            port.seed(seededSnapshot("recent", longBody))
            val repo = StoreMemoQueryRepository(port, StoreInvalidationBus(), FakeEngineReadinessRepository())

            val recent = repo.getRecentMemos(1)

            recent.single().id shouldBe "recent"
            recent.single().content shouldBe longBody.take(80)
            recent.single().contentKind shouldBe MemoContentKind.Preview
            port.getMemoCallCount shouldBe 0
        }
    }

    test("rank of a default-list identity is itemsBefore of that identity page") {
        runTest {
            val port = RecordingStorePort()
            port.seed(seededSnapshot("old", "old", createdAtMs = 1_000L))
            port.seed(seededSnapshot("mid", "mid", createdAtMs = 2_000L))
            port.seed(seededSnapshot("new", "new", createdAtMs = 3_000L))
            val repo = StoreMemoQueryRepository(port, StoreInvalidationBus(), FakeEngineReadinessRepository())

            repo.rankInDefaultMainList("mid") shouldBe 1
            repo.rankInDefaultMainList("new") shouldBe 0
            repo.rankInDefaultMainList("old") shouldBe 2
        }
    }

    test("missing identity rank is null rather than the head row") {
        runTest {
            val port = RecordingStorePort()
            port.seed(seededSnapshot("head", "head", createdAtMs = 3_000L))
            port.seed(seededSnapshot("tail", "tail", createdAtMs = 1_000L))
            val repo = StoreMemoQueryRepository(port, StoreInvalidationBus(), FakeEngineReadinessRepository())

            repo.rankInDefaultMainList("missing") shouldBe null
        }
    }

    test("reanchor invalidates the live main-list source so refresh starts at the identity") {
        runTest {
            val port = RecordingStorePort()
            port.seed(seededSnapshot("head", "head", createdAtMs = 3_000L))
            port.seed(seededSnapshot("deep", "deep", createdAtMs = 1_000L))
            val repo = StoreMemoQueryRepository(port, StoreInvalidationBus(), FakeEngineReadinessRepository())
            val source = repo.getMainListPagingSource(MemoQuerySpec())
            source.invalid shouldBe false

            repo.reanchorMainListToIdentity("deep")

            source.invalid shouldBe true
        }
    }

    test("getMemoCount queries queryCount with default query on ready engine and enforces Int range") {
        runTest {
            val port = RecordingStorePort()
            port.seed(seededSnapshot("m1", "first"))
            port.seed(seededSnapshot("m2", "second"))
            val readiness = FakeEngineReadinessRepository()
            val repo = StoreMemoQueryRepository(port, StoreInvalidationBus(), readiness)

            // When ready, queries port.queryCount
            repo.getMemoCount() shouldBe 2
            port.queryCountCallCount shouldBe 1
            port.sidebarQueryCount shouldBe 0

            // When unready, returns 0 without querying port
            readiness.publish(EngineReadiness.AwaitingWorkspaceSelection)
            repo.getMemoCount() shouldBe 0
            port.queryCountCallCount shouldBe 1

            // When count overflows Int, fails with illegal argument
            readiness.publish(EngineReadiness.Ready)
            port.customQueryCount = Int.MAX_VALUE.toLong() + 1L
            val overflowError = shouldThrow<IllegalArgumentException> { repo.getMemoCount() }
            overflowError.message shouldBe "memo_count is outside the Kotlin count range"
        }
    }

    test("main list query preserves typed date range and updated ascending sort") {
        runTest {
            val port = RecordingStorePort()
            val start = LocalDate.of(2026, 8, 8)
            val end = LocalDate.of(2026, 8, 9)
            val firstTimestamp = start.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val secondTimestamp = end.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            port.seed(seededSnapshot("newer-created", "second", createdAtMs = secondTimestamp))
            port.seed(seededSnapshot("older-created", "first", createdAtMs = firstTimestamp))
            val repo = StoreMemoQueryRepository(port, StoreInvalidationBus(), FakeEngineReadinessRepository())
            val source =
                repo.getMainListPagingSource(
                    MemoQuerySpec(
                        dateRange = MemoQueryDateRange(startDate = start, endDate = end),
                        sort = MemoQuerySort(MemoSortOption.UPDATED_TIME, ascending = true),
                    ),
                )

            val page =
                source.load(
                    PagingSource.LoadParams.Refresh(
                        key = null,
                        loadSize = 30,
                        placeholdersEnabled = false,
                    ),
                ).shouldBeInstanceOf<PagingSource.LoadResult.Page<Int, Memo>>()

            page.data.map { it.id } shouldContainExactly listOf("older-created", "newer-created")
            val query = port.queries.single()
            query.sort.field shouldBe StoreMemoSortField.UpdatedAt
            query.sort.direction shouldBe StoreSortDirection.Ascending
            query.filters.dateFromInclusiveMs shouldBe
                start.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            query.filters.dateUntilExclusiveMs shouldBe
                end.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
    }

    test("daily review page forwards the captured ordering boundary into the store query") {
        runTest {
            val port = RecordingStorePort()
            port.seed(seededSnapshot("review-head", "head", createdAtMs = 3_000L))
            port.seed(seededSnapshot("review-tail", "tail", createdAtMs = 2_000L))
            val repo = StoreMemoQueryRepository(port, StoreInvalidationBus(), FakeEngineReadinessRepository())

            val boundary = repo.getDailyReviewCandidateBoundary().shouldNotBeNull()
            repo.getDailyReviewCandidatePage(boundary, cursor = null, limit = 10)

            port.queries.last().boundary shouldBe
                StoreMemoQueryBoundary(
                    isPinned = boundary.isPinned,
                    primarySortMs = boundary.timestamp,
                    createdAtMs = boundary.timestamp,
                    memoId = boundary.id,
                )
        }
    }

    test("mutation repository create update delete pin go through store and reminders") {
        runTest {
            val port = RecordingStorePort()
            val invalidation = StoreInvalidationBus()
            val reminders = FakeReminderCoordinator()
            val query = StoreMemoQueryRepository(port, invalidation, FakeEngineReadinessRepository())
            val mutation =
                StoreMemoMutationRepository(
                    port = observingPort(port, invalidation),
                    queryRepository = query,
                    reminderScheduler = reminders,
                    writeLease = alwaysWritableWorkspaceMutationLease(),
                    invalidation = invalidation,
                    diagnostics = RingBufferEngineDiagnosticsRecorder(),
                    pendingStages = com.lomo.data.engine.media.PendingMediaStageRegistry(NoOpMediaPort(), { "/media" }),
                )

            val created = mutation.saveMemo(MemoCreateAttempt(MemoOperationId("create-test"), com.lomo.domain.model.DraftId("draft-test"), "new memo", 1L))
            created.content shouldBe "new memo"
            port.commands.last().kind shouldBe StoreMemoCommandKind.Create
            reminders.syncForMemoCalls.map { it.first } shouldContainExactly listOf(created.id)

            mutation.updateMemo(MemoUpdateAttempt(MemoOperationId("update-test"), com.lomo.domain.model.DraftId("draft-test"), EditableMemoSnapshot.fromFullSnapshot(created), "edited"))
            port.commands.last().kind shouldBe StoreMemoCommandKind.Update
            query.getMemoById(created.id)?.content shouldBe "edited"

            mutation.setMemoPinned(created.id, pinned = true, operationId = MemoOperationId("pin-1"))
            port.commands.last().kind shouldBe StoreMemoCommandKind.Pin
            query.getMemoById(created.id)?.isPinned shouldBe true

            mutation.deleteMemo(
                query.getMemoById(created.id).shouldNotBeNull(),
                MemoOperationId("delete-1"),
            )
            port.commands.last().kind shouldBe StoreMemoCommandKind.Delete
            reminders.cancelForMemoCalls shouldContainExactly listOf(created.id to emptySet())

            mutation.refreshMemos()
            port.rebuildCount shouldBe 1
        }
    }

    test("saveMemo without a mid-flight publication invalidates paging once") {
        runTest {
            val port = RecordingStorePort()
            val invalidation = StoreInvalidationBus()
            val query = StoreMemoQueryRepository(port, invalidation, FakeEngineReadinessRepository())
            val mutation =
                StoreMemoMutationRepository(
                    port = observingPort(port, invalidation),
                    queryRepository = query,
                    reminderScheduler = FakeReminderCoordinator(),
                    writeLease = alwaysWritableWorkspaceMutationLease(),
                    invalidation = invalidation,
                    diagnostics = RingBufferEngineDiagnosticsRecorder(),
                    pendingStages = com.lomo.data.engine.media.PendingMediaStageRegistry(NoOpMediaPort(), { "/media" }),
                )
            val paging = query.getMainListPagingSource(MemoQuerySpec.fromFilter("", MemoListFilter()))

            mutation.saveMemo(MemoCreateAttempt(MemoOperationId("create-test"), com.lomo.domain.model.DraftId("draft-test"), "new memo", 1L))

            paging.invalid shouldBe true
            invalidation.publications.value.coreRevision shouldBe 1
        }
    }

    test("saveMemo confirming a pending create advances the clock without a second paging invalidate") {
        runTest {
            val port = RecordingStorePort().apply { emitPendingCreatePublication = true }
            val invalidation = StoreInvalidationBus()
            val query = StoreMemoQueryRepository(port, invalidation, FakeEngineReadinessRepository())
            val mutation =
                StoreMemoMutationRepository(
                    port = observingPort(port, invalidation),
                    queryRepository = query,
                    reminderScheduler = FakeReminderCoordinator(),
                    writeLease = alwaysWritableWorkspaceMutationLease(),
                    invalidation = invalidation,
                    diagnostics = RingBufferEngineDiagnosticsRecorder(),
                    pendingStages = com.lomo.data.engine.media.PendingMediaStageRegistry(NoOpMediaPort(), { "/media" }),
                )
            val pendingSource = query.getMainListPagingSource(MemoQuerySpec.fromFilter("", MemoListFilter()))

            mutation.saveMemo(MemoCreateAttempt(MemoOperationId("create-test"), com.lomo.domain.model.DraftId("draft-test"), "new memo", 1L))
            val afterConfirm = query.getMainListPagingSource(MemoQuerySpec.fromFilter("", MemoListFilter()))

            pendingSource.invalid shouldBe true
            afterConfirm.invalid shouldBe false
            invalidation.publications.value.coreRevision shouldBe 2
        }
    }

    test("update uses the edit session baseline for CAS and does not reread the memo") {
        runTest {
            val port = RecordingStorePort()
            port.seed(seededSnapshot("edit-baseline", "full body"))
            val invalidation = StoreInvalidationBus()
            val query = StoreMemoQueryRepository(port, invalidation, FakeEngineReadinessRepository())
            val mutation =
                StoreMemoMutationRepository(
                    port = observingPort(port, invalidation),
                    queryRepository = query,
                    reminderScheduler = FakeReminderCoordinator(),
                    writeLease = alwaysWritableWorkspaceMutationLease(),
                    invalidation = invalidation,
                    diagnostics = RingBufferEngineDiagnosticsRecorder(),
                    pendingStages = com.lomo.data.engine.media.PendingMediaStageRegistry(NoOpMediaPort(), { "/media" }),
                )
            val editSessionMemo = query.getMemoById("edit-baseline").shouldNotBeNull()
            val readsBeforeUpdate = port.getMemoCallCount

            mutation.updateMemo(MemoUpdateAttempt(MemoOperationId("update-test"), com.lomo.domain.model.DraftId("draft-test"), EditableMemoSnapshot.fromFullSnapshot(editSessionMemo), "changed"))

            port.getMemoCallCount shouldBe readsBeforeUpdate
            port.commands.last().expectedRevision shouldBe editSessionMemo.contentRevision
            port.commands.last().expectedFingerprint shouldBe editSessionMemo.fileFingerprint
            port.commands.last().chronologyEpochMs shouldBe null
        }
    }

    test("history restore submits the selected revision body rather than its identity") {
        runTest {
            val port = RecordingStorePort()
            port.seed(seededSnapshot("history-target", "current body"))
            val invalidation = StoreInvalidationBus()
            val query = StoreMemoQueryRepository(port, invalidation, FakeEngineReadinessRepository())
            val mutation =
                StoreMemoMutationRepository(
                    port = observingPort(port, invalidation),
                    queryRepository = query,
                    reminderScheduler = FakeReminderCoordinator(),
                    writeLease = alwaysWritableWorkspaceMutationLease(),
                    invalidation = invalidation,
                    diagnostics = RingBufferEngineDiagnosticsRecorder(),
                    pendingStages = com.lomo.data.engine.media.PendingMediaStageRegistry(NoOpMediaPort(), { "/media" }),
                )
            val current = query.getMemoById("history-target").shouldNotBeNull()
            val selected =
                MemoRevision(
                    revisionId = "history-target-r1",
                    parentRevisionId = null,
                    memoId = "history-target",
                    commitId = "history-target-r1",
                    batchId = null,
                    createdAt = 1L,
                    origin = MemoRevisionOrigin.LOCAL_CREATE,
                    summary = "historical body",
                    lifecycleState = MemoRevisionLifecycleState.ACTIVE,
                    memoContent = "historical body",
                    isCurrent = false,
                )

            mutation.restoreMemoRevision(current, selected, MemoOperationId("history-restore-1"))

            port.commands.last().kind shouldBe StoreMemoCommandKind.HistoryRestore
            port.commands.last().content shouldBe "historical body"
            port.commands.last().content shouldBe selected.memoContent
            port.commands.last().historyRevision shouldBe 1L
            port.commands.last().chronologyEpochMs shouldBe null
        }
    }

    test("manual projection refresh does not hold the workspace write lease while scanning") {
        runTest {
            val port = RecordingStorePort()
            val invalidation = StoreInvalidationBus()
            val query = StoreMemoQueryRepository(port, invalidation, FakeEngineReadinessRepository())
            val rejectingWriteLease =
                object : WorkspaceMutationLease {
                    override val authority = kotlinx.coroutines.flow.flowOf(null)

                    override fun isWritable(): Boolean = false

                    override fun isWritableFlow() = kotlinx.coroutines.flow.flowOf(false)

                    override suspend fun <T> withWrite(
                        block: suspend (com.lomo.domain.model.WorkspaceAuthority) -> T,
                    ): T = error("refresh must not enter the workspace write lease")

                    override suspend fun <T : Any> withWriteOrNull(
                        block: suspend (com.lomo.domain.model.WorkspaceAuthority) -> T,
                    ): T? = error("refresh must not enter the workspace write lease")

                    override suspend fun <T> withExclusiveTransition(block: suspend () -> T): T =
                        error("refresh must not enter the workspace transition lease")
                }
            val mutation =
                StoreMemoMutationRepository(
                    port = observingPort(port, invalidation),
                    queryRepository = query,
                    reminderScheduler = FakeReminderCoordinator(),
                    writeLease = rejectingWriteLease,
                    invalidation = invalidation,
                    diagnostics = RingBufferEngineDiagnosticsRecorder(),
                    pendingStages = com.lomo.data.engine.media.PendingMediaStageRegistry(NoOpMediaPort(), { "/media" }),
                )

            mutation.refreshMemos()

            port.rebuildCount shouldBe 1
        }
    }

    test("matching fingerprints skip Full invalidation while still running rebuild") {
        runTest {
            val port = RecordingStorePort().apply { rebuildRewrites = false }
            val invalidation = StoreInvalidationBus()
            val query = StoreMemoQueryRepository(port, invalidation, FakeEngineReadinessRepository())
            val mutation =
                StoreMemoMutationRepository(
                    port = observingPort(port, invalidation),
                    queryRepository = query,
                    reminderScheduler = FakeReminderCoordinator(),
                    writeLease = alwaysWritableWorkspaceMutationLease(),
                    invalidation = invalidation,
                    diagnostics = RingBufferEngineDiagnosticsRecorder(),
                    pendingStages = com.lomo.data.engine.media.PendingMediaStageRegistry(NoOpMediaPort(), { "/media" }),
                )

            mutation.refreshMemos()

            port.rebuildCount shouldBe 1
            invalidation.publications.value.coreRevision shouldBe 0L
        }
    }

    test("a refused write admission is recorded as a typed rejection instead of vanishing") {
        runTest {
            val port = RecordingStorePort()
            val invalidation = StoreInvalidationBus()
            val query = StoreMemoQueryRepository(port, invalidation, FakeEngineReadinessRepository())
            val diagnostics = RingBufferEngineDiagnosticsRecorder()
            val refusingLease =
                object : WorkspaceMutationLease {
                    override val authority = kotlinx.coroutines.flow.flowOf(null)

                    override fun isWritable(): Boolean = false

                    override fun isWritableFlow() = kotlinx.coroutines.flow.flowOf(false)

                    override suspend fun <T> withWrite(
                        block: suspend (com.lomo.domain.model.WorkspaceAuthority) -> T,
                    ): T = throw IllegalStateException("Workspace projection is not verified")

                    override suspend fun <T : Any> withWriteOrNull(
                        block: suspend (com.lomo.domain.model.WorkspaceAuthority) -> T,
                    ): T? = null

                    override suspend fun <T> withExclusiveTransition(block: suspend () -> T): T = block()
                }
            val mutation =
                StoreMemoMutationRepository(
                    port = observingPort(port, invalidation),
                    queryRepository = query,
                    reminderScheduler = FakeReminderCoordinator(),
                    writeLease = refusingLease,
                    invalidation = invalidation,
                    diagnostics = diagnostics,
                    pendingStages = com.lomo.data.engine.media.PendingMediaStageRegistry(NoOpMediaPort(), { "/media" }),
                )

            runCatching { mutation.saveMemo(MemoCreateAttempt(MemoOperationId("create-test"), com.lomo.domain.model.DraftId("draft-test"), "blocked", 1L)) }
                .isFailure shouldBe true

            val rejection =
                diagnostics.events.value.filterIsInstance<EngineDiagnosticEvent.Rejected>().single()
            rejection.label shouldBe "memo.create"
            rejection.failure.code shouldBe "write_admission_refused"
        }
    }

    test("mutation diagnostics record the published revision and the typed rejection") {
        runTest {
            val port = RecordingStorePort()
            val invalidation = StoreInvalidationBus()
            val diagnostics = RingBufferEngineDiagnosticsRecorder()
            val query = StoreMemoQueryRepository(port, invalidation, FakeEngineReadinessRepository())
            val mutation =
                StoreMemoMutationRepository(
                    port = observingPort(port, invalidation),
                    queryRepository = query,
                    reminderScheduler = FakeReminderCoordinator(),
                    writeLease = alwaysWritableWorkspaceMutationLease(),
                    invalidation = invalidation,
                    diagnostics = diagnostics,
                    pendingStages = com.lomo.data.engine.media.PendingMediaStageRegistry(NoOpMediaPort(), { "/media" }),
                )

            val created = mutation.saveMemo(MemoCreateAttempt(MemoOperationId("create-test"), com.lomo.domain.model.DraftId("draft-test"), "new memo", 1L))

            val committed =
                diagnostics.events.value.filterIsInstance<EngineDiagnosticEvent.Committed>()[0]
            committed.label shouldBe "memo.create"
            committed.coreRevision shouldBe invalidation.publications.value.coreRevision

            port.rejection =
                EngineCommandFailureException(
                    EngineCommandFailure(
                        category = EngineFailureCategory.CONFLICT,
                        code = "stale_snapshot",
                        retryDisposition = EngineRetryDisposition.AFTER_USER_ACTION,
                        operationId = null,
                        jobId = null,
                        diagnostic = "memo changed first",
                    ),
                )

            shouldThrow<EngineCommandFailureException> {
                mutation.deleteMemo(created, MemoOperationId("delete-fails"))
            }

            val rejected =
                diagnostics.events.value.filterIsInstance<EngineDiagnosticEvent.Rejected>()[0]
            rejected.label shouldBe "memo.delete"
            rejected.failure.code shouldBe "stale_snapshot"
            rejected.failure.category shouldBe EngineFailureCategory.CONFLICT
            rejected.failure.retryDisposition shouldBe EngineRetryDisposition.AFTER_USER_ACTION
        }
    }

    test("delete commit invalidates an existing trash paging source") {
        runTest {
            val port = RecordingStorePort()
            port.seed(seededSnapshot("trash-me", "body"))
            val invalidation = StoreInvalidationBus()
            val query = StoreMemoQueryRepository(port, invalidation, FakeEngineReadinessRepository())
            val readiness = FakeEngineReadinessRepository()
            val reminders = FakeReminderCoordinator()
            val media = RecordingMediaRepository()
            val trash =
                StoreMemoTrashRepository(
                    observingPort(port, invalidation),
                    alwaysWritableWorkspaceMutationLease(),
                    invalidation,
                    readiness,
                    reminders,
                    media,
                )
            val mutation =
                StoreMemoMutationRepository(
                    port = observingPort(port, invalidation),
                    queryRepository = query,
                    reminderScheduler = FakeReminderCoordinator(),
                    writeLease = alwaysWritableWorkspaceMutationLease(),
                    invalidation = invalidation,
                    diagnostics = RingBufferEngineDiagnosticsRecorder(),
                    pendingStages = com.lomo.data.engine.media.PendingMediaStageRegistry(NoOpMediaPort(), { "/media" }),
                )
            val source = trash.getDeletedMemosPagingSource()
            source.invalid shouldBe false

            mutation.deleteMemo(
                query.getMemoById("trash-me").shouldNotBeNull(),
                MemoOperationId("delete-trash-me"),
            )

            source.invalid shouldBe true
            val refreshed =
                trash.getDeletedMemosPagingSource().load(
                    PagingSource.LoadParams.Refresh(
                        key = null,
                        loadSize = 30,
                        placeholdersEnabled = false,
                    ),
                ).shouldBeInstanceOf<PagingSource.LoadResult.Page<Int, Memo>>()
            refreshed.data.map { it.id } shouldContainExactly listOf("trash-me")
        }
    }

    test("trash mutation fails closed until the active projection is readable") {
        runTest {
            val port = RecordingStorePort()
            val readiness = FakeEngineReadinessRepository()
            val reminders = FakeReminderCoordinator()
            readiness.publishProjectionFreshness(com.lomo.domain.model.ProjectionFreshness.Unavailable)
            val trash =
                StoreMemoTrashRepository(
                    port,
                    alwaysWritableWorkspaceMutationLease(),
                    StoreInvalidationBus(),
                    readiness,
                    reminders,
                    RecordingMediaRepository(),
                )

            val failure =
                shouldThrow<EngineCommandFailureException> {
                    trash.restoreMemo(
                        Memo(
                            id = "trash-me",
                            content = "body",
                            rawContent = "body",
                            dateKey = "1970_01_01",
                            timestamp = 1L,
                        ),
                        MemoOperationId("trash-restore-blocked"),
                    )
                }

            failure.failure.code shouldBe "projection_not_ready"
            port.commands shouldHaveSize 0
        }
    }

    test("clearTrash permanently deletes all trashed memos sharing the same source file without stale_snapshot conflict") {
        runTest {
            val port = RecordingStorePort()
            port.seed(
                seededSnapshot(
                    id = "memo-1",
                    body = "memo 1",
                    sourcePath = "2026-09-02.md",
                    fileFingerprint = "ff-initial",
                    isTrashed = true,
                ),
            )
            port.seed(
                seededSnapshot(
                    id = "memo-2",
                    body = "memo 2",
                    sourcePath = "2026-09-02.md",
                    fileFingerprint = "ff-initial",
                    isTrashed = true,
                ),
            )
            port.seed(
                seededSnapshot(
                    id = "memo-3",
                    body = "memo 3",
                    sourcePath = "2026-09-03.md",
                    fileFingerprint = "ff-initial-3",
                    isTrashed = true,
                ),
            )
            val invalidation = StoreInvalidationBus()
            val reminders = FakeReminderCoordinator()
            val media = RecordingMediaRepository()
            val trash =
                StoreMemoTrashRepository(
                    port = observingPort(port, invalidation),
                    writeLease = alwaysWritableWorkspaceMutationLease(),
                    invalidation = invalidation,
                    readiness = FakeEngineReadinessRepository(),
                    reminderScheduler = reminders,
                    mediaRepository = media,
                )

            trash.clearTrash(MemoOperationId("clear-trash-1"))

            port.getMemo("memo-1") shouldBe null
            port.getMemo("memo-2") shouldBe null
            port.getMemo("memo-3") shouldBe null
            port.commands.filter { it.kind == StoreMemoCommandKind.PermanentDelete } shouldHaveSize 0
            port.batchDeleteCallCount shouldBe 1
            port.lastBatchDeleteTargets.map { it.memoId } shouldContainExactly listOf("memo-1", "memo-2", "memo-3")
            media.orphanSweepCallCount shouldBe 1
        }
    }

    test("restoreMemo applies Restore command, publishes invalidation, and syncs reminders") {
        runTest {
            val port = RecordingStorePort()
            port.seed(
                seededSnapshot(
                    id = "restore-me",
                    body = "body to restore",
                    isTrashed = true,
                ),
            )
            val invalidation = StoreInvalidationBus()
            val reminders = FakeReminderCoordinator()
            val media = RecordingMediaRepository()
            val trash =
                StoreMemoTrashRepository(
                    port = observingPort(port, invalidation),
                    writeLease = alwaysWritableWorkspaceMutationLease(),
                    invalidation = invalidation,
                    readiness = FakeEngineReadinessRepository(),
                    reminderScheduler = reminders,
                    mediaRepository = media,
                )
            val memoToRestore =
                Memo(
                    id = "restore-me",
                    content = "body to restore",
                    rawContent = "body to restore",
                    dateKey = "1970_01_01",
                    timestamp = 1L,
                    contentRevision = 1L,
                    fileFingerprint = "ff-restore-me",
                )

            invalidation.publications.test {
                awaitItem()
                trash.restoreMemo(memoToRestore, MemoOperationId("restore-me"))
                val update = awaitItem()
                update.coreRevision shouldBe 1L
                cancelAndIgnoreRemainingEvents()
            }

            val cmd = port.commands.single { it.memoId == "restore-me" }
            cmd.kind shouldBe StoreMemoCommandKind.Restore
            cmd.expectedRevision shouldBe 1L
            cmd.expectedFingerprint shouldBe "ff-restore-me"
            reminders.syncForMemoCalls.map { it.first } shouldContainExactly listOf("restore-me")
        }
    }

    test("deletePermanently applies PermanentDelete command, sweeps media, publishes invalidation, and cancels reminders") {
        runTest {
            val marker = sampleReminderMarker(opaqueId = "rem-perm-1", memoId = "perm-del")
            val port = RecordingStorePort()
            port.seed(
                seededSnapshot(
                    id = "perm-del",
                    body = "permanent body",
                    isTrashed = true,
                    reminders = listOf(marker),
                ),
            )
            val invalidation = StoreInvalidationBus()
            val reminders = FakeReminderCoordinator()
            val media = RecordingMediaRepository()
            val trash =
                StoreMemoTrashRepository(
                    port = observingPort(port, invalidation),
                    writeLease = alwaysWritableWorkspaceMutationLease(),
                    invalidation = invalidation,
                    readiness = FakeEngineReadinessRepository(),
                    reminderScheduler = reminders,
                    mediaRepository = media,
                )
            val memoToDelete =
                Memo(
                    id = "perm-del",
                    content = "permanent body",
                    rawContent = "permanent body",
                    dateKey = "1970_01_01",
                    timestamp = 1L,
                    contentRevision = 1L,
                    fileFingerprint = "ff-perm-del",
                    reminders = listOf(marker),
                )

            invalidation.publications.test {
                awaitItem()
                trash.deletePermanently(memoToDelete, MemoOperationId("perm-del"))
                val update = awaitItem()
                update.coreRevision shouldBe 1L
                cancelAndIgnoreRemainingEvents()
            }

            val cmd = port.commands.single { it.memoId == "perm-del" }
            cmd.kind shouldBe StoreMemoCommandKind.PermanentDelete
            cmd.expectedRevision shouldBe 1L
            cmd.expectedFingerprint shouldBe "ff-perm-del"
            port.getMemo("perm-del") shouldBe null
            media.orphanSweepCallCount shouldBe 1
            reminders.cancelForMemoCalls shouldContainExactly listOf("perm-del" to setOf("rem-perm-1"))
        }
    }

    test("version repository maps session history without walking the store listing") {
        runTest {
            val port = RecordingStorePort()
            port.seed(seededSnapshot("hist-1", "current body"))
            val memo =
                StoreMemoQueryRepository(
                    port,
                    StoreInvalidationBus(),
                    FakeEngineReadinessRepository(),
                ).getMemoById("hist-1").shouldNotBeNull()
            val session =
                RecordingSessionBridge().apply {
                    historyPage =
                        StoreMemoHistoryPage(
                            items =
                                listOf(
                                    StoreMemoHistoryRevision(
                                        revision = 1uL,
                                        createdAtMs = 1_111L,
                                        content = "first body",
                                        fileFingerprint = "ff-1",
                                    ),
                                    StoreMemoHistoryRevision(
                                        revision = 2uL,
                                        createdAtMs = 2_222L,
                                        content = "second body",
                                        fileFingerprint = "ff-2",
                                    ),
                                ),
                            nextCursor = "3",
                        )
                }
            val repo = StoreMemoVersionRepository(port = port, session = session)

            val page = repo.listMemoRevisions(memo, cursor = null, limit = 20)

            session.historyCalls shouldBe listOf(Triple("hist-1", null, 20u))
            port.historyCalls shouldHaveSize 0
            page.items.map { it.revisionId } shouldBe listOf("hist-1-r1", "hist-1-r2")
            page.items.map { it.memoContent } shouldBe listOf("first body", "second body")
            page.items.map { it.isCurrent } shouldBe listOf(true, false)
            page.items[0].parentRevisionId shouldBe null
            page.items[1].parentRevisionId shouldBe "hist-1-r1"
            page.nextCursor.shouldNotBeNull().revisionId shouldBe "hist-1:cursor:3"
        }
    }

    test("mutation repository fails closed when the workspace lease refuses admission") {
        runTest {
            val port = RecordingStorePort()
            val invalidation = StoreInvalidationBus()
            val mutation =
                StoreMemoMutationRepository(
                    port = observingPort(port, invalidation),
                    queryRepository = StoreMemoQueryRepository(
                        port,
                        invalidation,
                        FakeEngineReadinessRepository(),
                    ),
                    reminderScheduler = FakeReminderCoordinator(),
                    writeLease = notReadyWriteLease(),
                    invalidation = invalidation,
                    diagnostics = RingBufferEngineDiagnosticsRecorder(),
                    pendingStages = com.lomo.data.engine.media.PendingMediaStageRegistry(NoOpMediaPort(), { "/media" }),
                )
            shouldThrow<IllegalStateException> {
                mutation.saveMemo(MemoCreateAttempt(MemoOperationId("create-test"), com.lomo.domain.model.DraftId("draft-test"), "x", 1L))
            }
            port.commands shouldHaveSize 0
        }
    }

    test("statistics repository maps session statistics without walking memo bodies") {
        runTest {
            val port = RecordingStorePort()
            port.seed(
                seededSnapshot(
                    "a",
                    "one two",
                    createdAtMs = 1_720_000_000_000L,
                    tags = listOf("work", "life"),
                ),
            )
            port.seed(
                seededSnapshot(
                    "b",
                    "three",
                    createdAtMs = 1_720_000_100_000L,
                    tags = listOf("work"),
                ),
            )
            val invalidation = StoreInvalidationBus()
            val session =
                RecordingSessionBridge().apply {
                    statistics =
                        sessionStatistics(
                            totalMemos = 2uL,
                            tagCounts =
                                listOf(
                                    SessionTagCount(name = "work", count = 2uL),
                                    SessionTagCount(name = "life", count = 1uL),
                                ),
                        ).copy(
                            hourlyDistribution = listOf(SessionHourCount(hour = 8u.toUByte(), count = 2uL)),
                            earliestDailyMemoTime =
                                SessionCivilTime(
                                    hour = 8u.toUByte(),
                                    minute = 15u.toUByte(),
                                    second = 0u.toUByte(),
                                ),
                        )
                }
            val stats =
                StoreMemoStatisticsRepository(
                    port = port,
                    session = session,
                    invalidation = invalidation,
                    readiness = FakeEngineReadinessRepository(),
                    applicationScope = backgroundScope,
                )

            stats.getMemoCountFlow().test {
                awaitItem() shouldBe 2
                cancelAndIgnoreRemainingEvents()
            }
            val zone = ZoneId.of("UTC")
            val today = LocalDate.of(2024, 7, 3)
            val memoStats = stats.getMemoStatistics(zone = zone, today = today)
            memoStats.totalMemos shouldBe 2
            memoStats.hourlyDistribution shouldBe mapOf(8 to 2)
            memoStats.earliestDailyMemoTime shouldBe LocalTime.of(8, 15)
            memoStats.tagCounts.map { it.name to it.count } shouldContainExactly
                listOf("work" to 2, "life" to 1)
            session.statisticsCallCount shouldBe 1
            session.lastStatisticsSnapshot shouldBe
                SessionStatisticsSnapshot(
                    zone = "UTC",
                    asOf = SessionCivilDate(year = 2024, month = 7u.toUByte(), day = 3u.toUByte()),
                )
            port.statisticsCallCount shouldBe 0
            val tagCounts = stats.getTagCountsFlow().first()
            tagCounts.map { it.name to it.count } shouldContainExactly
                listOf("work" to 2, "life" to 1)
        }
    }

    test("observeMemoStatistics rereads session statistics on each accepted publication") {
        runTest {
            val port = RecordingStorePort()
            port.seed(seededSnapshot("a", "alpha", tags = listOf("life")))
            val invalidation = StoreInvalidationBus()
            val session =
                RecordingSessionBridge().apply {
                    statistics = sessionStatistics(totalMemos = 2uL)
                }
            val stats =
                StoreMemoStatisticsRepository(
                    port = port,
                    session = session,
                    invalidation = invalidation,
                    readiness = FakeEngineReadinessRepository(),
                    applicationScope = backgroundScope,
                )
            val zone = ZoneId.of("UTC")
            val today = LocalDate.of(2024, 7, 3)

            stats.observeMemoStatistics(zone, today).test {
                awaitItem().totalMemos shouldBe 2
                session.statisticsCallCount shouldBe 1
                invalidation.publish(
                    StoreMemoCommit(
                        operationId = "op-stats",
                        memoId = "a",
                        coreRevision = 1L,
                        eventSequence = 1L,
                        contentRevision = 1L,
                        fileFingerprint = "ff-a",
                        scopes = listOf(StoreInvalidationScope.Stats),
                        idempotentReplay = false,
                    ),
                )
                awaitItem().totalMemos shouldBe 2
                session.statisticsCallCount shouldBe 2
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    test("sidebar statistics uses one aggregate projection and never walks memo pages") {
        runTest {
            val port = RecordingStorePort()
            repeat(2_001) { index ->
                port.seed(
                    seededSnapshot(
                        id = "memo-$index",
                        body = "body",
                        createdAtMs = 1_720_000_000_000L,
                        tags = listOf("all"),
                    ),
                )
            }
            val session =
                RecordingSessionBridge().apply {
                    statistics = sessionStatistics(totalMemos = 2_001uL)
                }
            val stats =
                StoreMemoStatisticsRepository(
                    port = port,
                    session = session,
                    invalidation = StoreInvalidationBus(),
                    readiness = FakeEngineReadinessRepository(),
                    applicationScope = backgroundScope,
                )

            val sidebar = stats.getSidebarStatisticsFlow().first()

            sidebar.memoCount shouldBe 2_001
            sidebar.tagCounts shouldBe listOf(MemoTagCount("all", 2_001))
            port.sidebarQueryCount shouldBe 1
            port.queryCount shouldBe 0

            val detailed =
                stats.getMemoStatistics(
                    zone = ZoneId.of("UTC"),
                    today = LocalDate.of(2026, 8, 4),
                )
            detailed.totalMemos shouldBe 2_001
            port.queryCount shouldBe 0
            port.statisticsCallCount shouldBe 0
            session.statisticsCallCount shouldBe 1
        }
    }

    test("given multiple sidebar consumers when they subscribe together then one projection snapshot is shared") {
        runTest {
            val port = RecordingStorePort()
            port.seed(seededSnapshot("shared", "body", tags = listOf("all")))
            val stats =
                StoreMemoStatisticsRepository(
                    port = port,
                    session = RecordingSessionBridge(),
                    invalidation = StoreInvalidationBus(),
                    readiness = FakeEngineReadinessRepository(),
                    applicationScope = backgroundScope,
                )

            coroutineScope {
                val count = async { stats.getMemoCountFlow().first() }
                val tags = async { stats.getTagCountsFlow().first() }
                count.await() shouldBe 1
                tags.await().map { it.name to it.count } shouldBe listOf("all" to 1)
            }

            port.sidebarQueryCount shouldBe 1
        }
    }

    test("statistics repository stays empty until a workspace engine is ready") {
        runTest {
            val port = RecordingStorePort()
            port.seed(seededSnapshot("cold", "must not be queried"))
            val session = RecordingSessionBridge()
            val stats =
                StoreMemoStatisticsRepository(
                    port = port,
                    session = session,
                    invalidation = StoreInvalidationBus(),
                    readiness = FakeEngineReadinessRepository(EngineReadiness.AwaitingWorkspaceSelection),
                    applicationScope = backgroundScope,
                )

            stats.getMemoCountFlow().first() shouldBe 0
            stats.getTagCountsFlow().first() shouldBe emptyList()
            stats.getMemoStatistics(ZoneId.of("UTC"), LocalDate.of(2024, 7, 3)).totalMemos shouldBe 0
            port.queryCount shouldBe 0
            session.statisticsCallCount shouldBe 0
        }
    }

    test("fulltext search paging source uses the store query with filters") {
        runTest {
            val port = RecordingStorePort()
            port.seed(seededSnapshot("todo", "- [ ] alpha task"))
            val session = RecordingSessionBridge()
            val search =
                StoreMemoSearchRepository(
                    port = port,
                    session = session,
                    invalidation = StoreInvalidationBus(),
                )

            val results =
                search
                    .searchPagingSource(
                        query = "alpha",
                        mode = MemoSearchMode.Fulltext,
                        filter = MemoListFilter(hasTodo = true),
                    ).loadPage(loadSize = 10)

            results.map(Memo::id) shouldBe listOf("todo")
            port.queries.single().searchText shouldBe "alpha"
            port.queries.single().filters.hasTodo shouldBe true
            session.searchRequests shouldBe emptyList()
        }
    }

    test("fuzzy search paging source uses complete session summaries without per-hit reads") {
        runTest {
            val port = RecordingStorePort()
            port.seed(seededSnapshot("great-wall", "八达岭长城", sourcePath = "2026_09_10.md"))
            val session =
                RecordingSessionBridge().apply {
                    searchOutcome =
                        SessionSearchOutcome.Ready(
                            SessionSearchPage(
                                queryEpoch = 1uL,
                                mode = SessionSearchMode.FUZZY,
                                items =
                                    listOf(
                                        SessionSearchHit(
                                            score = 42L,
                                            summary = com.lomo.nativebridge.StoreMemoSummary(
                                                memoId = "great-wall", sourcePath = "2026_09_10.md",
                                                fileFingerprint = "f".repeat(64), updatedAtMs = 1L, createdAtMs = 1L,
                                                hasTodo = true, hasUrl = false, hasAttachment = false,
                                                isPinned = false, isTrashed = false, bodyPreview = "八达岭长城",
                                                contentRevision = 1uL, rank = null, tags = emptyList(), imageUrls = emptyList(),
                                                reminders = emptyList(), isPending = false, charCount = 5L,
                                            ),
                                        ),
                                    ),
                                nextCursor = com.lomo.nativebridge.StorePageCursor("next-fuzzy"),
                            ),
                        )
                }
            val search =
                StoreMemoSearchRepository(
                    port = port,
                    session = session,
                    invalidation = StoreInvalidationBus(),
                )

            val loaded =
                search
                    .searchPagingSource(
                        query = "bdlcc",
                        mode = MemoSearchMode.Fuzzy,
                        filter = MemoListFilter(hasTodo = true),
                    ).loadResult(loadSize = 20)

            val page = loaded.shouldBeInstanceOf<PagingSource.LoadResult.Page<String, Memo>>()
            page.data.map(Memo::id) shouldBe listOf("great-wall")
            page.data.single().content shouldBe "八达岭长城"
            page.nextKey shouldBe "next-fuzzy"
            session.searchRequests.single().text shouldBe "bdlcc"
            session.searchRequests.single().mode shouldBe SessionSearchMode.FUZZY
            session.searchRequests.single().queryEpoch shouldBe 1uL
            session.searchRequests.single().filters.hasTodo shouldBe true
            page.data.single().contentKind shouldBe com.lomo.domain.model.MemoContentKind.Preview
            port.getMemoCallCount shouldBe 0
        }
    }

    test("fuzzy search paging source invalidates when the session discards a stale epoch") {
        runTest {
            val session =
                RecordingSessionBridge().apply {
                    searchOutcome =
                        SessionSearchOutcome.Discarded(
                            queryEpoch = 1uL,
                            activeEpoch = 4uL,
                        )
                }
            val search =
                StoreMemoSearchRepository(
                    port = RecordingStorePort(),
                    session = session,
                    invalidation = StoreInvalidationBus(),
                )

            val loaded =
                search
                    .searchPagingSource(
                        query = "bdlcc",
                        mode = MemoSearchMode.Fuzzy,
                        filter = MemoListFilter(),
                    ).loadResult(loadSize = 20)

            loaded.shouldBeInstanceOf<PagingSource.LoadResult.Invalid<String, Memo>>()
        }
    }

    test("given missing edit session baseline when deleteMemo runs then conflict failure is thrown and recorded") {
        runTest {
            val port = RecordingStorePort()
            val invalidation = StoreInvalidationBus()
            val diagnostics = RingBufferEngineDiagnosticsRecorder()
            val query = StoreMemoQueryRepository(port, invalidation, FakeEngineReadinessRepository())
            val mutation =
                StoreMemoMutationRepository(
                    port = observingPort(port, invalidation),
                    queryRepository = query,
                    reminderScheduler = FakeReminderCoordinator(),
                    writeLease = alwaysWritableWorkspaceMutationLease(),
                    invalidation = invalidation,
                    diagnostics = diagnostics,
                    pendingStages = com.lomo.data.engine.media.PendingMediaStageRegistry(NoOpMediaPort(), { "/media" }),
                )

            val ghostMemo =
                Memo(
                    id = "ghost-memo-id",
                    timestamp = 1000L,
                    content = "does not exist",
                    rawContent = "12:00:00 does not exist",
                    dateKey = "2026_08_15",
                )

            val error = shouldThrow<EngineCommandFailureException> {
                mutation.deleteMemo(ghostMemo, MemoOperationId("delete-ghost"))
            }
            error.failure.code shouldBe "edit_baseline_missing"
            error.failure.category shouldBe EngineFailureCategory.CONFLICT

            val rejected =
                diagnostics.events.value.filterIsInstance<EngineDiagnosticEvent.Rejected>()[0]
            rejected.label shouldBe "memo.delete"
            rejected.failure.code shouldBe "edit_baseline_missing"
            rejected.failure.category shouldBe EngineFailureCategory.CONFLICT
        }
    }
})

private fun observingPort(
    port: StorePort,
    bus: StoreInvalidationBus,
): StorePort = PublishingStorePort(port, StoreProjectionObserver(bus))

private suspend fun PagingSource<String, Memo>.loadResult(
    loadSize: Int,
): PagingSource.LoadResult<String, Memo> =
    load(
        PagingSource.LoadParams.Refresh(
            key = null,
            loadSize = loadSize,
            placeholdersEnabled = false,
        ),
    )

private suspend fun PagingSource<String, Memo>.loadPage(loadSize: Int): List<Memo> =
    when (val result = loadResult(loadSize)) {
        is PagingSource.LoadResult.Page -> result.data
        is PagingSource.LoadResult.Error -> throw result.throwable
        is PagingSource.LoadResult.Invalid -> error("PagingSource returned invalid result")
    }
