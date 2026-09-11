package com.lomo.data.repository

import androidx.paging.PagingSource
import com.lomo.data.engine.SessionNativeBridge
import com.lomo.data.engine.engineCommandFailure
import com.lomo.data.engine.withEngineFailureConversion
import com.lomo.data.engine.media.PendingMediaStageRegistry
import com.lomo.data.engine.store.StoreInvalidationScope
import com.lomo.data.engine.store.StoreMemoCommand
import com.lomo.data.engine.store.StoreMemoCommandKind
import com.lomo.data.engine.store.StoreMemoDeleteTarget
import com.lomo.data.engine.store.StoreMemoFilters
import com.lomo.data.engine.store.StoreMemoQuery
import com.lomo.data.engine.store.StoreMemoQueryBoundary
import com.lomo.data.engine.store.StoreMemoSort
import com.lomo.data.engine.store.StoreMemoSortField
import com.lomo.data.engine.store.SessionSearchPagingSource
import com.lomo.data.engine.store.StorePagingSource
import com.lomo.data.engine.store.StorePort
import com.lomo.data.engine.store.StoreSortDirection
import com.lomo.data.engine.store.toDomainMemo
import com.lomo.data.reminder.MemoMutationReminderScheduler
import com.lomo.domain.model.DailyReviewCandidateBoundary
import com.lomo.domain.model.DailyReviewCandidateCursor
import com.lomo.domain.model.DailyReviewCandidatePage
import com.lomo.domain.model.EngineCommandFailure
import com.lomo.domain.model.EngineCommandFailureException
import com.lomo.domain.model.EngineDiagnosticEvent
import com.lomo.domain.model.EngineDiagnosticsRecorder
import com.lomo.domain.model.EngineFailureCategory
import com.lomo.domain.model.EngineReadiness
import com.lomo.domain.model.permitsReadsAt
import com.lomo.domain.model.EngineRetryDisposition
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoContentKind
import com.lomo.domain.model.MemoRevision
import com.lomo.domain.model.MemoFilterCriterion
import com.lomo.domain.model.MemoQuerySpec
import com.lomo.domain.model.MemoListFilter
import com.lomo.domain.model.MemoSearchMode
import com.lomo.domain.model.MemoStatistics
import com.lomo.domain.model.MemoSortOption
import com.lomo.domain.model.MemoSidebarStatistics
import com.lomo.domain.model.MemoTagCount
import com.lomo.domain.model.TagSelection
import com.lomo.domain.model.TagSelectionMode
import com.lomo.domain.repository.MainListQueryRepository
import com.lomo.domain.repository.EngineReadinessRepository
import com.lomo.domain.repository.MemoListQueryRepository
import com.lomo.domain.repository.MemoMutationRepository
import com.lomo.domain.repository.MemoQueryRepository
import com.lomo.domain.repository.MemoSearchRepository
import com.lomo.domain.repository.MemoStatisticsRepository
import com.lomo.domain.repository.MemoTrashRepository
import com.lomo.domain.repository.MemoVersionRepository
import com.lomo.domain.repository.MediaRepository
import com.lomo.domain.repository.WorkspaceMutationLease
import com.lomo.domain.repository.WorkspaceStateResolver
import com.lomo.domain.model.MemoRevisionCursor
import com.lomo.domain.model.MemoRevisionPage
import com.lomo.domain.model.MemoRevisionOrigin
import com.lomo.domain.model.MemoRevisionLifecycleState
import com.lomo.nativebridge.SessionCivilDate
import com.lomo.nativebridge.SessionCivilTime
import com.lomo.nativebridge.SessionStatistics
import com.lomo.nativebridge.SessionStatisticsSnapshot
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.withContext
import kotlin.time.TimeMark
import kotlin.time.TimeSource

private const val STORE_PAGE_SIZE = 50

/**
 * Production memo repositories after P3-10: sole owner is Rust store via [StorePort].
 * No Room / Kotlin SQLite path remains.
 */
