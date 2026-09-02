package com.lomo.data.repository

import androidx.paging.PagingSource
import com.lomo.data.engine.media.MediaSyncEdgeAdapter
import com.lomo.data.engine.media.PendingMediaStageRegistry
import com.lomo.data.engine.store.StoreInvalidationScope
import com.lomo.data.engine.store.StoreMemoCommand
import com.lomo.data.engine.store.StoreMemoCommandKind
import com.lomo.data.engine.store.StoreMemoFilters
import com.lomo.data.engine.store.StoreMemoQuery
import com.lomo.data.engine.store.StoreMemoSort
import com.lomo.data.engine.store.StoreMemoSortField
import com.lomo.data.engine.store.StorePagingSource
import com.lomo.data.engine.store.StorePort
import com.lomo.data.engine.store.StoreSortDirection
import com.lomo.data.engine.store.toDomainMemo
import com.lomo.data.reminder.MemoMutationReminderScheduler
import com.lomo.data.engine.engineCommandFailure
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
import com.lomo.domain.model.MemoRevision
import com.lomo.domain.model.MemoFilterCriterion
import com.lomo.domain.model.MemoQuerySpec
import com.lomo.domain.model.MemoStatistics
import com.lomo.domain.model.MemoStatisticsCalculator
import com.lomo.domain.model.MemoStatisticsMemoProjection
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
import com.lomo.domain.repository.WorkspaceMutationLease
import com.lomo.domain.repository.WorkspaceStateResolver
import com.lomo.domain.model.MemoRevisionCursor
import com.lomo.domain.model.MemoRevisionPage
import com.lomo.domain.model.MemoRevisionOrigin
import com.lomo.domain.model.MemoRevisionLifecycleState
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
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
                    port.getMemo(summary.memoId)?.toDomainMemo() ?: summary.toDomainMemo(summary.bodyPreview)
                }
        }

    override suspend fun getMemosPage(
        limit: Int,
        offset: Int,
    ): List<Memo> =
        withContext(Dispatchers.IO) {
            if (limit <= 0 || offset < 0) return@withContext emptyList()
            // Keyset owner has no offset; walk pages until offset covered (bounded UI windows only).
            collectOffsetWindow(limit = limit, offset = offset)
        }

    override suspend fun getMemoCount(): Int =
        withContext(Dispatchers.IO) {
            if (readiness.readiness.value !is EngineReadiness.Ready) 0
            else port.sidebarProjection().memoCount
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
            val page =
                port.queryMemos(
                    StoreMemoQuery(),
                    cursor?.token?.let { com.lomo.data.engine.store.StorePageCursor(it) },
                    limit.coerceAtLeast(1),
                )
            DailyReviewCandidatePage(
                ids = page.items.map { it.memoId },
                nextCursor =
                    page.nextCursor?.let { next ->
                        DailyReviewCandidateCursor(
                            isPinned = page.items.lastOrNull()?.isPinned ?: false,
                            timestamp = page.items.lastOrNull()?.createdAtMs ?: 0L,
                            id = page.items.lastOrNull()?.memoId.orEmpty(),
                            token = next.encoded,
                        )
                    },
            )
        }

    override fun getMainListPagingSource(spec: MemoQuerySpec): PagingSource<Int, Memo> {
        // Domain still uses Int keys in some paths; adapt String store cursor via wrapper.
        return StoreIntKeyPagingSource(
            port,
            spec.toStoreQuery(),
            registerInvalidation = { source: PagingSource<*, *> ->
                invalidation.register(source, setOf(StoreInvalidationScope.MemoList))
            },
        )
    }

    override fun getMainListCountFlow(spec: MemoQuerySpec): Flow<Int> =
        invalidation.publicationsFor(StoreInvalidationScope.MemoList, StoreInvalidationScope.Stats).map {
            if (readiness.readiness.value !is EngineReadiness.Ready) 0
            else if (spec.toStoreQuery() == StoreMemoQuery()) port.sidebarProjection().memoCount
            else walkStorePages(port, spec.toStoreQuery()).count()
        }.flowOn(Dispatchers.IO)

    override suspend fun getDefaultMainListIndexInWindow(
        id: String,
        limit: Int,
    ): Int? =
        withContext(Dispatchers.IO) {
            val window = getMemosPage(limit = limit, offset = 0)
            window.indexOfFirst { it.id == id }.takeIf { it >= 0 }
        }

    override suspend fun getMemoById(id: String): Memo? =
        withContext(Dispatchers.IO) {
            port.getMemo(id)?.toDomainMemo()
        }

    override fun isSyncing(): Flow<Boolean> = invalidation.syncing

    private fun collectOffsetWindow(limit: Int, offset: Int): List<Memo> {
        val collected = ArrayList<Memo>(limit)
        var skipped = 0
        for (item in walkStorePages(StoreMemoQuery())) {
            if (skipped < offset) {
                skipped++
                continue
            }
            collected += item.toDomainMemo(item.bodyPreview)
            if (collected.size >= limit) {
                return collected
            }
        }
        return collected
    }

    private fun walkStorePages(query: StoreMemoQuery): Sequence<com.lomo.data.engine.store.StoreMemoSummary> =
        walkStorePages(port, query)
}


