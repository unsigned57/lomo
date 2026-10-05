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
import com.lomo.domain.usecase.DefaultDispatcherProvider
import com.lomo.domain.usecase.DispatcherProvider
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
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
    private val dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
) : MemoQueryRepository {
    private val pendingMainListFocusIdentity = AtomicReference<String?>(null)
    private val liveMainListSource = AtomicReference<PagingSource<String, Memo>?>(null)

    override fun getGalleryMemosPagingSource(): PagingSource<String, Memo> =
        StorePagingSource(
            port = port,
            query = StoreMemoQuery(filters = StoreMemoFilters(hasAttachment = true)),
            dispatcherProvider = dispatcherProvider,
            registerInvalidation = { source ->
                invalidation.register(source)
            },
        )

    override suspend fun getRecentMemos(limit: Int): List<Memo> =
        withContext(dispatcherProvider.io) {
            port
                .queryMemos(StoreMemoQuery(), cursor = null, pageSize = limit.coerceAtLeast(1))
                .items
                .map { summary ->
                    summary.toDomainMemo(
                        body = summary.bodyPreview,
                        contentKind = MemoContentKind.Preview,
                    )
                }
        }

    override fun observeListProjection(): Flow<com.lomo.domain.model.MemoProjectionPublication> =
        invalidation.publications.map { publication ->
            com.lomo.domain.model.MemoProjectionPublication(coreRevision = publication.coreRevision)
        }

    override suspend fun getMemoCount(): Int =
        withContext(dispatcherProvider.io) {
            if (!readiness.mount.value.admitsProjectionReads) 0
            else {
                val count = port.queryCount(StoreMemoQuery())
                require(count in 0..Int.MAX_VALUE.toLong()) { "memo_count is outside the Kotlin count range" }
                count.toInt()
            }
        }

    override suspend fun getDailyReviewCandidateBoundary(): DailyReviewCandidateBoundary? =
        withContext(dispatcherProvider.io) {
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
        withContext(dispatcherProvider.io) {
            queryDailyReviewPage(boundary, cursor, limit)
        }

    override suspend fun getDailyReviewVisibleUnseenPage(
        cursor: DailyReviewCandidateCursor?,
        limit: Int,
    ): DailyReviewCandidatePage =
        withContext(dispatcherProvider.io) {
            // The visible-unseen pass is a fresh bounded query. Its separate cursor prevents a
            // frozen candidate cursor (whose fingerprint includes the boundary) from being reused
            // against the current collection.
            require(limit > 0) { "Daily Review page limit must be positive" }
            val page =
                port.queryMemos(
                    StoreMemoQuery(),
                    cursor?.run { com.lomo.data.engine.store.StorePageCursor(token) },
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
                cursor?.let { com.lomo.data.engine.store.StorePageCursor(it.token) },
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
            consumeRefreshIdentity = { pendingMainListFocusIdentity.getAndSet(null) },
            dispatcherProvider = dispatcherProvider,
            registerInvalidation = { source ->
                invalidation.register(source)
                liveMainListSource.set(source)
            },
        )

    override suspend fun rankInDefaultMainList(id: String): Int? =
        rankInMainListQuery(MemoQuerySpec(), id)

    override suspend fun rankInMainListQuery(
        spec: MemoQuerySpec,
        id: String,
    ): Int? =
        withContext(dispatcherProvider.io) {
            val page =
                port.queryMemos(
                    query = spec.toStoreQuery(),
                    cursor = null,
                    pageSize = 1,
                    startMemoId = id,
                )
            val first = page.items.firstOrNull() ?: return@withContext null
            if (first.memoId != id) {
                return@withContext null
            }
            require(page.itemsBefore in 0..Int.MAX_VALUE.toLong()) {
                "main-list rank ${page.itemsBefore} does not fit a Kotlin index"
            }
            page.itemsBefore.toInt()
        }

    override fun reanchorMainListToIdentity(id: String) {
        pendingMainListFocusIdentity.set(id)
        liveMainListSource.get()?.invalidate()
    }

    override suspend fun getMemoById(id: String): Memo? =
        withContext(dispatcherProvider.io) {
            port.getMemo(id)?.toDomainMemo()
        }

    override fun isSyncing(): Flow<Boolean> = invalidation.syncing

}


class StoreMemoMutationRepository
    internal constructor(
        private val port: StorePort,
        private val reminderScheduler: MemoMutationReminderScheduler,
        private val writeLease: WorkspaceMutationLease,
        private val invalidation: StoreInvalidationBus,
        private val diagnostics: EngineDiagnosticsRecorder,
        private val mediaCommit: MemoMediaCommitPipeline,
        private val dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
    ) : MemoMutationRepository {
    override suspend fun refreshMemos() {
        withContext(dispatcherProvider.io) {
            val started = TimeSource.Monotonic.markNow()
            invalidation.setSyncing(true)
            try {
                port.startRebuild(batchSize = 64)
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
            port.commitDocumentMutation(mutation)
            reminderScheduler.syncForMemo(mutation.facts.memoId)
        }
    }

    private fun recordRefreshFailure(
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
        attempt: com.lomo.domain.model.MemoCreateAttempt,
    ): Memo {
        return mutate("memo.create") {
            val opId = attempt.operationId.value
            // Transfer this draft's stage leases to the frozen operation and build the promote plans.
            val promotes = mediaCommit.plansForOperation(opId, attempt.draftId)
            val commit =
                port.applyMemoCommand(
                    StoreMemoCommand(
                        operationId = opId,
                        kind = StoreMemoCommandKind.Create,
                        memoId = "",
                        expectedRevision = 0L,
                        chronologyEpochMs = attempt.timestampMillis,
                        content = attempt.content,
                        pendingPromotes = promotes,
                    ),
                    onPublication = {},
                )
            val memo =
                port.getMemo(commit.memoId)?.toDomainMemo()
                    ?: error("create commit succeeded but get_memo returned null for ${commit.memoId}")
            mediaCommit.publishCommittedMedia(promotes)
            reminderScheduler.syncForMemo(memo.id)
            memo
        }
    }

    override suspend fun updateMemo(
        attempt: com.lomo.domain.model.MemoUpdateAttempt,
    ) {
        mutate("memo.update") {
            val baseline = attempt.snapshot.baseline
            val opId = attempt.operationId.value
            val promotes = mediaCommit.plansForOperation(opId, attempt.draftId)
            port.applyMemoCommand(
                StoreMemoCommand(
                    operationId = opId,
                    kind = StoreMemoCommandKind.Update,
                    memoId = baseline.memoId,
                    expectedRevision = baseline.contentRevision,
                    expectedFingerprint = baseline.fileFingerprint,
                    content = attempt.content,
                    pendingPromotes = promotes,
                ),
                onPublication = {},
            )
            mediaCommit.publishCommittedMedia(promotes)
            reminderScheduler.syncForMemo(baseline.memoId)
        }
    }

    override suspend fun deleteMemo(
        memo: Memo,
        operationId: com.lomo.domain.model.MemoOperationId,
    ) {
        mutate("memo.delete") {
            val (expectedRevision, expectedFingerprint) = memo.requireSessionCasBaseline()
            val reminderIds = memo.reminders.map { it.reference.opaqueId }.toSet()
            port.applyMemoCommand(
                StoreMemoCommand(
                    operationId = operationId.value,
                    kind = StoreMemoCommandKind.Delete,
                    memoId = memo.id,
                    expectedRevision = expectedRevision,
                    expectedFingerprint = expectedFingerprint,
                ),
                onPublication = {},
            )
            reminderScheduler.cancelForMemo(memo.id, reminderIds)
        }
    }

    override suspend fun restoreMemoRevision(
        currentMemo: Memo,
        revision: MemoRevision,
        operationId: com.lomo.domain.model.MemoOperationId,
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
            port.applyMemoCommand(
                StoreMemoCommand(
                    operationId = operationId.value,
                    kind = StoreMemoCommandKind.HistoryRestore,
                    memoId = currentMemo.id,
                    expectedRevision = expectedRevision,
                    expectedFingerprint = expectedFingerprint,
                    content = revision.memoContent,
                    historyRevision = historyRevisionNumber(revision),
                ),
                onPublication = {},
            )
            val restored = port.getMemo(currentMemo.id)?.toDomainMemo()
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
        operationId: com.lomo.domain.model.MemoOperationId,
    ) {
        mutate("memo.pin") {
            val snap = port.getMemo(memoId) ?: return@mutate
            port.applyMemoCommand(
                StoreMemoCommand(
                    operationId = operationId.value,
                    kind = if (pinned) StoreMemoCommandKind.Pin else StoreMemoCommandKind.Unpin,
                    memoId = memoId,
                    expectedRevision = snap.summary.contentRevision,
                    expectedFingerprint = snap.summary.fileFingerprint,
                    pin = pinned,
                ),
                onPublication = {},
            )
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
                withContext(dispatcherProvider.io) {
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
        } catch (other: kotlinx.coroutines.CancellationException) {
            throw other
        } catch (other: Exception) {
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
    private val dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
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
                invalidation.register(source)
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
                    dispatcherProvider = dispatcherProvider,
                    registerInvalidation = { source ->
                        invalidation.register(source)
                    },
                )
            MemoSearchMode.Fuzzy ->
                SessionSearchPagingSource(
                    session = session,
                    filters = MemoQuerySpec.fromFilter(queryText = query, filter = filter).toStoreQuery().filters,
                    queryEpoch = searchEpoch.incrementAndGet().toULong(),
                    text = query,
                    dispatcherProvider = dispatcherProvider,
                    registerInvalidation = { source ->
                        invalidation.register(source)
                    },
                )
        }
}

internal class StoreMemoStatisticsRepository(
    port: StorePort,
    private val session: SessionNativeBridge,
    private val invalidation: StoreInvalidationBus,
    private val readiness: EngineReadinessRepository,
    applicationScope: CoroutineScope,
    private val dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
) : MemoStatisticsRepository {
    /**
     * A publication is one projection snapshot. Share that bounded read across every sidebar
     * consumer and stop collecting when the application has no observers.
     */
    private val sharedSidebarProjection: Flow<com.lomo.data.engine.store.StoreSidebarProjection> =
        combine(
            invalidation.publications,
            readiness.mount,
        ) { _, mount ->
            if (mount.admitsProjectionReads) {
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
        withContext(dispatcherProvider.io) {
            loadMemoStatistics(zone = zone, today = today, admitsReads = readiness.mount.value.admitsProjectionReads)
        }

    override fun observeMemoStatistics(
        zone: ZoneId,
        today: LocalDate,
    ): Flow<MemoStatistics> =
        combine(invalidation.publications, readiness.mount) { _, mount ->
            loadMemoStatistics(zone = zone, today = today, admitsReads = mount.admitsProjectionReads)
        }.flowOn(dispatcherProvider.io)

    override fun getMemoCountFlow(): Flow<Int> =
        activeSidebarProjection().map { it.memoCount }.flowOn(dispatcherProvider.io)

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
            }.flowOn(dispatcherProvider.io)

    override fun getMemoCountByDateFlow(): Flow<Map<String, Int>> =
        activeSidebarProjection()
            .map { projection -> projection.dateCounts.associate { it.date to it.count } }
            .flowOn(dispatcherProvider.io)

    override fun getTagCountsFlow(): Flow<List<MemoTagCount>> =
        activeSidebarProjection()
            .map { projection -> projection.tagCounts.map { MemoTagCount(it.name, it.count) } }
            .flowOn(dispatcherProvider.io)

    override fun getActiveDayCount(): Flow<Int> =
        getMemoCountByDateFlow().map { it.size }

    private fun activeSidebarProjection(): Flow<com.lomo.data.engine.store.StoreSidebarProjection> =
        sharedSidebarProjection

    private fun loadMemoStatistics(
        zone: ZoneId,
        today: LocalDate,
        admitsReads: Boolean,
    ): MemoStatistics {
        if (!admitsReads) {
            return MemoStatistics.empty(today)
        }
        return withEngineFailureConversion {
            session
                .sessionStatistics(
                    SessionStatisticsSnapshot(
                        zone = zone.id,
                        asOf = today.toSessionCivilDate(),
                    ),
                ).toDomainMemoStatistics()
        }
    }
}

private const val STATS_PROJECTION_STOP_TIMEOUT_MILLIS = 5_000L

class StoreMemoTrashRepository(
    private val port: StorePort,
    private val writeLease: WorkspaceMutationLease,
    private val invalidation: StoreInvalidationBus,
    private val readiness: EngineReadinessRepository,
    private val reminderScheduler: MemoMutationReminderScheduler,
    private val mediaRepository: MediaRepository,
    private val dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
) : MemoTrashRepository {
    override fun getDeletedMemosPagingSource(): PagingSource<String, Memo> =
        StorePagingSource(
            port = port,
            query = StoreMemoQuery(filters = StoreMemoFilters(trashOnly = true, includeTrash = true)),
            dispatcherProvider = dispatcherProvider,
            registerInvalidation = { source ->
                invalidation.register(source)
            },
        )

    override suspend fun restoreMemo(
        memo: Memo,
        operationId: com.lomo.domain.model.MemoOperationId,
    ) {
        mutate {
            val (expectedRevision, expectedFingerprint) = memo.requireSessionCasBaseline()
            port.applyMemoCommand(
                StoreMemoCommand(
                    operationId = operationId.value,
                    kind = StoreMemoCommandKind.Restore,
                    memoId = memo.id,
                    expectedRevision = expectedRevision,
                    expectedFingerprint = expectedFingerprint,
                ),
                onPublication = {},
            )
            reminderScheduler.syncForMemo(memo.id)
        }
    }

    override suspend fun deletePermanently(
        memo: Memo,
        operationId: com.lomo.domain.model.MemoOperationId,
    ) {
        // Rust verifies the active Markdown source and matching durable trash record, removes the
        // source bytes first, then removes the record and publishes the rebuilt projection commit.
        mutate {
            val (expectedRevision, expectedFingerprint) = memo.requireSessionCasBaseline()
            val reminderIds = memo.reminders.map { it.reference.opaqueId }.toSet()
            port.applyMemoCommand(
                StoreMemoCommand(
                    operationId = operationId.value,
                    kind = StoreMemoCommandKind.PermanentDelete,
                    memoId = memo.id,
                    expectedRevision = expectedRevision,
                    expectedFingerprint = expectedFingerprint,
                ),
                onPublication = {},
            )
            reminderScheduler.cancelForMemo(memo.id, reminderIds)
            mediaRepository.runOrphanSweepAtOperationBoundary()
        }
    }

    override suspend fun clearTrash(operationId: com.lomo.domain.model.MemoOperationId) {
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
            val commit = port.permanentDeleteMany(operationId.value, targets)
            commit.deleted.forEach { deleted ->
                reminderScheduler.cancelForMemo(deleted.memoId, deleted.reminderIds.toSet())
            }
            mediaRepository.runOrphanSweepAtOperationBoundary()
        }
    }

    /** Trash mutations are admitted by the same workspace lease as memo mutations. */
    private suspend fun <T> mutate(block: suspend () -> T): T =
        writeLease.withWrite {
            withContext(dispatcherProvider.io) {
                readiness.requireProjectionReadable()
                block()
            }
        }
}

private fun EngineReadinessRepository.requireProjectionReadable() {
    if (!mount.value.admitsProjectionReads) {
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
        val cursorRevision = cursor?.run { revisionId.substringAfterLast(':').takeIf { it.all(Char::isDigit) } }
        val page =
            withEngineFailureConversion {
                session.sessionListHistory(
                    memo.id,
                    cursorRevision,
                    limit.coerceIn(1, 256).toUInt(),
                )
            }
        val currentRevision = port.getMemo(memo.id)?.run { summary.contentRevision }
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
    private val dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
) : WorkspaceStateResolver {
    override suspend fun rebuildFromCurrentWorkspace() {
        withContext(dispatcherProvider.io) {
            invalidation.setSyncing(true)
            try {
                port.startRebuild(batchSize = 64)
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
 * revisions promote to [StoreInvalidationScope.Full], and every paging consumer invalidates.
 * Scopes remain diagnostic labels on [StoreProjectionPublication]. The clock is generational:
 * [reanchor] binds it to the workspace whose store it tracks.
 */
class StoreInvalidationBus(
    private val eventSequenceRequiresFullInvalidate: (lastSeen: Long, incoming: Long) -> Boolean =
        ::transcribedEventSequenceRequiresFullInvalidate,
) {
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
    private val pagingSources = mutableSetOf<PagingSource<*, *>>()
    private var lastGeneration = 0L
    private var lastCoreRevision = 0L
    private var lastEventSequence: Long? = 0L

    fun register(source: PagingSource<*, *>) {
        synchronized(publicationLock) {
            pagingSources += source
        }
        source.registerInvalidatedCallback {
            synchronized(publicationLock) {
                pagingSources.remove(source)
            }
        }
    }

    /**
     * Binds the publication clock to a newly committed workspace generation. High-water may be
     * lower than the previous workspace; sequence is unknown until the next store commit.
     */
    fun reanchor(generation: Long, highWaterRevision: Long) {
        require(highWaterRevision >= 0L) { "Store reanchor high-water revision must be non-negative" }
        val sources =
            synchronized(publicationLock) {
                require(generation > lastGeneration) {
                    "Invalidation generation must advance (last=$lastGeneration incoming=$generation)"
                }
                lastGeneration = generation
                lastCoreRevision = highWaterRevision
                lastEventSequence = null
                _publications.value =
                    StoreProjectionPublication(
                        coreRevision = highWaterRevision,
                        eventSequence = null,
                        scopes = setOf(StoreInvalidationScope.Full),
                    )
                pagingSources.toList()
            }
        sources.forEach(PagingSource<*, *>::invalidate)
    }

    fun publish(commit: com.lomo.data.engine.store.StoreMemoCommit) {
        publishBatch(listOf(commit))
    }

    /**
     * Accepts a later commit for a projection that already published mid-flight. Advances the
     * publication clock so observers reread, without invalidating paging sources again.
     */
    fun confirm(commit: com.lomo.data.engine.store.StoreMemoCommit) {
        synchronized(publicationLock) {
            val decision = acceptCommit(commit) ?: return
            _publications.value =
                StoreProjectionPublication(
                    coreRevision = lastCoreRevision,
                    eventSequence = lastEventSequence,
                    scopes = decision,
                )
        }
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
                pagingSources.toList()
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
                    // opaque page cursors. A cursor therefore becomes invalid for *all* query
                    // projections. Scope labels stay on the publication as diagnostics only.
                    pagingSources.toList()
                }
            }
        sources.forEach(PagingSource<*, *>::invalidate)
    }

    fun publishRebuild(highWaterRevision: Long) {
        if (highWaterRevision <= 0L) {
            throw engineCommandFailure(
                category = EngineFailureCategory.INTERNAL,
                code = PROTOCOL_FAILURE_CODE,
                retryDisposition = EngineRetryDisposition.AFTER_USER_ACTION,
                diagnostic = "store rebuild returned a non-positive high-water revision ($highWaterRevision)",
            )
        }
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
                    pagingSources.toList()
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
        // A non-positive revision or sequence is not a programming bug in Kotlin: the owner shipped
        // a receipt that violates the publication protocol, so surface it as a structured
        // ProtocolFailure instead of an IllegalArgumentException that callers cannot classify.
        if (coreRevision <= 0L || eventSequence <= 0L) {
            throw engineCommandFailure(
                category = EngineFailureCategory.INTERNAL,
                code = PROTOCOL_FAILURE_CODE,
                retryDisposition = EngineRetryDisposition.AFTER_USER_ACTION,
                diagnostic =
                    "store commit receipt violated the publication protocol " +
                        "(coreRevision=$coreRevision, eventSequence=$eventSequence)",
            )
        }
        if (coreRevision < lastCoreRevision) return null
        if (coreRevision == lastCoreRevision) {
            val knownSequence = lastEventSequence
            if (knownSequence == null || eventSequence <= knownSequence) return null
            lastEventSequence = eventSequence
            return setOf(StoreInvalidationScope.Full)
        }
        val previousSequence = lastEventSequence
        val sequenceGap =
            previousSequence != null &&
                eventSequenceRequiresFullInvalidate(previousSequence, eventSequence)
        val revisionContiguous =
            previousSequence != null &&
                lastCoreRevision != Long.MAX_VALUE &&
                coreRevision == lastCoreRevision + 1L
        lastCoreRevision = coreRevision
        lastEventSequence = eventSequence
        return if (!sequenceGap && revisionContiguous && !idempotentReplay && scopes.isNotEmpty()) {
            scopes.toSet()
        } else {
            setOf(StoreInvalidationScope.Full)
        }
    }

    /**
     * Re-anchors the publication clock after the projection itself was replaced (archive import).
     *
     * A replaced projection is a new incarnation: its high-water may be *lower* than the retired
     * one, so comparing revisions would wrongly reject every later commit as stale. Re-anchoring
     * makes the next publication a full re-read instead of a size comparison.
     *
     * The generation clock is minted exclusively by the session's activation counter; a
     * projection replacement must not consume a generation the next activation will still
     * issue, so this re-anchor only resets the revision/sequence watermarks.
     */
    fun reanchorProjection(highWaterRevision: Long) {
        require(highWaterRevision >= 0L) {
            "Re-anchored projection high-water revision must be non-negative"
        }
        val sources =
            synchronized(publicationLock) {
                lastCoreRevision = highWaterRevision
                lastEventSequence = null
                _publications.value =
                    StoreProjectionPublication(
                        coreRevision = highWaterRevision,
                        eventSequence = null,
                        scopes = setOf(StoreInvalidationScope.Full),
                    )
                pagingSources.toList()
            }
        sources.forEach(PagingSource<*, *>::invalidate)
    }

    internal companion object {
        /** Structured code for a receipt that violates the projection publication protocol. */
        const val PROTOCOL_FAILURE_CODE = "store_protocol_failure"
    }
}

internal fun transcribedEventSequenceRequiresFullInvalidate(
    lastSeen: Long,
    incoming: Long,
): Boolean {
    if (incoming == lastSeen) return false
    if (lastSeen == Long.MAX_VALUE) return true
    return incoming != lastSeen + 1L
}

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
 * Maps a domain list query onto a store query. Continuity is a string cursor owned by Rust.
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
