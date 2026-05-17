package com.lomo.app.testing.fakes

import androidx.paging.PagingSource
import androidx.paging.PagingSource.LoadParams
import androidx.paging.PagingSource.LoadResult
import androidx.paging.PagingState
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoListFilter
import com.lomo.domain.model.MemoSortOption
import com.lomo.domain.model.MemoTagCount
import com.lomo.domain.repository.MemoRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/**
 * Hand-written in-memory fake for [MemoRepository] used by app/test ViewModel specs.
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
class FakeMemoRepository(
    initialActive: List<Memo> = emptyList(),
    initialDeleted: List<Memo> = emptyList(),
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) : MemoRepository {
    private val activeMemos = MutableStateFlow(initialActive)
    private val deletedMemos = MutableStateFlow(initialDeleted)
    private val syncing = MutableStateFlow(false)

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

    fun setActiveMemos(memos: List<Memo>) {
        activeMemos.value = memos
    }

    fun setDeletedMemos(memos: List<Memo>) {
        deletedMemos.value = memos
    }

    fun setSyncing(value: Boolean) {
        syncing.value = value
    }

    override fun getAllMemosList(): Flow<List<Memo>> = activeMemos.asStateFlow()

    override fun getMemosByDateRange(
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

    override fun getGalleryMemosList(): Flow<List<Memo>> =
        activeMemos.map { memos -> memos.filter { it.imageUrls.isNotEmpty() } }

    override suspend fun getRecentMemos(limit: Int): List<Memo> =
        activeMemos.value.sortedByDescending(Memo::timestamp).take(limit)

    override suspend fun getMemoCount(): Int = activeMemos.value.size

    override fun getMainListPagingSource(
        query: String,
        filter: MemoListFilter,
    ): PagingSource<Int, Memo> = InMemoryMemoPagingSource(activeMemos.value.matching(query, filter))

    override fun getMainListCountFlow(
        query: String,
        filter: MemoListFilter,
    ): Flow<Int> = activeMemos.map { memos -> memos.matching(query, filter).size }

    override fun isSyncing(): Flow<Boolean> = syncing.asStateFlow()

    override suspend fun refreshMemos() {
        refreshMemosCallCount += 1
    }

    override suspend fun saveMemo(
        content: String,
        timestamp: Long,
        geoLocation: String?,
    ) {
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
    }

    override suspend fun updateMemo(
        memo: Memo,
        newContent: String,
    ) {
        updateMemoCallCount += 1
        activeMemos.value =
            activeMemos.value.map { existing ->
                if (existing.id == memo.id) existing.copy(content = newContent, rawContent = newContent) else existing
            }
    }

    override suspend fun deleteMemo(memo: Memo) {
        deleteMemoCallCount += 1
        activeMemos.value = activeMemos.value.filterNot { it.id == memo.id }
        deletedMemos.value = deletedMemos.value + memo.copy(isDeleted = true)
    }

    override suspend fun setMemoPinned(
        memoId: String,
        pinned: Boolean,
    ) {
        activeMemos.value =
            activeMemos.value.map { memo -> if (memo.id == memoId) memo.copy(isPinned = pinned) else memo }
    }

    override fun searchMemosList(query: String): Flow<List<Memo>> =
        activeMemos.map { memos -> memos.filter { it.content.contains(query, ignoreCase = true) } }

    override fun getMemosByTagList(tag: String): Flow<List<Memo>> =
        activeMemos.map { memos -> memos.filter { tag in it.tags } }

    override fun getMemoCountFlow(): Flow<Int> = activeMemos.map { it.size }

    override fun getMemoTimestampsFlow(): Flow<List<Long>> = activeMemos.map { memos -> memos.map(Memo::timestamp) }

    override fun getMemoCountByDateFlow(): Flow<Map<String, Int>> =
        activeMemos.map { memos -> memos.groupingBy(Memo::dateKey).eachCount() }

    override fun getTagCountsFlow(): Flow<List<MemoTagCount>> =
        activeMemos.map { memos ->
            memos
                .flatMap(Memo::tags)
                .groupingBy { it }
                .eachCount()
                .map { (tag, count) -> MemoTagCount(tag, count) }
        }

    override fun getActiveDayCount(): Flow<Int> = activeMemos.map { memos -> memos.mapNotNull(Memo::localDate).toSet().size }

    override fun getDeletedMemosList(): Flow<List<Memo>> = deletedMemos.asStateFlow()

    override suspend fun restoreMemo(memo: Memo) {
        deletedMemos.value = deletedMemos.value.filterNot { it.id == memo.id }
        activeMemos.value = activeMemos.value + memo.copy(isDeleted = false)
    }

    override suspend fun deletePermanently(memo: Memo) {
        deletedMemos.value = deletedMemos.value.filterNot { it.id == memo.id }
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
                .filter { memo ->
                    query.isBlank() || memo.content.contains(query, ignoreCase = true)
                }.filter { memo ->
                    val date = memo.localDate
                    when {
                        filter.startDate != null && (date == null || date.isBefore(filter.startDate)) -> false
                        filter.endDate != null && (date == null || date.isAfter(filter.endDate)) -> false
                        else -> true
                    }
                }.toList()
        val keySelector: (Memo) -> Long =
            when (filter.sortOption) {
                MemoSortOption.CREATED_TIME -> Memo::timestamp
                MemoSortOption.UPDATED_TIME -> Memo::updatedAt
            }
        return if (filter.sortAscending) {
            filtered.sortedBy(keySelector)
        } else {
            filtered.sortedByDescending(keySelector)
        }
    }
}

private class InMemoryMemoPagingSource(
    private val snapshot: List<Memo>,
) : PagingSource<Int, Memo>() {
    override fun getRefreshKey(state: PagingState<Int, Memo>): Int? = state.anchorPosition

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Memo> {
        val start = (params.key ?: 0).coerceAtLeast(0)
        val end = (start + params.loadSize).coerceAtMost(snapshot.size)
        val slice = if (start >= snapshot.size) emptyList() else snapshot.subList(start, end)
        val prevKey = if (start == 0) null else (start - params.loadSize).coerceAtLeast(0)
        val nextKey = if (end >= snapshot.size) null else end
        return LoadResult.Page(slice, prevKey, nextKey)
    }
}