class StoreMemoMutationRepository(
    private val port: StorePort,
    private val queryRepository: MemoQueryRepository,
    private val reminderScheduler: MemoMutationReminderScheduler,
    private val writeLease: WorkspaceMutationLease,
    private val invalidation: StoreInvalidationBus,
    private val diagnostics: EngineDiagnosticsRecorder,
    private val pendingStages: PendingMediaStageRegistry = PendingMediaStageRegistry(),
    private val syncEdge: MediaSyncEdgeAdapter? = null,
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
                recordRefreshFailure(started, failure)
                throw failure
            } finally {
                invalidation.setSyncing(false)
            }
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
            val destinations = markdownAttachmentDestinations(content)
            val promotes = pendingStages.takePlansForDestinations(destinations, opId)
            val commit =
                try {
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
                } catch (error: Exception) {
                    // B5: re-stage so draft retry can takePlans again under a new opId.
                    reStagePromotes(promotes)
                    throw error
                }
            // D8: journal committed media only after memo-bound promote succeeds.
            invalidation.publish(commit)
            journalPromotedMedia(promotes.map { it.finalRelativePath })
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
            val snap = port.getMemo(memo.id) ?: error("memo not found: ${memo.id}")
            val opId = UUID.randomUUID().toString()
            val destinations = markdownAttachmentDestinations(newContent)
            val promotes = pendingStages.takePlansForDestinations(destinations, opId)
            val commit =
                try {
                    port.applyMemoCommand(
                    StoreMemoCommand(
                        operationId = opId,
                        kind = StoreMemoCommandKind.Update,
                        memoId = memo.id,
                        expectedRevision = snap.summary.contentRevision,
                        expectedFingerprint = snap.summary.fileFingerprint,
                        content = newContent,
                        chronologyEpochMs = System.currentTimeMillis(),
                        pendingPromotes = promotes,
                    ),
                    )
                } catch (error: Exception) {
                    reStagePromotes(promotes)
                    throw error
                }
            invalidation.publish(commit)
            journalPromotedMedia(promotes.map { it.finalRelativePath })
            reminderScheduler.syncForMemo(memo.id)
        }
    }

    private fun reStagePromotes(promotes: List<com.lomo.data.engine.media.MediaPromotePlan>) {
        for (plan in promotes) {
            pendingStages.put(plan.staged)
        }
    }

    private suspend fun journalPromotedMedia(finalRelativePaths: List<String>) {
        val edge = syncEdge ?: return
        for (path in finalRelativePaths) {
            val basename = path.substringAfterLast('/').substringAfterLast('\\')
            if (basename.isNotEmpty()) {
                edge.onCommittedMediaUpsert(basename)
            }
        }
    }

    override suspend fun deleteMemo(memo: Memo) {
        mutate("memo.delete") {
            val snap =
                port.getMemo(memo.id)
                    ?: throw engineCommandFailure(
                        category = EngineFailureCategory.VALIDATION,
                        code = "memo_identity_not_found",
                        retryDisposition = EngineRetryDisposition.NEVER,
                        diagnostic = "Cannot delete memo because it was not found: ${memo.id}",
                    )
            val commit =
                port.applyMemoCommand(
                    StoreMemoCommand(
                        operationId = UUID.randomUUID().toString(),
                        kind = StoreMemoCommandKind.Delete,
                        memoId = memo.id,
                        expectedRevision = snap.summary.contentRevision,
                        expectedFingerprint = snap.summary.fileFingerprint,
                    ),
                )
            invalidation.publish(commit)
            reminderScheduler.cancelForMemo(memo.id)
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
            val snap = port.getMemo(currentMemo.id) ?: error("memo not found: ${currentMemo.id}")
            val commit = port.applyMemoCommand(
                StoreMemoCommand(
                    operationId = UUID.randomUUID().toString(),
                    kind = StoreMemoCommandKind.HistoryRestore,
                    memoId = currentMemo.id,
                    expectedRevision = snap.summary.contentRevision,
                    expectedFingerprint = snap.summary.fileFingerprint,
                    content = revision.memoContent,
                    chronologyEpochMs = System.currentTimeMillis(),
                ),
            )
            invalidation.publish(commit)
            val restored = queryRepository.getMemoById(currentMemo.id)
            if (restored == null) {
                reminderScheduler.cancelForMemo(currentMemo.id)
            } else {
                reminderScheduler.syncForMemo(restored.id)
            }
        }
    }

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