class StoreMemoQueryRepository(
    private val port: StorePort,
    private val invalidation: StoreInvalidationBus,
    private val readiness: EngineReadinessRepository,
) : MemoQueryRepository {
    override fun getGalleryMemosPagingSource(): PagingSource<String, Memo> =
        StorePagingSource(
            port = port,
            query = StoreMemoQuery(filters = StoreMemoFilters(hasAttachment = true)),
            registerInvalidation = { source ->
                invalidation.register(source, setOf(StoreInvalidationScope.MemoList))
            },
        )

    override suspend fun getRecentMemos(limit: Int): List<Memo> =
        withContext(Dispatchers.IO) {
            port
                .queryMemos(StoreMemoQuery(), cursor = null, pageSize = limit.coerceAtLeast(1))
                .items
                .map { summary ->
                    // behavior-contract: loop-io-ok: no bulk getMemo API; N is the page/result set size
                    port.getMemo(summary.memoId)?.toDomainMemo()
                        ?: summary.toDomainMemo(
                            body = summary.bodyPreview,
                            contentKind = MemoContentKind.Preview,
                        )
                }
        }

    override suspend fun getMemoCount(): Int =
        withContext(Dispatchers.IO) {
            if (readiness.readiness.value !is EngineReadiness.Ready) 0
            else {
                val count = port.queryCount(StoreMemoQuery())
                require(count in 0..Int.MAX_VALUE.toLong()) { "memo_count is outside the Kotlin count range" }
                count.toInt()
            }
        }

    override suspend fun getDailyReviewCandidateBoundary(): DailyReviewCandidateBoundary? =
        withContext(Dispatchers.IO) {
            val first =
                port.queryMemos(StoreMemoQuery(), null, 1).items.firstOrNull()
                    ?: return@withContext null
            DailyReviewCandidateBoundary(
                isPinned = first.isPinned,
                timestamp = first.createdAtMs,
                id = first.memoId,
                token = first.fileFingerprint,
                observedCount = getMemoCount(),
            )
        }

    override suspend fun getDailyReviewCandidatePage(
        boundary: DailyReviewCandidateBoundary,
        cursor: DailyReviewCandidateCursor?,
        limit: Int,
    ): DailyReviewCandidatePage =
        withContext(Dispatchers.IO) {
            queryDailyReviewPage(boundary, cursor, limit)
        }

    override suspend fun getDailyReviewVisibleUnseenPage(
        cursor: DailyReviewCandidateCursor?,
        limit: Int,
    ): DailyReviewCandidatePage =
        withContext(Dispatchers.IO) {
            // The visible-unseen pass is a fresh bounded query. Its separate cursor prevents a
            // frozen candidate cursor (whose fingerprint includes the boundary) from being reused
            // against the current collection.
            require(limit > 0) { "Daily Review page limit must be positive" }
            val page =
                port.queryMemos(
                    StoreMemoQuery(),
                    cursor?.token?.let { com.lomo.data.engine.store.StorePageCursor(it) },
                    limit,
                )
            val last = page.items.lastOrNull()
            DailyReviewCandidatePage(
                ids = page.items.map { it.memoId },
                nextCursor =
                    page.nextCursor?.let { next ->
                        checkNotNull(last) { "Store returned a cursor without a page item" }
                        DailyReviewCandidateCursor(
                            isPinned = last.isPinned,
                            timestamp = last.createdAtMs,
                            id = last.memoId,
                            token = next.encoded,
                            position = (cursor?.position ?: 0) + page.items.size,
                        )
                    },
            )
        }

    private fun queryDailyReviewPage(
        boundary: DailyReviewCandidateBoundary,
        cursor: DailyReviewCandidateCursor?,
        limit: Int,
    ): DailyReviewCandidatePage {
        require(limit > 0) { "Daily Review page limit must be positive" }
        val storeBoundary =
            StoreMemoQueryBoundary(
                isPinned = boundary.isPinned,
                primarySortMs = boundary.timestamp,
                createdAtMs = boundary.timestamp,
                memoId = boundary.id,
            )
        val page =
            port.queryMemos(
                StoreMemoQuery(boundary = storeBoundary),
                cursor?.token?.let { com.lomo.data.engine.store.StorePageCursor(it) },
                limit,
            )
        val last = page.items.lastOrNull()
        return DailyReviewCandidatePage(
            ids = page.items.map { it.memoId },
            nextCursor =
                page.nextCursor?.let { next ->
                    checkNotNull(last) { "Store returned a cursor without a page item" }
                    DailyReviewCandidateCursor(
                        isPinned = last.isPinned,
                        timestamp = last.createdAtMs,
                        id = last.memoId,
                        token = next.encoded,
                    )
                },
        )
    }

    override fun getMainListPagingSource(spec: MemoQuerySpec): PagingSource<String, Memo> =
        StorePagingSource(
            port = port,
            query = spec.toStoreQuery(),
            registerInvalidation = { source ->
                invalidation.register(source, setOf(StoreInvalidationScope.MemoList))
            },
        )

    override suspend fun getDefaultMainListIndexInWindow(
        id: String,
        limit: Int,
    ): Int? =
        withContext(Dispatchers.IO) {
            if (limit <= 0) return@withContext null
            val window =
                port
                    .queryMemos(
                        query = StoreMemoQuery(),
                        cursor = null,
                        pageSize = limit,
                    ).items
            window.indexOfFirst { it.memoId == id }.takeIf { it >= 0 }
        }

    override suspend fun getMemoById(id: String): Memo? =
        withContext(Dispatchers.IO) {
            port.getMemo(id)?.toDomainMemo()
        }

    override fun isSyncing(): Flow<Boolean> = invalidation.syncing

}


