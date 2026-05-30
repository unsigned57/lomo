package com.lomo.data.testing.fakes

import androidx.paging.PagingSource
import androidx.room3.RoomRawQuery
import com.lomo.data.local.dao.DateCountRow
import com.lomo.data.local.dao.DefaultMainListDao
import com.lomo.data.local.dao.DefaultMainListMemoRow
import com.lomo.data.local.dao.MemoBrowseDao
import com.lomo.data.local.dao.MemoDao
import com.lomo.data.local.dao.MemoPinDao
import com.lomo.data.local.dao.MemoRowIdBounds
import com.lomo.data.local.dao.MemoSearchDao
import com.lomo.data.local.dao.MemoStatisticsDao
import com.lomo.data.local.dao.MemoStatisticsProjectionRow
import com.lomo.data.local.dao.S3SyncMetadataDao
import com.lomo.data.local.dao.S3SyncPlannerMetadataSnapshot
import com.lomo.data.local.dao.S3SyncRemoteMetadataSnapshot
import com.lomo.data.local.dao.TagCountRow
import com.lomo.data.local.dao.WebDavSyncMetadataDao
import com.lomo.data.local.entity.MemoEntity
import com.lomo.data.local.entity.MemoPinEntity
import com.lomo.data.local.entity.S3SyncMetadataEntity
import com.lomo.data.local.entity.WebDavSyncMetadataEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

open class FakeMemoDao : MemoDao {
    var allMemosFlowResult: Flow<List<MemoEntity>> = flowOf(emptyList())
    var randomMemosResult: List<MemoEntity> = emptyList()
    var recentMemosResult: List<MemoEntity> = emptyList()
    var allMemoIdsResult: List<String> = emptyList()
    var memosByIdsResult: List<MemoEntity> = emptyList()
    var memoCountSyncResult: Int = 0
    val memoResultMap = mutableMapOf<String, MemoEntity>()
    var allMemosSyncResult: List<MemoEntity> = emptyList()
    val memosByDateResult = mutableMapOf<String, List<MemoEntity>>()
    val memosByDatesResult = mutableMapOf<List<String>, List<MemoEntity>>()

    override fun getAllMemosFlow(): Flow<List<MemoEntity>> = allMemosFlowResult
    override suspend fun getRandomMemoRowIdBounds(): MemoRowIdBounds =
        if (randomMemosResult.isEmpty()) {
            MemoRowIdBounds(minRowId = null, maxRowId = null, totalCount = 0)
        } else {
            MemoRowIdBounds(minRowId = 1L, maxRowId = randomMemosResult.size.toLong(), totalCount = randomMemosResult.size)
        }
    override suspend fun getRandomMemosFromRowIdFloor(
        rowIdFloor: Long,
        limit: Int,
    ): List<MemoEntity> =
        randomMemosResult
            .drop((rowIdFloor - 1).coerceAtLeast(0L).toInt())
            .take(limit)

    override suspend fun getRandomMemosBeforeRowIdFloor(
        rowIdFloor: Long,
        limit: Int,
    ): List<MemoEntity> =
        randomMemosResult
            .take((rowIdFloor - 1).coerceAtLeast(0L).toInt())
            .take(limit)

    override suspend fun getRecentMemos(limit: Int): List<MemoEntity> = recentMemosResult
    override suspend fun getAllMemoIds(): List<String> = allMemoIdsResult
    override suspend fun getMemosByIds(ids: List<String>): List<MemoEntity> = memosByIdsResult
    override suspend fun getMemoCountSync(): Int = memoCountSyncResult
    override suspend fun getMemo(id: String): MemoEntity? = memoResultMap[id]
    override suspend fun getAllMemosSync(): List<MemoEntity> = allMemosSyncResult
    override suspend fun getMemosByDate(date: String): List<MemoEntity> = memosByDateResult[date] ?: emptyList()
    override suspend fun getMemosByDates(dates: List<String>): List<MemoEntity> = memosByDatesResult[dates] ?: emptyList()
}