/**
 * Lightweight markdown image/attachment destination extractor for promote matching.
 * Not a second Markdown owner — destinations are only used to select staged promote plans.
 * Full render IR remains Rust-owned.
 */
internal fun markdownAttachmentDestinations(content: String): List<String> {
    if (content.isEmpty()) return emptyList()
    val results = ArrayList<String>()
    var index = 0
    while (index < content.length) {
        val bang = content.indexOf("![", index)
        if (bang < 0) {
            index = content.length
        } else {
            val parsed = parseMarkdownImageDestination(content, bang)
            if (parsed == null) {
                index = content.length
            } else {
                if (parsed.destination.isNotEmpty() && isLocalAttachmentDestination(parsed.destination)) {
                    results += parsed.destination
                }
                index = parsed.nextIndex
            }
        }
    }
    return results
}

private data class MarkdownImageParse(
    val destination: String,
    val nextIndex: Int,
)

private fun parseMarkdownImageDestination(
    content: String,
    bang: Int,
): MarkdownImageParse? {
    val closeAlt = content.indexOf(']', bang + 2)
    if (closeAlt < 0) {
        return null
    }
    if (closeAlt + 1 >= content.length || content[closeAlt + 1] != '(') {
        return MarkdownImageParse(destination = "", nextIndex = closeAlt + 1)
    }
    val closeDest = content.indexOf(')', closeAlt + 2)
    if (closeDest < 0) {
        return null
    }
    val dest =
        content
            .substring(closeAlt + 2, closeDest)
            .trim()
            .substringBefore(' ')
            .trim()
    return MarkdownImageParse(destination = dest, nextIndex = closeDest + 1)
}

private fun isLocalAttachmentDestination(dest: String): Boolean =
    !dest.startsWith("http://", ignoreCase = true) &&
        !dest.startsWith("https://", ignoreCase = true) &&
        !dest.startsWith("data:", ignoreCase = true)

class StoreMemoSearchRepository(
    private val port: StorePort,
    private val invalidation: StoreInvalidationBus = StoreInvalidationBus(),
) : MemoSearchRepository {
    override fun getMemosByTagPagingSource(selection: TagSelection): PagingSource<Int, Memo> =
        StoreIntKeyPagingSource(
            port,
            StoreMemoQuery(
                filters =
                    StoreMemoFilters(
                        tag = selection.path.value,
                        tagSubtree = selection.mode == TagSelectionMode.Subtree,
                    ),
            ),
            registerInvalidation = { source: PagingSource<*, *> ->
                invalidation.register(
                    source,
                    setOf(StoreInvalidationScope.Search, StoreInvalidationScope.Tags),
                )
            },
        )
}