class StoreMemoMutationRepository(
    private val port: StorePort,
    private val queryRepository: MemoQueryRepository,
    private val reminderScheduler: MemoMutationReminderScheduler,
    private val writeLease: WorkspaceMutationLease,
    private val invalidation: StoreInvalidationBus,
    private val diagnostics: EngineDiagnosticsRecorder,
    private val pendingStages: PendingMediaStageRegistry,
) : MemoMutationRepository {
    override suspend fun refreshMemos() {
        withContext(Dispatchers.IO) {
            val started = TimeSource.Monotonic.markNow()
            invalidation.setSyncing(true)
            try {
                val result = port.startRebuild(batchSize = 64)
                invalidation.publishRebuild(result.highWaterRevision)
                val publication = invalidation.publications.value
                diagnostics.record(
                    EngineDiagnosticEvent.Committed(
                        label = "memo.refresh",
                        durationMillis = started.elapsedNow().inWholeMilliseconds,
                        coreRevision = publication.coreRevision,
                        scopes = publication.scopes.map { scope -> scope.name },
                    ),
                )
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                recordRefreshFailure(started, failure)
                throw failure
            } finally {
                invalidation.setSyncing(false)
            }
        }
    }

    override suspend fun commitDocumentMutation(
        mutation: com.lomo.domain.model.MemoDocumentMutation,
    ) {
        mutate("memo.document_projection") {
            val commit = port.commitDocumentMutation(mutation)
            invalidation.publish(commit)
            reminderScheduler.syncForMemo(mutation.facts.memoId)
        }
    }

    private suspend fun recordRefreshFailure(
        started: TimeMark,
        failure: Exception,
    ) {
        if (failure is CancellationException) throw failure
        val commandFailure =
            if (failure is EngineCommandFailureException) {
                failure.failure
            } else {
                EngineCommandFailure(
                    category = EngineFailureCategory.INTERNAL,
                    code = "refresh_failed",
                    retryDisposition = EngineRetryDisposition.AFTER_USER_ACTION,
                    operationId = null,
                    jobId = null,
                    diagnostic = failure.message ?: failure.javaClass.simpleName,
                )
            }
        diagnostics.record(
            EngineDiagnosticEvent.Rejected(
                label = "memo.refresh",
                durationMillis = started.elapsedNow().inWholeMilliseconds,
                failure = commandFailure,
            ),
        )
    }

    override suspend fun saveMemo(
        content: String,
        timestamp: Long,
        geoLocation: String?,
    ): Memo {
        return mutate("memo.create") {
            val opId = UUID.randomUUID().toString()
            val promotes = stagedPromotes(opId)
            val commit =
                port.applyMemoCommand(
                    StoreMemoCommand(
                        operationId = opId,
                        kind = StoreMemoCommandKind.Create,
                        memoId = "",
                        expectedRevision = 0L,
                        chronologyEpochMs = timestamp,
                        content = content,
                        pendingPromotes = promotes,
                    ),
                    // The engine publishes the pending create before its durable SAF I/O, so
                    // the list shows the memo while the commit is still in flight.
                    onPublication = invalidation::publish,
                )
            // D8: journal committed media only after memo-bound promote succeeds.
            invalidation.publish(commit)
            pendingStages.commit(promotes)
            val memo =
                port.getMemo(commit.memoId)?.toDomainMemo()
                    ?: error("create commit succeeded but get_memo returned null for ${commit.memoId}")
            reminderScheduler.syncForMemo(memo.id)
            memo
        }
    }

    override suspend fun updateMemo(
        memo: Memo,
        newContent: String,
    ) {
        mutate("memo.update") {
            // The editor session owns the optimistic-concurrency baseline. Re-reading here would
            // turn a stale edit into an update against a newer document and violate CAS semantics.
            val expectedRevision =
                memo.contentRevision
                    ?: throw engineCommandFailure(
                        category = EngineFailureCategory.CONFLICT,
                        code = "edit_baseline_missing",
                        retryDisposition = EngineRetryDisposition.AFTER_USER_ACTION,
                        diagnostic = "Memo edit session does not carry a verified content revision",
                    )
            val expectedFingerprint =
                memo.fileFingerprint?.takeIf(String::isNotBlank)
                    ?: throw engineCommandFailure(
                        category = EngineFailureCategory.CONFLICT,
                        code = "edit_baseline_missing",
                        retryDisposition = EngineRetryDisposition.AFTER_USER_ACTION,
                        diagnostic = "Memo edit session does not carry a verified file fingerprint",
                    )
            val opId = UUID.randomUUID().toString()
            val promotes = stagedPromotes(opId)
            val commit =
                port.applyMemoCommand(
                    StoreMemoCommand(
                        operationId = opId,
                        kind = StoreMemoCommandKind.Update,
                        memoId = memo.id,
                        expectedRevision = expectedRevision,
                        expectedFingerprint = expectedFingerprint,
                        content = newContent,
                        pendingPromotes = promotes,
                    ),
                    onPublication = {},
                )
            invalidation.publish(commit)
            pendingStages.commit(promotes)
            reminderScheduler.syncForMemo(memo.id)
        }
    }

    /**
     * Pass the complete staged-fact snapshot to Rust.  Destination membership is selected by the
     * Rust Markdown owner at the command boundary for both Direct and SAF workspaces.
     */
    private fun stagedPromotes(
        operationId: String,
    ): List<com.lomo.data.engine.media.MediaPromotePlan> = pendingStages.allPlans(operationId)

    override suspend fun deleteMemo(memo: Memo) {
        mutate("memo.delete") {
            val (expectedRevision, expectedFingerprint) = memo.requireSessionCasBaseline()
            val reminderIds = memo.reminders.map { it.reference.opaqueId }.toSet()
            val commit =
                port.applyMemoCommand(
                    StoreMemoCommand(
                        operationId = UUID.randomUUID().toString(),
                        kind = StoreMemoCommandKind.Delete,
                        memoId = memo.id,
                        expectedRevision = expectedRevision,
                        expectedFingerprint = expectedFingerprint,
                    ),
                    onPublication = {},
                )
            invalidation.publish(commit)
            reminderScheduler.cancelForMemo(memo.id, reminderIds)
        }
    }

    override suspend fun restoreMemoRevision(
        currentMemo: Memo,
        revision: MemoRevision,
    ) {
        mutate("memo.history_restore") {
            require(revision.memoId == currentMemo.id) {
                "History revision does not belong to the mutation target"
            }
            val (expectedRevision, expectedFingerprint) = run {
                val expectedRevision =
                    currentMemo.contentRevision
                        ?: throw engineCommandFailure(
                            category = EngineFailureCategory.CONFLICT,
                            code = "edit_baseline_missing",
                            retryDisposition = EngineRetryDisposition.AFTER_USER_ACTION,
                            diagnostic = "Memo edit session does not carry a verified content revision",
                        )
                val expectedFingerprint =
                    currentMemo.fileFingerprint?.takeIf(String::isNotBlank)
                        ?: throw engineCommandFailure(
                            category = EngineFailureCategory.CONFLICT,
                            code = "edit_baseline_missing",
                            retryDisposition = EngineRetryDisposition.AFTER_USER_ACTION,
                            diagnostic = "Memo edit session does not carry a verified file fingerprint",
                        )
                expectedRevision to expectedFingerprint
            }
            val previousReminderIds = currentMemo.reminders.map { it.reference.opaqueId }.toSet()
            val commit = port.applyMemoCommand(
                StoreMemoCommand(
                    operationId = UUID.randomUUID().toString(),
                    kind = StoreMemoCommandKind.HistoryRestore,
                    memoId = currentMemo.id,
                    expectedRevision = expectedRevision,
                    expectedFingerprint = expectedFingerprint,
                    content = revision.memoContent,
                    historyRevision = historyRevisionNumber(revision),
                ),
                onPublication = {},
            )
            invalidation.publish(commit)
            val restored = queryRepository.getMemoById(currentMemo.id)
            if (restored == null) {
                reminderScheduler.cancelForMemo(currentMemo.id, previousReminderIds)
            } else {
                reminderScheduler.syncForMemo(restored.id)
            }
        }
    }

    // behavior-contract: in-situ-read-ok: id-only command; engine snapshot is the mutation baseline
    override suspend fun setMemoPinned(
        memoId: String,
        pinned: Boolean,
    ) {
        mutate("memo.pin") {
            val snap = port.getMemo(memoId) ?: return@mutate
            val commit = port.applyMemoCommand(
                StoreMemoCommand(
                    operationId = UUID.randomUUID().toString(),
                    kind = if (pinned) StoreMemoCommandKind.Pin else StoreMemoCommandKind.Unpin,
                    memoId = memoId,
                    expectedRevision = snap.summary.contentRevision,
                    expectedFingerprint = snap.summary.fileFingerprint,
                    pin = pinned,
                ),
                onPublication = {},
            )
            invalidation.publish(commit)
        }
    }

    /**
     * Every memo mutation is admitted by the workspace lease before it touches the store.
     *
     * Admission is registered, not merely checked, so a switch cannot begin between this call and
     * the command reaching the engine.
     */
    /**
     * Runs one labelled mutation with the write admission *inside* the diagnostics boundary.
     *
     * A refused admission is the failure mode that used to be structurally unobservable: it is
     * raised before the mutation body runs, so a boundary that only wrapped the body reported
     * nothing at all. [entered] discriminates the two without inspecting failure messages.
     */
    private suspend fun <T> mutate(
        label: String,
        block: suspend () -> T,
    ): T {
        val started = TimeSource.Monotonic.markNow()
        var entered = false
        try {
            return writeLease.withWrite {
                withContext(Dispatchers.IO) {
                    entered = true
                    block().also {
                        val publication = invalidation.publications.value
                        diagnostics.record(
                            EngineDiagnosticEvent.Committed(
                                label = label,
                                durationMillis = started.elapsedNow().inWholeMilliseconds,
                                coreRevision = publication.coreRevision,
                                scopes = publication.scopes.map { scope -> scope.name },
                            ),
                        )
                    }
                }
            }
        } catch (other: Exception) {
            if (other is kotlinx.coroutines.CancellationException) throw other
            val rejection = other as? EngineCommandFailureException
            val failure =
                rejection?.failure ?: EngineCommandFailure(
                    category = EngineFailureCategory.INTERNAL,
                    code = if (entered) "mutation_failed" else "write_admission_refused",
                    retryDisposition = EngineRetryDisposition.AFTER_USER_ACTION,
                    operationId = null,
                    jobId = null,
                    diagnostic = other.message ?: other.javaClass.simpleName,
                )
            diagnostics.record(
                EngineDiagnosticEvent.Rejected(
                    label = label,
                    durationMillis = started.elapsedNow().inWholeMilliseconds,
                    failure = failure,
                ),
            )
            throw other
        }
    }
}

