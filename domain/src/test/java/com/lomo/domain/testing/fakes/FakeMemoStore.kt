package com.lomo.domain.testing.fakes

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.lomo.domain.model.DailyReviewCandidateBoundary
import com.lomo.domain.model.DailyReviewCandidateCursor
import com.lomo.domain.model.DailyReviewCandidatePage
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoContentAnalysis
import com.lomo.domain.model.MemoQuerySpec
import com.lomo.domain.model.MemoSortOption
import com.lomo.domain.model.MemoStatistics
import com.lomo.domain.model.MemoStatisticsCalculator
import com.lomo.domain.model.MemoTagCount
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class FakeMemoStore(
    initialMemos: List<Memo> = emptyList(),
    initialDeletedMemos: List<Memo> = emptyList(),
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    data class SavedMemo(
        val content: String,
        val timestamp: Long,
        val geoLocation: String?,
    )

    data class UpdatedMemo(
        val memo: Memo,
        val newContent: String,
    )

    data class PageRequest(
        val limit: Int,
        val offset: Int,
    )

    private val memos = MutableStateFlow(initialMemos)
    private val deletedMemos = MutableStateFlow(initialDeletedMemos)
    private val syncing = MutableStateFlow(false)
    private val dailyReviewCandidateSnapshots = mutableMapOf<String, List<DailyReviewCandidateSnapshotRow>>()

    val savedMemos = mutableListOf<SavedMemo>()
    val updatedMemos = mutableListOf<UpdatedMemo>()
    val deletedMemoRequests = mutableListOf<Memo>()
    val restoredRevisionRequests = mutableListOf<RestoredRevisionRequest>()
    val pageRequests = mutableListOf<PageRequest>()

    var refreshMemosCallCount = 0
        private set
    var getAllMemosListCallCount = 0
        private set
    var clearTrashCallCount = 0
        private set

    fun setMemos(value: List<Memo>) {
        memos.value = value
    }

    fun setDeletedMemos(value: List<Memo>) {
        deletedMemos.value = value
    }

    fun setSyncing(value: Boolean) {
        syncing.value = value
    }

    fun currentMemos(): List<Memo> = memos.value

    internal fun observeAllActiveMemos(): Flow<List<Memo>> {
        getAllMemosListCallCount += 1
        return memos.asStateFlow()
    }

    internal fun observeActiveMemosInDateRange(
        startDate: LocalDate?,
        endDate: LocalDate?,
    ): Flow<List<Memo>> =
        memos.map { values ->
            values.filter { memo ->
                val date = memo.localDate
                (startDate == null || date?.isBefore(startDate) == false) &&
                    (endDate == null || date?.isAfter(endDate) == false)
            }
        }

    internal fun observeGalleryActiveMemos(): Flow<List<Memo>> =
        memos.map { values -> values.filter { memo -> memo.imageUrls.isNotEmpty() } }

    internal suspend fun recentActiveMemos(limit: Int): List<Memo> =
        memos.value.sortedByDescending(Memo::timestamp).take(limit)

    internal suspend fun activeMemoPage(
        limit: Int,
        offset: Int,
    ): List<Memo> {
        pageRequests += PageRequest(limit = limit, offset = offset)
        return if (limit <= 0 || offset < 0) {
            emptyList()
        } else {
            memos.value.drop(offset).take(limit)
        }
    }

    internal suspend fun activeMemoCount(): Int = memos.value.size

    internal suspend fun captureDailyReviewCandidateBoundary(): DailyReviewCandidateBoundary? {
        val snapshot = memos.value
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
        pageRequests += PageRequest(limit = limit, offset = offset)
        val rows = snapshot.drop(offset).take(limit)
        val ids = rows.map { row -> row.id }
        val nextPosition = offset + ids.size
        val lastRow = rows.lastOrNull()
        return DailyReviewCandidatePage(
            ids = ids,
            nextCursor =
                if (ids.isEmpty() || lastRow == null) {
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

    internal suspend fun defaultMainListIndexInWindow(
        id: String,
        limit: Int,
    ): Int? {
        if (limit <= 0) {
            return null
        }
        val index =
            memos.value
                .take(limit)
                .indexOfFirst { memo -> memo.id == id }
        return index.takeIf { value -> value >= 0 }
    }

    internal suspend fun findActiveMemoById(id: String): Memo? = memos.value.firstOrNull { memo -> memo.id == id }

    internal fun mainListPagingSourceFor(
        spec: MemoQuerySpec,
    ): PagingSource<Int, Memo> =
        InMemoryMemoPagingSource(memos.value.matching(spec))

    internal fun observeMainListCount(
        spec: MemoQuerySpec,
    ): Flow<Int> = memos.map { values -> values.matching(spec).size }

    internal fun observeSyncing(): Flow<Boolean> = syncing.asStateFlow()

    internal suspend fun recordMemoRefresh() {
        refreshMemosCallCount += 1
    }

    internal suspend fun addSavedMemo(
        content: String,
        timestamp: Long,
        geoLocation: String?,
    ): Memo {
        savedMemos += SavedMemo(content = content, timestamp = timestamp, geoLocation = geoLocation)
        val date = Instant.ofEpochMilli(timestamp).atZone(zoneId).toLocalDate()
        val memo =
            Memo(
                id = timestamp.toString(),
                timestamp = timestamp,
                content = content,
                rawContent = content,
                dateKey = date.toString(),
                localDate = date,
                geoLocation = geoLocation,
            )
        memos.value = memos.value + memo
        return memo
    }

    internal suspend fun replaceMemoContent(
        memo: Memo,
        newContent: String,
    ) {
        updatedMemos += UpdatedMemo(memo = memo, newContent = newContent)
        memos.value =
            memos.value.map { existing ->
                if (existing.id == memo.id) {
                    existing.copy(content = newContent, rawContent = newContent)
                } else {
                    existing
                }
            }
    }

    internal suspend fun moveMemoToDeleted(memo: Memo) {
        deletedMemoRequests += memo
        memos.value = memos.value.filterNot { existing -> existing.id == memo.id }
        deletedMemos.value = deletedMemos.value + memo.copy(isDeleted = true)
    }

    internal suspend fun restoreMemoRevision(
        currentMemo: Memo,
        revisionId: String,
    ) {
        restoredRevisionRequests += RestoredRevisionRequest(currentMemo = currentMemo, revisionId = revisionId)
    }

    internal suspend fun updateMemoPinned(
        memoId: String,
        pinned: Boolean,
    ) {
        memos.value =
            memos.value.map { memo ->
                if (memo.id == memoId) memo.copy(isPinned = pinned) else memo
            }
    }

    internal suspend fun taggedMemoPage(
        tag: String,
        limit: Int,
        offset: Int,
    ): List<Memo> =
        if (limit <= 0 || offset < 0) {
            emptyList()
        } else {
            memos.value
                .filter { memo -> tag in memo.tags }
                .drop(offset)
                .take(limit)
        }

    internal fun observeTaggedMemoPage(
        tag: String,
        limit: Int,
        offset: Int,
    ): Flow<List<Memo>> =
        memos.map { values ->
            if (limit <= 0 || offset < 0) {
                emptyList()
            } else {
                values
                    .filter { memo -> tag in memo.tags }
                    .drop(offset)
                    .take(limit)
            }
        }

    internal fun observeMemoCount(): Flow<Int> = memos.map { values -> values.size }

    internal fun observeMemoTimestamps(): Flow<List<Long>> =
        memos.map { values -> values.map(Memo::timestamp) }

    internal fun observeMemoCountByDate(): Flow<Map<String, Int>> =
        memos.map { values -> values.groupingBy(Memo::dateKey).eachCount() }

    internal fun observeTagCounts(): Flow<List<MemoTagCount>> =
        memos.map { values ->
            values
                .flatMap(Memo::tags)
                .groupingBy { tag -> tag }
                .eachCount()
                .map { (tag, count) -> MemoTagCount(name = tag, count = count) }
        }

    internal fun observeActiveDayCount(): Flow<Int> =
        memos.map { values -> values.mapNotNull(Memo::localDate).toSet().size }

    internal fun computeMemoStatistics(
        zone: ZoneId,
        today: LocalDate,
    ): MemoStatistics =
        MemoStatisticsCalculator.compute(
            memos =
                memos.value.map { memo ->
                    MemoStatisticsCalculator.projectMemo(timestamp = memo.timestamp, content = memo.content)
                },
            tagCounts = currentTagCounts(),
            zone = zone,
            today = today,
        )

    internal fun observeDeletedMemoPage(
        limit: Int,
        offset: Int,
    ): Flow<List<Memo>> =
        deletedMemos.map { values ->
            deletedMemoPage(limit = limit, offset = offset, values = values)
        }

    internal fun deletedMemoPage(
        limit: Int,
        offset: Int,
    ): List<Memo> = deletedMemoPage(limit = limit, offset = offset, values = deletedMemos.value)

    private fun deletedMemoPage(
        limit: Int,
        offset: Int,
        values: List<Memo>,
    ): List<Memo> =
        if (limit <= 0 || offset < 0) {
            emptyList()
        } else {
            values.drop(offset).take(limit)
        }

    internal suspend fun restoreDeletedMemo(memo: Memo) {
        deletedMemos.value = deletedMemos.value.filterNot { existing -> existing.id == memo.id }
        memos.value = memos.value + memo.copy(isDeleted = false)
    }

    internal suspend fun removeDeletedMemoPermanently(memo: Memo) {
        deletedMemos.value = deletedMemos.value.filterNot { existing -> existing.id == memo.id }
    }

    internal suspend fun removeAllDeletedMemos() {
        clearTrashCallCount += 1
        deletedMemos.value = emptyList()
    }

private data class DailyReviewCandidateSnapshotRow(
    val id: String,
    val isPinned: Boolean,
    val timestamp: Long,
)

data class RestoredRevisionRequest(
    val currentMemo: Memo,
    val revisionId: String,
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

    private fun List<Memo>.matching(
        spec: MemoQuerySpec,
    ): List<Memo> {
        val filtered =
            asSequence()
                .filter { memo ->
                    spec.normalizedQueryText.isBlank() ||
                        memo.content.contains(spec.normalizedQueryText, ignoreCase = true)
                }
                .filter { memo ->
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
        MemoContentAnalysis(
            hasAttachment = imageUrls.isNotEmpty(),
            tags = tags,
            imageUrls = imageUrls,
        )

    private fun currentTagCounts(): List<MemoTagCount> =
        memos.value
            .flatMap(Memo::tags)
            .groupingBy { tag -> tag }
            .eachCount()
            .map { (tag, count) -> MemoTagCount(name = tag, count = count) }
}

private class InMemoryMemoPagingSource(
    private val snapshot: List<Memo>,
) : PagingSource<Int, Memo>() {
    override fun getRefreshKey(state: PagingState<Int, Memo>): Int? = state.anchorPosition

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Memo> {
        val start = (params.key ?: 0).coerceAtLeast(0)
        val end = (start + params.loadSize).coerceAtMost(snapshot.size)
        val data = if (start >= snapshot.size) emptyList() else snapshot.subList(start, end)
        val prevKey = if (start == 0) null else (start - params.loadSize).coerceAtLeast(0)
        val nextKey = if (end >= snapshot.size) null else end
        return LoadResult.Page(data = data, prevKey = prevKey, nextKey = nextKey)
    }
}