class StoreMemoStatisticsRepository(
    private val port: StorePort,
    private val invalidation: StoreInvalidationBus,
    private val readiness: EngineReadinessRepository,
) : MemoStatisticsRepository {
    override suspend fun getMemoStatistics(
        zone: ZoneId,
        today: LocalDate,
    ): MemoStatistics =
        withContext(Dispatchers.IO) {
            val memos = collectActiveSummaries()
            val tagCounts =
                memos.flatMap { it.tags }
                    .groupingBy { it }
                    .eachCount()
                    .map { (name, count) -> MemoTagCount(name, count) }
                    .sortedByDescending { it.count }
            MemoStatisticsCalculator.compute(
                memos =
                    memos.map {
                        val body = port.getMemo(it.memoId)?.body
                            ?: error("Store summary body missing for memo ${it.memoId}")
                        MemoStatisticsMemoProjection(
                            timestamp = it.createdAtMs,
                            wordCount = MemoStatisticsCalculator.projectMemo(it.createdAtMs, body).wordCount,
                            characterCount = body.length,
                        )
                    },
                tagCounts = tagCounts,
                zone = zone,
                today = today,
            )
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

    private fun collectActiveSummaries(): List<com.lomo.data.engine.store.StoreMemoSummary> =
        if (readiness.readiness.value is EngineReadiness.Ready) {
            walkStorePages(port, StoreMemoQuery()).toList()
        } else {
            emptyList()
        }

    private fun activeSidebarProjection(): Flow<com.lomo.data.engine.store.StoreSidebarProjection> =
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
        }
}