internal class StoreMemoSearchRepository(
    private val port: StorePort,
    private val session: SessionNativeBridge,
    private val invalidation: StoreInvalidationBus,
) : MemoSearchRepository {
    private val searchEpoch = AtomicLong(0)

    override fun getMemosByTagPagingSource(selection: TagSelection): PagingSource<String, Memo> =
        StorePagingSource(
            port = port,
            query = StoreMemoQuery(
                filters =
                    StoreMemoFilters(
                        tag = selection.path.value,
                        tagSubtree = selection.mode == TagSelectionMode.Subtree,
                    ),
            ),
            registerInvalidation = { source ->
                invalidation.register(
                    source,
                    setOf(StoreInvalidationScope.Search, StoreInvalidationScope.Tags),
                )
            },
        )

    override fun searchPagingSource(
        query: String,
        mode: MemoSearchMode,
        filter: MemoListFilter,
    ): PagingSource<String, Memo> =
        when (mode) {
            MemoSearchMode.Fulltext ->
                StorePagingSource(
                    port = port,
                    query = MemoQuerySpec.fromFilter(queryText = query, filter = filter).toStoreQuery(),
                    registerInvalidation = { source ->
                        invalidation.register(source, setOf(StoreInvalidationScope.Search))
                    },
                )
            MemoSearchMode.Fuzzy ->
                SessionSearchPagingSource(
                    session = session,
                    port = port,
                    queryEpoch = searchEpoch.incrementAndGet().toULong(),
                    text = query,
                    registerInvalidation = { source ->
                        invalidation.register(source, setOf(StoreInvalidationScope.Search))
                    },
                )
        }
}