open class FakeMemoBrowseDao : MemoBrowseDao {
    var memosByTimestampRangeFlowResult: Flow<List<MemoEntity>> = flowOf(emptyList())
    var galleryMemosFlowResult: Flow<List<MemoEntity>> = flowOf(emptyList())

    override fun getMemosByTimestampRangeFlow(
        startTimestampInclusive: Long,
        endTimestampExclusive: Long,
    ): Flow<List<MemoEntity>> = memosByTimestampRangeFlowResult

    override fun getGalleryMemosFlow(): Flow<List<MemoEntity>> = galleryMemosFlowResult
}

open class FakeMemoPinDao : MemoPinDao {
    var pinnedMemoIdsFlowResult: Flow<List<String>> = flowOf(emptyList())
    var pinnedMemoIdsResult: List<String> = emptyList()
    val upsertedPins = mutableListOf<MemoPinEntity>()
    val deletedPins = mutableListOf<String>()

    override fun getPinnedMemoIdsFlow(): Flow<List<String>> = pinnedMemoIdsFlowResult
    override suspend fun getPinnedMemoIds(): List<String> = pinnedMemoIdsResult
    override suspend fun upsertMemoPin(pin: MemoPinEntity) {
        upsertedPins += pin
    }
    override suspend fun deleteMemoPin(memoId: String) {
        deletedPins += memoId
    }
}

open class FakeDefaultMainListDao : DefaultMainListDao {
    data class DailyReviewCandidatePageCall(
        val maxRowId: Long,
        val cursorIsPinned: Boolean?,
        val cursorTimestamp: Long?,
        val cursorId: String?,
        val limit: Int,
    )

    var pagingSourceRawResult: PagingSource<Int, DefaultMainListMemoRow>? = null
    var countFlowRawResult: Flow<Int> = flowOf(0)
    var getCountFlowResult: Flow<Int>? = null
    var pageResult: List<DefaultMainListMemoRow> = emptyList()
    var dailyReviewCandidateMaxRowIdResult: Long? = null
    var dailyReviewCandidateCountResult: Int = 0
    var dailyReviewCandidatePageResult: List<DefaultMainListMemoRow> = emptyList()
    var dailyReviewCandidatePageHandler: ((DailyReviewCandidatePageCall) -> List<DefaultMainListMemoRow>)? = null
    var defaultMainListHeadIdsResult: List<String> = emptyList()
    var dailyReviewCandidateMaxRowIdCallCount: Int = 0
    val dailyReviewCandidateCountCalls = mutableListOf<Long>()
    val dailyReviewCandidatePageCalls = mutableListOf<DailyReviewCandidatePageCall>()
    val defaultMainListHeadIdCalls = mutableListOf<Int>()

    override fun getPagingSourceRaw(query: RoomRawQuery): PagingSource<Int, DefaultMainListMemoRow> =
        pagingSourceRawResult ?: object : PagingSource<Int, DefaultMainListMemoRow>() {
            override suspend fun load(params: LoadParams<Int>): LoadResult<Int, DefaultMainListMemoRow> =
                LoadResult.Page(emptyList(), null, null)
            override fun getRefreshKey(state: androidx.paging.PagingState<Int, DefaultMainListMemoRow>): Int? = null
        }

    override fun getCountFlowRaw(query: RoomRawQuery): Flow<Int> = countFlowRawResult

    override fun getCountFlow(
        query: String,
        startDate: String?,
        endDate: String?,
        sortOption: String,
        sortAscending: Boolean,
        hasTodo: Boolean?,
        hasAttachment: Boolean?,
        hasUrl: Boolean?,
    ): Flow<Int> {
        return getCountFlowResult ?: super.getCountFlow(query, startDate, endDate, sortOption, sortAscending, hasTodo, hasAttachment, hasUrl)
    }

    var getPagingSourceResult: PagingSource<Int, DefaultMainListMemoRow>? = null

    override fun getPagingSource(
        query: String,
        startDate: String?,
        endDate: String?,
        sortOption: String,
        sortAscending: Boolean,
        hasTodo: Boolean?,
        hasAttachment: Boolean?,
        hasUrl: Boolean?,
    ): PagingSource<Int, DefaultMainListMemoRow> {
        return getPagingSourceResult ?: super.getPagingSource(query, startDate, endDate, sortOption, sortAscending, hasTodo, hasAttachment, hasUrl)
    }

