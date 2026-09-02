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
 * - Given writable authority, when saveMemo succeeds, then Create is applied, invalidation bumps,
 *   reminder sync runs, and returned Memo matches getMemo.
 * - Given writable authority, when update/delete/pin run, then correct command kinds are applied.
 * - Given a selected historical revision, when restore runs, then its body rather than its ID is
 *   submitted as the replacement content.
 * - Given frozen write authority, when saveMemo is attempted, then it fails closed.
 * - Given store summaries with tags/images, when list/stats run, then tags/imageUrls and tag
 *   counts are projected from StorePort (M3).
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
 *
 * Observable outcomes: domain Memo fields, command kinds recorded on fake port, reminder calls,
 * invalidation tick advancement, write-lease admission, and thrown check failures.
 *
 * TDD proof:
 * - Target: ./kotlin test --include-module=data --include-classes='com.lomo.data.repository.StoreMemoRepositoriesTest'
 * - RED: production StoreMemo* repositories had zero host executions (coverage gaming C1).
 * - RED on 2026-08-25: manual refresh entered the workspace write lease before the full SAF scan,
 *   so a local rebuild blocked memo submission until every document had been read.
 *
 * Excludes:
 * - Real BoltFFI and Room dual-stack (deleted); history is covered by the store adapter contract.
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

import app.cash.turbine.test
import androidx.paging.PagingSource
import com.lomo.data.engine.store.StoreMemoCommand
import com.lomo.data.engine.store.StoreMemoCommandKind
import com.lomo.data.engine.store.StoreMemoCommit
import com.lomo.data.engine.store.StoreInvalidationScope
import com.lomo.data.engine.store.StoreMemoPage
import com.lomo.data.engine.store.StoreMemoQuery
import com.lomo.data.engine.store.StoreMemoSnapshot
import com.lomo.data.engine.store.StoreMemoSummary
import com.lomo.data.engine.store.StoreMemoSortField
import com.lomo.data.engine.store.StoreSortDirection
import com.lomo.data.engine.store.StoreHistoryAttachmentRef
import com.lomo.data.engine.store.StorePageCursor
import com.lomo.data.engine.store.StorePort
import com.lomo.data.engine.store.StoreRebuildResult
import com.lomo.data.testing.fakes.FakeEngineReadinessRepository
import com.lomo.data.testing.fakes.FakeReminderCoordinator
import com.lomo.domain.model.EngineReadiness
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoRevision
import com.lomo.domain.model.MemoRevisionLifecycleState
import com.lomo.domain.model.MemoRevisionOrigin
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
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