internal class StoreMemoStatisticsRepository(
    private val port: StorePort,
    private val session: SessionNativeBridge,
    private val invalidation: StoreInvalidationBus,
    private val readiness: EngineReadinessRepository,
    applicationScope: CoroutineScope,
) : MemoStatisticsRepository {
    /**
     * A publication is one projection snapshot. Share that bounded read across every sidebar
     * consumer and stop collecting when the application has no observers.
     */
    private val sharedSidebarProjection: Flow<com.lomo.data.engine.store.StoreSidebarProjection> =
        combine(
            invalidation.publicationsFor(StoreInvalidationScope.Stats),
            readiness.readiness,
        ) { _, engineReadiness ->
            if (engineReadiness is EngineReadiness.Ready) {
                port.sidebarProjection()
            } else {
                com.lomo.data.engine.store.StoreSidebarProjection(
                    schemaVersion = 1u,
                    memoCount = 0,
                    dateCounts = emptyList(),
                    tagCounts = emptyList(),
                )
            }
        }.shareIn(
            scope = applicationScope,
            started = SharingStarted.WhileSubscribed(STATS_PROJECTION_STOP_TIMEOUT_MILLIS),
            replay = 1,
        )

    override suspend fun getMemoStatistics(
        zone: ZoneId,
        today: LocalDate,
    ): MemoStatistics =
        withContext(Dispatchers.IO) {
            if (readiness.readiness.value !is EngineReadiness.Ready) {
                return@withContext MemoStatistics.empty(today)
            }
            withEngineFailureConversion {
                session
                    .sessionStatistics(
                        SessionStatisticsSnapshot(
                            zone = zone.id,
                            asOf = today.toSessionCivilDate(),
                        ),
                    ).toDomainMemoStatistics()
            }
        }

    override fun getMemoCountFlow(): Flow<Int> =
        activeSidebarProjection().map { it.memoCount }.flowOn(Dispatchers.IO)

    override fun getSidebarStatisticsFlow(): Flow<MemoSidebarStatistics> =
        activeSidebarProjection()
            .map { projection ->
                MemoSidebarStatistics(
                    memoCount = projection.memoCount,
                    memoCountByDate =
                        projection.dateCounts.associate { bucket ->
                            LocalDate.parse(bucket.date) to bucket.count
                        },
                    tagCounts = projection.tagCounts.map { MemoTagCount(it.name, it.count) },
                )
            }.flowOn(Dispatchers.IO)

    override fun getMemoCountByDateFlow(): Flow<Map<String, Int>> =
        activeSidebarProjection()
            .map { projection -> projection.dateCounts.associate { it.date to it.count } }
            .flowOn(Dispatchers.IO)

    override fun getTagCountsFlow(): Flow<List<MemoTagCount>> =
        activeSidebarProjection()
            .map { projection -> projection.tagCounts.map { MemoTagCount(it.name, it.count) } }
            .flowOn(Dispatchers.IO)

    override fun getActiveDayCount(): Flow<Int> =
        getMemoCountByDateFlow().map { it.size }

    private fun activeSidebarProjection(): Flow<com.lomo.data.engine.store.StoreSidebarProjection> =
        sharedSidebarProjection
}

private const val STATS_PROJECTION_STOP_TIMEOUT_MILLIS = 5_000L

class StoreMemoTrashRepository(
    private val port: StorePort,
    private val writeLease: WorkspaceMutationLease,
    private val invalidation: StoreInvalidationBus,
    private val readiness: EngineReadinessRepository,
    private val reminderScheduler: MemoMutationReminderScheduler,
    private val mediaRepository: MediaRepository,
) : MemoTrashRepository {
    override fun getDeletedMemosPagingSource(): PagingSource<String, Memo> =
        StorePagingSource(
            port = port,
            query = StoreMemoQuery(filters = StoreMemoFilters(trashOnly = true, includeTrash = true)),
            registerInvalidation = { source ->
                invalidation.register(source, setOf(StoreInvalidationScope.Trash))
            },
        )

    override suspend fun restoreMemo(memo: Memo) {
        mutate {
            val (expectedRevision, expectedFingerprint) = memo.requireSessionCasBaseline()
            val commit = port.applyMemoCommand(
                StoreMemoCommand(
                    operationId = UUID.randomUUID().toString(),
                    kind = StoreMemoCommandKind.Restore,
                    memoId = memo.id,
                    expectedRevision = expectedRevision,
                    expectedFingerprint = expectedFingerprint,
                ),
                onPublication = {},
            )
            invalidation.publish(commit)
            reminderScheduler.syncForMemo(memo.id)
        }
    }

    override suspend fun deletePermanently(memo: Memo) {
        // Rust verifies the active Markdown source and matching durable trash record, removes the
        // source bytes first, then removes the record and publishes the rebuilt projection commit.
        mutate {
            val (expectedRevision, expectedFingerprint) = memo.requireSessionCasBaseline()
            val reminderIds = memo.reminders.map { it.reference.opaqueId }.toSet()
            val commit = port.applyMemoCommand(
                StoreMemoCommand(
                    operationId = UUID.randomUUID().toString(),
                    kind = StoreMemoCommandKind.PermanentDelete,
                    memoId = memo.id,
                    expectedRevision = expectedRevision,
                    expectedFingerprint = expectedFingerprint,
                ),
                onPublication = {},
            )
            invalidation.publish(commit)
            reminderScheduler.cancelForMemo(memo.id, reminderIds)
            mediaRepository.runOrphanSweepAtOperationBoundary()
        }
    }

    override suspend fun clearTrash() {
        mutate {
            val trashQuery = StoreMemoQuery(filters = StoreMemoFilters(trashOnly = true, includeTrash = true))
            val targets =
                walkStorePages(port, trashQuery)
                    .map { summary ->
                        check(summary.isTrashed) { "Trash query returned a non-trashed memo" }
                        check(!summary.isPending) { "Pending memo cannot be permanently deleted" }
                        StoreMemoDeleteTarget(
                            memoId = summary.memoId,
                            sourcePath = summary.sourcePath,
                            expectedRevision = summary.contentRevision,
                            expectedFingerprint = summary.fileFingerprint,
                        )
                    }.sortedBy(StoreMemoDeleteTarget::memoId)
                    .toList()
            if (targets.isEmpty()) return@mutate
            val commit = port.permanentDeleteMany(UUID.randomUUID().toString(), targets)
            invalidation.publishBatchCommit(commit)
            commit.deleted.forEach { deleted ->
                reminderScheduler.cancelForMemo(deleted.memoId, deleted.reminderIds.toSet())
            }
            mediaRepository.runOrphanSweepAtOperationBoundary()
        }
    }

    /** Trash mutations are admitted by the same workspace lease as memo mutations. */
    private suspend fun <T> mutate(block: suspend () -> T): T =
        writeLease.withWrite {
            withContext(Dispatchers.IO) {
                readiness.requireProjectionReadable()
                block()
            }
        }
}