    override suspend fun getPage(limit: Int, offset: Int): List<DefaultMainListMemoRow> = pageResult
    override suspend fun getDailyReviewCandidateMaxRowId(): Long? {
        dailyReviewCandidateMaxRowIdCallCount += 1
        return dailyReviewCandidateMaxRowIdResult
    }
    override suspend fun getDailyReviewCandidateCount(maxRowId: Long): Int {
        dailyReviewCandidateCountCalls += maxRowId
        return dailyReviewCandidateCountResult
    }
    override suspend fun getDailyReviewCandidatePage(
        maxRowId: Long,
        cursorIsPinned: Boolean?,
        cursorTimestamp: Long?,
        cursorId: String?,
        limit: Int,
    ): List<DefaultMainListMemoRow> {
        val call =
            DailyReviewCandidatePageCall(
                maxRowId = maxRowId,
                cursorIsPinned = cursorIsPinned,
                cursorTimestamp = cursorTimestamp,
                cursorId = cursorId,
                limit = limit,
            )
        dailyReviewCandidatePageCalls += call
        return dailyReviewCandidatePageHandler?.invoke(call) ?: dailyReviewCandidatePageResult
    }
    override suspend fun getDefaultMainListHeadIds(limit: Int): List<String> {
        defaultMainListHeadIdCalls += limit
        return defaultMainListHeadIdsResult.take(limit.coerceAtLeast(0))
    }
}

open class FakeMemoSearchDao :
    MemoSearchDao,
    MemoStatisticsDao {
    data class TagPageCall(
        val tag: String,
        val tagPrefix: String,
        val limit: Int,
        val offset: Int,
    )

    var memosByTagPageResult: List<MemoEntity> = emptyList()
    var memosByTagPagingRows: List<DefaultMainListMemoRow> = emptyList()
    var memosByTagPageFlowResult: Flow<List<MemoEntity>> = flowOf(emptyList())
    var allTagsFlowResult: Flow<List<String>> = flowOf(emptyList())
    var tagCountsFlowResult: Flow<List<TagCountRow>> = flowOf(emptyList())
    var tagCountsResult: List<TagCountRow> = emptyList()
    var memoCountResult: Flow<Int> = flowOf(0)
    var activeDayCountResult: Flow<Int> = flowOf(0)
    var allTimestampsResult: Flow<List<Long>> = flowOf(emptyList())
    var memoCountByDateFlowResult: Flow<List<DateCountRow>> = flowOf(emptyList())
    var memoStatisticsProjectionResult: List<MemoStatisticsProjectionRow> = emptyList()
    var countMemosAndTrashWithImageResult: Int = 0

    val getMemosByTagPageCalls = mutableListOf<TagPageCall>()
    val getMemosByTagPageFlowCalls = mutableListOf<TagPageCall>()

    override suspend fun getMemosByTagPage(
        tag: String,
        tagPrefix: String,
        limit: Int,
        offset: Int,
    ): List<MemoEntity> {
        getMemosByTagPageCalls += TagPageCall(tag = tag, tagPrefix = tagPrefix, limit = limit, offset = offset)
        return memosByTagPageResult
    }
    override fun getMemosByTagPageFlow(
        tag: String,
        tagPrefix: String,
        limit: Int,
        offset: Int,
    ): Flow<List<MemoEntity>> {
        getMemosByTagPageFlowCalls += TagPageCall(tag = tag, tagPrefix = tagPrefix, limit = limit, offset = offset)
        return memosByTagPageFlowResult
    }
    override fun getMemosByTagPagingSource(
        tag: String,
        tagPrefix: String,
    ): PagingSource<Int, DefaultMainListMemoRow> =
        object : PagingSource<Int, DefaultMainListMemoRow>() {
            override suspend fun load(params: LoadParams<Int>): LoadResult<Int, DefaultMainListMemoRow> {
                val offset = params.key ?: 0
                val rows = memosByTagPagingRows.drop(offset).take(params.loadSize)
                return LoadResult.Page(
                    data = rows,
                    prevKey = null,
                    nextKey = if (rows.size < params.loadSize) null else offset + rows.size,
                )
            }

            override fun getRefreshKey(state: androidx.paging.PagingState<Int, DefaultMainListMemoRow>): Int? =
                state.anchorPosition
        }
    override fun getAllTagsFlow(): Flow<List<String>> = allTagsFlowResult
    override fun getTagCountsFlow(): Flow<List<TagCountRow>> = tagCountsFlowResult
    override suspend fun getTagCounts(): List<TagCountRow> = tagCountsResult
    override fun getMemoCount(): Flow<Int> = memoCountResult
    override fun getActiveDayCount(): Flow<Int> = activeDayCountResult
    override fun getAllTimestamps(): Flow<List<Long>> = allTimestampsResult
    override fun getMemoCountByDateFlow(): Flow<List<DateCountRow>> = memoCountByDateFlowResult
    override suspend fun getMemoStatisticsProjection(): List<MemoStatisticsProjectionRow> = memoStatisticsProjectionResult
    override suspend fun countMemosAndTrashWithImage(imagePath: String, excludeId: String): Int = countMemosAndTrashWithImageResult
}

