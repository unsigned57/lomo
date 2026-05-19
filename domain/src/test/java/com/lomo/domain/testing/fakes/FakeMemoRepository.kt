package com.lomo.domain.testing.fakes

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoListFilter
import com.lomo.domain.model.MemoSortOption
import com.lomo.domain.model.MemoTagCount
import com.lomo.domain.repository.MemoRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class FakeMemoRepository(
    initialMemos: List<Memo> = emptyList(),
    initialDeletedMemos: List<Memo> = emptyList(),
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) : MemoRepository {
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

    val savedMemos = mutableListOf<SavedMemo>()
    val updatedMemos = mutableListOf<UpdatedMemo>()
    val deletedMemoRequests = mutableListOf<Memo>()
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

    override fun getAllMemosList(): Flow<List<Memo>> {
        getAllMemosListCallCount += 1
        return memos.asStateFlow()
    }

    override fun getMemosByDateRange(
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

    override fun getGalleryMemosList(): Flow<List<Memo>> =
        memos.map { values -> values.filter { memo -> memo.imageUrls.isNotEmpty() } }

    override suspend fun getRecentMemos(limit: Int): List<Memo> =
        memos.value.sortedByDescending(Memo::timestamp).take(limit)

    override suspend fun getMemosPage(
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

    override suspend fun getMemoCount(): Int = memos.value.size

    override fun getMainListPagingSource(
        query: String,
        filter: MemoListFilter,
    ): PagingSource<Int, Memo> =
        InMemoryMemoPagingSource(memos.value.matching(query = query, filter = filter))

    override fun getMainListCountFlow(
        query: String,
        filter: MemoListFilter,
    ): Flow<Int> = memos.map { values -> values.matching(query = query, filter = filter).size }

    override fun isSyncing(): Flow<Boolean> = syncing.asStateFlow()

    override suspend fun refreshMemos() {
        refreshMemosCallCount += 1
    }

    override suspend fun saveMemo(
        content: String,
        timestamp: Long,
        geoLocation: String?,
    ) {
        savedMemos += SavedMemo(content = content, timestamp = timestamp, geoLocation = geoLocation)
        val date = Instant.ofEpochMilli(timestamp).atZone(zoneId).toLocalDate()
        memos.value =
            memos.value +
                Memo(
                    id = timestamp.toString(),
                    timestamp = timestamp,
                    content = content,
                    rawContent = content,
                    dateKey = date.toString(),
                    localDate = date,
                    geoLocation = geoLocation,
                )
    }

    override suspend fun updateMemo(
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

    override suspend fun deleteMemo(memo: Memo) {
        deletedMemoRequests += memo
        memos.value = memos.value.filterNot { existing -> existing.id == memo.id }
        deletedMemos.value = deletedMemos.value + memo.copy(isDeleted = true)
    }

    override suspend fun setMemoPinned(
        memoId: String,
        pinned: Boolean,
    ) {
        memos.value =
            memos.value.map { memo ->
                if (memo.id == memoId) memo.copy(isPinned = pinned) else memo
            }
    }

    override fun searchMemosList(query: String): Flow<List<Memo>> =
        memos.map { values -> values.filter { memo -> memo.content.contains(query, ignoreCase = true) } }

    override fun getMemosByTagList(tag: String): Flow<List<Memo>> =
        memos.map { values -> values.filter { memo -> tag in memo.tags } }

    override fun getMemoCountFlow(): Flow<Int> = memos.map { values -> values.size }

    override fun getMemoTimestampsFlow(): Flow<List<Long>> =
        memos.map { values -> values.map(Memo::timestamp) }

    override fun getMemoCountByDateFlow(): Flow<Map<String, Int>> =
        memos.map { values -> values.groupingBy(Memo::dateKey).eachCount() }

    override fun getTagCountsFlow(): Flow<List<MemoTagCount>> =
        memos.map { values ->
            values
                .flatMap(Memo::tags)
                .groupingBy { tag -> tag }
                .eachCount()
                .map { (tag, count) -> MemoTagCount(name = tag, count = count) }
        }

    override fun getActiveDayCount(): Flow<Int> =
        memos.map { values -> values.mapNotNull(Memo::localDate).toSet().size }

    override fun getDeletedMemosList(): Flow<List<Memo>> = deletedMemos.asStateFlow()

    override suspend fun restoreMemo(memo: Memo) {
        deletedMemos.value = deletedMemos.value.filterNot { existing -> existing.id == memo.id }
        memos.value = memos.value + memo.copy(isDeleted = false)
    }

    override suspend fun deletePermanently(memo: Memo) {
        deletedMemos.value = deletedMemos.value.filterNot { existing -> existing.id == memo.id }
    }

    override suspend fun clearTrash() {
        clearTrashCallCount += 1
        deletedMemos.value = emptyList()
    }

    private fun List<Memo>.matching(
        query: String,
        filter: MemoListFilter,
    ): List<Memo> {
        val filtered =
            asSequence()
                .filter { memo -> query.isBlank() || memo.content.contains(query, ignoreCase = true) }
                .filter { memo ->
                    val date = memo.localDate
                    when {
                        filter.startDate != null && (date == null || date.isBefore(filter.startDate)) -> false
                        filter.endDate != null && (date == null || date.isAfter(filter.endDate)) -> false
                        else -> true
                    }
                }.toList()
        val selector: (Memo) -> Long =
            when (filter.sortOption) {
                MemoSortOption.CREATED_TIME -> Memo::timestamp
                MemoSortOption.UPDATED_TIME -> Memo::updatedAt
            }
        return if (filter.sortAscending) filtered.sortedBy(selector) else filtered.sortedByDescending(selector)
    }
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