private fun EngineReadinessRepository.requireProjectionReadable() {
    val authority = workspaceAuthority.value
    val readable =
        readiness.value is EngineReadiness.Ready &&
            authority != null &&
            projectionFreshness.value.permitsReadsAt(authority.projectionRevision)
    if (!readable) {
        throw engineCommandFailure(
            category = EngineFailureCategory.BUSY,
            code = "projection_not_ready",
            retryDisposition = EngineRetryDisposition.TRANSIENT,
            diagnostic = "Trash mutation is waiting for the active workspace projection",
        )
    }
}

/**
 * Version history is durable under `.lomo/history` (Rust). Kotlin journal/Room is deleted.
 * History listing is supplied by the application session; restore uses session restore revision.
 */
internal class StoreMemoVersionRepository(
    private val port: StorePort,
    private val session: SessionNativeBridge,
) : MemoVersionRepository {
    override suspend fun listMemoRevisions(
        memo: Memo,
        cursor: MemoRevisionCursor?,
        limit: Int,
    ): MemoRevisionPage {
        val cursorRevision = cursor?.revisionId?.substringAfterLast(':')?.takeIf { it.all(Char::isDigit) }
        val page =
            withEngineFailureConversion {
                session.sessionListHistory(
                    memo.id,
                    cursorRevision,
                    limit.coerceIn(1, 256).toUInt(),
                )
            }
        val currentRevision = port.getMemo(memo.id)?.summary?.contentRevision
        val items = page.items.map { item ->
            val revision = item.revision.toLong()
            val id = "${memo.id}-r$revision"
            MemoRevision(
                revisionId = id,
                parentRevisionId = if (revision > 1) "${memo.id}-r${revision - 1}" else null,
                memoId = memo.id,
                commitId = id,
                batchId = null,
                createdAt = item.createdAtMs,
                origin = MemoRevisionOrigin.LOCAL_EDIT,
                summary = item.content.lineSequence().firstOrNull().orEmpty(),
                lifecycleState =
                    if (memo.isDeleted) MemoRevisionLifecycleState.TRASHED
                    else MemoRevisionLifecycleState.ACTIVE,
                memoContent = item.content,
                isCurrent = revision == currentRevision,
            )
        }
        return MemoRevisionPage(
            items = items,
            nextCursor = page.nextCursor?.let {
                MemoRevisionCursor(items.lastOrNull()?.createdAt ?: 0L, "${memo.id}:cursor:$it")
            },
        )
    }

}

class StoreWorkspaceStateResolver(
    private val port: StorePort,
    private val invalidation: StoreInvalidationBus,
) : WorkspaceStateResolver {
    override suspend fun rebuildFromCurrentWorkspace() {
        withContext(Dispatchers.IO) {
            invalidation.setSyncing(true)
            try {
                val result = port.startRebuild(batchSize = 64)
                invalidation.publishRebuild(result.highWaterRevision)
            } finally {
                invalidation.setSyncing(false)
            }
        }
    }
}

/** One accepted Rust projection publication. Sequence is unknown only immediately after rebuild. */
data class StoreProjectionPublication(
    val coreRevision: Long,
    val eventSequence: Long?,
    val scopes: Set<StoreInvalidationScope>,
)

/**
 * Monotonic projection-publication boundary replacing Room invalidation guesses after cutover.
 *
 * Rust commit revisions are the only mutation clock. Duplicate/older results are ignored, missing
 * revisions promote to [StoreInvalidationScope.Full], and paging consumers subscribe by scope.
 */
class StoreInvalidationBus {
    private val publicationLock = Any()
    private val _publications =
        MutableStateFlow(
            StoreProjectionPublication(
                coreRevision = 0L,
                eventSequence = 0L,
                scopes = setOf(StoreInvalidationScope.Full),
            ),
        )
    val publications: StateFlow<StoreProjectionPublication> = _publications.asStateFlow()
    private val _syncing = MutableStateFlow(false)
    val syncing: Flow<Boolean> = _syncing
    private val pagingSources = mutableMapOf<PagingSource<*, *>, Set<StoreInvalidationScope>>()
    private var lastCoreRevision = 0L
    private var lastEventSequence: Long? = 0L

    fun register(
        source: PagingSource<*, *>,
        scopes: Set<StoreInvalidationScope>,
    ) {
        require(scopes.isNotEmpty()) { "Store paging invalidation scopes must not be empty" }
        synchronized(publicationLock) {
            pagingSources[source] = scopes
        }
        source.registerInvalidatedCallback {
            synchronized(publicationLock) {
                pagingSources.remove(source)
            }
        }
    }

    fun publicationsFor(vararg scopes: StoreInvalidationScope): Flow<StoreProjectionPublication> {
        require(scopes.isNotEmpty()) { "Store publication scopes must not be empty" }
        val selected = scopes.toSet()
        return publications.filter { publication -> publication.scopes.affects(selected) }
    }

    fun publish(commit: com.lomo.data.engine.store.StoreMemoCommit) {
        publishBatch(listOf(commit))
    }