open class FakeS3SyncMetadataDao : S3SyncMetadataDao {
    var allEntities = mutableListOf<S3SyncMetadataEntity>()
    var plannerSnapshots = mutableListOf<S3SyncPlannerMetadataSnapshot>()
    var remoteSnapshots = mutableListOf<S3SyncRemoteMetadataSnapshot>()
    val deletedRelativePaths = mutableListOf<String>()

    override suspend fun getAll(): List<S3SyncMetadataEntity> = allEntities
    override suspend fun getAllPlannerMetadataSnapshots(): List<S3SyncPlannerMetadataSnapshot> = plannerSnapshots
    override suspend fun getAllRemoteMetadataSnapshots(): List<S3SyncRemoteMetadataSnapshot> = remoteSnapshots

    override suspend fun getByRelativePaths(relativePaths: List<String>): List<S3SyncMetadataEntity> {
        return allEntities.filter { it.relativePath in relativePaths }
    }

    override suspend fun upsertAll(entities: List<S3SyncMetadataEntity>) {
        entities.forEach { entity ->
            allEntities.removeAll { it.relativePath == entity.relativePath }
            allEntities.add(entity)
        }
    }

    override suspend fun deleteByRelativePath(relativePath: String) {
        deletedRelativePaths += relativePath
        allEntities.removeAll { it.relativePath == relativePath }
    }

    override suspend fun deleteByRelativePaths(relativePaths: List<String>) {
        deletedRelativePaths += relativePaths
        allEntities.removeAll { it.relativePath in relativePaths }
    }

    override suspend fun clearAll() {
        allEntities.clear()
        plannerSnapshots.clear()
        remoteSnapshots.clear()
    }
}

open class FakeWebDavSyncMetadataDao : WebDavSyncMetadataDao {
    var allEntities = mutableListOf<WebDavSyncMetadataEntity>()
    val deletedRelativePaths = mutableListOf<String>()

    override suspend fun getAll(): List<WebDavSyncMetadataEntity> = allEntities

    override suspend fun getByRelativePaths(relativePaths: List<String>): List<WebDavSyncMetadataEntity> {
        return allEntities.filter { it.relativePath in relativePaths }
    }

    override suspend fun upsertAll(entities: List<WebDavSyncMetadataEntity>) {
        entities.forEach { entity ->
            allEntities.removeAll { it.relativePath == entity.relativePath }
            allEntities.add(entity)
        }
    }

    override suspend fun deleteByRelativePath(relativePath: String) {
        deletedRelativePaths += relativePath
        allEntities.removeAll { it.relativePath == relativePath }
    }

    override suspend fun deleteByRelativePaths(relativePaths: List<String>) {
        deletedRelativePaths += relativePaths
        allEntities.removeAll { it.relativePath in relativePaths }
    }

    override suspend fun clearAll() {
        allEntities.clear()
    }
}
