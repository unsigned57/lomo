package com.lomo.data.repository

import androidx.paging.PagingSource
import com.lomo.data.engine.media.MediaSyncEdgeAdapter
import com.lomo.data.engine.media.PendingMediaStageRegistry
import com.lomo.data.engine.store.StoreMemoCommand
import com.lomo.data.engine.store.StoreMemoCommandKind
import com.lomo.data.engine.store.StoreMemoFilters
import com.lomo.data.engine.store.StoreMemoQuery
import com.lomo.data.engine.store.StorePagingSource
import com.lomo.data.engine.store.StorePort
import com.lomo.data.engine.store.toDomainMemo
import com.lomo.data.reminder.MemoMutationReminderScheduler
import com.lomo.domain.model.DailyReviewCandidateBoundary
import com.lomo.domain.model.DailyReviewCandidateCursor
import com.lomo.domain.model.DailyReviewCandidatePage
import com.lomo.domain.model.EngineReadiness
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoFilterCriterion
import com.lomo.domain.model.MemoQuerySpec
import com.lomo.domain.model.MemoStatistics
import com.lomo.domain.model.MemoStatisticsCalculator
import com.lomo.domain.model.MemoStatisticsMemoProjection
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
import com.lomo.domain.model.MemoRevision
import com.lomo.domain.model.MemoRevisionOrigin
import com.lomo.domain.model.MemoRevisionLifecycleState
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

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
            registerInvalidation = { source -> invalidation.register(source) },
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
            registerInvalidation = { source: PagingSource<*, *> -> invalidation.register(source) },
        )
    }

    override fun getMainListCountFlow(spec: MemoQuerySpec): Flow<Int> =
        invalidation.ticks.map {
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
    private val pendingStages: PendingMediaStageRegistry = PendingMediaStageRegistry(),
    private val syncEdge: MediaSyncEdgeAdapter? = null,
) : MemoMutationRepository {
    override suspend fun refreshMemos() {
        mutate {
            invalidation.setSyncing(true)
            try {
                port.startRebuild(batchSize = 64)
                invalidation.bump()
            } finally {
                invalidation.setSyncing(false)
            }
        }
    }

    override suspend fun saveMemo(
        content: String,
        timestamp: Long,
        geoLocation: String?,
    ): Memo {
        return mutate {
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
                    )
                } catch (error: Exception) {
                    // B5: re-stage so draft retry can takePlans again under a new opId.
                    reStagePromotes(promotes)
                    throw error
                }
            // D8: journal committed media only after memo-bound promote succeeds.
            journalPromotedMedia(promotes.map { it.finalRelativePath })
            invalidation.bump()
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
        mutate {
            val snap = port.getMemo(memo.id) ?: error("memo not found: ${memo.id}")
            val opId = UUID.randomUUID().toString()
            val destinations = markdownAttachmentDestinations(newContent)
            val promotes = pendingStages.takePlansForDestinations(destinations, opId)
            try {
                port.applyMemoCommand(
                    StoreMemoCommand(
                        operationId = opId,
                        kind = StoreMemoCommandKind.Update,
                        memoId = memo.id,
                        expectedRevision = snap.summary.contentRevision,
                        expectedFingerprint = snap.summary.fileFingerprint,
                        content = newContent,
                        pendingPromotes = promotes,
                    ),
                )
            } catch (error: Exception) {
                reStagePromotes(promotes)
                throw error
            }
            journalPromotedMedia(promotes.map { it.finalRelativePath })
            invalidation.bump()
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
        mutate {
            val snap = port.getMemo(memo.id) ?: return@mutate
            port.applyMemoCommand(
                StoreMemoCommand(
                    operationId = UUID.randomUUID().toString(),
                    kind = StoreMemoCommandKind.Delete,
                    memoId = memo.id,
                    expectedRevision = snap.summary.contentRevision,
                    expectedFingerprint = snap.summary.fileFingerprint,
                ),
            )
            invalidation.bump()
            reminderScheduler.cancelForMemo(memo.id)
        }
    }

    override suspend fun restoreMemoRevision(
        currentMemo: Memo,
        revisionId: String,
    ) {
        mutate {
            val snap = port.getMemo(currentMemo.id) ?: error("memo not found: ${currentMemo.id}")
            port.applyMemoCommand(
                StoreMemoCommand(
                    operationId = UUID.randomUUID().toString(),
                    kind = StoreMemoCommandKind.HistoryRestore,
                    memoId = currentMemo.id,
                    expectedRevision = snap.summary.contentRevision,
                    expectedFingerprint = snap.summary.fileFingerprint,
                    content = revisionId,
                ),
            )
            invalidation.bump()
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
        mutate {
            val snap = port.getMemo(memoId) ?: return@mutate
            port.applyMemoCommand(
                StoreMemoCommand(
                    operationId = UUID.randomUUID().toString(),
                    kind = if (pinned) StoreMemoCommandKind.Pin else StoreMemoCommandKind.Unpin,
                    memoId = memoId,
                    expectedRevision = snap.summary.contentRevision,
                    expectedFingerprint = snap.summary.fileFingerprint,
                    pin = pinned,
                ),
            )
            invalidation.bump()
        }
    }

    /**
     * Every memo mutation is admitted by the workspace lease before it touches the store.
     *
     * Admission is registered, not merely checked, so a switch cannot begin between this call and
     * the command reaching the engine.
     */
    private suspend fun <T> mutate(block: suspend () -> T): T =
        writeLease.withWrite { withContext(Dispatchers.IO) { block() } }
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
            registerInvalidation = { source: PagingSource<*, *> -> invalidation.register(source) },
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
        combine(invalidation.ticks, readiness.readiness) { _, engineReadiness ->
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
) : MemoTrashRepository {
    override fun getDeletedMemosPagingSource(): PagingSource<Int, Memo> =
        StoreIntKeyPagingSource(
            port,
            StoreMemoQuery(filters = StoreMemoFilters(trashOnly = true, includeTrash = true)),
        )

    override suspend fun restoreMemo(memo: Memo) {
        mutate {
            val snap = port.getMemo(memo.id) ?: return@mutate
            port.applyMemoCommand(
                StoreMemoCommand(
                    operationId = UUID.randomUUID().toString(),
                    kind = StoreMemoCommandKind.Restore,
                    memoId = memo.id,
                    expectedRevision = snap.summary.contentRevision,
                    expectedFingerprint = snap.summary.fileFingerprint,
                ),
            )
            invalidation.bump()
        }
    }

    override suspend fun deletePermanently(memo: Memo) {
        // Permanent delete: delete again from trash (store treats delete as trash; permanent is
        // workspace fact removal via another delete after trash — fail closed if not trashed).
        mutate {
            val snap = port.getMemo(memo.id) ?: return@mutate
            port.applyMemoCommand(
                StoreMemoCommand(
                    operationId = UUID.randomUUID().toString(),
                    kind = StoreMemoCommandKind.PermanentDelete,
                    memoId = memo.id,
                    expectedRevision = snap.summary.contentRevision,
                    expectedFingerprint = snap.summary.fileFingerprint,
                ),
            )
            invalidation.bump()
        }
    }

    override suspend fun clearTrash() {
        mutate {
            val trashQuery = StoreMemoQuery(filters = StoreMemoFilters(trashOnly = true, includeTrash = true))
            val ids = walkStorePages(port, trashQuery).toList()
            for (item in ids) {
                port.applyMemoCommand(
                    StoreMemoCommand(
                        operationId = UUID.randomUUID().toString(),
                        kind = StoreMemoCommandKind.PermanentDelete,
                        memoId = item.memoId,
                        expectedRevision = item.contentRevision,
                        expectedFingerprint = item.fileFingerprint,
                    ),
                )
            }
            invalidation.bump()
        }
    }

    /** Trash mutations are admitted by the same workspace lease as memo mutations. */
    private suspend fun <T> mutate(block: suspend () -> T): T =
        writeLease.withWrite { withContext(Dispatchers.IO) { block() } }
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
                port.startRebuild(batchSize = 64)
                invalidation.bump()
            } finally {
                invalidation.setSyncing(false)
            }
        }
    }
}

/** Invalidation bus replacing Room Flow emissions after cutover. */
class StoreInvalidationBus {
    private val _ticks = MutableStateFlow(0L)
    val ticks: Flow<Long> = _ticks
    private val _syncing = MutableStateFlow(false)
    val syncing: Flow<Boolean> = _syncing
    private val pagingSources = mutableSetOf<PagingSource<*, *>>()

    fun register(source: PagingSource<*, *>) {
        synchronized(pagingSources) {
            pagingSources += source
        }
        source.registerInvalidatedCallback {
            synchronized(pagingSources) {
                pagingSources -= source
            }
        }
    }

    fun bump() {
        val sources = synchronized(pagingSources) { pagingSources.toList() }
        sources.forEach { it.invalidate() }
        _ticks.value = _ticks.value + 1L
    }

    fun setSyncing(value: Boolean) {
        _syncing.value = value
    }
}


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
                hasTodo = hasTodo,
                hasAttachment = hasAttachment,
                hasUrl = hasUrl,
            ),
    )
}