    /** Publishes one logical batch mutation without fabricating a per-memo commit. */
    fun publishBatchCommit(commit: com.lomo.data.engine.store.StoreMemoBatchCommit) {
        require(commit.deleted.isNotEmpty()) { "Store batch commit must delete at least one memo" }
        val sources =
            synchronized(publicationLock) {
                val decision =
                    acceptPublication(
                        coreRevision = commit.coreRevision,
                        eventSequence = commit.eventSequence,
                        scopes = commit.scopes,
                        idempotentReplay = commit.idempotentReplay,
                    ) ?: return@synchronized emptyList()
                val publication =
                    StoreProjectionPublication(
                        coreRevision = lastCoreRevision,
                        eventSequence = lastEventSequence,
                        scopes = decision,
                    )
                _publications.value = publication
                pagingSources.keys.toList()
            }
        sources.forEach(PagingSource<*, *>::invalidate)
    }

    fun publishBatch(commits: List<com.lomo.data.engine.store.StoreMemoCommit>) {
        if (commits.isEmpty()) return
        val sources =
            synchronized(publicationLock) {
                val effectiveScopes = linkedSetOf<StoreInvalidationScope>()
                var accepted = false
                for (commit in commits) {
                    val decision = acceptCommit(commit) ?: continue
                    accepted = true
                    if (decision == setOf(StoreInvalidationScope.Full)) {
                        effectiveScopes.clear()
                        effectiveScopes += StoreInvalidationScope.Full
                    } else if (StoreInvalidationScope.Full !in effectiveScopes) {
                        effectiveScopes += decision
                    }
                }
                if (!accepted) {
                    emptyList()
                } else {
                    val publication =
                        StoreProjectionPublication(
                            coreRevision = lastCoreRevision,
                            eventSequence = lastEventSequence,
                            scopes = effectiveScopes.toSet(),
                        )
                    _publications.value = publication
                    // Every accepted commit advances the Rust high-water revision embedded in
                    // opaque page cursors.  A cursor therefore becomes invalid for *all* query
                    // projections, regardless of the commit's diagnostic scopes.  Scope labels
                    // remain available through `publicationsFor` for aggregate observers, but
                    // they are deliberately not a paging invalidation router.
                    pagingSources.keys.toList()
                }
            }
        sources.forEach(PagingSource<*, *>::invalidate)
    }

    fun publishRebuild(highWaterRevision: Long) {
        require(highWaterRevision > 0L) { "Store rebuild high-water revision must be positive" }
        val sources =
            synchronized(publicationLock) {
                if (highWaterRevision <= lastCoreRevision) {
                    emptyList()
                } else {
                    lastCoreRevision = highWaterRevision
                    lastEventSequence = null
                    _publications.value =
                        StoreProjectionPublication(
                            coreRevision = highWaterRevision,
                            eventSequence = null,
                            scopes = setOf(StoreInvalidationScope.Full),
                        )
                    pagingSources.keys.toList()
                }
            }
        sources.forEach(PagingSource<*, *>::invalidate)
    }

    fun setSyncing(value: Boolean) {
        _syncing.value = value
    }

    private fun acceptCommit(
        commit: com.lomo.data.engine.store.StoreMemoCommit,
    ): Set<StoreInvalidationScope>? =
        acceptPublication(
            coreRevision = commit.coreRevision,
            eventSequence = commit.eventSequence,
            scopes = commit.scopes,
            idempotentReplay = commit.idempotentReplay,
        )

    private fun acceptPublication(
        coreRevision: Long,
        eventSequence: Long,
        scopes: List<StoreInvalidationScope>,
        idempotentReplay: Boolean,
    ): Set<StoreInvalidationScope>? {
        require(coreRevision > 0L) { "Store commit core revision must be positive" }
        require(eventSequence > 0L) { "Store commit event sequence must be positive" }
        if (coreRevision < lastCoreRevision) return null
        if (coreRevision == lastCoreRevision) {
            val knownSequence = lastEventSequence
            if (knownSequence == null || eventSequence <= knownSequence) return null
            error("Store commit advanced event sequence without advancing core revision")
        }
        val previousSequence = lastEventSequence
        if (previousSequence != null && eventSequence <= previousSequence) {
            error("Store commit event sequence regressed while core revision advanced")
        }
        val contiguous =
            previousSequence != null &&
                lastCoreRevision != Long.MAX_VALUE &&
                previousSequence != Long.MAX_VALUE &&
                coreRevision == lastCoreRevision + 1L &&
                eventSequence == previousSequence + 1L
        lastCoreRevision = coreRevision
        lastEventSequence = eventSequence
        return if (contiguous && !idempotentReplay && scopes.isNotEmpty()) {
            scopes.toSet()
        } else {
            setOf(StoreInvalidationScope.Full)
        }
    }
}

private fun Set<StoreInvalidationScope>.affects(
    selected: Set<StoreInvalidationScope>,
): Boolean =
    StoreInvalidationScope.Full in this ||
        StoreInvalidationScope.Full in selected ||
        any(selected::contains)

private fun Memo.requireSessionCasBaseline(): Pair<Long, String> {
    val expectedRevision =
        contentRevision
            ?: throw engineCommandFailure(
                category = EngineFailureCategory.CONFLICT,
                code = "edit_baseline_missing",
                retryDisposition = EngineRetryDisposition.AFTER_USER_ACTION,
                diagnostic = "Memo edit session does not carry a verified content revision",
            )
    val expectedFingerprint =
        fileFingerprint?.takeIf(String::isNotBlank)
            ?: throw engineCommandFailure(
                category = EngineFailureCategory.CONFLICT,
                code = "edit_baseline_missing",
                retryDisposition = EngineRetryDisposition.AFTER_USER_ACTION,
                diagnostic = "Memo edit session does not carry a verified file fingerprint",
            )
    return expectedRevision to expectedFingerprint
}

