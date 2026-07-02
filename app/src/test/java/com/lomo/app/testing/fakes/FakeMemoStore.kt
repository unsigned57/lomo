package com.lomo.app.testing.fakes

import androidx.paging.PagingSource
import androidx.paging.PagingSource.LoadParams
import androidx.paging.PagingSource.LoadResult
import androidx.paging.PagingState
import com.lomo.domain.model.DailyReviewCandidateBoundary
import com.lomo.domain.model.DailyReviewCandidateCursor
import com.lomo.domain.model.DailyReviewCandidatePage
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoContentAnalysis
import com.lomo.domain.model.MemoListFilter
import com.lomo.domain.model.MemoQuerySpec
import com.lomo.domain.model.MemoSortOption
import com.lomo.domain.model.MemoStatistics
import com.lomo.domain.model.MemoStatisticsCalculator
import com.lomo.domain.model.MemoTagCount
import com.lomo.domain.usecase.MemoContentAnalyzer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/**
 * Hand-written in-memory fake for memo repository ports used by app/test ViewModel specs.
 *
 * Holds two backing lists — active memos and trash — as [MutableStateFlow]s so the same
 * collaborator can drive read flows and accept mutating commands. Mutating calls
 * are recorded as plain counters so tests can assert ordering or call counts
 * without resorting to MockK verifiers.
 *
 * Extend the public mutation helpers (e.g. [setActiveMemos], [setDeletedMemos])
 * as more specs adopt this fake. Avoid adding `mockk(relaxed = true)` overrides
 * on top of an instance of this class — extend the fake itself instead.
 */