class StoreMemoTrashRepository(
    private val port: StorePort,
    private val writeLease: WorkspaceMutationLease,
    private val invalidation: StoreInvalidationBus,
    private val readiness: EngineReadinessRepository,
) : MemoTrashRepository {
    override fun getDeletedMemosPagingSource(): PagingSource<Int, Memo> =
        StoreIntKeyPagingSource(
            port,
            StoreMemoQuery(filters = StoreMemoFilters(trashOnly = true, includeTrash = true)),
            registerInvalidation = { source ->
                invalidation.register(source, setOf(StoreInvalidationScope.Trash))
            },
        )

    override suspend fun restoreMemo(memo: Memo) {
        mutate {
            val snap = port.getMemo(memo.id) ?: return@mutate
            val commit = port.applyMemoCommand(
                StoreMemoCommand(
                    operationId = UUID.randomUUID().toString(),
                    kind = StoreMemoCommandKind.Restore,
                    memoId = memo.id,
                    expectedRevision = snap.summary.contentRevision,
                    expectedFingerprint = snap.summary.fileFingerprint,
                ),
            )
            invalidation.publish(commit)
        }
    }

    override suspend fun deletePermanently(memo: Memo) {
        // Rust verifies the active Markdown source and matching durable trash record, removes the
        // source bytes first, then removes the record and publishes the rebuilt projection commit.
        mutate {
            val snap = port.getMemo(memo.id) ?: return@mutate
            val commit = port.applyMemoCommand(
                StoreMemoCommand(
                    operationId = UUID.randomUUID().toString(),
                    kind = StoreMemoCommandKind.PermanentDelete,
                    memoId = memo.id,
                    expectedRevision = snap.summary.contentRevision,
                    expectedFingerprint = snap.summary.fileFingerprint,
                ),
            )
            invalidation.publish(commit)
        }
    }

    override suspend fun clearTrash() {
        mutate {
            val trashQuery = StoreMemoQuery(filters = StoreMemoFilters(trashOnly = true, includeTrash = true))
            val ids = walkStorePages(port, trashQuery).map { it.memoId }.toList()
            val commits = ArrayList<com.lomo.data.engine.store.StoreMemoCommit>(ids.size)
            for (memoId in ids) {
                val snap = port.getMemo(memoId) ?: continue
                commits += port.applyMemoCommand(
                    StoreMemoCommand(
                        operationId = UUID.randomUUID().toString(),
                        kind = StoreMemoCommandKind.PermanentDelete,
                        memoId = snap.summary.memoId,
                        expectedRevision = snap.summary.contentRevision,
                        expectedFingerprint = snap.summary.fileFingerprint,
                    ),
                )
            }
            if (commits.isNotEmpty()) {
                invalidation.publishBatch(commits)
            }
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
 * History listing is supplied by the store adapter; restore uses [StoreMemoCommandKind.HistoryRestore].
 */
class StoreMemoVersionRepository(
    private val port: StorePort,
) : MemoVersionRepository {
    override suspend fun listMemoRevisions(
        memo: Memo,
        cursor: MemoRevisionCursor?,
        limit: Int,
    ): MemoRevisionPage {
        val cursorRevision = cursor?.revisionId?.substringAfterLast(':')?.takeIf { it.all(Char::isDigit) }
        val page = port.listMemoHistory(memo.id, cursorRevision, limit)
        val currentRevision = port.getMemo(memo.id)?.summary?.contentRevision
        val items = page.items.map { item ->
            val id = "${memo.id}-r${item.revision}"
            MemoRevision(
                revisionId = id,
                parentRevisionId = if (item.revision > 1) "${memo.id}-r${item.revision - 1}" else null,
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
                isCurrent = item.revision == currentRevision,
            )
        }
        return MemoRevisionPage(
            items = items,
            nextCursor = page.nextCursor?.let {
                MemoRevisionCursor(items.lastOrNull()?.createdAt ?: 0L, "${memo.id}:cursor:$it")
            },
        )
    }

    override suspend fun clearAllMemoSnapshots() = Unit
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
                    pagingSources
                        .filterValues { registered -> publication.scopes.affects(registered) }
                        .keys
                        .toList()
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
    ): Set<StoreInvalidationScope>? {
        require(commit.coreRevision > 0L) { "Store commit core revision must be positive" }
        require(commit.eventSequence > 0L) { "Store commit event sequence must be positive" }
        if (commit.coreRevision < lastCoreRevision) return null
        if (commit.coreRevision == lastCoreRevision) {
            val knownSequence = lastEventSequence
            if (knownSequence == null || commit.eventSequence <= knownSequence) return null
            error("Store commit advanced event sequence without advancing core revision")
        }
        val previousSequence = lastEventSequence
        if (previousSequence != null && commit.eventSequence <= previousSequence) {
            error("Store commit event sequence regressed while core revision advanced")
        }
        val contiguous =
            previousSequence != null &&
                lastCoreRevision != Long.MAX_VALUE &&
                previousSequence != Long.MAX_VALUE &&
                commit.coreRevision == lastCoreRevision + 1L &&
                commit.eventSequence == previousSequence + 1L
        lastCoreRevision = commit.coreRevision
        lastEventSequence = commit.eventSequence
        return if (contiguous && !commit.idempotentReplay && commit.scopes.isNotEmpty()) {
            commit.scopes.toSet()
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


private fun walkStorePages(
    port: StorePort,
    query: StoreMemoQuery,
): Sequence<com.lomo.data.engine.store.StoreMemoSummary> =
    sequence {
        var cursor: com.lomo.data.engine.store.StorePageCursor? = null
        val seenCursors = mutableSetOf<String>()
        while (true) {
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
private class StoreIntKeyPagingSource(
    private val port: StorePort,
    private val query: StoreMemoQuery,
    private val pageSize: Int = 30,
    registerInvalidation: ((PagingSource<*, *>) -> Unit)? = null,
) : PagingSource<Int, Memo>() {
    private val cursors = HashMap<Int, String?>()

    init {
        cursors[0] = null
        registerInvalidation?.invoke(this)
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Memo> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val pageIndex = params.key ?: 0
                val encoded = cursors[pageIndex]
                val cursor = encoded?.let { com.lomo.data.engine.store.StorePageCursor(it) }
                val page = port.queryMemos(query, cursor, pageSize.coerceAtLeast(1))
                val nextKey =
                    page.nextCursor?.encoded?.let { nextEncoded ->
                        val next = pageIndex + 1
                        cursors[next] = nextEncoded
                        next
                    }
                LoadResult.Page(
                    data = page.items.map { it.toDomainMemo(it.bodyPreview) },
                    prevKey = pageIndex.takeIf { it > 0 }?.minus(1),
                    nextKey = nextKey,
                )
            }.fold(
                onSuccess = { it },
                onFailure = { error ->
                    LoadResult.Error(error as? Exception ?: IllegalStateException(error))
                },
            )
        }

    override fun getRefreshKey(state: androidx.paging.PagingState<Int, Memo>): Int? = 0
}

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

private fun LocalDate.toStartOfDayEpochMillis(): Long =
    atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

private fun LocalDate.toExclusiveEndOfDayEpochMillis(): Long? =
    takeUnless { it == LocalDate.MAX }
        ?.plusDays(1)
        ?.atStartOfDay(ZoneId.systemDefault())
        ?.toInstant()
        ?.toEpochMilli()