private fun walkStorePages(
    port: StorePort,
    query: StoreMemoQuery,
): Sequence<com.lomo.data.engine.store.StoreMemoSummary> =
    sequence {
        var cursor: com.lomo.data.engine.store.StorePageCursor? = null
        val seenCursors = mutableSetOf<String>()
        while (true) {
            // behavior-contract: loop-io-ok: no bulk queryMemos API; each iteration is one bounded page
            val page = port.queryMemos(query, cursor, pageSize = STORE_PAGE_SIZE)
            if (page.items.isEmpty()) {
                return@sequence
            }
            yieldAll(page.items)
            val next = page.nextCursor ?: return@sequence
            check(seenCursors.add(next.encoded)) { "Store page cursor repeated before traversal completed" }
            cursor = next
        }
    }

/**
 * Adapts store string cursors to domain [PagingSource] Int keys used by existing UI contracts.
 * Page index is only a local load key; the real continuity token is carried internally.
 */
private fun MemoQuerySpec.toStoreQuery(): StoreMemoQuery {
    val hasTodo =
        when {
            MemoFilterCriterion.HasTodo in criteria -> true
            MemoFilterCriterion.NoTodo in criteria -> false
            else -> null
        }
    val hasAttachment =
        when {
            MemoFilterCriterion.HasAttachment in criteria -> true
            MemoFilterCriterion.NoAttachment in criteria -> false
            else -> null
        }
    val hasUrl =
        when {
            MemoFilterCriterion.HasUrl in criteria -> true
            MemoFilterCriterion.NoUrl in criteria -> false
            else -> null
        }
    return StoreMemoQuery(
        searchText = normalizedQueryText.ifBlank { null },
        filters =
            StoreMemoFilters(
                dateFromInclusiveMs = dateRange.startDate?.toStartOfDayEpochMillis(),
                dateUntilExclusiveMs = dateRange.endDate?.toExclusiveEndOfDayEpochMillis(),
                hasTodo = hasTodo,
                hasAttachment = hasAttachment,
                hasUrl = hasUrl,
            ),
        sort =
            StoreMemoSort(
                field =
                    when (sort.option) {
                        MemoSortOption.CREATED_TIME -> StoreMemoSortField.CreatedAt
                        MemoSortOption.UPDATED_TIME -> StoreMemoSortField.UpdatedAt
                    },
                direction =
                    if (sort.ascending) {
                        StoreSortDirection.Ascending
                    } else {
                        StoreSortDirection.Descending
                    },
            ),
    )
}

private fun historyRevisionNumber(revision: MemoRevision): Long {
    val prefix = "${revision.memoId}-r"
    require(revision.revisionId.startsWith(prefix)) {
        "History revision id must be $prefix<n>"
    }
    return revision.revisionId.removePrefix(prefix).toLong()
}

private fun LocalDate.toStartOfDayEpochMillis(): Long =
    atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

private fun LocalDate.toExclusiveEndOfDayEpochMillis(): Long? =
    takeUnless { it == LocalDate.MAX }
        ?.plusDays(1)
        ?.atStartOfDay(ZoneId.systemDefault())
        ?.toInstant()
        ?.toEpochMilli()

private fun LocalDate.toSessionCivilDate(): SessionCivilDate =
    SessionCivilDate(
        year = year,
        month = monthValue.toUByte(),
        day = dayOfMonth.toUByte(),
    )

private fun SessionStatistics.toDomainMemoStatistics(): MemoStatistics {
    val weekly = mutableMapOf<DayOfWeek, MutableMap<Int, Int>>()
    for (row in weeklyHourDistribution) {
        val weekday = DayOfWeek.of(row.weekday.toInt())
        weekly.getOrPut(weekday) { mutableMapOf() }[row.hour.toInt()] =
            row.count.toBoundedInt("weekly_hour_count")
    }
    return MemoStatistics(
        asOfDate = LocalDate.of(asOf.year, asOf.month.toInt(), asOf.day.toInt()),
        totalMemos = totalMemos.toBoundedInt("total_memos"),
        totalWords = totalWords.toBoundedInt("total_words"),
        totalCharacters = totalCharacters.toBoundedInt("total_characters"),
        averageWordsPerMemo = averageWordsPerMemo,
        totalTags = totalTags.toBoundedInt("total_tags"),
        activeDays = activeDays.toBoundedInt("active_days"),
        currentStreak = currentStreak.toBoundedInt("current_streak"),
        longestStreak = longestStreak.toBoundedInt("longest_streak"),
        memoCountByDate =
            memoCountByDate.associate { bucket ->
                LocalDate.of(bucket.year, bucket.month.toInt(), bucket.day.toInt()) to
                    bucket.count.toBoundedInt("date_count")
            },
        hourlyDistribution =
            hourlyDistribution.associate { bucket ->
                bucket.hour.toInt() to bucket.count.toBoundedInt("hour_count")
            },
        weeklyHourDistribution = weekly.mapValues { (_, hours) -> hours.toMap() },
        earliestDailyMemoTime = earliestDailyMemoTime?.toLocalTime(),
        latestDailyMemoTime = latestDailyMemoTime?.toLocalTime(),
        thisWeekCount = thisWeekCount.toBoundedInt("this_week_count"),
        lastWeekCount = lastWeekCount.toBoundedInt("last_week_count"),
        thisMonthCount = thisMonthCount.toBoundedInt("this_month_count"),
        lastMonthCount = lastMonthCount.toBoundedInt("last_month_count"),
        thisYearCount = thisYearCount.toBoundedInt("this_year_count"),
        lastYearCount = lastYearCount.toBoundedInt("last_year_count"),
        tagCounts =
            tagCounts.map { tag ->
                MemoTagCount(name = tag.name, count = tag.count.toBoundedInt("tag_count"))
            },
    )
}

private fun SessionCivilTime.toLocalTime(): LocalTime =
    LocalTime.of(hour.toInt(), minute.toInt(), second.toInt())

private fun ULong.toBoundedInt(field: String): Int {
    require(this <= Int.MAX_VALUE.toULong()) { "$field is outside the Kotlin count range" }
    return toInt()
}