private class RecordingStorePort : StorePort {
    val commands = mutableListOf<StoreMemoCommand>()
    var queryCount = 0
    var sidebarQueryCount = 0
    var rebuildCount = 0
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
        val start = if (cursor == null) 0 else all.indexOfFirst { it.memoId == cursor.encoded }.let { if (it < 0) 0 else it + 1 }
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
        )
    }

    override fun getMemo(memoId: String): StoreMemoSnapshot? = memos[memoId]

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

    override fun listHistoryAttachmentRefs(): List<StoreHistoryAttachmentRef> = emptyList()

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
                commitOf(command, memos.getValue(id))
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

    override fun startRebuild(batchSize: Int): StoreRebuildResult {
        rebuildCount++
        coreRevision += 1L
        eventSequence += 1L
        val digest = "digest-${memos.size}"
        return StoreRebuildResult(
            memosIndexed = memos.size.toLong(),
            fileCount = memos.size.toLong(),
            attachmentCount = memos.values.count { it.summary.hasAttachment }.toLong(),
            workspaceDigest = digest,
            storeDigest = digest,
            corruptLomoIsolated = 0L,
            highWaterRevision = coreRevision,
        )
    }

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
}

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
            ),
        body = body,
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

    test("mutation repository create update delete pin go through store and reminders") {
        runTest {
            val port = RecordingStorePort()
            val invalidation = StoreInvalidationBus()
            val reminders = FakeReminderCoordinator()
            val query = StoreMemoQueryRepository(port, invalidation, FakeEngineReadinessRepository())
            val mutation =
                StoreMemoMutationRepository(
                    port = port,
                    queryRepository = query,
                    reminderScheduler = reminders,
                    writeLease = alwaysWritableWorkspaceMutationLease(),
                    invalidation = invalidation,
                    diagnostics = RingBufferEngineDiagnosticsRecorder(),
                )

            val created = mutation.saveMemo(content = "new memo", timestamp = 1L, geoLocation = null)
            created.content shouldBe "new memo"
            port.commands.last().kind shouldBe StoreMemoCommandKind.Create
            reminders.syncForMemoCalls.map { it.first } shouldContainExactly listOf(created.id)

            mutation.updateMemo(created, "edited")
            port.commands.last().kind shouldBe StoreMemoCommandKind.Update
            query.getMemoById(created.id)?.content shouldBe "edited"

            mutation.setMemoPinned(created.id, pinned = true)
            port.commands.last().kind shouldBe StoreMemoCommandKind.Pin
            query.getMemoById(created.id)?.isPinned shouldBe true

            mutation.deleteMemo(created)
            port.commands.last().kind shouldBe StoreMemoCommandKind.Delete
            reminders.cancelForMemoCalls shouldContainExactly listOf(created.id)

            mutation.refreshMemos()
            port.rebuildCount shouldBe 1
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
                    port = port,
                    queryRepository = query,
                    reminderScheduler = FakeReminderCoordinator(),
                    writeLease = alwaysWritableWorkspaceMutationLease(),
                    invalidation = invalidation,
                    diagnostics = RingBufferEngineDiagnosticsRecorder(),
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

            mutation.restoreMemoRevision(current, selected)

            port.commands.last().kind shouldBe StoreMemoCommandKind.HistoryRestore
            port.commands.last().content shouldBe "historical body"
            port.commands.last().content shouldBe selected.memoContent
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
                    port = port,
                    queryRepository = query,
                    reminderScheduler = FakeReminderCoordinator(),
                    writeLease = rejectingWriteLease,
                    invalidation = invalidation,
                    diagnostics = RingBufferEngineDiagnosticsRecorder(),
                )

            mutation.refreshMemos()

            port.rebuildCount shouldBe 1
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
                    port = port,
                    queryRepository = query,
                    reminderScheduler = FakeReminderCoordinator(),
                    writeLease = refusingLease,
                    invalidation = invalidation,
                    diagnostics = diagnostics,
                )

            runCatching { mutation.saveMemo(content = "blocked", timestamp = 1L, geoLocation = null) }
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
                    port = port,
                    queryRepository = query,
                    reminderScheduler = FakeReminderCoordinator(),
                    writeLease = alwaysWritableWorkspaceMutationLease(),
                    invalidation = invalidation,
                    diagnostics = diagnostics,
                )

            val created = mutation.saveMemo(content = "new memo", timestamp = 1L, geoLocation = null)

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

            shouldThrow<EngineCommandFailureException> { mutation.deleteMemo(created) }

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
            val trash =
                StoreMemoTrashRepository(
                    port,
                    alwaysWritableWorkspaceMutationLease(),
                    invalidation,
                    readiness,
                )
            val mutation =
                StoreMemoMutationRepository(
                    port = port,
                    queryRepository = query,
                    reminderScheduler = FakeReminderCoordinator(),
                    writeLease = alwaysWritableWorkspaceMutationLease(),
                    invalidation = invalidation,
                    diagnostics = RingBufferEngineDiagnosticsRecorder(),
                )
            val source = trash.getDeletedMemosPagingSource()
            source.invalid shouldBe false

            mutation.deleteMemo(query.getMemoById("trash-me").shouldNotBeNull())

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
            readiness.publishProjectionFreshness(com.lomo.domain.model.ProjectionFreshness.Building(0uL))
            val trash =
                StoreMemoTrashRepository(
                    port,
                    alwaysWritableWorkspaceMutationLease(),
                    StoreInvalidationBus(),
                    readiness,
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
            val trash =
                StoreMemoTrashRepository(
                    port = port,
                    writeLease = alwaysWritableWorkspaceMutationLease(),
                    invalidation = invalidation,
                    readiness = FakeEngineReadinessRepository(),
                )

            trash.clearTrash()

            port.getMemo("memo-1") shouldBe null
            port.getMemo("memo-2") shouldBe null
            port.getMemo("memo-3") shouldBe null
            port.commands.filter { it.kind == StoreMemoCommandKind.PermanentDelete } shouldHaveSize 3
        }
    }

    test("mutation repository fails closed when the workspace lease refuses admission") {
        runTest {
            val port = RecordingStorePort()
            val mutation =
                StoreMemoMutationRepository(
                    port = port,
                    queryRepository = StoreMemoQueryRepository(
                        port,
                        StoreInvalidationBus(),
                        FakeEngineReadinessRepository(),
                    ),
                    reminderScheduler = FakeReminderCoordinator(),
                    writeLease = notReadyWriteLease(),
                    invalidation = StoreInvalidationBus(),
                    diagnostics = RingBufferEngineDiagnosticsRecorder(),
                )
            shouldThrow<IllegalStateException> {
                mutation.saveMemo("x", timestamp = 1L, geoLocation = null)
            }
            port.commands shouldHaveSize 0
        }
    }

    test("statistics repository aggregates from store summaries") {
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
            val stats =
                StoreMemoStatisticsRepository(
                    port = port,
                    invalidation = invalidation,
                    readiness = FakeEngineReadinessRepository(),
                )

            stats.getMemoCountFlow().test {
                awaitItem() shouldBe 2
                cancelAndIgnoreRemainingEvents()
            }
            val zone = ZoneId.of("UTC")
            val memoStats =
                stats.getMemoStatistics(
                    zone = zone,
                    today = LocalDate.of(2024, 7, 3),
                )
            memoStats.totalMemos shouldBe 2
            val tagCounts = stats.getTagCountsFlow().first()
            tagCounts.map { it.name to it.count } shouldContainExactly
                listOf("work" to 2, "life" to 1)
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
            val stats =
                StoreMemoStatisticsRepository(
                    port = port,
                    invalidation = StoreInvalidationBus(),
                    readiness = FakeEngineReadinessRepository(),
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
            port.queryCount shouldBe 41
        }
    }

    test("statistics repository stays empty until a workspace engine is ready") {
        runTest {
            val port = RecordingStorePort()
            port.seed(seededSnapshot("cold", "must not be queried"))
            val stats =
                StoreMemoStatisticsRepository(
                    port = port,
                    invalidation = StoreInvalidationBus(),
                    readiness = FakeEngineReadinessRepository(EngineReadiness.AwaitingWorkspaceSelection),
                )

            stats.getMemoCountFlow().first() shouldBe 0
            stats.getTagCountsFlow().first() shouldBe emptyList()
            stats.getMemoStatistics(ZoneId.of("UTC"), LocalDate.of(2024, 7, 3)).totalMemos shouldBe 0
            port.queryCount shouldBe 0
        }
    }

    test("given missing memo snapshot when deleteMemo runs then validation failure is thrown and recorded") {
        runTest {
            val port = RecordingStorePort()
            val invalidation = StoreInvalidationBus()
            val diagnostics = RingBufferEngineDiagnosticsRecorder()
            val query = StoreMemoQueryRepository(port, invalidation, FakeEngineReadinessRepository())
            val mutation =
                StoreMemoMutationRepository(
                    port = port,
                    queryRepository = query,
                    reminderScheduler = FakeReminderCoordinator(),
                    writeLease = alwaysWritableWorkspaceMutationLease(),
                    invalidation = invalidation,
                    diagnostics = diagnostics,
                )

            val ghostMemo =
                Memo(
                    id = "ghost-memo-id",
                    timestamp = 1000L,
                    content = "does not exist",
                    rawContent = "12:00:00 does not exist",
                    dateKey = "2026_08_15",
                )

            val error = shouldThrow<EngineCommandFailureException> { mutation.deleteMemo(ghostMemo) }
            error.failure.code shouldBe "memo_identity_not_found"
            error.failure.category shouldBe EngineFailureCategory.VALIDATION

            val rejected =
                diagnostics.events.value.filterIsInstance<EngineDiagnosticEvent.Rejected>()[0]
            rejected.label shouldBe "memo.delete"
            rejected.failure.code shouldBe "memo_identity_not_found"
            rejected.failure.category shouldBe EngineFailureCategory.VALIDATION
        }
    }
})