class FakeMemoStore(
    initialActive: List<Memo> = emptyList(),
    initialDeleted: List<Memo> = emptyList(),
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    private val activeMemos = MutableStateFlow(initialActive)
    private val deletedMemos = MutableStateFlow(initialDeleted)
    private val syncing = MutableStateFlow(false)
    private val dailyReviewCandidateSnapshots = mutableMapOf<String, List<DailyReviewCandidateSnapshotRow>>()

    var saveMemoCallCount: Int = 0
        private set
    var updateMemoCallCount: Int = 0
        private set
    var deleteMemoCallCount: Int = 0
        private set
    var refreshMemosCallCount: Int = 0
        private set
    var clearTrashCallCount: Int = 0
        private set
    var getMainListPagingSourceCallCount: Int = 0
    var getAllMemosListCallCount: Int = 0
    val mainListCalls = mutableListOf<MainListCall>()
    val mainListPageLoads = mutableListOf<MainListPageLoad>()
    var mainListPageProvider: ((spec: MemoQuerySpec) -> List<Memo>)? = null
    var mainListLoadDelayMillis: Long = 0L
    var mainListLoadDelayMillisProvider: ((spec: MemoQuerySpec) -> Long)? = null
    var mainListLoadFailure: Throwable? = null
    var mainListLoadFailureProvider: ((spec: MemoQuerySpec) -> Throwable?)? = null

    fun resetCallCounts() {
        saveMemoCallCount = 0
        updateMemoCallCount = 0
        deleteMemoCallCount = 0
        refreshMemosCallCount = 0
        clearTrashCallCount = 0
        beforeGetMemoById = null
    }

    fun setActiveMemos(memos: List<Memo>) {
        activeMemos.value = memos
    }

    fun setDeletedMemos(memos: List<Memo>) {
        deletedMemos.value = memos
    }

    fun setSyncing(value: Boolean) {
        syncing.value = value
    }

    fun currentActiveMemos(): List<Memo> = activeMemos.value

    fun currentDeletedMemos(): List<Memo> = deletedMemos.value

    internal fun observeAllActiveMemos(): Flow<List<Memo>> {
        getAllMemosListCallCount += 1
        return activeMemos.asStateFlow()
    }

    internal fun observeActiveMemosInDateRange(
        startDate: LocalDate?,
        endDate: LocalDate?,
    ): Flow<List<Memo>> =
        activeMemos.map { memos ->
            memos.filter { memo ->
                val date = memo.localDate ?: return@filter startDate == null && endDate == null
                (startDate == null || !date.isBefore(startDate)) &&
                    (endDate == null || !date.isAfter(endDate))
            }
        }

    internal fun observeGalleryActiveMemos(): Flow<List<Memo>> =
        activeMemos.map { memos -> memos.filter { it.imageUrls.isNotEmpty() } }

    internal suspend fun recentActiveMemos(limit: Int): List<Memo> =
        activeMemos.value.sortedByDescending(Memo::timestamp).take(limit)

    internal suspend fun activeMemoPage(limit: Int, offset: Int): List<Memo> {
        val list = activeMemos.value
        if (offset < 0 || offset >= list.size || limit <= 0) return emptyList()
        return list.subList(offset, (offset + limit).coerceAtMost(list.size))
    }

    var getMemoCountFailure: Throwable? = null

    internal suspend fun activeMemoCount(): Int {
        getMemoCountFailure?.let { throw it }
        return activeMemos.value.size
    }

    internal suspend fun captureDailyReviewCandidateBoundary(): DailyReviewCandidateBoundary? {
        val snapshot = activeMemos.value
        val anchor = snapshot.firstOrNull() ?: return null
        val rows =
            snapshot.map { memo ->
                DailyReviewCandidateSnapshotRow(
                    id = memo.id,
                    isPinned = memo.isPinned,
                    timestamp = memo.timestamp,
                )
            }
        val token = rows.stableDailyReviewCandidateToken()
        dailyReviewCandidateSnapshots[token] = rows
        return DailyReviewCandidateBoundary(
            isPinned = anchor.isPinned,
            timestamp = anchor.timestamp,
            id = anchor.id,
            token = token,
            observedCount = snapshot.size,
        )
    }

    internal suspend fun dailyReviewCandidatePage(
        boundary: DailyReviewCandidateBoundary,
        cursor: DailyReviewCandidateCursor?,
        limit: Int,
    ): DailyReviewCandidatePage {
        if (limit <= 0) {
            return DailyReviewCandidatePage(ids = emptyList(), nextCursor = cursor)
        }
        val snapshot = dailyReviewCandidateSnapshots[boundary.token] ?: emptyList()
        val offset = cursor?.position ?: 0
        val rows = snapshot.drop(offset).take(limit)
        val nextPosition = offset + rows.size
        val lastRow = rows.lastOrNull()
        return DailyReviewCandidatePage(
            ids = rows.map { row -> row.id },
            nextCursor =
                if (rows.isEmpty() || lastRow == null) {
                    cursor
                } else {
                    DailyReviewCandidateCursor(
                        isPinned = lastRow.isPinned,
                        timestamp = lastRow.timestamp,
                        id = lastRow.id,
                        token = boundary.token,
                        position = nextPosition,
                    )
                },
        )
    }

    var recordedQuery: String? = null
        private set
    var recordedSpec: MemoQuerySpec? = null
        private set

    var getMemoByIdOverride: ((String) -> Memo?)? = null
    var beforeGetMemoById: (suspend (String) -> Unit)? = null

    internal suspend fun findActiveMemoById(id: String): Memo? {
        beforeGetMemoById?.invoke(id)
        getMemoByIdOverride?.let { return it(id) }
        return activeMemos.value.find { it.id == id }
    }

    internal suspend fun defaultMainListIndexInWindow(
        id: String,
        limit: Int,
    ): Int? {
        if (limit <= 0) {
            return null
        }
        val index =
            activeMemos.value
                .take(limit)
                .indexOfFirst { memo -> memo.id == id }
        return index.takeIf { value -> value >= 0 }
    }

    internal fun mainListPagingSourceFor(spec: MemoQuerySpec): PagingSource<Int, Memo> {
        getMainListPagingSourceCallCount += 1
        recordedQuery = spec.normalizedQueryText
        recordedSpec = spec
        mainListCalls += MainListCall(spec = spec)
        return InMemoryMemoPagingSource(
            snapshot = mainListPageProvider?.invoke(spec)
                ?: manualMainListMemos
                ?: activeMemos.value.matching(spec),
            loads = mainListPageLoads,
            delayMillis = mainListLoadDelayMillisProvider?.invoke(spec) ?: mainListLoadDelayMillis,
            failure = mainListLoadFailureProvider?.invoke(spec) ?: mainListLoadFailure,
        )
    }

    internal fun observeMainListCount(spec: MemoQuerySpec): Flow<Int> {
        recordedQuery = spec.normalizedQueryText
        recordedSpec = spec
        return activeMemos.map { memos -> memos.matching(spec).size }
    }

    internal fun observeSyncing(): Flow<Boolean> = syncing.asStateFlow()

    var refreshMemosFailure: Throwable? = null

    internal suspend fun recordMemoRefresh() {
        refreshMemosFailure?.let { throw it }
        refreshMemosCallCount += 1
    }

    internal suspend fun addSavedMemo(
        content: String,
        timestamp: Long,
        geoLocation: String?,
    ): Memo {
        saveMemoCallCount += 1
        val memo =
            Memo(
                id = timestamp.toString(),
                timestamp = timestamp,
                content = content,
                rawContent = content,
                dateKey = Instant.ofEpochMilli(timestamp).atZone(zoneId).toLocalDate().toString(),
                localDate = Instant.ofEpochMilli(timestamp).atZone(zoneId).toLocalDate(),
                geoLocation = geoLocation,
            )
        activeMemos.value = activeMemos.value + memo
        return memo
    }

    var deleteMemoFailure: Throwable? = null
    var updateMemoFailure: Throwable? = null
    val restoredRevisionRequests = mutableListOf<RestoredRevisionRequest>()

    internal suspend fun replaceMemoContent(
        memo: Memo,
        newContent: String,
    ) {
        updateMemoFailure?.let { throw it }
        updateMemoCallCount += 1
        activeMemos.value =
            activeMemos.value.map { existing ->
                if (existing.id == memo.id) existing.copy(content = newContent, rawContent = newContent) else existing
            }
    }

    internal suspend fun moveMemoToDeleted(memo: Memo) {
        deleteMemoFailure?.let { throw it }
        deleteMemoCallCount += 1
        activeMemos.value = activeMemos.value.filterNot { it.id == memo.id }
        deletedMemos.value = deletedMemos.value + memo.copy(isDeleted = true)
    }

    internal suspend fun restoreMemoRevision(
        currentMemo: Memo,
        revisionId: String,
    ) {
        restoredRevisionRequests += RestoredRevisionRequest(currentMemo = currentMemo, revisionId = revisionId)
    }

    var setMemoPinnedFailure: Throwable? = null

    internal suspend fun updateMemoPinned(
        memoId: String,
        pinned: Boolean,
    ) {
        setMemoPinnedFailure?.let { throw it }
        activeMemos.value =
            activeMemos.value.map { memo -> if (memo.id == memoId) memo.copy(isPinned = pinned) else memo }
    }

    val tagPageCalls = mutableListOf<TagPageCall>()
    val trashPageCalls = mutableListOf<TrashPageCall>()

    internal suspend fun taggedMemoPage(
        tag: String,
        limit: Int,
        offset: Int,
    ): List<Memo> {
        tagPageCalls += TagPageCall(tag = tag, limit = limit, offset = offset)
        return if (limit <= 0 || offset < 0) {
            emptyList()
        } else {
            activeMemos.value
                .filter { memo -> tag in memo.tags }
                .drop(offset)
                .take(limit)
        }
    }

    internal fun observeTaggedMemoPage(
        tag: String,
        limit: Int,
        offset: Int,
    ): Flow<List<Memo>> {
        tagPageCalls += TagPageCall(tag = tag, limit = limit, offset = offset)
        return activeMemos.map { memos ->
            if (limit <= 0 || offset < 0) {
                emptyList()
            } else {
                memos
                    .filter { memo -> tag in memo.tags }
                    .drop(offset)
                    .take(limit)
            }
        }
    }

    internal fun observeMemoCount(): Flow<Int> = activeMemos.map { it.size }

    internal fun observeMemoTimestamps(): Flow<List<Long>> = activeMemos.map { memos -> memos.map(Memo::timestamp) }

    internal fun observeMemoCountByDate(): Flow<Map<String, Int>> =
        activeMemos.map { memos -> memos.groupingBy(Memo::dateKey).eachCount() }

    internal fun observeTagCounts(): Flow<List<MemoTagCount>> =
        activeMemos.map { memos ->
            memos
                .flatMap(Memo::tags)
                .groupingBy { it }
                .eachCount()
                .map { (tag, count) -> MemoTagCount(tag, count) }
        }

    internal fun observeActiveDayCount(): Flow<Int> =
        activeMemos.map { memos -> memos.mapNotNull(Memo::localDate).toSet().size }

    internal fun computeMemoStatistics(
        zone: ZoneId,
        today: LocalDate,
    ): MemoStatistics =
        MemoStatisticsCalculator.compute(
            memos =
                activeMemos.value.map { memo ->
                    MemoStatisticsCalculator.projectMemo(timestamp = memo.timestamp, content = memo.content)
                },
            tagCounts = currentTagCounts(),
            zone = zone,
            today = today,
        )

    internal fun observeDeletedMemoPage(
        limit: Int,
        offset: Int,
    ): Flow<List<Memo>> {
        trashPageCalls += TrashPageCall(limit = limit, offset = offset)
        return deletedMemos.map { memos ->
            deletedMemoPage(limit = limit, offset = offset, memos = memos)
        }
    }

    internal fun deletedMemoPage(
        limit: Int,
        offset: Int,
    ): List<Memo> {
        trashPageCalls += TrashPageCall(limit = limit, offset = offset)
        return deletedMemoPage(limit = limit, offset = offset, memos = deletedMemos.value)
    }

    private fun deletedMemoPage(
        limit: Int,
        offset: Int,
        memos: List<Memo>,
    ): List<Memo> =
        if (limit <= 0 || offset < 0) {
            emptyList()
        } else {
            memos.drop(offset).take(limit)
        }

    var deletePermanentlyOverride: (suspend (Memo) -> Unit)? = null
    var restoreMemoOverride: (suspend (Memo) -> Unit)? = null

    internal suspend fun restoreDeletedMemo(memo: Memo) {
        restoreMemoOverride?.invoke(memo) ?: run {
            deletedMemos.value = deletedMemos.value.filterNot { it.id == memo.id }
            activeMemos.value = activeMemos.value + memo.copy(isDeleted = false)
        }
    }

    internal suspend fun removeDeletedMemoPermanently(memo: Memo) {
        deletePermanentlyOverride?.invoke(memo) ?: run {
            deletedMemos.value = deletedMemos.value.filterNot { it.id == memo.id }
        }
    }



    fun verifyRefreshMemosCalled(exactly: Int = 1) {
        if (refreshMemosCallCount != exactly) {
            throw AssertionError("Expected refreshMemos to be called $exactly times, but was $refreshMemosCallCount")
        }
    }

    fun verifyRefreshMemosNotCalled() {
        if (refreshMemosCallCount != 0) {
            throw AssertionError("Expected refreshMemos not to be called, but was $refreshMemosCallCount")
        }
    }

    fun verifyGetAllMemosListNotCalled() {
        if (getAllMemosListCallCount != 0) {
            throw AssertionError(
                "Expected getAllMemosList not to be called, but was called $getAllMemosListCallCount times",
            )
        }
    }

    fun resetRecordedCalls() {
        recordedQuery = null
        recordedSpec = null
        refreshMemosCallCount = 0
        getMainListPagingSourceCallCount = 0
        getAllMemosListCallCount = 0
        mainListCalls.clear()
        mainListPageLoads.clear()
        mainListPageProvider = null
        mainListLoadDelayMillis = 0L
        mainListLoadDelayMillisProvider = null
        mainListLoadFailure = null
        mainListLoadFailureProvider = null
        tagPageCalls.clear()
        trashPageCalls.clear()
        manualMainListMemos = null
        saveMemoCallCount = 0
        updateMemoCallCount = 0
        deleteMemoCallCount = 0
        clearTrashCallCount = 0
        beforeGetMemoById = null
    }

    private var manualMainListMemos: List<Memo>? = null
    fun setMainListPagingSource(memos: List<Memo>) {
        manualMainListMemos = memos
    }

    fun verifyMainListPagingSourceCalled(query: String? = null, filter: MemoListFilter? = null) {
        if (getMainListPagingSourceCallCount == 0) {
            throw AssertionError("Expected getMainListPagingSource to be called, but was not")
        }
        val expectedSpec =
            if (query != null || filter != null) {
                MemoQuerySpec.fromFilter(queryText = query.orEmpty(), filter = filter ?: MemoListFilter())
            } else {
                null
            }
        query?.let {
            if (recordedQuery != it) {
                throw AssertionError("Expected query '$it', but was '$recordedQuery'")
            }
        }
        filter?.let {
            if (recordedSpec != expectedSpec) {
                throw AssertionError("Expected spec $expectedSpec, but was $recordedSpec")
            }
        }
    }

    var deleteResult: String? = null

    data class TagPageCall(
        val tag: String,
        val limit: Int,
        val offset: Int,
    )

    data class TrashPageCall(
        val limit: Int,
        val offset: Int,
    )

data class MainListCall(
    val spec: MemoQuerySpec,
) {
    constructor(query: String, filter: MemoListFilter) : this(
        spec = MemoQuerySpec.fromFilter(queryText = query, filter = filter),
    )

    val query: String
        get() = spec.normalizedQueryText

    val filter: MemoListFilter
        get() =
            MemoListFilter(
                sortOption = spec.sort.option,
                sortAscending = spec.sort.ascending,
                startDate = spec.dateRange.startDate,
                endDate = spec.dateRange.endDate,
            )
}

data class RestoredRevisionRequest(
    val currentMemo: Memo,
    val revisionId: String,
)

    data class MainListPageLoad(
        val key: Int?,
        val loadSize: Int,
    )

    internal suspend fun removeAllDeletedMemos() {
        clearTrashCallCount += 1
        deletedMemos.value = emptyList()
    }

    private fun List<Memo>.matching(
        spec: MemoQuerySpec,
    ): List<Memo> {
        val filtered =
            asSequence()
                .filter { memo ->
                    spec.normalizedQueryText.isBlank() ||
                        memo.content.contains(spec.normalizedQueryText, ignoreCase = true)
                }.filter { memo ->
                    val date = memo.localDate
                    when {
                        spec.dateRange.startDate != null &&
                            (date == null || date.isBefore(spec.dateRange.startDate)) -> false
                        spec.dateRange.endDate != null &&
                            (date == null || date.isAfter(spec.dateRange.endDate)) -> false
                        else -> true
                    }
                }.filter { memo -> spec.matches(memo.queryAnalysis()) }
                .toList()
        val selector: (Memo) -> Long =
            when (spec.sort.option) {
                MemoSortOption.CREATED_TIME -> Memo::timestamp
                MemoSortOption.UPDATED_TIME -> Memo::updatedAt
            }
        val comparator =
            if (spec.sort.ascending) {
                compareByDescending<Memo> { memo -> memo.isPinned }.thenBy(selector).thenBy { memo -> memo.id }
            } else {
                compareByDescending<Memo> { memo -> memo.isPinned }.thenByDescending(selector).thenByDescending { memo -> memo.id }
            }
        return filtered.sortedWith(comparator)
    }

    private fun Memo.queryAnalysis(): MemoContentAnalysis =
        MemoContentAnalyzer.analyze(content)

    private fun currentTagCounts(): List<MemoTagCount> =
        activeMemos.value
            .flatMap(Memo::tags)
            .groupingBy { tag -> tag }
            .eachCount()
            .map { (tag, count) -> MemoTagCount(name = tag, count = count) }

    private data class DailyReviewCandidateSnapshotRow(
        val id: String,
        val isPinned: Boolean,
        val timestamp: Long,
    )

    private fun List<DailyReviewCandidateSnapshotRow>.stableDailyReviewCandidateToken(): String {
        val hash =
            fold(1125899906842597L) { accumulator, row ->
                val pinnedValue = if (row.isPinned) 1L else 0L
                accumulator
                    .times(31)
                    .plus(row.id.hashCode().toLong())
                    .times(31)
                    .plus(row.timestamp)
                    .times(31)
                    .plus(pinnedValue)
            }
        return "fake-daily-review-$size-$hash"
    }
}

private class InMemoryMemoPagingSource(
    private val snapshot: List<Memo>,
    private val loads: MutableList<FakeMemoStore.MainListPageLoad>,
    private val delayMillis: Long,
    private val failure: Throwable?,
) : PagingSource<Int, Memo>() {
    override val jumpingSupported: Boolean = true

    override fun getRefreshKey(state: PagingState<Int, Memo>): Int? = state.anchorPosition

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Memo> {
        loads += FakeMemoStore.MainListPageLoad(key = params.key, loadSize = params.loadSize)
        if (delayMillis > 0L) {
            delay(delayMillis)
        }
        failure?.let { throwable -> return LoadResult.Error(throwable) }
        val start = (params.key ?: 0).coerceAtLeast(0)
        val end = (start + params.loadSize).coerceAtMost(snapshot.size)
        val slice = if (start >= snapshot.size) emptyList() else snapshot.subList(start, end)
        val prevKey = if (start == 0) null else (start - params.loadSize).coerceAtLeast(0)
        val nextKey = if (end >= snapshot.size) null else end
        return LoadResult.Page(slice, prevKey, nextKey)
    }
}
